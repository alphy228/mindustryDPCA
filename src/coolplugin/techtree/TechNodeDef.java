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

    /** Unique node identifier within the tree (e.g. {@code "dagger"}, {@code "router"}). */
    public String id;
    /** Content type name (`unit`, `block`, `item`, `liquid`), or {@code "virtual"}. */
    public String contentType = "block";
    /** Content name inside {@code Vars.content}. */
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
    /** True if this node is a non-gameplay grouping node. */
    public boolean virtual = false;

    /** Resolved vanilla Mindustry content. */
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

    public int totalCostAmount(Item item) {
        for (ItemStack stack : costs) {
            if (stack.item == item) return stack.amount;
        }
        return 0;
    }
}
