package coolplugin.gameplay;

import arc.Events;
import arc.graphics.Color;
import arc.util.Strings;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.game.MapObjectives.TextMarker;
import mindustry.logic.LMarkerControl;
import mindustry.graphics.Layer;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Nullable;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BuildSelectEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.LaunchPayload;
import mindustry.gen.Player;
import mindustry.net.Administration.ActionType;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.campaign.LaunchPad.LaunchPadBuild;

import static mindustry.Vars.*;

/**
 * Player-owned Launch Pad manager (`launch-pad` 3x3 only; v8 `advanced-launch-pad` removed):
 * <ul>
 *   <li>Tracks individual player ownership per placed Launch Pad (not shared across teammates).</li>
 *   <li>Renders a persistent flat octagonal nameplate on each Launch Pad using world-coordinate markers.</li>
 *   <li>Intercepts launched {@link LaunchPayload} pods and deposits their item stacks into the owner's
 *       per-faction persistent SQLite orbital storage (`account_orbital_storage`, capped at {@link Integer#MAX_VALUE}),
 *       persisting across rejoins, map changes, and gameovers.</li>
 *   <li>Provides server console command {@code fillstorage <player> [faction]} to fill persistent storage up to {@code 2147483647}.</li>
 * </ul>
 */
public class LaunchPadSystem {

    public static final class PadOwnership {
        public final long accountId;
        public final String factionId;
        public String ownerName;
        public final String colorHex;
        public @Nullable TextMarker marker;
        public final Seq<Integer> markerIds = new Seq<>();
        private String markerText = "";

        public PadOwnership(long accountId, String factionId, String ownerName, String colorHex) {
            this.accountId = accountId;
            this.factionId = factionId;
            this.ownerName = ownerName;
            this.colorHex = colorHex;
        }
    }

    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;

    /** Center tile.pos() -> ownership record for active LaunchPadBuilds. */
    private final IntMap<PadOwnership> padOwners = new IntMap<>();
    /** Pending tile.pos() -> builder ownership recorded when construction begins. */
    private final IntMap<PadOwnership> pendingBuildOwners = new IntMap<>();
    /** Entity IDs of LaunchPayloads already credited into orbital storage. */
    private final IntSet processedPayloadIds = new IntSet();

    private final Interval timer = new Interval(2);
    private boolean started = false;

    public LaunchPadSystem(PlayerDatabase database, PlayerSessionManager sessionManager) {
        this.database = database;
        this.sessionManager = sessionManager;
    }

    public void start() {
        if (started) return;
        started = true;

        // Keep only the 3x3 launch-pad player-buildable; hide v8 advanced-launch-pad and landing-pad
        if (Blocks.launchPad != null) {
            Blocks.launchPad.buildVisibility = mindustry.world.meta.BuildVisibility.shown;
        }
        if (Blocks.advancedLaunchPad != null) {
            Blocks.advancedLaunchPad.buildVisibility = mindustry.world.meta.BuildVisibility.hidden;
        }
        if (Blocks.landingPad != null) {
            Blocks.landingPad.buildVisibility = mindustry.world.meta.BuildVisibility.hidden;
        }

        if (netServer != null && netServer.admins != null) {
            netServer.admins.addActionFilter(action -> {
                if (action != null
                    && action.type == ActionType.placeBlock
                    && action.player != null
                    && action.tile != null
                    && isLaunchPadBlock(action.block)) {
                    recordPendingOwner(action.tile, action.player);
                }
                return true;
            });
        }

        Events.on(WorldLoadEvent.class, e -> clearAllMarkers());

        Events.on(BuildSelectEvent.class, e -> {
            if (e.tile == null) return;
            if (e.breaking) {
                pendingBuildOwners.remove(e.tile.pos());
                return;
            }
            if (e.builder != null && e.builder.getPlayer() != null) {
                Block target = (e.tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cb)
                    ? cb.current
                    : e.tile.block();
                if (isLaunchPadBlock(target)) {
                    recordPendingOwner(e.tile, e.builder.getPlayer());
                }
            }
        });

        Events.on(BlockBuildEndEvent.class, e -> {
            if (e.tile == null) return;
            int pos = e.tile.pos();
            if (e.breaking) {
                pendingBuildOwners.remove(pos);
                removePadOwner(pos);
                return;
            }
            if (e.tile.build instanceof LaunchPadBuild padBuild) {
                Player builderPlayer = (e.unit != null) ? e.unit.getPlayer() : null;
                if (builderPlayer != null) {
                    assignOwner(padBuild, builderPlayer);
                    pendingBuildOwners.remove(pos);
                } else {
                    PadOwnership pending = pendingBuildOwners.remove(pos);
                    if (pending != null) {
                        attachOwnership(padBuild, pending);
                    }
                }
            }
        });

        Events.run(Trigger.update, this::update);
    }

    public void registerServerCommands(CommandHandler handler) {
        handler.register(
            "fillstorage",
            "<player> [faction]",
            "Fill a player's persistent per-faction orbital storage up to 2147483647 (32-bit signed integer limit).",
            args -> {
                String query = args[0];
                String explicitFaction = args.length > 1 ? args[1] : null;
                AccountRecord account = sessionManager.resolveTargetAccount(query);
                if (account == null) {
                    Log.warn("[CoolPlugin] fillstorage: No online player or database account matched '@'.", query);
                    return;
                }
                String factionId = sessionManager.resolveTargetFactionId(query, explicitFaction);
                int filledCount = fillStorageToIntMax(account.id, factionId);
                String labelName = account.login != null
                    ? account.login
                    : (account.lastName != null ? account.lastName : "guest");
                Log.info(
                    "[CoolPlugin] Filled persistent orbital storage for account #@ (@, faction=@): @ item types set to @.",
                    account.id, labelName, factionId, filledCount, Integer.MAX_VALUE
                );
            }
        );
    }

    public int fillStorageToIntMax(long accountId, String factionId) {
        Seq<String> itemNames = new Seq<>();
        if (Vars.content != null) {
            for (Item item : Vars.content.items()) {
                if (item != null && !item.hidden) {
                    itemNames.add(item.name);
                }
            }
        }
        return database.fillOrbitalStorageToMax(accountId, factionId, itemNames);
    }

    public static boolean isLaunchPadBlock(@Nullable Block block) {
        if (block == null) return false;
        if (block == Blocks.advancedLaunchPad || block == Blocks.landingPad) return false;
        return block == Blocks.launchPad || "launch-pad".equals(block.name);
    }

    public void assignOwner(LaunchPadBuild padBuild, Player player) {
        if (padBuild == null || player == null) return;
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        FactionDef fDef = FactionDef.byId(factionId);
        String hex = fDef != null ? fDef.accentHex : "ffd37f";
        String ownerLabel = acc.isRegistered() && acc.login != null
            ? acc.login
            : (player.plainName() != null && !player.plainName().isEmpty() ? player.plainName() : "Player");
        attachOwnership(padBuild, new PadOwnership(acc.id, factionId, ownerLabel, hex));
    }

    public void assignOwnerByAccount(
        LaunchPadBuild padBuild,
        long accountId,
        String factionId,
        String ownerLabel,
        @Nullable String colorHex
    ) {
        if (padBuild == null) return;
        FactionDef fDef = FactionDef.byId(factionId);
        String hex = colorHex != null ? colorHex : (fDef != null ? fDef.accentHex : "ffd37f");
        attachOwnership(padBuild, new PadOwnership(accountId, factionId, ownerLabel, hex));
    }

    public @Nullable PadOwnership getOwnership(@Nullable LaunchPadBuild padBuild) {
        if (padBuild == null || padBuild.tile == null) return null;
        return padOwners.get(padBuild.tile.pos());
    }

    private void recordPendingOwner(Tile tile, Player player) {
        AccountRecord acc = sessionManager.getOrInitSession(player);
        String factionId = sessionManager.getActiveFactionId(player.uuid());
        FactionDef fDef = FactionDef.byId(factionId);
        String hex = fDef != null ? fDef.accentHex : "ffd37f";
        String ownerLabel = acc.isRegistered() && acc.login != null
            ? acc.login
            : (player.plainName() != null && !player.plainName().isEmpty() ? player.plainName() : "Player");
        pendingBuildOwners.put(tile.pos(), new PadOwnership(acc.id, factionId, ownerLabel, hex));
    }

    private void attachOwnership(LaunchPadBuild padBuild, PadOwnership ownership) {
        if (padBuild.tile == null) return;
        int pos = padBuild.tile.pos();
        removePadOwner(pos);
        padOwners.put(pos, ownership);
        ensureMarker(padBuild, ownership);
    }

    private int allocatePadMarker() {
        // Separate from shop circles (4000+) and item processors (10000+).
        for (int id = 6000; id < 10000; id++) {
            if (!usedPadMarkers.contains(id) && !state.markers.has(id)) {
                usedPadMarkers.add(id);
                return id;
            }
        }
        return -1;
    }

    private final IntSet usedPadMarkers = new IntSet();

    private void ensureMarker(LaunchPadBuild padBuild, PadOwnership ownership) {
        if (state == null || state.markers == null) return;
        String text = formatMarkerText(ownership);
        boolean intact = ownership.marker != null && text.equals(ownership.markerText);
        for (int id : ownership.markerIds) intact &= state.markers.has(id);
        if (intact) return;
        removeOwnershipMarkers(ownership);
        int base = allocatePadMarker();
        int accent = allocatePadMarker();
        int name = allocatePadMarker();
        if (base < 0 || accent < 0 || name < 0) {
            if (base >= 0) usedPadMarkers.remove(base);
            if (accent >= 0) usedPadMarkers.remove(accent);
            if (name >= 0) usedPadMarkers.remove(name);
            return;
        }
        ownership.markerIds.add(base, accent, name);
        // Flat, eight-sided inset plate: no gradients, glow, pulses, or floating UI bubble.
        ShapeMarker plate = new ShapeMarker(padBuild.x, padBuild.y, 9f, 22.5f);
        plate.sides = 8;
        plate.fill = true;
        plate.outline = false;
        plate.color = Color.valueOf("252b32");
        plate.control(LMarkerControl.drawLayer, Layer.blockOver + 0.1f, Double.NaN, Double.NaN);
        Call.createMarker(base, plate);
        ShapeMarker rim = new ShapeMarker(padBuild.x, padBuild.y, 8f, 22.5f);
        rim.sides = 8;
        rim.stroke = 0.65f;
        rim.outline = false;
        rim.color = Color.valueOf(ownership.colorHex);
        rim.control(LMarkerControl.drawLayer, Layer.blockOver + 0.2f, Double.NaN, Double.NaN);
        Call.createMarker(accent, rim);
        String plainName = Strings.stripColors(ownership.ownerName == null ? "Unknown" : ownership.ownerName)
            .replace('\n', ' ').replace('\r', ' ');
        float fontSize = Math.min(0.48f, 4.8f / Math.max(8, plainName.length() + 2));
        TextMarker label = new TextMarker(text, padBuild.x, padBuild.y, fontSize, (byte)0);
        label.control(LMarkerControl.drawLayer, Layer.blockOver + 0.3f, Double.NaN, Double.NaN);
        Call.createMarker(name, label);
        ownership.marker = label;
        ownership.markerText = text;
    }

    private static String formatMarkerText(PadOwnership ownership) {
        String name = Strings.stripColors(ownership.ownerName == null ? "Unknown" : ownership.ownerName)
            .replace('\n', ' ').replace('\r', ' ').replace("[", "[[");
        return "[#" + ownership.colorHex + "]" + name + "'s[]\n[white]launch pad[]";
    }

    private void removeOwnershipMarkers(PadOwnership ownership) {
        for (int id : ownership.markerIds) {
            if (state != null && state.markers != null && state.markers.has(id)) Call.removeMarker(id);
            usedPadMarkers.remove(id);
        }
        ownership.markerIds.clear();
        ownership.marker = null;
    }

    private void removePadOwner(int tilePos) {
        PadOwnership old = padOwners.remove(tilePos);
        if (old != null) removeOwnershipMarkers(old);
    }

    private void clearAllMarkers() {
        for (IntMap.Entry<PadOwnership> entry : padOwners.entries()) {
            if (entry.value != null) removeOwnershipMarkers(entry.value);
        }
        usedPadMarkers.clear();
        padOwners.clear();
        pendingBuildOwners.clear();
        processedPayloadIds.clear();
    }

    private void update() {
        if (state == null || !state.isGame() || state.isPaused() || world == null || world.tiles == null) return;

        // 1. Capture newly launched LaunchPayload entities and deposit stacks into pad owner's persistent DB storage
        if (Groups.all != null) {
            for (var entity : Groups.all) {
                if (!(entity instanceof LaunchPayload payload)) continue;
                if (!processedPayloadIds.add(payload.id)) continue;

                LaunchPadBuild sourcePad = findLaunchPadAt(payload.x, payload.y);
                if (sourcePad == null) continue;

                PadOwnership owner = getOwnership(sourcePad);
                if (owner == null || payload.stacks == null || payload.stacks.isEmpty()) continue;

                for (ItemStack stack : payload.stacks) {
                    if (stack == null || stack.item == null || stack.amount <= 0) continue;
                    database.addOrbitalItem(owner.accountId, owner.factionId, stack.item.name, stack.amount);
                }

            }
        }

        // 2. Periodic cleanup of destroyed/deconstructed pads & expired payload IDs
        if (timer.get(0, 30f)) {
            Seq<Integer> stalePositions = new Seq<>();
            for (IntMap.Entry<PadOwnership> entry : padOwners.entries()) {
                Tile t = world.tile(entry.key);
                if (t == null || !(t.build instanceof LaunchPadBuild padBuild) || !padBuild.isValid() || padBuild.tile.pos() != entry.key) {
                    stalePositions.add(entry.key);
                } else {
                    // Refresh owner display name if guest converted to registered login
                    AccountRecord acc = database.getById(entry.value.accountId);
                    if (acc != null && acc.isRegistered() && acc.login != null && !acc.login.equals(entry.value.ownerName)) {
                        entry.value.ownerName = acc.login;
                    }
                    ensureMarker(padBuild, entry.value);
                }
            }
            for (int i = 0; i < stalePositions.size; i++) {
                removePadOwner(stalePositions.get(i));
            }
        }

        if (timer.get(1, 300f) && processedPayloadIds.size > 256) {
            IntSet stillAlive = new IntSet();
            if (Groups.all != null) {
                for (var entity : Groups.all) {
                    if (entity instanceof LaunchPayload lp && processedPayloadIds.contains(lp.id)) {
                        stillAlive.add(lp.id);
                    }
                }
            }
            processedPayloadIds.clear();
            processedPayloadIds.addAll(stillAlive);
        }
    }

    private @Nullable LaunchPadBuild findLaunchPadAt(float wx, float wy) {
        if (world == null) return null;
        Tile center = world.tileWorld(wx, wy);
        if (center != null && center.build instanceof LaunchPadBuild direct) {
            return direct;
        }
        LaunchPadBuild closest = null;
        float bestDst2 = 16f * 16f;
        for (IntMap.Entry<PadOwnership> entry : padOwners.entries()) {
            Tile t = world.tile(entry.key);
            if (t != null && t.build instanceof LaunchPadBuild pad) {
                float d2 = Mathf.dst2(wx, wy, pad.x, pad.y);
                if (d2 <= bestDst2) {
                    bestDst2 = d2;
                    closest = pad;
                }
            }
        }
        return closest;
    }

    /** Removes active map Launch Pad ownership markers belonging to {@code accountId} after {@code wipedata}. */
    public void onAccountWiped(long accountId) {
        Seq<Integer> toRemove = new Seq<>();
        for (IntMap.Entry<PadOwnership> e : padOwners.entries()) {
            if (e.value != null && e.value.accountId == accountId) {
                toRemove.add(e.key);
            }
        }
        for (int i = 0; i < toRemove.size; i++) {
            removePadOwner(toRemove.get(i));
        }

        Seq<Integer> pendingRemove = new Seq<>();
        for (IntMap.Entry<PadOwnership> e : pendingBuildOwners.entries()) {
            if (e.value != null && e.value.accountId == accountId) {
                pendingRemove.add(e.key);
            }
        }
        for (int i = 0; i < pendingRemove.size; i++) {
            pendingBuildOwners.remove(pendingRemove.get(i));
        }
    }
}
