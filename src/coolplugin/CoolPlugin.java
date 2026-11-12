package coolplugin;

import arc.files.Fi;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import coolplugin.techtree.TechNodeDef;
import coolplugin.techtree.TechResourceStorage;
import coolplugin.techtree.TechTreeCommands;
import coolplugin.techtree.TechTreeService;

/**
 * Main Mindustry server plugin entry point (`config/mods/coolplugin.jar`).
 * All Tech Tree logic, item research costs, HJSON serialization, MenuBuilder UI,
 * and resource storage abstractions live inside {@code coolplugin.techtree}.
 */
public class CoolPlugin extends Plugin {

    private TechTreeService techTree;

    @Override
    public void init() {
        ensureTechTreeCreated();
        techTree.init();
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        ensureTechTreeCreated();
        TechTreeCommands.registerServerCommands(handler, techTree);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        ensureTechTreeCreated();
        TechTreeCommands.registerClientCommands(handler, techTree);
    }

    /** Exposes the {@link TechTreeService} so plugin subsystems can plug in custom {@link TechResourceStorage}s or conditions. */
    public TechTreeService getTechTree() {
        ensureTechTreeCreated();
        return techTree;
    }

    public void setTechResourceStorage(TechResourceStorage storage) {
        ensureTechTreeCreated();
        techTree.setStorage(storage);
    }

    /** Unlocks a custom (or standard) tech tree node by {@code nodeId} for {@code player} and persists it. */
    public boolean unlockTechNode(Player player, String nodeId) {
        ensureTechTreeCreated();
        return techTree.unlockNode(player, nodeId);
    }

    /** Unlocks any custom tech tree node(s) referencing {@code databaseEntry} (e.g. {@code Items.titanium}) for {@code player}. */
    public boolean unlockTechNode(Player player, UnlockableContent databaseEntry) {
        ensureTechTreeCreated();
        return techTree.unlockNode(player, databaseEntry);
    }

    /** Unlocks {@code nodeId} by player UUID (works for both online and offline players). */
    public boolean unlockTechNode(String playerUuid, String playerName, String nodeId) {
        ensureTechTreeCreated();
        return techTree.unlockNode(playerUuid, playerName, nodeId);
    }

    public boolean isTechNodeUnlocked(Player player, String nodeId) {
        ensureTechTreeCreated();
        TechNodeDef node = techTree.findNode(nodeId);
        return node != null && techTree.isUnlocked(player, node);
    }

    private void ensureTechTreeCreated() {
        if (techTree == null) {
            Fi configBase = Vars.dataDirectory != null ? Vars.dataDirectory : new Fi("config");
            techTree = new TechTreeService(configBase.child("alphystuff"));
        }
    }
}
