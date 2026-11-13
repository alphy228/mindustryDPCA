package coolplugin.gameplay;

import arc.util.Nullable;
import mindustry.game.Rules;
import mindustry.game.Rules.TeamRule;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Defines the four factions displayed as hexagonal-capped columns in {@link FactionJoinMenu}
 * (ordered {@code Sharded | Crux | Malis | Neoplasm} with Neoplasm rightmost), their
 * Mindustry {@link Team}s, UI symbols, custom hexagon-top/bottom column frames
 * ({@code dp-faction-col-*}), borderless tilted center renders ({@code dp-faction-*}),
 * compact faction bonus description renders ({@code dp-faction-desc-*}), post-map-load global
 * {@link TeamRule} bonuses, and {@code dp-<teamname>-spawnpoint} floor identifiers.
 */
public enum FactionDef {
    SHARDED(
        "sharded",
        "Sharded",
        "ffd37f",
        "eab678",
        "team-sharded",
        null,
        "dp-faction-col-sharded",
        "dp-faction-sharded",
        "unit-mega-ui",
        "dp-faction-desc-sharded",
        true,
        "[#ffd37f]10%[] [lightgray]increased unit health,\n[#ffd37f]10%[] increased building damage and health[]",
        0.10f,
        0.00f,
        0.10f,
        0.10f,
        new String[]{"dp-sharded-spawnpoint", "sharded-spawnpoint"}
    ) {
        @Override
        public Team team() {
            return Team.sharded;
        }
    },

    CRUX(
        "crux",
        "Crux",
        "f25555",
        "a04553",
        "team-crux",
        null,
        "dp-faction-col-crux",
        "dp-faction-crux",
        "unit-eclipse-ui",
        "dp-faction-desc-crux",
        true,
        "[#f25555]15%[] [lightgray]increased unit damage[]",
        0.00f,
        0.15f,
        0.00f,
        0.00f,
        new String[]{"dp-crux-spawnpoint", "crux-spawnpoint"}
    ) {
        @Override
        public Team team() {
            return Team.crux;
        }
    },

    MALIS(
        "malis",
        "Malis",
        "a27ce5",
        "504cba",
        "dp-team-malis-gray",
        "team-malis",
        "dp-faction-col-malis",
        "dp-faction-malis",
        "unit-disrupt-ui",
        "dp-faction-desc-malis",
        false,
        "[#828290]????[]",
        0.00f,
        0.00f,
        0.00f,
        0.00f,
        new String[]{"dp-malis-spawnpoint", "malis-spawnpoint"}
    ) {
        @Override
        public Team team() {
            return Team.malis;
        }
    },

    NEOPLASM(
        "neoplasm",
        "Neoplasm",
        "e05438",
        "8a3222",
        "dp-team-neoplasm",
        "liquid-neoplasm-ui",
        "dp-faction-col-neoplasm",
        "dp-faction-neoplasm",
        "unit-latum-ui",
        "dp-faction-desc-neoplasm",
        false,
        "[#828290]????[]",
        0.00f,
        0.00f,
        0.00f,
        0.00f,
        new String[]{"dp-neoplasm-spawnpoint", "dp-neoplastic-spawnpoint", "neoplasm-spawnpoint", "neoplastic-spawnpoint"}
    ) {
        @Override
        public Team team() {
            return Team.neoplastic;
        }
    };

    private static final Map<Rules, Boolean> APPLIED_RULES = Collections.synchronizedMap(new WeakHashMap<>());

    public final String id;
    public final String title;
    public final String accentHex;
    public final String borderHex;
    public final String iconRegion;
    public final @Nullable String fallbackIconRegion;
    public final String colBgRegion;
    public final String artRegion;
    public final String artPlaceholder;
    public final String descRegion;
    public final boolean available;
    public final String bonusMarkup;
    public final float unitHealthBonus;
    public final float unitDamageBonus;
    public final float blockDamageBonus;
    public final float blockHealthBonus;
    public final String[] spawnFloorNames;

    FactionDef(
        String id,
        String title,
        String accentHex,
        String borderHex,
        String iconRegion,
        @Nullable String fallbackIconRegion,
        String colBgRegion,
        String artRegion,
        String artPlaceholder,
        String descRegion,
        boolean available,
        String bonusMarkup,
        float unitHealthBonus,
        float unitDamageBonus,
        float blockDamageBonus,
        float blockHealthBonus,
        String[] spawnFloorNames
    ) {
        this.id = id;
        this.title = title;
        this.accentHex = accentHex;
        this.borderHex = borderHex;
        this.iconRegion = iconRegion;
        this.fallbackIconRegion = fallbackIconRegion;
        this.colBgRegion = colBgRegion;
        this.artRegion = artRegion;
        this.artPlaceholder = artPlaceholder;
        this.descRegion = descRegion;
        this.available = available;
        this.bonusMarkup = bonusMarkup;
        this.unitHealthBonus = unitHealthBonus;
        this.unitDamageBonus = unitDamageBonus;
        this.blockDamageBonus = blockDamageBonus;
        this.blockHealthBonus = blockHealthBonus;
        this.spawnFloorNames = spawnFloorNames;
    }

    public abstract Team team();

    /**
     * Applies global per-faction {@link TeamRule} stat bonuses onto {@code rules} once per loaded
     * map {@link Rules} instance (safe to call from multiple post-map-load hooks without stacking).
     * <ul>
     *   <li>Sharded: +10% unit health, +10% building damage, +10% building health</li>
     *   <li>Crux: +15% unit damage</li>
     * </ul>
     */
    public static boolean applyGlobalTeamRules(@Nullable Rules rules) {
        if (rules == null) return false;
        synchronized (APPLIED_RULES) {
            if (APPLIED_RULES.containsKey(rules)) {
                return false;
            }
            for (FactionDef faction : values()) {
                TeamRule tr = rules.teams.get(faction.team());
                if (faction.unitHealthBonus != 0f) {
                    tr.unitHealthMultiplier = roundStat(tr.unitHealthMultiplier * (1f + faction.unitHealthBonus));
                }
                if (faction.unitDamageBonus != 0f) {
                    tr.unitDamageMultiplier = roundStat(tr.unitDamageMultiplier * (1f + faction.unitDamageBonus));
                }
                if (faction.blockDamageBonus != 0f) {
                    tr.blockDamageMultiplier = roundStat(tr.blockDamageMultiplier * (1f + faction.blockDamageBonus));
                }
                if (faction.blockHealthBonus != 0f) {
                    tr.blockHealthMultiplier = roundStat(tr.blockHealthMultiplier * (1f + faction.blockHealthBonus));
                }
            }
            APPLIED_RULES.put(rules, Boolean.TRUE);
            return true;
        }
    }

    private static float roundStat(float value) {
        return Math.round(value * 10000f) / 10000f;
    }

    public String canonicalSpawnFloorName() {
        return spawnFloorNames[0];
    }

    public boolean matchesSpawnTile(@Nullable Tile tile) {
        if (tile == null) return false;
        return matchesBlockName(tile.floor())
            || matchesBlockName(tile.overlay())
            || matchesBlockName(tile.block());
    }

    private boolean matchesBlockName(@Nullable Block b) {
        if (b == null || b.name == null) return false;
        for (String candidate : spawnFloorNames) {
            if (candidate.equalsIgnoreCase(b.name)) return true;
        }
        return false;
    }

    public static @Nullable FactionDef byId(@Nullable String id) {
        if (id == null) return null;
        for (FactionDef f : values()) {
            if (f.id.equalsIgnoreCase(id)) return f;
        }
        return null;
    }
}
