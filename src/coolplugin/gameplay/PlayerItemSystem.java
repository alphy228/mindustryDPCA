package coolplugin.gameplay;

import arc.Events;
import arc.files.Fi;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Time;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.HaloAnchor;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.MountSubtype;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.StationTier;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.entities.units.StatusEntry;
import mindustry.entities.units.WeaponMount;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.gen.WorldLabel;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.type.UnitType.UnitEngine;

import static mindustry.Vars.*;

/**
 * Manages player equipable items (up to 10 inventory slots), crafting, decrafting,
 * Bazaar orbital ship trades, passive stat bonuses (positions 1–4), overburden speed penalty
 * ({@code -10%} movement speed per item after the 4th, with no passive buffs), and 4-mode
 * world item systems ({@code MOUNT}, {@code HALO}, {@code FOLLOW}, {@code SUBWEAPON} — {@code ORBIT} removed):
 * <ul>
 *   <li>{@link AttachMode#MOUNT}: attaches flush to the unit hull sprite with {@link MountSubtype}s;
 *       automatically duplicates into a symmetrical Left + Right pair whenever placed off the symmetry axis.</li>
 *   <li>{@link AttachMode#HALO}: renders geometric {@code HaloPart} halos + configurable {@link Effect}s
 *       on the center of the unit, its weapons, or its engines.</li>
 *   <li>{@link AttachMode#FOLLOW}: floating companion that smoothly follows the unit and maintains
 *       its configured standoff distance.</li>
 *   <li>{@link AttachMode#SUBWEAPON}: becomes an active rotating, recoiling, firing weapon on the unit
 *       (symmetrically duplicated when placed off the symmetry axis).</li>
 * </ul>
 */
public class PlayerItemSystem {

    public static final int MAX_EQUIPPED_SLOTS = 10;
    public static final int ACTIVE_PASSIVE_SLOTS = 4;
    public static final float OVERLOAD_SPEED_PENALTY_PER_ITEM = 0.10f;

    public record ActionOutcome(boolean success, String message) {}

    public static final class ComputedStats {
        public int totalEquipped;
        public int activeSlotsUsed;
        public int overloadCount;

        public float healthMult = 1f;
        public float damageMult = 1f;
        public float speedMult = 1f;
        public float reloadMult = 1f;
        public float buildSpeedMult = 1f;
        public float armorBonus = 0f;
        public float regenPerSec = 0f;

        public boolean isNeutral() {
            return totalEquipped == 0
                && Mathf.equal(healthMult, 1f)
                && Mathf.equal(damageMult, 1f)
                && Mathf.equal(speedMult, 1f)
                && Mathf.equal(reloadMult, 1f)
                && Mathf.equal(buildSpeedMult, 1f)
                && Mathf.equal(armorBonus, 0f)
                && Mathf.equal(regenPerSec, 0f);
        }
    }

    private static final class InertVisualAI extends AIController {
        @Override
        public void updateUnit() {
            // Driven strictly by PlayerItemSystem.updateVisualRig
        }
    }

    /**
     * AI controller for {@link AttachMode#SUBWEAPON} carrier units: aims &amp; fires alongside
     * the host unit when shooting, or auto-targets nearby enemies within weapon range.
     */
    private static final class SubweaponAI extends AIController {
        @Nullable Unit host;
        boolean activeWeapon;

        SubweaponAI(@Nullable Unit host, boolean activeWeapon) {
            this.host = host;
            this.activeWeapon = activeWeapon;
        }

        @Override
        public void updateUnit() {
            if (unit == null) return;
            if (!activeWeapon || host == null || host.dead() || !host.isValid()) {
                stopShooting();
                unit.isShooting = false;
                return;
            }

            if (host.isShooting) {
                driveMounts(host.aimX, host.aimY, true);
                return;
            }

            float maxRange = Math.max(80f, unit.range());
            if (timer.get(timerTarget, 15f)) {
                target = Units.closestTarget(
                    host.team,
                    unit.x,
                    unit.y,
                    maxRange,
                    u -> u.checkTarget(true, true) && !u.type.hidden,
                    b -> true
                );
            }

            if (target != null && unit.within(target, maxRange + 8f)) {
                driveMounts(target.x(), target.y(), true);
            } else {
                float idleX = (host.aimX == 0f && host.aimY == 0f)
                    ? unit.x + Angles.trnsx(host.rotation, 48f)
                    : host.aimX;
                float idleY = (host.aimX == 0f && host.aimY == 0f)
                    ? unit.y + Angles.trnsy(host.rotation, 48f)
                    : host.aimY;
                driveMounts(idleX, idleY, false);
            }
        }

        private void driveMounts(float ax, float ay, boolean shoot) {
            unit.aimX = ax;
            unit.aimY = ay;
            unit.isShooting = shoot;
            if (unit.mounts != null) {
                for (WeaponMount m : unit.mounts) {
                    m.aimX = ax;
                    m.aimY = ay;
                    m.shoot = shoot;
                    m.rotate = true;
                }
            }
        }
    }

    private static final class VisualInstance {
        final ShopItemDef def;
        final AttachMode mode;
        final boolean overloaded;
        /** +1 left, -1 right for mirrored off-axis mounts/subweapons; 0 for on-axis / follow / halo. */
        final float symmetrySideSign;
        final int subtypeRepeatIndex;
        final int anchorIndex;
        final int modeIndex;
        final int modeTotal;
        @Nullable Unit spriteUnit;
        @Nullable WorldLabel fallbackLabel;
        float worldX;
        float worldY;
        float fxTimer;

        VisualInstance(
            ShopItemDef def,
            AttachMode mode,
            boolean overloaded,
            float symmetrySideSign,
            int subtypeRepeatIndex,
            int anchorIndex,
            int modeIndex,
            int modeTotal,
            @Nullable Unit spriteUnit,
            @Nullable WorldLabel fallbackLabel,
            float wx,
            float wy
        ) {
            this.def = def;
            this.mode = mode;
            this.overloaded = overloaded;
            this.symmetrySideSign = symmetrySideSign;
            this.subtypeRepeatIndex = subtypeRepeatIndex;
            this.anchorIndex = anchorIndex;
            this.modeIndex = modeIndex;
            this.modeTotal = modeTotal;
            this.spriteUnit = spriteUnit;
            this.fallbackLabel = fallbackLabel;
            this.worldX = wx;
            this.worldY = wy;
            this.fxTimer = (modeIndex * 7f) % Math.max(8f, def.haloInterval);
        }

        boolean isAliveInWorld() {
            if (spriteUnit != null) return spriteUnit.isAdded() && !spriteUnit.dead();
            if (fallbackLabel != null) return fallbackLabel.isAdded();
            return false;
        }

        void removeFromWorld() {
            if (spriteUnit != null) {
                try {
                    spriteUnit.remove();
                } catch (Throwable ignored) {
                }
                spriteUnit = null;
            }
            if (fallbackLabel != null) {
                try {
                    fallbackLabel.hide();
                } catch (Throwable ignored) {
                    fallbackLabel.remove();
                }
                fallbackLabel = null;
            }
        }
    }

    private static final class VisualRig {
        int unitId = -1;
        int teamId = -1;
        String signature = "";
        final Seq<VisualInstance> instances = new Seq<>();
    }

    private final Fi shopConfigFile;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final BuildSystem buildSystem;

    private Catalog catalog;
    private final ObjectMap<String, VisualRig> activeRigs = new ObjectMap<>();
    private final Seq<Vec2> tmpAnchors = new Seq<>();
    private boolean started = false;

    public PlayerItemSystem(
        Fi alphyDir,
        PlayerDatabase database,
        PlayerSessionManager sessionManager,
        BuildSystem buildSystem
    ) {
        this.shopConfigFile = alphyDir.child("shop.hjson");
        this.database = database;
        this.sessionManager = sessionManager;
        this.buildSystem = buildSystem;
    }

    public void start() {
        reloadCatalog();
        if (started) return;
        started = true;

        Events.on(WorldLoadEvent.class, e -> clearAllRigs());
        Events.on(PlayerLeave.class, e -> {
            if (e.player != null) {
                destroyRig(e.player.uuid());
            }
        });

        Events.run(Trigger.update, this::updateUnitsAndVisuals);
    }

    public synchronized void reloadCatalog() {
        this.catalog = ShopConfigIO.loadOrCreate(shopConfigFile);
    }

    public Catalog getCatalog() {
        if (catalog == null) reloadCatalog();
        return catalog;
    }

    // ------------------------------------------------------------------ inventory & stat computation

    public EquipmentData getEquipment(Player player) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        EquipmentData data = database.getEquipmentData(acc.id, factionId);

        boolean dirty = false;
        if (!data.stash.isEmpty()) {
            Seq<String> keys = data.stash.keys().toSeq();
            for (String k : keys) {
                int cnt = data.stash.get(k, 0);
                while (cnt > 0 && data.equipped.size < MAX_EQUIPPED_SLOTS) {
                    data.equipped.add(k);
                    cnt--;
                    dirty = true;
                }
            }
            data.stash.clear();
            dirty = true;
        }
        while (data.equipped.size > MAX_EQUIPPED_SLOTS) {
            data.equipped.pop();
            dirty = true;
        }
        if (dirty) {
            database.saveEquipmentData(acc.id, factionId, data);
        }
        return data;
    }

    public void saveEquipment(Player player, EquipmentData data) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        data.stash.clear();
        while (data.equipped.size > MAX_EQUIPPED_SLOTS) {
            data.equipped.pop();
        }
        database.saveEquipmentData(acc.id, factionId, data);
    }

    public void onPlayerDataWiped(@Nullable Player player) {
        if (player == null) return;
        destroyRig(player.uuid());
        Unit unit = PlayerUnitSystem.ownedUnit(player);
        if (unit == null) unit = player.unit();
        if (unit != null && unit.hasEffect(StatusEffects.dynamic)) {
            unit.unapply(StatusEffects.dynamic);
        }
    }

    public int countOwnedTotal(EquipmentData data, String itemId) {
        int count = 0;
        for (int i = 0; i < data.equipped.size; i++) {
            if (itemId.equals(data.equipped.get(i))) count++;
        }
        return count;
    }

    public ComputedStats computeStats(Seq<String> equippedIds) {
        ComputedStats st = new ComputedStats();
        if (equippedIds == null || equippedIds.isEmpty()) return st;

        Catalog cat = getCatalog();
        int n = Math.min(MAX_EQUIPPED_SLOTS, equippedIds.size);
        st.totalEquipped = n;
        st.activeSlotsUsed = Math.min(ACTIVE_PASSIVE_SLOTS, n);
        st.overloadCount = Math.max(0, n - ACTIVE_PASSIVE_SLOTS);

        float hpBonus = 0f;
        float dmgBonus = 0f;
        float spdBonus = 0f;
        float rldBonus = 0f;
        float bldBonus = 0f;

        for (int i = 0; i < st.activeSlotsUsed; i++) {
            ShopItemDef def = cat.findItem(equippedIds.get(i));
            if (def == null) continue;
            hpBonus += def.healthMultBonus;
            dmgBonus += def.damageMultBonus;
            spdBonus += def.speedMultBonus;
            rldBonus += def.reloadMultBonus;
            bldBonus += def.buildSpeedMultBonus;
            st.armorBonus += def.armorBonus;
            st.regenPerSec += def.regenPerSec;
        }

        float overloadSpeedFactor = Math.max(0.10f, 1f - OVERLOAD_SPEED_PENALTY_PER_ITEM * st.overloadCount);

        st.healthMult = Math.max(0.25f, 1f + hpBonus);
        st.damageMult = Math.max(0.10f, 1f + dmgBonus);
        st.speedMult = Math.max(0.10f, (1f + spdBonus) * overloadSpeedFactor);
        st.reloadMult = Math.max(0.10f, 1f + rldBonus);
        st.buildSpeedMult = Math.max(0.10f, 1f + bldBonus);
        return st;
    }

    public ActionOutcome promoteSlotToActive(Player player, int slotIndex) {
        EquipmentData data = getEquipment(player);
        if (slotIndex < 0 || slotIndex >= data.equipped.size) {
            return new ActionOutcome(false, "[scarlet]Empty inventory slot.[]");
        }
        if (slotIndex == 0) {
            return new ActionOutcome(true, "");
        }
        String moved = data.equipped.remove(slotIndex);
        data.equipped.insert(0, moved);
        saveEquipment(player, data);
        ShopItemDef def = getCatalog().findItem(moved);
        return new ActionOutcome(true, "[stat]Moved " + (def != null ? def.name : moved) + " to top of inventory.[]");
    }

    public ActionOutcome discardAtSlot(Player player, int slotIndex) {
        EquipmentData data = getEquipment(player);
        if (slotIndex < 0 || slotIndex >= data.equipped.size) {
            return new ActionOutcome(false, "[scarlet]Empty inventory slot.[]");
        }
        String removed = data.equipped.remove(slotIndex);
        saveEquipment(player, data);
        ShopItemDef def = getCatalog().findItem(removed);
        return new ActionOutcome(true, "[lightgray]Discarded " + (def != null ? def.name : removed) + ".[]");
    }

    // ------------------------------------------------------------------ crafting, decrafting, orbital trades

    public boolean isStationAllowedToCraft(StationTier station, ShopItemDef item) {
        if (!item.craftable) return false;
        return switch (station) {
            case WORKBENCH -> item.tier == StationTier.WORKBENCH;
            case BAZAAR -> item.tier == StationTier.WORKBENCH || item.tier == StationTier.BAZAAR;
            case CURSED_ALTAR -> item.tier == StationTier.CURSED_ALTAR;
        };
    }

    public boolean canAffordCraft(Player player, ShopItemDef item, EquipmentData data) {
        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem == null || buildSystem.getAvailableAmount(player, mItem) < rc.amount()) {
                return false;
            }
        }
        for (ItemIngredient ing : item.itemCosts) {
            if (countOwnedTotal(data, ing.shopItemId()) < ing.amount()) {
                return false;
            }
        }
        return true;
    }

    public ActionOutcome craft(Player player, String itemId, StationTier station) {
        ShopItemDef item = getCatalog().findItem(itemId);
        if (item == null) return new ActionOutcome(false, "[scarlet]Item not found.[]");
        if (!item.craftable) {
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be crafted (decraft only).[]");
        }
        if (!isStationAllowedToCraft(station, item)) {
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be crafted at this station.[]");
        }

        EquipmentData data = getEquipment(player);
        if (!canAffordCraft(player, item, data)) {
            return new ActionOutcome(false, "[scarlet]Missing required resources or ingredient items.[]");
        }

        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem != null) {
                buildSystem.takeItems(player, mItem, rc.amount());
            }
        }

        for (ItemIngredient ing : item.itemCosts) {
            consumeOwnedItem(data, ing.shopItemId(), ing.amount());
        }

        boolean stored = grantCraftedOrTradedItem(data, item);
        saveEquipment(player, data);
        if (!stored) {
            return new ActionOutcome(true, "[scarlet]Inventory full (10/10) - crafted " + item.name + " vanished![]");
        }
        return new ActionOutcome(true, "[stat]Crafted [white]" + item.name + "[stat].[]");
    }

    public ActionOutcome decraftAtSlot(Player player, int slotIndex, StationTier station) {
        if (station == StationTier.CURSED_ALTAR) {
            return new ActionOutcome(false, "[scarlet]Cursed Altar does not perform decrafting.[]");
        }
        EquipmentData data = getEquipment(player);
        if (slotIndex < 0 || slotIndex >= data.equipped.size) {
            return new ActionOutcome(false, "[scarlet]Empty inventory slot.[]");
        }
        String itemId = data.equipped.get(slotIndex);
        ShopItemDef item = getCatalog().findItem(itemId);
        if (item == null) return new ActionOutcome(false, "[scarlet]Item not found.[]");
        if (!item.decraftable || !item.hasRecipe()) {
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be decrafted.[]");
        }

        data.equipped.remove(slotIndex);
        return finishDecraft(player, data, item);
    }

    public ActionOutcome decraft(Player player, String itemId, StationTier station) {
        if (station == StationTier.CURSED_ALTAR) {
            return new ActionOutcome(false, "[scarlet]Cursed Altar does not perform decrafting.[]");
        }
        ShopItemDef item = getCatalog().findItem(itemId);
        if (item == null) return new ActionOutcome(false, "[scarlet]Item not found.[]");
        if (!item.decraftable || !item.hasRecipe()) {
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be decrafted.[]");
        }

        EquipmentData data = getEquipment(player);
        if (countOwnedTotal(data, itemId) <= 0) {
            return new ActionOutcome(false, "[scarlet]You do not own " + item.name + ".[]");
        }

        consumeOwnedItem(data, itemId, 1);
        return finishDecraft(player, data, item);
    }

    private ActionOutcome finishDecraft(Player player, EquipmentData data, ShopItemDef item) {
        int vanishedSubItems = 0;
        for (ItemIngredient ing : item.itemCosts) {
            ShopItemDef subDef = getCatalog().findItem(ing.shopItemId());
            for (int c = 0; c < ing.amount(); c++) {
                if (subDef != null && data.equipped.size < MAX_EQUIPPED_SLOTS) {
                    data.equipped.add(subDef.id);
                } else {
                    vanishedSubItems++;
                }
            }
        }
        saveEquipment(player, data);

        int totalReturned = 0;
        int totalVanished = 0;
        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem == null || rc.amount() <= 0) continue;
            int deposited = buildSystem.giveItems(player, mItem, rc.amount());
            totalReturned += deposited;
            totalVanished += Math.max(0, rc.amount() - deposited);
        }

        if (totalVanished > 0 || vanishedSubItems > 0) {
            return new ActionOutcome(true,
                "[#ffbe76]Decrafted [white]" + item.name + "[#ffbe76]: stored " + totalReturned
                    + " ([scarlet]" + totalVanished + " vanished - insufficient storage[]).[]");
        }
        return new ActionOutcome(true,
            "[stat]Decrafted [white]" + item.name + "[stat] -> +" + totalReturned + " stored.[]");
    }

    public boolean canAffordOrbitalTrade(Player player, OrbitalTradeDef trade) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        for (ResourceCost rc : trade.orbitalCosts) {
            if (database.getOrbitalItemAmount(acc.id, factionId, rc.itemName()) < rc.amount()) {
                return false;
            }
        }
        return true;
    }

    public ActionOutcome executeOrbitalTrade(Player player, String tradeId) {
        OrbitalTradeDef trade = getCatalog().findTrade(tradeId);
        if (trade == null) return new ActionOutcome(false, "[scarlet]Unknown orbital trade.[]");
        if (!canAffordOrbitalTrade(player, trade)) {
            return new ActionOutcome(false, "[scarlet]Insufficient resources in persistent Orbital Storage.[]");
        }

        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        for (ResourceCost rc : trade.orbitalCosts) {
            database.takeOrbitalItem(acc.id, factionId, rc.itemName(), rc.amount());
        }

        StringBuilder outcomeMsg = new StringBuilder("[#7fe6ff]Traded with [white]").append(trade.shipName).append("[#7fe6ff]: ");
        if (trade.rewardShopItemId != null) {
            ShopItemDef rewardItem = getCatalog().findItem(trade.rewardShopItemId);
            if (rewardItem != null) {
                EquipmentData data = getEquipment(player);
                boolean stored = grantCraftedOrTradedItem(data, rewardItem);
                saveEquipment(player, data);
                if (stored) {
                    outcomeMsg.append("received [white]").append(rewardItem.name).append("[]");
                } else {
                    outcomeMsg.append("[scarlet]inventory full - ").append(rewardItem.name).append(" vanished![]");
                }
            }
        }

        if (!trade.rewardMapResources.isEmpty()) {
            int stored = 0;
            int vanished = 0;
            for (ResourceCost rc : trade.rewardMapResources) {
                Item mItem = rc.resolveItem();
                if (mItem == null) continue;
                int dep = buildSystem.giveItems(player, mItem, rc.amount());
                stored += dep;
                vanished += Math.max(0, rc.amount() - dep);
            }
            outcomeMsg.append("dropped ").append(stored).append(" into storage");
            if (vanished > 0) {
                outcomeMsg.append(" ([scarlet]").append(vanished).append(" vanished[])");
            }
        }
        return new ActionOutcome(true, outcomeMsg.toString());
    }

    private static boolean grantCraftedOrTradedItem(EquipmentData data, ShopItemDef item) {
        if (data.equipped.size < MAX_EQUIPPED_SLOTS) {
            data.equipped.add(item.id);
            return true;
        }
        return false;
    }

    private static void consumeOwnedItem(EquipmentData data, String itemId, int amount) {
        int remaining = amount;
        for (int i = data.equipped.size - 1; i >= 0 && remaining > 0; i--) {
            if (itemId.equals(data.equipped.get(i))) {
                data.equipped.remove(i);
                remaining--;
            }
        }
    }

    // ------------------------------------------------------------------ live unit stats & 4-mode world item system

    public void updateUnitsAndVisuals() {
        if (state == null || !state.isGame() || state.isPaused()) return;
        if (Groups.player == null) return;

        for (Player player : Groups.player) {
            if (player == null || player.con == null || !player.con.isConnected()) continue;

            Unit unit = PlayerUnitSystem.ownedUnit(player);
            if (unit == null) unit = player.unit();

            if (unit == null || unit.dead() || !unit.isValid()) {
                destroyRig(player.uuid());
                continue;
            }

            EquipmentData eq = getEquipment(player);
            ComputedStats stats = computeStats(eq.equipped);
            applyStatsToUnit(unit, stats);
            updateVisualRig(player.uuid(), unit, eq.equipped, stats);
        }
    }

    public void applyStatsToUnit(Unit unit, ComputedStats stats) {
        if (unit == null || unit.dead()) return;

        if (stats.isNeutral()) {
            if (unit.hasEffect(StatusEffects.dynamic)) {
                unit.unapply(StatusEffects.dynamic);
            }
            return;
        }

        StatusEntry dyn = unit.applyDynamicStatus();
        dyn.time = Float.POSITIVE_INFINITY;
        dyn.speedMultiplier = stats.speedMult;
        dyn.damageMultiplier = stats.damageMult;
        dyn.healthMultiplier = stats.healthMult;
        dyn.reloadMultiplier = stats.reloadMult;
        dyn.buildSpeedMultiplier = stats.buildSpeedMult;
        dyn.armorOverride = (stats.armorBonus != 0f)
            ? Math.max(0f, unit.type.armor + stats.armorBonus)
            : -1f;

        if (stats.regenPerSec > 0f && unit.health < unit.maxHealth) {
            unit.heal(stats.regenPerSec * (Time.delta / 60f));
        }
    }

    public void updateVisualRigForUnit(String rigKey, Unit unit, Seq<String> equippedIds, ComputedStats stats) {
        updateVisualRig(rigKey, unit, equippedIds, stats);
    }

    private void updateVisualRig(String rigKey, Unit unit, Seq<String> equippedIds, ComputedStats stats) {
        String sig = buildSignature(equippedIds);
        VisualRig rig = activeRigs.get(rigKey);

        if (rig != null && (rig.unitId != unit.id
            || rig.teamId != unit.team.id
            || !sig.equals(rig.signature)
            || anyVisualRemoved(rig))) {
            destroyRig(rigKey);
            rig = null;
        }

        if (equippedIds.isEmpty()) return;

        if (rig == null) {
            rig = createRig(unit, equippedIds, sig);
            activeRigs.put(rigKey, rig);
        }

        float hullRadius = Math.max(4f, unit.hitSize * 0.5f);

        for (int i = 0; i < rig.instances.size; i++) {
            VisualInstance vi = rig.instances.get(i);
            if (!vi.isAliveInWorld()) continue;

            float targetX = unit.x;
            float targetY = unit.y;
            float targetRot = unit.rotation;

            switch (vi.mode) {
                case MOUNT, SUBWEAPON -> {
                    float baseForward = vi.def.computeForwardOffset(hullRadius);
                    float absSide = Math.abs(vi.def.computeSideOffset(hullRadius));
                    float angleOff = vi.def.effectiveMountAngleOffset();

                    float forward;
                    float signedSide;
                    if (vi.symmetrySideSign != 0f) {
                        // Off-axis pair: step outward/aft symmetrically on BOTH left (+1) and right (-1)
                        forward = baseForward - vi.subtypeRepeatIndex * 2.6f;
                        signedSide = vi.symmetrySideSign * (absSide + vi.subtypeRepeatIndex * 1.6f);
                        targetRot = unit.rotation + vi.symmetrySideSign * angleOff;
                    } else {
                        // On longitudinal symmetry axis: stay squarely on side == 0
                        forward = baseForward - vi.subtypeRepeatIndex * 3.0f;
                        signedSide = 0f;
                        targetRot = unit.rotation + angleOff;
                    }

                    targetX = unit.x + Angles.trnsx(unit.rotation, forward, signedSide);
                    targetY = unit.y + Angles.trnsy(unit.rotation, forward, signedSide);
                    vi.worldX = targetX;
                    vi.worldY = targetY;

                    if (vi.mode == AttachMode.SUBWEAPON && vi.spriteUnit != null) {
                        vi.spriteUnit.team(unit.team);
                        if (!vi.overloaded && (!Mathf.equal(stats.damageMult, 1f) || !Mathf.equal(stats.reloadMult, 1f))) {
                            StatusEntry subDyn = vi.spriteUnit.applyDynamicStatus();
                            subDyn.time = Float.POSITIVE_INFINITY;
                            subDyn.damageMultiplier = stats.damageMult;
                            subDyn.reloadMultiplier = stats.reloadMult;
                        }
                    }
                }
                case HALO -> {
                    collectHaloAnchors(unit, vi.def.haloAnchor, tmpAnchors);
                    if (!tmpAnchors.isEmpty()) {
                        int idx = Math.min(vi.anchorIndex, tmpAnchors.size - 1);
                        Vec2 pt = tmpAnchors.get(idx);
                        targetX = pt.x;
                        targetY = pt.y;
                    }
                    targetRot = unit.rotation;
                    vi.worldX = targetX;
                    vi.worldY = targetY;

                    // Emit configurable Mindustry Fx halo effect periodically at this anchor
                    Effect fx = vi.def.resolveHaloFx();
                    if (fx != null && vi.def.haloInterval > 0f) {
                        vi.fxTimer += Time.delta;
                        if (vi.fxTimer >= vi.def.haloInterval) {
                            vi.fxTimer %= Math.max(4f, vi.def.haloInterval);
                            Color col = vi.def.resolveHaloColor();
                            Call.effect(fx, targetX, targetY, vi.def.haloRadius, col);
                        }
                    }
                }
                case FOLLOW -> {
                    float desiredDist = hullRadius + Math.max(4f, vi.def.followDistance);
                    float relAngle = computeSymmetricFollowAngle(vi.modeIndex, vi.modeTotal);
                    float formAngle = unit.rotation + 180f + relAngle;

                    float bobPhase = Time.time * 0.065f + vi.modeIndex * 1.7f;
                    float bobX = Angles.trnsx(formAngle + 90f, Mathf.sin(bobPhase) * 0.85f);
                    float bobY = Angles.trnsy(formAngle + 90f, Mathf.sin(bobPhase) * 0.85f);

                    float idealX = unit.x + Angles.trnsx(formAngle, desiredDist) + bobX;
                    float idealY = unit.y + Angles.trnsy(formAngle, desiredDist) + bobY;

                    float curDist = Mathf.dst(vi.worldX - unit.x, vi.worldY - unit.y);
                    if (curDist > desiredDist * 4.5f || curDist < 0.01f) {
                        // Snap cleanly into formation on initial spawn or teleport
                        vi.worldX = idealX;
                        vi.worldY = idealY;
                    } else {
                        float alpha = Math.min(1f, 0.13f * Math.max(1f, Time.delta));
                        vi.worldX = Mathf.lerp(vi.worldX, idealX, alpha);
                        vi.worldY = Mathf.lerp(vi.worldY, idealY, alpha);

                        // Keep follower floating at its specific standoff band without collapsing onto unit
                        float newDx = vi.worldX - unit.x;
                        float newDy = vi.worldY - unit.y;
                        float newDist = Mathf.len(newDx, newDy);
                        float minAllowed = desiredDist * 0.82f;
                        float maxAllowed = desiredDist * 1.35f;
                        if (newDist > 0.001f && newDist < minAllowed) {
                            float s = minAllowed / newDist;
                            vi.worldX = unit.x + newDx * s;
                            vi.worldY = unit.y + newDy * s;
                        } else if (newDist > maxAllowed) {
                            float s = maxAllowed / newDist;
                            vi.worldX = unit.x + newDx * s;
                            vi.worldY = unit.y + newDy * s;
                        }
                    }

                    targetX = vi.worldX;
                    targetY = vi.worldY;
                    targetRot = unit.rotation;
                }
            }

            if (vi.spriteUnit != null) {
                vi.spriteUnit.set(targetX, targetY);
                vi.spriteUnit.rotation = targetRot;
                vi.spriteUnit.elevation = (vi.mode == AttachMode.FOLLOW) ? 1f : unit.elevation;
                vi.spriteUnit.vel.setZero();
            } else if (vi.fallbackLabel != null) {
                vi.fallbackLabel.set(targetX, targetY);
            }
        }
    }

    /**
     * Symmetric rear-arc formation angles for floating {@link AttachMode#FOLLOW} companions:
     * 1 follower -> 0 deg (straight behind);
     * 2 followers -> +28 / -28 deg;
     * 3 followers -> 0, +34, -34 deg; etc.
     */
    public static float computeSymmetricFollowAngle(int index, int total) {
        if (total <= 1) return 0f;
        if (total % 2 == 1) {
            if (index == 0) return 0f;
            int pair = (index + 1) / 2;
            float sign = (index % 2 == 1) ? 1f : -1f;
            return sign * pair * 32f;
        } else {
            int pair = index / 2;
            float sign = (index % 2 == 0) ? 1f : -1f;
            return sign * (24f + pair * 30f);
        }
    }

    public static void collectHaloAnchors(Unit unit, HaloAnchor anchor, Seq<Vec2> out) {
        out.clear();
        if (unit == null) return;

        switch (anchor) {
            case WEAPONS -> {
                if (unit.mounts != null && unit.mounts.length > 0) {
                    for (WeaponMount m : unit.mounts) {
                        if (m == null || m.weapon == null) continue;
                        float wx = unit.x + Angles.trnsx(unit.rotation - 90f, m.weapon.x, m.weapon.y);
                        float wy = unit.y + Angles.trnsy(unit.rotation - 90f, m.weapon.x, m.weapon.y);
                        out.add(new Vec2(wx, wy));
                    }
                }
                if (out.isEmpty()) {
                    out.add(new Vec2(unit.x, unit.y));
                }
            }
            case ENGINES -> {
                if (unit.type != null && unit.type.engines != null && !unit.type.engines.isEmpty()) {
                    for (UnitEngine eng : unit.type.engines) {
                        float ex = unit.x + Angles.trnsx(unit.rotation - 90f, eng.x, eng.y);
                        float ey = unit.y + Angles.trnsy(unit.rotation - 90f, eng.x, eng.y);
                        out.add(new Vec2(ex, ey));
                    }
                }
                if (out.isEmpty() && unit.type != null && unit.type.engineSize > 0f) {
                    float ex = unit.x + Angles.trnsx(unit.rotation + 180f, unit.type.engineOffset);
                    float ey = unit.y + Angles.trnsy(unit.rotation + 180f, unit.type.engineOffset);
                    out.add(new Vec2(ex, ey));
                }
                if (out.isEmpty()) {
                    float rearOff = Math.max(3f, unit.hitSize * 0.36f);
                    out.add(new Vec2(
                        unit.x + Angles.trnsx(unit.rotation + 180f, rearOff),
                        unit.y + Angles.trnsy(unit.rotation + 180f, rearOff)
                    ));
                }
            }
            case CENTER -> out.add(new Vec2(unit.x, unit.y));
        }
    }

    private VisualRig createRig(Unit unit, Seq<String> equippedIds, String sig) {
        VisualRig rig = new VisualRig();
        rig.unitId = unit.id;
        rig.teamId = unit.team.id;
        rig.signature = sig;

        Catalog cat = getCatalog();
        int n = Math.min(MAX_EQUIPPED_SLOTS, equippedIds.size);
        float hullRadius = Math.max(4f, unit.hitSize * 0.5f);

        int followTotal = 0;
        for (int i = 0; i < n; i++) {
            ShopItemDef def = cat.findItem(equippedIds.get(i));
            if (def != null && def.attachMode == AttachMode.FOLLOW) {
                followTotal++;
            }
        }

        ObjectIntMap<MountSubtype> subtypeCounts = new ObjectIntMap<>();
        int followIdx = 0;

        for (int i = 0; i < n; i++) {
            String itemId = equippedIds.get(i);
            ShopItemDef def = cat.findItem(itemId);
            if (def == null) continue;

            AttachMode mode = def.attachMode;
            boolean overloaded = i >= ACTIVE_PASSIVE_SLOTS;

            switch (mode) {
                case MOUNT, SUBWEAPON -> {
                    int repeatIdx = subtypeCounts.get(def.mountSubtype, 0);
                    subtypeCounts.put(def.mountSubtype, repeatIdx + 1);

                    boolean offAxis = def.isSymmetricallyDuplicated(hullRadius);
                    if (offAxis) {
                        // Symmetrical duplication across longitudinal axis (+1 left, -1 right)!
                        rig.instances.add(spawnVisualEntity(unit, def, mode, overloaded, +1f, repeatIdx, 0, 0, 2));
                        rig.instances.add(spawnVisualEntity(unit, def, mode, overloaded, -1f, repeatIdx, 0, 1, 2));
                    } else {
                        rig.instances.add(spawnVisualEntity(unit, def, mode, overloaded, 0f, repeatIdx, 0, 0, 1));
                    }
                }
                case HALO -> {
                    Seq<Vec2> anchors = new Seq<>();
                    collectHaloAnchors(unit, def.haloAnchor, anchors);
                    int count = Math.max(1, anchors.size);
                    for (int a = 0; a < count; a++) {
                        rig.instances.add(spawnVisualEntity(unit, def, mode, overloaded, 0f, 0, a, a, count));
                    }
                }
                case FOLLOW -> {
                    int myFollowIdx = followIdx++;
                    float desiredDist = hullRadius + Math.max(4f, def.followDistance);
                    float relAngle = computeSymmetricFollowAngle(myFollowIdx, followTotal);
                    float startX = unit.x + Angles.trnsx(unit.rotation + 180f + relAngle, desiredDist);
                    float startY = unit.y + Angles.trnsy(unit.rotation + 180f + relAngle, desiredDist);
                    VisualInstance inst = spawnVisualEntity(unit, def, mode, overloaded, 0f, 0, 0, myFollowIdx, followTotal);
                    inst.worldX = startX;
                    inst.worldY = startY;
                    if (inst.spriteUnit != null) {
                        inst.spriteUnit.set(startX, startY);
                    }
                    rig.instances.add(inst);
                }
            }
        }

        return rig;
    }

    private VisualInstance spawnVisualEntity(
        Unit hostUnit,
        ShopItemDef def,
        AttachMode mode,
        boolean overloaded,
        float symmetrySideSign,
        int subtypeRepeatIndex,
        int anchorIndex,
        int modeIndex,
        int modeTotal
    ) {
        UnitType visType = (Vars.content != null) ? Vars.content.unit(def.visualUnitName()) : null;
        Unit spriteUnit = null;
        WorldLabel fallbackLabel = null;

        if (visType != null) {
            Team spawnTeam = (mode == AttachMode.SUBWEAPON) ? hostUnit.team : Team.derelict;
            spriteUnit = visType.create(spawnTeam);
            if (mode == AttachMode.SUBWEAPON) {
                spriteUnit.controller(new SubweaponAI(hostUnit, !overloaded));
            } else {
                spriteUnit.controller(new InertVisualAI());
            }
            spriteUnit.set(hostUnit.x, hostUnit.y);
            spriteUnit.rotation = hostUnit.rotation;
            spriteUnit.elevation = (mode == AttachMode.FOLLOW) ? 1f : hostUnit.elevation;
            spriteUnit.add();
        } else {
            String hex = overloaded ? "ff6b6b" : def.colorHex;
            fallbackLabel = WorldLabel.create();
            fallbackLabel.parent = null;
            fallbackLabel.set(hostUnit.x, hostUnit.y);
            fallbackLabel.fontSize = overloaded ? 0.65f : 0.78f;
            fallbackLabel.flags = WorldLabel.flagOutline;
            fallbackLabel.text = "[#" + hex + "]" + def.worldGlyph() + "[]";
            fallbackLabel.add();
        }

        return new VisualInstance(
            def,
            mode,
            overloaded,
            symmetrySideSign,
            subtypeRepeatIndex,
            anchorIndex,
            modeIndex,
            modeTotal,
            spriteUnit,
            fallbackLabel,
            hostUnit.x,
            hostUnit.y
        );
    }

    private static boolean anyVisualRemoved(VisualRig rig) {
        for (int i = 0; i < rig.instances.size; i++) {
            if (!rig.instances.get(i).isAliveInWorld()) return true;
        }
        return false;
    }

    private static String buildSignature(Seq<String> equippedIds) {
        if (equippedIds == null || equippedIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(equippedIds.size * 8);
        for (int i = 0; i < equippedIds.size; i++) {
            if (i > 0) sb.append('|');
            sb.append(equippedIds.get(i));
        }
        return sb.toString();
    }

    public int countActiveVisualLabelsForPlayer(Player player) {
        if (player == null) return 0;
        VisualRig rig = activeRigs.get(player.uuid());
        return rig == null ? 0 : rig.instances.size;
    }

    public Seq<Unit> getActiveVisualUnitsForPlayer(Player player) {
        if (player == null) return new Seq<>();
        return getActiveVisualUnitsByKey(player.uuid());
    }

    public Seq<Unit> getActiveVisualUnitsByKey(String rigKey) {
        Seq<Unit> out = new Seq<>();
        VisualRig rig = activeRigs.get(rigKey);
        if (rig == null) return out;
        for (VisualInstance vi : rig.instances) {
            if (vi.spriteUnit != null && vi.spriteUnit.isAdded()) {
                out.add(vi.spriteUnit);
            }
        }
        return out;
    }

    public void destroyRig(String rigKey) {
        VisualRig old = activeRigs.remove(rigKey);
        if (old == null) return;
        for (int i = 0; i < old.instances.size; i++) {
            old.instances.get(i).removeFromWorld();
        }
        old.instances.clear();
    }

    public void clearAllRigs() {
        for (String key : activeRigs.keys().toSeq()) {
            destroyRig(key);
        }
        activeRigs.clear();
    }
}
