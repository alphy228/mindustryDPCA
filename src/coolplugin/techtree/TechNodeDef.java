package coolplugin.techtree;

import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.ItemStack;

/**
 * Represents a single node inside the server's custom tech tree loaded from {@code config/alphystuff/techtree.hjson}.
 */
public class TechNodeDef {
    /** Unique node identifier within the tree (defaults to the vanilla content name, e.g. {@code "dagger"} or {@code "router"}). */
    public String id;
    /** Content type name (`unit`, `block`, `item`, `liquid`), or {@code "virtual"} for grouping roots. */
    public String contentType = "block";
    /** Content name inside {@code Vars.content} (e.g. {@code "dagger"}, {@code "mechanical-drill"}). */
    public String contentName;
    /** Optional custom display name override (used for virtual root or custom labels). */
    public @Nullable String displayName;
    /** Optional custom description override. */
    public @Nullable String description;
    /** Optional atlas/Icon drawable name override (defaults to {@code <contentType>-<contentName>-ui}). */
    public @Nullable String icon;
    /** True if this node starts unlocked by default for every player (e.g. the 7 Serpulo T1 units & virtual root). */
    public boolean alwaysUnlocked = false;
    /** True if this node is a non-gameplay organizational root node. */
    public boolean virtual = false;

    /** Resolved vanilla Mindustry content (null only if {@code virtual == true} or unknown content). */
    public transient @Nullable UnlockableContent content;
    /** Parent node in the tree (null for the root node). */
    public transient @Nullable TechNodeDef parent;
    /** Explicit item research costs loaded from {@code techtree.hjson}. */
    public ItemStack[] costs = ItemStack.empty;
    /** Optional special unlock conditions (currently empty by default). */
    public final Seq<UnlockCondition> conditions = new Seq<>();
    /** Child tech nodes branching from this node. */
    public final Seq<TechNodeDef> children = new Seq<>();

    /** Computed integer grid coordinates from {@link TechTreeLayoutEngine} for UI rendering. */
    public transient int gridX;
    public transient int gridY;

    public String resolvedName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (content != null && content.localizedName != null) return content.localizedName;
        return id;
    }

    public String resolvedDescription() {
        if (description != null && !description.isEmpty()) return description;
        if (content != null && content.description != null) return content.description;
        return "";
    }

    public String resolvedIcon() {
        if (icon != null && !icon.isEmpty()) return icon;
        if (content != null) {
            ContentType ct = content.getContentType();
            return ct.name() + "-" + content.name + "-ui";
        }
        return "units";
    }

    public int totalCostAmount(mindustry.type.Item item) {
        for (ItemStack stack : costs) {
            if (stack.item == item) return stack.amount;
        }
        return 0;
    }
}
