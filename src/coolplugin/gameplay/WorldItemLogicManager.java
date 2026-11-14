package coolplugin.gameplay;

import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import coolplugin.gameplay.BuildSystem.DestroyedCoreSite;
import coolplugin.gameplay.ShopConfigIO.Catalog;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.HaloAnchor;
import coolplugin.gameplay.ShopItemDef.MountSubtype;
import coolplugin.techtree.TechTreeService;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.WeaponMount;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
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
 *   <li>Processors are placed on top-right border tiles strictly outside {@link BuildSystem#inReserved}
 *       and core sites so client-only core refreshes ({@code CORE_REFRESH_INTERVAL = 300f}) never overwrite
 *       any client {@code WorldProcessor}.</li>
 *   <li>Active slots {@code 0..3} render {@link AttachMode#MOUNT}, {@link AttachMode#SUBWEAPON},
 *       {@link AttachMode#HALO}, and {@link AttachMode#FOLLOW}.</li>
 *   <li>Overburdened slots {@code 4..9} ("items outside the four") always use tail-following behind the
 *       player unit's tail, and every line-connected item runs genuine 2D spring-rope + momentum/inertia +
 *       slack/taut + pairwise collision/jostle + hull-deflection follow physics with a {@code LineMarker}
 *       connection tether.</li>
 * </ul>
 */
public final class WorldItemLogicManager {

    public static final int MARKER_BASE_ID = 10_000;
    public static final int MARKERS_PER_SLOT = 128;
    public static final int MAX_PROCESSOR_SLOTS = 250;
    public static final int MAX_PROGRAM_INSTRUCTIONS=500, MAX_PROCESSORS_PER_PLAYER=10;
    /** Dedicated non-derelict system team so WorldProcessors have enabled=true & efficiency=1.0 on clients. */
    public static final Team PROCESSOR_TEAM = Team.get(254);

    public enum RenderedKind {
        MOUNT,
        SUBWEAPON,
        HALO,
        TAIL_FOLLOW
    }

    /** Metadata for a client-drawn element. connectionLineMarkerId is the secondary
     * shape ID for HALO entries and the rope ID for tails. */
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
        int initialSyncBurstsRemaining = 0;
        final Seq<CompiledVisualEntry> entries = new Seq<>();
        final Seq<ProcessorSlot> children = new Seq<>();
        String lastMlogCode = "";

        ProcessorSlot(int slotIndex) {
            this.slotIndex = slotIndex;
            this.baseMarkerId = MARKER_BASE_ID + slotIndex * MARKERS_PER_SLOT;
        }
    }

    private final @Nullable BuildSystem buildSystem;
    private final ObjectMap<String, ProcessorSlot> slotsByOwner = new ObjectMap<>();
    private final Seq<ProcessorSlot> allSlots = new Seq<>(MAX_PROCESSOR_SLOTS);
    private final IntSet managedProcessorPositions = new IntSet();
    private boolean started = false;

    public WorldItemLogicManager() {
        this(null);
    }

    public WorldItemLogicManager(@Nullable BuildSystem buildSystem) {
        this.buildSystem = buildSystem;
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
        Events.on(mindustry.game.EventType.PlayerConnect.class,e->{
            if(e.player!=null&&obtainSlot(e.player.uuid())==null&&e.player.con!=null)
                e.player.con.kick("Server visual processor capacity reached (250). Try again when a slot is free.");
        });
        Events.on(mindustry.game.EventType.PlayerConnectionConfirmed.class,e->{
            if(e.player!=null&&obtainSlot(e.player.uuid())==null)rejectRig(e.player.uuid(),"Server visual processor capacity reached (250). Try again when a slot is free.");
        });
        Events.on(mindustry.game.EventType.PlayerLeave.class,e->{if(e.player!=null)clearRig(e.player.uuid());});
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
            slot.initialSyncBurstsRemaining = 0;
            slot.entries.clear();slot.children.clear();
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
        syncRig(ownerKey, null, unit, equippedIds, catalog);
    }

    public void syncRig(
        String ownerKey,
        @Nullable String ownerPlayerName,
        Unit unit,
        Seq<String> equippedIds,
        Catalog catalog
    ) {
        try{syncRigChecked(ownerKey,ownerPlayerName,unit,equippedIds,catalog);}
        catch(RuntimeException failure){
            Log.err("[CoolPlugin] Unable to compile/allocate visual rig for "+ownerKey,failure);
            if(ownerKey!=null)rejectRig(ownerKey,"Unable to allocate safe visual processors for your equipment (500 instructions, 10 per player).");
        }
    }
    private void syncRigChecked(String ownerKey,String ownerPlayerName,Unit unit,Seq<String> equippedIds,Catalog catalog){
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
        if (slot == null){rejectRig(ownerKey,"Server visual processor capacity reached (250).");return;}

        LogicBuild procBuild = ensureProcessorPlaced(slot);
        if (procBuild == null){rejectRig(ownerKey,"No safe map tile available for visual processors.");return;}
        String signature = buildRigSignature(unit, ownerPlayerName, equippedIds);
        for(String id:equippedIds){var def=catalog.findItem(id);if(def!=null)signature+="/"+def.subweaponRecoilTime+"/"+def.subweaponRecoil+"/"+def.subweaponShootEffect+"/"+def.mountWeaponSprite;}
        boolean codeChanged = !signature.equals(slot.compiledSignature)
            || procBuild.code == null || procBuild.code.isEmpty();
        for(ProcessorSlot child:slot.children)if(getExistingProcessor(child)==null)codeChanged=true;
        if (codeChanged) {
            Seq<CompiledVisualEntry> probe=new Seq<>();
            String whole=compileRigShard(slot.baseMarkerId,ownerPlayerName,unit,equippedIds,catalog,probe,-1);
            Seq<Integer> groups=new Seq<>();
            if(instructionCount(whole)<=MAX_PROGRAM_INSTRUCTIONS)groups.add(-1);
            else for(int group=0;group<PlayerItemSystem.MAX_EQUIPPED_SLOTS;group++){
                final int g=group;if(probe.contains(e->e.inventorySlot==g))groups.add(group);
            }
            int required=Math.max(1,groups.size);
            if(required>MAX_PROCESSORS_PER_PLAYER){rejectRig(ownerKey,"Item visuals need more than 10 processors. Reduce equipped visual items.");return;}
            int freeCount=0;for(ProcessorSlot candidate:allSlots)if(candidate.ownerKey==null)freeCount++;
            if(required-1-slot.children.size>freeCount){rejectRig(ownerKey,"Server visual processor capacity reached (250). Cannot allocate your item visuals.");return;}
            while(slot.children.size<required-1){
                for(ProcessorSlot candidate:allSlots)if(candidate.ownerKey==null){candidate.ownerKey=ownerKey;slot.children.add(candidate);break;}
            }
            Seq<ProcessorSlot> parts=new Seq<>();parts.add(slot);parts.addAll(slot.children);
            // Allocate/compile before replacing any live code. Never publish a truncated program.
            Seq<String> programs=new Seq<>();Seq<Seq<CompiledVisualEntry>> entries=new Seq<>();
            for(int i=0;i<required;i++){
                ProcessorSlot part=parts.get(i);if(ensureProcessorPlaced(part)==null){rejectRig(ownerKey,"No safe map tile available for visual processors.");return;}
                Seq<CompiledVisualEntry> partEntries=new Seq<>();
                String code=compileRigShard(part.baseMarkerId,ownerPlayerName,unit,equippedIds,catalog,partEntries,groups.isEmpty()?-1:groups.get(i));
                if(instructionCount(code)>MAX_PROGRAM_INSTRUCTIONS){rejectRig(ownerKey,"An equipped visual item exceeds the 500-instruction processor limit. Please change your equipment.");return;}
                programs.add(code);entries.add(partEntries);
            }
            for(int i=0;i<required;i++)if(entries.get(i).contains(e->e.kind==RenderedKind.TAIL_FOLLOW)&&groups.get(i)>=0){
                var peers=new java.util.ArrayList<int[]>();int tailIndex=0,myIndex=-1;
                for(int j=0;j<required;j++)if(entries.get(j).contains(e->e.kind==RenderedKind.TAIL_FOLLOW)){if(j==i)myIndex=tailIndex;tailIndex++;}
                tailIndex=0;
                for(int j=0;j<required;j++)if(entries.get(j).contains(e->e.kind==RenderedKind.TAIL_FOLLOW)){
                    if(j!=i&&Math.abs(tailIndex-myIndex)<=3){var other=getExistingProcessor(parts.get(j));peers.add(new int[]{other.tileX(),other.tileY(),i<j?1:-1});}tailIndex++;
                }
                float sep=Math.max(.88f,Mathf.clamp((5.4f+unit.hitSize*.14f)/tilesize,.72f,1.32f)*1.12f);
                String code=addFollowerPeers(programs.get(i),peers,sep);
                if(instructionCount(code)>MAX_PROGRAM_INSTRUCTIONS){rejectRig(ownerKey,"Follower visuals exceed the 500-instruction processor limit.");return;}
                programs.set(i,code);
            }
            while(slot.children.size>required-1)clearSlot(slot.children.pop());
            for(int i=0;i<required;i++){
                ProcessorSlot part=parts.get(i);part.entries.clear();part.entries.addAll(entries.get(i));
                part.lastMlogCode=programs.get(i);part.boundUnitId=unit.id;
                part.syncTickCounter=0;part.initialSyncBurstsRemaining=4;
                pushCodeAndUnit(getExistingProcessor(part),part.lastMlogCode,unit);
            }
            slot.compiledSignature=signature;
            return;
        }
        syncBinding(slot,unit);
        for(ProcessorSlot child:slot.children)syncBinding(child,unit);
    }

    private void syncBinding(ProcessorSlot slot,Unit unit){
        LogicBuild procBuild=getExistingProcessor(slot);if(procBuild==null)return;
        bindHostUnitOnServer(procBuild, unit);
        if (slot.boundUnitId != unit.id) {
            slot.boundUnitId = unit.id;
            slot.syncTickCounter = 0;
            slot.initialSyncBurstsRemaining = 4;
            broadcastHostUnitSync(procBuild, unit);
        } else if (slot.initialSyncBurstsRemaining > 0) {
            slot.syncTickCounter++;
            if (slot.syncTickCounter >= 3) {
                slot.syncTickCounter = 0;
                slot.initialSyncBurstsRemaining--;
                broadcastHostUnitSync(procBuild, unit);
            }
        } else {
            slot.syncTickCounter++;
            if (slot.syncTickCounter >= 15) {
                slot.syncTickCounter = 0;
                broadcastHostUnitSync(procBuild, unit);
            }
        }
    }

    /** Cross-processor collision reads are entirely client-local; the server never transports physics state. */
    private static String addFollowerPeers(String code,java.util.List<int[]> peers,float separation){
        if(peers.isEmpty())return code;
        StringBuilder calls=new StringBuilder();int n=0;
        for(int[] peer:peers){
            String skip="peerSkip"+n++;
            calls.append("getblock building peer ").append(peer[0]).append(" ").append(peer[1]).append("\n")
                .append("read peerReady peer \"vis\"\njump ").append(skip).append(" notEqual peerReady 1\n")
                .append("read peerHost peer \"hostUnit\"\njump ").append(skip).append(" notEqual peerHost u\n")
                .append("read peerX peer \"tx0\"\nread peerY peer \"ty0\"\nop sub pdx tx0 peerX\nop sub pdy ty0 peerY\n")
                .append("set peerSign ").append(peer[2]).append("\nop add peerReturn @counter 1\njump peerImpulse always 0 0\n")
                .append("op add vx0 vx0 pvx\nop add vy0 vy0 pvy\nop add tx0 tx0 psx\nop add ty0 ty0 psy\n")
                .append(skip).append(":\n");
        }
        code=code.replace("peerFollowStep:\n",calls.toString());
        return code+"peerImpulse:\nset pvx 0\nset pvy 0\nset psx 0\nset psy 0\nop len pd pdx pdy\n"
            +"jump peerDone greaterThanEq pd "+f(separation)+"\njump peerNudge lessThan pd 0.02\nop sub pov "+f(separation)+" pd\n"
            +"op div pinv pov pd\nop mul pimp pinv 0.065\nop mul pimp pimp dt\nop mul pvx pdx pimp\nop mul pvy pdy pimp\n"
            +"op mul psep pinv 0.16\nop mul psx pdx psep\nop mul psy pdy psep\njump peerDone always 0 0\n"
            +"peerNudge:\nop mul pvx peerSign 0.02\npeerDone:\nset @counter peerReturn\n";
    }

    public static int instructionCount(String code){return (int)code.lines().map(String::strip).filter(l->!l.isEmpty()&&!l.startsWith("#")&&!l.endsWith(":" )).count();}
    private void rejectRig(String owner,String reason){
        clearRig(owner);
        if(Groups.player!=null)for(Player player:Groups.player)if(owner.equals(player.uuid())&&player.con!=null){player.con.kick(reason);return;}
        Log.warn("[CoolPlugin] Visual rig @ rejected: @",owner,reason);
    }

    public void clearRig(@Nullable String ownerKey) {
        if (ownerKey == null) return;
        ProcessorSlot slot = slotsByOwner.remove(ownerKey);
        if (slot == null) return;

        for(ProcessorSlot child:slot.children)clearSlot(child);
        slot.children.clear();
        clearSlot(slot);
    }

    private void clearSlot(ProcessorSlot slot){
        LogicBuild procBuild = getExistingProcessor(slot);
        String clearMlog = compileClearMlog(slot.baseMarkerId);
        slot.ownerKey = null;
        slot.compiledSignature = "";
        slot.boundUnitId = -1;
        slot.syncTickCounter = 0;
        slot.initialSyncBurstsRemaining = 0;
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
            for(ProcessorSlot child:slot.children)copy.addAll(child.entries);
        }
        return copy;
    }

    /** Native logic-variable sync drives recoil from real server shots, including auto-fire. */
    public void kickSubweapon(String ownerKey,ShopItemDef def,float forward,float side){
        ProcessorSlot slot=slotsByOwner.get(ownerKey);if(slot==null)return;
        kickSlot(slot,def,forward,side);
        for(ProcessorSlot child:slot.children)kickSlot(child,def,forward,side);
    }
    private void kickSlot(ProcessorSlot slot,ShopItemDef def,float forward,float side){
        LogicBuild processor=getExistingProcessor(slot);if(processor==null||processor.executor==null)return;
        for(CompiledVisualEntry entry:slot.entries){
            if(entry.kind!=RenderedKind.SUBWEAPON||entry.def!=def||Math.abs(entry.localForwardWorld-forward)>0.01f||Math.abs(entry.localSideWorld-side)>0.01f)continue;
            LVar shot=processor.executor.optionalVar("shot"+entry.primaryMarkerId);
            if(shot!=null){shot.setnum(state.tick+1d);if(net!=null&&net.server())Call.syncVariable(processor,shot.id,Double.valueOf(state.tick+1d));}
        }
    }

    /** Local-forward depth, independent of inventory order or world-facing direction. */
    public static float mountLayerOffset(float localForward){
        return -localForward/(1f+Math.abs(localForward))*0.1f;
    }

    public @Nullable LogicBuild getProcessorForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? null : getExistingProcessor(slot);
    }

    public Seq<LogicBuild> getProcessorsForOwner(String ownerKey){
        Seq<LogicBuild> result=new Seq<>();ProcessorSlot slot=slotsByOwner.get(ownerKey);if(slot==null)return result;
        LogicBuild root=getExistingProcessor(slot);if(root!=null)result.add(root);
        for(ProcessorSlot child:slot.children){LogicBuild build=getExistingProcessor(child);if(build!=null)result.add(build);}
        return result;
    }

    public @Nullable String getLastMlogForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? null : slot.lastMlogCode;
    }

    public int getBaseMarkerIdForOwner(String ownerKey) {
        ProcessorSlot slot = slotsByOwner.get(ownerKey);
        return slot == null ? -1 : slot.baseMarkerId;
    }

    public boolean isManagedProcessorTile(int x, int y) {
        if (world == null) return false;
        Tile t = world.tile(x, y);
        return t != null && managedProcessorPositions.contains(t.pos());
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

    /**
     * Scans from the TOP-RIGHT border downward, strictly avoiding {@link BuildSystem#inReserved(int, int)}
     * ({@code (2, 2)} client-only core area refreshed every 5s) and any destroyed core sites!
     */
    private @Nullable Tile findAvailableBorderTile() {
        if (world == null || world.width() <= 0 || world.height() <= 0) return null;
        int w = world.width();
        int h = world.height();

        // Pass 1: clean air border tiles starting at top-right (w-1, h-1), far away from (2, 2) client core!
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                if (x > 1 && x < w - 2 && y > 1 && y < h - 2) {
                    x = 2;
                    continue;
                }
                Tile t = world.tile(x, y);
                if (isIdealProcessorTile(t, true)) {
                    return t;
                }
            }
        }

        // Pass 2: static border wall tile along top/right edge (outside BuildSystem reserved zone & buildings)
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                if (x > 1 && x < w - 2 && y > 1 && y < h - 2) {
                    x = 2;
                    continue;
                }
                Tile t = world.tile(x, y);
                if (isIdealProcessorTile(t, false)) {
                    return t;
                }
            }
        }

        // Pass 3: any non-reserved air tile on the map
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                Tile t = world.tile(x, y);
                if (isIdealProcessorTile(t, true)) {
                    return t;
                }
            }
        }
        return null;
    }

    private boolean isIdealProcessorTile(@Nullable Tile t, boolean requireAirBlock) {
        if (t == null || t.build != null) return false;
        // CRITICAL: Never place inside BuildSystem's client-only core zone around (2, 2) (which refreshes every 5s!)
        if (BuildSystem.inReserved(t.x, t.y) || (t.x <= 8 && t.y <= 8)) {
            return false;
        }
        if (managedProcessorPositions.contains(t.pos())) return false;
        if (buildSystem != null) {
            Seq<DestroyedCoreSite> sites = buildSystem.getDestroyedCoreSites();
            for (int i = 0; i < sites.size; i++) {
                DestroyedCoreSite s = sites.get(i);
                if (Math.abs(t.x - s.x) <= 4 && Math.abs(t.y - s.y) <= 4) {
                    return false;
                }
            }
        }
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

    private void pushCodeAndUnit(LogicBuild procBuild, String mlogCode, @Nullable Unit unit) {
        if(instructionCount(mlogCode)>MAX_PROGRAM_INSTRUCTIONS)throw new IllegalArgumentException("Visual program exceeds 500 instructions");
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
            TechTreeService techTree = buildSystem != null ? buildSystem.getTechTree() : null;
            boolean prevAllow = state != null && state.rules != null && state.rules.allowEditWorldProcessors;

            if (!prevAllow && state != null && state.rules != null) {
                state.rules.allowEditWorldProcessors = true;
                sendRulesPreservingTechTree(techTree, true);
            }
            Call.tileConfig(null, procBuild, compressed);
            if (!prevAllow && state != null && state.rules != null) {
                state.rules.allowEditWorldProcessors = false;
                sendRulesPreservingTechTree(techTree, false);
            }
            if (hostVar != null) {
                Call.syncVariable(procBuild, hostVar.id, unit);
            }
        }
    }

    private static void sendRulesPreservingTechTree(@Nullable TechTreeService techTree, boolean allowEditWorldProc) {
        if (state == null || state.rules == null) return;
        if (techTree != null && Groups.player != null) {
            for (Player p : Groups.player) {
                if (p == null || p.con == null || !p.con.isConnected()) continue;
                Rules r = techTree.buildPlayerRules(p);
                r.allowEditWorldProcessors = allowEditWorldProc;
                Call.setRules(p.con, r);
            }
        } else {
            Call.setRules(state.rules);
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
        return compileRigMlog(baseMarkerId, null, unit, equippedIds, catalog, outEntries);
    }

    public static String compileRigMlog(
        int baseMarkerId,
        @Nullable String ownerPlayerName,
        Unit unit,
        Seq<String> equippedIds,
        Catalog catalog,
        Seq<CompiledVisualEntry> outEntries
    ) {
        String code=compileRigShard(baseMarkerId,ownerPlayerName,unit,equippedIds,catalog,outEntries,-1);
        return code;
    }

    /** Inspect all native-sized programs without placing blocks (also used by validation tools). */
    public static Seq<String> compileRigPrograms(int baseMarkerId,String ownerName,Unit unit,Seq<String> ids,Catalog catalog){
        Seq<CompiledVisualEntry> entries=new Seq<>();
        String full=compileRigShard(baseMarkerId,ownerName,unit,ids,catalog,entries,-1);
        Seq<String> result=new Seq<>();
        if(instructionCount(full)<=MAX_PROGRAM_INSTRUCTIONS){result.add(full);return result;}
        for(int group=0;group<PlayerItemSystem.MAX_EQUIPPED_SLOTS;group++){
            Seq<CompiledVisualEntry> part=new Seq<>();
            String code=compileRigShard(baseMarkerId+group*MARKERS_PER_SLOT,ownerName,unit,ids,catalog,part,group);
            if(!part.isEmpty())result.add(code);
        }
        return result;
    }

    private static String compileRigShard(int baseMarkerId,String ownerPlayerName,Unit unit,Seq<String> equippedIds,
        Catalog catalog,Seq<CompiledVisualEntry> outEntries,int group){
        outEntries.clear();
        int n = Math.min(PlayerItemSystem.MAX_EQUIPPED_SLOTS, equippedIds.size);
        float hullRadius = Math.max(4f, unit.hitSize * 0.5f);
        float spriteTiles = Mathf.clamp((5.4f + unit.hitSize * 0.14f) / tilesize, 0.72f, 1.32f);

        int tailTotal = 0;
        for (int i = 0; i < n; i++) {
            ShopItemDef def = catalog.findItem(equippedIds.get(i));
            if (def == null || def.attachMode == AttachMode.VFX) continue;
            boolean overloaded = i >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            if (overloaded || def.attachMode == AttachMode.FOLLOW) {
                tailTotal++;
            }
        }

        int nextMarkerOffset = 0;
        int tailIndex = 0;
        boolean needsAimSensors = false;
        ObjectIntMap<MountSubtype> subtypeCounts = new ObjectIntMap<>();
        IntSeq tailEntryIndices = new IntSeq();
        Seq<Float> tailRopeLengthsTiles = new Seq<>();

        for (int i = 0; i < n; i++) {
            ShopItemDef def = catalog.findItem(equippedIds.get(i));
            if (def == null || def.attachMode == AttachMode.VFX) continue;

            boolean overloaded = i >= PlayerItemSystem.ACTIVE_PASSIVE_SLOTS;
            // Items outside the first 4 slots ALWAYS become tail-following with physical spring-tether physics!
            if (overloaded || def.attachMode == AttachMode.FOLLOW) {
                if (nextMarkerOffset + 2 > MARKERS_PER_SLOT) break;
                int lineId = baseMarkerId + (nextMarkerOffset++);
                int texId = baseMarkerId + (nextMarkerOffset++);

                int myTailIdx = tailIndex++;
                float relAngle = computeTailFormationAngle(myTailIdx, tailTotal);
                float ropeWorld = Math.max(10.5f, def.followDistance * 0.90f)
                    + (myTailIdx / 2) * 7.2f
                    + (myTailIdx % 2) * 1.8f;
                float standoffFromCenterWorld = hullRadius * 0.62f + ropeWorld;
                float localFwd = -standoffFromCenterWorld * Mathf.cosDeg(relAngle);
                float localSide = -standoffFromCenterWorld * Mathf.sinDeg(relAngle);

                tailEntryIndices.add(outEntries.size);
                tailRopeLengthsTiles.add(ropeWorld / tilesize);
                outEntries.add(new CompiledVisualEntry(
                    def, i, RenderedKind.TAIL_FOLLOW, overloaded, texId, lineId, localFwd, localSide, relAngle
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

                    for(var placement:MountLayout.resolve(unit,equippedIds,catalog))if(placement.slot()==i){
                        int required=kind==RenderedKind.SUBWEAPON?2:1;
                        if(nextMarkerOffset+required>MARKERS_PER_SLOT)break;
                        int textureId=baseMarkerId+(nextMarkerOffset++);
                        int flashId=kind==RenderedKind.SUBWEAPON?baseMarkerId+(nextMarkerOffset++):-1;
                        outEntries.add(new CompiledVisualEntry(def,i,kind,false,textureId,flashId,
                            placement.forward(),placement.side(),placement.mirrored()?-def.effectiveMountAngleOffset():def.effectiveMountAngleOffset()));
                    }
                }
                case HALO -> {
                    Seq<Point2f> anchors = computeUnitLocalHaloOffsets(unit, def.haloAnchor);
                    for (int a = 0; a < anchors.size; a++) {
                        if (nextMarkerOffset + 2 > MARKERS_PER_SLOT) break;
                        Point2f pt = anchors.get(a);
                        int haloId = baseMarkerId + (nextMarkerOffset++);
                        int innerId = baseMarkerId + (nextMarkerOffset++);
                        outEntries.add(new CompiledVisualEntry(
                            def, i, RenderedKind.HALO, false, haloId, innerId, pt.fwd, pt.side, a * 30f
                        ));
                    }
                }
                default -> {
                }
            }
        }

        if(group>=0){
            var lengths=new Seq<Float>();
            int cursor=0;
            for(var entry:outEntries)if(entry.kind==RenderedKind.TAIL_FOLLOW){
                float length=tailRopeLengthsTiles.get(cursor++);if(entry.inventorySlot==group)lengths.add(length);
            }
            for(int i=outEntries.size-1;i>=0;i--)if(outEntries.get(i).inventorySlot!=group)outEntries.remove(i);
            tailEntryIndices.clear();tailRopeLengthsTiles.clear();tailRopeLengthsTiles.addAll(lengths);
            for(int i=0;i<outEntries.size;i++)if(outEntries.get(i).kind==RenderedKind.TAIL_FOLLOW)tailEntryIndices.add(i);
            tailTotal=tailEntryIndices.size;
        }
        if (outEntries.isEmpty()) {
            return compileClearMlog(baseMarkerId);
        }

        int endSweepId = baseMarkerId + MARKERS_PER_SLOT;
        StringBuilder sb = new StringBuilder(6144);

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
                    var fit=ColouringAssets.mountSizes.get(ce.def.id);
                    float width=(fit==null?6f:fit.x)/tilesize,height=(fit==null?7f:fit.y)/tilesize;
                    sb.append("makemarker texture ").append(ce.primaryMarkerId).append(" 0 0 true\n");
                    sb.append("setmarker texture ").append(ce.primaryMarkerId).append(" 0 \"")
                        .append(MountLayout.texture(unit,ce.def)).append("\" 0\n");
                    sb.append("setmarker textureSize ").append(ce.primaryMarkerId).append(" ")
                        .append(f(ce.localSideWorld<0?-width:width)).append(" ").append(f(height)).append(" 0\n");
                    if(ce.kind==RenderedKind.SUBWEAPON){
                        sb.append("makemarker shape ").append(ce.connectionLineMarkerId).append(" 0 0 true\n");
                        sb.append("setmarker shape ").append(ce.connectionLineMarkerId).append(" 3 1 0\n");
                        sb.append("setmarker radius ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
                        sb.append("setmarker minimap ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
                        sb.append("setmarker world ").append(ce.connectionLineMarkerId).append(" 0 0 0\n");
                    }
                    sb.append("setmarker minimap ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                }
                case HALO -> { /* Created and styled by the shared haloDraw subroutine. */ }
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
                        .append(ce.def.attachMode==AttachMode.MOUNT||ce.def.attachMode==AttachMode.SUBWEAPON?MountLayout.texture(unit,ce.def):ce.def.worldTextureName()).append("\" 0\n");
                    var carryFit=ColouringAssets.mountSizes.get(ce.def.id);
                    float carryWidth=carryFit==null?tailSpriteTiles:carryFit.x/tilesize;
                    float carryHeight=carryFit==null?tailSpriteTiles:carryFit.y/tilesize;
                    sb.append("setmarker textureSize ").append(ce.primaryMarkerId).append(" ")
                        .append(f(carryWidth)).append(" ").append(f(carryHeight)).append(" 0\n");
                    sb.append("setmarker minimap ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                    sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 0 0 0\n");
                }
            }
        }
        sb.append("set vis 0\n");
        sb.append("set inited 1\n");

        // 3. 60-FPS per-frame local tracking & follow-physics loop (0 ms ping on each client!)
        sb.append("mainLoop:\n");
        sb.append("setrate 1000\n");
        sb.append("set u hostUnit\n");
        sb.append("sensor dead u @dead\n");
        sb.append("jump unitReady equal dead 0\n");
        String safeOwnerName = sanitizeMlogString(ownerPlayerName);
        if (safeOwnerName != null) {
            sb.append("jump hideAll notEqual @clientName \"").append(safeOwnerName).append("\"\n");
            sb.append("set u @clientUnit\n");
            sb.append("sensor dead u @dead\n");
            sb.append("jump hideAll notEqual dead 0\n");
        } else {
            sb.append("jump hideAll always 0 0\n");
        }
        sb.append("unitReady:\n");

        sb.append("sensor ux u @x\n");
        sb.append("sensor uy u @y\n");
        sb.append("sensor urot u @rotation\n");
        sb.append("sensor flying u @flying\n");
        if (needsAimSensors) {
            sb.append("sensor sx u @shootX\n");
            sb.append("sensor sy u @shootY\n");
        }

        sb.append("set baseLayer ").append(f(unit.type.groundLayer + Math.min(unit.hitSize / 4000f, 0.01f) + 0.15f)).append("\n");
        sb.append("jump layerReady equal flying 0\n");
        sb.append("set baseLayer ").append(f((unit.type.flyingLayer < 0 ? (unit.type.lowAltitude ? 90f : 115f) : unit.type.flyingLayer) + 0.15f)).append("\n");
        sb.append("layerReady:\n");
        sb.append("op sub underLayer baseLayer 0.80\n");

        sb.append("op cos ucos urot 0\n");
        sb.append("op sin usin urot 0\n");

        float tailAnchorTiles = (hullRadius * 0.62f) / tilesize;
        if (tailTotal > 0) {
            sb.append("op mul tax ").append(f(tailAnchorTiles)).append(" ucos\n");
            sb.append("op mul tay ").append(f(tailAnchorTiles)).append(" usin\n");
            sb.append("op sub anchorX ux tax\n");
            sb.append("op sub anchorY uy tay\n");
        }

        // Reveal markers & initialize spring-tether positions + zero velocities on first visible frame
        sb.append("jump skipReveal equal vis 1\n");
        if (tailTotal > 0) {
            sb.append("set lastTick @tick\n");
        }
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            if (ce.connectionLineMarkerId != -1) {
                sb.append("setmarker world ").append(ce.connectionLineMarkerId).append(" 1 0 0\n");
            }
            sb.append("setmarker world ").append(ce.primaryMarkerId).append(" 1 0 0\n");
            if (ce.kind == RenderedKind.TAIL_FOLLOW) {
                float fwdT = ce.localForwardWorld / tilesize;
                float sideT = ce.localSideWorld / tilesize;
                emitRigidTransform(sb, fwdT, sideT, "tx" + e, "ty" + e);
                sb.append("set vx").append(e).append(" 0\n");
                sb.append("set vy").append(e).append(" 0\n");
            }
        }
        sb.append("set vis 1\n");
        sb.append("skipReveal:\n");

        // Compute frame-rate independent dt (in ticks, 1.0 at 60 FPS) & damping for physical tethers
        if (tailTotal > 0) {
            sb.append("op sub dt @tick lastTick\n");
            sb.append("set lastTick @tick\n");
            sb.append("jump dtLoOk greaterThan dt 0.02\n");
            sb.append("set dt 1\n");
            sb.append("dtLoOk:\n");
            sb.append("jump dtHiOk lessThanEq dt 2.2\n");
            sb.append("set dt 2.2\n");
            sb.append("dtHiOk:\n");
            sb.append("op pow drag 0.89 dt\n");
        }

        // Step A: Update rigid mounts, aiming subweapons, halos, and tether spring-rope forces for tail items
        int tailCursor = 0;
        for (int e = 0; e < outEntries.size; e++) {
            CompiledVisualEntry ce = outEntries.get(e);
            float fwdT = ce.localForwardWorld / tilesize;
            float sideT = ce.localSideWorld / tilesize;

            switch (ce.kind) {
                case MOUNT -> {
                    sb.append("op min mountBase baseLayer 99\n");
                    sb.append("op add mountLayer mountBase ").append(Float.toString(mountLayerOffset(ce.localForwardWorld))).append("\n");
                    emitRigidTransform(sb, fwdT, sideT, "mx", "my");
                    float rotDelta = ce.angleOffsetDeg - 90f;
                    sb.append("op add mrot urot ").append(f(rotDelta)).append("\n");
                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" mx my 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" mrot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" mountLayer 0 0\n");
                }
                case SUBWEAPON -> {
                    sb.append("subBegin").append(e).append(":\n");
                    sb.append("op min mountBase baseLayer 99\n");
                    sb.append("op add mountLayer mountBase ").append(Float.toString(mountLayerOffset(ce.localForwardWorld))).append("\n");
                    emitRigidTransform(sb, fwdT, sideT, "mx", "my");
                    sb.append("op sub adx sx mx\n");
                    sb.append("op sub ady sy my\n");
                    sb.append("op len alen adx ady\n");
                    sb.append("op add mrot urot ").append(f(ce.angleOffsetDeg - 90f)).append("\n");
                    sb.append("jump swAimDone").append(e).append(" lessThan alen 0.25\n");
                    sb.append("op angle arot adx ady\n");
                    sb.append("op sub mrot arot 90\n");
                    sb.append("swAimDone").append(e).append(":\n");
                    // Reset the client-local recoil clock when a real server shot arrives;
                    // do not lose the animation just because network delay exceeded recovery time.
                    sb.append("jump recoilReady").append(e).append(" equal shot").append(ce.primaryMarkerId).append(" seenShot").append(e).append("\n");
                    sb.append("set seenShot").append(e).append(" shot").append(ce.primaryMarkerId).append("\n");
                    sb.append("set recoilStart").append(e).append(" @tick\n");
                    sb.append("recoilReady").append(e).append(":\n");
                    sb.append("op sub shotAge @tick recoilStart").append(e).append("\n");
                    sb.append("op greaterThan hasShot shot").append(ce.primaryMarkerId).append(" 0\n");
                    sb.append("op div kick shotAge ").append(f(ce.def.subweaponRecoilTime)).append("\n");
                    sb.append("op sub kick 1 kick\nop max kick kick 0\nop min kick kick 1\n");
                    sb.append("op mul kick kick hasShot\n");
                    sb.append("op mul kick kick ").append(f(ce.def.subweaponRecoil/tilesize)).append("\n");
                    sb.append("op add barrelAngle mrot 90\nop cos rx barrelAngle 0\nop sin ry barrelAngle 0\n");
                    sb.append("op mul rx rx kick\nop mul ry ry kick\nop sub mx mx rx\nop sub my my ry\n");
                    sb.append("setmarker pos ").append(ce.primaryMarkerId).append(" mx my 0\n");
                    sb.append("setmarker rotation ").append(ce.primaryMarkerId).append(" mrot 0 0\n");
                    sb.append("setmarker drawLayer ").append(ce.primaryMarkerId).append(" mountLayer 0 0\n");
                    if(ce.def.resolveWeaponEffect(ce.def.subweaponShootEffect)!=null){
                        var dimensions=ColouringAssets.mountSizes.get(ce.def.id);
                        sb.append("set flashId ").append(ce.connectionLineMarkerId).append("\n");
                        sb.append("set flashLength ").append(f((dimensions==null?3f:dimensions.y*.45f)/tilesize)).append("\n");
                        sb.append("op add flashReturn @counter 1\njump mountFlashDraw always 0 0\n");
                    }
                    sb.append("subEnd").append(e).append(":\n");
                }
                case HALO -> {
                    // One compact call per anchor avoids the native 1000-instruction parser
                    // limit for multi-weapon units such as Dissociate.
                    if(e==0 || outEntries.get(e-1).inventorySlot!=ce.inventorySlot){
                        Color hc=ce.def.resolveHaloColor();
                        sb.append("set hRadius ").append(f(ce.def.haloRadius)).append("\n");
                        sb.append("set hSides ").append(Math.max(3,ce.def.haloSides)).append("\n");
                        sb.append("packcolor hColor ").append(f(hc.r)).append(" ").append(f(hc.g)).append(" ").append(f(hc.b)).append(" 0.90\n");
                        sb.append("packcolor hInnerColor ").append(f(Math.min(1f,hc.r+0.18f))).append(" ").append(f(Math.min(1f,hc.g+0.18f))).append(" ").append(f(Math.min(1f,hc.b+0.18f))).append(" 0.75\n");
                    }
                    // Pack marker offset and two fixed-point coordinates into an exact
                    // (<2^53) integer, keeping even fourteen-weapon rigs below 1000 lines.
                    long packed=(long)(ce.primaryMarkerId-baseMarkerId)*4_000_000_000_000L
                        +(Math.round(fwdT*10000d)+1_000_000L)*2_000_000L
                        +Math.round(sideT*10000d)+1_000_000L;
                    sb.append("set hPacked ").append(packed).append("\n");
                    sb.append("op add hReturn @counter 1\njump haloDraw always 0 0\n");
                }
                case TAIL_FOLLOW -> {
                    float ropeLenT = tailRopeLengthsTiles.get(tailCursor);
                    int myTailIdx = tailCursor++;
                    String tx = "tx" + e;
                    String ty = "ty" + e;
                    String vx = "vx" + e;
                    String vy = "vy" + e;

                    // Vector from towed item -> player unit's rear hitch anchor
                    sb.append("op sub rx anchorX ").append(tx).append("\n");
                    sb.append("op sub ry anchorY ").append(ty).append("\n");
                    sb.append("op len rDist rx ry\n");
                    sb.append("jump tSnap").append(e).append(" greaterThan rDist 15\n");

                    // 1) Elastic rope tension ONLY when taut (rDist > ropeLenT); slack when <= ropeLenT!
                    sb.append("jump tSlack").append(e).append(" lessThanEq rDist ").append(f(ropeLenT)).append("\n");
                    sb.append("op sub stretch rDist ").append(f(ropeLenT)).append("\n");
                    sb.append("op div invR 1 rDist\n");
                    sb.append("op mul rnx rx invR\n");
                    sb.append("op mul rny ry invR\n");
                    sb.append("op mul fPull stretch 0.092\n");
                    sb.append("op mul fPull fPull dt\n");
                    sb.append("op mul dvx rnx fPull\n");
                    sb.append("op mul dvy rny fPull\n");
                    sb.append("op add ").append(vx).append(" ").append(vx).append(" dvx\n");
                    sb.append("op add ").append(vy).append(" ").append(vy).append(" dvy\n");

                    // Taut-leash max-stretch constraint so fast boosting units whip-tow cleanly
                    sb.append("jump tSlack").append(e).append(" lessThanEq stretch 1.45\n");
                    sb.append("op sub over stretch 1.45\n");
                    sb.append("op mul cpx rnx over\n");
                    sb.append("op mul cpy rny over\n");
                    sb.append("op add ").append(tx).append(" ").append(tx).append(" cpx\n");
                    sb.append("op add ").append(ty).append(" ").append(ty).append(" cpy\n");
                    sb.append("op mul vn1 ").append(vx).append(" rnx\n");
                    sb.append("op mul vn2 ").append(vy).append(" rny\n");
                    sb.append("op add vn vn1 vn2\n");
                    sb.append("jump tSlack").append(e).append(" greaterThanEq vn 0\n");
                    sb.append("op mul vn vn 0.65\n");
                    sb.append("op mul dvx rnx vn\n");
                    sb.append("op mul dvy rny vn\n");
                    sb.append("op sub ").append(vx).append(" ").append(vx).append(" dvx\n");
                    sb.append("op sub ").append(vy).append(" ").append(vy).append(" dvy\n");
                    sb.append("tSlack").append(e).append(":\n");

                    // 2) Subtle wake drift + organic out-of-phase hover sway (gentle so inertia dominates!)
                    emitRigidTransform(sb, fwdT, sideT, "wx", "wy");
                    sb.append("op sub wdx wx ").append(tx).append("\n");
                    sb.append("op sub wdy wy ").append(ty).append("\n");
                    sb.append("op mul wScl 0.0048 dt\n");
                    sb.append("op mul wdx wdx wScl\n");
                    sb.append("op mul wdy wdy wScl\n");
                    float phaseDeg = myTailIdx * 67f;
                    sb.append("op mul bang @tick 3.6\n");
                    sb.append("op add bang bang ").append(f(phaseDeg)).append("\n");
                    sb.append("op cos bcos bang 0\n");
                    sb.append("op sin bsin bang 0\n");
                    sb.append("op mul bScl 0.0018 dt\n");
                    sb.append("op mul bfx bcos bScl\n");
                    sb.append("op mul bfy bsin bScl\n");
                    sb.append("op add ").append(vx).append(" ").append(vx).append(" wdx\n");
                    sb.append("op add ").append(vy).append(" ").append(vy).append(" wdy\n");
                    sb.append("op add ").append(vx).append(" ").append(vx).append(" bfx\n");
                    sb.append("op add ").append(vy).append(" ").append(vy).append(" bfy\n");
                    sb.append("jump tForceDone").append(e).append(" always 0 0\n");

                    // Cross-map teleport snap
                    sb.append("tSnap").append(e).append(":\n");
                    emitRigidTransform(sb, fwdT, sideT, tx, ty);
                    sb.append("set ").append(vx).append(" 0\n");
                    sb.append("set ").append(vy).append(" 0\n");
                    sb.append("tForceDone").append(e).append(":\n");
                }
            }
        }

        sb.append("peerFollowStep:\n");
        // Step B: Pairwise elastic item-to-item collision/jostling among line-connected items!
        if (tailEntryIndices.size >= 2) {
            float minSepTiles = Math.max(0.88f, spriteTiles * 1.12f);
            for (int i = 0; i < tailEntryIndices.size; i++) {
                int ea = tailEntryIndices.get(i);
                int maxJ = Math.min(tailEntryIndices.size - 1, i + 3);
                for (int j = i + 1; j <= maxJ; j++) {
                    int eb = tailEntryIndices.get(j);
                    String lblSkip = "pSkip_" + ea + "_" + eb;
                    String lblNudge = "pNudge_" + ea + "_" + eb;
                    sb.append("op sub pdx tx").append(ea).append(" tx").append(eb).append("\n");
                    sb.append("op abs adx pdx 0\n");
                    sb.append("jump ").append(lblSkip).append(" greaterThanEq adx ").append(f(minSepTiles)).append("\n");
                    sb.append("op sub pdy ty").append(ea).append(" ty").append(eb).append("\n");
                    sb.append("op abs ady pdy 0\n");
                    sb.append("jump ").append(lblSkip).append(" greaterThanEq ady ").append(f(minSepTiles)).append("\n");
                    sb.append("op len pd pdx pdy\n");
                    sb.append("jump ").append(lblSkip).append(" greaterThanEq pd ").append(f(minSepTiles)).append("\n");
                    sb.append("jump ").append(lblNudge).append(" lessThan pd 0.02\n");
                    sb.append("op sub pov ").append(f(minSepTiles)).append(" pd\n");
                    sb.append("op div pinv pov pd\n");
                    sb.append("op mul pimp pinv 0.065\n");
                    sb.append("op mul pimp pimp dt\n");
                    sb.append("op mul pvx pdx pimp\n");
                    sb.append("op mul pvy pdy pimp\n");
                    sb.append("op add vx").append(ea).append(" vx").append(ea).append(" pvx\n");
                    sb.append("op add vy").append(ea).append(" vy").append(ea).append(" pvy\n");
                    sb.append("op sub vx").append(eb).append(" vx").append(eb).append(" pvx\n");
                    sb.append("op sub vy").append(eb).append(" vy").append(eb).append(" pvy\n");
                    sb.append("op mul psep pinv 0.16\n");
                    sb.append("op mul psx pdx psep\n");
                    sb.append("op mul psy pdy psep\n");
                    sb.append("op add tx").append(ea).append(" tx").append(ea).append(" psx\n");
                    sb.append("op add ty").append(ea).append(" ty").append(ea).append(" psy\n");
                    sb.append("op sub tx").append(eb).append(" tx").append(eb).append(" psx\n");
                    sb.append("op sub ty").append(eb).append(" ty").append(eb).append(" psy\n");
                    sb.append("jump ").append(lblSkip).append(" always 0 0\n");
                    sb.append(lblNudge).append(":\n");
                    sb.append("op add vx").append(ea).append(" vx").append(ea).append(" 0.02\n");
                    sb.append("op sub vx").append(eb).append(" vx").append(eb).append(" 0.02\n");
                    sb.append(lblSkip).append(":\n");
                }
            }
        }

        // Step C: Drag damping, velocity integration, tangential hull deflection & marker updates for tail items
        if (tailEntryIndices.size > 0) {
            float minStandTiles = Math.max(0.95f, (hullRadius + 5.2f) / tilesize);
            for (int i = 0; i < tailEntryIndices.size; i++) {
                int e = tailEntryIndices.get(i);
                CompiledVisualEntry ce = outEntries.get(e);
                String tx = "tx" + e;
                String ty = "ty" + e;
                String vx = "vx" + e;
                String vy = "vy" + e;

                sb.append("op mul ").append(vx).append(" ").append(vx).append(" drag\n");
                sb.append("op mul ").append(vy).append(" ").append(vy).append(" drag\n");
                sb.append("op len vlen ").append(vx).append(" ").append(vy).append("\n");
                sb.append("jump vCap").append(e).append(" lessThanEq vlen 0.90\n");
                sb.append("op div vscl 0.90 vlen\n");
                sb.append("op mul ").append(vx).append(" ").append(vx).append(" vscl\n");
                sb.append("op mul ").append(vy).append(" ").append(vy).append(" vscl\n");
                sb.append("vCap").append(e).append(":\n");

                sb.append("op mul stepX ").append(vx).append(" dt\n");
                sb.append("op mul stepY ").append(vy).append(" dt\n");
                sb.append("op add ").append(tx).append(" ").append(tx).append(" stepX\n");
                sb.append("op add ").append(ty).append(" ").append(ty).append(" stepY\n");

                // Soft tangential hull deflection so coasting items glide around the player unit instead of clipping!
                sb.append("op sub udx ").append(tx).append(" ux\n");
                sb.append("op sub udy ").append(ty).append(" uy\n");
                sb.append("op len ud udx udy\n");
                sb.append("jump tHullOk").append(e).append(" greaterThanEq ud ").append(f(minStandTiles)).append("\n");
                sb.append("jump tHullOk").append(e).append(" lessThan ud 0.01\n");
                sb.append("op div uinv 1 ud\n");
                sb.append("op mul unx udx uinv\n");
                sb.append("op mul uny udy uinv\n");
                sb.append("op mul cux unx ").append(f(minStandTiles)).append("\n");
                sb.append("op mul cuy uny ").append(f(minStandTiles)).append("\n");
                sb.append("op add ").append(tx).append(" ux cux\n");
                sb.append("op add ").append(ty).append(" uy cuy\n");
                sb.append("op mul uvn1 ").append(vx).append(" unx\n");
                sb.append("op mul uvn2 ").append(vy).append(" uny\n");
                sb.append("op add uvn uvn1 uvn2\n");
                sb.append("jump tHullOk").append(e).append(" greaterThanEq uvn 0\n");
                sb.append("op mul uvn uvn 1.25\n");
                sb.append("op mul dvx unx uvn\n");
                sb.append("op mul dvy uny uvn\n");
                sb.append("op sub ").append(vx).append(" ").append(vx).append(" dvx\n");
                sb.append("op sub ").append(vy).append(" ").append(vy).append(" dvy\n");
                sb.append("tHullOk").append(e).append(":\n");

                // Dynamic velocity-banked sprite tilt + tether line update
                sb.append("op sub ldx anchorX ").append(tx).append("\n");
                sb.append("op sub ldy anchorY ").append(ty).append("\n");
                sb.append("op mul bvx ").append(vx).append(" 2.4\n");
                sb.append("op mul bvy ").append(vy).append(" 2.4\n");
                sb.append("op add rdx ldx bvx\n");
                sb.append("op add rdy ldy bvy\n");
                sb.append("op angle trot rdx rdy\n");
                sb.append("op sub trot trot 90\n");

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

        sb.append("set halosReady 1\njump waitTick always 0 0\n");

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

        sb.append("jump waitTick always 0 0\n");
        if(outEntries.contains(entry->entry.kind==RenderedKind.SUBWEAPON)){
            sb.append("mountFlashDraw:\nop div flashAlpha shotAge 6\nop sub flashAlpha 1 flashAlpha\nop max flashAlpha flashAlpha 0\nop min flashAlpha flashAlpha 1\nop mul flashAlpha flashAlpha hasShot\n");
            sb.append("op add flashAngle mrot 90\nop cos flashX flashAngle 0\nop sin flashY flashAngle 0\nop mul flashX flashX flashLength\nop mul flashY flashY flashLength\nop add flashX flashX mx\nop add flashY flashY my\n");
            sb.append("setmarker pos flashId flashX flashY 0\nsetmarker rotation flashId flashAngle 0 0\nop add flashLayer mountLayer 0.45\nsetmarker drawLayer flashId flashLayer 0 0\n");
            sb.append("op mul flashRadius flashAlpha 2.2\nsetmarker radius flashId flashRadius 0 0\npackcolor flashColor 1 0.91 0.55 flashAlpha\nsetmarker color flashId flashColor 0 0\nset @counter flashReturn\n");
        }
        if(outEntries.contains(entry->entry.kind==RenderedKind.HALO)){
            sb.append("haloDraw:\nop idiv hId hPacked 4000000000000\nop add hId hId ").append(baseMarkerId).append("\n");
            sb.append("op mod hSide hPacked 2000000\nop sub hSide hSide 1000000\nop div hSide hSide 10000\n");
            sb.append("op idiv hFwd hPacked 2000000\nop mod hFwd hFwd 2000000\nop sub hFwd hFwd 1000000\nop div hFwd hFwd 10000\nop add hInner hId 1\n");
            sb.append("jump haloStyleReady equal halosReady 1\n");
            sb.append("makemarker shape hId 0 0 true\nsetmarker shape hId hSides 0 0\nsetmarker radius hId hRadius 0 0\n");
            sb.append("setmarker stroke hId 0.85 0 0\nsetmarker color hId hColor 0 0\nsetmarker minimap hId 0 0 0\nsetmarker world hId 1 0 0\n");
            sb.append("makemarker shape hInner 0 0 true\nop sub hInnerSides hSides 2\nop max hInnerSides hInnerSides 3\n");
            sb.append("op mul hInnerRadius hRadius 0.62\nsetmarker shape hInner hInnerSides 0 0\nsetmarker radius hInner hInnerRadius 0 0\n");
            sb.append("setmarker stroke hInner 0.65 0 0\nsetmarker color hInner hInnerColor 0 0\nsetmarker minimap hInner 0 0 0\nsetmarker world hInner 1 0 0\n");
            sb.append("haloStyleReady:\nop mul hfx hFwd ucos\nop mul hfy hFwd usin\nop mul hsx hSide usin\nop mul hsy hSide ucos\n");
            sb.append("op add mx ux hfx\nop sub mx mx hsx\nop add my uy hfy\nop add my my hsy\n");
            sb.append("op mul hPhase hId 15\nop mul hrot @time 0.055\nop add hrot hrot hPhase\nop add hLayer baseLayer 0.30\n");
            sb.append("setmarker pos hId mx my 0\nsetmarker rotation hId hrot 0 0\nsetmarker drawLayer hId hLayer 0 0\n");
            sb.append("op mul innerRot hrot -1\nop add innerRot innerRot 30\nsetmarker pos hInner mx my 0\nsetmarker rotation hInner innerRot 0 0\nsetmarker drawLayer hInner hLayer 0 0\n");
            sb.append("set @counter hReturn\n");
        }
        sb.append("waitTick:\n");
        sb.append("wait 0\n");
        sb.append("jump mainLoop always 0 0\n");

        return compactPhysics(sb.toString());
    }

    // Share repeated routines; each published program is separately checked against
    // the configured 500-instruction budget.
    private static String compactPhysics(String code){
        code=shareBlocks(code,"(?m)^subBegin\\d+:\n[\\s\\S]*?^subEnd\\d+:\n","subweapon");
        code=shareBlocks(code,"(?m)^op sub rx anchorX tx\\d+\n[\\s\\S]*?^tForceDone\\d+:\n","towForce");
        code=shareBlocks(code,"(?m)^op mul vx\\d+ vx\\d+ drag\n[\\s\\S]*?^setmarker drawLayer \\d+ baseLayer 0 0\n","towStep");
        code=compactPairs(code);
        code=shareBlocks(code,"(?m)^op sub pdx tx\\d+ tx\\d+\n[\\s\\S]*?^pSkip_\\d+_\\d+:\n","towPair");
        code=code.replaceAll("(?m)^(?:subBegin\\d+|subEnd\\d+|subweaponsubBegin|subweaponsubEnd):\n", "");
        var visibility=java.util.regex.Pattern.compile("(?m)(?:^setmarker world [0-9]+ [01] 0 0\\n){5,}").matcher(code);
        StringBuffer compact=new StringBuffer();int group=0;
        while(visibility.find()){
            String[] rows=visibility.group().strip().split("\\n");
            int lo=Integer.MAX_VALUE,hi=0;String flag="0";
            for(String row:rows){String[] words=row.split(" ");int id=Integer.parseInt(words[2]);lo=Math.min(lo,id);hi=Math.max(hi,id);flag=words[3];}
            String label="visibility"+(group++);
            visibility.appendReplacement(compact,java.util.regex.Matcher.quoteReplacement("set visibilityId "+lo+"\n"+label+":\nsetmarker world visibilityId "+flag+" 0 0\nop add visibilityId visibilityId 1\njump "+label+" lessThanEq visibilityId "+hi+"\n"));
        }
        visibility.appendTail(compact);code=compact.toString();
        return code;
    }
    /** Shared collision impulse calculation, with positions/velocities kept in the caller. */
    private static String compactPairs(String code){
        var pattern=java.util.regex.Pattern.compile("(?m)^op sub pdx tx(\\d+) tx(\\d+)\n[\\s\\S]*?^pSkip_\\d+_\\d+:\n");
        var matcher=pattern.matcher(code);StringBuffer result=new StringBuffer();String separation=null;
        while(matcher.find()){
            String a=matcher.group(1),b=matcher.group(2);
            var sep=java.util.regex.Pattern.compile("greaterThanEq adx ([0-9.]+)").matcher(matcher.group());
            if(!sep.find())return code;separation=sep.group(1);
            StringBuilder call=new StringBuilder();
            call.append("op sub pdx tx").append(a).append(" tx").append(b).append("\n");
            call.append("op sub pdy ty").append(a).append(" ty").append(b).append("\n");
            call.append("op add pairReturn @counter 1\njump pairImpulse always 0 0\n");
            for(String axis:new String[]{"x","y"}){
                call.append("op add v").append(axis).append(a).append(" v").append(axis).append(a).append(" pv").append(axis).append("\n");
                call.append("op sub v").append(axis).append(b).append(" v").append(axis).append(b).append(" pv").append(axis).append("\n");
                call.append("op add t").append(axis).append(a).append(" t").append(axis).append(a).append(" ps").append(axis).append("\n");
                call.append("op sub t").append(axis).append(b).append(" t").append(axis).append(b).append(" ps").append(axis).append("\n");
            }
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(call.toString()));
        }
        if(separation==null)return code;
        matcher.appendTail(result);
        result.append("pairImpulse:\nset pvx 0\nset pvy 0\nset psx 0\nset psy 0\nop len pd pdx pdy\n")
            .append("jump pairDone greaterThanEq pd ").append(separation).append("\n")
            .append("jump pairNudge lessThan pd 0.02\nop sub pov ").append(separation).append(" pd\n")
            .append("op div pinv pov pd\nop mul pimp pinv 0.065\nop mul pimp pimp dt\n")
            .append("op mul pvx pdx pimp\nop mul pvy pdy pimp\nop mul psep pinv 0.16\n")
            .append("op mul psx pdx psep\nop mul psy pdy psep\njump pairDone always 0 0\n")
            .append("pairNudge:\nset pvx 0.02\npairDone:\nset @counter pairReturn\n");
        return result.toString();
    }

    private static String shareBlocks(String code,String pattern,String routine){
        var matcher=java.util.regex.Pattern.compile(pattern).matcher(code);
        java.util.List<String> originals=new java.util.ArrayList<>();
        java.util.List<String[]> tokens=new java.util.ArrayList<>();
        java.util.List<java.util.LinkedHashMap<String,String>> mappings=new java.util.ArrayList<>();
        while(matcher.find()){
            String block=matcher.group();originals.add(block);
            var vars=new java.util.LinkedHashMap<String,String>();
            var vm=java.util.regex.Pattern.compile("\\b(?:tx|ty|vx|vy|seenShot|recoilStart|shot)\\d+\\b").matcher(block);
            while(vm.find())vars.computeIfAbsent(vm.group(),k->routine+"v"+vars.size());
            for(var entry:vars.entrySet())block=block.replaceAll("\\b"+entry.getKey()+"\\b",entry.getValue());
            block=block.replaceAll("(tSnap|tSlack|tForceDone|vCap|tHullOk|swAimDone|recoilReady|subBegin|subEnd)\\d+",routine+"$1")
                .replaceAll("(pSkip|pNudge)_\\d+_\\d+",routine+"$1");
            tokens.add(block.split("(?<=\\s)|(?=\\s)"));mappings.add(vars);
        }
        if(tokens.size()<2)return code;
        String[] body=tokens.get(0).clone();
        var params=new java.util.ArrayList<Integer>();
        for(int i=0;i<body.length;i++){
            boolean different=false;
            for(var row:tokens){if(row.length!=body.length)return code;if(!row[i].equals(body[i]))different=true;}
            if(different){
                if(!body[i].matches("-?[0-9.]+"))return code;
                Integer equivalent=null;
                for(int previous:params){boolean same=true;for(var row:tokens)if(!row[i].equals(row[previous]))same=false;if(same){equivalent=previous;break;}}
                if(equivalent==null){params.add(i);body[i]=routine+"p"+i;}
                else body[i]=routine+"p"+equivalent;
            }
        }
        for(int n=0;n<originals.size();n++){
            StringBuilder call=new StringBuilder();
            for(var entry:mappings.get(n).entrySet())call.append("set ").append(entry.getValue()).append(" ").append(entry.getKey()).append("\n");
            for(int i:params)call.append("set ").append(routine).append("p").append(i).append(" ").append(tokens.get(n)[i]).append("\n");
            call.append("op add ").append(routine).append("Return @counter 1\njump ").append(routine).append(" always 0 0\n");
            for(var entry:mappings.get(n).entrySet())if(!entry.getKey().matches("shot\\d+"))call.append("set ").append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
            code=code.replace(originals.get(n),call.toString());
        }
        return code+routine+":\n"+String.join("",body)+"set @counter "+routine+"Return\n";
    }

    private static @Nullable String sanitizeMlogString(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return null;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\' || c == '\n' || c == '\r') {
                return null;
            }
            out.append(c);
        }
        return out.toString();
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

    private static String buildRigSignature(Unit unit, @Nullable String ownerPlayerName, Seq<String> equippedIds) {
        StringBuilder sb = new StringBuilder(equippedIds.size * 8 + 40);
        sb.append(unit.type != null ? unit.type.name : "unit").append('#');
        sb.append(Math.round(unit.hitSize * 10f)).append('#');
        sb.append(ownerPlayerName != null ? ownerPlayerName : "").append(':');
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
