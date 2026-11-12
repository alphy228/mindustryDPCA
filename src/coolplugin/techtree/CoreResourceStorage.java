package coolplugin.techtree;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

/**
 * Default {@link TechResourceStorage} backed by the player's team Core inventory.
 * Swap out via {@link TechTreeService#setStorage(TechResourceStorage)} when adding custom plugin storage.
 */
public class CoreResourceStorage implements TechResourceStorage {

    @Override
    public int getAmount(Player player, Item item) {
        if (player == null || item == null || Vars.state == null) return 0;
        CoreBuild core = player.team().core();
        if (core == null || core.items == null) return 0;
        return Math.max(core.items.get(item), 0);
    }

    @Override
    public int remove(Player player, Item item, int amount) {
        if (amount <= 0 || player == null || item == null || Vars.state == null) return 0;
        CoreBuild core = player.team().core();
        if (core == null || core.items == null) return 0;
        ItemModule items = core.items;
        int available = Math.max(items.get(item), 0);
        int taken = Math.min(available, amount);
        if (taken > 0) {
            items.remove(item, taken);
        }
        return taken;
    }

    @Override
    public boolean isAvailable(Player player) {
        return player != null && Vars.state != null && player.team().core() != null;
    }

    @Override
    public Seq<Item> displayItems(Player player, Seq<Item> treeReferencedItems) {
        return Vars.content != null ? Vars.content.items() : treeReferencedItems;
    }
}
