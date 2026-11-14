package coolplugin.gameplay;

import arc.util.Nullable;
import arc.util.Scaling;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.gameplay.PlayerItemSystem.ActionOutcome;
import coolplugin.gameplay.PlayerItemSystem.ComputedStats;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.StationTier;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder.*;

import java.util.Locale;

import static mindustry.ui.builder.UiBuilder.*;

/**
 * Reusable UI component rendering a player's 6-sided scrollable size-budget Inventory panel
 * together with the live Stat Multipliers readout directly below it.
 * <p>
 * Designed to be embedded in {@link ItemShopMenu} as well as any other {@code MenuBuilder}
 * dialog (unit loadout, faction, tech, inspection menus, etc.).
 */
public class PlayerInventoryDrawer {

    public static final float WIDTH = 228f;
    public static final float HEX_HEIGHT = 368f;
    public static final String GLOW_SPRITE = "shop-slot-glow";

    public static final String ACT_SELECT_PREFIX = "inv:";
    public static final String ACT_DECRAFT_PREFIX = "decraftslot:";
    public static final String ACT_DROP_PREFIX = "dropslot:";
    public static final String ACT_PROMOTE_PREFIX = "promoteslot:";

    public record DrawerTheme(String hexSprite, String accentHex, String borderHex) {
        public static final DrawerTheme DEFAULT = new DrawerTheme(
            StationTier.WORKBENCH.invHexSprite,
            StationTier.WORKBENCH.accentHex,
            StationTier.WORKBENCH.borderHex
        );

        public static DrawerTheme forStation(@Nullable StationTier station) {
            if (station == null) return DEFAULT;
            return new DrawerTheme(station.invHexSprite, station.accentHex, station.borderHex);
        }
    }

    public record ActionResult(
        boolean handled,
        int selectedSlot,
        String statusMessage,
        boolean inventoryChanged,
        boolean resourcesChanged
    ) {
        public static ActionResult unhandled(int currentSlot) {
            return new ActionResult(false, currentSlot, "", false, false);
        }
    }

    private final PlayerItemSystem itemSystem;

    public PlayerInventoryDrawer(PlayerItemSystem itemSystem) {
        this.itemSystem = itemSystem;
    }

    /**
     * Builds the complete right-hand drawer column:
     * <ol>
     *   <li>Unit-specific equip/carry capacities; selected items glow.</li>
     *   <li>Live stat multipliers panel directly below the inventory hexagon.</li>
     * </ol>
     */
    public TableBuilder build(Player player, int selectedSlot) {
        return build(player, selectedSlot, DrawerTheme.DEFAULT, false);
    }

    public TableBuilder build(
        Player player,
        int selectedSlot,
        DrawerTheme theme,
        boolean allowDecraft
    ) {
        EquipmentData eq = itemSystem.getEquipment(player);
        var wearer=PlayerUnitSystem.ownedUnit(player);if(wearer==null)wearer=player.unit();
        ComputedStats stats = itemSystem.computeStats(eq.equipped,wearer);

        TableBuilder col = table().align("top").width(WIDTH);
        col.add(buildHexInventory(eq, selectedSlot, theme, allowDecraft).padBottom(8f)).row();
        col.add(buildStatsPanel(stats));
        return col;
    }

    public StackBuilder buildHexInventory(
        EquipmentData eq,
        int selectedSlot,
        DrawerTheme theme,
        boolean allowDecraft
    ) {
        Catalog cat = itemSystem.getCatalog();

        StackBuilder hexStack = stack()
            .width(WIDTH)
            .height(HEX_HEIGHT);

        hexStack.add(
            image(UiSpriteSync.resolveMenuDrawable(theme.hexSprite))
                .placeholder("pane")
                .scaling(Scaling.stretch)
        );

        TableBuilder hexInner = table().margin(0f).align("top");

        // Capacity costs, rather than raw number of items.
        String countCol="[gray]";
        int equipUsed=PlayerItemSystem.usedCapacity(eq,true),carryUsed=PlayerItemSystem.usedCapacity(eq,false);
        hexInner.add(
            label("[#" + theme.accentHex + "]Inventory[]\n" + countCol + "E "+equipUsed+"/"+eq.equipCapacity+" · C "+carryUsed+"/"+eq.carryCapacity+"[]")
                .labelAlign("center")
                .padTop(34f)
                .padBottom(4f)
        ).row();
        hexInner.add(
            image("whiteui")
                .color(theme.borderHex + "88")
                .width(WIDTH - 52f)
                .height(2f)
                .padBottom(5f)
        ).row();

        // Scrollable equipped/carry item grid. Empty capacity is summarized, not 90 blank buttons.
        TableBuilder grid = table().align("center");
        var shown=new arc.struct.IntSeq();
        for(int i=0;i<Math.min(30,eq.equipped.size);i++)if(!eq.equipped.get(i).isBlank())shown.add(i);
        if(equipUsed<eq.equipCapacity){for(int i=0;i<30;i++)if(i>=eq.equipped.size||eq.equipped.get(i).isBlank()){shown.add(i);break;}}
        for(int i=30;i<Math.min(90,eq.equipped.size);i++)if(!eq.equipped.get(i).isBlank())shown.add(i);
        if(carryUsed<eq.carryCapacity){for(int i=30;i<90;i++)if(i>=eq.equipped.size||eq.equipped.get(i).isBlank()){shown.add(i);break;}}
        boolean carryHeader=false;int column=0;
        for (int cell=0;cell<shown.size;cell++) {
            int idx=shown.get(cell);
            boolean activeTier = idx < PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            boolean occupied = idx < eq.equipped.size && !eq.equipped.get(idx).isBlank();
            boolean selected = occupied && (selectedSlot == idx);

            if (!activeTier&&!carryHeader) {
                if(column%2!=0)grid.row();column=0;carryHeader=true;
                grid.add(
                    image("whiteui")
                        .color("5c343cff")
                        .width(150f)
                        .height(1f)
                        .colspan(2)
                        .padTop(1f)
                        .padBottom(2f)
                ).row();
            }

            ButtonTableBuilder cellBtn = buttonTable()
                .style("defaultt")
                .checked(selected)
                .margin(2f)
                .width(66f)
                .height(32f);

            if (occupied) {
                String itemId = eq.equipped.get(idx);
                ShopItemDef def = cat.findItem(itemId);
                cellBtn.clicked(ACT_SELECT_PREFIX + idx);

                ImageBuilder icon = image(def != null ? def.uiDrawable() : "item-copper-ui")
                    .placeholder("item-copper-ui")
                    .scaling(Scaling.fit)
                    .size(23f);
                if (!activeTier && !selected) {
                    icon.color("ff9e9eff");
                }
                cellBtn.add(icon);
                cellBtn.add(label("[gray]"+(def==null?1:(activeTier?Math.max(eq.minimumEquipSize,def.size):def.size))+"[]"));
            } else {
                cellBtn.disabled(true);
                cellBtn.add(label(activeTier ? "[#45485a].[]" : "[#3a2830].[]").labelAlign("center"));
            }

            // Selected inventory slot glows with bright outer halo + crisp luminous rim!
            if (selected) {
                StackBuilder glowCell = stack().width(80f).height(42f);
                glowCell.add(
                    image(UiSpriteSync.resolveMenuDrawable(GLOW_SPRITE))
                        .placeholder("whiteui")
                        .color(theme.accentHex + "ff")
                        .scaling(Scaling.stretch)
                );
                glowCell.add(table().align("center").add(cellBtn));
                grid.add(glowCell);
            } else {
                grid.add(table().width(80f).height(42f).align("center").add(cellBtn));
            }

            if (++column % 2 == 0) {
                grid.row();
            }
        }
        hexInner.add(pane().add(grid).height(150f)).row();
        hexInner.add(label("[gray]Min equip "+eq.minimumEquipSize+"\nCarry slow "+Math.round(75f*Math.min(1f,carryUsed/(float)eq.carryCapacity))+"%[]")).row();

        // Selected slot quick-actions tucked cleanly above the lower hexagon peak
        if (selectedSlot >= 0 && selectedSlot < eq.equipped.size) {
            String selId = eq.equipped.get(selectedSlot);
            ShopItemDef selDef = cat.findItem(selId);
            String selName = selDef != null ? selDef.name : selId;

            hexInner.add(
                label("[#" + theme.accentHex + "]" + truncate(selName, 18) + "[]")
                    .labelAlign("center")
                    .padTop(3f)
                    .padBottom(2f)
            ).row();

            TableBuilder actRow = table().align("center");
            boolean canDecraftSelected = allowDecraft
                && selDef != null && selDef.decraftable && selDef.hasRecipe();

            if (canDecraftSelected) {
                actRow.add(
                    buttonTable()
                        .style("defaultt")
                        .margin(3f)
                        .clicked(ACT_DECRAFT_PREFIX + selectedSlot)
                        .add(label("[#eab678]Decraft[]"))
                        .width(82f)
                        .height(24f)
                        .padRight(selectedSlot >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS ? 4f : 0f)
                );
            } else {
                actRow.add(
                    buttonTable()
                        .style("defaultt")
                        .margin(3f)
                        .clicked(ACT_DROP_PREFIX + selectedSlot)
                        .add(label("[scarlet]Drop[]"))
                        .width(72f)
                        .height(24f)
                        .padRight(selectedSlot >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS ? 4f : 0f)
                );
            }

            if (selDef!=null) {
                actRow.add(
                    buttonTable()
                        .style("defaultt")
                        .margin(3f)
                        .clicked(ACT_PROMOTE_PREFIX + selectedSlot)
                        .add(label(selectedSlot>=30?"[stat]Equip[]":"[stat]Carry[]"))
                        .width(56f)
                        .height(24f)
                );
            }
            hexInner.add(actRow);
        }

        hexStack.add(hexInner);
        return hexStack;
    }

    public TableBuilder buildStatsPanel(ComputedStats st) {
        TableBuilder box = table().background("pane").margin(8f).width(WIDTH);

        TableBuilder grid = table().growX();
        addMultCell(grid, "HP", st.healthMult, true);
        addMultCell(grid, "DMG", st.damageMult, false);
        grid.row();
        addMultCell(grid, "SPD", st.speedMult, true);
        addMultCell(grid, "RLD", st.reloadMult, false);
        grid.row();
        addMultCell(grid, "BLD", st.buildSpeedMult, true);

        String armVal = st.armorBonus == 0f
            ? "[lightgray]+0[]"
            : ((st.armorBonus > 0 ? "[stat]+" : "[scarlet]") + Math.round(st.armorBonus) + "[]");
        grid.add(label("[gray]ARM[] " + armVal).labelAlign("left").growX().padTop(2f));

        if (st.regenPerSec > 0f) {
            grid.row();
            grid.add(
                label("[gray]REGEN[] [stat]+" + String.format(Locale.ROOT, "%.1f/s[]", st.regenPerSec))
                    .labelAlign("left")
                    .colspan(2)
                    .padTop(2f)
            );
        }

        grid.row();
        grid.add(label("[gray]Carry speed[] ×"+String.format(Locale.ROOT,"%.2f",st.carrySpeedFactor))
            .colspan(2).padTop(5f));
        box.add(grid.growX());
        return box;
    }

    /**
     * Processes inventory slot clicks ({@code inv:}, {@code decraftslot:}, {@code dropslot:}, {@code promoteslot:})
     * for any hosting menu.
     */
    public ActionResult handleAction(
        Player player,
        String action,
        int currentSelectedSlot,
        @Nullable StationTier decraftStation
    ) {
        if (action == null) return ActionResult.unhandled(currentSelectedSlot);

        if (action.startsWith(ACT_SELECT_PREFIX)) {
            try {
                int idx = Integer.parseInt(action.substring(ACT_SELECT_PREFIX.length()));
                EquipmentData eq = itemSystem.getEquipment(player);
                if (idx >= 0 && idx < eq.equipped.size) {
                    int next = (currentSelectedSlot == idx) ? -1 : idx;
                    return new ActionResult(true, next, "", false, false);
                }
            } catch (NumberFormatException ignored) {
            }
            return new ActionResult(true, currentSelectedSlot, "", false, false);
        }

        if (action.startsWith(ACT_DECRAFT_PREFIX)) {
            try {
                int idx = Integer.parseInt(action.substring(ACT_DECRAFT_PREFIX.length()));
                StationTier st = decraftStation != null ? decraftStation : StationTier.WORKBENCH;
                ActionOutcome out = itemSystem.decraftAtSlot(player, idx, st);
                int clamped = clampSlot(player, currentSelectedSlot);
                return new ActionResult(true, clamped, out.message(), true, true);
            } catch (NumberFormatException ignored) {
            }
            return new ActionResult(true, currentSelectedSlot, "", false, false);
        }

        if (action.startsWith(ACT_PROMOTE_PREFIX)) {
            try {
                int idx = Integer.parseInt(action.substring(ACT_PROMOTE_PREFIX.length()));
                ActionOutcome out = itemSystem.promoteSlotToActive(player, idx);
                return new ActionResult(true, -1, out.message(), out.success(), false);
            } catch (NumberFormatException ignored) {
            }
            return new ActionResult(true, currentSelectedSlot, "", false, false);
        }

        if (action.startsWith(ACT_DROP_PREFIX)) {
            try {
                int idx = Integer.parseInt(action.substring(ACT_DROP_PREFIX.length()));
                ActionOutcome out = itemSystem.discardAtSlot(player, idx);
                int clamped = clampSlot(player, currentSelectedSlot);
                return new ActionResult(true, clamped, out.message(), true, false);
            } catch (NumberFormatException ignored) {
            }
            return new ActionResult(true, currentSelectedSlot, "", false, false);
        }

        return ActionResult.unhandled(currentSelectedSlot);
    }

    public int clampSlot(Player player, int selectedSlot) {
        EquipmentData eq = itemSystem.getEquipment(player);
        if (selectedSlot >= eq.equipped.size) {
            return eq.equipped.size - 1;
        }
        return selectedSlot;
    }

    private static void addMultCell(TableBuilder grid, String statName, float mult, boolean leftCol) {
        String col = mult > 1.001f ? "[stat]" : (mult < 0.999f ? "[scarlet]" : "[lightgray]");
        String txt = String.format(Locale.ROOT, "[gray]%s[] %sx%.2f[]", statName, col, mult);
        LabelBuilder lb = label(txt).labelAlign("left").growX().padTop(2f);
        if (leftCol) lb.padRight(6f);
        grid.add(lb);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s == null ? "" : s;
        return s.substring(0, maxLen - 2) + "..";
    }
}
