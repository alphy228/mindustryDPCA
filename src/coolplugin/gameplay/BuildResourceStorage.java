package coolplugin.gameplay;

import arc.struct.Seq;
import coolplugin.techtree.TechResourceStorage;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
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

    private final PlayerDatabase database;
    private final PlayerSessionManager sessions;

    public BuildResourceStorage(BuildSystem buildSystem) {
        this(buildSystem, null, null);
    }

    public BuildResourceStorage(BuildSystem buildSystem, PlayerDatabase database, PlayerSessionManager sessions) {
        this.buildSystem = buildSystem;
        this.database = database;
        this.sessions = sessions;
    }

    private int orbitalAmount(Player player, Item item) {
        if (player == null || item == null || database == null || sessions == null) return 0;
        var account = sessions.getOrInitSession(player);
        return database.getOrbitalItemAmount(account.id, sessions.getActiveFactionId(player.uuid()), item.name);
    }

    @Override
    public boolean isAvailable(Player player) {
        return player != null && (database != null || (!player.dead() && player.unit() != null));
    }

    @Override
    public int getAmount(Player player, Item item) {
        if (player == null || item == null) return 0;
        return (int)Math.min(Integer.MAX_VALUE, (long)orbitalAmount(player, item)
            + buildSystem.getAvailableAmount(player, item));
    }

    @Override
    public int remove(Player player, Item item, int maxAmount) {
        if (player == null || item == null || maxAmount <= 0) return 0;
        int orbital = Math.min(maxAmount, orbitalAmount(player, item));
        if (orbital > 0) {
            var account = sessions.getOrInitSession(player);
            if (!database.takeOrbitalItem(account.id, sessions.getActiveFactionId(player.uuid()), item.name, orbital)) {
                orbital = 0;
            }
        }
        return orbital + buildSystem.takeItems(player, item, maxAmount - orbital);
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
        return "World + Orbital";
    }
}
