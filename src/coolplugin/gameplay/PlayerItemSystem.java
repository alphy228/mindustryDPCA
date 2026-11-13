package coolplugin.gameplay;

import arc.Events;
import arc.files.Fi;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.StationTier;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.units.StatusEntry;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.gen.WorldLabel;
import mindustry.type.Item;

import static mindustry.Vars.*;

/**
 * Manages player equipable items (up to 10 equipped slots + unequipped bag), crafting, decrafting,
 * Bazaar orbital ship trades, passive stat bonuses (slots 1–4), overburden speed penalty
 * ({@code -10%} movement speed per item after the 4th, with no passive buffs), and multi-mode
 * in-world unit visual attachments ({@code ORBIT}, {@code MOUNT}, {@code HALO}, {@code TRAIL}).
 */
public class PlayerItemSystem {

    public static final int MAX_EQUIPPED_SLOTS = 10;
    public static final int ACTIVE_PASSIVE_SLOTS = 4;
    public static final float OVERLOAD_SPEED_PENALTY_PER_ITEM = 0.10f;
    public static final int MAX_BAG_ITEMS = 32;

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

    private static final class SlotVisual {
        final String itemId;
        final AttachMode mode;
        final boolean overloaded;
        final int modeIndex;
        final int modeTotal;
        WorldLabel label;
        float worldX;
        float worldY;

        SlotVisual(String itemId, AttachMode mode, boolean overloaded, int modeIndex, int modeTotal, WorldLabel label, float wx, float wy) {
            this.itemId = itemId;
            this.mode = mode;
            this.overloaded = overloaded;
            this.modeIndex = modeIndex;
            this.modeTotal = modeTotal;
            this.label = label;
            this.worldX = wx;
            this.worldY = wy;
        }
    }

    private static final class VisualRig {
        int unitId = -1;
        String signature = "";
        final Seq<SlotVisual> slots = new Seq<>();
    }

    private final Fi shopConfigFile;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final BuildSystem buildSystem;

    private Catalog catalog;
    private final ObjectMap<String, VisualRig> activeRigs = new ObjectMap<>();
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

    // ------------------------------------------------------------------ equipment & stat computation

    public EquipmentData getEquipment(Player player) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        EquipmentData data = database.getEquipmentData(acc.id, factionId);
        while (data.equipped.size > MAX_EQUIPPED_SLOTS) {
            String extra = data.equipped.pop();
            data.stash.put(extra, data.stash.get(extra, 0) + 1);
        }
        return data;
    }

    public void saveEquipment(Player player, EquipmentData data) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        database.saveEquipmentData(acc.id, factionId, data);
    }

    public int countOwnedTotal(EquipmentData data, String itemId) {
        int count = data.stash.get(itemId, 0);
        for (int i = 0; i < data.equipped.size; i++) {
            if (itemId.equals(data.equipped.get(i))) count++;
        }
        return count;
    }

    public static int totalBagCount(EquipmentData data) {
        int sum = 0;
        for (var e : data.stash.entries()) {
            if (e.value > 0) sum += e.value;
        }
        return sum;
    }

    /**
     * Computes passive bonuses from slots 1–4 (`index 0..3`) and `-10%` movement speed penalty
     * per item in slots 5–10 (`index 4..9`, which grant zero passive bonuses).
     */
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

    public ActionOutcome equipFromBag(Player player, String itemId) {
        ShopItemDef def = getCatalog().findItem(itemId);
        if (def == null) return new ActionOutcome(false, "[scarlet]Unknown item.[]");

        EquipmentData data = getEquipment(player);
        int inBag = data.stash.get(itemId, 0);
        if (inBag <= 0) {
            return new ActionOutcome(false, "[scarlet]No unequipped " + def.name + " in bag.[]");
        }
        if (data.equipped.size >= MAX_EQUIPPED_SLOTS) {
            return new ActionOutcome(false, "[scarlet]All 10 equip slots are full! Unequip an item first.[]");
        }

        if (inBag == 1) data.stash.remove(itemId, 0);
        else data.stash.put(itemId, inBag - 1);
        data.equipped.add(itemId);
        saveEquipment(player, data);

        int slotNum = data.equipped.size;
        if (slotNum > ACTIVE_PASSIVE_SLOTS) {
            return new ActionOutcome(true,
                "[#ff9f43]Equipped " + def.name + " in Slot #" + slotNum + " (Overburdened: no buffs, -10% speed).[]");
        }
        return new ActionOutcome(true, "[stat]Equipped " + def.name + " in Active Slot #" + slotNum + "![]");
    }

    public ActionOutcome unequipSlot(Player player, int slotIndex) {
        EquipmentData data = getEquipment(player);
        if (slotIndex < 0 || slotIndex >= data.equipped.size) {
            return new ActionOutcome(false, "[scarlet]Empty equip slot.[]");
        }
        String removedId = data.equipped.remove(slotIndex);
        ShopItemDef def = getCatalog().findItem(removedId);
        String name = def != null ? def.name : removedId;

        if (totalBagCount(data) < MAX_BAG_ITEMS) {
            data.stash.put(removedId, data.stash.get(removedId, 0) + 1);
            saveEquipment(player, data);
            return new ActionOutcome(true, "[lightgray]Moved " + name + " to bag.[]");
        } else {
            saveEquipment(player, data);
            return new ActionOutcome(true, "[scarlet]Bag was full - unequipped " + name + " vanished![]");
        }
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
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be crafted (salvage/decraft only).[]");
        }
        if (!isStationAllowedToCraft(station, item)) {
            return new ActionOutcome(false, "[scarlet]" + item.name + " cannot be crafted at this station.[]");
        }

        EquipmentData data = getEquipment(player);
        if (!canAffordCraft(player, item, data)) {
            return new ActionOutcome(false, "[scarlet]Missing required map resources or ingredient items.[]");
        }

        // Deduct map storage resources
        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem != null) {
                buildSystem.takeItems(player, mItem, rc.amount());
            }
        }

        // Deduct ingredient "items" (from Bag first, then Equipped slots from back to front)
        for (ItemIngredient ing : item.itemCosts) {
            consumeOwnedItem(data, ing.shopItemId(), ing.amount());
        }

        String placementNote = grantCraftedOrTradedItem(data, item);
        saveEquipment(player, data);
        return new ActionOutcome(true, "[stat]Crafted [white]" + item.name + "[stat]! " + placementNote);
    }

    public ActionOutcome decraft(Player player, String itemId, StationTier station) {
        if (station == StationTier.CURSED_ALTAR) {
            return new ActionOutcome(false, "[scarlet]The Cursed Altar does not perform basic decrafting.[]");
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

        // Refund sub-items if the recipe used "items"
        int vanishedSubItems = 0;
        for (ItemIngredient ing : item.itemCosts) {
            ShopItemDef subDef = getCatalog().findItem(ing.shopItemId());
            for (int c = 0; c < ing.amount(); c++) {
                if (subDef != null && totalBagCount(data) < MAX_BAG_ITEMS) {
                    data.stash.put(subDef.id, data.stash.get(subDef.id, 0) + 1);
                } else {
                    vanishedSubItems++;
                }
            }
        }
        saveEquipment(player, data);

        // Deposit recipe resources into nearby Map Storage; any overflow beyond map storage vanishes!
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
                    + " resources ([scarlet]" + totalVanished + " vanished - insufficient map storage[]).[]");
        }
        return new ActionOutcome(true,
            "[stat]Decrafted [white]" + item.name + "[stat] -> deposited " + totalReturned + " resources into map storage.[]");
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
            return new ActionOutcome(false, "[scarlet]Insufficient resources in persistent Launch Pad Orbital Storage.[]");
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
                String note = grantCraftedOrTradedItem(data, rewardItem);
                saveEquipment(player, data);
                outcomeMsg.append("received [white]").append(rewardItem.name).append("[] ").append(note);
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
            outcomeMsg.append("dropped ").append(stored).append(" resources into map storage");
            if (vanished > 0) {
                outcomeMsg.append(" ([scarlet]").append(vanished).append(" vanished - map storage full[])");
            }
        }
        return new ActionOutcome(true, outcomeMsg.toString());
    }

    private String grantCraftedOrTradedItem(EquipmentData data, ShopItemDef item) {
        if (data.equipped.size < ACTIVE_PASSIVE_SLOTS) {
            data.equipped.add(item.id);
            return "[lightgray](Equipped in Slot #" + data.equipped.size + ")[]";
        }
        if (totalBagCount(data) < MAX_BAG_ITEMS) {
            data.stash.put(item.id, data.stash.get(item.id, 0) + 1);
            return "[lightgray](Placed in Bag)[]";
        }
        if (data.equipped.size < MAX_EQUIPPED_SLOTS) {
            data.equipped.add(item.id);
            return "[#ff9f43](Equipped in Overburdened Slot #" + data.equipped.size + ": -10% SPD)[]";
        }
        return "[scarlet](Vanished - all 10 equip slots and bag were full!)[]";
    }

    private static void consumeOwnedItem(EquipmentData data, String itemId, int amount) {
        int remaining = amount;
        int inBag = data.stash.get(itemId, 0);
        if (inBag > 0) {
            int take = Math.min(remaining, inBag);
            if (inBag - take <= 0) data.stash.remove(itemId, 0);
            else data.stash.put(itemId, inBag - take);
            remaining -= take;
        }
        for (int i = data.equipped.size - 1; i >= 0 && remaining > 0; i--) {
            if (itemId.equals(data.equipped.get(i))) {
                data.equipped.remove(i);
                remaining--;
            }
        }
    }

    // ------------------------------------------------------------------ live unit stats & multi-style world visuals

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
            updateVisualRig(player.uuid(), unit, eq.equipped);
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

    private void updateVisualRig(String uuid, Unit unit, Seq<String> equippedIds) {
        String sig = buildSignature(equippedIds);
        VisualRig rig = activeRigs.get(uuid);

        if (rig != null && (rig.unitId != unit.id || !sig.equals(rig.signature) || anyLabelRemoved(rig))) {
            destroyRig(uuid);
            rig = null;
        }

        if (equippedIds.isEmpty()) return;

        if (rig == null) {
            rig = createRig(unit, equippedIds, sig);
            activeRigs.put(uuid, rig);
        }

        float prevTrailX = unit.x;
        float prevTrailY = unit.y;
        float rearAngle = unit.rotation + 180f;

        for (int i = 0; i < rig.slots.size; i++) {
            SlotVisual sv = rig.slots.get(i);
            if (sv.label == null || !sv.label.isAdded()) continue;

            switch (sv.mode) {
                case ORBIT -> {
                    float step = 360f / Math.max(1, sv.modeTotal);
                    float angle = Time.time * 2.1f + sv.modeIndex * step;
                    float radius = unit.hitSize * 0.85f + 7f + (sv.overloaded ? 4f : 0f);
                    sv.label.set(Angles.trnsx(angle, radius), Angles.trnsy(angle, radius));
                }
                case MOUNT -> {
                    float sideAngle = unit.rotation + ((sv.modeIndex % 2 == 0) ? 105f : -105f);
                    float radius = unit.hitSize * 0.65f + 4f + (sv.modeIndex / 2) * 5f;
                    sv.label.set(Angles.trnsx(sideAngle, radius), Angles.trnsy(sideAngle, radius));
                }
                case HALO -> {
                    float spread = (sv.modeIndex - (sv.modeTotal - 1) * 0.5f) * 6.5f;
                    float bob = Mathf.sin(Time.time * 0.08f + sv.modeIndex * 1.1f) * 1.6f;
                    sv.label.set(spread, unit.hitSize * 0.72f + 8f + bob);
                }
                case TRAIL -> {
                    float targetDist = (sv.modeIndex == 0) ? (unit.hitSize * 0.75f + 6f) : 7.5f;
                    float anchorX = (sv.modeIndex == 0)
                        ? unit.x + Angles.trnsx(rearAngle, targetDist)
                        : prevTrailX + Angles.trnsx(rearAngle, 4f);
                    float anchorY = (sv.modeIndex == 0)
                        ? unit.y + Angles.trnsy(rearAngle, targetDist)
                        : prevTrailY + Angles.trnsy(rearAngle, 4f);
                    float lerp = Math.min(1f, 0.22f * Math.max(1f, Time.delta));
                    sv.worldX = Mathf.lerp(sv.worldX, anchorX, lerp);
                    sv.worldY = Mathf.lerp(sv.worldY, anchorY, lerp);
                    sv.label.set(sv.worldX, sv.worldY);
                    prevTrailX = sv.worldX;
                    prevTrailY = sv.worldY;
                }
            }
        }
    }

    private VisualRig createRig(Unit unit, Seq<String> equippedIds, String sig) {
        VisualRig rig = new VisualRig();
        rig.unitId = unit.id;
        rig.signature = sig;

        Catalog cat = getCatalog();
        int n = Math.min(MAX_EQUIPPED_SLOTS, equippedIds.size);

        int[] totals = new int[AttachMode.values().length];
        for (int i = 0; i < n; i++) {
            ShopItemDef def = cat.findItem(equippedIds.get(i));
            AttachMode m = def != null ? def.attachMode : AttachMode.ORBIT;
            totals[m.ordinal()]++;
        }

        int[] indices = new int[AttachMode.values().length];
        for (int i = 0; i < n; i++) {
            String itemId = equippedIds.get(i);
            ShopItemDef def = cat.findItem(itemId);
            AttachMode mode = def != null ? def.attachMode : AttachMode.ORBIT;
            boolean overloaded = i >= ACTIVE_PASSIVE_SLOTS;
            int idx = indices[mode.ordinal()]++;
            int tot = totals[mode.ordinal()];

            String hex = overloaded ? "ff6b6b" : (def != null ? def.colorHex : "ffd37f");
            String glyph = def != null ? def.worldGlyph() : "*";

            WorldLabel wl = WorldLabel.create();
            if (mode != AttachMode.TRAIL) {
                wl.parent = unit;
                wl.set(0f, unit.hitSize);
            } else {
                wl.parent = null;
                wl.set(unit.x, unit.y);
            }
            wl.fontSize = overloaded ? 0.65f : 0.78f;
            wl.flags = WorldLabel.flagOutline;
            wl.text = "[#" + hex + "]" + glyph + "[]";
            wl.add();

            rig.slots.add(new SlotVisual(itemId, mode, overloaded, idx, tot, wl, unit.x, unit.y));
        }

        return rig;
    }

    private static boolean anyLabelRemoved(VisualRig rig) {
        for (int i = 0; i < rig.slots.size; i++) {
            WorldLabel l = rig.slots.get(i).label;
            if (l == null || !l.isAdded()) return true;
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
        return rig == null ? 0 : rig.slots.size;
    }

    private void destroyRig(String uuid) {
        VisualRig old = activeRigs.remove(uuid);
        if (old == null) return;
        for (int i = 0; i < old.slots.size; i++) {
            WorldLabel wl = old.slots.get(i).label;
            if (wl != null) {
                try {
                    wl.hide();
                } catch (Throwable ignored) {
                    wl.remove();
                }
            }
        }
        old.slots.clear();
    }

    private void clearAllRigs() {
        for (String uuid : activeRigs.keys().toSeq()) {
            destroyRig(uuid);
        }
        activeRigs.clear();
    }
}
