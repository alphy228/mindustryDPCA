import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import coolplugin.CoolPlugin;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.gameplay.BuildSystem;
import coolplugin.gameplay.ItemShopMenu;
import coolplugin.gameplay.ItemShopSystem;
import coolplugin.gameplay.LaunchPadSystem;
import coolplugin.gameplay.PlayerInventoryDrawer;
import coolplugin.gameplay.PlayerItemSystem;
import coolplugin.gameplay.ShopItemDef;
import coolplugin.gameplay.ShopItemDef.AttachMode;
import coolplugin.gameplay.ShopItemDef.StationTier;
import coolplugin.gameplay.WorldItemLogicManager;
import coolplugin.gameplay.WorldItemLogicManager.CompiledVisualEntry;
import coolplugin.gameplay.WorldItemLogicManager.RenderedKind;
import coolplugin.techtree.DefaultSerpuloPreThoriumTree;
import coolplugin.techtree.TechNodeDef;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.ctype.ContentType;
import mindustry.game.MapObjectives.LineMarker;
import mindustry.game.MapObjectives.ObjectiveMarker;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.game.MapObjectives.TextureMarker;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.io.TypeIO;
import mindustry.mod.DataPatcher;
import mindustry.mod.data.ContentAsset;
import mindustry.mod.data.PatchAsset;
import mindustry.net.Administration;
import mindustry.net.Administration.ActionType;
import mindustry.net.Administration.PlayerAction;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.type.UnitType;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.SwitchBlock.SwitchBuild;
import mindustry.world.meta.BuildVisibility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class VerifyColouringGeneration {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String desc) {
        if (cond) {
            passed++;
            System.out.println("  [PASS] " + desc);
        } else {
            failed++;
            System.err.println("  [FAIL] " + desc);
        }
    }

    private static final class CapturingCon extends NetConnection {
        final Seq<Object> sentPackets = new Seq<>();

        CapturingCon(String uuid) {
            super("127.0.0.1");
            this.uuid = uuid;
        }

        @Override
        public void send(Object object, boolean reliable) {
            sentPackets.add(object);
        }

        @Override
        public void close() {}

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    public static void main(String[] args) {
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                try {
                    runSuite(args.length > 0 ? args[0] : "/tmp/mindustryDPCA");
                } catch (Throwable t) {
                    t.printStackTrace();
                    failed++;
                } finally {
                    System.out.println("\n=== COLOURING SUITE SUMMARY: " + passed + " passed, " + failed + " failed ===");
                    System.exit(failed == 0 ? 0 : 1);
                }
            }
        });
    }

    private static void runSuite(String repoPath) throws Exception {
        Vars.headless = true;
        Core.settings = new Settings();
        Fi workConfig = new Fi(System.getProperty("colourtest.output", "/home/user/.cache/colouring-test-" + System.nanoTime()));
        new Fi(repoPath).child("config").copyFilesTo(workConfig);
        workConfig.child("alphystuff").child("players.db").delete();
        Core.settings.setDataDirectory(workConfig);

        Vars.loadLogger();
        Vars.net = new Net(null);
        Vars.init();
        Vars.dataDirectory = workConfig;
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.logicVars.init();
        Vars.world = new World();
        Vars.logic = new Logic();
        Vars.indexer = new BlockIndexer();
        Vars.netServer = new NetServer();
        Vars.netServer.admins = new Administration();

        Vars.world.loadGenerator(48, 48, (Tiles tiles) -> {
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) {
                    tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
                }
            }
        });
        Vars.state.rules.unitCap = 9999;
        Vars.state.rules.fog = false;
        Vars.state.set(GameState.State.playing);

        if (System.getProperty("coolplugin.colouring.vanillaJar") == null) System.setProperty("coolplugin.colouring.vanillaJar", "/tmp/Mindustry.jar");
        if (System.getProperty("coolplugin.colouring.testPaletteLimit") == null) System.setProperty("coolplugin.colouring.testPaletteLimit", "1");
        new coolplugin.gameplay.ColouringAssets(workConfig).generate();
        Seq<ContentAsset> assets=new Seq<>();
        for(String folder:new String[]{"liquids","items","blocks","units"}) {
            ContentType ct=ContentType.valueOf(folder.substring(0,folder.length()-1));
            workConfig.child("assets/content/"+folder).walk(f->{
                if(f.extension().equals("hjson"))assets.add(new ContentAsset(f.nameWithoutExtension(),ct,f.readString()));
            });
        }
        Seq<PatchAsset> generatedPatches=new Seq<>();
        workConfig.child("assets/patches").walk(f->{if(f.extension().equals("hjson")){PatchAsset patch=new PatchAsset(f.readString());patch.setPath(f.name());generatedPatches.add(patch);}});
        generatedPatches.sort();
        new DataPatcher().apply(generatedPatches,assets,false);
        int errors=0;
        for(var a:assets) if(a.content==null || a.content.hasErrored()){errors++;System.out.println("BAD CONTENT "+a.name+" "+(a.content==null?"parse failure":a.content.minfo.baseError));}
        System.out.println("CONTENT ERRORS="+errors);
        var palette=coolplugin.gameplay.ColouringAssets.palettes.first();
        UnitType base=Vars.content.unit("dagger"), copy=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant("dagger",palette));
        check(coolplugin.gameplay.ColouringAssets.palettes.size<=20,"At most 20 complete schemes, not one palette per source unit");
        check(!coolplugin.gameplay.ColouringAssets.palettes.contains(scheme->scheme.id().startsWith("turret-")),"No turret palettes generated");
        var report=arc.util.serialization.Jval.read(workConfig.child("colouring-cache/manifest.json").readString());
        check(report.get("bulkRgbaBytes").asDouble()<=coolplugin.gameplay.ColouringAssets.BULK_RGBA_BUDGET_BYTES,"Bulk RGBA stays within the hard 144 MiB generation budget");
        check(workConfig.child("assets/sprites/colouring-generated").findAll(f->f.extension().equals("png")&&(f.name().contains("-wreck")||f.name().endsWith("-full.png")||f.name().endsWith("-ui.png"))).isEmpty(),"No recoloured wreck or full/UI icon copies in the bulk datapatch");
        check(workConfig.child("colouring-cache/previews/"+copy.name.substring(3)+".png").exists(),"Selected full icon is available in the server-only preview cache");
        check(copy!=null && copy.hidden && copy.internal,"Hidden unit variant exists");
        check(copy.health==base.health && copy.speed==base.speed && copy.weapons.size==base.weapons.size,
            "Variant retains gameplay and exact weapon count: base="+base.weapons.size+" copy="+copy.weapons.size);
        check(errors==0,"All generated content parses without errors");
        int different=0;
        for(UnitType u:Vars.content.units()) {
            if(u.name.startsWith("dp-paint-")) {
                UnitType original=Vars.content.unit(coolplugin.gameplay.ColouringAssets.baseName(u.name));
                if(original==null || u.health!=original.health || u.speed!=original.speed || u.weapons.size!=original.weapons.size)different++;
            }
        }
        check(different==0,"All variants preserve base health, speed and weapon counts");
        int altered=0;
        for(UnitType u:Vars.content.units())if(u.name.startsWith("dp-paint-")){
            UnitType original=Vars.content.unit(coolplugin.gameplay.ColouringAssets.baseName(u.name));
            if(u.armor!=original.armor||u.hitSize!=original.hitSize||u.itemCapacity!=original.itemCapacity
                ||u.buildSpeed!=original.buildSpeed||u.mineSpeed!=original.mineSpeed||u.engines.size!=original.engines.size
                ||u.abilities.size!=original.abilities.size||u.flying!=original.flying
                ||!u.constructor.get().getClass().isAssignableFrom(original.constructor.get().getClass())) {altered++;System.out.println("ALTERED "+u.name+" armor="+u.armor+"/"+original.armor+" cap="+u.itemCapacity+"/"+original.itemCapacity+" build="+u.buildSpeed+"/"+original.buildSpeed+" mine="+u.mineSpeed+"/"+original.mineSpeed+" engine="+u.engines.size+"/"+original.engines.size+" abilities="+u.abilities.size+"/"+original.abilities.size+" type="+u.constructor.get().getClass()+"/"+original.constructor.get().getClass());}
            for(int i=0;i<Math.min(u.weapons.size,original.weapons.size);i++)if(u.weapons.get(i).reload!=original.weapons.get(i).reload
                ||u.weapons.get(i).bullet.damage!=original.weapons.get(i).bullet.damage) {altered++;System.out.println("ALTERED WEAPON "+u.name+" #"+i);}
        }
        check(altered==0,"All variants preserve armor, hitbox, capacities, mining/building, movement class, engines, abilities and weapon damage/reloads");
        check(Vars.content.block("dp-colouring-station") instanceof mindustry.world.blocks.payloads.PayloadConveyor
            && Vars.content.block("dp-colouring-station-2").size==5,"T1 conveyor and 5x5 T2 reconstructor are generated");
        var plugin=new CoolPlugin();
        var db=plugin.getPlayerDatabase();db.init();
        Player p=Player.create();p.name="Painter";p.con=new CapturingCon("painter");p.team(Team.sharded);p.add();
        Unit host=base.create(Team.sharded);host.set(160,160);host.add();p.unit(host);
        var system=new coolplugin.gameplay.ColouringSystem(workConfig,plugin.getPlayerItemSystem(),plugin.getPlayerUnitSystem());system.start();
        String emptyUi=new String(serializeUi(system.buildMenu(p,base,2,"")),StandardCharsets.UTF_8);
        check(emptyUi.contains("You dont have any paint cans on you")&&!emptyUi.contains("Apply"),"Empty inventory hides all palettes and apply action");
        var eq=plugin.getPlayerItemSystem().getEquipment(p);eq.equipped.add(palette.canId());plugin.getPlayerItemSystem().saveEquipment(p,eq);
        check(system.available(p,1).size==(palette.tier()==1?1:0)&&system.available(p,2).size==1,"Paint availability respects its shared scheme station tier");
        String paintedUi=new String(serializeUi(system.buildMenu(p,base,2,palette.id())),StandardCharsets.UTF_8);
        check(paintedUi.contains("palette:"+palette.id())&&paintedUi.contains("Apply")&&!paintedUi.contains("exit"),"Menu has owned palette, preview and apply but no redundant exit button");
        Tile t=Vars.world.tile(20,20);t.setNet(Vars.content.block("dp-colouring-station-2"),Team.sharded,0);
        var station=t.build;host.remove();station.handlePayload(station,new mindustry.world.blocks.payloads.UnitPayload(host));
        host.health=61;host.shield=23;
        check(system.apply(p,station,host,palette.id())&&host.type==copy&&host.health==61&&host.shield==23,
            "Apply changes held unit type while preserving identity, health and shield");
        check(plugin.getPlayerItemSystem().countOwnedTotal(plugin.getPlayerItemSystem().getEquipment(p),palette.canId())==0,
            "Applying consumes exactly one inventory paint can");
        check(!system.apply(p,station,host,palette.id()),"Unowned or repeat paint cannot be applied by forged action");
        system.open(p,station,host);
        check(!station.enabled,"Payload locks while colouring menu is open");
        check(system.release(p)&&station.enabled,"Menu close releases station lock");
        check(station.getPayload()==null&&host.isValid(),"Closing menu dumps unit back into the world");
        t.setNet(Vars.content.block("dp-colouring-station-2"),Team.sharded,0);
        station=t.build;host.remove();station.handlePayload(station,new mindustry.world.blocks.payloads.UnitPayload(host));
        system.open(p,station,host);
        check(coolplugin.gameplay.ColouringSystem.exit(p)&&station.enabled&&station.getPayload()==null,"/exit delegate releases payload and closes session");
        check(!coolplugin.gameplay.ColouringSystem.exit(p),"Repeated /exit cannot duplicate a released payload");
        Unit large=UnitTypes.reign.create(Team.sharded);large.set(station.x,station.y);
        check(station.canControlSelect(large),"5x5 T2 can accept a larger player unit as a payload");
        Tile smallTile=Vars.world.tile(30,20);smallTile.setNet(Vars.content.block("dp-colouring-station"),Team.sharded,0);
        large.set(smallTile.build.x,smallTile.build.y);
        check(!smallTile.build.canControlSelect(large),"T1 conveyor rejects units above its payload size limit");
        new coolplugin.gameplay.UnitPatchManager(workConfig).reloadGeneratedAssets();
        Seq<mindustry.mod.data.DataAsset> streamed=new Seq<>();
        arc.Events.fire(new mindustry.game.EventType.DataPatchLoadEvent(streamed));
        check(streamed.contains(a->a instanceof mindustry.mod.data.ImageAsset && a.name.startsWith(palette.spritePrefix()))
            && streamed.contains(a->a instanceof ContentAsset c && c.name.startsWith(palette.spritePrefix())),
            "Fallback datapatch registration includes generated PNGs and hidden unit definitions");
        int assetCount=streamed.size;
        arc.Events.fire(new mindustry.game.EventType.DataPatchLoadEvent(streamed));
        check(streamed.size==assetCount,"Repeated datapatch load hooks do not duplicate generated assets");
        var migrationEq=plugin.getPlayerItemSystem().getEquipment(p);
        migrationEq.equipped.clear();migrationEq.equipped.add("paint-unit-flare");plugin.getPlayerItemSystem().saveEquipment(p,migrationEq);
        String shared=coolplugin.gameplay.ColouringAssets.paletteAliases.get("unit-flare");
        check(plugin.getPlayerItemSystem().getEquipment(p).equipped.first().equals("paint-"+shared),"Old per-unit cans migrate to shared schemes without losing the item");
        var restored=coolplugin.database.PlayerSessionManager.lookupUnitTypeOrDefault("dp-paint-unit-flare-reign");
        check(coolplugin.gameplay.ColouringAssets.baseName(restored.name).equals("reign"),"Retired variant names preserve the player's base unit on reconnect");
        var colour=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);colour.setRGB(0,0,0xff5b739a);
        int transferred=coolplugin.gameplay.ColouringAssets.recolour(colour,palette.ramp()).getRGB(0,0)&0xffffff;
        check(java.util.Arrays.stream(palette.ramp()).anyMatch(c->c==transferred),"Whole-scheme transfer uses actual palette colours, not interpolated gradients");
        migrationEq=plugin.getPlayerItemSystem().getEquipment(p);migrationEq.equipped.add("paint-turret-duo");plugin.getPlayerItemSystem().saveEquipment(p,migrationEq);
        plugin.getPlayerItemSystem().getEquipment(p);
        var retired=plugin.getPlayerItemSystem().getCatalog().findItem("paint-turret-duo");
        check(retired!=null&&!retired.craftable&&retired.decraftable,"Retired turret cans remain salvageable without regenerating turret palettes");
        System.out.println("GENERATED CONFIG="+workConfig);
    }

    private static ShapeMarker findShopCircleAt(float wx, float wy) {
        for (ObjectiveMarker m : Vars.state.markers.worldMarkers) {
            if (m instanceof ShapeMarker sm && Mathf.dst(sm.pos.x, sm.pos.y, wx, wy) < 0.5f) {
                return sm;
            }
        }
        return null;
    }

    private static boolean containsNodeId(TechNodeDef node, String id) {
        if (node == null) return false;
        if (id.equals(node.id)) return true;
        for (TechNodeDef child : node.children) {
            if (containsNodeId(child, id)) return true;
        }
        return false;
    }

    private static void setEquipped(CoolPlugin plugin, Player p, String... ids) {
        EquipmentData eq = plugin.getPlayerItemSystem().getEquipment(p);
        eq.equipped.clear();
        for (String id : ids) eq.equipped.add(id);
        plugin.getPlayerItemSystem().saveEquipment(p, eq);
    }

    private static byte[] serializeUi(NodeBuilder<?> tree) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        TypeIO.writeUiBuilder(new Writes(new DataOutputStream(bos)), tree);
        byte[] bytes = bos.toByteArray();
        NodeBuilder<?> readBack = TypeIO.readUiBuilder(new Reads(new DataInputStream(new ByteArrayInputStream(bytes))));
        if (readBack == null) throw new AssertionError("TypeIO.readUiBuilder returned null");
        return bytes;
    }
}
