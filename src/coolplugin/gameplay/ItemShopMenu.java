package coolplugin.gameplay;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Scaling;
import arc.util.io.Writes;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.PlayerInventoryDrawer.ActionResult;
import coolplugin.gameplay.PlayerInventoryDrawer.DrawerTheme;
import coolplugin.gameplay.PlayerItemSystem.ActionOutcome;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.ShopCategory;
import coolplugin.gameplay.ShopItemDef.StationTier;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuBuilder;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Locale;

import static mindustry.ui.builder.UiBuilder.*;

/**
 * Deadlock-inspired multi-column Item Shop menu for {@link StationTier#WORKBENCH},
 * {@link StationTier#BAZAAR}, and {@link StationTier#CURSED_ALTAR}:
 * <ul>
 *   <li>Clean top station title ({@code Workbench}, {@code Bazaar}, {@code Cursed Altar}) with no icon and no background box.</li>
 *   <li>Borderless non-zero resource item counter directly below the station title.</li>
 *   <li>4 category headers ({@code Resource}, {@code Attack}, {@code Defense}, {@code Utility}) with no background boxes,
 *       keeping only the thick colored category lines.</li>
 *   <li>Search bar directly below the category names.</li>
 *   <li>2 columns per category of pure custom-icon rectangles; item name, description, stats, and costs remain hidden
 *       until clicked, which opens the item detail ({@code dsc}) panel.</li>
 *   <li>Right-side hexagonal inventory + multipliers below rendered via reusable {@link PlayerInventoryDrawer}.</li>
 * </ul>
 */
public class ItemShopMenu {

    public static final String ID_ROOT = "alphy-shop-root";
    public static final String ID_TITLE = "alphy-shop-title";
    public static final String ID_TOP_RES = "alphy-shop-topres";
    public static final String ID_LEFT_AREA = "alphy-shop-columns";
    public static final String ID_RIGHT_COL = "alphy-shop-rightcol";
    public static final String ID_SEARCH_FIELD = "alphy-shop-search";

    private static final float CAT_COL_W = 172f;
    private static final float CAT_COL_GAP = 12f;
    private static final float CAT_SCROLL_H_FULL = 408f;
    private static final float CAT_SCROLL_H_WITH_DSC = 292f;
    private static final float ICON_CELL_W = 68f;
    private static final float ICON_CELL_H = 52f;
    private static final float LEFT_TOTAL_W = CAT_COL_W * 4f + CAT_COL_GAP * 3f;

    private static final float RIGHT_GAP = 14f;
    private static final float TOTAL_W = LEFT_TOTAL_W + RIGHT_GAP + PlayerInventoryDrawer.WIDTH;

    private static final class Session {
        StationTier station = StationTier.WORKBENCH;
        boolean bazaarShipsMode = false;
        String searchQuery = "";
        String selectedShopItemId = "";
        int selectedInvSlot = -1;
        String statusMsg = "";
    }

    private final PlayerItemSystem itemSystem;
    private final BuildSystem buildSystem;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final PlayerInventoryDrawer inventoryDrawer;
    private final ObjectMap<String, Session> sessions = new ObjectMap<>();
    private int menuId = -1;

    public ItemShopMenu(
        PlayerItemSystem itemSystem,
        BuildSystem buildSystem,
        PlayerDatabase database,
        PlayerSessionManager sessionManager
    ) {
        this.itemSystem = itemSystem;
        this.buildSystem = buildSystem;
        this.database = database;
        this.sessionManager = sessionManager;
        this.inventoryDrawer = new PlayerInventoryDrawer(itemSystem);
    }

    public PlayerInventoryDrawer getInventoryDrawer() {
        return inventoryDrawer;
    }

    public void register() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder((player, res) -> {
            if (player == null || res == null || res.wasCancelled() || res.result == null) return;
            handleAction(player, res);
        });
    }

    public int getMenuId() {
        return menuId;
    }

    public void open(Player player, StationTier station) {
        if (player == null || player.con == null || !player.con.isConnected()) return;
        register();
        UiSpriteSync.ensureSent(player);

        Session s = getOrCreateSession(player);
        s.station = station;
        s.bazaarShipsMode = false;
        s.searchQuery = "";
        s.selectedShopItemId = "";
        s.selectedInvSlot = -1;
        s.statusMsg = "";

        MenuBuilder.of(buildFullDialog(player, s))
            .id(menuId)
            .title(null)
            .hideOnClick(false)
            .hideExisting(true)
            .fillScreen(true)
            .show(player);
    }

    public void refreshOpenSession(@Nullable Player player) {
        if (player == null || player.con == null || !player.con.isConnected() || menuId == -1) return;
        Session s = sessions.get(player.uuid());
        if (s == null) return;
        s.selectedInvSlot = -1;
        s.statusMsg = "";
        pushUpdates(player, s, true, true, true, true);
    }

    public TableBuilder buildForTest(Player player, StationTier station) {
        return buildForTest(player, station, false, -1, "", "");
    }

    public TableBuilder buildForTest(Player player, StationTier station, boolean shipsMode, int selectedInvSlot) {
        return buildForTest(player, station, shipsMode, selectedInvSlot, "", "");
    }

    public TableBuilder buildForTest(
        Player player,
        StationTier station,
        boolean shipsMode,
        int selectedInvSlot,
        String selectedShopItemId,
        String searchQuery
    ) {
        Session s = getOrCreateSession(player);
        s.station = station;
        s.bazaarShipsMode = (station == StationTier.BAZAAR) && shipsMode;
        s.selectedInvSlot = selectedInvSlot;
        s.selectedShopItemId = selectedShopItemId == null ? "" : selectedShopItemId;
        s.searchQuery = searchQuery == null ? "" : searchQuery;
        return buildFullDialog(player, s);
    }

    public static int serializeSize(NodeBuilder<?> builder) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            builder.write(new Writes(new DataOutputStream(bos)));
            return bos.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private Session getOrCreateSession(Player player) {
        Session s = sessions.get(player.uuid());
        if (s == null) {
            s = new Session();
            sessions.put(player.uuid(), s);
        }
        return s;
    }

    private void handleAction(Player player, MenuResult res) {
        String action = res.result;
        Session s = getOrCreateSession(player);

        // Preserve any typed text in the search field across clicks
        if (res.values != null && res.values.containsKey(ID_SEARCH_FIELD)) {
            s.searchQuery = res.getString(ID_SEARCH_FIELD, "").trim();
        }

        // 1. Delegate right-hand inventory drawer actions to reusable PlayerInventoryDrawer
        ActionResult invRes = inventoryDrawer.handleAction(player, action, s.selectedInvSlot, s.station);
        if (invRes.handled()) {
            s.selectedInvSlot = invRes.selectedSlot();
            EquipmentData selectedEq = itemSystem.getEquipment(player);
            s.selectedShopItemId = s.selectedInvSlot >= 0 && s.selectedInvSlot < selectedEq.equipped.size
                ? selectedEq.equipped.get(s.selectedInvSlot) : "";
            if (action.startsWith(PlayerInventoryDrawer.ACT_SELECT_PREFIX) && s.selectedInvSlot >= 0) {
                s.bazaarShipsMode = false;
            }
            s.statusMsg = invRes.statusMessage();
            clearUnownedSalvageSelection(player, s);
            pushUpdates(
                player,
                s,
                !s.statusMsg.isEmpty(),
                invRes.resourcesChanged(),
                true,
                true
            );
            return;
        }

        // 2. Search bar apply / clear
        if ("search:apply".equals(action)) {
            s.statusMsg = "";
            pushUpdates(player, s, false, false, true, false);
            return;
        }

        if ("search:clear".equals(action)) {
            s.searchQuery = "";
            s.statusMsg = "";
            pushUpdates(player, s, false, false, true, false);
            return;
        }

        // 3. Bazaar mode toggle (Crafts vs Orbital Ships)
        if ("mode:crafts".equals(action)) {
            s.bazaarShipsMode = false;
            s.statusMsg = "";
            pushUpdates(player, s, true, true, true, false);
            return;
        }

        if ("mode:ships".equals(action) && s.station == StationTier.BAZAAR) {
            s.bazaarShipsMode = true;
            s.selectedShopItemId = "";
            s.statusMsg = "";
            pushUpdates(player, s, true, true, true, false);
            return;
        }

        // 4. Shop item selection -> toggles the item detail (dsc) panel
        if (action.startsWith("select:")) {
            s.selectedInvSlot = -1;
            String itemId = action.substring("select:".length());
            if ("close".equals(itemId) || itemId.equals(s.selectedShopItemId)) {
                s.selectedShopItemId = "";
            } else {
                ShopItemDef candidate = itemSystem.getCatalog().findItem(itemId);
                if (candidate != null && isItemVisibleInShop(candidate, itemSystem.getEquipment(player))) {
                    s.selectedShopItemId = itemId;
                }
            }
            s.statusMsg = "";
            pushUpdates(player, s, true, false, true, false);
            return;
        }

        // 5. Craft from opened dsc panel
        if (action.startsWith("craft:")) {
            String itemId = action.substring("craft:".length());
            ActionOutcome out = itemSystem.craft(player, itemId, s.station);
            s.statusMsg = out.message();
            s.selectedInvSlot = inventoryDrawer.clampSlot(player, s.selectedInvSlot);
            pushUpdates(player, s, true, true, true, true);
            return;
        }

        // 6. Bazaar orbital ship trade
        if (action.startsWith("trade:")) {
            if (s.station != StationTier.BAZAAR) return;
            String tradeId = action.substring("trade:".length());
            ActionOutcome out = itemSystem.executeOrbitalTrade(player, tradeId);
            s.statusMsg = out.message();
            s.selectedInvSlot = inventoryDrawer.clampSlot(player, s.selectedInvSlot);
            pushUpdates(player, s, true, true, true, true);
        }
    }

    private void pushUpdates(
        Player player,
        Session s,
        boolean titleRow,
        boolean topRes,
        boolean leftArea,
        boolean rightCol
    ) {
        if (player.con == null || !player.con.isConnected()) return;
        if (titleRow) Call.menuBuilderUpdate(player.con, menuId, ID_TITLE, buildTopTitleRow(s));
        if (topRes) Call.menuBuilderUpdate(player.con, menuId, ID_TOP_RES, buildResourceCounterBar(player, s));
        if (leftArea) Call.menuBuilderUpdate(player.con, menuId, ID_LEFT_AREA, buildLeftArea(player, s));
        if (rightCol) Call.menuBuilderUpdate(player.con, menuId, ID_RIGHT_COL, buildRightDrawer(player, s));
    }

    private TableBuilder buildFullDialog(Player player, Session s) {
        TableBuilder root = table().id(ID_ROOT).align("center").margin(6f);

        // 1. Station title text on top (NO icon, NO background rectangle!)
        TableBuilder titleHolder = table().id(ID_TITLE).width(TOTAL_W);
        titleHolder.add(buildTopTitleRow(s));
        root.add(titleHolder.padBottom(2f)).row();

        // 2. Non-zero resource item counter directly BELOW station title (NO background rectangle!)
        TableBuilder topResHolder = table().id(ID_TOP_RES).width(TOTAL_W);
        topResHolder.add(buildResourceCounterBar(player, s));
        root.add(topResHolder.padBottom(8f)).row();

        // 3. Main Body: Left = Category Headers + Search Bar + 2-Col Icon Grids (+ dsc on click) | Right = PlayerInventoryDrawer
        TableBuilder bodyRow = table().align("top");

        TableBuilder leftHolder = table().id(ID_LEFT_AREA).width(LEFT_TOTAL_W).align("top");
        leftHolder.add(buildLeftArea(player, s));
        bodyRow.add(leftHolder).align("top");

        TableBuilder rightHolder = table().id(ID_RIGHT_COL).width(PlayerInventoryDrawer.WIDTH).align("top").padLeft(RIGHT_GAP);
        rightHolder.add(buildRightDrawer(player, s));
        bodyRow.add(rightHolder).align("top");

        root.add(bodyRow);
        return root;
    }

    // ------------------------------------------------------------------ 1. Pure Text Top Station Title

    private TableBuilder buildTopTitleRow(Session s) {
        StationTier st = s.station;
        TableBuilder row = table().align("center").width(TOTAL_W);
        row.add(
            label("[#" + st.accentHex + "]" + st.title + "[]")
                .style("outlineLabel")
                .labelAlign("center")
        );
        if (s.statusMsg != null && !s.statusMsg.isEmpty()) {
            row.row();
            row.add(label(s.statusMsg).labelAlign("center").padTop(2f));
        }
        return row;
    }

    // ------------------------------------------------------------------ 2. Borderless Resource Item Counter Below Title

    private TableBuilder buildResourceCounterBar(Player player, Session s) {
        TableBuilder resBar = table().wrap(true).align("center");
        boolean anyShown = false;

        if (s.station == StationTier.BAZAAR && s.bazaarShipsMode) {
            AccountRecord acc = sessionManager.getOrInitSession(player);
            String factionId = sessionManager.getActiveFactionId(player.uuid());
            ObjectIntMap<String> orbital = database.getOrbitalStorage(acc.id, factionId);
            if (Vars.content != null) {
                for (Item it : Vars.content.items()) {
                    if (it.isHidden()) continue;
                    int amt = orbital.get(it.name, 0);
                    if (amt <= 0) continue;
                    anyShown = true;
                    resBar.add(image("item-" + it.name + "-ui").scaling(Scaling.fit).size(20f).padRight(3f).padTop(2f).padBottom(2f));
                    resBar.add(label("[#7fe6ff]" + formatCompact(amt) + "[]").padRight(10f).padTop(2f).padBottom(2f));
                }
            }
        } else {
            ObjectIntMap<Item> mapRes = buildSystem.getAvailableSnapshot(player);
            if (Vars.content != null) {
                for (Item it : Vars.content.items()) {
                    if (it.isHidden()) continue;
                    int amt = mapRes.get(it, 0);
                    if (amt <= 0) continue;
                    anyShown = true;
                    resBar.add(image("item-" + it.name + "-ui").scaling(Scaling.fit).size(20f).padRight(3f).padTop(2f).padBottom(2f));
                    resBar.add(label(formatCompact(amt)).padRight(10f).padTop(2f).padBottom(2f));
                }
            }
        }

        // No background("pane") rectangle!
        if (anyShown) {
            return table().width(TOTAL_W).align("center").add(resBar);
        }
        return table().width(TOTAL_W);
    }

    // ------------------------------------------------------------------ 3. Left Area: Category Headers + Search Bar + 2-Col Icon Grids + dsc

    private TableBuilder buildLeftArea(Player player, Session s) {
        TableBuilder left = table().align("topLeft").width(LEFT_TOTAL_W);

        // Row A: Category Names with ONLY thick colored lines (no background rectangles!)
        ShopCategory[] categories = ShopCategory.values();
        if (!(s.station == StationTier.BAZAAR && s.bazaarShipsMode)) {
            TableBuilder headersRow = table().align("left");
            for (int i = 0; i < categories.length; i++) {
                ShopCategory category = categories[i];
                TableBuilder head = table().align("center").width(CAT_COL_W);
                head.add(
                    label("[#" + category.accentHex + "]" + category.title + "[]")
                        .labelAlign("center")
                        .padBottom(4f)
                ).row();
                head.add(
                    image("whiteui")
                        .color(category.accentHex + "ff")
                        .height(4f)
                        .width(CAT_COL_W)
                );
                if (i < categories.length - 1) {
                    head.padRight(CAT_COL_GAP);
                }
                headersRow.add(head);
            }
            left.add(headersRow.padBottom(6f)).row();
        }

        // Row B: Search Bar directly below category names (+ Bazaar Crafts / Orbital Ships toggle if at Bazaar)
        left.add(buildSearchAndModeBar(s).padBottom(6f)).row();

        if (s.station == StationTier.BAZAAR && s.bazaarShipsMode) {
            left.add(buildOrbitalShipsPanel(player, s.searchQuery));
            return left;
        }

        Catalog cat = itemSystem.getCatalog();
        EquipmentData eq = itemSystem.getEquipment(player);
        ObjectIntMap<Item> mapRes = buildSystem.getAvailableSnapshot(player);
        String detailItemId = s.selectedInvSlot >= 0 && s.selectedInvSlot < eq.equipped.size
            ? eq.equipped.get(s.selectedInvSlot) : s.selectedShopItemId;
        ShopItemDef selectedItem = detailItemId.isEmpty() ? null : cat.findItem(detailItemId);
        if (selectedItem != null && !isItemVisibleInShop(selectedItem, eq)) {
            selectedItem = null;
            s.selectedShopItemId = "";
        }
        float scrollH = (selectedItem != null) ? CAT_SCROLL_H_WITH_DSC : CAT_SCROLL_H_FULL;

        // Row C: 4 Scrollable Columns, each containing a 2-column grid of icon-only rectangles
        TableBuilder colsRow = table().align("topLeft");
        for (int i = 0; i < categories.length; i++) {
            ShopCategory category = categories[i];
            TableBuilder colPane = buildCategoryIconPane(
                s.station,
                category,
                cat,
                eq,
                mapRes,
                s.searchQuery,
                s.selectedShopItemId,
                scrollH
            ).width(CAT_COL_W);
            if (i < categories.length - 1) {
                colPane.padRight(CAT_COL_GAP);
            }
            colsRow.add(colPane).align("top");
        }
        left.add(colsRow).row();

        // Row D: Item Description / Cost / Craft panel (dsc), ONLY shown when an item is clicked!
        if (selectedItem != null) {
            left.add(buildItemDescriptionPanel(player,selectedItem, s.station, cat, eq, mapRes).padTop(8f));
        }

        return left;
    }

    private TableBuilder buildSearchAndModeBar(Session s) {
        TableBuilder bar = table().align("left").width(LEFT_TOTAL_W);

        bar.add(
            field(s.searchQuery)
                .id(ID_SEARCH_FIELD)
                .hint("Search items...")
                .maxLength(48)
                .enter("search:apply")
                .growX()
                .height(30f)
                .padRight(5f)
        );

        bar.add(
            buttonTable()
                .style("defaultt")
                .margin(4f)
                .clicked("search:apply")
                .add(label("[lightgray]Search[]"))
                .width(74f)
                .height(30f)
                .padRight(s.searchQuery.isEmpty() ? 0f : 4f)
        );

        if (!s.searchQuery.isEmpty()) {
            bar.add(
                buttonTable()
                    .style("defaultt")
                    .margin(4f)
                    .clicked("search:clear")
                    .add(label("[scarlet]Clear[]"))
                    .width(60f)
                    .height(30f)
            );
        }

        if (s.station == StationTier.BAZAAR) {
            bar.add(modeBtn("Crafts", "mode:crafts", !s.bazaarShipsMode, s.station.accentHex, 96f).padLeft(8f).padRight(4f));
            bar.add(modeBtn("Orbital Ships", "mode:ships", s.bazaarShipsMode, s.station.accentHex, 142f));
        }

        return bar;
    }

    private static ButtonTableBuilder modeBtn(String text, String action, boolean active, String accentHex, float width) {
        String labelText = active ? ("[#" + accentHex + "]" + text + "[]") : ("[lightgray]" + text + "[]");
        return buttonTable()
            .style("defaultt")
            .checked(active)
            .margin(4f)
            .clicked(action)
            .add(label(labelText).labelAlign("center"))
            .width(width)
            .height(30f);
    }

    private TableBuilder buildCategoryIconPane(
        StationTier station,
        ShopCategory category,
        Catalog cat,
        EquipmentData eq,
        ObjectIntMap<Item> mapRes,
        String searchQuery,
        String selectedShopItemId,
        float scrollHeight
    ) {
        TableBuilder list = table().align("topLeft").margin(2f);

        if (station == StationTier.BAZAAR) {
            Seq<ShopItemDef> tier1 = filterItems(cat.itemsForTierAndCategory(StationTier.WORKBENCH, category), eq, searchQuery, cat);
            Seq<ShopItemDef> tier2 = filterItems(cat.itemsForTierAndCategory(StationTier.BAZAAR, category), eq, searchQuery, cat);

            if (!tier1.isEmpty()) {
                list.add(tierDivider("I", "eab678").padRight(16f)).row();
                list.add(buildTwoColIconGrid(tier1, eq, mapRes, selectedShopItemId).padRight(16f)).row();
            }
            if (!tier2.isEmpty()) {
                list.add(tierDivider("II", "7fe6ff").padRight(16f).padTop(4f)).row();
                list.add(buildTwoColIconGrid(tier2, eq, mapRes, selectedShopItemId).padRight(16f)).row();
            }
        } else {
            Seq<ShopItemDef> items = filterItems(cat.itemsForTierAndCategory(station, category), eq, searchQuery, cat);
            if (!items.isEmpty()) {
                list.add(buildTwoColIconGrid(items, eq, mapRes, selectedShopItemId).padRight(16f)).row();
            }
        }

        list.add(space().growY());

        PaneBuilder scroll = pane()
            .style("defaultPane")
            .width(CAT_COL_W)
            .height(scrollHeight)
            .add(list.growY().align("topLeft"));

        return table().align("top").add(scroll);
    }

    private TableBuilder buildTwoColIconGrid(
        Seq<ShopItemDef> items,
        EquipmentData eq,
        ObjectIntMap<Item> mapRes,
        String selectedShopItemId
    ) {
        TableBuilder grid = table().align("topLeft");
        for (int i = 0; i < items.size; i++) {
            ShopItemDef item = items.get(i);
            boolean selected = item.id.equals(selectedShopItemId);
            boolean affordable = item.craftable && canAfford(item, eq, mapRes);

            ButtonTableBuilder tile = buttonTable()
                .style("defaultt")
                .checked(selected)
                .margin(4f)
                .width(ICON_CELL_W)
                .height(ICON_CELL_H)
                .clicked("select:" + item.id);

            ImageBuilder icon = image(item.uiDrawable())
                .placeholder("item-" + item.iconItem + "-ui")
                .scaling(Scaling.fit)
                .size(30f);
            if (!affordable && !selected) {
                icon.color("9698a8ff");
            }
            tile.add(icon);

            grid.add(tile.padRight((i % 2 == 0) ? 6f : 0f).padBottom(6f));
            if (i % 2 == 1) {
                grid.row();
            }
        }
        return grid;
    }

    private TableBuilder buildItemDescriptionPanel(
        Player player,ShopItemDef item,
        StationTier station,
        Catalog cat,
        EquipmentData eq,
        ObjectIntMap<Item> mapRes
    ) {
        boolean craftableHere = item.craftable && itemSystem.isStationAllowedToCraft(station, item);
        boolean affordable = craftableHere && itemSystem.isItemResearched(player,item) && canAfford(item, eq, mapRes);

        TableBuilder box = table().background("pane").margin(8f).width(LEFT_TOTAL_W);

        // Left: Icon + Name + Passive Summary + Description
        TableBuilder row = table().align("left").growX();
        row.add(
            image(item.uiDrawable())
                .placeholder("item-" + item.iconItem + "-ui")
                .scaling(Scaling.fit)
                .size(36f)
                .padRight(10f)
        );

        TableBuilder textCol = table().align("left").width(LEFT_TOTAL_W - 260f);
        textCol.add(
            label("[#" + item.tier.accentHex + "]" + item.name + "[]  " + item.formatPassiveSummary())
                .wrap()
                .labelAlign("left")
                .width(LEFT_TOTAL_W - 266f)
        ).row();
        textCol.add(label("[gray]Weight: "+String.format(java.util.Locale.ROOT,"%.1f",item.carryingWeight())+"[]")).row();
        if(!itemSystem.isItemResearched(player,item))textCol.add(label("[scarlet]Requires item research[]")).row();
        if (item.description != null && !item.description.isEmpty()) {
            textCol.add(
                label("[lightgray]" + item.description + "[]")
                    .wrap()
                    .labelAlign("left")
                    .width(LEFT_TOTAL_W - 266f)
                    .padTop(2f)
            );
        }
        row.add(textCol.growX());

        // Right: Cost breakdown + Craft & Close buttons
        TableBuilder rightCtrl = table().align("right");
        TableBuilder costRow = table().wrap(true).align("right").padBottom(4f);
        if (!item.craftable) {
            costRow.add(label("[gray]Decrafts into:[] ").padRight(3f));
            for (ResourceCost rc : item.resourceCosts) {
                costRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(16f).padRight(2f));
                costRow.add(label("[lightgray]" + formatCompact(rc.amount()) + "[]").padRight(6f));
            }
        } else {
            for (ResourceCost rc : item.resourceCosts) {
                Item mItem = rc.resolveItem();
                int have = mItem != null ? mapRes.get(mItem, 0) : 0;
                String col = have >= rc.amount() ? "[#d8f8d8]" : "[scarlet]";
                costRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(16f).padRight(2f));
                costRow.add(label(col + formatCompact(rc.amount()) + "[]").padRight(6f));
            }
            for (ItemIngredient ing : item.itemCosts) {
                ShopItemDef sub = cat.findItem(ing.shopItemId());
                int have = itemSystem.countOwnedTotal(eq, ing.shopItemId());
                String col = have >= ing.amount() ? "[#ffd37f]" : "[scarlet]";
                String subDraw = sub != null ? sub.uiDrawable() : "item-copper-ui";
                costRow.add(image(subDraw).placeholder("item-copper-ui").scaling(Scaling.fit).size(17f).padRight(2f));
                costRow.add(label(col + "x" + ing.amount() + "[]").padRight(6f));
            }
        }
        rightCtrl.add(costRow).row();

        TableBuilder btnRow = table().align("right");
        if (craftableHere) {
            String craftCol = affordable ? "[stat]" : "[gray]";
            btnRow.add(
                buttonTable()
                    .style("defaultt")
                    .margin(4f)
                    .disabled(!affordable)
                    .clicked("craft:" + item.id)
                    .add(label(craftCol + "Craft[]"))
                    .width(96f)
                    .height(28f)
                    .padRight(6f)
            );
        }

        btnRow.add(
            buttonTable()
                .style("defaultt")
                .margin(4f)
                .clicked("select:close")
                .add(label("[lightgray]X[]"))
                .width(32f)
                .height(28f)
        );
        rightCtrl.add(btnRow);

        row.add(rightCtrl.padLeft(8f));
        box.add(row.growX());
        return box;
    }

    private boolean isItemVisibleInShop(@Nullable ShopItemDef item, EquipmentData eq) {
        if (item == null) return false;
        if (item.craftable) return true;
        return itemSystem.countOwnedTotal(eq, item.id) > 0;
    }

    private void clearUnownedSalvageSelection(Player player, Session s) {
        if (s.selectedShopItemId == null || s.selectedShopItemId.isEmpty()) return;
        ShopItemDef selected = itemSystem.getCatalog().findItem(s.selectedShopItemId);
        if (selected == null || !isItemVisibleInShop(selected, itemSystem.getEquipment(player))) {
            s.selectedShopItemId = "";
        }
    }

    private Seq<ShopItemDef> filterItems(
        Seq<ShopItemDef> items,
        EquipmentData eq,
        String searchQuery,
        Catalog cat
    ) {
        boolean hasSearch = searchQuery != null && !searchQuery.isBlank();
        return items.select(i -> isItemVisibleInShop(i, eq) && (!hasSearch || i.matchesSearch(searchQuery, cat)));
    }

    private static TableBuilder tierDivider(String title, String hex) {
        float w = ICON_CELL_W * 2f + 6f;
        TableBuilder div = table().width(w).padBottom(3f);
        div.add(image("whiteui").color(hex + "66").height(2f).growX().padRight(4f));
        div.add(label("[#" + hex + "]" + title + "[]"));
        div.add(image("whiteui").color(hex + "66").height(2f).growX().padLeft(4f));
        return div;
    }

    private TableBuilder buildOrbitalShipsPanel(Player player, String searchQuery) {
        Catalog cat = itemSystem.getCatalog();
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        ObjectIntMap<String> orbital = database.getOrbitalStorage(acc.id, factionId);

        TableBuilder list = table().align("topLeft").margin(4f);
        float shipCardW = (LEFT_TOTAL_W - 28f) / 2f;
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);

        int shown = 0;
        for (int i = 0; i < cat.trades.size; i++) {
            OrbitalTradeDef tr = cat.trades.get(i);
            if (!q.isEmpty()) {
                boolean match = tr.shipName.toLowerCase(Locale.ROOT).contains(q)
                    || tr.title.toLowerCase(Locale.ROOT).contains(q)
                    || tr.description.toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
            }

            boolean canPay = true;
            for (ResourceCost rc : tr.orbitalCosts) {
                if (orbital.get(rc.itemName(), 0) < rc.amount()) {
                    canPay = false;
                    break;
                }
            }

            ButtonTableBuilder card = buttonTable()
                .style("defaultt")
                .margin(7f)
                .width(shipCardW)
                .align("left")
                .clicked("trade:" + tr.id);

            card.add(
                label("[#7fe6ff]" + tr.shipName + "[] [gray]-[] [white]" + tr.title + "[]")
                    .wrap()
                    .labelAlign("left")
                    .width(shipCardW - 18f)
            ).row();

            TableBuilder exRow = table().wrap(true).align("left").padTop(4f);
            for (ResourceCost rc : tr.orbitalCosts) {
                int have = orbital.get(rc.itemName(), 0);
                String col = have >= rc.amount() ? "[#7fe6ff]" : "[scarlet]";
                exRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(15f).padRight(2f));
                exRow.add(label(col + formatCompact(rc.amount()) + "[]").padRight(6f));
            }
            exRow.add(label("[gray]->[]").padRight(6f));

            if (tr.rewardShopItemId != null) {
                ShopItemDef rew = cat.findItem(tr.rewardShopItemId);
                if (rew != null) {
                    exRow.add(image(rew.uiDrawable()).placeholder("item-copper-ui").scaling(Scaling.fit).size(18f).padRight(4f));
                    exRow.add(label((canPay ? "[stat]" : "[lightgray]") + rew.name + "[]"));
                }
            }
            for (ResourceCost rc : tr.rewardMapResources) {
                exRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(16f).padRight(2f));
                exRow.add(label("[stat]+" + formatCompact(rc.amount()) + "[]").padRight(5f));
            }

            card.add(exRow.growX());
            list.add(card.padRight((shown % 2 == 0) ? 8f : 0f).padBottom(6f));
            if (shown % 2 == 1) list.row();
            shown++;
        }
        list.add(space().growY());

        return table().add(
            pane()
                .style("defaultPane")
                .width(LEFT_TOTAL_W)
                .height(CAT_SCROLL_H_FULL + 26f)
                .add(list.growY().align("topLeft"))
        );
    }

    // ------------------------------------------------------------------ 4. Right Drawer via Reusable PlayerInventoryDrawer

    private TableBuilder buildRightDrawer(Player player, Session s) {
        boolean allowDecraft = (s.station != StationTier.CURSED_ALTAR);
        return inventoryDrawer.build(
            player,
            s.selectedInvSlot,
            DrawerTheme.forStation(s.station),
            allowDecraft
        );
    }

    private boolean canAfford(ShopItemDef item, EquipmentData eq, ObjectIntMap<Item> mapRes) {
        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            if (mItem == null || mapRes.get(mItem, 0) < rc.amount()) return false;
        }
        for (ItemIngredient ing : item.itemCosts) {
            if (itemSystem.countOwnedTotal(eq, ing.shopItemId()) < ing.amount()) return false;
        }
        return true;
    }

    private static String formatCompact(int n) {
        if (n >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return Integer.toString(n);
    }
}
