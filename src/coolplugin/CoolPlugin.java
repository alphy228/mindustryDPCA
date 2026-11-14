package coolplugin;

import arc.files.Fi;
import arc.util.CommandHandler;
import coolplugin.database.AccountCommands;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.BuildResourceStorage;
import coolplugin.gameplay.BuildSystem;
import coolplugin.gameplay.ColouringAssets;
import coolplugin.gameplay.ColouringSystem;
import coolplugin.gameplay.ItemShopSystem;
import coolplugin.gameplay.LaunchPadSystem;
import coolplugin.gameplay.PlayerItemSystem;
import coolplugin.gameplay.PlayerUnitSystem;
import coolplugin.gameplay.UiSpriteSync;
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
 *   <li>{@code coolplugin.database} — Embedded SQLite (`config/alphystuff/players.db`) guest UUID + {@code login + password} accounts,
 *       per-faction persistent Launch Pad orbital storage, and per-faction equipped items.</li>
 *   <li>{@code coolplugin.techtree} — Per-player Tech Tree, HJSON config, and building bans.</li>
 *   <li>{@code coolplugin.gameplay} — Coreless {@link BuildSystem}, reconnect-safe {@link UiSpriteSync},
 *       {@link FactionJoinMenu}, {@link PlayerUnitSystem}, {@link LaunchPadSystem}, {@link PlayerItemSystem}, and {@link ItemShopSystem}.</li>
 * </ul>
 */
public class CoolPlugin extends Plugin {

    private Fi configBaseDir;
    private PlayerDatabase playerDatabase;
    private PlayerSessionManager sessionManager;
    private UnitPatchManager unitPatchManager;
    private TechTreeService techTree;
    private BuildSystem buildSystem;
    private PlayerUnitSystem playerUnitSystem;
    private LaunchPadSystem launchPadSystem;
    private PlayerItemSystem playerItemSystem;
    private ItemShopSystem itemShopSystem;
    private ColouringSystem colouringSystem;

    @Override
    public void init() {
        ensureSystemsCreated();
        unitPatchManager.runOnServerLoad();
        new ColouringAssets(configBaseDir).generate();
        unitPatchManager.reloadGeneratedAssets();
        UiSpriteSync.init(configBaseDir);
        sessionManager.start();
        techTree.init();
        buildSystem.start();
        playerUnitSystem.start();
        launchPadSystem.start();
        playerItemSystem.start();
        itemShopSystem.start();
        colouringSystem.start();
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        ensureSystemsCreated();
        TechTreeCommands.registerServerCommands(handler, techTree);
        AccountCommands.registerServerCommands(handler, sessionManager);
        launchPadSystem.registerServerCommands(handler);
        itemShopSystem.registerServerCommands(handler);
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

    public LaunchPadSystem getLaunchPadSystem() {
        ensureSystemsCreated();
        return launchPadSystem;
    }

    public PlayerItemSystem getPlayerItemSystem() {
        ensureSystemsCreated();
        return playerItemSystem;
    }

    public ItemShopSystem getItemShopSystem() {
        ensureSystemsCreated();
        return itemShopSystem;
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
            configBaseDir = Vars.dataDirectory != null ? Vars.dataDirectory : new Fi("config");
            Fi alphyDir = configBaseDir.child("alphystuff");
            unitPatchManager = new UnitPatchManager(configBaseDir);
            playerDatabase = new PlayerDatabase(alphyDir.child("players.db"));
            sessionManager = new PlayerSessionManager(playerDatabase);
            techTree = new TechTreeService(alphyDir, playerDatabase, sessionManager);
            buildSystem = new BuildSystem(techTree);
            techTree.setStorage(new BuildResourceStorage(buildSystem, playerDatabase, sessionManager));
            techTree.setWorldStreamListener(buildSystem::onPlayerWorldSynced);
            playerUnitSystem = new PlayerUnitSystem(buildSystem, techTree, sessionManager);
            launchPadSystem = new LaunchPadSystem(playerDatabase, sessionManager);
            playerItemSystem = new PlayerItemSystem(alphyDir, playerDatabase, sessionManager, buildSystem);
            colouringSystem = new ColouringSystem(configBaseDir, playerItemSystem, playerUnitSystem);
            itemShopSystem = new ItemShopSystem(
                playerItemSystem,
                buildSystem,
                playerUnitSystem,
                techTree,
                launchPadSystem,
                playerDatabase,
                sessionManager
            );
        }
    }
}
