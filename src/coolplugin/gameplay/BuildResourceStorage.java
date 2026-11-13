package coolplugin.gameplay;

import arc.struct.Seq;
import coolplugin.techtree.TechResourceStorage;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.Item;

/**
 * Bridges {@link BuildSystem}'s personal reach-based storage (controlled unit stack +
 * carried/nearby containers and vaults) into {@link TechResourceStorage} so {@code /t}
 * Tech Tree research works seamlessly on coreless maps.
 */
public class BuildResourceStorage implements TechResourceStorage {

    private final BuildSystem buildSystem;

    public BuildResourceStorage(BuildSystem buildSystem) {
        this.buildSystem = buildSystem;
    }

    @Override
    public boolean isAvailable(Player player) {
        return player != null && !player.dead() && player.unit() != null;
    }

    @Override
    public int getAmount(Player player, Item item) {
        return buildSystem.getAvailableAmount(player, item);
    }

    @Override
    public int remove(Player player, Item item, int maxAmount) {
        return buildSystem.takeItems(player, item, maxAmount);
    }

    @Override
    public Seq<Item> displayItems(Player player, Seq<Item> treeItems) {
        Seq<Item> out = new Seq<>(treeItems);
        if (Vars.content != null) {
            for (Item it : Vars.content.items()) {
                if (!it.isHidden() && getAmount(player, it) > 0 && !out.contains(it)) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    @Override
    public String storageName(Player player) {
        return "Nearby Storage";
    }
}
