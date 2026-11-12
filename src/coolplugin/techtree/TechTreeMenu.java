package coolplugin.techtree;

import arc.struct.ObjectMap;
import arc.util.Scaling;
import arc.util.Strings;
import arc.util.io.Writes;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuBuilder;
import mindustry.ui.builder.UiBuilder.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static coolplugin.techtree.TechTreeLayoutEngine.*;
import static mindustry.ui.builder.UiBuilder.*;

/**
 * Builds and updates the interactive vanilla-like Tech Tree dialog using Mindustry v160.7's
 * {@link MenuBuilder} and {@link mindustry.ui.builder.UiBuilder} APIs.
 *
 * <p>Features:
 * <ul>
 *   <li>Top resource bar backed by {@link TechResourceStorage} + Serpulo Tier-1 root tabs</li>
 *   <li>2D scrollable visual node canvas (`defaultPane`) with orthogonal `whiteui`
 *       parent-to-child connector lines (`Pal.accent` unlocked, `Pal.gray` locked)</li>
 *   <li>Vanilla `nodei` &amp; `button` / `button-over` / `button-red` node states</li>
 *   <li>Right-hand inspector panel with full item progress (`paid / total`), special condition checklist,
 *       and incremental item spending matching vanilla {@code ResearchDialog}</li>
 *   <li>Serialized payload verification well below ArcNet's packet ceiling even at 100% unlock</li>
 * </ul>
 */
public class TechTreeMenu {

    public static final String ID_ROOT = "alphystuff-tech-root";
    public static final String ID_HEADER = "alphystuff-tech-header";
    public static final String ID_TREE_CANVAS = "alphystuff-tech-canvas";
    public static final String ID_INFO_PANEL = "alphystuff-tech-info";

    private static final float NODE_SIZE = 54f;
    private static final float COL_GAP = 14f;
    private static final float ROW_GAP = 22f;
    private static final float LINE_THICK = 4f;

    private static final String COL_ACCENT = "ffd37fff";
    private static final String COL_UNLOCKED_LINE = "ffd37fff";
    private static final String COL_LOCKED_LINE = "454545ff";
    private static final String COL_SELECTED_TINT = "a9d8ffff";
    private static final String COL_DIM_ICON = "7a7a7aff";

    private static final class PlayerMenuSession {
        String activeTabId = "";
        String selectedNodeId = "";
        String statusMessage = "";
    }

    private final TechTreeService service;
    private final ObjectMap<String, PlayerMenuSession> sessions = new ObjectMap<>();
    private int menuId = -1;

    public TechTreeMenu(TechTreeService service) {
        this.service = service;
    }

    public void register() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder((player, result) -> {
            if (player == null || result == null || result.wasCancelled() || result.result == null) return;
            handleAction(player, result.result);
        });
    }

    public int getMenuId() {
        return menuId;
    }

    public void open(Player player) {
        if (player == null || player.con == null) return;
        register();
        service.ensurePlayerInitialized(player);

        PlayerMenuSession session = getOrInitSession(player);
        session.statusMessage = "";

        TableBuilder rootUi = buildFullDialog(player, session);
        MenuBuilder.of(rootUi)
            .id(menuId)
            .title(null)
            .hideOnClick(false)
            .hideExisting(true)
            .fillScreen(true)
            .show(player);
    }

    public void refreshOpenSession(Player player) {
        if (player == null || player.con == null || menuId == -1) return;
        PlayerMenuSession session = sessions.get(player.uuid());
        if (session == null) return;
        pushPartialUpdates(player, session, true, true, true);
    }

    private PlayerMenuSession getOrInitSession(Player player) {
        PlayerMenuSession session = sessions.get(player.uuid());
        if (session == null) {
            session = new PlayerMenuSession();
            sessions.put(player.uuid(), session);
        }

        TechNodeDef root = service.getRoot();
        if (resolveTabBranch(root, session.activeTabId) == null) {
            if (!root.children.isEmpty()) {
                session.activeTabId = root.children.first().id;
            } else {
                session.activeTabId = root.id;
            }
        }

        TechNodeDef activeBranch = resolveTabBranch(root, session.activeTabId);
        TechNodeDef selected = service.findNode(session.selectedNodeId);
        if (selected == null || !isInSubtree(activeBranch, selected) || !service.isVisible(player, selected)) {
            session.selectedNodeId = activeBranch != null ? activeBranch.id : root.id;
        }
        return session;
    }

    private void handleAction(Player player, String clicked) {
        if ("close".equals(clicked)) {
            Call.hideMenuBuilder(player.con, menuId);
            return;
        }

        PlayerMenuSession session = getOrInitSession(player);
        session.statusMessage = "";

        if (clicked.startsWith("tab:")) {
            String tabId = clicked.substring(4);
            TechNodeDef branch = resolveTabBranch(service.getRoot(), tabId);
            if (branch != null) {
                session.activeTabId = branch.id;
                session.selectedNodeId = branch.id;
                pushPartialUpdates(player, session, true, true, true);
            }
            return;
        }

        if (clicked.startsWith("node:")) {
            String nodeId = clicked.substring(5);
            TechNodeDef node = service.findNode(nodeId);
            if (node != null && service.isVisible(player, node)) {
                session.selectedNodeId = node.id;
                pushPartialUpdates(player, session, false, true, true);
            }
            return;
        }

        if (clicked.startsWith("research:")) {
            String nodeId = clicked.substring(9);
            TechNodeDef node = service.findNode(nodeId);
            if (node != null) {
                TechTreeService.ResearchOutcome outcome = service.attemptResearch(player, node);
                session.selectedNodeId = node.id;
                session.statusMessage = outcome.message;
                pushPartialUpdates(player, session, true, true, true);
            }
        }
    }

    private void pushPartialUpdates(
            Player player,
            PlayerMenuSession session,
            boolean updateHeader,
            boolean updateCanvas,
            boolean updateInfo) {
        if (player.con == null) return;
        if (updateHeader) {
            Call.menuBuilderUpdate(player.con, menuId, ID_HEADER, buildHeaderContents(player, session));
        }
        if (updateCanvas) {
            Call.menuBuilderUpdate(player.con, menuId, ID_TREE_CANVAS, buildTreeCanvasContents(player, session));
        }
        if (updateInfo) {
            Call.menuBuilderUpdate(player.con, menuId, ID_INFO_PANEL, buildInfoPanelContents(player, session));
        }
    }

    public TableBuilder buildFullDialog(Player player, PlayerMenuSession session) {
        TableBuilder root = table()
            .id(ID_ROOT)
            .background("pane")
            .margin(8f)
            .grow();

        // Top header: resource bar + Tier 1 branch tabs + Close button
        TableBuilder headerBox = table()
            .id(ID_HEADER)
            .colspan(2)
            .growX()
            .padBottom(6f);
        headerBox.add(buildHeaderContents(player, session));
        root.add(headerBox).row();

        // Center-left: 2D pannable Tech Tree canvas
        TableBuilder treeCanvasHolder = table()
            .id(ID_TREE_CANVAS)
            .margin(24f);
        treeCanvasHolder.add(buildTreeCanvasContents(player, session));

        PaneBuilder scrollCanvas = pane()
            .style("defaultPane")
            .grow()
            .padRight(8f)
            .add(treeCanvasHolder);

        root.add(scrollCanvas);

        // Right: Node Inspector & Research Panel
        TableBuilder infoPanel = table()
            .id(ID_INFO_PANEL)
            .background("button")
            .margin(10f)
            .align("topLeft")
            .width(315f)
            .growY();
        infoPanel.add(buildInfoPanelContents(player, session));

        root.add(infoPanel);
        return root;
    }

    /** Helper for headless tests to inspect serialized packet size for a given branch tab (or current tab). */
    public int measureFullDialogBytes(Player player, String tabId) {
        PlayerMenuSession session = getOrInitSession(player);
        if (tabId != null && !tabId.isEmpty()) {
            session.activeTabId = tabId;
        }
        TableBuilder ui = buildFullDialog(player, session);
        return serializeSize(ui);
    }

    public static int serializeSize(NodeBuilder<?> builder) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            builder.write(new Writes(new DataOutputStream(bos)));
            return bos.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private TableBuilder buildHeaderContents(Player player, PlayerMenuSession session) {
        TableBuilder header = table().growX();

        // Row 1: Title + Storage Resource HUD + Unlock Progress + Close button
        TableBuilder topBar = table().background("button").margin(6f).growX().padBottom(4f);
        topBar.add(image("tree").size(22f).color(COL_ACCENT).padRight(6f));
        topBar.add(
            label("[accent]Tech Tree[] [lightgray](" + service.unlockedCount(player) + "/" + service.totalUnlockableCount() + ")[]")
                .labelAlign("left")
                .padRight(14f)
        );

        TableBuilder resTable = table().align("left").growX();
        TechResourceStorage storage = service.getStorage();
        resTable.add(label("[gray]" + storage.storageName(player) + ":[]").padRight(8f));
        int shown = 0;
        for (Item item : storage.displayItems(player, service.getReferencedItems())) {
            int amt = storage.getAmount(player, item);
            String iconName = "item-" + item.name + "-ui";
            resTable.add(image(iconName).scaling(Scaling.fit).size(18f).padRight(3f));
            String col = amt > 0 ? "[white]" : "[darkgray]";
            resTable.add(label(col + formatAmount(amt) + "[]").padRight(9f));
            shown++;
            if (shown >= 11) break;
        }
        topBar.add(resTable);

        topBar.add(
            button("[scarlet]Close[]")
                .icon("cancel")
                .style("defaultt")
                .clicked("close")
                .width(106f)
                .height(38f)
                .align("right")
        );

        header.add(topBar).row();

        // Row 2: Serpulo Tier-1 Root Branch Tabs
        TechNodeDef root = service.getRoot();
        if (root.children.size > 1) {
            TableBuilder tabs = table().align("left").growX();
            tabs.add(label("[lightgray]Branches:[]").padRight(8f));
            for (TechNodeDef branch : root.children) {
                boolean active = branch.id.equals(session.activeTabId);
                int branchUnlocked = service.countUnlockedInSubtree(player, branch);
                int branchTotal = service.countTotalInSubtree(branch);
                String caption = (active ? "[accent]" : "[white]")
                    + branch.resolvedName()
                    + " [lightgray]" + branchUnlocked + "/" + branchTotal + "[]";

                tabs.add(
                    button(caption)
                        .icon(branch.resolvedIcon())
                        .style("togglet")
                        .checked(active)
                        .clicked("tab:" + branch.id)
                        .height(40f)
                        .padRight(4f)
                );
            }
            header.add(tabs);
        }

        return header;
    }

    private TableBuilder buildTreeCanvasContents(Player player, PlayerMenuSession session) {
        TechNodeDef root = service.getRoot();
        TechNodeDef branchRoot = resolveTabBranch(root, session.activeTabId);
        if (branchRoot == null) branchRoot = root;

        GridCanvas canvas = TechTreeLayoutEngine.buildCanvas(
            branchRoot,
            node -> service.isVisible(player, node),
            node -> service.isUnlocked(player, node)
        );

        TableBuilder grid = table();
        for (int r = 0; r < canvas.rows; r++) {
            boolean isNodeRow = (r % 2 == 0);
            float rowH = isNodeRow ? NODE_SIZE : ROW_GAP;

            for (int c = 0; c < canvas.cols; c++) {
                boolean isNodeCol = (c % 2 == 0);
                float colW = isNodeCol ? NODE_SIZE : COL_GAP;

                TechNodeDef node = canvas.nodes[r][c];
                int mask = canvas.lineMask[r][c];

                if (node != null) {
                    grid.add(buildNodeButton(player, session, node));
                } else if (mask != 0) {
                    String lineColor = canvas.unlockedMask[r][c] ? COL_UNLOCKED_LINE : COL_LOCKED_LINE;
                    grid.add(buildConnectorCell(mask, colW, rowH, lineColor));
                } else {
                    grid.add(space().width(colW).height(rowH));
                }
            }
            grid.row();
        }
        return grid;
    }

    private ButtonTableBuilder buildNodeButton(Player player, PlayerMenuSession session, TechNodeDef node) {
        boolean unlocked = service.isUnlocked(player, node);
        boolean selected = node.id.equals(session.selectedNodeId);
        boolean canSpend = !unlocked && service.canSpendOrUnlockNow(player, node);
        boolean parentUnlocked = node.parent == null || service.isUnlocked(player, node.parent);

        String bg;
        String borderTint;
        String iconTint;

        if (unlocked) {
            bg = "button-over";
            borderTint = selected ? COL_SELECTED_TINT : "ffffffff";
            iconTint = "ffffffff";
        } else if (parentUnlocked && canSpend) {
            bg = "button";
            borderTint = selected ? COL_ACCENT : "ffd37faa";
            iconTint = "ffffffff";
        } else {
            bg = "button-red";
            borderTint = selected ? COL_SELECTED_TINT : "ffffffbb";
            iconTint = COL_DIM_ICON;
        }

        TableBuilder card = table()
            .background(bg)
            .color(borderTint)
            .margin(4f)
            .grow();

        int pct = unlocked ? 0 : service.getProgressPercent(player, node);
        float iconSz = pct > 0 ? 26f : 32f;

        card.add(
            image(node.resolvedIcon())
                .scaling(Scaling.fit)
                .color(iconTint)
                .size(iconSz)
        );

        if (pct > 0) {
            card.row();
            card.add(label("[accent]" + pct + "%[]").style("outlineLabel"));
        }

        return buttonTable()
            .style("emptyi")
            .clicked("node:" + node.id)
            .size(NODE_SIZE)
            .add(card);
    }

    /**
     * Renders an exact orthogonal connector cell (`│`, `─`, `└`, `┘`, `┌`, `┐`, `┴`, `┬`, `┼`)
     * using `whiteui` quads so parent-to-child branches meet cleanly with zero gaps.
     */
    private TableBuilder buildConnectorCell(int mask, float cellW, float cellH, String color) {
        if (mask == (DIR_LEFT | DIR_RIGHT)) {
            return table().width(cellW).height(cellH).add(
                image("whiteui").color(color).width(cellW).height(LINE_THICK)
            );
        }
        if (mask == (DIR_UP | DIR_DOWN)) {
            return table().width(cellW).height(cellH).add(
                image("whiteui").color(color).width(LINE_THICK).height(cellH)
            );
        }

        float sideW = Math.max((cellW - LINE_THICK) / 2f, 1f);
        float sideH = Math.max((cellH - LINE_THICK) / 2f, 1f);

        boolean up = (mask & DIR_UP) != 0;
        boolean right = (mask & DIR_RIGHT) != 0;
        boolean down = (mask & DIR_DOWN) != 0;
        boolean left = (mask & DIR_LEFT) != 0;

        TableBuilder t = table().width(cellW).height(cellH);

        // Top row
        t.add(space().width(sideW).height(sideH));
        t.add(up
            ? image("whiteui").color(color).width(LINE_THICK).height(sideH)
            : space().width(LINE_THICK).height(sideH));
        t.add(space().width(sideW).height(sideH));
        t.row();

        // Middle row
        t.add(left
            ? image("whiteui").color(color).width(sideW).height(LINE_THICK)
            : space().width(sideW).height(LINE_THICK));
        t.add(image("whiteui").color(color).width(LINE_THICK).height(LINE_THICK));
        t.add(right
            ? image("whiteui").color(color).width(sideW).height(LINE_THICK)
            : space().width(sideW).height(LINE_THICK));
        t.row();

        // Bottom row
        t.add(space().width(sideW).height(sideH));
        t.add(down
            ? image("whiteui").color(color).width(LINE_THICK).height(sideH)
            : space().width(LINE_THICK).height(sideH));
        t.add(space().width(sideW).height(sideH));

        return t;
    }

    private TableBuilder buildInfoPanelContents(Player player, PlayerMenuSession session) {
        TableBuilder panel = table().align("topLeft").grow();

        TechNodeDef node = service.findNode(session.selectedNodeId);
        if (node == null) {
            panel.add(label("[lightgray]Select a node on the left to view details and research costs.[]").wrap().growX());
            return panel;
        }

        boolean unlocked = service.isUnlocked(player, node);
        boolean parentUnlocked = node.parent == null || service.isUnlocked(player, node.parent);
        boolean conditionsMet = service.areConditionsMet(player, node);

        // Header: icon + localized name + unlock status
        TableBuilder titleRow = table().align("left").growX().padBottom(8f);
        titleRow.add(image(node.resolvedIcon()).scaling(Scaling.fit).size(42f).padRight(10f));

        TableBuilder titleText = table().align("left").growX();
        titleText.add(label("[accent]" + node.resolvedName() + "[]").wrap().labelAlign("left").growX()).row();
        String statusBadge = unlocked
            ? "[stat]Unlocked[]"
            : (parentUnlocked ? "[lightgray]Locked - Researchable[]" : "[scarlet]Locked - Parent Required[]");
        titleText.add(label(statusBadge).labelAlign("left").growX());
        titleRow.add(titleText);

        panel.add(titleRow).row();
        panel.add(image("whiteui").color("454545ff").height(2f).growX().padBottom(8f)).row();

        // Description
        String desc = node.resolvedDescription();
        if (desc != null && !desc.isEmpty()) {
            panel.add(
                pane()
                    .style("smallPane")
                    .maxHeight(130f)
                    .growX()
                    .padBottom(10f)
                    .add(label("[lightgray]" + desc + "[]").wrap().labelAlign("left").width(275f))
            ).row();
        }

        // Parent dependency
        if (node.parent != null && !node.parent.virtual) {
            boolean pOk = service.isUnlocked(player, node.parent);
            panel.add(
                label((pOk ? "[stat]+ []" : "[scarlet]x []") + "[lightgray]Requires: []" + node.parent.resolvedName())
                    .wrap()
                    .labelAlign("left")
                    .growX()
                    .padBottom(6f)
            ).row();
        }

        // Special unlock conditions (if configured in techtree.hjson)
        if (!node.conditions.isEmpty()) {
            panel.add(label("[accent]Conditions:[]").labelAlign("left").growX().padBottom(4f)).row();
            for (UnlockCondition cond : node.conditions) {
                boolean met = cond.isMet(player, service.getProgressStore());
                String prefix = met ? "[stat][OK][] " : "[scarlet][X][] ";
                panel.add(
                    label(prefix + cond.describe(player, service.getProgressStore()))
                        .wrap()
                        .labelAlign("left")
                        .growX()
                        .padBottom(3f)
                ).row();
            }
            panel.add(space().height(6f)).row();
        }

        // Research item costs & partial progress
        if (!unlocked) {
            panel.add(label("[accent]Research Cost:[]").labelAlign("left").growX().padBottom(4f)).row();

            if (node.costs.length == 0) {
                panel.add(label("[stat]Free (no items required)[]").labelAlign("left").growX().padBottom(8f)).row();
            } else {
                TableBuilder costTable = table().align("left").growX().padBottom(10f);
                TechResourceStorage storage = service.getStorage();
                for (ItemStack req : node.costs) {
                    int paid = service.getProgressStore().getPaidAmount(player.uuid(), node.id, req.item.name);
                    int remaining = Math.max(req.amount - paid, 0);
                    int have = storage.getAmount(player, req.item);

                    String amountColor;
                    if (remaining == 0) {
                        amountColor = "[stat]";
                    } else if (have >= remaining) {
                        amountColor = "[white]";
                    } else if (have > 0) {
                        amountColor = "[accent]";
                    } else {
                        amountColor = "[scarlet]";
                    }

                    costTable.add(image("item-" + req.item.name + "-ui").scaling(Scaling.fit).size(20f).padRight(6f));
                    costTable.add(label(req.item.localizedName).labelAlign("left").growX());
                    costTable.add(label(amountColor + formatAmount(paid) + " / " + formatAmount(req.amount) + "[]")
                        .labelAlign("right"))
                        .row();
                }
                panel.add(costTable).row();
            }

            // Research / Contribute action button (incremental spending matching vanilla ResearchDialog)
            boolean canAct = parentUnlocked && conditionsMet && service.canSpendOrUnlockNow(player, node);
            boolean fullAfford = parentUnlocked && conditionsMet && service.canCompleteUnlockNow(player, node);

            String btnText;
            if (!parentUnlocked) {
                btnText = "[gray]Unlock Parent First[]";
            } else if (!conditionsMet) {
                btnText = "[scarlet]Conditions Not Met[]";
            } else if (!service.getStorage().isAvailable(player) && node.costs.length > 0) {
                btnText = "[scarlet]No Resource Core[]";
            } else if (fullAfford) {
                btnText = "[accent]Research & Unlock[]";
            } else if (canAct) {
                btnText = "[white]Contribute Items[]";
            } else {
                btnText = "[scarlet]Insufficient Items[]";
            }

            panel.add(
                button(btnText)
                    .icon(canAct ? "hammer" : "lock")
                    .style("defaultt")
                    .disabled(!canAct)
                    .clicked("research:" + node.id)
                    .height(44f)
                    .growX()
                    .padTop(4f)
            ).row();
        } else {
            int unlockedKids = 0;
            for (TechNodeDef ch : node.children) {
                if (service.isUnlocked(player, ch)) unlockedKids++;
            }
            panel.add(
                label("[stat]Researched & Ready to Use![]")
                    .labelAlign("left")
                    .growX()
                    .padBottom(6f)
            ).row();

            if (!node.children.isEmpty()) {
                panel.add(
                    label("[lightgray]Sub-branches unlocked: " + unlockedKids + " / " + node.children.size + "[]")
                        .labelAlign("left")
                        .growX()
                        .padBottom(6f)
                ).row();
            }
        }

        if (session.statusMessage != null && !session.statusMessage.isEmpty()) {
            panel.add(
                label(session.statusMessage)
                    .wrap()
                    .labelAlign("left")
                    .growX()
                    .padTop(8f)
            ).row();
        }

        panel.add(space().growY());
        return panel;
    }

    private TechNodeDef resolveTabBranch(TechNodeDef root, String tabId) {
        if (root == null) return null;
        if (tabId != null && tabId.equals(root.id)) return root;
        for (TechNodeDef child : root.children) {
            if (child.id.equals(tabId)) return child;
        }
        return root.children.isEmpty() ? root : root.children.first();
    }

    private boolean isInSubtree(TechNodeDef subtreeRoot, TechNodeDef candidate) {
        if (subtreeRoot == null || candidate == null) return false;
        TechNodeDef cur = candidate;
        while (cur != null) {
            if (cur == subtreeRoot) return true;
            cur = cur.parent;
        }
        return false;
    }

    private static String formatAmount(int amount) {
        if (amount >= 1_000_000) return Strings.autoFixed(amount / 1_000_000f, 1) + "m";
        if (amount >= 10_000) return Strings.autoFixed(amount / 1_000f, 1) + "k";
        return Integer.toString(amount);
    }
}
