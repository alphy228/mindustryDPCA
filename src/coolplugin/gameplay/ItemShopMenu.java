package coolplugin.gameplay;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Scaling;
import arc.util.Strings;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.PlayerItemSystem.ActionOutcome;
import coolplugin.gameplay.PlayerItemSystem.ComputedStats;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.ItemIngredient;
import coolplugin.gameplay.ShopItemDef.OrbitalTradeDef;
import coolplugin.gameplay.ShopItemDef.ResourceCost;
import coolplugin.gameplay.ShopItemDef.StationTier;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuBuilder;
import mindustry.ui.builder.UiBuilder.ButtonBuilder;
import mindustry.ui.builder.UiBuilder.ButtonTableBuilder;
import mindustry.ui.builder.UiBuilder.ImageBuilder;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;

import static mindustry.ui.builder.UiBuilder.*;

/**
 * Rich server-side {@link MenuBuilder} interfaces for {@link StationTier#WORKBENCH},
 * {@link StationTier#BAZAAR}, and {@link StationTier#CURSED_ALTAR}.
 */
public class ItemShopMenu {

    public static final String ID_DYNAMIC_BODY = "coolplugin-shop-body";

    public static final String TAB_CRAFT = "craft";
    public static final String TAB_CRAFT_T2 = "craft2";
    public static final String TAB_DECRAFT = "decraft";
    public static final String TAB_SHIPS = "ships";
    public static final String TAB_BAG = "bag";

    private static final float DIALOG_WIDTH = 836f;
    private static final float CARD_WIDTH = 392f;
    private static final float SCROLL_HEIGHT = 330f;

    public static final class ShopSession {
        public StationTier station = StationTier.WORKBENCH;
        public String tab = TAB_CRAFT;
        public String statusBanner = "";
    }

    private final PlayerItemSystem itemSystem;
    private final BuildSystem buildSystem;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final ObjectMap<String, ShopSession> sessions = new ObjectMap<>();
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
    }

    public void register() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder((player, result) -> {
            if (player == null || result == null || result.wasCancelled() || result.result == null) return;
            handleAction(player, result.result);
        });
    }

    public void open(Player player, StationTier station) {
        if (player == null || player.con == null || !player.con.isConnected() || station == null) return;
        register();
        UiSpriteSync.ensureSent(player);

        ShopSession session = getOrCreateSession(player);
        session.station = station;
        session.tab = (station == StationTier.BAZAAR) ? TAB_CRAFT_T2 : TAB_CRAFT;
        session.statusBanner = "";

        TableBuilder root = buildFullDialog(player, session);
        MenuBuilder.of(root)
            .id(menuId)
            .title(null)
            .hideOnClick(false)
            .hideExisting(true)
            .fillScreen(false)
            .show(player);
    }

    public NodeBuilder<?> buildRootForTest(Player player, StationTier station, String tab) {
        ShopSession session = getOrCreateSession(player);
        session.station = station;
        session.tab = tab;
        session.statusBanner = "";
        return buildFullDialog(player, session);
    }

    private ShopSession getOrCreateSession(Player player) {
        ShopSession s = sessions.get(player.uuid());
        if (s == null) {
            s = new ShopSession();
            sessions.put(player.uuid(), s);
        }
        return s;
    }

    private void handleAction(Player player, String action) {
        if ("close".equals(action)) {
            if (player.con != null) {
                Call.hideFollowUpMenu(player.con, menuId);
            }
            return;
        }

        ShopSession session = getOrCreateSession(player);

        if (action.startsWith("tab:")) {
            String nextTab = action.substring("tab:".length());
            if (session.station == StationTier.CURSED_ALTAR
                && (TAB_DECRAFT.equals(nextTab) || TAB_SHIPS.equals(nextTab) || TAB_CRAFT_T2.equals(nextTab))) {
                nextTab = TAB_CRAFT;
            }
            session.tab = nextTab;
            session.statusBanner = "";
            pushBodyUpdate(player, session);
            return;
        }

        if (action.startsWith("craft:")) {
            String itemId = action.substring("craft:".length());
            ActionOutcome out = itemSystem.craft(player, itemId, session.station);
            session.statusBanner = out.message();
            pushBodyUpdate(player, session);
            return;
        }

        if (action.startsWith("decraft:")) {
            String itemId = action.substring("decraft:".length());
            ActionOutcome out = itemSystem.decraft(player, itemId, session.station);
            session.statusBanner = out.message();
            pushBodyUpdate(player, session);
            return;
        }

        if (action.startsWith("ship:")) {
            if (session.station != StationTier.BAZAAR) return;
            String tradeId = action.substring("ship:".length());
            ActionOutcome out = itemSystem.executeOrbitalTrade(player, tradeId);
            session.statusBanner = out.message();
            pushBodyUpdate(player, session);
            return;
        }

        if (action.startsWith("equip:")) {
            String itemId = action.substring("equip:".length());
            ActionOutcome out = itemSystem.equipFromBag(player, itemId);
            session.statusBanner = out.message();
            pushBodyUpdate(player, session);
            return;
        }

        if (action.startsWith("unequip:")) {
            try {
                int slotIdx = Integer.parseInt(action.substring("unequip:".length()));
                ActionOutcome out = itemSystem.unequipSlot(player, slotIdx);
                session.statusBanner = out.message();
                pushBodyUpdate(player, session);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void pushBodyUpdate(Player player, ShopSession session) {
        if (player.con == null || !player.con.isConnected()) return;
        Call.menuBuilderUpdate(player.con, menuId, ID_DYNAMIC_BODY, buildDynamicBody(player, session));
    }

    private TableBuilder buildFullDialog(Player player, ShopSession session) {
        TableBuilder outer = table().background("pane").margin(0f).width(DIALOG_WIDTH);
        TableBuilder dynamicHolder = table().id(ID_DYNAMIC_BODY).growX();
        dynamicHolder.add(buildDynamicBody(player, session));
        outer.add(dynamicHolder.growX());
        return outer;
    }

    private TableBuilder buildDynamicBody(Player player, ShopSession session) {
        if (session.station == StationTier.CURSED_ALTAR) {
            return buildCursedAltarBody(player, session);
        }
        return buildStandardShopBody(player, session);
    }

    // =========================================================================
    // WORKBENCH & BAZAAR INTERFACE
    // =========================================================================

    private TableBuilder buildStandardShopBody(Player player, ShopSession session) {
        StationTier st = session.station;
        String accent = st.accentHex + "ff";
        EquipmentData eq = itemSystem.getEquipment(player);
        ComputedStats stats = itemSystem.computeStats(eq.equipped);

        TableBuilder body = table().margin(0f).width(DIALOG_WIDTH);

        // 1. Top glowing trim
        body.add(image("whiteui").color(accent).height(4f).growX()).row();

        // 2. Station Title Bar + Close Button
        TableBuilder topBar = table().margin(10f).align("left");
        ImageBuilder stationIcon = image(UiSpriteSync.resolveMenuDrawable(st.blockName))
            .placeholder("block-container-ui")
            .scaling(Scaling.fit)
            .size(34f)
            .padRight(10f);
        topBar.add(stationIcon);

        TableBuilder titleCol = table().align("left");
        String tierBadge = (st == StationTier.WORKBENCH) ? "TIER I // FIELD ARTIFICER" : "TIER II // ORBITAL EXCHANGE";
        titleCol.add(label("[#" + st.accentHex + "]" + st.title.toUpperCase() + "[]  [gray]" + tierBadge + "[]").labelAlign("left")).row();
        String subHint = (st == StationTier.WORKBENCH)
            ? "[lightgray]Uses nearby map containers. Excess decrafted items vanish if storage is full.[]"
            : "[lightgray]Map crafting/decrafting + Orbital Merchant Ship exchange via Launch Pad reserves.[]";
        titleCol.add(label(subHint).labelAlign("left"));
        topBar.add(titleCol.growX());

        topBar.add(
            button("[scarlet]Close[]")
                .style("defaultt")
                .clicked("close")
                .width(86f)
                .height(36f)
        );
        body.add(topBar.growX()).row();

        body.add(image("whiteui").color("383a48ff").height(2f).growX()).row();

        // 3. Resource Readout Strip (Map Reach Storage + Orbital Storage on Bazaar)
        body.add(buildResourceReadoutStrip(player, st == StationTier.BAZAAR).growX()).row();
        body.add(image("whiteui").color("303240ff").height(2f).growX()).row();

        // 4. 10-Slot Loadout Rack + Active Passive / Overburden Readout
        body.add(buildTenSlotLoadoutRack(eq, stats, st.accentHex).growX()).row();

        // 5. Status feedback banner (if any action just ran)
        if (session.statusBanner != null && !session.statusBanner.isEmpty()) {
            TableBuilder banner = table().background("button").margin(6f);
            banner.add(label(session.statusBanner).wrap().labelAlign("center").width(DIALOG_WIDTH - 36f));
            body.add(banner.growX().padLeft(10f).padRight(10f).padTop(4f)).row();
        }

        // 6. Navigation Tabs
        TableBuilder tabs = table().margin(6f);
        if (st == StationTier.BAZAAR) {
            tabs.add(buildTabBtn("Bazaar Crafts", TAB_CRAFT_T2, session.tab, st.accentHex, 148f).padRight(5f));
            tabs.add(buildTabBtn("Basic Crafts", TAB_CRAFT, session.tab, "ffd37f", 142f).padRight(5f));
            tabs.add(buildTabBtn("Decraft", TAB_DECRAFT, session.tab, st.accentHex, 118f).padRight(5f));
            tabs.add(buildTabBtn("Orbital Ships", TAB_SHIPS, session.tab, "7fe6ff", 148f).padRight(5f));
            int bagCount = PlayerItemSystem.totalBagCount(eq);
            tabs.add(buildTabBtn("Bag (" + bagCount + ")", TAB_BAG, session.tab, st.accentHex, 118f));
        } else {
            tabs.add(buildTabBtn("Craft", TAB_CRAFT, session.tab, st.accentHex, 184f).padRight(6f));
            tabs.add(buildTabBtn("Decraft", TAB_DECRAFT, session.tab, st.accentHex, 184f).padRight(6f));
            int bagCount = PlayerItemSystem.totalBagCount(eq);
            tabs.add(buildTabBtn("Bag (" + bagCount + ")", TAB_BAG, session.tab, st.accentHex, 184f));
        }
        body.add(tabs.growX()).row();

        // 7. Scrollable Content Pane
        TableBuilder scrollInner = switch (session.tab) {
            case TAB_DECRAFT -> buildDecraftGrid(player, eq, session.station);
            case TAB_SHIPS -> buildOrbitalShipsGrid(player);
            case TAB_BAG -> buildBagManagementGrid(eq, st.accentHex);
            case TAB_CRAFT_T2 -> buildCraftGrid(player, eq, StationTier.BAZAAR, StationTier.BAZAAR, st.accentHex);
            default -> buildCraftGrid(player, eq, StationTier.WORKBENCH, session.station, st.accentHex);
        };

        PaneBuilder scroll = pane()
            .style("defaultPane")
            .width(DIALOG_WIDTH - 16f)
            .height(SCROLL_HEIGHT)
            .add(scrollInner);
        body.add(scroll.padBottom(8f));

        return body;
    }

    // =========================================================================
    // CURSED ALTAR — DEDICATED ABYSSAL CRAFT MENU (NO BASIC CRAFTS / NO TRADES)
    // =========================================================================

    private TableBuilder buildCursedAltarBody(Player player, ShopSession session) {
        EquipmentData eq = itemSystem.getEquipment(player);
        ComputedStats stats = itemSystem.computeStats(eq.equipped);

        TableBuilder body = table().margin(0f).width(DIALOG_WIDTH);

        // Twin crimson + violet eldritch trim
        body.add(image("whiteui").color("ff3b6bff").height(3f).growX()).row();
        body.add(image("whiteui").color("9d4eddff").height(2f).growX()).row();

        TableBuilder header = table().margin(12f).align("left");
        header.add(
            image(UiSpriteSync.resolveMenuDrawable("dp-cursed-altar"))
                .placeholder("block-thorium-reactor-ui")
                .scaling(Scaling.fit)
                .size(40f)
                .padRight(12f)
        );

        TableBuilder titleCol = table().align("left");
        titleCol.add(label("[#ff3b6b]* CURSED ALTAR[]  [#c77dff]// ABYSSAL SANCTUM[]").labelAlign("left")).row();
        titleCol.add(label("[#b8a2c8]No mortal crafts or orbital merchants reach here. Bind forbidden relics at your peril.[]")
            .labelAlign("left"));
        header.add(titleCol.growX());

        header.add(
            button("[#ff4b6e]Leave Altar[]")
                .style("defaultt")
                .clicked("close")
                .width(114f)
                .height(36f)
        );
        body.add(header.growX()).row();

        body.add(image("whiteui").color("5a1e38ff").height(2f).growX()).row();
        body.add(buildResourceReadoutStrip(player, false).growX()).row();
        body.add(image("whiteui").color("421834ff").height(2f).growX()).row();

        // Soul-binding 10-slot rack
        body.add(buildTenSlotLoadoutRack(eq, stats, "ff4b6e").growX()).row();

        if (session.statusBanner != null && !session.statusBanner.isEmpty()) {
            TableBuilder banner = table().background("button").margin(6f);
            banner.add(label(session.statusBanner).wrap().labelAlign("center").width(DIALOG_WIDTH - 36f));
            body.add(banner.growX().padLeft(10f).padRight(10f).padTop(4f)).row();
        }

        // Only Cursed Rituals & Soul Bag tabs (no basic crafts, no trades)
        TableBuilder tabs = table().margin(6f);
        tabs.add(buildTabBtn("Forbidden Crafts (15)", TAB_CRAFT, session.tab, "ff4b6e", 230f).padRight(8f));
        tabs.add(buildTabBtn("Bound Relics & Bag (" + PlayerItemSystem.totalBagCount(eq) + ")", TAB_BAG, session.tab, "c77dff", 240f));
        body.add(tabs.growX()).row();

        TableBuilder scrollInner = TAB_BAG.equals(session.tab)
            ? buildBagManagementGrid(eq, "ff4b6e")
            : buildCraftGrid(player, eq, StationTier.CURSED_ALTAR, StationTier.CURSED_ALTAR, "ff4b6e");

        PaneBuilder scroll = pane()
            .style("defaultPane")
            .width(DIALOG_WIDTH - 16f)
            .height(SCROLL_HEIGHT)
            .add(scrollInner);
        body.add(scroll.padBottom(8f));

        return body;
    }

    // =========================================================================
    // SHARED WIDGETS: 10-SLOT RACK, RESOURCE STRIP, CRAFT/DECRAFT/SHIP CARDS
    // =========================================================================

    private TableBuilder buildResourceReadoutStrip(Player player, boolean includeOrbital) {
        TableBuilder wrap = table().margin(6f).align("left");

        // Map Reach Storage row
        TableBuilder mapRow = table().wrap(true).align("left");
        mapRow.add(label("[#ffd37f]Map Storage:[] ").padRight(6f));
        ObjectIntMap<Item> mapRes = buildSystem.getAvailableSnapshot(player);
        boolean anyMap = false;
        if (Vars.content != null) {
            for (Item it : Vars.content.items()) {
                int amt = mapRes.get(it, 0);
                if (amt <= 0) continue;
                anyMap = true;
                mapRow.add(image("item-" + it.name + "-ui").scaling(Scaling.fit).size(16f).padRight(2f));
                mapRow.add(label("[white]" + fmtAmt(amt) + "[]").padRight(8f));
            }
        }
        if (!anyMap) {
            mapRow.add(label("[gray]Empty (place items in unit or nearby containers)[]"));
        }
        wrap.add(mapRow.growX()).row();

        // Orbital Storage row (shown on Bazaar)
        if (includeOrbital) {
            AccountRecord acc = sessionManager.getOrInitSession(player);
            String factionId = sessionManager.getActiveFactionId(player.uuid());
            ObjectIntMap<String> orb = database.getOrbitalStorage(acc.id, factionId);

            TableBuilder orbRow = table().wrap(true).align("left").padTop(3f);
            orbRow.add(label("[#7fe6ff]Orbital Storage:[] ").padRight(6f));
            boolean anyOrb = false;
            if (Vars.content != null) {
                for (Item it : Vars.content.items()) {
                    int amt = orb.get(it.name, 0);
                    if (amt <= 0) continue;
                    anyOrb = true;
                    orbRow.add(image("item-" + it.name + "-ui").scaling(Scaling.fit).size(16f).padRight(2f));
                    orbRow.add(label("[#b8f4ff]" + fmtAmt(amt) + "[]").padRight(8f));
                }
            }
            if (!anyOrb) {
                orbRow.add(label("[gray]0 (launch resources via your owned Launch Pad)[]"));
            }
            wrap.add(orbRow.growX());
        }

        return wrap;
    }

    private TableBuilder buildTenSlotLoadoutRack(EquipmentData eq, ComputedStats stats, String accentHex) {
        Catalog cat = itemSystem.getCatalog();
        TableBuilder rack = table().margin(8f);

        TableBuilder labelsRow = table();
        labelsRow.add(label("[stat]ACTIVE SLOTS (1-4: Full Passive Bonuses)[]").padRight(26f));
        labelsRow.add(label("[#ff6b6b]OVERBURDEN SLOTS (5-10: No Buffs, -10% Speed Each)[]"));
        rack.add(labelsRow).row();

        TableBuilder slotsRow = table().padTop(4f).padBottom(4f);
        for (int i = 0; i < PlayerItemSystem.MAX_EQUIPPED_SLOTS; i++) {
            boolean activeSlot = i < PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            boolean occupied = i < eq.equipped.size;
            ShopItemDef def = occupied ? cat.findItem(eq.equipped.get(i)) : null;

            ButtonTableBuilder slotBtn = buttonTable()
                .style(occupied ? "nodei" : "defaulti")
                .size(46f);

            if (occupied) {
                slotBtn.clicked("unequip:" + i);
                slotBtn.color(activeSlot ? (accentHex + "ff") : "ff5c5cff");
                ImageBuilder ic = image(def != null ? def.uiDrawable() : "item-copper-ui")
                    .scaling(Scaling.fit)
                    .size(26f);
                if (def != null) {
                    ic.color((activeSlot ? def.colorHex : "ff8787") + "ff");
                }
                slotBtn.add(ic);
            } else {
                slotBtn.disabled(true);
                slotBtn.color(activeSlot ? "607868aa" : "684848aa");
                slotBtn.add(label(activeSlot ? ("[gray]" + (i + 1) + "[]") : ("[#7a5252]" + (i + 1) + "[]")));
            }

            if (i == PlayerItemSystem.ACTIVE_PASSIVE_SLOTS - 1) {
                slotsRow.add(slotBtn.padRight(18f));
            } else if (i < PlayerItemSystem.MAX_EQUIPPED_SLOTS - 1) {
                slotsRow.add(slotBtn.padRight(5f));
            } else {
                slotsRow.add(slotBtn);
            }
        }
        rack.add(slotsRow).row();

        // Live computed multiplier summary
        String spdColor = stats.overloadCount > 0 ? "[#ff6b6b]" : "[white]";
        String overloadTag = stats.overloadCount > 0
            ? (" [#ff6b6b](-" + (stats.overloadCount * 10) + "% Overburden)[]")
            : "";
        String statLine = "[lightgray]HP[] [white]x" + Strings.autoFixed(stats.healthMult, 2)
            + "[]   [lightgray]DMG[] [white]x" + Strings.autoFixed(stats.damageMult, 2)
            + "[]   [lightgray]SPD[] " + spdColor + "x" + Strings.autoFixed(stats.speedMult, 2) + "[]" + overloadTag
            + "   [lightgray]RLD[] [white]x" + Strings.autoFixed(stats.reloadMult, 2)
            + "[]   [lightgray]BLD[] [white]x" + Strings.autoFixed(stats.buildSpeedMult, 2)
            + "[]   [lightgray]ARM[] [white]+" + Math.round(stats.armorBonus) + "[]";
        rack.add(label(statLine).labelAlign("center"));

        return rack;
    }

    private ButtonBuilder buildTabBtn(String title, String tabId, String currentTab, String accentHex, float width) {
        boolean selected = tabId.equals(currentTab);
        String text = selected ? ("[#" + accentHex + "]>> " + title + "[]") : ("[lightgray]" + title + "[]");
        return button(text)
            .style("defaultt")
            .clicked("tab:" + tabId)
            .width(width)
            .height(36f);
    }

    private TableBuilder buildCraftGrid(
        Player player,
        EquipmentData eq,
        StationTier filterTier,
        StationTier station,
        String accentHex
    ) {
        Catalog cat = itemSystem.getCatalog();
        Seq<ShopItemDef> availableCrafts = cat.items.select(it -> it.tier == filterTier);

        TableBuilder grid = table().align("topLeft").margin(4f);
        int col = 0;
        for (int i = 0; i < availableCrafts.size; i++) {
            ShopItemDef item = availableCrafts.get(i);
            TableBuilder card = buildCraftCard(player, eq, item, station, accentHex);
            if (col == 0) {
                grid.add(card.width(CARD_WIDTH).padRight(10f).padBottom(8f));
                col = 1;
            } else {
                grid.add(card.width(CARD_WIDTH).padBottom(8f)).row();
                col = 0;
            }
        }
        if (col == 1) grid.row();
        return grid;
    }

    private TableBuilder buildCraftCard(
        Player player,
        EquipmentData eq,
        ShopItemDef item,
        StationTier station,
        String accentHex
    ) {
        Catalog cat = itemSystem.getCatalog();
        boolean canCraft = item.craftable && itemSystem.canAffordCraft(player, item, eq);
        int ownedCount = itemSystem.countOwnedTotal(eq, item.id);

        TableBuilder card = table().background("button").margin(0f);
        card.add(image("whiteui").color(item.colorHex + "ff").height(3f).growX()).row();

        TableBuilder inner = table().margin(8f).align("topLeft");

        // Header: icon + name + attach mode badge + owned badge
        TableBuilder head = table().align("left");
        head.add(
            image(item.uiDrawable())
                .color(item.colorHex + "ff")
                .scaling(Scaling.fit)
                .size(24f)
                .padRight(7f)
        );
        String ownedStr = ownedCount > 0 ? (" [gray](x" + ownedCount + ")[]") : "";
        head.add(label("[#" + item.colorHex + "]" + item.name + "[]" + ownedStr).labelAlign("left").growX());
        head.add(label("[#9aa0b8][" + item.attachMode.label + "][]"));
        inner.add(head.growX()).row();

        // Passive bonuses
        inner.add(label(item.formatPassiveSummary()).wrap().labelAlign("left").width(CARD_WIDTH - 20f).padTop(3f)).row();

        // Description
        if (item.description != null && !item.description.isEmpty()) {
            inner.add(label("[lightgray]" + item.description + "[]").wrap().labelAlign("left").width(CARD_WIDTH - 20f).padTop(2f)).row();
        }

        // Recipe ingredients (Map resources + "items")
        TableBuilder reqRow = table().wrap(true).align("left").padTop(5f).padBottom(5f);
        for (ResourceCost rc : item.resourceCosts) {
            Item mItem = rc.resolveItem();
            int have = mItem != null ? buildSystem.getAvailableAmount(player, mItem) : 0;
            String col = have >= rc.amount() ? "[stat]" : "[scarlet]";
            reqRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(15f).padRight(2f));
            reqRow.add(label(col + rc.amount() + "[]").padRight(7f));
        }
        for (ItemIngredient ing : item.itemCosts) {
            ShopItemDef sub = cat.findItem(ing.shopItemId());
            int have = itemSystem.countOwnedTotal(eq, ing.shopItemId());
            String col = have >= ing.amount() ? "[#ffd37f]" : "[scarlet]";
            String subName = sub != null ? sub.name : ing.shopItemId();
            reqRow.add(label(col + "[" + ing.amount() + "x " + subName + "][]").padRight(6f));
        }
        inner.add(reqRow.growX()).row();

        if (item.craftable) {
            String btnText = (station == StationTier.CURSED_ALTAR)
                ? (canCraft ? "[#" + accentHex + "]Invoke Ritual[]" : "[gray]Missing Offering[]")
                : (canCraft ? "[#" + accentHex + "]Craft[]" : "[gray]Insufficient Materials[]");
            inner.add(
                button(btnText)
                    .style("defaultt")
                    .disabled(!canCraft)
                    .clicked("craft:" + item.id)
                    .height(34f)
                    .growX()
            );
        } else {
            inner.add(
                button("[#ffbe76]Non-Craftable Relic (Decraftable)[]")
                    .style("defaultt")
                    .disabled(true)
                    .height(34f)
                    .growX()
            );
        }

        card.add(inner.growX());
        return card;
    }

    private TableBuilder buildDecraftGrid(Player player, EquipmentData eq, StationTier station) {
        Catalog cat = itemSystem.getCatalog();
        Seq<ShopItemDef> ownedDecraftables = new Seq<>();
        for (ShopItemDef item : cat.items) {
            if (item.decraftable && item.hasRecipe() && itemSystem.countOwnedTotal(eq, item.id) > 0) {
                ownedDecraftables.add(item);
            }
        }

        TableBuilder grid = table().align("topLeft").margin(4f);
        if (ownedDecraftables.isEmpty()) {
            grid.add(
                label("[lightgray]You currently hold no decraftable items.\nCraft items or trade with Orbital Ships (salvage relics can also be decrafted here!).[]")
                    .wrap()
                    .labelAlign("center")
                    .width(DIALOG_WIDTH - 48f)
                    .padTop(40f)
            );
            return grid;
        }

        int col = 0;
        for (int i = 0; i < ownedDecraftables.size; i++) {
            ShopItemDef item = ownedDecraftables.get(i);
            int count = itemSystem.countOwnedTotal(eq, item.id);

            TableBuilder card = table().background("button").margin(0f);
            card.add(image("whiteui").color(item.colorHex + "ff").height(3f).growX()).row();

            TableBuilder inner = table().margin(8f).align("topLeft");
            TableBuilder head = table().align("left");
            head.add(image(item.uiDrawable()).color(item.colorHex + "ff").scaling(Scaling.fit).size(22f).padRight(6f));
            head.add(label("[#" + item.colorHex + "]" + item.name + "[] [white](Owned: " + count + ")[]").labelAlign("left").growX());
            inner.add(head.growX()).row();

            TableBuilder outRow = table().wrap(true).align("left").padTop(5f).padBottom(6f);
            outRow.add(label("[lightgray]Yields to Map Storage:[] ").padRight(5f));
            for (ResourceCost rc : item.resourceCosts) {
                outRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(15f).padRight(2f));
                outRow.add(label("[stat]+" + rc.amount() + "[]").padRight(7f));
            }
            for (ItemIngredient ing : item.itemCosts) {
                ShopItemDef sub = cat.findItem(ing.shopItemId());
                outRow.add(label("[#ffd37f]+" + ing.amount() + "x " + (sub != null ? sub.name : ing.shopItemId()) + "[]").padRight(6f));
            }
            inner.add(outRow.growX()).row();

            inner.add(
                button("[#ffbe76]Decraft 1x -> Map Storage[]")
                    .style("defaultt")
                    .clicked("decraft:" + item.id)
                    .height(34f)
                    .growX()
            );
            card.add(inner.growX());

            if (col == 0) {
                grid.add(card.width(CARD_WIDTH).padRight(10f).padBottom(8f));
                col = 1;
            } else {
                grid.add(card.width(CARD_WIDTH).padBottom(8f)).row();
                col = 0;
            }
        }
        if (col == 1) grid.row();
        return grid;
    }

    private TableBuilder buildOrbitalShipsGrid(Player player) {
        Catalog cat = itemSystem.getCatalog();
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        ObjectIntMap<String> orb = database.getOrbitalStorage(acc.id, factionId);

        TableBuilder grid = table().align("topLeft").margin(4f);
        int col = 0;
        for (int i = 0; i < cat.trades.size; i++) {
            OrbitalTradeDef tr = cat.trades.get(i);
            boolean canBuy = itemSystem.canAffordOrbitalTrade(player, tr);

            TableBuilder card = table().background("button").margin(0f);
            card.add(image("whiteui").color("7fe6ffff").height(3f).growX()).row();

            TableBuilder inner = table().margin(8f).align("topLeft");
            inner.add(label("[#7fe6ff][" + tr.shipName + "][]  [white]" + tr.title + "[]").labelAlign("left")).row();
            inner.add(label("[lightgray]" + tr.description + "[]").wrap().labelAlign("left").width(CARD_WIDTH - 20f).padTop(3f)).row();

            TableBuilder costRow = table().wrap(true).align("left").padTop(5f).padBottom(6f);
            costRow.add(label("[#7fe6ff]Orbit Cost:[] ").padRight(5f));
            for (ResourceCost rc : tr.orbitalCosts) {
                int have = orb.get(rc.itemName(), 0);
                String c = have >= rc.amount() ? "[stat]" : "[scarlet]";
                costRow.add(image("item-" + rc.itemName() + "-ui").scaling(Scaling.fit).size(15f).padRight(2f));
                costRow.add(label(c + rc.amount() + "[]").padRight(7f));
            }
            inner.add(costRow.growX()).row();

            inner.add(
                button(canBuy ? "[#7fe6ff]Trade with Ship[]" : "[gray]Need Orbital Cargo[]")
                    .style("defaultt")
                    .disabled(!canBuy)
                    .clicked("ship:" + tr.id)
                    .height(34f)
                    .growX()
            );
            card.add(inner.growX());

            if (col == 0) {
                grid.add(card.width(CARD_WIDTH).padRight(10f).padBottom(8f));
                col = 1;
            } else {
                grid.add(card.width(CARD_WIDTH).padBottom(8f)).row();
                col = 0;
            }
        }
        if (col == 1) grid.row();
        return grid;
    }

    private TableBuilder buildBagManagementGrid(EquipmentData eq, String accentHex) {
        Catalog cat = itemSystem.getCatalog();
        TableBuilder grid = table().align("topLeft").margin(4f);

        Seq<ShopItemDef> bagItems = new Seq<>();
        for (ShopItemDef def : cat.items) {
            if (eq.stash.get(def.id, 0) > 0) {
                bagItems.add(def);
            }
        }

        if (bagItems.isEmpty()) {
            grid.add(
                label("[lightgray]Your unequipped Bag is empty.\nClick any occupied slot in the 10-slot rack above to unequip an item, or craft new gear![]")
                    .wrap()
                    .labelAlign("center")
                    .width(DIALOG_WIDTH - 48f)
                    .padTop(40f)
            );
            return grid;
        }

        boolean canEquipMore = eq.equipped.size < PlayerItemSystem.MAX_EQUIPPED_SLOTS;
        boolean nextOverloaded = eq.equipped.size >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;

        int col = 0;
        for (int i = 0; i < bagItems.size; i++) {
            ShopItemDef item = bagItems.get(i);
            int count = eq.stash.get(item.id, 0);

            TableBuilder card = table().background("button").margin(0f);
            card.add(image("whiteui").color(item.colorHex + "ff").height(3f).growX()).row();

            TableBuilder inner = table().margin(8f).align("topLeft");
            TableBuilder head = table().align("left");
            head.add(image(item.uiDrawable()).color(item.colorHex + "ff").scaling(Scaling.fit).size(22f).padRight(6f));
            head.add(label("[#" + item.colorHex + "]" + item.name + "[] [white]x" + count + "[]").labelAlign("left").growX());
            head.add(label("[#9aa0b8][" + item.attachMode.label + "][]"));
            inner.add(head.growX()).row();

            inner.add(label(item.formatPassiveSummary()).wrap().labelAlign("left").width(CARD_WIDTH - 20f).padTop(3f).padBottom(6f)).row();

            String btnLabel = !canEquipMore
                ? "[gray]10/10 Slots Full[]"
                : (nextOverloaded
                    ? "[#ff9f43]Equip (Overburden Slot: -10% SPD)[]"
                    : ("[#" + accentHex + "]Equip in Active Slot #" + (eq.equipped.size + 1) + "[]"));

            inner.add(
                button(btnLabel)
                    .style("defaultt")
                    .disabled(!canEquipMore)
                    .clicked("equip:" + item.id)
                    .height(34f)
                    .growX()
            );
            card.add(inner.growX());

            if (col == 0) {
                grid.add(card.width(CARD_WIDTH).padRight(10f).padBottom(8f));
                col = 1;
            } else {
                grid.add(card.width(CARD_WIDTH).padBottom(8f)).row();
                col = 0;
            }
        }
        if (col == 1) grid.row();
        return grid;
    }

    private static String fmtAmt(int amount) {
        if (amount >= 1_000_000_000) return Strings.autoFixed(amount / 1_000_000_000f, 2) + "b";
        if (amount >= 1_000_000) return Strings.autoFixed(amount / 1_000_000f, 1) + "m";
        if (amount >= 10_000) return Strings.autoFixed(amount / 1_000f, 1) + "k";
        return Integer.toString(amount);
    }
}
