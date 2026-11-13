package coolplugin.gameplay;

import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.type.Item;

/**
 * Data definitions for Item Shop tiers, equipable items, visual attachment modes,
 * crafting/decrafting recipes, and Bazaar orbital ship trades.
 */
public final class ShopItemDef {

    public enum StationTier {
        WORKBENCH("workbench", "Workbench", "dp-workbench", "ffd37f", "eab678"),
        BAZAAR("bazaar", "Bazaar", "dp-bazaar", "7fe6ff", "4aa8d8"),
        CURSED_ALTAR("cursed-altar", "Cursed Altar", "dp-cursed-altar", "ff4b6e", "a8284e");

        public final String id;
        public final String title;
        public final String blockName;
        public final String accentHex;
        public final String borderHex;

        StationTier(String id, String title, String blockName, String accentHex, String borderHex) {
            this.id = id;
            this.title = title;
            this.blockName = blockName;
            this.accentHex = accentHex;
            this.borderHex = borderHex;
        }

        public static @Nullable StationTier byIdOrBlock(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return null;
            String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (s) {
                case "workbench", "dp-workbench", "wb", "1" -> WORKBENCH;
                case "bazaar", "dp-bazaar", "bz", "2" -> BAZAAR;
                case "cursed-altar", "dp-cursed-altar", "altar", "cursed", "ca", "3" -> CURSED_ALTAR;
                default -> null;
            };
        }
    }

    public enum AttachMode {
        /** Revolves smoothly around the unit in a circular orbit. */
        ORBIT("Orbit"),
        /** Mounted on left/right hull hardpoints rotating with the unit's heading. */
        MOUNT("Mount"),
        /** Hovers in a bobbing crown arc above the unit. */
        HALO("Halo"),
        /** Trails behind the unit in a smooth follow-the-leader wake. */
        TRAIL("Trail");

        public final String label;

        AttachMode(String label) {
            this.label = label;
        }

        public static AttachMode parse(@Nullable String raw) {
            if (raw == null) return ORBIT;
            for (AttachMode m : values()) {
                if (m.name().equalsIgnoreCase(raw.trim())) return m;
            }
            return ORBIT;
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
    public String iconItem = "copper";
    public String colorHex = "ffd37f";
    public AttachMode attachMode = AttachMode.ORBIT;

    /** True if players can craft this item at its station; false for salvage/relic items that can only be decrafted. */
    public boolean craftable = true;
    /** True if players can break this item down at Workbench/Bazaar into its recipe components. */
    public boolean decraftable = true;

    // Passive bonuses granted ONLY while in equipped slots 1..4 (indices 0..3)
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

    public String uiDrawable() {
        return "item-" + iconItem + "-ui";
    }

    public String worldGlyph() {
        if (Vars.content != null) {
            Item it = Vars.content.item(iconItem);
            if (it != null && it.hasEmoji()) {
                return it.emoji();
            }
        }
        return switch (attachMode) {
            case ORBIT -> "o";
            case MOUNT -> "+";
            case HALO -> "*";
            case TRAIL -> "~";
        };
    }

    public String formatPassiveSummary() {
        Seq<String> parts = new Seq<>();
        if (healthMultBonus != 0f) parts.add(fmtPct(healthMultBonus, "HP"));
        if (damageMultBonus != 0f) parts.add(fmtPct(damageMultBonus, "DMG"));
        if (speedMultBonus != 0f) parts.add(fmtPct(speedMultBonus, "SPD"));
        if (reloadMultBonus != 0f) parts.add(fmtPct(reloadMultBonus, "RLD"));
        if (buildSpeedMultBonus != 0f) parts.add(fmtPct(buildSpeedMultBonus, "BLD"));
        if (armorBonus != 0f) parts.add((armorBonus > 0 ? "+" : "") + Math.round(armorBonus) + " ARM");
        if (regenPerSec != 0f) parts.add("+" + fmtNum(regenPerSec) + " HP/s");
        return parts.isEmpty() ? "Cosmetic Trinket" : parts.toString(", ");
    }

    private static String fmtPct(float v, String stat) {
        int pct = Math.round(v * 100f);
        return (pct >= 0 ? "[stat]+" : "[scarlet]") + pct + "% " + stat + "[]";
    }

    private static String fmtNum(float v) {
        if (Math.abs(v - Math.round(v)) < 0.01f) return Integer.toString(Math.round(v));
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
