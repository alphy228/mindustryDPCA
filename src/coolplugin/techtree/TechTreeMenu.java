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
 * Minimal, bloat-free vanilla-style Tech Tree UI built on Mindustry v160.7 {@link MenuBuilder}:
 * <ul>
 *   <li>Top bar displays ONLY resources with amount &gt; 0 (nothing shown when 0).</li>
 *   <li>Single unified 2D scrollable canvas (no branch tabs, no custom close button).</li>
 *   <li>Clicking any node opens a compact submenu directly below that node containing ONLY:
 *       {@code icon + name}, {@code costs}, {@code description}, and a {@code "Research"} button.</li>
 * </ul>
 */
public class TechTreeMenu {

    public static final String ID_ROOT = "alphystuff-tech-root";
    public static final String ID_HEADER = "alphystuff-tech-header";
    public static final String ID_TREE_CANVAS = "alphystuff-tech-canvas";

    private static final float NODE_SIZE = 50f;
    private static final float COL_GAP = 14f;
    private static final float ROW_GAP = 18f;
    private static final float CANVAS_MARGIN = 96f;
    private static final float CANVAS_BOTTOM_EXTRA = 160f;
    private static final float LINE_THICK = 4f;
    private static final float SUBMENU_WIDTH = 236f;

    private static final String COL_UNLOCKED_LINE = "ffd37fff";
    private static final String COL_LOCKED_LINE = "454545ff";
    private static final String COL_SELECTED_TINT = "ffd37fff";
    private static final String COL_DIM_ICON = "7a7a7aff";

    private static final class PlayerMenuSession {
        String selectedNodeId = "";
        String profileScope="";
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
        coolplugin.gameplay.UiSpriteSync.ensureSent(player);
        service.ensurePlayerInitialized(player);

        PlayerMenuSession session = getOrInitSession(player);
        session.selectedNodeId = "";

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
        if(!sessions.containsKey(player.uuid()))return;
        PlayerMenuSession session = getOrInitSession(player);
        pushPartialUpdates(player, session, true, true);
    }

    private PlayerMenuSession getOrInitSession(Player player) {
        PlayerMenuSession session = sessions.get(player.uuid());
        if (session == null || !session.profileScope.equals(service.progressScope(player))) {
            session = new PlayerMenuSession();session.profileScope=service.progressScope(player);
            sessions.put(player.uuid(), session);
        }
        if (!session.selectedNodeId.isEmpty()) {
            TechNodeDef sel = service.findNode(session.selectedNodeId);
            if (sel == null || !service.isVisible(player, sel)) {
                session.selectedNodeId = "";
            }
        }
        return session;
    }

    private void handleAction(Player player, String clicked) {
        PlayerMenuSession session = getOrInitSession(player);

        if (clicked.startsWith("n:")) {
            String nodeId = clicked.substring(2);
            TechNodeDef node = service.findNode(nodeId);
            if (node == null || !service.isVisible(player, node)) return;

            // Toggle submenu directly below the clicked node
            if (node.id.equals(session.selectedNodeId)) {
                session.selectedNodeId = "";
            } else {
                session.selectedNodeId = node.id;
            }
            pushPartialUpdates(player, session, false, true);
            return;
        }

        if (clicked.startsWith("r:")) {
            String nodeId = clicked.substring(2);
            TechNodeDef node = service.findNode(nodeId);
            if (node != null) {
                TechTreeService.ResearchOutcome outcome = service.attemptResearch(player, node);
                if (outcome.unlocked) {
                    session.selectedNodeId = "";
                } else {
                    session.selectedNodeId = node.id;
                }
                pushPartialUpdates(player, session, true, true);
            }
        }
    }

    private void pushPartialUpdates(
            Player player,
            PlayerMenuSession session,
            boolean updateHeader,
            boolean updateCanvas) {
        if (player.con == null) return;
        if (updateHeader) {
            Call.menuBuilderUpdate(player.con, menuId, ID_HEADER, buildHeaderContents(player));
        }
        if (updateCanvas) {
            Call.menuBuilderUpdate(player.con, menuId, ID_TREE_CANVAS, buildTreeCanvasContents(player, session));
        }
    }

    public TableBuilder buildFullDialog(Player player, PlayerMenuSession session) {
        TableBuilder root = table()
            .id(ID_ROOT)
            .grow();

        // Top bar: ONLY non-zero resources (empty when all resources are 0)
        TableBuilder headerBox = table()
            .id(ID_HEADER)
            .growX();
        headerBox.add(buildHeaderContents(player));
        root.add(headerBox).row();

        // Unified 2D scrollable canvas (no branch tabs, no side panel, no custom close button)
        TableBuilder treeCanvasHolder = table()
            .id(ID_TREE_CANVAS);
        treeCanvasHolder.add(buildTreeCanvasContents(player, session));

        PaneBuilder scrollCanvas = pane()
            .style("defaultPane")
            .grow()
            .add(treeCanvasHolder);

        root.add(scrollCanvas);
        return root;
    }

    /** Public helper for headless packet-size & layout verification. */
    public TableBuilder buildCanvasUpdateForTest(Player player, String selectedNodeId) {
        PlayerMenuSession session = getOrInitSession(player);
        session.selectedNodeId = selectedNodeId == null ? "" : selectedNodeId;
        return buildTreeCanvasContents(player, session);
    }

    /** Public helper for headless packet-size verification. */
    public int measureFullDialogBytes(Player player, String selectedNodeId) {
        PlayerMenuSession session = getOrInitSession(player);
        session.selectedNodeId = selectedNodeId == null ? "" : selectedNodeId;
        return serializeSize(buildFullDialog(player, session));
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

    private TableBuilder buildHeaderContents(Player player) {
        TableBuilder resBar = table().wrap(true).align("center");
        TechResourceStorage storage = service.getStorage();

        boolean anyShown = false;
        for (Item item : storage.displayItems(player, service.getReferencedItems())) {
            int amt = storage.getAmount(player, item);
            if (amt <= 0) continue; // Do not show resources whose amount is 0

            anyShown = true;
            resBar.add(image("item-" + item.name + "-ui").scaling(Scaling.fit).size(20f).padRight(3f).padTop(2f).padBottom(2f));
            resBar.add(label(formatAmount(amt)).padRight(10f).padTop(2f).padBottom(2f));
        }

        if (anyShown) {
            return table().background("pane").margin(6f).padBottom(4f).add(resBar);
        }
        return table();
    }

    /**
     * Wraps the {@code StackBuilder} inside a {@link TableBuilder} so {@code Call.menuBuilderUpdate}
     * (whose {@code UiTreeBuilder.build(Table, NodeBuilder)} unpacks root entries directly into the target {@code Table})
     * keeps {@code grid} and {@code submenu} stacked on top of each other rather than side-by-side.
     */
    private TableBuilder buildTreeCanvasContents(Player player, PlayerMenuSession session) {
        TechNodeDef root = service.getRoot();

        GridCanvas canvas = TechTreeLayoutEngine.buildCanvas(
            root,
            node -> service.isVisible(player, node),
            node -> service.isUnlocked(player, node)
        );

        TableBuilder grid = table().align("topLeft").margin(CANVAS_MARGIN);

        // Row 0: zero-height column-width definitions so empty runs can be collapsed with colspan()
        for (int c = 0; c < canvas.cols; c++) {
            float colW = (c % 2 == 0) ? NODE_SIZE : COL_GAP;
            grid.add(space().width(colW).height(0f));
        }
        grid.row();

        for (int r = 0; r < canvas.rows; r++) {
            boolean isNodeRow = (r % 2 == 0);
            float rowH = isNodeRow ? NODE_SIZE : ROW_GAP;

            int c = 0;
            while (c < canvas.cols) {
                TechNodeDef node = canvas.nodes[r][c];
                int mask = canvas.lineMask[r][c];

                // Collapse consecutive empty cells into a single colspan space()
                if (node == null && mask == 0) {
                    int run = 1;
                    while (c + run < canvas.cols && canvas.nodes[r][c + run] == null && canvas.lineMask[r][c + run] == 0) {
                        run++;
                    }
                    SpaceBuilder sp = space().height(rowH);
                    if (run > 1) sp.colspan(run);
                    grid.add(sp);
                    c += run;
                    continue;
                }

                // Collapse consecutive straight horizontal line cells of the same color into a single colspan image()
                if (node == null && mask == (DIR_LEFT | DIR_RIGHT)) {
                    boolean unl = canvas.unlockedMask[r][c];
                    int run = 1;
                    float spanW = (c % 2 == 0) ? NODE_SIZE : COL_GAP;
                    while (c + run < canvas.cols
                            && canvas.nodes[r][c + run] == null
                            && canvas.lineMask[r][c + run] == (DIR_LEFT | DIR_RIGHT)
                            && canvas.unlockedMask[r][c + run] == unl) {
                        spanW += ((c + run) % 2 == 0) ? NODE_SIZE : COL_GAP;
                        run++;
                    }
                    String lineColor = unl ? COL_UNLOCKED_LINE : COL_LOCKED_LINE;
                    ImageBuilder bar = image("whiteui").color(lineColor).width(spanW).height(LINE_THICK);
                    if (run > 1) bar.colspan(run);
                    grid.add(bar);
                    c += run;
                    continue;
                }

                float colW = (c % 2 == 0) ? NODE_SIZE : COL_GAP;
                if (node != null) {
                    grid.add(buildNodeButton(player, session, node));
                } else {
                    String lineColor = canvas.unlockedMask[r][c] ? COL_UNLOCKED_LINE : COL_LOCKED_LINE;
                    grid.add(buildConnectorCell(mask, colW, rowH, lineColor));
                }
                c++;
            }
            grid.row();
        }
        grid.add(space().height(CANVAS_BOTTOM_EXTRA).colspan(canvas.cols)).row();

        StackBuilder layers = stack().add(grid);

        // If a node is clicked, render its submenu directly below that node
        if (!session.selectedNodeId.isEmpty()) {
            TechNodeDef selected = service.findNode(session.selectedNodeId);
            if (selected != null && service.isVisible(player, selected)) {
                float nodeLeftX = CANVAS_MARGIN + selected.gridX * (NODE_SIZE + COL_GAP);
                float nodeCenterX = nodeLeftX + NODE_SIZE / 2f;
                float nodeBottomY = CANVAS_MARGIN + selected.gridY * (NODE_SIZE + ROW_GAP) + NODE_SIZE;

                float popupLeftX = Math.max(4f, nodeCenterX - SUBMENU_WIDTH / 2f);
                float popupTopY = nodeBottomY + 4f;

                StackBuilder submenu = buildNodeSubmenu(player, selected)
                    .width(SUBMENU_WIDTH)
                    .padLeft(popupLeftX)
                    .padTop(popupTopY)
                    .padBottom(12f);

                layers.add(table().align("topLeft").add(submenu));
            }
        }

        return table().add(layers);
    }

    /**
     * Uses {@code buttonTable()} + child {@code image(...)} rather than {@code imageButton(...)},
     * because {@code UiTreeBuilder}'s {@code imageButton} calls {@code setStyle(Styles.nodei / defaulti)}
     * AFTER setting the drawable, which overwrites {@code imageUp} with {@code null}.
     */
    private NodeBuilder<?> buildNodeButton(Player player, PlayerMenuSession session, TechNodeDef node) {
        boolean unlocked = service.isUnlocked(player, node);
        boolean selected = node.id.equals(session.selectedNodeId);
        boolean parentUnlocked = service.areAncestorsUnlocked(player, node);
        boolean canSpend = !unlocked && service.canSpendOrUnlockNow(player, node);

        var btn = buttonTable()
            .style(unlocked ? "nodei" : "defaulti")
            .clicked("n:" + node.id)
            .size(NODE_SIZE);

        ImageBuilder icon = image(node.resolvedIcon()).scaling(Scaling.fit).size(32f);
        String fallback = node.resolvedIconFallback();
        if (fallback != null) {
            icon.placeholder(fallback);
        }
        if (selected) {
            btn.color(COL_SELECTED_TINT);
        } else if (!unlocked && !(parentUnlocked && canSpend)) {
            btn.color("ff6464cc");
            icon.color(COL_DIM_ICON);
        } else if (!unlocked) {
            icon.color("d8d8d8ff");
        }
        btn.add(icon);
        return btn;
    }

    /**
     * Submenu rendered directly below the clicked node:
     * 1) Icon + Localized Name on top
     * 2) Costs (when locked and non-pluginUnlocked; plus any configured special conditions)
     * 3) Localized Description
     * 4) "Research" button (omitted for {@code pluginUnlocked} database milestone nodes).
     */
    private StackBuilder buildNodeSubmenu(Player player, TechNodeDef node) {
        boolean unlocked = service.isUnlocked(player, node);
        boolean parentUnlocked = service.areAncestorsUnlocked(player, node);
        boolean conditionsMet = service.areConditionsMet(player, node);
        boolean canAct = !node.pluginUnlocked && !unlocked && parentUnlocked && conditionsMet && service.canSpendOrUnlockNow(player, node);

        TableBuilder body = table().margin(10f).align("topLeft");

        // 1. Icon + Localized Name on top (`@type.name.name` must start at index 0 so client Label resolves Core.bundle)
        TableBuilder topRow = table().align("left").growX().padBottom(6f);
        ImageBuilder headerIcon = image(node.resolvedIcon()).scaling(Scaling.fit).size(26f).padRight(6f);
        String fallback = node.resolvedIconFallback();
        if (fallback != null) {
            headerIcon.placeholder(fallback);
        }
        topRow.add(headerIcon);
        topRow.add(
            label(node.uiNameText())
                .color("ffd37fff")
                .wrap()
                .labelAlign("left")
                .growX()
        );
        body.add(topRow).row();

        // 2. Costs (only when locked, non-pluginUnlocked, and non-empty)
        if (!node.pluginUnlocked && !unlocked && node.costs.length > 0) {
            TableBuilder costsTable = table().align("left").growX().padBottom(6f);
            TechResourceStorage storage = service.getStorage();
            for (ItemStack req : node.costs) {
                int paid = service.getProgressStore().getPaidAmount(player.uuid(), node.id, req.item.name);
                int remaining = Math.max(req.amount - paid, 0);
                int have = storage.getAmount(player, req.item);

                String col = (remaining == 0 || have >= remaining)
                    ? "[white]"
                    : (have > 0 ? "[lightgray]" : "[scarlet]");
                String text = paid > 0
                    ? (formatAmount(paid) + " / " + formatAmount(req.amount))
                    : formatAmount(req.amount);

                costsTable.add(image("item-" + req.item.name + "-ui").scaling(Scaling.fit).size(18f).padRight(5f));
                costsTable.add(label(col + text + "[]").labelAlign("left").growX()).row();
            }
            body.add(costsTable).row();
        }

        if (!unlocked && !node.conditions.isEmpty()) {
            for (UnlockCondition cond : node.conditions) {
                boolean met = cond.isMet(player, service.getProgressStore());
                body.add(
                    label((met ? "[stat]" : "[scarlet]") + cond.describe(player, service.getProgressStore()) + "[]")
                        .wrap()
                        .labelAlign("left")
                        .width(SUBMENU_WIDTH - 20f)
                        .padBottom(4f)
                ).row();
            }
        }

        // 3. Localized Description (`@type.name.description` resolved client-side via Core.bundle)
        String desc = node.uiDescriptionText();
        if (desc != null && !desc.isEmpty()) {
            body.add(
                label(desc)
                    .color("d3d3d3ff")
                    .wrap()
                    .labelAlign("left")
                    .width(SUBMENU_WIDTH - 20f)
                    .padBottom(node.pluginUnlocked ? 0f : 8f)
            ).row();
        }

        // 4. "Research" button (shown only for normal researchable nodes, not plugin-unlocked nodes)
        if (!node.pluginUnlocked) {
            body.add(
                button("@research")
                    .style("defaultt")
                    .disabled(!canAct)
                    .clicked("r:" + node.id)
                    .height(38f)
                    .growX()
            );
        }

        // `image("button")` draws the 9-patch background AND blocks clicks from passing through to nodes underneath
        return stack()
            .add(image("button"))
            .add(body);
    }

    private NodeBuilder<?> buildConnectorCell(int mask, float cellW, float cellH, String color) {
        if (mask == (DIR_LEFT | DIR_RIGHT)) {
            return image("whiteui").color(color).width(cellW).height(LINE_THICK);
        }
        if (mask == (DIR_UP | DIR_DOWN)) {
            return image("whiteui").color(color).width(LINE_THICK).height(cellH);
        }

        boolean up = (mask & DIR_UP) != 0;
        boolean right = (mask & DIR_RIGHT) != 0;
        boolean down = (mask & DIR_DOWN) != 0;
        boolean left = (mask & DIR_LEFT) != 0;

        float halfW = (cellW + LINE_THICK) / 2f;
        float halfH = (cellH + LINE_THICK) / 2f;

        StackBuilder s = stack().width(cellW).height(cellH);
        if (left && right) {
            s.add(table().add(image("whiteui").color(color).width(cellW).height(LINE_THICK)));
        } else if (left) {
            s.add(table().align("left").add(image("whiteui").color(color).width(halfW).height(LINE_THICK)));
        } else if (right) {
            s.add(table().align("right").add(image("whiteui").color(color).width(halfW).height(LINE_THICK)));
        }

        if (up && down) {
            s.add(table().add(image("whiteui").color(color).width(LINE_THICK).height(cellH)));
        } else if (up) {
            s.add(table().align("top").add(image("whiteui").color(color).width(LINE_THICK).height(halfH)));
        } else if (down) {
            s.add(table().align("bottom").add(image("whiteui").color(color).width(LINE_THICK).height(halfH)));
        }
        return s;
    }

    private static String formatAmount(int amount) {
        if (amount >= 1_000_000) return Strings.autoFixed(amount / 1_000_000f, 1) + "m";
        if (amount >= 10_000) return Strings.autoFixed(amount / 1_000f, 1) + "k";
        return Integer.toString(amount);
    }
}
