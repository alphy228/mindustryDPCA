package coolplugin.techtree;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.core.NetServer.TeamAssigner;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Rules;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.RequestAssetsCallPacket;
import mindustry.gen.RequestWorldCallPacket;
import mindustry.gen.ResearchedCallPacket;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.units.UnitFactory.UnitPlan;
import mindustry.world.meta.BuildVisibility;

/**
 * Central orchestrator for the server-side Tech Tree system:
 * <ul>
 *   <li>Loads/saves {@code config/alphystuff/techtree.hjson} (including {@code whitelist}) and {@code techtree-progress.hjson}</li>
 *   <li>Automatically enables {@code hideBannedBlocks} on the server</li>
 *   <li>Bans all player-placeable buildings per-player unless whitelisted or researched by that player,
 *       syncing per-player {@link Rules} to each client without touching custom ({@code dp-*}) units</li>
 *   <li>Holds the swappable {@link TechResourceStorage} implementation</li>
 *   <li>Handles incremental item spending &amp; special {@link UnlockCondition} checks</li>
 * </ul>
 */
public class TechTreeService {

    public static final class ResearchOutcome {
        public final boolean unlocked;
        public final boolean contributed;
        public final String message;

        public ResearchOutcome(boolean unlocked, boolean contributed, String message) {
            this.unlocked = unlocked;
            this.contributed = contributed;
            this.message = message;
        }
    }

    private final Fi configDir;
    private final Fi treeFile;
    private final UnlockCondition.ConditionRegistry conditionRegistry = new UnlockCondition.ConditionRegistry();
    private final TechProgressStore progressStore;
    private final TechTreeMenu menu;

    private TechResourceStorage storage = new CoreResourceStorage();

    private TechNodeDef root;
    private final Seq<String> whitelistOrder = new Seq<>();
    private final ObjectSet<String> whitelistedBlockNames = new ObjectSet<>();

    private final ObjectMap<String, TechNodeDef> nodesById = new ObjectMap<>();
    private final ObjectMap<UnlockableContent, TechNodeDef> nodesByContent = new ObjectMap<>();
    private final Seq<Item> referencedItems = new Seq<>();
    private int totalUnlockableCount = 0;

    /** Map-authored block bans captured on map load before per-player rules are injected. */
    private final ObjectSet<Block> mapBannedBlocks = new ObjectSet<>();
    private boolean applyingTempWorldRules = false;
    private boolean worldStreamHooksInstalled = false;
    private final ObjectSet<String> worldStreamSyncedUuids = new ObjectSet<>();

    private final ObjectMap<String, Long> denyMessageCooldown = new ObjectMap<>();

    public TechTreeService(Fi configDir) {
        this.configDir = configDir;
        this.treeFile = configDir.child("techtree.hjson");
        this.progressStore = new TechProgressStore(configDir.child("techtree-progress.hjson"));
        this.menu = new TechTreeMenu(this);
    }

    public void init() {
        configDir.mkdirs();
        reloadTree();
        progressStore.load(root);
        menu.register();

        applyServerHideBannedBlocksAndCaptureMapBans();
        installWorldStreamHooks();

        Events.on(WorldLoadEvent.class, event -> {
            applyServerHideBannedBlocksAndCaptureMapBans();
        });

        Events.on(PlayEvent.class, event -> {
            applyServerHideBannedBlocksAndCaptureMapBans();
            syncAllOnlinePlayers(null);
        });

        Events.on(PlayerConnect.class, event -> {
            restoreServerGlobalBansAfterWorldStream();
        });

        Events.on(PlayerJoin.class, event -> {
            if (event.player != null) {
                ensurePlayerInitialized(event.player);
                boolean syncedInStream = worldStreamSyncedUuids.remove(event.player.uuid());
                if (syncedInStream) {
                    sendCleanPlayerRules(event.player);
                } else {
                    syncPlayerRulesLive(event.player, null);
                }
            }
        });

        if (Vars.netServer != null && Vars.netServer.admins != null) {
            Vars.netServer.admins.addActionFilter(this::allowPlayerAction);
        }

        Log.info("[CoolPlugin] TechTree ready (@ unlockable nodes, @ whitelisted blocks, @ saved player records, hideBannedBlocks=true, config=@)",
            totalUnlockableCount, whitelistOrder.size, progressStore.playerCount(), treeFile.path());
    }

    private void applyServerHideBannedBlocksAndCaptureMapBans() {
        if (Vars.state == null || Vars.state.rules == null) return;
        Vars.state.rules.hideBannedBlocks = true;
        if (!applyingTempWorldRules) {
            mapBannedBlocks.clear();
            mapBannedBlocks.addAll(Vars.state.rules.bannedBlocks);
        }
    }

    private void installWorldStreamHooks() {
        if (worldStreamHooksInstalled) return;
        worldStreamHooksInstalled = true;

        // Path 1: servers without external assets call assigner -> sendWorldData -> PlayerConnect synchronously
        if (Vars.netServer != null && Vars.netServer.assigner != null) {
            TeamAssigner previousAssigner = Vars.netServer.assigner;
            Vars.netServer.assigner = (player, players) -> {
                var assigned = previousAssigner.assign(player, players);
                if (Vars.state != null && Vars.state.data != null && !Vars.state.data.hasExternalAssets()) {
                    applyTempWorldRulesForPlayer(player);
                }
                return assigned;
            };
        }

        // Path 2: servers WITH external assets (like mindustryDPCA sprites/patches) call sendWorldData inside RequestAssets / RequestWorld
        if (Vars.net != null) {
            Vars.net.handleServer(RequestAssetsCallPacket.class, (con, packet) -> {
                Player p = con == null ? null : con.player;
                withPlayerWorldRules(p, () -> packet.handleServer(con));
            });
            Vars.net.handleServer(RequestWorldCallPacket.class, (con, packet) -> {
                Player p = con == null ? null : con.player;
                withPlayerWorldRules(p, () -> packet.handleServer(con));
            });
        }
    }

    /** Executes {@code action} (such as {@code netServer.sendWorldData(player)}) while {@code state.rules} holds {@code player}'s bans. */
    public synchronized void withPlayerWorldRules(@Nullable Player player, Runnable action) {
        if (player == null || Vars.state == null || Vars.state.rules == null) {
            action.run();
            return;
        }
        applyTempWorldRulesForPlayer(player);
        try {
            action.run();
        } finally {
            restoreServerGlobalBansAfterWorldStream();
        }
    }

    private synchronized void applyTempWorldRulesForPlayer(@Nullable Player player) {
        if (player == null || Vars.state == null || Vars.state.rules == null) return;
        ensurePlayerInitialized(player);
        if (!applyingTempWorldRules) {
            mapBannedBlocks.clear();
            mapBannedBlocks.addAll(Vars.state.rules.bannedBlocks);
            applyingTempWorldRules = true;
        }
        Vars.state.rules.hideBannedBlocks = true;
        Vars.state.rules.bannedBlocks.clear();
        populateBannedBlocksForPlayer(player, Vars.state.rules.bannedBlocks);
        worldStreamSyncedUuids.add(player.uuid());
    }

    private synchronized void restoreServerGlobalBansAfterWorldStream() {
        if (!applyingTempWorldRules) return;
        applyingTempWorldRules = false;
        if (Vars.state != null && Vars.state.rules != null) {
            Vars.state.rules.hideBannedBlocks = true;
            Vars.state.rules.bannedBlocks.clear();
            Vars.state.rules.bannedBlocks.addAll(mapBannedBlocks);
        }
    }

    public synchronized void reloadTree() {
        TechTreeConfigIO.LoadedConfig cfg = TechTreeConfigIO.loadConfigOrCreate(treeFile, conditionRegistry);
        this.root = cfg.root;
        setWhitelistInternal(cfg.whitelist);
        rebuildIndexes();
        progressStore.reseedAll(root);
        applyServerHideBannedBlocksAndCaptureMapBans();
    }

    public synchronized void regenerateDefaultTree(boolean resetPlayerProgress) {
        this.root = DefaultSerpuloPreThoriumTree.createDefaultRoot();
        TechTreeConfigIO.save(treeFile, root, whitelistOrder);
        rebuildIndexes();
        if (resetPlayerProgress) {
            progressStore.resetAllPlayers(root);
        } else {
            progressStore.reseedAll(root);
        }
        applyServerHideBannedBlocksAndCaptureMapBans();
    }

    private void setWhitelistInternal(Seq<String> names) {
        whitelistOrder.clear();
        whitelistedBlockNames.clear();
        if (names == null) return;
        for (String n : names) {
            if (n == null) continue;
            String trimmed = n.trim();
            if (!trimmed.isEmpty() && whitelistedBlockNames.add(trimmed)) {
                whitelistOrder.add(trimmed);
            }
        }
    }

    public synchronized Seq<String> getWhitelist() {
        return whitelistOrder.copy();
    }

    public synchronized boolean isWhitelisted(@Nullable Block block) {
        return block != null && whitelistedBlockNames.contains(block.name);
    }

    public synchronized boolean addWhitelist(String blockName) {
        if (blockName == null || blockName.trim().isEmpty()) return false;
        String name = blockName.trim();
        if (!whitelistedBlockNames.add(name)) return false;
        whitelistOrder.add(name);
        TechTreeConfigIO.save(treeFile, root, whitelistOrder);
        return true;
    }

    public synchronized boolean removeWhitelist(String blockName) {
        if (blockName == null || blockName.trim().isEmpty()) return false;
        String name = blockName.trim();
        if (!whitelistedBlockNames.remove(name)) return false;
        whitelistOrder.remove(name);
        TechTreeConfigIO.save(treeFile, root, whitelistOrder);
        return true;
    }

    private void rebuildIndexes() {
        nodesById.clear();
        nodesByContent.clear();
        referencedItems.clear();
        totalUnlockableCount = 0;

        ObjectSet<Item> seenItems = new ObjectSet<>();
        indexNodeRecursive(root, seenItems);
    }

    private void indexNodeRecursive(TechNodeDef node, ObjectSet<Item> seenItems) {
        if (node == null) return;
        nodesById.put(node.id, node);
        if (!node.virtual) {
            totalUnlockableCount++;
            if (node.content != null) {
                nodesByContent.put(node.content, node);
            }
        }
        if (node.costs != null) {
            for (ItemStack s : node.costs) {
                if (s != null && s.item != null && seenItems.add(s.item)) {
                    referencedItems.add(s.item);
                }
            }
        }
        for (TechNodeDef child : node.children) {
            indexNodeRecursive(child, seenItems);
        }
    }

    public TechResourceStorage getStorage() {
        return storage;
    }

    /** Swap in a custom plugin resource storage implementation at any time. */
    public void setStorage(TechResourceStorage customStorage) {
        if (customStorage != null) {
            this.storage = customStorage;
        }
    }

    public UnlockCondition.ConditionRegistry getConditionRegistry() {
        return conditionRegistry;
    }

    public TechProgressStore getProgressStore() {
        return progressStore;
    }

    public TechTreeMenu getMenu() {
        return menu;
    }

    public TechNodeDef getRoot() {
        return root;
    }

    public Fi getTreeFile() {
        return treeFile;
    }

    public Seq<Item> getReferencedItems() {
        return referencedItems;
    }

    public TechNodeDef findNode(String idOrContentName) {
        if (idOrContentName == null) return null;
        TechNodeDef direct = nodesById.get(idOrContentName);
        if (direct != null) return direct;
        String lower = idOrContentName.toLowerCase();
        for (var entry : nodesById.entries()) {
            if (entry.key.toLowerCase().equals(lower)) return entry.value;
            if (entry.value.content != null && entry.value.content.name.toLowerCase().equals(lower)) {
                return entry.value;
            }
        }
        return null;
    }

    public TechNodeDef findNodeByContent(UnlockableContent content) {
        return content == null ? null : nodesByContent.get(content);
    }

    public void ensurePlayerInitialized(Player player) {
        if (player == null) return;
        String rawName = player.name != null ? player.name : player.plainName();
        progressStore.getOrCreate(player.uuid(), rawName, root);
    }

    public boolean isUnlocked(Player player, TechNodeDef node) {
        if (node == null) return true;
        if (node.alwaysUnlocked || node.virtual) return true;
        if (player == null) return false;
        return progressStore.isUnlocked(player.uuid(), node);
    }

    public boolean isContentUnlocked(Player player, UnlockableContent content) {
        if (content instanceof Block block) {
            return isBlockAllowedForPlayer(player, block);
        }
        TechNodeDef node = findNodeByContent(content);
        if (node == null) return true;
        return isUnlocked(player, node);
    }

    /** True if {@code block} is a player-buildable building subject to whitelist/tech-tree gating. */
    public static boolean isLockableBuilding(@Nullable Block block) {
        return block != null && block.placeablePlayer && block.buildVisibility != BuildVisibility.hidden;
    }

    /**
     * A building is allowed for {@code player} iff it is NOT banned by the map AND
     * (it is in {@code whitelist} OR it is present in {@code techtree.hjson} and unlocked by {@code player}).
     */
    public boolean isBlockAllowedForPlayer(@Nullable Player player, @Nullable Block block) {
        if (!isLockableBuilding(block)) return true;
        if (mapBannedBlocks.contains(block)) return false;
        if (isWhitelisted(block)) return true;

        TechNodeDef node = findNodeByContent(block);
        return node != null && isUnlocked(player, node);
    }

    /** Fills {@code out} with every building banned for {@code player} (map bans + unresearched non-whitelisted buildings). */
    public void populateBannedBlocksForPlayer(Player player, ObjectSet<Block> out) {
        out.addAll(mapBannedBlocks);
        if (Vars.content == null) return;
        for (Block block : Vars.content.blocks()) {
            if (isLockableBuilding(block) && !isBlockAllowedForPlayer(player, block)) {
                out.add(block);
            }
        }
    }

    /** Builds a clean per-player {@link Rules} copy with {@code hideBannedBlocks = true} and {@code player}'s banned buildings. */
    public Rules buildPlayerRules(Player player) {
        Rules base = (Vars.state != null && Vars.state.rules != null) ? Vars.state.rules.copy() : new Rules();
        base.hideBannedBlocks = true;
        base.bannedBlocks.clear();
        populateBannedBlocksForPlayer(player, base.bannedBlocks);
        return base;
    }

    public void sendCleanPlayerRules(@Nullable Player player) {
        if (player == null || player.con == null) return;
        Call.setRules(player.con, buildPlayerRules(player));
    }

    /**
     * Pushes updated per-player {@code bannedBlocks} + {@code hideBannedBlocks = true} to {@code player.con}
     * AND triggers {@code PlacementFragment.rebuild()} immediately on that client without closing open menus.
     */
    public void syncPlayerRulesLive(@Nullable Player player, @Nullable Block newlyUnlockedBlock) {
        if (player == null || player.con == null) return;
        ensurePlayerInitialized(player);

        Rules cleanRules = buildPlayerRules(player);
        Block triggerBlock = (newlyUnlockedBlock != null && !newlyUnlockedBlock.alwaysUnlocked)
            ? newlyUnlockedBlock
            : findFallbackTriggerBlock();

        if (triggerBlock == null || SectorPresets.groundZero == null || SectorPresets.groundZero.sector == null) {
            Call.setRules(player.con, cleanRules);
            return;
        }

        Rules tempCampaignRules = cleanRules.copy();
        if (tempCampaignRules.sector == null) {
            tempCampaignRules.sector = SectorPresets.groundZero.sector;
        }
        tempCampaignRules.researched.clear();
        if (Vars.content != null) {
            for (Block b : Vars.content.blocks()) {
                if (isLockableBuilding(b) && !cleanRules.bannedBlocks.contains(b) && b != triggerBlock) {
                    tempCampaignRules.researched.add(b);
                }
            }
        }

        Call.setRules(player.con, tempCampaignRules);

        ResearchedCallPacket pkt = new ResearchedCallPacket();
        pkt.content = triggerBlock;
        player.con.send(pkt, true);

        Call.setRules(player.con, cleanRules);
    }

    public void syncAllOnlinePlayers(@Nullable Block newlyUnlockedBlock) {
        if (Groups.player == null) return;
        for (Player p : Groups.player) {
            syncPlayerRulesLive(p, newlyUnlockedBlock);
        }
    }

    private @Nullable Block findFallbackTriggerBlock() {
        if (Vars.content == null) return null;
        for (Block b : Vars.content.blocks()) {
            if (!b.alwaysUnlocked) return b;
        }
        return null;
    }

    /**
     * Matches vanilla {@code ResearchDialog}: a node is visible on the tree canvas iff it is the root
     * or its parent node has already been unlocked by this player.
     */
    public boolean isVisible(Player player, TechNodeDef node) {
        if (node == null) return false;
        if (node.parent == null || node.parent.virtual) return true;
        return isUnlocked(player, node.parent);
    }

    public boolean areConditionsMet(Player player, TechNodeDef node) {
        if (node == null) return true;
        for (UnlockCondition cond : node.conditions) {
            if (!cond.isMet(player, progressStore)) return false;
        }
        return true;
    }

    public boolean canSpendOrUnlockNow(Player player, TechNodeDef node) {
        if (player == null || node == null || isUnlocked(player, node)) return false;
        if (node.parent != null && !isUnlocked(player, node.parent)) return false;
        if (!areConditionsMet(player, node)) return false;
        if (node.costs.length == 0) return true;
        if (!storage.isAvailable(player)) return false;

        boolean allPaidAlready = true;
        for (ItemStack req : node.costs) {
            int paid = progressStore.getPaidAmount(player.uuid(), node.id, req.item.name);
            int remaining = req.amount - paid;
            if (remaining > 0) {
                allPaidAlready = false;
                if (storage.getAmount(player, req.item) > 0) {
                    return true;
                }
            }
        }
        return allPaidAlready;
    }

    public boolean canCompleteUnlockNow(Player player, TechNodeDef node) {
        if (player == null || node == null || isUnlocked(player, node)) return false;
        if (node.parent != null && !isUnlocked(player, node.parent)) return false;
        if (!areConditionsMet(player, node)) return false;
        if (node.costs.length == 0) return true;
        if (!storage.isAvailable(player)) return false;

        for (ItemStack req : node.costs) {
            int paid = progressStore.getPaidAmount(player.uuid(), node.id, req.item.name);
            int remaining = Math.max(req.amount - paid, 0);
            if (storage.getAmount(player, req.item) < remaining) {
                return false;
            }
        }
        return true;
    }

    public int getProgressPercent(Player player, TechNodeDef node) {
        if (player == null || node == null || node.costs.length == 0) return 0;
        long totalReq = 0;
        long totalPaid = 0;
        for (ItemStack req : node.costs) {
            totalReq += req.amount;
            totalPaid += Math.min(req.amount, progressStore.getPaidAmount(player.uuid(), node.id, req.item.name));
        }
        if (totalReq <= 0) return 0;
        return (int)((totalPaid * 100L) / totalReq);
    }

    /**
     * Performs vanilla {@code ResearchDialog}-style incremental item spending:
     * deducts up to the remaining cost per item stack from {@link TechResourceStorage},
     * saves partial progress, and unlocks the node once every stack reaches `0` remaining.
     */
    public synchronized ResearchOutcome attemptResearch(Player player, TechNodeDef node) {
        if (player == null || node == null) {
            return new ResearchOutcome(false, false, "[scarlet]Invalid node.[]");
        }
        ensurePlayerInitialized(player);

        if (isUnlocked(player, node)) {
            return new ResearchOutcome(true, false, "[stat]Already researched![]");
        }
        if (node.parent != null && !isUnlocked(player, node.parent)) {
            return new ResearchOutcome(false, false, "[scarlet]Research parent '" + node.parent.resolvedName() + "' first.[]");
        }
        if (!areConditionsMet(player, node)) {
            return new ResearchOutcome(false, false, "[scarlet]Special unlock conditions are not met yet.[]");
        }
        if (node.costs.length > 0 && !storage.isAvailable(player)) {
            return new ResearchOutcome(false, false, "[scarlet]No resource storage available.[]");
        }

        boolean spentAny = false;
        boolean completedAll = true;

        for (ItemStack req : node.costs) {
            int paid = progressStore.getPaidAmount(player.uuid(), node.id, req.item.name);
            int need = Math.max(req.amount - paid, 0);
            if (need <= 0) continue;

            int taken = storage.remove(player, req.item, need);
            if (taken > 0) {
                progressStore.addPaidAmount(player.uuid(), player.plainName(), node.id, req.item.name, taken, root);
                spentAny = true;
            }
            if (taken < need) {
                completedAll = false;
            }
        }

        if (completedAll) {
            progressStore.unlockWithParents(player.uuid(), player.plainName(), node, root);
            if (node.content instanceof Block unlockedBlock) {
                syncPlayerRulesLive(player, unlockedBlock);
            }
            player.sendMessage("[accent][TechTree][] Researched [stat]" + node.resolvedName() + "[]!");
            return new ResearchOutcome(true, spentAny, "[stat]Unlocked " + node.resolvedName() + "![]");
        }

        if (spentAny) {
            int pct = getProgressPercent(player, node);
            return new ResearchOutcome(false, true, "[accent]Contributed items (" + pct + "% complete).[]");
        }

        return new ResearchOutcome(false, false, "[scarlet]Not enough items in " + storage.storageName(player) + ".[]");
    }

    /**
     * ActionFilter enforcing per-player building bans (all buildings banned unless whitelisted or researched)
     * and vanilla unit locks in {@code techtree.hjson}. Custom units ({@code dp-*}) are NEVER touched.
     */
    public boolean allowPlayerAction(PlayerAction action) {
        if (action == null || action.player == null) return true;
        Player player = action.player;
        ensurePlayerInitialized(player);

        if (action.type == ActionType.placeBlock && action.block != null) {
            Block block = action.block;
            if (!isBlockAllowedForPlayer(player, block)) {
                TechNodeDef blockNode = findNodeByContent(block);
                if (blockNode != null && !mapBannedBlocks.contains(block)) {
                    notifyLocked(player, blockNode);
                } else {
                    notifyBannedBuilding(player, block);
                }
                return false;
            }

            // Also check blueprint-configured UnitFactory plan on placement
            if (block instanceof UnitFactory factory && action.config != null) {
                UnitType plannedUnit = extractFactoryUnit(factory, action.config);
                if (plannedUnit != null && isVanillaUnitLocked(player, plannedUnit)) {
                    notifyLocked(player, findNodeByContent(plannedUnit));
                    return false;
                }
            }
        } else if (action.type == ActionType.configure && action.tile != null && action.tile.build instanceof UnitFactoryBuild fb) {
            UnitFactory factory = (UnitFactory) fb.block;
            UnitType selectedUnit = extractFactoryUnit(factory, action.config);
            if (selectedUnit != null && isVanillaUnitLocked(player, selectedUnit)) {
                notifyLocked(player, findNodeByContent(selectedUnit));
                return false;
            }
        }

        return true;
    }

    private UnitType extractFactoryUnit(UnitFactory factory, Object config) {
        if (config instanceof UnitType ut) {
            return ut;
        }
        if (config instanceof Integer idx && idx >= 0 && idx < factory.plans.size) {
            UnitPlan plan = factory.plans.get(idx);
            return plan == null ? null : plan.unit;
        }
        return null;
    }

    private boolean isVanillaUnitLocked(Player player, UnitType unit) {
        // Hard safety guard: NEVER lock or touch custom/DataPatch units (e.g. dp-oxidise-unit, dp-corrode-unit, dp-dissociate-unit)
        if (unit == null) return false;
        if (unit.name.startsWith("dp-") || (unit.minfo != null && unit.minfo.mod != null)) {
            return false;
        }
        TechNodeDef unitNode = findNodeByContent(unit);
        return unitNode != null && !isUnlocked(player, unitNode);
    }

    private void notifyLocked(Player player, TechNodeDef node) {
        if (player == null || node == null) return;
        if (onDenyCooldown(player)) return;
        player.sendMessage("[scarlet][TechTree][] [accent]" + node.resolvedName()
            + "[] is not researched yet! Use [accent]/t[] to unlock it.");
    }

    private void notifyBannedBuilding(Player player, Block block) {
        if (player == null || block == null) return;
        if (onDenyCooldown(player)) return;
        player.sendMessage("[scarlet][TechTree][] [accent]" + TechNodeDef.prettifyId(block.name)
            + "[] is banned (not researched or whitelisted).");
    }

    private boolean onDenyCooldown(Player player) {
        long now = Time.millis();
        Long last = denyMessageCooldown.get(player.uuid());
        if (last != null && now - last < 1500L) return true;
        denyMessageCooldown.put(player.uuid(), now);
        return false;
    }

    public int totalUnlockableCount() {
        return totalUnlockableCount;
    }

    public int unlockedCount(Player player) {
        if (player == null) return 0;
        return countUnlockedInSubtree(player, root);
    }

    public int countUnlockedInSubtree(Player player, TechNodeDef sub) {
        if (sub == null) return 0;
        int c = (!sub.virtual && isUnlocked(player, sub)) ? 1 : 0;
        for (TechNodeDef ch : sub.children) {
            c += countUnlockedInSubtree(player, ch);
        }
        return c;
    }

    public int countTotalInSubtree(TechNodeDef sub) {
        if (sub == null) return 0;
        int c = sub.virtual ? 0 : 1;
        for (TechNodeDef ch : sub.children) {
            c += countTotalInSubtree(ch);
        }
        return c;
    }
}
