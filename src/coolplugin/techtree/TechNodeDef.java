package coolplugin.techtree;

import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Item;
import mindustry.type.ItemStack;

/**
 * Represents a single node inside {@code config/alphystuff/techtree.hjson}.
 */
public class TechNodeDef {
    public static final String DIR_UP = "up";
    public static final String DIR_DOWN = "down";
    public static final String DIR_LEFT = "left";
    public static final String DIR_RIGHT = "right";

    /** Unique node identifier within the tree (e.g. {@code "dagger"}, {@code "router"}, {@code "titanium"}). */
    public String id;
    /** Content type name (`unit`, `block`, `item`, `liquid`, `sector`, `status`, `resource`), or {@code "virtual"}. */
    public String contentType = "block";
    /** Content name inside {@code Vars.content} (resource/core database entry for icon &amp; localized text). */
    public String contentName;
    /** Layout growth direction relative to parent (`up`, `down`, `left`, `right`). */
    public String direction = DIR_DOWN;
    /** Optional custom display name override. */
    public @Nullable String displayName;
    /** Optional custom description override. */
    public @Nullable String description;
    /** Optional atlas/Icon drawable name override. */
    public @Nullable String icon;
    /** True if this node starts researched for every player (only {@code dagger} by default). */
    public boolean alwaysUnlocked = false;
    /**
     * True if this node cannot be researched with the UI button and is unlocked only when plugin code calls
     * {@code TechTreeService.unlockNode(...)} / {@code CoolPlugin.unlockTechNode(...)} for that player.
     */
    public boolean pluginUnlocked = false;
    /** True if this node is a non-gameplay grouping node. */
    public boolean virtual = false;

    /** Resolved Mindustry database entry ({@code Item}, {@code Liquid}, {@code Block}, {@code UnitType}, {@code SectorPreset}, etc.). */
    public transient @Nullable UnlockableContent content;
    /** Parent node in the tree (null for the root node). */
    public transient @Nullable TechNodeDef parent;
    /** Explicit item research costs loaded from {@code techtree.hjson}. */
    public ItemStack[] costs = ItemStack.empty;
    /** Optional special unlock conditions (empty by default). */
    public final Seq<UnlockCondition> conditions = new Seq<>();
    /** Child tech nodes branching from this node. */
    public final Seq<TechNodeDef> children = new Seq<>();

    /** Computed integer node-grid coordinates (`0..cols-1`, `0..rows-1`) from {@link TechTreeLayoutEngine}. */
    public transient int gridX;
    public transient int gridY;

    public static boolean isCustomOrModContent(@Nullable UnlockableContent c) {
        if (c == null) return false;
        return c.isPatchContent() || c.name.startsWith("dp-") || (c.minfo != null && c.minfo.mod != null);
    }

    /** Human-readable name for server logs and admin command output. */
    public String resolvedName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (content != null) {
            if (content.localizedName != null && !content.localizedName.equals(content.name)) {
                return content.localizedName;
            }
            return prettifyId(content.name);
        }
        return prettifyId(id);
    }

    /**
     * Client-localized title string for {@code MenuBuilder} {@code Label}s.
     * Vanilla database entries use {@code "@<type>.<name>.name"} so each client resolves its own {@code Core.bundle};
     * server DataPatch / mod entries use the HJSON {@code localizedName}.
     */
    public String uiNameText() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (content != null) {
            if (isCustomOrModContent(content)) {
                if (content.localizedName != null && !content.localizedName.isEmpty() && !content.localizedName.equals(content.name)) {
                    return content.localizedName;
                }
                String raw = content.name.startsWith("dp-") ? content.name.substring(3) : content.name;
                return prettifyId(raw);
            }
            return "@" + content.getContentType().name() + "." + content.name + ".name";
        }
        return resolvedName();
    }

    /**
     * Client-localized description string for {@code MenuBuilder} {@code Label}s.
     */
    public String uiDescriptionText() {
        if (description != null && !description.isEmpty()) return description;
        if (content != null) {
            if (isCustomOrModContent(content)) {
                return content.description != null ? content.description : "";
            }
            if (content.getContentType() == ContentType.status) {
                return "";
            }
            return "@" + content.getContentType().name() + "." + content.name + ".description";
        }
        return "";
    }

    public String resolvedIcon() {
        if (icon != null && !icon.isEmpty()) return coolplugin.gameplay.UiSpriteSync.resolveMenuDrawable(icon);
        if (content != null) {
            if (isCustomOrModContent(content)) {
                if (coolplugin.gameplay.UiSpriteSync.hasSprite(content.name)) {
                    return coolplugin.gameplay.UiSpriteSync.resolveMenuDrawable(content.name);
                }
                return content.name + "-ui";
            }
            ContentType ct = content.getContentType();
            return ct.name() + "-" + content.name + "-ui";
        }
        return "units";
    }

    /** Optional fallback sprite region for server DataPatch sprites packed without a {@code -ui} suffix. */
    public @Nullable String resolvedIconFallback() {
        if (icon != null && !icon.isEmpty()) return null;
        if (content != null && isCustomOrModContent(content)) {
            return content.name;
        }
        return null;
    }

    public int totalCostAmount(Item item) {
        for (ItemStack stack : costs) {
            if (stack.item == item) return stack.amount;
        }
        return 0;
    }

    public static String prettifyId(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.split("[-_]");
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }
}
