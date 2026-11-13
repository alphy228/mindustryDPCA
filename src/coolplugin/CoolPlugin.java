package coolplugin;

import arc.files.Fi;
import arc.util.CommandHandler;
import coolplugin.database.AccountCommands;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.BuildResourceStorage;
import coolplugin.gameplay.BuildSystem;
import coolplugin.gameplay.PlayerUnitSystem;
import coolplugin.gameplay.UnitPatchManager;
import coolplugin.techtree.TechNodeDef;
import coolplugin.techtree.TechResourceStorage;
import coolplugin.techtree.TechTreeCommands;
import coolplugin.techtree.TechTreeService;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

/**
 * Main Mindustry server plugin entry point (`config/mods/coolplugin.jar`).
 * Wires together:
 * <ul>
 *   <li>{@code coolplugin.database} — Embedded SQLite (`config/alphystuff/players.db`) guest UUID + {@code login + password} accounts.</li>
 *   <li>{@code coolplugin.techtree} — Per-player Tech Tree, HJSON config, and building bans.</li>
 *   <li>{@code coolplugin.gameplay} — Coreless {@link BuildSystem}, {@link FactionJoinMenu}, and single-unit {@link PlayerUnitSystem}.</li>
 * </ul>
 */
public class CoolPlugin extends Plugin {

    private PlayerDatabase playerDatabase;
    private PlayerSessionManager sessionManager;
    private UnitPatchManager unitPatchManager;
    private TechTreeService techTree;
    private BuildSystem buildSystem;
    private PlayerUnitSystem playerUnitSystem;

    @Override
    public void init() {
        ensureSystemsCreated();
        unitPatchManager.runOnServerLoad();
        sessionManager.start();
        techTree.init();
        buildSystem.start();
        playerUnitSystem.start();
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        ensureSystemsCreated();
        TechTreeCommands.registerServerCommands(handler, techTree);
        AccountCommands.registerServerCommands(handler, sessionManager);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        ensureSystemsCreated();
        TechTreeCommands.registerClientCommands(handler, techTree);
        AccountCommands.registerClientCommands(handler, sessionManager);
        playerUnitSystem.registerCommands(handler);
    }

    public PlayerDatabase getPlayerDatabase() {
        ensureSystemsCreated();
        return playerDatabase;
    }

    public PlayerSessionManager getSessionManager() {
        ensureSystemsCreated();
        return sessionManager;
    }

    public TechTreeService getTechTree() {
        ensureSystemsCreated();
        return techTree;
    }

    public BuildSystem getBuildSystem() {
        ensureSystemsCreated();
        return buildSystem;
    }

    public PlayerUnitSystem getPlayerUnitSystem() {
        ensureSystemsCreated();
        return playerUnitSystem;
    }

    public void setTechResourceStorage(TechResourceStorage storage) {
        ensureSystemsCreated();
        techTree.setStorage(storage);
    }

    public boolean unlockTechNode(Player player, String nodeId) {
        ensureSystemsCreated();
        return techTree.unlockNode(player, nodeId);
    }

    public boolean unlockTechNode(Player player, UnlockableContent databaseEntry) {
        ensureSystemsCreated();
        return techTree.unlockNode(player, databaseEntry);
    }

    public boolean unlockTechNode(String playerUuid, String playerName, String nodeId) {
        ensureSystemsCreated();
        return techTree.unlockNode(playerUuid, playerName, nodeId);
    }

    public boolean isTechNodeUnlocked(Player player, String nodeId) {
        ensureSystemsCreated();
        TechNodeDef node = techTree.findNode(nodeId);
        return node != null && techTree.isUnlocked(player, node);
    }

    private void ensureSystemsCreated() {
        if (techTree == null) {
            Fi configBase = Vars.dataDirectory != null ? Vars.dataDirectory : new Fi("config");
            Fi alphyDir = configBase.child("alphystuff");
            unitPatchManager = new UnitPatchManager(configBase);
            playerDatabase = new PlayerDatabase(alphyDir.child("players.db"));
            sessionManager = new PlayerSessionManager(playerDatabase);
            techTree = new TechTreeService(alphyDir, playerDatabase, sessionManager);
            buildSystem = new BuildSystem(techTree);
            techTree.setStorage(new BuildResourceStorage(buildSystem));
            techTree.setWorldStreamListener(buildSystem::onPlayerWorldSynced);
            playerUnitSystem = new PlayerUnitSystem(buildSystem, techTree, sessionManager);
        }
    }
}
