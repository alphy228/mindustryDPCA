package coolplugin.gameplay;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.Jval;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.StationTier;

/**
 * Loads and autogenerates {@code config/alphystuff/shop.hjson} containing:
 * <ul>
 *   <li>15 placeholder items for {@link StationTier#WORKBENCH} ({@code wb-01 .. wb-15})</li>
 *   <li>15 placeholder items for {@link StationTier#BAZAAR} ({@code bz-01 .. bz-15})</li>
 *   <li>15 placeholder items for {@link StationTier#CURSED_ALTAR} ({@code ca-01 .. ca-15})</li>
 *   <li>Orbital merchant ship trades for the Bazaar (exchanging persistent Launch Pad orbital storage).</li>
 * </ul>
 */
public final class ShopConfigIO {

    public static final class Catalog {
        public final Seq<ShopItemDef> items = new Seq<>();
        public final ObjectMap<String, ShopItemDef> itemsById = new ObjectMap<>();
        public final Seq<OrbitalTradeDef> trades = new Seq<>();
        public final ObjectMap<String, OrbitalTradeDef> tradesById = new ObjectMap<>();

        public void rebuildIndexes() {
            itemsById.clear();
            for (ShopItemDef def : items) {
                itemsById.put(def.id, def);
            }
            tradesById.clear();
            for (OrbitalTradeDef tr : trades) {
                tradesById.put(tr.id, tr);
            }
        }

        public @Nullable ShopItemDef findItem(@Nullable String id) {
            return id == null ? null : itemsById.get(id);
        }

        public @Nullable OrbitalTradeDef findTrade(@Nullable String id) {
            return id == null ? null : tradesById.get(id);
        }

        public Seq<ShopItemDef> itemsForTier(StationTier tier) {
            return items.select(i -> i.tier == tier);
        }
    }

    private ShopConfigIO() {}

    public static Catalog loadOrCreate(Fi file) {
        if (!file.exists()) {
            Catalog def = createDefaultCatalog();
            save(file, def);
            Log.info("[CoolPlugin] Created default Item Shop catalog (@ items, @ orbital trades) at @",
                def.items.size, def.trades.size, file.path());
            return def;
        }
        try {
            return load(file);
        } catch (Throwable t) {
            Log.err("[CoolPlugin] Failed parsing shop.hjson; falling back to default catalog", t);
            return createDefaultCatalog();
        }
    }

    public static Catalog load(Fi file) {
        String rawHjson = file.readString("UTF-8");
        String jsonText = Jval.read(rawHjson).toString(Jval.Jformat.plain);
        JsonValue root = new JsonReader().parse(jsonText);

        Catalog cat = new Catalog();
        JsonValue itemsArr = root.get("items");
        if (itemsArr != null) {
            for (JsonValue v = itemsArr.child; v != null; v = v.next) {
                ShopItemDef item = new ShopItemDef();
                item.id = v.getString("id", "");
                if (item.id.isEmpty()) continue;
                item.name = v.getString("name", item.id);
                item.description = v.getString("description", "");
                StationTier tier = StationTier.byIdOrBlock(v.getString("tier", "workbench"));
                item.tier = tier != null ? tier : StationTier.WORKBENCH;
                item.iconItem = v.getString("iconItem", "copper");
                item.colorHex = v.getString("colorHex", item.tier.accentHex);
                item.attachMode = AttachMode.parse(v.getString("attachMode", "ORBIT"));
                item.craftable = v.getBoolean("craftable", true);
                item.decraftable = v.getBoolean("decraftable", true);

                item.healthMultBonus = v.getFloat("healthMultBonus", 0f);
                item.damageMultBonus = v.getFloat("damageMultBonus", 0f);
                item.speedMultBonus = v.getFloat("speedMultBonus", 0f);
                item.reloadMultBonus = v.getFloat("reloadMultBonus", 0f);
                item.buildSpeedMultBonus = v.getFloat("buildSpeedMultBonus", 0f);
                item.armorBonus = v.getFloat("armorBonus", 0f);
                item.regenPerSec = v.getFloat("regenPerSec", 0f);

                parseResourceMap(v.get("resources"), item.resourceCosts);
                JsonValue itemIngs = v.get("items");
                if (itemIngs != null && itemIngs.isObject()) {
                    for (JsonValue e = itemIngs.child; e != null; e = e.next) {
                        int amt = e.asInt();
                        if (e.name != null && amt > 0) {
                            item.itemCosts.add(new ItemIngredient(e.name, amt));
                        }
                    }
                }
                cat.items.add(item);
            }
        }

        JsonValue tradesArr = root.get("orbitalTrades");
        if (tradesArr != null) {
            for (JsonValue v = tradesArr.child; v != null; v = v.next) {
                OrbitalTradeDef tr = new OrbitalTradeDef();
                tr.id = v.getString("id", "");
                if (tr.id.isEmpty()) continue;
                tr.shipName = v.getString("shipName", "Orbital Freighter");
                tr.title = v.getString("title", tr.id);
                tr.description = v.getString("description", "");
                tr.rewardShopItemId = v.getString("rewardItem", null);
                parseResourceMap(v.get("orbitalCost"), tr.orbitalCosts);
                parseResourceMap(v.get("rewardResources"), tr.rewardMapResources);
                cat.trades.add(tr);
            }
        }

        cat.rebuildIndexes();
        return cat;
    }

    private static void parseResourceMap(@Nullable JsonValue obj, Seq<ResourceCost> target) {
        if (obj == null || !obj.isObject()) return;
        for (JsonValue e = obj.child; e != null; e = e.next) {
            int amt = e.asInt();
            if (e.name != null && amt > 0) {
                target.add(new ResourceCost(e.name, amt));
            }
        }
    }

    public static Catalog createDefaultCatalog() {
        Catalog cat = new Catalog();

        // =========================================================================
        // TIER 1: WORKBENCH — 15 Placeholder Items (wb-01 .. wb-15)
        // Includes standard crafts, item-ingredient upgrades, and decraft-only salvage relics!
        // =========================================================================
        cat.items.add(item("wb-01", "Copper Plating I", "Basic hull plating bolted onto unit frame.",
            StationTier.WORKBENCH, "copper", "d99d73", AttachMode.MOUNT, true, true,
            0.08f, 0f, 0f, 0f, 0f, 1f, 0f,
            res("copper", 80, "lead", 40), noItems()));

        cat.items.add(item("wb-02", "Lead Counterweight", "Stabilizes recoil for heavier shots.",
            StationTier.WORKBENCH, "lead", "8c7fa9", AttachMode.MOUNT, true, true,
            0f, 0.08f, 0f, 0f, 0f, 0f, 0f,
            res("lead", 90, "copper", 45), noItems()));

        cat.items.add(item("wb-03", "Graphite Gyro", "Revolving gyroscopic ring boosting maneuver speed.",
            StationTier.WORKBENCH, "graphite", "b2c6d2", AttachMode.ORBIT, true, true,
            0f, 0f, 0.08f, 0f, 0f, 0f, 0f,
            res("graphite", 45, "copper", 60), noItems()));

        cat.items.add(item("wb-04", "Sandglass Capacitor", "Cycles weapon chambers slightly faster.",
            StationTier.WORKBENCH, "metaglass", "ebeef5", AttachMode.HALO, true, true,
            0f, 0f, 0f, 0.08f, 0f, 0f, 0f,
            res("metaglass", 40, "lead", 60), noItems()));

        cat.items.add(item("wb-05", "Builder's Servo", "Auxiliary construction drone trailing your unit.",
            StationTier.WORKBENCH, "silicon", "8e909c", AttachMode.TRAIL, true, true,
            0f, 0f, 0f, 0f, 0.20f, 0f, 0f,
            res("silicon", 50, "copper", 80), noItems()));

        cat.items.add(item("wb-06", "Coal Burner Core", "Warm trailing ember granting mild hull repair.",
            StationTier.WORKBENCH, "coal", "ffb36b", AttachMode.TRAIL, true, true,
            0f, 0f, 0.04f, 0f, 0f, 0f, 2.0f,
            res("coal", 60, "copper", 70), noItems()));

        cat.items.add(item("wb-07", "Scrap Buckler", "Improvised orbiting debris shield.",
            StationTier.WORKBENCH, "scrap", "b08968", AttachMode.ORBIT, true, true,
            0.05f, 0f, 0f, 0f, 0f, 2f, 0f,
            res("scrap", 100, "lead", 50), noItems()));

        cat.items.add(item("wb-08", "Pyratite Sparkplug", "Volatile igniter boosting weapon output.",
            StationTier.WORKBENCH, "pyratite", "ffaa5f", AttachMode.MOUNT, true, true,
            0f, 0.12f, 0f, 0.04f, 0f, 0f, 0f,
            res("pyratite", 25, "lead", 80), noItems()));

        cat.items.add(item("wb-09", "Spore Poultice", "Living spore halo that slowly mends armor cracks.",
            StationTier.WORKBENCH, "spore-pod", "9d7fe8", AttachMode.HALO, true, true,
            0.06f, 0f, 0f, 0f, 0f, 0f, 3.0f,
            res("spore-pod", 35, "graphite", 40), noItems()));

        cat.items.add(item("wb-10", "Silicon Sight", "Precision targeting halo.",
            StationTier.WORKBENCH, "silicon", "a3e7fc", AttachMode.HALO, true, true,
            0f, 0.07f, 0f, 0.09f, 0f, 0f, 0f,
            res("silicon", 75, "metaglass", 40), noItems()));

        // Workbench composite crafts consuming earlier Workbench "items" + resources!
        cat.items.add(item("wb-11", "Reinforced Copper Aegis", "Upgrades Copper Plating I with Graphite Gyro.",
            StationTier.WORKBENCH, "copper", "ffd37f", AttachMode.ORBIT, true, true,
            0.14f, 0f, 0.06f, 0f, 0f, 3f, 0f,
            res("copper", 120, "graphite", 50), ing("wb-01", 1, "wb-03", 1)));

        cat.items.add(item("wb-12", "Overclocked Rig", "Fuses Builder's Servo and Sandglass Capacitor.",
            StationTier.WORKBENCH, "metaglass", "98ffd9", AttachMode.TRAIL, true, true,
            0f, 0f, 0.05f, 0.10f, 0.30f, 0f, 0f,
            res("silicon", 60, "graphite", 40), ing("wb-04", 1, "wb-05", 1)));

        cat.items.add(item("wb-13", "Heavy Striker Frame", "Combines Lead Counterweight with Pyratite Sparkplug.",
            StationTier.WORKBENCH, "pyratite", "ff8c66", AttachMode.MOUNT, true, true,
            0.05f, 0.16f, 0f, 0.06f, 0f, 1f, 0f,
            res("lead", 120, "silicon", 40), ing("wb-02", 1, "wb-08", 1)));

        // Non-craftable Workbench salvage items (craftable=false, decraftable=true) — break down into resources!
        cat.items.add(item("wb-14", "Ancient Copper Relic", "Non-craftable salvage core. Can be equipped or decrafted for rich copper & silicon.",
            StationTier.WORKBENCH, "copper", "ffbe76", AttachMode.HALO, false, true,
            0.10f, 0.05f, 0f, 0f, 0.15f, 2f, 0f,
            res("copper", 220, "silicon", 90, "graphite", 60), noItems()));

        cat.items.add(item("wb-15", "Sealed Smelter Core", "Non-craftable salvaged furnace heart. Decraft at Workbench/Bazaar to reclaim materials.",
            StationTier.WORKBENCH, "graphite", "ff9f43", AttachMode.ORBIT, false, true,
            0f, 0.10f, 0.05f, 0.05f, 0f, 0f, 2.5f,
            res("lead", 200, "graphite", 120, "metaglass", 80), noItems()));

        // =========================================================================
        // TIER 2: BAZAAR — 15 Placeholder Items (bz-01 .. bz-15)
        // =========================================================================
        cat.items.add(item("bz-01", "Titanium Carapace", "High-grade titanium hull plates.",
            StationTier.BAZAAR, "titanium", "8da1e3", AttachMode.MOUNT, true, true,
            0.18f, 0f, 0f, 0f, 0f, 4f, 0f,
            res("titanium", 120, "graphite", 90), ing("wb-01", 1)));

        cat.items.add(item("bz-02", "Cryo-Cooled Breech", "Rapid-fire weapon cycling module.",
            StationTier.BAZAAR, "titanium", "7fe6ff", AttachMode.MOUNT, true, true,
            0f, 0.08f, 0f, 0.18f, 0f, 0f, 0f,
            res("titanium", 90, "metaglass", 80, "silicon", 100), noItems()));

        cat.items.add(item("bz-03", "Orbital Thruster Ring", "Sleek cyan thruster ring accelerating movement.",
            StationTier.BAZAAR, "silicon", "66f2ff", AttachMode.ORBIT, true, true,
            0f, 0f, 0.16f, 0f, 0f, 0f, 0f,
            res("titanium", 80, "silicon", 140, "graphite", 80), ing("wb-03", 1)));

        cat.items.add(item("bz-04", "Blast Munitions Rack", "Explosive payload feed boosting weapon damage.",
            StationTier.BAZAAR, "blast-compound", "ff795e", AttachMode.MOUNT, true, true,
            0f, 0.22f, 0f, 0f, 0f, 0f, 0f,
            res("blast-compound", 45, "titanium", 80, "lead", 150), noItems()));

        cat.items.add(item("bz-05", "Plastanium Dampener", "Absorptive composite ring reducing incoming trauma.",
            StationTier.BAZAAR, "plastanium", "cbd97e", AttachMode.ORBIT, true, true,
            0.15f, 0f, 0.04f, 0f, 0f, 5f, 3.0f,
            res("plastanium", 60, "titanium", 100, "silicon", 80), noItems()));

        cat.items.add(item("bz-06", "Architect Drone Swarm", "Twin trailing fab-drones dramatically boosting build speed.",
            StationTier.BAZAAR, "silicon", "98ffd9", AttachMode.TRAIL, true, true,
            0f, 0f, 0.06f, 0f, 0.55f, 0f, 0f,
            res("silicon", 180, "titanium", 90, "graphite", 100), ing("wb-05", 1)));

        cat.items.add(item("bz-07", "Thorium Reactor Core", "Dense fissile power pack amplifying damage & reload.",
            StationTier.BAZAAR, "thorium", "f9a3c7", AttachMode.HALO, true, true,
            0.08f, 0.16f, 0f, 0.12f, 0f, 0f, 0f,
            res("thorium", 80, "titanium", 100, "lead", 200), noItems()));

        cat.items.add(item("bz-08", "Phase Blink Feather", "Shifting phase wake increasing speed and regeneration.",
            StationTier.BAZAAR, "phase-fabric", "f4ba6e", AttachMode.TRAIL, true, true,
            0f, 0f, 0.18f, 0.06f, 0f, 0f, 5.0f,
            res("phase-fabric", 40, "silicon", 150), noItems()));

        cat.items.add(item("bz-09", "Surge Dynamo", " Crackling arc halo supercharging weapons and servos.",
            StationTier.BAZAAR, "surge-alloy", "f3e979", AttachMode.HALO, true, true,
            0f, 0.20f, 0.08f, 0.14f, 0f, 0f, 0f,
            res("surge-alloy", 40, "titanium", 120, "silicon", 140), noItems()));

        cat.items.add(item("bz-10", "Beryllium Lattice", "Lightweight off-world lattice plating.",
            StationTier.BAZAAR, "beryllium", "8be89c", AttachMode.ORBIT, true, true,
            0.14f, 0f, 0.08f, 0f, 0.25f, 3f, 0f,
            res("titanium", 110, "silicon", 110, "metaglass", 90), noItems()));

        cat.items.add(item("bz-11", "Vanguard Bastion Rig", "Fuses Reinforced Copper Aegis with Titanium Carapace.",
            StationTier.BAZAAR, "titanium", "74b9ff", AttachMode.ORBIT, true, true,
            0.28f, 0f, 0.05f, 0f, 0f, 7f, 4.0f,
            res("titanium", 180, "silicon", 120), ing("wb-11", 1, "bz-01", 1)));

        cat.items.add(item("bz-12", "Starlance Targeting Array", "Combines Heavy Striker Frame and Cryo-Cooled Breech.",
            StationTier.BAZAAR, "surge-alloy", "ffeaa7", AttachMode.HALO, true, true,
            0f, 0.26f, 0f, 0.20f, 0f, 0f, 0f,
            res("silicon", 200, "titanium", 120), ing("wb-13", 1, "bz-02", 1)));

        cat.items.add(item("bz-13", "Slipstream Vector Engine", "Combines Orbital Thruster Ring with Overclocked Rig.",
            StationTier.BAZAAR, "phase-fabric", "81ecec", AttachMode.TRAIL, true, true,
            0f, 0f, 0.22f, 0.12f, 0.40f, 0f, 0f,
            res("titanium", 140, "silicon", 160), ing("bz-03", 1, "wb-12", 1)));

        // Non-craftable Bazaar orbital salvage items (traded from ships; decraftable at Workbench/Bazaar!)
        cat.items.add(item("bz-14", "Orbital Vault Capsule", "Non-craftable merchant capsule. Equip for armor or decraft into titanium & silicon.",
            StationTier.BAZAAR, "titanium", "7fe6ff", AttachMode.ORBIT, false, true,
            0.16f, 0f, 0.06f, 0f, 0.20f, 4f, 3.0f,
            res("titanium", 260, "silicon", 220, "graphite", 180), noItems()));

        cat.items.add(item("bz-15", "Starfarer's Blackbox", "Non-craftable orbital flight core. Equip or decraft to harvest surge, thorium & phase.",
            StationTier.BAZAAR, "surge-alloy", "ffd37f", AttachMode.HALO, false, true,
            0.12f, 0.14f, 0.10f, 0.10f, 0f, 2f, 4.0f,
            res("thorium", 150, "plastanium", 120, "phase-fabric", 80, "surge-alloy", 60), noItems()));

        // =========================================================================
        // TIER 3: CURSED ALTAR — 15 Placeholder Items (ca-01 .. ca-15)
        // Exclusive Cursed Altar ritual crafts (not part of Workbench/Bazaar craft tabs)
        // =========================================================================
        cat.items.add(item("ca-01", "Crimson Soulshard", "Throbbing blood-red crystal orbiting its host.",
            StationTier.CURSED_ALTAR, "thorium", "ff4b6e", AttachMode.ORBIT, true, true,
            0.25f, 0.25f, 0f, 0f, 0f, 0f, 6.0f,
            res("copper", 300, "lead", 300, "silicon", 200), noItems()));

        cat.items.add(item("ca-02", "Abyssal Fang", "Twin cursed fangs mounted to your unit's flanks.",
            StationTier.CURSED_ALTAR, "blast-compound", "ff2e63", AttachMode.MOUNT, true, true,
            0f, 0.40f, 0.05f, 0.15f, 0f, 0f, 0f,
            res("blast-compound", 80, "pyratite", 80, "silicon", 200), noItems()));

        cat.items.add(item("ca-03", "Wraithstep Shroud", "Ghostly trailing wake bending inertia.",
            StationTier.CURSED_ALTAR, "spore-pod", "b565ff", AttachMode.TRAIL, true, true,
            0f, 0f, 0.32f, 0.15f, 0f, 0f, 5.0f,
            res("spore-pod", 120, "graphite", 250, "silicon", 200), noItems()));

        cat.items.add(item("ca-04", "Crown of Embers", "Blazing ritual halo raining rapid devastation.",
            StationTier.CURSED_ALTAR, "pyratite", "ff6b4a", AttachMode.HALO, true, true,
            0f, 0.25f, 0f, 0.35f, 0f, 0f, 0f,
            res("coal", 300, "pyratite", 120, "metaglass", 200), noItems()));

        cat.items.add(item("ca-05", "Obsidian Carapace", "Impenetrable cursed monolith plates.",
            StationTier.CURSED_ALTAR, "carbide", "9d4edd", AttachMode.MOUNT, true, true,
            0.45f, 0f, -0.04f, 0f, 0f, 12f, 8.0f,
            res("titanium", 300, "graphite", 300, "lead", 500), noItems()));

        cat.items.add(item("ca-06", "Bloodforged Architect", "Forbidden flesh-metal drone weaving structures instantly.",
            StationTier.CURSED_ALTAR, "oxide", "ff5d8f", AttachMode.TRAIL, true, true,
            0.10f, 0f, 0.10f, 0f, 1.00f, 0f, 4.0f,
            res("silicon", 350, "copper", 400, "titanium", 200), ing("bz-06", 1)));

        cat.items.add(item("ca-07", "Eye of Malice", "Unblinking violet halo amplifying lethal precision.",
            StationTier.CURSED_ALTAR, "spore-pod", "c77dff", AttachMode.HALO, true, true,
            0f, 0.35f, 0.08f, 0.25f, 0f, 0f, 0f,
            res("silicon", 300, "metaglass", 250, "titanium", 200), ing("wb-10", 1)));

        cat.items.add(item("ca-08", "Neoplasmic Symbiote", "Pulsating organic orbit feeding constant regeneration.",
            StationTier.CURSED_ALTAR, "pyratite", "e05438", AttachMode.ORBIT, true, true,
            0.30f, 0.10f, 0.08f, 0f, 0f, 4f, 18.0f,
            res("spore-pod", 200, "coal", 250, "silicon", 200), ing("wb-09", 1)));

        cat.items.add(item("ca-09", "Voidpendulum", "Swinging dark matter orb accelerating reload and speed.",
            StationTier.CURSED_ALTAR, "phase-fabric", "d980fa", AttachMode.ORBIT, true, true,
            0f, 0.15f, 0.22f, 0.28f, 0f, 0f, 0f,
            res("titanium", 250, "silicon", 300, "graphite", 250), ing("bz-03", 1)));

        cat.items.add(item("ca-10", "Hellfire Singularity", "Superheated core fusing Bazaar Starlance into cursed power.",
            StationTier.CURSED_ALTAR, "surge-alloy", "ff3838", AttachMode.HALO, true, true,
            0.15f, 0.45f, 0f, 0.25f, 0f, 3f, 0f,
            res("silicon", 400, "titanium", 300), ing("bz-12", 1, "ca-01", 1)));

        cat.items.add(item("ca-11", "Dreadnought Bastion", "Cursed evolution of the Vanguard Bastion Rig.",
            StationTier.CURSED_ALTAR, "carbide", "ff4b6e", AttachMode.MOUNT, true, true,
            0.55f, 0.15f, 0f, 0f, 0f, 15f, 12.0f,
            res("titanium", 400, "lead", 600), ing("bz-11", 1, "ca-05", 1)));

        cat.items.add(item("ca-12", "Phantom Astral Wake", "Transcends physical drag with Wraithstep + Slipstream.",
            StationTier.CURSED_ALTAR, "phase-fabric", "e056fd", AttachMode.TRAIL, true, true,
            0.10f, 0.15f, 0.40f, 0.20f, 0.50f, 0f, 6.0f,
            res("silicon", 350, "metaglass", 300), ing("bz-13", 1, "ca-03", 1)));

        cat.items.add(item("ca-13", "Harbinger's Seal", "Tri-rune orbital seal of ruin.",
            StationTier.CURSED_ALTAR, "thorium", "ff5252", AttachMode.ORBIT, true, true,
            0.25f, 0.30f, 0.15f, 0.20f, 0.30f, 6f, 8.0f,
            res("copper", 500, "lead", 500, "titanium", 300, "silicon", 300), ing("ca-02", 1)));

        cat.items.add(item("ca-14", "Eclipse Covenant", "Twin-star cursed crown uniting offense and speed.",
            StationTier.CURSED_ALTAR, "surge-alloy", "ff7979", AttachMode.HALO, true, true,
            0.30f, 0.35f, 0.20f, 0.25f, 0f, 8f, 10.0f,
            res("titanium", 450, "silicon", 450, "graphite", 400), ing("ca-04", 1, "ca-07", 1)));

        cat.items.add(item("ca-15", "Apotheosis Core", "Ultimate cursed artifact forged from abyssal relics.",
            StationTier.CURSED_ALTAR, "carbide", "ff3b6b", AttachMode.ORBIT, true, true,
            0.50f, 0.50f, 0.25f, 0.30f, 0.60f, 12f, 15.0f,
            res("copper", 800, "lead", 800, "titanium", 600, "silicon", 600), ing("ca-10", 1, "ca-11", 1)));

        // =========================================================================
        // BAZAAR ORBITAL MERCHANT SHIP TRADES (spends persistent Launch Pad orbital storage!)
        // =========================================================================
        cat.trades.add(trade("ship-01", "ISS Copperfield", " Salvaged Ancient Copper Relic",
            "Trade orbital copper & lead for a non-craftable Ancient Copper Relic (equip or decraft!).",
            res("copper", 300, "lead", 150), "wb-14", noRes()));

        cat.trades.add(trade("ship-02", "ISS Hephaestus", "Salvaged Smelter Core",
            "Trade orbital coal & graphite for a non-craftable Sealed Smelter Core.",
            res("graphite", 180, "coal", 120), "wb-15", noRes()));

        cat.trades.add(trade("ship-03", "HSV Horizon", "Orbital Vault Capsule",
            "Exchange orbital copper & silicon for a sealed Orbital Vault Capsule.",
            res("copper", 500, "silicon", 250), "bz-14", noRes()));

        cat.trades.add(trade("ship-04", "HSV Astral Nomad", "Starfarer's Blackbox",
            "Exchange heavy orbital titanium & silicon for a high-tech Starfarer's Blackbox.",
            res("titanium", 400, "silicon", 400), "bz-15", noRes()));

        cat.trades.add(trade("ship-05", "CSV Ironclad", "Titanium Carapace",
            "Commission an off-world Titanium Carapace straight from orbit.",
            res("copper", 600, "lead", 400, "titanium", 150), "bz-01", noRes()));

        cat.trades.add(trade("ship-06", "CSV Velocity", "Orbital Thruster Ring",
            "Purchase a factory-tuned Orbital Thruster Ring using orbital reserves.",
            res("silicon", 320, "graphite", 220), "bz-03", noRes()));

        cat.trades.add(trade("ship-07", "CSV Stormbringer", "Surge Dynamo",
            "Acquire a Surge Dynamo directly from an orbital weapons barge.",
            res("titanium", 350, "silicon", 350, "copper", 500), "bz-09", noRes()));

        cat.trades.add(trade("ship-08", "Drop-Pod Alpha", "Titanium Drop Crate (x200)",
            "Call down 200 Titanium into nearby map containers (excess vanishes if storage is full).",
            res("copper", 450, "lead", 300), null, res("titanium", 200)));

        cat.trades.add(trade("ship-09", "Drop-Pod Beta", "Silicon & Metaglass Crate (x200)",
            "Call down 200 Silicon + 150 Metaglass into nearby map storage.",
            res("copper", 500, "graphite", 250), null, res("silicon", 200, "metaglass", 150)));

        cat.trades.add(trade("ship-10", "Drop-Pod Gamma", "Exotic Alloy Drop (Thorium/Plast/Surge)",
            "Call down exotic off-world alloys into nearby map storage.",
            res("titanium", 500, "silicon", 500), null, res("thorium", 120, "plastanium", 80, "surge-alloy", 50)));

        cat.rebuildIndexes();
        return cat;
    }

    public static void save(Fi file, Catalog catalog) {
        if (file.parent() != null) file.parent().mkdirs();
        StringBuilder sb = new StringBuilder(16384);
        sb.append("# Item Shop Catalog (config/alphystuff/shop.hjson)\n");
        sb.append("# Tiers: workbench (15 items), bazaar (15 items), cursed-altar (15 items)\n");
        sb.append("# Attach modes: ORBIT | MOUNT | HALO | TRAIL\n\n");

        sb.append("items: [\n");
        for (ShopItemDef it : catalog.items) {
            sb.append("  {\n");
            sb.append("    id: ").append(it.id).append("\n");
            sb.append("    name: \"").append(esc(it.name)).append("\"\n");
            sb.append("    description: \"").append(esc(it.description)).append("\"\n");
            sb.append("    tier: ").append(it.tier.id).append("\n");
            sb.append("    iconItem: ").append(it.iconItem).append("\n");
            sb.append("    colorHex: \"").append(it.colorHex).append("\"\n");
            sb.append("    attachMode: ").append(it.attachMode.name()).append("\n");
            sb.append("    craftable: ").append(it.craftable).append("\n");
            sb.append("    decraftable: ").append(it.decraftable).append("\n");
            if (it.healthMultBonus != 0f) sb.append("    healthMultBonus: ").append(it.healthMultBonus).append("\n");
            if (it.damageMultBonus != 0f) sb.append("    damageMultBonus: ").append(it.damageMultBonus).append("\n");
            if (it.speedMultBonus != 0f) sb.append("    speedMultBonus: ").append(it.speedMultBonus).append("\n");
            if (it.reloadMultBonus != 0f) sb.append("    reloadMultBonus: ").append(it.reloadMultBonus).append("\n");
            if (it.buildSpeedMultBonus != 0f) sb.append("    buildSpeedMultBonus: ").append(it.buildSpeedMultBonus).append("\n");
            if (it.armorBonus != 0f) sb.append("    armorBonus: ").append(it.armorBonus).append("\n");
            if (it.regenPerSec != 0f) sb.append("    regenPerSec: ").append(it.regenPerSec).append("\n");
            sb.append("    resources: ").append(fmtRes(it.resourceCosts)).append("\n");
            if (!it.itemCosts.isEmpty()) {
                sb.append("    items: ").append(fmtIng(it.itemCosts)).append("\n");
            }
            sb.append("  }\n");
        }
        sb.append("]\n\n");

        sb.append("orbitalTrades: [\n");
        for (OrbitalTradeDef tr : catalog.trades) {
            sb.append("  {\n");
            sb.append("    id: ").append(tr.id).append("\n");
            sb.append("    shipName: \"").append(esc(tr.shipName)).append("\"\n");
            sb.append("    title: \"").append(esc(tr.title)).append("\"\n");
            sb.append("    description: \"").append(esc(tr.description)).append("\"\n");
            sb.append("    orbitalCost: ").append(fmtRes(tr.orbitalCosts)).append("\n");
            if (tr.rewardShopItemId != null) {
                sb.append("    rewardItem: ").append(tr.rewardShopItemId).append("\n");
            }
            if (!tr.rewardMapResources.isEmpty()) {
                sb.append("    rewardResources: ").append(fmtRes(tr.rewardMapResources)).append("\n");
            }
            sb.append("  }\n");
        }
        sb.append("]\n");

        file.writeString(sb.toString(), false, "UTF-8");
    }

    private static ShopItemDef item(
        String id, String name, String desc, StationTier tier,
        String iconItem, String colorHex, AttachMode mode,
        boolean craftable, boolean decraftable,
        float hp, float dmg, float spd, float rld, float bld, float arm, float regen,
        Seq<ResourceCost> resources, Seq<ItemIngredient> items
    ) {
        ShopItemDef d = new ShopItemDef();
        d.id = id;
        d.name = name;
        d.description = desc;
        d.tier = tier;
        d.iconItem = iconItem;
        d.colorHex = colorHex;
        d.attachMode = mode;
        d.craftable = craftable;
        d.decraftable = decraftable;
        d.healthMultBonus = hp;
        d.damageMultBonus = dmg;
        d.speedMultBonus = spd;
        d.reloadMultBonus = rld;
        d.buildSpeedMultBonus = bld;
        d.armorBonus = arm;
        d.regenPerSec = regen;
        d.resourceCosts.addAll(resources);
        d.itemCosts.addAll(items);
        return d;
    }

    private static OrbitalTradeDef trade(
        String id, String shipName, String title, String desc,
        Seq<ResourceCost> orbitalCosts, @Nullable String rewardItem, Seq<ResourceCost> rewardMapRes
    ) {
        OrbitalTradeDef t = new OrbitalTradeDef();
        t.id = id;
        t.shipName = shipName;
        t.title = title;
        t.description = desc;
        t.orbitalCosts.addAll(orbitalCosts);
        t.rewardShopItemId = rewardItem;
        t.rewardMapResources.addAll(rewardMapRes);
        return t;
    }

    private static Seq<ResourceCost> res(Object... kv) {
        Seq<ResourceCost> out = new Seq<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new ResourceCost((String) kv[i], ((Number) kv[i + 1]).intValue()));
        }
        return out;
    }

    private static Seq<ResourceCost> noRes() {
        return new Seq<>();
    }

    private static Seq<ItemIngredient> ing(Object... kv) {
        Seq<ItemIngredient> out = new Seq<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new ItemIngredient((String) kv[i], ((Number) kv[i + 1]).intValue()));
        }
        return out;
    }

    private static Seq<ItemIngredient> noItems() {
        return new Seq<>();
    }

    private static String fmtRes(Seq<ResourceCost> list) {
        if (list.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < list.size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).itemName()).append(": ").append(list.get(i).amount());
        }
        sb.append(" }");
        return sb.toString();
    }

    private static String fmtIng(Seq<ItemIngredient> list) {
        if (list.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < list.size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).shopItemId()).append(": ").append(list.get(i).amount());
        }
        sb.append(" }");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
