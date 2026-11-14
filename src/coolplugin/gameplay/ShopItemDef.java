package coolplugin.gameplay;

import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.type.Item;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Data definitions for Item Shop tiers, Deadlock-style shop categories, equipable items,
 * custom sprites, world attachment systems ({@link AttachMode#MOUNT}, {@link AttachMode#HALO},
 * {@link AttachMode#FOLLOW}, {@link AttachMode#SUBWEAPON}), symmetry-preserving mount subtypes,
 * configurable halo anchors/effects, crafting/decrafting recipes, and Bazaar orbital ship trades.
 */
public final class ShopItemDef {

    public enum StationTier {
        WORKBENCH("workbench", "Workbench", "dp-workbench", "shop-inv-hex-workbench", "ffd37f", "eab678"),
        BAZAAR("bazaar", "Bazaar", "dp-bazaar", "shop-inv-hex-bazaar", "7fe6ff", "4aa8d8"),
        CURSED_ALTAR("cursed-altar", "Cursed Altar", "dp-cursed-altar", "shop-inv-hex-altar", "ff4b6e", "a8284e");

        public final String id;
        public final String title;
        public final String blockName;
        public final String invHexSprite;
        public final String accentHex;
        public final String borderHex;

        StationTier(
            String id,
            String title,
            String blockName,
            String invHexSprite,
            String accentHex,
            String borderHex
        ) {
            this.id = id;
            this.title = title;
            this.blockName = blockName;
            this.invHexSprite = invHexSprite;
            this.accentHex = accentHex;
            this.borderHex = borderHex;
        }

        public static @Nullable StationTier byIdOrBlock(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return null;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "workbench", "dp-workbench", "wb", "1" -> WORKBENCH;
                case "bazaar", "dp-bazaar", "bz", "2" -> BAZAAR;
                case "cursed-altar", "dp-cursed-altar", "altar", "cursed", "ca", "3" -> CURSED_ALTAR;
                default -> null;
            };
        }
    }

    public enum ShopCategory {
        RESOURCE("resource", "Resource", "eab678"),
        ATTACK("attack", "Attack", "ff6b5b"),
        DEFENSE("defense", "Defense", "6ad99a"),
        UTILITY("utility", "Utility", "7fd8ff");

        public final String id;
        public final String title;
        public final String accentHex;

        ShopCategory(String id, String title, String accentHex) {
            this.id = id;
            this.title = title;
            this.accentHex = accentHex;
        }

        public static ShopCategory parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return UTILITY;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            for (ShopCategory c : values()) {
                if (c.id.equals(s) || c.name().equalsIgnoreCase(s)) return c;
            }
            return UTILITY;
        }
    }

    /**
     * World visual/functional behavior for an equipped item (Orbit is removed).
     */
    public enum AttachMode {
        /** Attaches flush to the unit's hull sprite using {@link MountSubtype}; duplicates symmetrically if off-axis. */
        MOUNT("Mount"),
        /** Displays geometric HaloPart + configurable {@link Fx} halo effects on unit center, weapons, or engines. */
        HALO("Halo"),
        /** Floating companion that smoothly follows the unit and maintains a specific standoff distance. */
        FOLLOW("Follow"),
        /** Becomes a real rotating, recoiling, firing weapon mounted on the unit (symmetrically duplicated if off-axis). */
        SUBWEAPON("Subweapon"),
        /** Cosmetic particles only; no world attachment sprite or halo geometry. */
        VFX("VFX");

        public final String label;

        AttachMode(String label) {
            this.label = label;
        }

        public static AttachMode parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return FOLLOW;
            String s = raw.trim().toUpperCase(Locale.ROOT);
            // Backwards-compatible mapping for older configs where ORBIT or TRAIL existed
            if ("ORBIT".equals(s) || "TRAIL".equals(s) || "FLOATING".equals(s)) {
                return FOLLOW;
            }
            if ("WEAPON".equals(s) || "SUB_WEAPON".equals(s)) {
                return SUBWEAPON;
            }
            for (AttachMode m : values()) {
                if (m.name().equalsIgnoreCase(s)) return m;
            }
            return FOLLOW;
        }
    }

    /**
     * Anatomical hardpoint subtypes for {@link AttachMode#MOUNT} and {@link AttachMode#SUBWEAPON}.
     * Any subtype with {@code sideRatio != 0} (or non-zero {@code mountSideOffset}) is off the longitudinal
     * symmetry axis and is automatically duplicated into a mirrored Left + Right pair!
     */
    public enum MountSubtype {
        // Off-axis hull mounts (automatically duplicated symmetrically Left + Right)
        SIDE_SHIELD(0.04f, 0f, 0.78f, 0.55f, -18f),
        WING_BLADE(0.16f, 0f, 0.84f, 0.75f, 26f),
        SHOULDER_PAD(0.44f, 0.3f, 0.64f, 0.45f, 12f),
        REAR_THRUSTER(-0.66f, -0.4f, 0.50f, 0.55f, -16f),
        SIDE_POD(0.06f, 0f, 0.78f, 0.75f, 0f),
        WING_POD(-0.14f, 0f, 0.86f, 0.90f, 0f),

        // On-symmetry-axis hull mounts (side == 0 -> single centered mount along heading axis)
        NOSE_PROW(0.72f, 0.55f, 0f, 0f, 0f),
        SPINE_CORE(-0.12f, 0f, 0f, 0f, 0f),
        TAIL_BOOSTER(-0.76f, -0.55f, 0f, 0f, 0f),
        DORSAL_TURRET(-0.08f, 0f, 0f, 0f, 0f);

        public final float forwardRatio;
        public final float forwardExtra;
        public final float sideRatio;
        public final float sideExtra;
        public final float defaultAngleOffset;

        MountSubtype(
            float forwardRatio,
            float forwardExtra,
            float sideRatio,
            float sideExtra,
            float defaultAngleOffset
        ) {
            this.forwardRatio = forwardRatio;
            this.forwardExtra = forwardExtra;
            this.sideRatio = sideRatio;
            this.sideExtra = sideExtra;
            this.defaultAngleOffset = defaultAngleOffset;
        }

        public boolean isDefaultOffAxis() {
            return Math.abs(sideRatio) > 0.001f || Math.abs(sideExtra) > 0.001f;
        }

        public static MountSubtype parse(@Nullable String raw, AttachMode mode) {
            if (raw != null && !raw.isEmpty()) {
                String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
                for (MountSubtype st : values()) {
                    if (st.name().equalsIgnoreCase(s)) return st;
                }
            }
            return mode == AttachMode.SUBWEAPON ? SIDE_POD : SIDE_SHIELD;
        }
    }

    /**
     * Placement target for {@link AttachMode#HALO} items.
     */
    public enum HaloAnchor {
        /** Displays at the center of the unit. */
        CENTER,
        /** Displays at the center of every weapon mount on the unit. */
        WEAPONS,
        /** Displays at the center of every engine on the unit. */
        ENGINES;

        public static HaloAnchor parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return CENTER;
            String s = raw.trim().toUpperCase(Locale.ROOT);
            if ("WEAPON".equals(s)) return WEAPONS;
            if ("ENGINE".equals(s)) return ENGINES;
            for (HaloAnchor a : values()) {
                if (a.name().equalsIgnoreCase(s)) return a;
            }
            return CENTER;
        }
    }

    public record ResourceCost(String itemName, int amount) {
        public @Nullable Item resolveItem() {
            return Vars.content == null ? null : Vars.content.item(itemName);
        }
    }

    public record ItemIngredient(String shopItemId, int amount) {}

    public static final class OrbitalTradeDef {
        public String id;
        public String shipName;
        public String title;
        public String description;
        public final Seq<ResourceCost> orbitalCosts = new Seq<>();
        public @Nullable String rewardShopItemId;
        public final Seq<ResourceCost> rewardMapResources = new Seq<>();
    }

    public String id;
    public String name;
    public String description;
    public StationTier tier = StationTier.WORKBENCH;
    public ShopCategory category = ShopCategory.UTILITY;
    public String sprite = ""; // legacy shared sprite; retained for old configs
    public String iconSprite = "", worldSprite = "";
    /** Inventory capacity cost, independent of world sprite scale. */
    public int size=1;
    public String iconItem = "copper";
    public String mountWeaponSprite = "";
    public String colorHex = "ffd37f";
    public AttachMode attachMode = AttachMode.FOLLOW;

    // Mount / Subweapon placement & symmetry configuration
    public MountSubtype mountSubtype = MountSubtype.SIDE_SHIELD;
    public float mountForwardOffset = 0f;
    public float mountSideOffset = 0f;
    public float mountAngleOffset = Float.NaN;

    // Halo placement & steady geometric ShapeMarker / optional Fx parameters
    public HaloAnchor haloAnchor = HaloAnchor.CENTER;
    public String haloEffect = "none";
    public String vfxStatus = "none";
    /** Real statuses, granted only while equipped. */
    public final Seq<String> wearerStatuses = new Seq<>();
    public String haloColorHex = "";
    public float haloRadius = 12f;
    public float haloInterval = 26f;
    public int haloSides = 6;

    // Subweapon server projectile parameters (fired via Call.createBullet only while equipped)
    public String subweaponPreset = "kinetic";
    /** Opt-in autonomous targeting; otherwise this mount fires only with the wearer. */
    public boolean subweaponAutoTarget=false;
    public float subweaponRecoil = 2.5f, subweaponRecoilTime = 12f;
    public String subweaponShootEffect = "shootSmall", subweaponSmokeEffect = "shootSmallSmoke";
    public String subweaponShootSound = "none";
    public float subweaponSoundVolume = 0.6f, subweaponSoundPitch = 1f;
    public float subweaponReload = 16f;
    public float subweaponDamage = 11f;
    public float subweaponRange = 160f;

    // Floating / tail follower configuration
    public float followDistance = 10.5f;

    /** True if players can craft this item at its station; false for salvage/relic items that can only be decrafted. */
    public boolean hidden = true;
    public boolean craftable = true;
    /** True if players can break this item down at Workbench/Bazaar into its recipe components. */
    public boolean decraftable = true;

    // Passive bonuses granted ONLY while in active inventory positions 1..4 (indices 0..3)
    public float healthMultBonus = 0f;
    public float damageMultBonus = 0f;
    public float speedMultBonus = 0f;
    public float reloadMultBonus = 0f;
    public float buildSpeedMultBonus = 0f;
    public float armorBonus = 0f;
    public float regenPerSec = 0f;

    public final Seq<ResourceCost> resourceCosts = new Seq<>();
    public final Seq<ItemIngredient> itemCosts = new Seq<>();

    public boolean hasRecipe() {
        return !resourceCosts.isEmpty() || !itemCosts.isEmpty();
    }

    /**
     * Computes the lateral offset from the longitudinal symmetry axis for a unit of radius {@code hullRadius}.
     * Whenever {@code Math.abs(computeSideOffset(hullRadius)) > 0.01f}, the mount/subweapon is duplicated
     * symmetrically onto both left ({@code +side}) and right ({@code -side})!
     */
    public float computeSideOffset(float hullRadius) {
        return mountSubtype.sideRatio * hullRadius + mountSubtype.sideExtra + mountSideOffset;
    }

    public float computeForwardOffset(float hullRadius) {
        return mountSubtype.forwardRatio * hullRadius + mountSubtype.forwardExtra + mountForwardOffset;
    }

    public float effectiveMountAngleOffset() {
        return Float.isNaN(mountAngleOffset) ? mountSubtype.defaultAngleOffset : mountAngleOffset;
    }

    public boolean isSymmetricallyDuplicated(float hullRadius) {
        return Math.abs(computeSideOffset(hullRadius)) > 0.01f;
    }

    public Color resolveHaloColor() {
        String hex = (haloColorHex != null && !haloColorHex.isBlank()) ? haloColorHex.trim() : colorHex;
        try {
            return Color.valueOf(hex);
        } catch (Throwable ignored) {
            return Color.valueOf("ffd37f");
        }
    }

    public @Nullable Effect resolveHaloFx() {
        if (haloEffect == null || haloEffect.isBlank() || "none".equalsIgnoreCase(haloEffect.trim())) {
            var status=resolveWearerStatus(vfxStatus);
            return status==null?null:status.effect;
        }
        String fxName = haloEffect.trim();
        try {
            Field f = Fx.class.getField(fxName);
            Object val = f.get(null);
            if (val instanceof Effect eff && eff != Fx.none) {
                return eff;
            }
        } catch (Throwable ignored) {
        }
        return Fx.dynamicWave;
    }

    public static @Nullable mindustry.type.StatusEffect resolveWearerStatus(String name){
        if(name==null||name.isBlank()||Vars.content==null)return null;
        var effect=Vars.content.statusEffect(name);
        // Reactive statuses are one-shot damage events, not wearable continuous buffs.
        return effect==null||effect==mindustry.content.StatusEffects.none||effect==mindustry.content.StatusEffects.dynamic||effect.reactive?null:effect;
    }

    public @Nullable arc.audio.Sound resolveShootSound(){
        if(subweaponShootSound==null||subweaponShootSound.isBlank()||"none".equalsIgnoreCase(subweaponShootSound))return null;
        try{Object value=mindustry.gen.Sounds.class.getField(subweaponShootSound).get(null);return value instanceof arc.audio.Sound sound?sound:null;}
        catch(ReflectiveOperationException ignored){return null;}
    }

    public @Nullable Effect resolveWeaponEffect(String name){
        if(name==null||name.isBlank()||"none".equalsIgnoreCase(name))return null;
        try{Object value=Fx.class.getField(name).get(null);return value instanceof Effect effect?effect:null;}
        catch(ReflectiveOperationException ignored){return null;}
    }
    public String vanillaTurretSprite(){
        return switch(subweaponPreset.toLowerCase(Locale.ROOT)){
            case "mortar","artillery","blast"->"hail";
            case "pierce","lance","phase"->"lancer";
            case "incendiary","pyratite","gatling"->"spectre";
            case "blaster","homing","plastanium"->"cyclone";
            default->"duo";
        };
    }

    public String effectiveSprite() {
        if (sprite != null && !sprite.isBlank()) return sprite.trim();
        return "shop-" + id;
    }

    public String uiDrawable() {
        String sp = iconSprite!=null&&!iconSprite.isBlank()?iconSprite.trim():effectiveSprite();
        if (UiSpriteSync.hasSprite(sp)) {
            return UiSpriteSync.resolveMenuDrawable(sp);
        }
        if(iconSprite!=null&&!iconSprite.isBlank())return sp;
        return "item-" + iconItem + "-ui";
    }



    public String worldTextureName() {
        if(worldSprite!=null&&!worldSprite.isBlank())return worldSprite.trim();
        if(attachMode==AttachMode.MOUNT || attachMode==AttachMode.SUBWEAPON)return "dp-item-mount-"+id;
        if(attachMode==AttachMode.SUBWEAPON)return vanillaTurretSprite();
        String sp = effectiveSprite();
        if (sp.startsWith("dp-") || sp.startsWith("item-") || sp.startsWith("block-") || sp.startsWith("unit-")) {
            return sp;
        }
        return "dp-" + sp;
    }

    public String visualUnitName() {
        return worldTextureName();
    }

    public String worldGlyph() {
        if (Vars.content != null) {
            Item it = Vars.content.item(iconItem);
            if (it != null && it.hasEmoji()) {
                return it.emoji();
            }
        }
        return "*";
    }

    public boolean matchesSearch(@Nullable String rawQuery, @Nullable ShopConfigIO.Catalog catalog) {
        if (rawQuery == null || rawQuery.isBlank()) return true;
        String q = rawQuery.trim().toLowerCase(Locale.ROOT);
        if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (id != null && id.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (description != null && description.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (category != null && category.title.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (healthMultBonus != 0f && ("hp".contains(q) || "health".contains(q))) return true;
        if (damageMultBonus != 0f && ("dmg".contains(q) || "damage".contains(q))) return true;
        if (speedMultBonus != 0f && ("spd".contains(q) || "speed".contains(q))) return true;
        if (reloadMultBonus != 0f && ("rld".contains(q) || "reload".contains(q))) return true;
        if (buildSpeedMultBonus != 0f && ("bld".contains(q) || "build".contains(q))) return true;
        if (armorBonus != 0f && ("arm".contains(q) || "armor".contains(q))) return true;
        if (regenPerSec != 0f && ("regen".contains(q) || "heal".contains(q))) return true;
        for (ResourceCost rc : resourceCosts) {
            if (rc.itemName().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        if (catalog != null) {
            for (ItemIngredient ing : itemCosts) {
                ShopItemDef sub = catalog.findItem(ing.shopItemId());
                if (sub != null && sub.name != null && sub.name.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String formatPassiveSummary() {
        Seq<String> parts = new Seq<>();
        if (healthMultBonus != 0f) parts.add(fmtPct(healthMultBonus, "HP"));
        if (damageMultBonus != 0f) parts.add(fmtPct(damageMultBonus, "DMG"));
        if (speedMultBonus != 0f) parts.add(fmtPct(speedMultBonus, "SPD"));
        if (reloadMultBonus != 0f) parts.add(fmtPct(reloadMultBonus, "RLD"));
        if (buildSpeedMultBonus != 0f) parts.add(fmtPct(buildSpeedMultBonus, "BLD"));
        if (armorBonus != 0f) parts.add((armorBonus > 0 ? "[stat]+" : "[scarlet]") + Math.round(armorBonus) + " ARM[]");
        if (regenPerSec != 0f) parts.add("[stat]+" + fmtNum(regenPerSec) + "/s[]");

        if (!parts.isEmpty()) {
            return parts.toString(" ");
        }
        if (!craftable && decraftable) {
            return "[#eab678]Salvageable[]";
        }
        if (decraftable && resourceCosts.size == 1 && itemCosts.isEmpty()) {
            ResourceCost rc = resourceCosts.first();
            return "[#eab678]Packs " + rc.amount() + " " + capitalize(rc.itemName()) + "[]";
        }
        if (decraftable && !resourceCosts.isEmpty()) {
            return "[#eab678]Resource Pack[]";
        }
        return "[lightgray]Relic[]";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmtPct(float v, String stat) {
        int pct = Math.round(v * 100f);
        return (pct >= 0 ? "[stat]+" : "[scarlet]") + pct + "% " + stat + "[]";
    }

    private static String fmtNum(float v) {
        if (Math.abs(v - Math.round(v)) < 0.01f) return Integer.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
