package coolplugin.techtree;

import arc.struct.Seq;
import mindustry.gen.Player;
import mindustry.type.Item;

/**
 * Pluggable per-player resource storage interface for TechTree research spending.
 * Replace {@link CoreResourceStorage} with your custom server-side resource bank
 * via {@link TechTreeService#setStorage(TechResourceStorage)} without touching any
 * menu, persistence, or HJSON code.
 */
public interface TechResourceStorage {

    /** Returns how much of {@code item} {@code player} currently has available to spend. */
    int getAmount(Player player, Item item);

    /**
     * Removes up to {@code amount} of {@code item} from {@code player}'s available bank.
     *
     * @return the exact amount actually deducted (`0 .. amount`).
     */
    int remove(Player player, Item item, int amount);

    /** Returns true if {@code player} currently has a valid storage bank connected (e.g. a living Core). */
    default boolean isAvailable(Player player) {
        return player != null;
    }

    /** Optional label describing where resources are drawn from in the UI header. */
    default String storageName(Player player) {
        return "Core";
    }

    /** Returns which items should be displayed in the top resource bar of the tech tree dialog. */
    Seq<Item> displayItems(Player player, Seq<Item> treeReferencedItems);
}
