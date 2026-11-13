package coolplugin.gameplay;

import arc.func.Cons2;
import arc.func.Func2;
import arc.util.Nullable;
import arc.util.Scaling;
import mindustry.content.UnitTypes;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.ButtonTableBuilder;
import mindustry.ui.builder.UiBuilder.ImageBuilder;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiBuilder.StackBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static mindustry.ui.builder.UiBuilder.*;

/**
 * Decorated 4-column {@code MenuBuilder} faction selection dialog with 6-sided elongated
 * hexagonal column buttons (2 angled sides on top, 2 vertical sides, 2 angled sides on bottom)
 * and compact Mindustry-font faction bonus descriptions.
 * <ul>
 *   <li>Header: {@code Choose your faction}</li>
 *   <li>4 hexagonal columns: {@code Sharded}, {@code Crux}, {@code Malis} (disabled/grayed),
 *       {@code Neoplasm} (rightmost, disabled/grayed)</li>
 *   <li>Each column displays {@code [symbol] [Faction Name]} beneath the upper pointed peak,
 *       borderless tilted faction render ({@code dp-faction-*}, {@link Scaling#fit}) in the center,
 *       compact faction stat bonus text ({@code dp-faction-desc-*}) below the render,
 *       and {@code Current:} with {@code [unit uiIcon] [unit name]} above the lower pointed peak.</li>
 * </ul>
 */
public class FactionJoinMenu {

    public static final float COL_WIDTH = 228f;
    public static final float COL_HEIGHT = 440f;
    public static final float COL_GAP = 10f;
    public static final float TOTAL_WIDTH = COL_WIDTH * 4f + COL_GAP * 3f;
    public static final float ART_BOX_WIDTH = 200f;
    public static final float ART_BOX_HEIGHT = 200f;
    public static final float DESC_BOX_WIDTH = 212f;
    public static final float DESC_BOX_HEIGHT = 54f;

    private final Cons2<Player, FactionDef> onChosen;
    private final Cons2<Player, MenuResult> onDismissedWithoutChoice;
    private final Func2<Player, FactionDef, UnitType> unitResolver;
    private final AtomicLong tokenSeq = new AtomicLong(1000L);
    private int menuId = -1;

    public FactionJoinMenu(
        Cons2<Player, FactionDef> onChosen,
        Cons2<Player, MenuResult> onDismissedWithoutChoice
    ) {
        this(onChosen, onDismissedWithoutChoice, (p, f) -> null);
    }

    public FactionJoinMenu(
        Cons2<Player, FactionDef> onChosen,
        Cons2<Player, MenuResult> onDismissedWithoutChoice,
        Func2<Player, FactionDef, UnitType> unitResolver
    ) {
        this.onChosen = onChosen;
        this.onDismissedWithoutChoice = onDismissedWithoutChoice;
        this.unitResolver = unitResolver;
    }

    public void register() {
        if (menuId != -1) return;
        menuId = Menus.registerMenuBuilder(this::handleResult);
    }

    public void open(Player player) {
        if (player == null || player.con == null || !player.con.isConnected()) return;
        register();
        UiSpriteSync.ensureSent(player);
        long token = tokenSeq.incrementAndGet();
        Call.menuBuilder(player.con, menuId, token, null, true, true, false, buildRoot(player));
    }

    public NodeBuilder<?> buildRootForTest(@Nullable Player player) {
        return buildRoot(player);
    }

    private void handleResult(Player player, MenuResult result) {
        if (player == null || player.con == null || !player.con.isConnected()) return;

        String btn = (result == null || result.wasCancelled()) ? null : result.result;
        if (btn != null && btn.startsWith("faction:")) {
            FactionDef chosen = FactionDef.byId(btn.substring("faction:".length()));
            if (chosen != null && chosen.available) {
                onChosen.get(player, chosen);
                return;
            }
        }
        onDismissedWithoutChoice.get(player, result);
    }

    private TableBuilder buildRoot(@Nullable Player player) {
        TableBuilder root = table().margin(6f);

        // 1. Decorated Minimal Header Banner ("Choose your faction")
        root.add(buildHeaderBanner().width(TOTAL_WIDTH).padBottom(10f)).row();

        // 2. 4 6-Sided Hexagonal Faction Columns: Sharded | Crux | Malis | Neoplasm (Neoplasm rightmost)
        TableBuilder cols = table();
        FactionDef[] all = FactionDef.values();
        for (int i = 0; i < all.length; i++) {
            FactionDef faction = all[i];
            UnitType currentUnit = resolveUnit(player, faction);
            ButtonTableBuilder col = buildFactionColumn(faction, currentUnit)
                .width(COL_WIDTH)
                .height(COL_HEIGHT);
            if (i < all.length - 1) {
                col.padRight(COL_GAP);
            }
            cols.add(col);
        }
        root.add(cols);

        return root;
    }

    private TableBuilder buildHeaderBanner() {
        TableBuilder banner = table().background("pane").margin(0f);

        // Top 4-segment decorative trim mirroring the 4 columns below
        TableBuilder topTrim = table();
        topTrim.add(image("whiteui").color("ffd37fff").height(4f).growX());
        topTrim.add(image("whiteui").color("f25555ff").height(4f).growX());
        topTrim.add(image("whiteui").color("5e5e6cff").height(4f).growX());
        topTrim.add(image("whiteui").color("5e5e6cff").height(4f).growX());
        banner.add(topTrim.growX()).row();

        TableBuilder inner = table().margin(10f);
        inner.add(image("whiteui").color("ffd37fff").width(18f).height(3f).padRight(12f));
        inner.add(label("[white]Choose your faction[]").labelAlign("center"));
        inner.add(image("whiteui").color("f25555ff").width(18f).height(3f).padLeft(12f));
        banner.add(inner.growX()).row();

        banner.add(image("whiteui").color("3b3b48ff").height(2f).growX());
        return banner;
    }

    private ButtonTableBuilder buildFactionColumn(FactionDef faction, UnitType currentUnit) {
        boolean enabled = faction.available;
        String dividerHex = enabled ? (faction.borderHex + "66") : "525260cc";

        // Borderless button wrapper ("emptyi" has null up/over/down/disabled drawables)
        // so the 6-sided hexagonal dp-faction-col-* frame defines the column's silhouette cleanly.
        ButtonTableBuilder col = buttonTable()
            .style("emptyi")
            .margin(0f)
            .align("center");

        if (enabled) {
            col.clicked("faction:" + faction.id);
        } else {
            col.disabled(true);
        }

        StackBuilder colStack = stack()
            .width(COL_WIDTH)
            .height(COL_HEIGHT);

        // Layer 0: 6-sided vertical hexagon sprite (2 angled sides top, 2 vertical sides, 2 angled sides bottom)
        ImageBuilder bgImg = image(UiSpriteSync.resolveMenuDrawable(faction.colBgRegion))
            .placeholder(enabled ? "button" : "button-disabled")
            .scaling(Scaling.stretch);
        colStack.add(bgImg);

        // Layer 1: Column content aligned cleanly within the pointed upper & lower hexagon peaks
        TableBuilder inner = table().margin(0f).align("top");

        // 1. Upper Pointed Hexagon Peak: [Faction Symbol] + [Faction Name]
        TableBuilder headerRow = table().align("center");
        ImageBuilder symbol = image(UiSpriteSync.resolveMenuDrawable(faction.iconRegion))
            .scaling(Scaling.fit)
            .size(24f)
            .padRight(7f);
        if (faction.fallbackIconRegion != null) {
            symbol.placeholder(faction.fallbackIconRegion);
        }
        symbol.color(enabled ? (faction.accentHex + "ff") : "8c8c9aff");
        headerRow.add(symbol);

        String titleMarkup = enabled
            ? ("[#" + faction.accentHex + "]" + faction.title + "[]")
            : ("[#9696a4]" + faction.title + "[]");
        headerRow.add(label(titleMarkup).labelAlign("left"));
        inner.add(headerRow.padTop(34f).padBottom(6f)).row();

        // Divider right below the upper hexagon shoulder
        inner.add(image("whiteui").color(dividerHex).width(COL_WIDTH - 28f).height(2f).padBottom(4f)).row();

        // 2. Center: Borderless Tilted Faction Art (Scaling.fit)
        ImageBuilder artImg = image(UiSpriteSync.resolveMenuDrawable(faction.artRegion))
            .placeholder(faction.artPlaceholder)
            .scaling(Scaling.fit)
            .width(ART_BOX_WIDTH)
            .height(ART_BOX_HEIGHT)
            .padBottom(4f);
        if (!enabled) {
            artImg.color("b4b4c0e8");
        }
        inner.add(artImg).row();

        // 3. Compact Reduced-Size Faction Bonus Description (below render, above Current unit)
        ImageBuilder descImg = image(UiSpriteSync.resolveMenuDrawable(faction.descRegion))
            .placeholder("clear")
            .scaling(Scaling.fit)
            .width(DESC_BOX_WIDTH)
            .height(DESC_BOX_HEIGHT)
            .padBottom(4f);
        inner.add(descImg).row();

        inner.add(space().growY()).row();

        // Divider right above the lower hexagon shoulder
        inner.add(image("whiteui").color(dividerHex).width(COL_WIDTH - 28f).height(2f).padBottom(5f)).row();

        // 4. Lower Pointed Hexagon Peak:
        //    - existing faction unit -> "Current:" + [unit uiIcon + unit name]
        //    - never started available faction -> "New game"
        //    - unavailable faction -> blank (write nothing)
        TableBuilder bottomStatus = table().align("center").height(45f);
        if (currentUnit != null) {
            String currentLabelMarkup = enabled ? "[lightgray]Current:[]" : "[#828290]Current:[]";
            bottomStatus.add(label(currentLabelMarkup).labelAlign("center").padBottom(3f)).row();

            TableBuilder unitRow = table().align("center").height(22f);
            ImageBuilder unitIcon = image(UiSpriteSync.resolveMenuDrawable(unitUiIconRegion(currentUnit)))
                .placeholder("unit-dagger-ui")
                .scaling(Scaling.fit)
                .size(22f)
                .padRight(6f);
            if (!enabled) {
                unitIcon.color("8a8a98d8");
            }
            unitRow.add(unitIcon);

            String unitName = formatUnitName(currentUnit);
            String unitMarkup = enabled ? ("[white]" + unitName + "[]") : ("[#9696a4]" + unitName + "[]");
            unitRow.add(label(unitMarkup).labelAlign("left"));
            bottomStatus.add(unitRow);
        } else if (enabled) {
            bottomStatus.add(label("[white]New game[]").labelAlign("center"));
        }
        inner.add(bottomStatus.padBottom(35f));

        colStack.add(inner);
        col.add(colStack);
        return col;
    }

    private @Nullable UnitType resolveUnit(@Nullable Player player, FactionDef faction) {
        if (unitResolver != null) {
            return unitResolver.get(player, faction);
        }
        return null;
    }

    public static String unitUiIconRegion(@Nullable UnitType unit) {
        if (unit == null || unit.name == null || unit.name.isEmpty()) {
            return "unit-dagger-ui";
        }
        return "unit-" + unit.name + "-ui";
    }

    public static String formatUnitName(@Nullable UnitType unit) {
        if (unit == null || unit.name == null || unit.name.isEmpty()) {
            return "New game";
        }
        if (unit.localizedName != null
            && !unit.localizedName.isEmpty()
            && !unit.localizedName.equals(unit.name)) {
            return unit.localizedName;
        }
        String raw = unit.name.startsWith("dp-") ? unit.name.substring(3) : unit.name;
        String[] parts = raw.split("[-_]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.length() > 0 ? sb.toString() : "Dagger";
    }
}
