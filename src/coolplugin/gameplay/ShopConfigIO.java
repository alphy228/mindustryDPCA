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
import coolplugin.gameplay.ShopItemDef.HaloAnchor;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.MountSubtype;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.ShopCategory;
import coolplugin.gameplay.ShopItemDef.StationTier;

/**
 * Loads and autogenerates {@code config/alphystuff/shop.hjson} containing:
 * <ul>
 *   <li>15 placeholder items for {@link StationTier#WORKBENCH} ({@code wb-01 .. wb-15})</li>
 *   <li>15 placeholder items for {@link StationTier#BAZAAR} ({@code bz-01 .. bz-15})</li>
 *   <li>15 placeholder items for {@link StationTier#CURSED_ALTAR} ({@code ca-01 .. ca-15})</li>
 *   <li>4 Deadlock-style categories ({@code Resource}, {@code Attack}, {@code Defense}, {@code Utility})</li>
 *   <li>4 world attachment systems ({@code MOUNT} with subtypes &amp; auto-symmetry, {@code HALO} on
 *       {@code CENTER}/{@code WEAPONS}/{@code ENGINES} with configurable {@code Fx} effects,
 *       {@code FOLLOW} floating standoff companions, and {@code SUBWEAPON} active unit weapons).</li>
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

        public Seq<ShopItemDef> itemsForTierAndCategory(StationTier tier, ShopCategory category) {
            return items.select(i -> i.tier == tier && i.category == category);
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
                item.category = ShopCategory.parse(v.getString("category", inferCategory(item).id));
                item.sprite = v.getString("sprite", "shop-" + item.id);
                item.iconItem = v.getString("iconItem", "copper");
                item.colorHex = v.getString("colorHex", item.tier.accentHex);
                item.attachMode = AttachMode.parse(v.getString("attachMode", "FOLLOW"));

                item.mountSubtype = MountSubtype.parse(v.getString("mountSubtype", null), item.attachMode);
                item.mountForwardOffset = v.getFloat("mountForwardOffset", 0f);
                item.mountSideOffset = v.getFloat("mountSideOffset", 0f);
                item.mountAngleOffset = v.has("mountAngleOffset") ? v.getFloat("mountAngleOffset", 0f) : Float.NaN;

                item.haloAnchor = HaloAnchor.parse(v.getString("haloAnchor", "CENTER"));
                item.haloEffect = v.getString("haloEffect", "none");
                item.vfxStatus = v.getString("vfxStatus", "none");
                JsonValue statuses=v.get("wearerStatuses");
                if(statuses!=null&&statuses.isArray())for(JsonValue entry=statuses.child;entry!=null;entry=entry.next)item.wearerStatuses.add(entry.asString());
                item.haloColorHex = v.getString("haloColorHex", "");
                item.haloRadius = v.getFloat("haloRadius", 12f);
                item.haloInterval = v.getFloat("haloInterval", 26f);
                item.haloSides = Math.max(3, Math.min(24, v.getInt("haloSides", 6)));

                item.subweaponPreset = v.getString("subweaponPreset", "kinetic");
                item.subweaponShootSound=v.getString("subweaponShootSound","none");
                item.subweaponSoundVolume=Math.max(0f,Math.min(2f,v.getFloat("subweaponSoundVolume",0.6f)));
                item.subweaponSoundPitch=Math.max(0.25f,Math.min(3f,v.getFloat("subweaponSoundPitch",1f)));
                item.subweaponReload = Math.max(3f, v.getFloat("subweaponReload", 16f));
                item.subweaponDamage = Math.max(1f, v.getFloat("subweaponDamage", 11f));
                item.subweaponRange = Math.max(40f, v.getFloat("subweaponRange", 160f));

                item.followDistance = v.getFloat("followDistance", 10.5f);

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
                if (!v.has("category")) {
                    item.category = inferCategory(item);
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

    private static ShopCategory inferCategory(ShopItemDef item) {
        if (!item.craftable && item.decraftable) return ShopCategory.RESOURCE;
        if (item.damageMultBonus > 0f || item.reloadMultBonus > 0f) return ShopCategory.ATTACK;
        if (item.healthMultBonus > 0f || item.armorBonus > 0f || item.regenPerSec > 0f) return ShopCategory.DEFENSE;
        if (item.speedMultBonus > 0f || item.buildSpeedMultBonus > 0f) return ShopCategory.UTILITY;
        return ShopCategory.RESOURCE;
    }

    private static void parseResourceMap(@Nullable JsonValue obj, Seq<ResourceCost> dest) {
        if (obj == null || !obj.isObject()) return;
        for (JsonValue e = obj.child; e != null; e = e.next) {
            int amt = e.asInt();
            if (e.name != null && amt > 0) {
                dest.add(new ResourceCost(e.name, amt));
            }
        }
    }

    public static void save(Fi file, Catalog cat) {
        if (file.parent() != null) file.parent().mkdirs();
        StringBuilder sb = new StringBuilder(20480);
        sb.append("# Item Shop Catalog (Placeholder Items + VFX/Status Examples + Bazaar Orbital Ship Trades)\n");
        sb.append("# Categories: resource | attack | defense | utility\n");
        sb.append("# attachMode: MOUNT | HALO | FOLLOW | SUBWEAPON | VFX (ORBIT removed)\n");
        sb.append("# mountSubtype: SIDE_SHIELD | WING_BLADE | SHOULDER_PAD | REAR_THRUSTER | SIDE_POD | WING_POD | NOSE_PROW | SPINE_CORE | TAIL_BOOSTER | DORSAL_TURRET\n");
        sb.append("# haloAnchor: CENTER | WEAPONS | ENGINES  (haloEffect accepts any mindustry.content.Fx field name)\n");
        sb.append("# Active passive bonuses apply to inventory positions 1..4; positions 5..10 grant no buffs and give -10% speed each.\n\n");

        sb.append("items: [\n");
        for (ShopItemDef it : cat.items) {
            sb.append("  {\n");
            sb.append("    id: ").append(quote(it.id)).append("\n");
            sb.append("    name: ").append(quote(it.name)).append("\n");
            sb.append("    description: ").append(quote(it.description)).append("\n");
            sb.append("    tier: ").append(it.tier.id).append("\n");
            sb.append("    category: ").append(it.category.id).append("\n");
            sb.append("    sprite: ").append(quote(it.effectiveSprite())).append("\n");
            sb.append("    iconItem: ").append(it.iconItem).append("\n");
            sb.append("    colorHex: ").append(quote(it.colorHex)).append("\n");
            sb.append("    attachMode: ").append(it.attachMode.name()).append("\n");

            if (it.attachMode == AttachMode.MOUNT || it.attachMode == AttachMode.SUBWEAPON) {
                sb.append("    mountSubtype: ").append(it.mountSubtype.name()).append("\n");
                if (it.mountForwardOffset != 0f) sb.append("    mountForwardOffset: ").append(fmt(it.mountForwardOffset)).append("\n");
                if (it.mountSideOffset != 0f) sb.append("    mountSideOffset: ").append(fmt(it.mountSideOffset)).append("\n");
                if (!Float.isNaN(it.mountAngleOffset)) sb.append("    mountAngleOffset: ").append(fmt(it.mountAngleOffset)).append("\n");
                if (it.attachMode == AttachMode.SUBWEAPON) {
                    sb.append("    subweaponPreset: ").append(quote(it.subweaponPreset)).append("\n");
                    sb.append("    subweaponReload: ").append(fmt(it.subweaponReload)).append("\n");
                    sb.append("    subweaponDamage: ").append(fmt(it.subweaponDamage)).append("\n");
                    sb.append("    subweaponRange: ").append(fmt(it.subweaponRange)).append("\n");
                }
            } else if (it.attachMode == AttachMode.HALO || it.attachMode == AttachMode.VFX) {
                sb.append("    haloAnchor: ").append(it.haloAnchor.name()).append("\n");
                sb.append("    haloSides: ").append(it.haloSides).append("\n");
                sb.append("    haloEffect: ").append(quote(it.haloEffect)).append("\n");
                if (it.haloColorHex != null && !it.haloColorHex.isEmpty()) {
                    sb.append("    haloColorHex: ").append(quote(it.haloColorHex)).append("\n");
                }
                sb.append("    haloRadius: ").append(fmt(it.haloRadius)).append("\n");
                sb.append("    haloInterval: ").append(fmt(it.haloInterval)).append("\n");
            } else if (it.attachMode == AttachMode.FOLLOW) {
                sb.append("    followDistance: ").append(fmt(it.followDistance)).append("\n");
            }

            if(it.attachMode==AttachMode.SUBWEAPON){
                sb.append("    subweaponShootSound: ").append(quote(it.subweaponShootSound)).append("\n");
                sb.append("    subweaponSoundVolume: ").append(fmt(it.subweaponSoundVolume)).append("\n");
                sb.append("    subweaponSoundPitch: ").append(fmt(it.subweaponSoundPitch)).append("\n");
            }
            if(it.attachMode==AttachMode.VFX)sb.append("    vfxStatus: ").append(quote(it.vfxStatus)).append("\n");
            if(!it.wearerStatuses.isEmpty()){
                sb.append("    wearerStatuses: [");
                for(int si=0;si<it.wearerStatuses.size;si++){if(si>0)sb.append(", ");sb.append(quote(it.wearerStatuses.get(si)));}
                sb.append("]\n");
            }

            sb.append("    craftable: ").append(it.craftable).append("\n");
            sb.append("    decraftable: ").append(it.decraftable).append("\n");
            if (it.healthMultBonus != 0f) sb.append("    healthMultBonus: ").append(fmt(it.healthMultBonus)).append("\n");
            if (it.damageMultBonus != 0f) sb.append("    damageMultBonus: ").append(fmt(it.damageMultBonus)).append("\n");
            if (it.speedMultBonus != 0f) sb.append("    speedMultBonus: ").append(fmt(it.speedMultBonus)).append("\n");
            if (it.reloadMultBonus != 0f) sb.append("    reloadMultBonus: ").append(fmt(it.reloadMultBonus)).append("\n");
            if (it.buildSpeedMultBonus != 0f) sb.append("    buildSpeedMultBonus: ").append(fmt(it.buildSpeedMultBonus)).append("\n");
            if (it.armorBonus != 0f) sb.append("    armorBonus: ").append(fmt(it.armorBonus)).append("\n");
            if (it.regenPerSec != 0f) sb.append("    regenPerSec: ").append(fmt(it.regenPerSec)).append("\n");

            if (!it.resourceCosts.isEmpty()) {
                sb.append("    resources: { ");
                for (int i = 0; i < it.resourceCosts.size; i++) {
                    ResourceCost rc = it.resourceCosts.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(rc.itemName()).append(": ").append(rc.amount());
                }
                sb.append(" }\n");
            }
            if (!it.itemCosts.isEmpty()) {
                sb.append("    items: { ");
                for (int i = 0; i < it.itemCosts.size; i++) {
                    ItemIngredient ic = it.itemCosts.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(quote(ic.shopItemId())).append(": ").append(ic.amount());
                }
                sb.append(" }\n");
            }
            sb.append("  }\n");
        }
        sb.append("]\n\n");

        sb.append("orbitalTrades: [\n");
        for (OrbitalTradeDef tr : cat.trades) {
            sb.append("  {\n");
            sb.append("    id: ").append(quote(tr.id)).append("\n");
            sb.append("    shipName: ").append(quote(tr.shipName)).append("\n");
            sb.append("    title: ").append(quote(tr.title)).append("\n");
            sb.append("    description: ").append(quote(tr.description)).append("\n");
            if (!tr.orbitalCosts.isEmpty()) {
                sb.append("    orbitalCost: { ");
                for (int i = 0; i < tr.orbitalCosts.size; i++) {
                    ResourceCost rc = tr.orbitalCosts.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(rc.itemName()).append(": ").append(rc.amount());
                }
                sb.append(" }\n");
            }
            if (tr.rewardShopItemId != null && !tr.rewardShopItemId.isEmpty()) {
                sb.append("    rewardItem: ").append(quote(tr.rewardShopItemId)).append("\n");
            }
            if (!tr.rewardMapResources.isEmpty()) {
                sb.append("    rewardResources: { ");
                for (int i = 0; i < tr.rewardMapResources.size; i++) {
                    ResourceCost rc = tr.rewardMapResources.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(rc.itemName()).append(": ").append(rc.amount());
                }
                sb.append(" }\n");
            }
            sb.append("  }\n");
        }
        sb.append("]\n");

        file.writeString(sb.toString(), false, "UTF-8");
    }

    public static Catalog createDefaultCatalog() {
        Catalog c = new Catalog();

        // =========================================================================
        // TIER 1: WORKBENCH (15 Placeholder Items: 4 Resource, 4 Attack, 4 Defense, 3 Utility)
        // =========================================================================
        // Resource (4)
        add(c, follow(item("wb-01", "Copper Chunk", "300 copper compacted into a dense transport crate.",
            StationTier.WORKBENCH, ShopCategory.RESOURCE, "copper", "d99d73", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("copper", 300), items()), 10.0f));
        add(c, follow(item("wb-02", "Lead Bundle", "300 lead bars strapped together for field hauling.",
            StationTier.WORKBENCH, ShopCategory.RESOURCE, "lead", "8c7fa9", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("lead", 300), items()), 11.0f));
        add(c, follow(item("wb-03", "Graphite Brick", "200 pressed carbon plates locked in a heavy frame.",
            StationTier.WORKBENCH, ShopCategory.RESOURCE, "graphite", "95abd9", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("graphite", 200), items()), 12.0f));
        add(c, mount(item("wb-04", "Scrap Relic Core", "Recovered core husk; cannot be crafted, only decrafted for resources.",
            StationTier.WORKBENCH, ShopCategory.RESOURCE, "copper", "eab678", AttachMode.MOUNT, false, true,
            0.04f, 0f, 0f, 0f, 0f, 0f, 0f, res("copper", 140, "lead", 110, "graphite", 55), items()),
            MountSubtype.SPINE_CORE));

        // Attack (4)
        add(c, mount(item("wb-05", "Copper Fang", "Sharpened copper wing blades boosting weapon damage.",
            StationTier.WORKBENCH, ShopCategory.ATTACK, "copper", "eab678", AttachMode.MOUNT, true, true,
            0f, 0.12f, 0f, 0f, 0f, 0f, 0f, res("copper", 110, "lead", 60), items()),
            MountSubtype.WING_BLADE));
        add(c, subweapon(item("wb-06", "Graphite Striker", "Twin hull-mounted autocannon pods firing kinetic bolts.",
            StationTier.WORKBENCH, ShopCategory.ATTACK, "graphite", "95abd9", AttachMode.SUBWEAPON, true, true,
            0f, 0f, 0f, 0.12f, 0f, 0f, 0f, res("graphite", 65, "copper", 90), items()),
            MountSubtype.SIDE_POD, "kinetic", 16f, 10f, 150f));
        add(c, mount(item("wb-07", "Silicon Sight", "Prow-mounted optical rangefinder improving damage and fire rate.",
            StationTier.WORKBENCH, ShopCategory.ATTACK, "silicon", "8e94a4", AttachMode.MOUNT, true, true,
            0f, 0.10f, 0f, 0.08f, 0f, 0f, 0f, res("silicon", 70, "metaglass", 35), items()),
            MountSubtype.NOSE_PROW));
        add(c, mount(item("wb-08", "Twin Fang Array", "Dual linked Copper Fangs forged into symmetrical wing blades.",
            StationTier.WORKBENCH, ShopCategory.ATTACK, "copper", "ffd37f", AttachMode.MOUNT, true, true,
            0f, 0.22f, 0f, 0.06f, 0f, 0f, 0f, res("silicon", 60, "graphite", 40), items("wb-05", 2)),
            MountSubtype.WING_BLADE));

        // Defense (4)
        add(c, mount(item("wb-09", "Lead Buckler", "Riveted flank shields bolstering hull health and armor.",
            StationTier.WORKBENCH, ShopCategory.DEFENSE, "lead", "8c7fa9", AttachMode.MOUNT, true, true,
            0.18f, 0f, 0f, 0f, 0f, 2f, 0f, res("lead", 150, "copper", 80), items()),
            MountSubtype.SIDE_SHIELD));
        add(c, mount(item("wb-10", "Ceramic Plate", "Kiln-fired metaglass cheek armor plating.",
            StationTier.WORKBENCH, ShopCategory.DEFENSE, "metaglass", "d8e2ee", AttachMode.MOUNT, true, true,
            0.22f, 0f, 0f, 0f, 0f, 3f, 0f, res("metaglass", 75, "lead", 90), items()),
            MountSubtype.SIDE_SHIELD));
        add(c, halo(item("wb-11", "Mending Vial", "Pressurized repair gel projecting a restorative core halo.",
            StationTier.WORKBENCH, ShopCategory.DEFENSE, "silicon", "84f491", AttachMode.HALO, true, true,
            0.08f, 0f, 0f, 0f, 0f, 0f, 2.5f, res("silicon", 60, "lead", 80, "copper", 80), items()),
            HaloAnchor.CENTER, 6, "none", 10f, 30f));
        add(c, mount(item("wb-12", "Bastion Carapace", "Reinforced Lead Bucklers layered with dense graphite ribs.",
            StationTier.WORKBENCH, ShopCategory.DEFENSE, "graphite", "eab678", AttachMode.MOUNT, true, true,
            0.30f, 0f, 0f, 0f, 0f, 4f, 0f, res("graphite", 80, "metaglass", 45), items("wb-09", 1)),
            MountSubtype.SIDE_SHIELD));

        // Utility (3)
        add(c, mount(item("wb-13", "Glass Thruster", "Centerline metaglass jet nozzle boosting movement speed.",
            StationTier.WORKBENCH, ShopCategory.UTILITY, "metaglass", "7fe6ff", AttachMode.MOUNT, true, true,
            0f, 0f, 0.14f, 0f, 0f, 0f, 0f, res("metaglass", 55, "copper", 95), items()),
            MountSubtype.TAIL_BOOSTER));
        add(c, mount(item("wb-14", "Builder's Servo", "Symmetrical shoulder construction claws accelerating build speed.",
            StationTier.WORKBENCH, ShopCategory.UTILITY, "silicon", "ffd37f", AttachMode.MOUNT, true, true,
            0f, 0f, 0f, 0f, 0.28f, 0f, 0f, res("silicon", 65, "graphite", 50, "copper", 80), items()),
            MountSubtype.SHOULDER_PAD));
        add(c, follow(item("wb-15", "Scout Gyro", "Floating telemetry gyroscope enhancing speed and construction.",
            StationTier.WORKBENCH, ShopCategory.UTILITY, "silicon", "84f491", AttachMode.FOLLOW, true, true,
            0f, 0f, 0.10f, 0f, 0.15f, 0f, 0f, res("silicon", 85, "metaglass", 40), items()), 9.0f));

        // =========================================================================
        // TIER 2: BAZAAR (15 Placeholder Items: 4 Resource, 4 Attack, 4 Defense, 3 Utility)
        // =========================================================================
        // Resource (4)
        add(c, follow(item("bz-01", "Titanium Crate", "400 titanium rods locked inside a floating cargo pod.",
            StationTier.BAZAAR, ShopCategory.RESOURCE, "titanium", "8da1e3", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("titanium", 400), items()), 11.0f));
        add(c, follow(item("bz-02", "Silicon Wafer Pack", "350 high-purity silicon wafers sealed for transit.",
            StationTier.BAZAAR, ShopCategory.RESOURCE, "silicon", "7fe6ff", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("silicon", 350), items()), 12.0f));
        add(c, follow(item("bz-03", "Thorium Cell", "300 thorium rods encased in a shielded floating canister.",
            StationTier.BAZAAR, ShopCategory.RESOURCE, "thorium", "f9a3c7", AttachMode.FOLLOW, true, true,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, res("thorium", 300), items()), 13.0f));
        add(c, mount(item("bz-04", "Orbital Blackbox", "Orbital ship salvage; non-craftable, decrafts into advanced alloys.",
            StationTier.BAZAAR, ShopCategory.RESOURCE, "plastanium", "7fe6ff", AttachMode.MOUNT, false, true,
            0f, 0f, 0.06f, 0f, 0.12f, 0f, 0f, res("silicon", 220, "titanium", 180, "plastanium", 90), items()),
            MountSubtype.SPINE_CORE));

        // Attack (4)
        add(c, subweapon(item("bz-05", "Plastanium Repeater", "Twin rotary subweapon pods unloading rapid plastanium flechettes.",
            StationTier.BAZAAR, ShopCategory.ATTACK, "plastanium", "cbd97e", AttachMode.SUBWEAPON, true, true,
            0f, 0.12f, 0f, 0.22f, 0f, 0f, 0f, res("plastanium", 110, "silicon", 140, "titanium", 90), items()),
            MountSubtype.SIDE_POD, "blaster", 12f, 13f, 170f));
        add(c, mount(item("bz-06", "Surge Sabre", "Twin Fang Array reforged with high-voltage surge wing blades.",
            StationTier.BAZAAR, ShopCategory.ATTACK, "surge-alloy", "f3e979", AttachMode.MOUNT, true, true,
            0f, 0.32f, 0f, 0.10f, 0f, 0f, 0f, res("surge-alloy", 80, "silicon", 140), items("wb-08", 1)),
            MountSubtype.WING_BLADE));
        add(c, subweapon(item("bz-07", "Blast Cannon Pod", "Dorsal artillery subweapon firing high-explosive blast shells.",
            StationTier.BAZAAR, ShopCategory.ATTACK, "blast-compound", "ff795e", AttachMode.SUBWEAPON, true, true,
            0f, 0.26f, 0f, 0.10f, 0f, 0f, 0f, res("blast-compound", 90, "titanium", 120, "graphite", 100), items()),
            MountSubtype.DORSAL_TURRET, "mortar", 46f, 38f, 225f));
        add(c, subweapon(item("bz-08", "Phase Lance", "Wing-mounted phase lance subweapons firing armor-piercing bolts.",
            StationTier.BAZAAR, ShopCategory.ATTACK, "phase-fabric", "f4ba6e", AttachMode.SUBWEAPON, true, true,
            0f, 0.38f, 0f, 0.15f, 0f, 0f, 0f, res("phase-fabric", 95, "thorium", 130, "silicon", 150), items()),
            MountSubtype.WING_POD, "pierce", 20f, 24f, 190f));

        // Defense (4)
        add(c, mount(item("bz-09", "Titanium Aegis", "Bastion Carapace reinforced with heavy titanium flank kite plates.",
            StationTier.BAZAAR, ShopCategory.DEFENSE, "titanium", "7595e6", AttachMode.MOUNT, true, true,
            0.38f, 0f, 0f, 0f, 0f, 6f, 0f, res("titanium", 150, "silicon", 100), items("wb-12", 1)),
            MountSubtype.SIDE_SHIELD));
        add(c, halo(item("bz-10", "Thorium Reactor Core", "Miniature thorium core radiating a hexagonal reactor halo.",
            StationTier.BAZAAR, ShopCategory.DEFENSE, "thorium", "f9a3c7", AttachMode.HALO, true, true,
            0.45f, 0f, 0f, 0f, 0f, 2f, 6.0f, res("thorium", 160, "silicon", 130, "metaglass", 80), items()),
            HaloAnchor.CENTER, 6, "none", 12f, 28f));
        add(c, mount(item("bz-11", "Plastanium Mesh", "Impact-absorbing honeycomb shoulder armor shells.",
            StationTier.BAZAAR, ShopCategory.DEFENSE, "plastanium", "a8d474", AttachMode.MOUNT, true, true,
            0.32f, 0f, 0f, 0f, 0f, 8f, 0f, res("plastanium", 130, "titanium", 120, "graphite", 90), items()),
            MountSubtype.SHOULDER_PAD));
        add(c, halo(item("bz-12", "Regen Halo", "Projector ring bathing the unit center in a steady repair halo.",
            StationTier.BAZAAR, ShopCategory.DEFENSE, "plastanium", "84f491", AttachMode.HALO, true, true,
            0.20f, 0f, 0f, 0f, 0f, 0f, 12.0f, res("plastanium", 100, "phase-fabric", 65, "silicon", 140), items()),
            HaloAnchor.CENTER, 8, "none", 14f, 24f));

        // Utility (3)
        add(c, mount(item("bz-13", "Phase Blink Drive", "Glass Thruster upgraded into twin phase-folded rear nacelles.",
            StationTier.BAZAAR, ShopCategory.UTILITY, "phase-fabric", "f4ba6e", AttachMode.MOUNT, true, true,
            0f, 0f, 0.26f, 0f, 0f, 0f, 0f, res("phase-fabric", 90, "silicon", 130), items("wb-13", 1)),
            MountSubtype.REAR_THRUSTER));
        add(c, halo(item("bz-14", "Nano Fabricator", "Autonomous nanobot halos energizing every weapon mount.",
            StationTier.BAZAAR, ShopCategory.UTILITY, "plastanium", "84f491", AttachMode.HALO, true, true,
            0f, 0f, 0f, 0f, 0.55f, 0f, 4.0f, res("plastanium", 110, "silicon", 180, "titanium", 100), items()),
            HaloAnchor.WEAPONS, 4, "none", 6.0f, 26f));
        add(c, halo(item("bz-15", "Astral Compass", "Engine-core overdrive halos boosting flight speed and building.",
            StationTier.BAZAAR, ShopCategory.UTILITY, "surge-alloy", "7fe6ff", AttachMode.HALO, true, true,
            0f, 0f, 0.20f, 0f, 0.35f, 0f, 0f, res("surge-alloy", 75, "phase-fabric", 60, "silicon", 150), items()),
            HaloAnchor.ENGINES, 4, "none", 6.5f, 22f));

        // =========================================================================
        // TIER 3: CURSED ALTAR (15 Placeholder Items: 3 Resource, 4 Attack, 4 Defense, 4 Utility)
        // =========================================================================
        // Resource (3)
        add(c, halo(item("ca-01", "Philosopher's Husk", "Condensed forbidden matter radiating a crimson core halo.",
            StationTier.CURSED_ALTAR, ShopCategory.RESOURCE, "surge-alloy", "d9385a", AttachMode.HALO, true, false,
            0.25f, 0.25f, 0f, 0f, 0f, 0f, 0f, res("surge-alloy", 500, "phase-fabric", 500, "thorium", 1000), items()),
            HaloAnchor.CENTER, 5, "none", 14f, 26f));
        add(c, follow(item("ca-02", "Void Reliquary", "Abyssal coffer floating silently at fixed standoff behind its bearer.",
            StationTier.CURSED_ALTAR, ShopCategory.RESOURCE, "thorium", "bf92f9", AttachMode.FOLLOW, true, false,
            0.30f, 0f, 0f, 0f, 0.40f, 4f, 0f, res("plastanium", 800, "silicon", 1200, "titanium", 1000), items()), 12.0f));
        add(c, follow(item("ca-03", "Blood Ingot", "Floating scarlet alloy bar empowering weapons at hull cost.",
            StationTier.CURSED_ALTAR, ShopCategory.RESOURCE, "blast-compound", "ff4b6e", AttachMode.FOLLOW, true, false,
            -0.08f, 0.35f, 0f, 0f, 0f, 0f, 0f, res("blast-compound", 700, "pyratite", 700, "copper", 2000), items()), 10.0f));

        // Attack (4)
        add(c, mount(item("ca-04", "Bloodthirst Scythe", "Twin reaper wing blades granting massive damage at health cost.",
            StationTier.CURSED_ALTAR, ShopCategory.ATTACK, "blast-compound", "ff4b6e", AttachMode.MOUNT, true, false,
            -0.15f, 0.75f, 0f, 0.15f, 0f, 0f, 0f, res("surge-alloy", 450, "blast-compound", 600), items()),
            MountSubtype.WING_BLADE));
        add(c, subweapon(item("ca-05", "Abyssal Gatling", "Twin demonic rotary subweapons unleashing incendiary barrages.",
            StationTier.CURSED_ALTAR, ShopCategory.ATTACK, "pyratite", "ff6b5b", AttachMode.SUBWEAPON, true, false,
            0f, 0.30f, -0.10f, 0.65f, 0f, 0f, 0f, res("pyratite", 650, "surge-alloy", 400, "silicon", 900), items()),
            MountSubtype.SIDE_POD, "incendiary", 7f, 18f, 200f));
        add(c, mount(item("ca-06", "Ruinblade", "Corrupted Surge Sabres mounted symmetrically across the hull.",
            StationTier.CURSED_ALTAR, ShopCategory.ATTACK, "surge-alloy", "ff2e55", AttachMode.MOUNT, true, false,
            0f, 0.90f, 0f, 0.20f, -0.20f, 0f, 0f, res("surge-alloy", 600, "thorium", 800), items("bz-06", 1)),
            MountSubtype.WING_BLADE));
        add(c, halo(item("ca-07", "Eye of Malice", "Crimson sigil halos wreathed around every weapon mount.",
            StationTier.CURSED_ALTAR, ShopCategory.ATTACK, "phase-fabric", "ff5e7e", AttachMode.HALO, true, false,
            -0.12f, 0.55f, 0f, 0.45f, 0f, 0f, 0f, res("phase-fabric", 550, "silicon", 1000, "spore-pod", 600), items()),
            HaloAnchor.WEAPONS, 4, "none", 6.5f, 22f));

        // Defense (4)
        add(c, mount(item("ca-08", "Leviathan Husk", "Colossal obsidian flank carapaces doubling hull health and armor.",
            StationTier.CURSED_ALTAR, ShopCategory.DEFENSE, "thorium", "ff4b6e", AttachMode.MOUNT, true, false,
            1.20f, 0f, -0.12f, 0f, 0f, 18f, 0f, res("thorium", 1200, "plastanium", 800, "titanium", 1200), items()),
            MountSubtype.SIDE_SHIELD));
        add(c, halo(item("ca-09", "Undying Heart", "Crimson core halo regenerating tremendous health.",
            StationTier.CURSED_ALTAR, ShopCategory.DEFENSE, "phase-fabric", "ff2e55", AttachMode.HALO, true, false,
            0.60f, -0.15f, 0f, 0f, 0f, 0f, 35.0f, res("phase-fabric", 650, "plastanium", 700, "spore-pod", 800), items()),
            HaloAnchor.CENTER, 6, "none", 13f, 20f));
        add(c, mount(item("ca-10", "Dread Bulwark", "Titanium Aegis fused with cursed spikes for extreme flank armor.",
            StationTier.CURSED_ALTAR, ShopCategory.DEFENSE, "surge-alloy", "ffd37f", AttachMode.MOUNT, true, false,
            0.90f, 0f, 0f, -0.15f, 0f, 25f, 0f, res("surge-alloy", 550, "thorium", 900), items("bz-09", 1)),
            MountSubtype.SIDE_SHIELD));
        add(c, halo(item("ca-11", "Martyr's Crown", "Iron-ember halo crown sustaining the bearer through heavy fire.",
            StationTier.CURSED_ALTAR, ShopCategory.DEFENSE, "pyratite", "ff6b5b", AttachMode.HALO, true, false,
            0.80f, 0f, 0f, 0f, 0f, 8f, 20.0f, res("pyratite", 700, "thorium", 800, "metaglass", 900), items()),
            HaloAnchor.CENTER, 8, "none", 15f, 30f));

        // Utility (4)
        add(c, mount(item("ca-12", "Wraith Stride", "Phase Blink Drive twisted into spectral crimson tail wings.",
            StationTier.CURSED_ALTAR, ShopCategory.UTILITY, "phase-fabric", "ff4b6e", AttachMode.MOUNT, true, false,
            -0.18f, 0f, 0.50f, 0f, 0f, 0f, 0f, res("phase-fabric", 700, "surge-alloy", 350), items("bz-13", 1)),
            MountSubtype.REAR_THRUSTER));
        add(c, mount(item("ca-13", "Architect's Curse", "Forbidden golden shoulder claws erecting structures at blinding speed.",
            StationTier.CURSED_ALTAR, ShopCategory.UTILITY, "surge-alloy", "ffd37f", AttachMode.MOUNT, true, false,
            0f, -0.15f, 0f, 0f, 1.40f, 0f, 0f, res("silicon", 1500, "surge-alloy", 500, "plastanium", 600), items()),
            MountSubtype.SHOULDER_PAD));
        add(c, follow(item("ca-14", "Chrono Hourglass", "Floating crimson sandglass bending movement and reload time.",
            StationTier.CURSED_ALTAR, ShopCategory.UTILITY, "phase-fabric", "ff5e7e", AttachMode.FOLLOW, true, false,
            -0.15f, 0f, 0.35f, 0.40f, 0f, 0f, 0f, res("phase-fabric", 800, "silicon", 1100), items()), 9.5f));
        add(c, halo(item("ca-15", "Apotheosis Halo", "Ultimate grand crown halo converging offense, bastion, and flight.",
            StationTier.CURSED_ALTAR, ShopCategory.UTILITY, "surge-alloy", "ffd37f", AttachMode.HALO, true, false,
            0.50f, 0.50f, 0.30f, 0.25f, 0.50f, 12f, 15.0f,
            res("surge-alloy", 1000, "phase-fabric", 1000),
            items("ca-04", 1, "ca-08", 1, "ca-12", 1)),
            HaloAnchor.CENTER, 8, "none", 18f, 22f));

        // =========================================================================
        // BAZAAR ORBITAL SHIP TRADES (Spend persistent Launch Pad orbital storage)
        // =========================================================================
        c.trades.add(trade("ship-01", "ISV Heliotrope", "Scrap Relic Salvage Drop",
            "Exchange launched orbital copper & lead for a Scrap Relic Core.",
            res("copper", 500, "lead", 400), "wb-04", res()));
        c.trades.add(trade("ship-02", "ISV Meridian", "Orbital Blackbox Recovery",
            "Trade launched silicon & titanium for an Orbital Blackbox.",
            res("silicon", 450, "titanium", 350), "bz-04", res()));
        c.trades.add(trade("ship-03", "CSV Starfarer", "Silicon Drop-Pod",
            "Ship 600 orbital copper & 400 coal; drops 300 silicon into map storage.",
            res("copper", 600, "coal", 400), null, res("silicon", 300)));
        c.trades.add(trade("ship-04", "CSV Ironclad", "Titanium Cargo Pod",
            "Exchange 800 orbital lead & 400 graphite for 350 titanium in map storage.",
            res("lead", 800, "graphite", 400), null, res("titanium", 350)));
        c.trades.add(trade("ship-05", "ISV Halcyon", "Thorium Isotope Cask",
            "Exchange 500 orbital titanium & 400 silicon for 260 thorium in map storage.",
            res("titanium", 500, "silicon", 400), null, res("thorium", 260)));
        c.trades.add(trade("ship-06", "MSV Vespera", "Plastanium Polymer Crate",
            "Trade 600 orbital titanium & 300 spore-pod for 220 plastanium in map storage.",
            res("titanium", 600, "spore-pod", 300), null, res("plastanium", 220)));
        c.trades.add(trade("ship-07", "MSV Auric", "Surge Smelter Shipment",
            "Trade 700 orbital copper, 700 lead & 500 silicon for 140 surge-alloy.",
            res("copper", 700, "lead", 700, "silicon", 500), null, res("surge-alloy", 140)));
        c.trades.add(trade("ship-08", "ISV Loomweaver", "Phase Weave Bundle",
            "Trade 500 orbital thorium & 600 sand for 150 phase-fabric.",
            res("thorium", 500, "sand", 600), null, res("phase-fabric", 150)));
        c.trades.add(trade("ship-09", "CSV Vanguard", "Chartered Surge Sabre",
            "Commission an orbital armorer to deliver a Surge Sabre directly to your inventory.",
            res("surge-alloy", 220, "silicon", 400, "titanium", 300), "bz-06", res()));
        c.trades.add(trade("ship-10", "ISV Celestia", "Chartered Astral Compass",
            "Acquire an Astral Compass from the deep-space guild fleet.",
            res("surge-alloy", 180, "phase-fabric", 180, "silicon", 400), "bz-15", res()));

        addEffectExamples(c);
        c.rebuildIndexes();
        return c;
    }

    private static void addEffectExamples(Catalog c){
        for(int i=0;i<3;i++){
            ShopItemDef d=new ShopItemDef();
            d.id=new String[]{"temp-ember-vfx","temp-overclock","temp-mending-vfx"}[i];
            d.name=new String[]{"Ember Projector","Overclock Injector","Mending Spark"}[i];
            d.description=new String[]{"Temporary cosmetic burning particles; does not burn the wearer.","Temporary overclock status while equipped in active slots 1-4.","Temporary cosmetic healing particles; does not heal the wearer."}[i];
            d.attachMode=AttachMode.VFX;d.iconItem=new String[]{"pyratite","silicon","phase-fabric"}[i];
            d.vfxStatus=i==0?"burning":i==1?"overclock":"none";
            d.haloEffect=i==2?"heal":"none";d.haloInterval=18f;d.haloRadius=0f;
            if(i==1)d.wearerStatuses.add("overclock");
            d.resourceCosts.add(new ResourceCost("copper",75));d.resourceCosts.add(new ResourceCost("silicon",35));
            c.items.add(d);
        }
        for(ShopItemDef d:c.items)if(d.attachMode==AttachMode.SUBWEAPON)d.subweaponShootSound="shoot";
    }

    private static void add(Catalog c, ShopItemDef def) {
        c.items.add(def);
    }

    private static ShopItemDef mount(ShopItemDef d, MountSubtype subtype) {
        d.mountSubtype = subtype;
        return d;
    }

    private static ShopItemDef subweapon(
        ShopItemDef d,
        MountSubtype subtype,
        String preset,
        float reload,
        float damage,
        float range
    ) {
        d.mountSubtype = subtype;
        d.subweaponPreset = preset;
        d.subweaponReload = reload;
        d.subweaponDamage = damage;
        d.subweaponRange = range;
        return d;
    }

    private static ShopItemDef halo(ShopItemDef d, HaloAnchor anchor, int sides, String fx, float radius, float interval) {
        d.haloAnchor = anchor;
        d.haloSides = sides;
        d.haloEffect = fx;
        d.haloRadius = radius;
        d.haloInterval = interval;
        return d;
    }

    private static ShopItemDef follow(ShopItemDef d, float distance) {
        d.followDistance = distance;
        return d;
    }

    private static ShopItemDef item(
        String id,
        String name,
        String desc,
        StationTier tier,
        ShopCategory category,
        String iconItem,
        String colorHex,
        AttachMode mode,
        boolean craftable,
        boolean decraftable,
        float hp,
        float dmg,
        float spd,
        float rld,
        float bld,
        float armor,
        float regen,
        Seq<ResourceCost> resources,
        Seq<ItemIngredient> items
    ) {
        ShopItemDef d = new ShopItemDef();
        d.id = id;
        d.name = name;
        d.description = desc;
        d.tier = tier;
        d.category = category;
        d.sprite = "shop-" + id;
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
        d.armorBonus = armor;
        d.regenPerSec = regen;
        d.resourceCosts.addAll(resources);
        d.itemCosts.addAll(items);
        return d;
    }

    private static OrbitalTradeDef trade(
        String id,
        String shipName,
        String title,
        String desc,
        Seq<ResourceCost> cost,
        @Nullable String rewardItem,
        Seq<ResourceCost> rewardRes
    ) {
        OrbitalTradeDef t = new OrbitalTradeDef();
        t.id = id;
        t.shipName = shipName;
        t.title = title;
        t.description = desc;
        t.orbitalCosts.addAll(cost);
        t.rewardShopItemId = rewardItem;
        t.rewardMapResources.addAll(rewardRes);
        return t;
    }

    private static Seq<ResourceCost> res(Object... kv) {
        Seq<ResourceCost> out = new Seq<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new ResourceCost((String) kv[i], ((Number) kv[i + 1]).intValue()));
        }
        return out;
    }

    private static Seq<ItemIngredient> items(Object... kv) {
        Seq<ItemIngredient> out = new Seq<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new ItemIngredient((String) kv[i], ((Number) kv[i + 1]).intValue()));
        }
        return out;
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.0001f) return Integer.toString(Math.round(v));
        return String.format(java.util.Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
