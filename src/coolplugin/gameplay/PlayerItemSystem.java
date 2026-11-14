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
import coolplugin.gameplay.WorldItemLogicManager.CompiledVisualEntry;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.StatusEntry;
import mindustry.entities.units.WeaponMount;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.UnitType.UnitEngine;
import mindustry.world.blocks.defense.turrets.ItemTurret;

import java.util.Locale;

import static mindustry.Vars.*;

/**
 * Manages player equipable items (up to 10 inventory slots), crafting, decrafting,
 * Bazaar orbital ship trades, passive stat bonuses (positions 1–4), overburden speed penalty
 * ({@code -10%} movement speed per item after the 4th, with no passive buffs), and hybrid
 * WorldProcessor + server plugin item mechanics:
 * <ul>
 *   <li>Items are never spawned as {@link Unit} entities (not hoverable, zero Top-button duplication).</li>
 *   <li>Client-local 60-FPS visuals ({@code MOUNT}, {@code HALO}, {@code SUBWEAPON}, {@code FOLLOW},
 *       plus forced tail-following + {@code LineMarker} connection lines for all items outside the first four)
 *       are driven by server-placed {@link Blocks#worldProcessor} blocks via {@link WorldItemLogicManager}.</li>
 *   <li>Authoritative unit stat multipliers ({@link StatusEffects#dynamic}) and active {@code SUBWEAPON}
 *       projectile firing ({@link Call#createBullet}) run on the server plugin.</li>
 * </ul>
 */
public class PlayerItemSystem {

    public static final int MAX_EQUIPPED_SLOTS = 10;
    public static final int ACTIVE_PASSIVE_SLOTS = 4;
    public static final float OVERLOAD_SPEED_PENALTY_PER_ITEM = 0.10f;

    public record ActionOutcome(boolean success, String message) {}

    public static final class ComputedStats {
        public float totalWeight=0f, weightSpeedFactor=1f, weightReferenceHealth=0f;
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

    /** Server-side firing state for one active SUBWEAPON barrel (only for slots 0..3). */
    private static final class ServerSubweaponBarrel {
        final ShopItemDef def;
        final float localForward;
        final float localSide;
        float reloadCounter;
        float targetScanTimer;
        @Nullable Teamc cachedTarget;

        ServerSubweaponBarrel(ShopItemDef def, float localForward, float localSide, float phaseOffset) {
            this.def = def;
            this.localForward = localForward;
            this.localSide = localSide;
            this.reloadCounter = phaseOffset;
        }
    }

    /** Optional server-side Fx emitter for HALO items when haloEffect != "none". */
    private static final class ServerHaloFxEmitter {
        final ShopItemDef def;
        final int anchorIndex;
        float fxTimer;

        ServerHaloFxEmitter(ShopItemDef def, int anchorIndex) {
            this.def = def;
            this.anchorIndex = anchorIndex;
            this.fxTimer = (anchorIndex * 6f) % Math.max(8f, def.haloInterval);
        }
    }

    private static final class ActiveServerRig {
        int unitId = -1;
        int teamId = -1;
        String signature = "";
        Unit wearer;
        final arc.struct.ObjectSet<mindustry.type.StatusEffect> desiredStatuses=new arc.struct.ObjectSet<>();
        final arc.struct.ObjectSet<mindustry.type.StatusEffect> grantedStatuses=new arc.struct.ObjectSet<>();
        final Seq<ServerSubweaponBarrel> subweapons = new Seq<>();
        final Seq<ServerHaloFxEmitter> haloFxEmitters = new Seq<>();
    }

    private final Fi shopConfigFile;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final BuildSystem buildSystem;
    private final WorldItemLogicManager worldLogicManager;

    private Catalog catalog;
    private final ObjectMap<String, ActiveServerRig> serverRigs = new ObjectMap<>();
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
        this.worldLogicManager = new WorldItemLogicManager(buildSystem);
    }

    public WorldItemLogicManager getWorldLogicManager() {
        return worldLogicManager;
    }

    public void start() {
        reloadCatalog();
        worldLogicManager.start();
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
        for (var palette : ColouringAssets.palettes) {
            if (catalog.findItem(palette.canId()) != null) continue;
            ShopItemDef can = new ShopItemDef();
            can.id = palette.canId();
            can.name = palette.title() + " Paint Can";
            can.description = "One use. " + palette.title() + " palette. Requires Colouring Station " + (palette.tier() == 2 ? "II" : "I") + ".";
            can.tier = StationTier.WORKBENCH;
            can.category = ShopItemDef.ShopCategory.UTILITY;
            can.sprite = "paint-can-" + palette.id();
            can.resourceCosts.add(new ResourceCost("copper", 50));
            can.resourceCosts.add(new ResourceCost("lead", 25));
            catalog.items.add(can);
        }
        catalog.rebuildIndexes();
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
        for(int i=0;i<data.equipped.size;i++){
            String id=data.equipped.get(i);
            if(id.startsWith("paint-unit-")){
                String scheme=ColouringAssets.paletteAliases.get(id.substring("paint-".length()));
                if(scheme!=null&&!id.equals("paint-"+scheme)){data.equipped.set(i,"paint-"+scheme);dirty=true;}
            }
        }
        dirty |= normalizeEquipment(data);
        if(!data.stash.isEmpty())for(String id:data.stash.keys().toSeq()){
            int count=data.stash.get(id,0);ShopItemDef item=getCatalog().findItem(id);
            while(count>0&&item!=null&&grantCraftedOrTradedItem(data,item)){count--;dirty=true;}
            if(count==0)data.stash.remove(id,0);else data.stash.put(id,count);
        }
        // Keep retired v17 cans salvageable without bringing back their palettes/textures.
        Catalog activeCatalog=getCatalog();
        for(String id:data.equipped)if(id.startsWith("paint-") && activeCatalog.findItem(id)==null){
            ShopItemDef retired=new ShopItemDef();retired.id=id;retired.name="Retired Paint Can";
            retired.description="Retired colour scheme. Decraft to recover its recipe materials.";
            retired.craftable=false;retired.decraftable=true;
            retired.resourceCosts.add(new ResourceCost("copper",50));retired.resourceCosts.add(new ResourceCost("lead",25));
            activeCatalog.items.add(retired);activeCatalog.rebuildIndexes();
        }

        if (dirty) {
            database.saveEquipmentData(acc.id, factionId, data);
        }
        return data;
    }

    public void saveEquipment(Player player, EquipmentData data) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        normalizeEquipment(data);
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

    public ComputedStats computeStats(Seq<String> equippedIds) {return computeStats(equippedIds,null);}

    /** Base type health only: injuries and temporary HP bonuses never alter carrying capacity. */
    public ComputedStats computeStats(Seq<String> equippedIds,@Nullable Unit unit) {
        ComputedStats st = new ComputedStats();
        if (equippedIds == null || equippedIds.isEmpty()) return st;

        Catalog cat = getCatalog();
        int n = Math.min(MAX_EQUIPPED_SLOTS, equippedIds.size);
        for(int i=0;i<n;i++)if(cat.findItem(equippedIds.get(i))!=null){
            st.totalEquipped++;if(i<ACTIVE_PASSIVE_SLOTS)st.activeSlotsUsed++;else st.overloadCount++;
        }

        for(int i=0;i<n;i++){ShopItemDef def=cat.findItem(equippedIds.get(i));if(def!=null)st.totalWeight+=def.carryingWeight();}
        if(unit!=null&&unit.type!=null){st.weightReferenceHealth=Math.max(1f,unit.type.health);st.weightSpeedFactor=weightFactor(st.totalWeight,st.weightReferenceHealth);}
        float hpBonus = 0f;
        float dmgBonus = 0f;
        float spdBonus = 0f;
        float rldBonus = 0f;
        float bldBonus = 0f;

        for (int i = 0; i < Math.min(ACTIVE_PASSIVE_SLOTS,n); i++) {
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
        st.speedMult = Math.max(0.10f, (1f + spdBonus) * overloadSpeedFactor * st.weightSpeedFactor);
        st.reloadMult = Math.max(0.10f, 1f + rldBonus);
        st.buildSpeedMult = Math.max(0.10f, 1f + bldBonus);
        return st;
    }

    public float weightFactor(float weight,float baseHealth){
        Catalog cat=getCatalog();
        return Math.max(cat.minimumWeightSpeedFactor,1f/(1f+cat.weightScale*Math.max(0f,weight)/Math.max(1f,baseHealth)));
    }

    public ActionOutcome promoteSlotToActive(Player player, int slotIndex) {
        EquipmentData data = getEquipment(player);
        if (slotIndex < 0 || slotIndex >= data.equipped.size) {
            return new ActionOutcome(false, "[scarlet]Empty inventory slot.[]");
        }
        String requested=data.equipped.get(slotIndex);
        if(requested.isBlank())return new ActionOutcome(false,"[scarlet]Empty inventory slot.[]");
        if(slotIndex>=ACTIVE_PASSIVE_SLOTS)for(int i=0;i<Math.min(ACTIVE_PASSIVE_SLOTS,data.equipped.size);i++)
            if(requested.equals(data.equipped.get(i)))return new ActionOutcome(true,"[stat]One copy is already active; this copy stays in carry.[]");
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
        EquipmentData trial=new EquipmentData();trial.equipped.addAll(data.equipped);
        for(ItemIngredient ingredient:item.itemCosts)consumeOwnedItem(trial,ingredient.shopItemId(),ingredient.amount());
        normalizeEquipment(trial);return grantCraftedOrTradedItem(trial,item);
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
            return new ActionOutcome(false, "[scarlet]Missing resources/ingredients, or no free inventory/carry slot.[]");
        }

        EquipmentData trial=new EquipmentData();trial.equipped.addAll(data.equipped);
        for(ItemIngredient ingredient:item.itemCosts)consumeOwnedItem(trial,ingredient.shopItemId(),ingredient.amount());
        normalizeEquipment(trial);
        if(!grantCraftedOrTradedItem(trial,item))return new ActionOutcome(false,"[scarlet]No free carry/inventory slot. No resources consumed.[]");

        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem != null) {
                buildSystem.takeItems(player, mItem, rc.amount());
            }
        }

        for (ItemIngredient ing : item.itemCosts) {
            consumeOwnedItem(data, ing.shopItemId(), ing.amount());
        }

        normalizeEquipment(data);
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
                if (subDef != null) {
                    if(!grantCraftedOrTradedItem(data,subDef))data.stash.increment(subDef.id,0,1);
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

        return new ActionOutcome(true, "");
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

        if(trade.rewardShopItemId!=null){
            var reward=getCatalog().findItem(trade.rewardShopItemId);
            EquipmentData trial=new EquipmentData();trial.equipped.addAll(getEquipment(player).equipped);
            if(reward!=null&&!grantCraftedOrTradedItem(trial,reward))return new ActionOutcome(false,"[scarlet]No free carry/inventory slot. No resources consumed.[]");
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

    public static boolean grantCraftedOrTradedItem(EquipmentData data, ShopItemDef item) {
        boolean active=false;
        for(int i=0;i<Math.min(ACTIVE_PASSIVE_SLOTS,data.equipped.size);i++)if(item.id.equals(data.equipped.get(i)))active=true;
        int start=active?ACTIVE_PASSIVE_SLOTS:0;
        for(int i=start;i<MAX_EQUIPPED_SLOTS;i++)if(i>=data.equipped.size||data.equipped.get(i).isBlank()){
            while(data.equipped.size<=i)data.equipped.add("");
            data.equipped.set(i,item.id);return true;
        }
        return false;
    }
    /** Keep slot holes explicit: a second copy must never slide into an active slot. */
    public static boolean normalizeEquipment(EquipmentData data){
        boolean changed=false;var seen=new arc.struct.ObjectSet<String>();
        Seq<String> duplicates=new Seq<>();
        for(int i=0;i<Math.min(ACTIVE_PASSIVE_SLOTS,data.equipped.size);i++){
            String id=data.equipped.get(i);if(id==null){data.equipped.set(i,"");changed=true;continue;}
            if(id.isBlank())continue;
            if(!seen.add(id)){data.equipped.set(i,"");duplicates.add(id);changed=true;}
        }
        while(data.equipped.size>MAX_EQUIPPED_SLOTS){String id=data.equipped.pop();if(!id.isBlank())data.stash.increment(id,0,1);changed=true;}
        for(String id:duplicates){
            int free=-1;for(int i=ACTIVE_PASSIVE_SLOTS;i<MAX_EQUIPPED_SLOTS;i++)if(i>=data.equipped.size||data.equipped.get(i).isBlank()){free=i;break;}
            if(free<0)data.stash.increment(id,0,1); // preserve legacy overflow, retry when carry frees
            else {while(data.equipped.size<=free)data.equipped.add("");data.equipped.set(free,id);}
        }
        while(!data.equipped.isEmpty()&&data.equipped.peek().isBlank()){data.equipped.pop();changed=true;}
        return changed;
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

    // ------------------------------------------------------------------ live unit stats, WorldProcessor sync & server subweapons

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
            ComputedStats stats = computeStats(eq.equipped,unit);
            applyStatsToUnit(unit, stats);
            updateVisualRig(player.uuid(), player.name, unit, eq.equipped, stats);
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
        dyn.speedMultiplier = Math.max(0.1f,stats.speedMult*(stats.weightReferenceHealth>0f?1f:weightFactor(stats.totalWeight,unit.type.health)));
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
        updateVisualRig(rigKey, null, unit, equippedIds, stats);
    }

    private void updateVisualRig(
        String rigKey,
        @Nullable String ownerPlayerName,
        Unit unit,
        Seq<String> equippedIds,
        ComputedStats stats
    ) {
        if (equippedIds == null || equippedIds.isEmpty()) {
            destroyRig(rigKey);
            return;
        }

        // 1. Synchronise client-local 60-FPS WorldProcessor markers (zero Unit entities spawned!)
        worldLogicManager.syncRig(rigKey, ownerPlayerName, unit, equippedIds, getCatalog());

        // 2. Maintain authoritative server gameplay state (active SUBWEAPON barrels in slots 0..3 + optional custom HALO Fx)
        String sig = buildSignature(unit, equippedIds);
        ActiveServerRig rig = serverRigs.get(rigKey);
        if (rig == null || rig.unitId != unit.id || rig.teamId != unit.team.id || !sig.equals(rig.signature)) {
            if(rig!=null)clearWearerStatuses(rig);
            rig = buildServerRig(unit, equippedIds, sig);
            serverRigs.put(rigKey, rig);
        }

        updateWearerStatuses(unit,rig);
        updateServerSubweapons(rigKey,unit, rig, stats);
        updateOptionalHaloFx(unit, rig);
    }

    private ActiveServerRig buildServerRig(Unit unit, Seq<String> equippedIds, String sig) {
        ActiveServerRig rig = new ActiveServerRig();
        rig.wearer=unit;
        rig.unitId = unit.id;
        rig.teamId = unit.team.id;
        rig.signature = sig;

        Catalog cat = getCatalog();
        int activeCount = Math.min(ACTIVE_PASSIVE_SLOTS, equippedIds.size);
        float hullRadius = Math.max(4f, unit.hitSize * 0.5f);
        ObjectIntMap<MountSubtype> subtypeCounts = new ObjectIntMap<>();

        // ONLY items in active slots 0..3 execute active SUBWEAPON or HALO gameplay;
        // items in slots 4..9 ("outside the four") are strictly towed behind the tail!
        for (int i = 0; i < activeCount; i++) {
            ShopItemDef def = cat.findItem(equippedIds.get(i));
            if (def == null) continue;

            for(String name:def.wearerStatuses){var status=ShopItemDef.resolveWearerStatus(name);if(status!=null)rig.desiredStatuses.add(status);}
            if (def.attachMode == AttachMode.SUBWEAPON) {
                for(var placement:MountLayout.resolve(unit,equippedIds,cat))if(placement.slot()==i){
                    rig.subweapons.add(new ServerSubweaponBarrel(def,placement.forward(),placement.side(),
                        placement.mirrored()?def.subweaponReload*.5f:def.subweaponReload));
                }
            } else if ((def.attachMode == AttachMode.HALO || def.attachMode == AttachMode.VFX) && def.resolveHaloFx() != null) {
                collectHaloAnchors(unit, def.haloAnchor, tmpAnchors);
                int count = Math.max(1, tmpAnchors.size);
                for (int a = 0; a < count; a++) {
                    rig.haloFxEmitters.add(new ServerHaloFxEmitter(def, a));
                }
            }
        }
        return rig;
    }

    private void updateServerSubweapons(String rigKey,Unit unit, ActiveServerRig rig, ComputedStats stats) {
        if (rig.subweapons.isEmpty()) return;

        float dt = Math.max(0.01f, Time.delta);
        float reloadRate = Math.max(0.15f, stats.reloadMult);

        for (int i = 0; i < rig.subweapons.size; i++) {
            ServerSubweaponBarrel barrel = rig.subweapons.get(i);
            barrel.reloadCounter += dt * reloadRate;

            float muzzleX = unit.x + Angles.trnsx(unit.rotation, barrel.localForward, barrel.localSide);
            float muzzleY = unit.y + Angles.trnsy(unit.rotation, barrel.localForward, barrel.localSide);

            boolean shouldFire = false;
            float aimX = unit.aimX;
            float aimY = unit.aimY;

            if (unit.isShooting()) {
                shouldFire = true;
                if (aimX == 0f && aimY == 0f) {
                    aimX = muzzleX + Angles.trnsx(unit.rotation, 64f);
                    aimY = muzzleY + Angles.trnsy(unit.rotation, 64f);
                }
            } else {
                barrel.targetScanTimer += dt;
                if (barrel.targetScanTimer >= 12f) {
                    barrel.targetScanTimer = 0f;
                    barrel.cachedTarget = Units.closestTarget(
                        unit.team,
                        muzzleX,
                        muzzleY,
                        barrel.def.subweaponRange,
                        u -> u.checkTarget(true, true) && !u.type.hidden,
                        b -> true
                    );
                }
                Teamc tgt = barrel.cachedTarget;
                if (tgt != null && Mathf.within(muzzleX, muzzleY, tgt.x(), tgt.y(), barrel.def.subweaponRange + 8f)) {
                    shouldFire = true;
                    aimX = tgt.x();
                    aimY = tgt.y();
                }
            }

            float reloadThreshold = Math.max(4f, barrel.def.subweaponReload);
            if (!shouldFire) {
                barrel.reloadCounter = Math.min(barrel.reloadCounter, reloadThreshold);
                continue;
            }

            if (barrel.reloadCounter >= reloadThreshold) {
                barrel.reloadCounter %= reloadThreshold;
                BulletType bulletType = resolveSubweaponBullet(barrel.def.subweaponPreset);
                if (bulletType != null) {
                    float aimAngle = Angles.angle(muzzleX, muzzleY, aimX, aimY);
                    var dimensions=ColouringAssets.mountSizes.get(barrel.def.id);
                    float muzzleLength=dimensions==null?3f:dimensions.y*.45f;
                    float spawnX = muzzleX + Angles.trnsx(aimAngle, muzzleLength);
                    float spawnY = muzzleY + Angles.trnsy(aimAngle, muzzleLength);
                    float dmg = Math.max(1f, barrel.def.subweaponDamage * stats.damageMult);
                    float bSpeed = Math.max(1.5f, bulletType.speed);
                    float lifeScl = Mathf.clamp(barrel.def.subweaponRange / (bSpeed * Math.max(8f, bulletType.lifetime)), 0.45f, 1.85f);
                    Call.createBullet(bulletType, unit.team, spawnX, spawnY, aimAngle, dmg, 1f, lifeScl);
                    worldLogicManager.kickSubweapon(rigKey,barrel.def,barrel.localForward,barrel.localSide);
                    var muzzle=barrel.def.resolveWeaponEffect(barrel.def.subweaponShootEffect);
                    var smoke=barrel.def.resolveWeaponEffect(barrel.def.subweaponSmokeEffect);
                    // Built-in IDs are stable; dynamically parsed datapatch Effect IDs are not.
                    // A client-local flash marker also renders above airborne mounts.
                    if(muzzle!=null)Call.effect(muzzle,spawnX,spawnY,aimAngle,bulletType.hitColor);
                    if(smoke!=null)Call.effect(smoke,spawnX,spawnY,aimAngle,bulletType.hitColor);
                    var sound=barrel.def.resolveShootSound();
                    if(sound!=null&&barrel.def.subweaponSoundVolume>0f)Call.soundAt(sound,spawnX,spawnY,barrel.def.subweaponSoundVolume,barrel.def.subweaponSoundPitch);
                }
            }
        }
    }

    private static void updateWearerStatuses(Unit unit,ActiveServerRig rig){
        for(var status:rig.desiredStatuses){
            // Do not take ownership of a status already supplied by the map/another source.
            if(rig.grantedStatuses.contains(status)&&unit.getDuration(status)>31f)rig.grantedStatuses.remove(status);
            if(rig.grantedStatuses.contains(status)||unit.getDuration(status)<=0f){
                unit.apply(status,30f);rig.grantedStatuses.add(status);
            }
        }
    }
    private static void clearWearerStatuses(ActiveServerRig rig){
        if(rig.wearer!=null)for(var status:rig.grantedStatuses)rig.wearer.unapply(status);
        rig.grantedStatuses.clear();
    }

    private void updateOptionalHaloFx(Unit unit, ActiveServerRig rig) {
        if (rig.haloFxEmitters.isEmpty()) return;
        for (int i = 0; i < rig.haloFxEmitters.size; i++) {
            ServerHaloFxEmitter em = rig.haloFxEmitters.get(i);
            Effect fx = em.def.resolveHaloFx();
            if (fx == null || em.def.haloInterval <= 0f) continue;

            em.fxTimer += Time.delta;
            if (em.fxTimer >= em.def.haloInterval) {
                em.fxTimer %= Math.max(4f, em.def.haloInterval);
                collectHaloAnchors(unit, em.def.haloAnchor, tmpAnchors);
                if (!tmpAnchors.isEmpty()) {
                    Vec2 pt = tmpAnchors.get(Math.min(em.anchorIndex, tmpAnchors.size - 1));
                    Color col = em.def.resolveHaloColor();
                    Call.effect(fx, pt.x, pt.y, em.def.haloRadius, col);
                }
            }
        }
    }

    public static @Nullable BulletType resolveSubweaponBullet(@Nullable String preset) {
        if (Vars.content == null) return null;
        String key = preset == null ? "kinetic" : preset.trim().toLowerCase(Locale.ROOT);

        try {
            return switch (key) {
                case "mortar", "artillery", "blast" ->
                    (UnitTypes.fortress != null && !UnitTypes.fortress.weapons.isEmpty())
                        ? UnitTypes.fortress.weapons.first().bullet
                        : fallbackBullet();
                case "pierce", "lance", "phase" -> {
                    if (Blocks.salvo instanceof ItemTurret it && Items.thorium != null) {
                        BulletType b = it.ammoTypes.get(Items.thorium);
                        if (b != null) yield b;
                    }
                    yield fallbackBullet();
                }
                case "incendiary", "pyratite", "gatling" -> {
                    if (Blocks.salvo instanceof ItemTurret it && Items.pyratite != null) {
                        BulletType b = it.ammoTypes.get(Items.pyratite);
                        if (b != null) yield b;
                    }
                    yield fallbackBullet();
                }
                case "blaster", "homing", "plastanium" -> {
                    if (Blocks.salvo instanceof ItemTurret it && Items.silicon != null) {
                        BulletType b = it.ammoTypes.get(Items.silicon);
                        if (b != null) yield b;
                    }
                    yield fallbackBullet();
                }
                default -> {
                    if (Blocks.duo instanceof ItemTurret it && Items.graphite != null) {
                        BulletType b = it.ammoTypes.get(Items.graphite);
                        if (b != null) yield b;
                    }
                    yield fallbackBullet();
                }
            };
        } catch (Throwable ignored) {
            return fallbackBullet();
        }
    }

    private static @Nullable BulletType fallbackBullet() {
        if (UnitTypes.dagger != null && !UnitTypes.dagger.weapons.isEmpty()) {
            return UnitTypes.dagger.weapons.first().bullet;
        }
        return null;
    }

    /** Symmetric rear-arc formation angles for floating/tail companions. */
    public static float computeSymmetricFollowAngle(int index, int total) {
        return WorldItemLogicManager.computeTailFormationAngle(index, total);
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

    private static String buildSignature(Unit unit, Seq<String> equippedIds) {
        StringBuilder sb = new StringBuilder(equippedIds.size * 8 + 20);
        sb.append(unit.type != null ? unit.type.name : "u").append(':');
        for (int i = 0; i < equippedIds.size; i++) {
            if (i > 0) sb.append('|');
            sb.append(equippedIds.get(i));
        }
        return sb.toString();
    }

    public int countActiveVisualLabelsForPlayer(Player player) {
        if (player == null) return 0;
        return worldLogicManager.getCompiledEntries(player.uuid()).size;
    }

    public Seq<CompiledVisualEntry> getCompiledVisualEntriesForKey(String rigKey) {
        return worldLogicManager.getCompiledEntries(rigKey);
    }

    /** Items are never spawned as Unit entities in v15+; always returns an empty sequence. */
    public Seq<Unit> getActiveVisualUnitsForPlayer(Player player) {
        return new Seq<>();
    }

    /** Items are never spawned as Unit entities in v15+; always returns an empty sequence. */
    public Seq<Unit> getActiveVisualUnitsByKey(String rigKey) {
        return new Seq<>();
    }

    public void destroyRig(String rigKey) {
        ActiveServerRig removed=serverRigs.remove(rigKey);
        if(removed!=null)clearWearerStatuses(removed);
        worldLogicManager.clearRig(rigKey);
    }

    public void clearAllRigs() {
        for(var rig:serverRigs.values())clearWearerStatuses(rig);
        serverRigs.clear();
        worldLogicManager.clearAllRigs();
    }
}
