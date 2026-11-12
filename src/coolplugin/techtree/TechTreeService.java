package coolplugin.techtree;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.gen.Player;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.units.UnitFactory.UnitPlan;

/**
 * Central orchestrator for the server-side Tech Tree system:
 * <ul>
 *   <li>Loads/saves {@code config/alphystuff/techtree.hjson} and {@code techtree-progress.hjson}</li>
 *   <li>Holds the swappable {@link TechResourceStorage} implementation</li>
 *   <li>Handles incremental item spending &amp; special {@link UnlockCondition} checks</li>
 *   <li>Enforces per-player unlocks for buildings and vanilla units present in {@code techtree.hjson}
 *       without ever touching unlisted or custom (`dp-*`) units/blocks</li>
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
    private final ObjectMap<String, TechNodeDef> nodesById = new ObjectMap<>();
    private final ObjectMap<UnlockableContent, TechNodeDef> nodesByContent = new ObjectMap<>();
    private final Seq<Item> referencedItems = new Seq<>();
    private int totalUnlockableCount = 0;

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

        Events.on(PlayerJoin.class, event -> {
            if (event.player != null) {
                ensurePlayerInitialized(event.player);
            }
        });

        if (Vars.netServer != null && Vars.netServer.admins != null) {
            Vars.netServer.admins.addActionFilter(this::allowPlayerAction);
        }

        Log.info("[CoolPlugin] TechTree ready (@ unlockable nodes, @ saved player records, config=@)",
            totalUnlockableCount, progressStore.playerCount(), treeFile.path());
    }

    public synchronized void reloadTree() {
        this.root = TechTreeConfigIO.loadOrCreate(treeFile, conditionRegistry);
        rebuildIndexes();
        progressStore.reseedAll(root);
    }

    public synchronized void regenerateDefaultTree(boolean resetPlayerProgress) {
        this.root = DefaultSerpuloPreThoriumTree.createDefaultRoot();
        TechTreeConfigIO.save(treeFile, root);
        rebuildIndexes();
        if (resetPlayerProgress) {
            progressStore.resetAllPlayers(root);
        } else {
            progressStore.reseedAll(root);
        }
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
                // Never index custom mod/DataPatch content even if manually added by mistake unless vanilla
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
        TechNodeDef node = findNodeByContent(content);
        // Content NOT in techtree.hjson (including ALL custom dp-* units and blocks outside the tree) is untouched!
        if (node == null) return true;
        return isUnlocked(player, node);
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
     * ActionFilter enforcing per-player unlocks ONLY for blocks and vanilla units explicitly present
     * in {@code config/alphystuff/techtree.hjson}. Custom units (`dp-*`) are NEVER touched.
     */
    public boolean allowPlayerAction(PlayerAction action) {
        if (action == null || action.player == null) return true;
        Player player = action.player;
        ensurePlayerInitialized(player);

        if (action.type == ActionType.placeBlock && action.block != null) {
            Block block = action.block;
            TechNodeDef blockNode = findNodeByContent(block);
            if (blockNode != null && !isUnlocked(player, blockNode)) {
                notifyLocked(player, blockNode);
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
        long now = Time.millis();
        Long last = denyMessageCooldown.get(player.uuid());
        if (last != null && now - last < 1500L) return;
        denyMessageCooldown.put(player.uuid(), now);
        player.sendMessage("[scarlet][TechTree][] [accent]" + node.resolvedName()
            + "[] is not researched yet! Use [accent]/tech[] to unlock it.");
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
