package coolplugin.gameplay;

import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.HaloAnchor;
import coolplugin.gameplay.ShopItemDef.MountSubtype;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.WeaponMount;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.logic.LVar;
import mindustry.net.Administration.ActionType;
import mindustry.type.UnitType.UnitEngine;
import mindustry.type.Weapon;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.util.Locale;

import static mindustry.Vars.*;

/**
 * Places and synchronises hidden/non-hoverable server {@link Blocks#worldProcessor} blocks that render
 * each player's equipped items locally on clients at 60 FPS with zero network ping lag:
 * <ul>
 *   <li>Items are rendered via {@code MapObjectives} markers ({@code TextureMarker}, {@code ShapeMarker},
 *       {@code LineMarker}) rather than {@link Unit} entities, so items are never hoverable and never
 *       duplicate carrier units when reordering inventory slots (e.g. clicking {@code Top}).</li>
 *   <li>Active slots {@code 0..3} render {@link AttachMode#MOUNT}, {@link AttachMode#SUBWEAPON},
 *       {@link AttachMode#HALO}, and {@link AttachMode#FOLLOW}.</li>
 *   <li>Overburdened slots {@code 4..9} ("items outside the four") always use tail-following behind the
 *       player unit's tail, and every tail-attached item renders a {@code LineMarker} connection tether
 *       between the player unit and the item.</li>
 * </ul>
 */
public final class WorldItemLogicManager {

    public static final int MARKER_BASE_ID = 10_000;
    public static final int MARKERS_PER_SLOT = 64;
    public static final int MAX_PROCESSOR_SLOTS = 64;
    /** Dedicated non-derelict system team so WorldProcessors have enabled=true & efficiency=1.0 on clients. */
    public static final Team PROCESSOR_TEAM = Team.get(254);

    public enum RenderedKind {
        MOUNT,
        SUBWEAPON,
        HALO,
        TAIL_FOLLOW
    }

    /** Metadata describing one client-drawn visual element compiled into a player's WorldProcessor. */
    public record CompiledVisualEntry(
        ShopItemDef def,
        int inventorySlot,
        RenderedKind kind,
        boolean overloaded,
        int primaryMarkerId,
        int connectionLineMarkerId,
        float localForwardWorld,
        float localSideWorld,
        float angleOffsetDeg
    ) {}

    private static final class ProcessorSlot {
        final int slotIndex;
        final int baseMarkerId;
        int tilePos = -1;
        @Nullable String ownerKey;
        String compiledSignature = "";
        int boundUnitId = -1;
        int syncTickCounter = 0;
        final Seq<CompiledVisualEntry> entries = new Seq<>();
        String lastMlogCode = "";

        ProcessorSlot(int slotIndex) {
            this.slotIndex = slotIndex;
            this.baseMarkerId = MARKER_BASE_ID + slotIndex * MARKERS_PER_SLOT;
        }
    }

    private final ObjectMap<String, ProcessorSlot> slotsByOwner = new ObjectMap<>();
    private final Seq<ProcessorSlot> allSlots = new Seq<>(MAX_PROCESSOR_SLOTS);
    private final IntSet managedProcessorPositions = new IntSet();
    private boolean started = false;

    public WorldItemLogicManager() {
        for (int i = 0; i < MAX_PROCESSOR_SLOTS; i++) {
            allSlots.add(new ProcessorSlot(i));
        }
    }

    public void start() {
        if (started) return;
        started = true;

        if (netServer != null && netServer.admins != null) {
            netServer.admins.addActionFilter(action -> {
                if (action == null || action.tile == null) return true;
                if (managedProcessorPositions.contains(action.tile.pos())
                    || action.tile.block() == Blocks.worldProcessor
                    || (action.tile.build != null && action.tile.build.block == Blocks.worldProcessor)) {
                    if (action.type == ActionType.configure
                        || action.type == ActionType.breakBlock
                        || action.type == ActionType.control
                        || action.type == ActionType.depositItem
                        || action.type == ActionType.withdrawItem) {
                        return false;
                    }
                }
                return true;
            });
        }

        Events.on(WorldLoadEvent.class, e -> resetForNewWorld());
    }

    public void resetForNewWorld() {
        slotsByOwner.clear();
        managedProcessorPositions.clear();
        for (int i = 0; i < allSlots.size; i++) {
            ProcessorSlot slot = allSlots.get(i);
            slot.tilePos = -1;
            slot.ownerKey = null;
            slot.compiledSignature = "";
            slot.boundUnitId = -1;
            slot.syncTickCounter = 0;
            slot.entries.clear();
            slot.lastMlogCode = "";
        }
        if (state != null && state.rules != null) {
            state.rules.allowEditWorldProcessors = false;
        }
    }

    public void syncRig(
        String ownerKey,
        Unit unit,
        Seq<String> equippedIds,
        Catalog catalog
    ) {
        if (ownerKey == null || unit == null || unit.dead() || !unit.isValid()) {
            clearRig(ownerKey);
            return;
        }
        if (equippedIds == null || equippedIds.isEmpty()) {
            clearRig(ownerKey);
            return;
        }

        ensureWorldProcessorsNonEditableByDefault();

        ProcessorSlot slot = obtainSlot(ownerKey);
        if (slot == null) return;

        LogicBuild procBuild = ensureProcessorPlaced(slot);
        if (procBuild == null) return;

        String signature = buildRigSignature(unit, equippedIds);
        boolean codeChanged = !signature.equals(slot.compiledSignature)
            || procBuild.code == null
            || procBuild.code.isEmpty();

        if (codeChanged) {
            slot.entries.clear();
            String mlog = compileRigMlog(slot.baseMarkerId, unit, equippedIds, catalog, slot.entries);
            slot.compiledSignature = signature;
            slot.lastMlogCode = mlog;
            slot.boundUnitId = unit.id;
            slot.syncTickCounter = 0;
            pushCodeAndUnit(procBuild, mlog, unit);
            return;
        }

        bindHostUnitOnServer(procBuild, unit);
        if (slot.boundUnitId != unit.id) {
            slot.boundUnitId = unit.id;
            slot.syncTickCounter = 0;
            broadcastHostUnitSync(procBuild, unit);
        } else {
            slot.syncTickCounter++;
            if (slot.syncTickCounter >= 30) {
                slot.syncTickCounter = 0;
                broadcastHostUnitSync(procBuild, unit);
            }
        }
    }

    public void clearRig(@Nullable String ownerKey) {
        if (ownerKey == null) return;
        ProcessorSlot slot = slotsByOwner.remove(ownerKey);
        if (slot == null) return;

        LogicBuild procBuild = getExistingProcessor(slot);
        String clearMlog = compileClearMlog(slot.baseMarkerId);
        slot.ownerKey = null;
        slot.compiledSignature = "";
        slot.boundUnitId = -1;
        slot.syncTickCounter = 0;
        slot.entries.clear();
        slot.lastMlogCode = clearMlog;

        // Immediately purge markers in server/local state.markers as well
        if (state != null && state.markers != null) {
            for (int id = slot.baseMarkerId; id < slot.baseMarkerId + MARKERS_PER_SLOT; id++) {
                state.markers.remove(id);
            }
        }

        if (procBuild != null) {
            pushCodeAndUnit(procBuild, clearMlog, null);
        }
    }

    public void clearAllRigs() {
        Seq<String> keys = slotsByOwner.keys().toSeq();
        for (int i = 0; i < keys.size; i++) {
            clearRig(keys.get(i));
        }
    }

    public Seq<CompiledVisualEntry> getCompiledEntries(String ownerKey) {
        Seq<CompiledVisualEntry> copy = new Seq<>();
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        if (slot != null) {
            copy.addAll(slot.entries);
        }
        return copy;
    }

    public @Nullable LogicBuild getProcessorForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? null : getExistingProcessor(slot);
    }

    public @Nullable String getLastMlogForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? null : slot.lastMlogCode;
    }

    public int getBaseMarkerIdForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? -1 : slot.baseMarkerId;
    }

    // ------------------------------------------------------------------ WorldProcessor placement & live sync

    private static void ensureWorldProcessorsNonEditableByDefault() {
        if (state != null && state.rules != null && state.rules.allowEditWorldProcessors) {
            state.rules.allowEditWorldProcessors = false;
        }
    }

    private @Nullable ProcessorSlot obtainSlot(String ownerKey) {
        ProcessorSlot existing = slotsByOwner.get(ownerKey);
        if (existing != null) return existing;

        for (int i = 0; i < allSlots.size; i++) {
            ProcessorSlot candidate = allSlots.get(i);
            if (candidate.ownerKey == null) {
                candidate.ownerKey = ownerKey;
                slotsByOwner.put(ownerKey, candidate);
                return candidate;
            }
        }
        Log.warn("[CoolPlugin] WorldItemLogicManager exhausted all @ processor slots!", MAX_PROCESSOR_SLOTS);
        return null;
    }

    private @Nullable LogicBuild getExistingProcessor(ProcessorSlot slot) {
        if (world == null || slot.tilePos == -1) return null;
        Tile tile = world.tile(slot.tilePos);
        if (tile != null && tile.build instanceof LogicBuild lb && lb.block == Blocks.worldProcessor) {
            return lb;
        }
        return null;
    }

    private @Nullable LogicBuild ensureProcessorPlaced(ProcessorSlot slot) {
        LogicBuild existing = getExistingProcessor(slot);
        if (existing != null) return existing;

        Tile chosen = findAvailableBorderTile();
        if (chosen == null) return null;

        int pos = chosen.pos();
        slot.tilePos = pos;
        managedProcessorPositions.add(pos);

        Call.setTile(chosen, Blocks.worldProcessor, PROCESSOR_TEAM, 0);
        if (chosen.build instanceof LogicBuild lb) {
            return lb;
        }
        return null;
    }

    private @Nullable Tile findAvailableBorderTile() {
        if (world == null || world.width() <= 0 || world.height() <= 0) return null;
        int w = world.width();
        int h = world.height();

        // Pass 1: clean air border tiles away from buildings and custom spawnpoint overlays
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x > 1 && x < w - 2 && y > 1 && y < h - 2) {
                    x = w - 3;
                    continue;
                }
                Tile t = world.tile(x, y);
                if (isIdealProcessorTile(t, true)) {
                    return t;
                }
            }
        }

        // Pass 2: any air tile on the map without a building or spawnpoint overlay
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Tile t = world.tile(x, y);
                if (isIdealProcessorTile(t, true)) {
                    return t;
                }
            }
        }

        // Pass 3: border tile even if covered by static terrain wall (never overwrite active buildings)
        for (int x = 0; x < w; x++) {
            Tile t = world.tile(x, 0);
            if (isIdealProcessorTile(t, false)) {
                return t;
            }
        }
        return null;
    }

    private boolean isIdealProcessorTile(@Nullable Tile t, boolean requireAirBlock) {
        if (t == null || t.build != null) return false;
        if (managedProcessorPositions.contains(t.pos())) return false;
        if (requireAirBlock && t.block() != Blocks.air) return false;
        Block overlay = t.overlay();
        if (overlay != null && overlay.name != null && overlay.name.contains("spawnpoint")) {
            return false;
        }
        Block floor = t.floor();
        if (floor != null && floor.name != null && floor.name.contains("spawnpoint")) {
            return false;
        }
        return true;
    }

    private static void pushCodeAndUnit(LogicBuild procBuild, String mlogCode, @Nullable Unit unit) {
        procBuild.ipt = 1000;
        procBuild.updateCode(mlogCode, false, null);
        LVar hostVar = bindHostUnitOnServer(procBuild, unit);

        // Charge accumulator & execute initial pass via update() (which sets efficiency=1f -> edelta()>0)
        if (state != null && state.isGame() && !state.isPaused()) {
            float prevDelta = arc.util.Time.delta;
            if (prevDelta <= 0f) arc.util.Time.delta = 1f;
            procBuild.update();
            bindHostUnitOnServer(procBuild, unit);
            procBuild.update();
            bindHostUnitOnServer(procBuild, unit);
            arc.util.Time.delta = prevDelta;
        }

        if (Vars.net != null && Vars.net.server() && Groups.player != null && !Groups.player.isEmpty()) {
            byte[] compressed = LogicBlock.compress(mlogCode, procBuild.relativeConnections());
            boolean prevAllow = state != null && state.rules != null && state.rules.allowEditWorldProcessors;
            if (!prevAllow && state != null && state.rules != null) {
                state.rules.allowEditWorldProcessors = true;
                Call.setRules(state.rules);
            }
            Call.tileConfig(null, procBuild, compressed);
            if (!prevAllow && state != null && state.rules != null) {
                state.rules.allowEditWorldProcessors = false;
                Call.setRules(state.rules);
            }
            if (hostVar != null) {
                Call.syncVariable(procBuild, hostVar.id, unit);
            }
        }
    }

    private static @Nullable LVar bindHostUnitOnServer(LogicBuild procBuild, @Nullable Unit unit) {
        if (procBuild == null || procBuild.executor == null) return null;
        LVar hostVar = procBuild.executor.optionalVar("hostUnit");
        if (hostVar != null && !hostVar.constant) {
            hostVar.isobj = true;
            hostVar.objval = unit;
        }
        return hostVar;
    }

    private static void broadcastHostUnitSync(LogicBuild procBuild, @Nullable Unit unit) {
        if (Vars.net == null || !Vars.net.server() || Groups.player == null || Groups.player.isEmpty()) return;
        LVar hostVar = bindHostUnitOnServer(procBuild, unit);
        if (hostVar != null) {
            Call.syncVariable(procBuild, hostVar.id, unit);
        }
    }

    // ------------------------------------------------------------------ MLOG Compiler

    public static String compileClearMlog(int baseMarkerId) {
        int endId = baseMarkerId + MARKERS_PER_SLOT;
        return "setrate 1000\n"
            + "jump idleDone equal inited 1\n"
            + "set cid " + baseMarkerId + "\n"
            + "sweepLoop:\n"
            + "setmarker remove cid 0 0 0\n"
            + "op add cid cid 1\n"
            + "jump sweepLoop lessThan cid " + endId + "\n"
            + "set inited 1\n"
            + "idleDone:\n"
            + "stop\n";
    }

    public static String compileRigMlog(
        int baseMarkerId,
        Unit unit,
        Seq<String> equippedIds,
        Catalog catalog,
        Seq<CompiledVisualEntry> outEntries
    ) {
        outEntries.clear();
        int n = Math.min(PlayerItemSystem.MAX_EQUIPPED_SLOTS, equippedIds.size);
        float hullRadius = Math.max(4f, unit.hitSize * 0.5f);
        float spriteTiles = Mathf.clamp((5.4f + unit.hitSize * 0.14f) / tilesize, 0.72f, 1.32f);

        // Count total tail-following items:
        // - Active FOLLOW items in slots 0..3
        // - ALL overburdened items in slots 4..9 ("items outside the four should be always tail following")
        int tailTotal = 0;
        for (int i = 0; i < n; i++) {
            ShopItemDef def = catalog.findItem(equippedIds.get(i));
            if (def == null) continue;
            boolean overloaded = i >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            if (overloaded || def.attachMode == AttachMode.FOLLOW) {
                tailTotal++;
            }
        }

        int nextMarkerOffset = 0;
        int tailIndex = 0;
        boolean needsAimSensors = false;
        ObjectIntMap<MountSubtype> subtypeCounts = new ObjectIntMap<>();

        for (int i = 0; i < n; i++) {
            ShopItemDef def = catalog.findItem(equippedIds.get(i));
            if (def == null) continue;

            boolean overloaded = i >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            // Items outside the first 4 slots ALWAYS become tail-following with connection lines!
            if (overloaded || def.attachMode == AttachMode.FOLLOW) {
                if (nextMarkerOffset + 2 > MARKERS_PER_SLOT) break;
                int lineId = baseMarkerId + (nextMarkerOffset++);
                int texId = baseMarkerId + (nextMarkerOffset++);

                int myTailIdx = tailIndex++;
                float relAngle = computeTailFormationAngle(myTailIdx, tailTotal);
                float standoffWorld = hullRadius + Math.max(11.5f, def.followDistance) + (myTailIdx / 2) * 7.5f;
                float localFwd = -standoffWorld * Mathf.cosDeg(relAngle);
                float localSide = -standoffWorld * Mathf.sinDeg(relAngle);

                outEntries.add(new CompiledVisualEntry(
                    def, i, RenderedKind.TAIL_FOLLOW, overloaded, texId, lineId, localFwd, localSide, 0f
                ));
                continue;
            }

            switch (def.attachMode) {
                case MOUNT, SUBWEAPON -> {
                    RenderedKind kind = (def.attachMode == AttachMode.SUBWEAPON)
                        ? RenderedKind.SUBWEAPON
                        : RenderedKind.MOUNT;
                    if (kind == RenderedKind.SUBWEAPON) {
                        needsAimSensors = true;
                    }

                    int repeatIdx = subtypeCounts.get(def.mountSubtype, 0);
                    subtypeCounts.put(def.mountSubtype, repeatIdx + 1);

                    float baseForward = def.computeForwardOffset(hullRadius);
                    float absSide = Math.abs(def.computeSideOffset(hullRadius));
                    float angleOff = def.effectiveMountAngleOffset();

                    if (def.isSymmetricallyDuplicated(hullRadius)) {
                        if (nextMarkerOffset + 2 > MARKERS_PER_SLOT) break;
                        float fwd = baseForward - repeatIdx * 2.6f;
                        float sideMag = absSide + repeatIdx * 1.6f;

                        int leftId = baseMarkerId + (nextMarkerOffset++);
                        int rightId = baseMarkerId + (nextMarkerOffset++);
                        outEntries.add(new CompiledVisualEntry(
                            def, i, kind, false, leftId, -1, fwd, +sideMag, +angleOff
                        ));
                        outEntries.add(new CompiledVisualEntry(
                            def, i, kind, false, rightId, -1, fwd, -sideMag, -angleOff
                        ));
                    } else {
                        if (nextMarkerOffset + 1 > MARKERS_PER_SLOT) break;
                        float fwd = baseForward - repeatIdx * 3.0f;
                        int centerId = baseMarkerId + (nextMarkerOffset++);
                        outEntries.add(new CompiledVisualEntry(
                            def, i, kind, false, centerId, -1, fwd, 0f, angleOff
                        ));
                    }
                }
                case HALO -> {
                    Seq<Point2f> anchors = computeUnitLocalHaloOffsets(unit, def.haloAnchor);
                    for (int a = 0; a < anchors.size; a++) {
                        if (nextMarkerOffset + 1 > MARKERS_PER_SLOT) break;
                        Point2f pt = anchors.get(a);
                        int haloId = baseMarkerId + (nextMarkerOffset++);
                        outEntries.add(new CompiledVisualEntry(
                            def, i, RenderedKind.HALO, false, haloId, -1, pt.fwd, pt.side, a * 30f
                        ));
                    }
                }
                default -> {
                }
            }
        }

        if (outEntries.isEmpty()) {
            return compileClearMlog(baseMarkerId);
        }

        int endSweepId = baseMarkerId + MARKERS_PER_SLOT;
        StringBuilder sb = new StringBuilder(4096);

        sb.append("setrate 1000\n");
        sb.append("jump mainLoop equal inited 1\n");

        // 1. One-time sweep of ALL 64 marker IDs in this player's block so reordering / Top NEVER leaves duplicates
        sb.append("set cid ").append(baseMarkerId).append("\n");
        sb.append("sweepLoop:\n");
        sb.append("setmarker remove cid 0 0 0\n");
        sb.append("op add cid cid 1\n");
        sb.append("jump sweepLoop lessThan cid ").append(endSweepId).append("\n");

        // 2. One-time marker creation & static appearance setup
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            switch (ce.kind) {
                case MOUNT, SUBWEAPON -> {
                    float sz = ce.kind == RenderedKind.SUBWEAPON ? spriteTiles * 1.04f : spriteTiles;
                    sb.append("makemarker texture ").append(ce.primaryMarkerId).append(" 0 0 true\n");
                    sb.append("setmarker texture ").append(ce.primaryMarkerId).append(" 0 \"")
                        .append(ce.def.worldTextureName()).append("\" 0\n");
                    sb.append("setmarker textureSize ").append(ce.primaryMarkerId).append(" ")
                        .append(f(sz)).append(" ").append(f(sz)).append(" 0\n");
                    sb.append("setmarker minimap ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                }
                case HALO -> {
                    Color hc = ce.def.resolveHaloColor();
                    sb.append("makemarker shape ").append(ce.primaryMarkerId).append(" 0 0 true\n");
                    sb.append("setmarker shape ").append(ce.primaryMarkerId).append(" ")
                        .append(Math.max(3, ce.def.haloSides)).append(" 0 0\n");
                    sb.append("setmarker radius ").append(ce.primaryMarkerId).append(" ")
                        .append(f(ce.def.haloRadius)).append(" 0 0\n");
                    sb.append("setmarker stroke ").append(ce.primaryMarkerId).append(" 0.85 0 0\n");
                    sb.append("packcolor hcol ").append(f(hc.r)).append(" ").append(f(hc.g)).append(" ")
                        .append(f(hc.b)).append(" 0.90\n");
                    sb.append("setmarker color ").append(ce.primaryMarkerId).append(" hcol 0 0\n");
                    sb.append("setmarker minimap ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                }
                case TAIL_FOLLOW -> {
                    Color lineCol = ce.overloaded
                        ? Color.valueOf("ff6b6b")
                        : ce.def.resolveHaloColor();
                    float lineAlpha = ce.overloaded ? 0.88f : 0.82f;
                    float tailSpriteTiles = ce.overloaded ? spriteTiles * 0.92f : spriteTiles;

                    // Connection line marker between player unit tail and tail-attached item
                    sb.append("makemarker line ").append(ce.connectionLineMarkerId).append(" 0 0 true\n");
                    sb.append("setmarker stroke ").append(ce.connectionLineMarkerId).append(" 0.72 0 0\n");
                    sb.append("setmarker outline ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
                    sb.append("packcolor lcol ").append(f(lineCol.r)).append(" ").append(f(lineCol.g)).append(" ")
                        .append(f(lineCol.b)).append(" ").append(f(lineAlpha)).append("\n");
                    sb.append("setmarker color ").append(ce.connectionLineMarkerId).append(" lcol 0 0\n");
                    sb.append("setmarker minimap ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");

                    // Tail-following item texture marker
                    sb.append("makemarker texture ").append(ce.primaryMarkerId).append(" 0 0 true\n");
                    sb.append("setmarker texture ").append(ce.primaryMarkerId).append(" 0 \"")
                        .append(ce.def.worldTextureName()).append("\" 0\n");
                    sb.append("setmarker textureSize ").append(ce.primaryMarkerId).append(" ")
                        .append(f(tailSpriteTiles)).append(" ").append(f(tailSpriteTiles)).append(" 0\n");
                    sb.append("setmarker minimap ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                }
            }
        }
        sb.append("set vis 0\n");
        sb.append("set inited 1\n");

        // 3. 60-FPS per-frame local tracking loop (0 ms ping on each client!)
        sb.append("mainLoop:\n");
        sb.append("sync hostUnit\n");
        sb.append("sensor dead hostUnit @dead\n");
        sb.append("jump hideAll notEqual dead 0\n");

        sb.append("sensor ux hostUnit @x\n");
        sb.append("sensor uy hostUnit @y\n");
        sb.append("sensor urot hostUnit @rotation\n");
        sb.append("sensor flying hostUnit @flying\n");
        if (needsAimSensors) {
            sb.append("sensor sx hostUnit @shootX\n");
            sb.append("sensor sy hostUnit @shootY\n");
        }

        sb.append("set baseLayer 60.45\n");
        sb.append("jump layerReady equal flying 0\n");
        sb.append("set baseLayer 115.45\n");
        sb.append("layerReady:\n");
        sb.append("op sub underLayer baseLayer 0.80\n");

        sb.append("op cos ucos urot 0\n");
        sb.append("op sin usin urot 0\n");

        if (tailTotal > 0) {
            float tailAnchorTiles = (hullRadius * 0.68f) / tilesize;
            sb.append("op mul tax ").append(f(tailAnchorTiles)).append(" ucos\n");
            sb.append("op mul tay ").append(f(tailAnchorTiles)).append(" usin\n");
            sb.append("op sub anchorX ux tax\n");
            sb.append("op sub anchorY uy tay\n");
        }

        // Reveal markers & snap tail items onto formation on first visible frame
        sb.append("jump skipReveal equal vis 1\n");
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            if (ce.connectionLineMarkerId != -1) {
                sb.append("setmarker world ").append(ce.connectionLineMarkerId).append(" 1 0 0\n");
            }
            sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 1 0 0\n");
            if (ce.kind == RenderedKind.TAIL_FOLLOW) {
                float fwdT = ce.localForwardWorld / tilesize;
                float sideT = ce.localSideWorld / tilesize;
                sb.append("op mul t1 ").append(f(fwdT)).append(" ucos\n");
                sb.append("op mul t2 ").append(f(sideT)).append(" usin\n");
                sb.append("op sub tx").append(e).append(" t1 t2\n");
                sb.append("op add tx").append(e).append(" ux tx").append(e).append("\n");
                sb.append("op mul t1 ").append(f(fwdT)).append(" usin\n");
                sb.append("op mul t2 ").append(f(sideT)).append(" ucos\n");
                sb.append("op add ty").append(e).append(" t1 t2\n");
                sb.append("op add ty").append(e).append(" uy ty").append(e).append("\n");
            }
        }
        sb.append("set vis 1\n");
        sb.append("skipReveal:\n");

        // Update every compiled visual entry at 60 FPS
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            float fwdT = ce.localForwardWorld / tilesize;
            float sideT = ce.localSideWorld / tilesize;

            switch (ce.kind) {
                case MOUNT -> {
                    emitRigidTransform(sb, fwdT, sideT, "mx", "my");
                    float rotDelta = ce.angleOffsetDeg - 90f;
                    sb.append("op add mrot urot ").append(f(rotDelta)).append("\n");
                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" mx my 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" mrot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" baseLayer 0 0\n");
                }
                case SUBWEAPON -> {
                    emitRigidTransform(sb, fwdT, sideT, "mx", "my");
                    sb.append("op sub adx sx mx\n");
                    sb.append("op sub ady sy my\n");
                    sb.append("op len alen adx ady\n");
                    sb.append("op add mrot urot ").append(f(ce.angleOffsetDeg - 90f)).append("\n");
                    sb.append("jump swAimDone").append(e).append(" lessThan alen 0.25\n");
                    sb.append("op angle arot adx ady\n");
                    sb.append("op sub mrot arot 90\n");
                    sb.append("swAimDone").append(e).append(":\n");
                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" mx my 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" mrot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" baseLayer 0 0\n");
                }
                case HALO -> {
                    emitRigidTransform(sb, fwdT, sideT, "mx", "my");
                    float spinSpeed = (e % 2 == 0) ? 0.055f : -0.055f;
                    sb.append("op mul hrot @time ").append(f(spinSpeed)).append("\n");
                    sb.append("op add hrot hrot ").append(f(ce.angleOffsetDeg)).append("\n");
                    sb.append("op add hLayer baseLayer 0.30\n");
                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" mx my 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" hrot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" hLayer 0 0\n");
                }
                case TAIL_FOLLOW -> {
                    emitRigidTransform(sb, fwdT, sideT, "gx", "gy");
                    String tx = "tx" + e;
                    String ty = "ty" + e;
                    sb.append("op sub dx gx ").append(tx).append("\n");
                    sb.append("op sub dy gy ").append(ty).append("\n");
                    sb.append("op len d dx dy\n");
                    sb.append("jump tSnap").append(e).append(" greaterThan d 7.5\n");
                    sb.append("op mul dx dx 0.18\n");
                    sb.append("op mul dy dy 0.18\n");
                    sb.append("op add ").append(tx).append(" ").append(tx).append(" dx\n");
                    sb.append("op add ").append(ty).append(" ").append(ty).append(" dy\n");
                    sb.append("jump tMoved").append(e).append(" always 0 0\n");
                    sb.append("tSnap").append(e).append(":\n");
                    sb.append("set ").append(tx).append(" gx\n");
                    sb.append("set ").append(ty).append(" gy\n");
                    sb.append("tMoved").append(e).append(":\n");

                    // Keep minimum standoff from unit center so towed items never collapse onto the hull
                    float minStandTiles = Math.max(0.95f, (hullRadius + 5.5f) / tilesize);
                    sb.append("op sub udx ").append(tx).append(" ux\n");
                    sb.append("op sub udy ").append(ty).append(" uy\n");
                    sb.append("op len ud udx udy\n");
                    sb.append("jump tClampDone").append(e).append(" greaterThanEq ud ").append(f(minStandTiles)).append("\n");
                    sb.append("jump tClampDone").append(e).append(" lessThan ud 0.01\n");
                    sb.append("op div scl ").append(f(minStandTiles)).append(" ud\n");
                    sb.append("op mul udx udx scl\n");
                    sb.append("op mul udy udy scl\n");
                    sb.append("op add ").append(tx).append(" ux udx\n");
                    sb.append("op add ").append(ty).append(" uy udy\n");
                    sb.append("tClampDone").append(e).append(":\n");

                    // Orient towed item along its connection tether toward the player unit's tail anchor
                    sb.append("op sub ldx anchorX ").append(tx).append("\n");
                    sb.append("op sub ldy anchorY ").append(ty).append("\n");
                    sb.append("op angle trot ldx ldy\n");
                    sb.append("op sub trot trot 90\n");

                    // Update connection line (player unit tail -> tail-attached item) & item sprite
                    sb.append("setmarker pos ").append(ce.connectionLineMarkerId).append(" anchorX anchorY 0\n");
                    sb.append("setmarker endPos ").append(ce.connectionLineMarkerId).append(" ")
                        .append(tx).append(" ").append(ty).append(" 0\n");
                    sb.append("setmarker drawLayer ").append(ce.connectionLineMarkerId).append(" underLayer 0 0\n");

                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" ")
                        .append(tx).append(" ").append(ty).append(" 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" trot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" baseLayer 0 0\n");
                }
            }
        }

        sb.append("jump waitTick always 0 0\n");

        // Hide markers cleanly whenever hostUnit is temporarily null/dead/in payload
        sb.append("hideAll:\n");
        sb.append("jump waitTick equal vis 0\n");
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            if (ce.connectionLineMarkerId != -1) {
                sb.append("setmarker world ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
            }
            sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
        }
        sb.append("set vis 0\n");

        sb.append("waitTick:\n");
        sb.append("wait 0\n");
        sb.append("jump mainLoop always 0 0\n");

        return sb.toString();
    }

    private static void emitRigidTransform(StringBuilder sb, float fwdTiles, float sideTiles, String outX, String outY) {
        if (Math.abs(fwdTiles) < 0.0001f && Math.abs(sideTiles) < 0.0001f) {
            sb.append("set ").append(outX).append(" ux\n");
            sb.append("set ").append(outY).append(" uy\n");
            return;
        }
        sb.append("op mul t1 ").append(f(fwdTiles)).append(" ucos\n");
        sb.append("op mul t2 ").append(f(sideTiles)).append(" usin\n");
        sb.append("op sub ").append(outX).append(" t1 t2\n");
        sb.append("op add ").append(outX).append(" ux ").append(outX).append("\n");
        sb.append("op mul t1 ").append(f(fwdTiles)).append(" usin\n");
        sb.append("op mul t2 ").append(f(sideTiles)).append(" ucos\n");
        sb.append("op add ").append(outY).append(" t1 t2\n");
        sb.append("op add ").append(outY).append(" uy ").append(outY).append("\n");
    }

    /** Symmetric rear-wake angle in degrees (clamped to [-64, +64] so all towed items fan cleanly behind the tail). */
    public static float computeTailFormationAngle(int index, int total) {
        if (total <= 1) return 0f;
        float raw;
        if (total % 2 == 1) {
            if (index == 0) return 0f;
            int pair = (index + 1) / 2;
            float sign = (index % 2 == 1) ? 1f : -1f;
            raw = sign * pair * 23f;
        } else {
            int pair = index / 2;
            float sign = (index % 2 == 0) ? 1f : -1f;
            raw = sign * (17f + pair * 21f);
        }
        return Mathf.clamp(raw, -64f, 64f);
    }

    private record Point2f(float fwd, float side) {}

    private static Seq<Point2f> computeUnitLocalHaloOffsets(Unit unit, HaloAnchor anchor) {
        Seq<Point2f> out = new Seq<>();
        if (unit == null) {
            out.add(new Point2f(0f, 0f));
            return out;
        }

        switch (anchor) {
            case WEAPONS -> {
                if (unit.mounts != null && unit.mounts.length > 0) {
                    for (WeaponMount m : unit.mounts) {
                        if (m == null || m.weapon == null) continue;
                        Weapon w = m.weapon;
                        out.add(new Point2f(w.y, -w.x));
                    }
                }
                if (out.isEmpty()) {
                    out.add(new Point2f(0f, 0f));
                }
            }
            case ENGINES -> {
                if (unit.type != null && unit.type.engines != null && !unit.type.engines.isEmpty()) {
                    for (UnitEngine eng : unit.type.engines) {
                        out.add(new Point2f(eng.y, -eng.x));
                    }
                }
                if (out.isEmpty() && unit.type != null && unit.type.engineSize > 0f) {
                    out.add(new Point2f(-unit.type.engineOffset, 0f));
                }
                if (out.isEmpty()) {
                    out.add(new Point2f(-Math.max(3f, unit.hitSize * 0.36f), 0f));
                }
            }
            case CENTER -> out.add(new Point2f(0f, 0f));
        }
        return out;
    }

    private static String buildRigSignature(Unit unit, Seq<String> equippedIds) {
        StringBuilder sb = new StringBuilder(equippedIds.size * 8 + 24);
        sb.append(unit.type != null ? unit.type.name : "unit").append('#');
        sb.append(Math.round(unit.hitSize * 10f)).append(':');
        for (int i = 0; i < equippedIds.size; i++) {
            if (i > 0) sb.append('|');
            sb.append(equippedIds.get(i));
        }
        return sb.toString();
    }

    private static String f(float val) {
        if (Math.abs(val) < 0.00005f) return "0";
        String s = String.format(Locale.ROOT, "%.4f", val);
        int dot = s.indexOf('.');
        if (dot >= 0) {
            int end = s.length();
            while (end > dot + 1 && s.charAt(end - 1) == '0') {
                end--;
            }
            if (end == dot + 1) end = dot;
            return s.substring(0, end);
        }
        return s;
    }
}
