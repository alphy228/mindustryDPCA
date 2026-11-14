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
        check(workConfig.child("assets/sprites/colouring-generated").findAll(f->f.extension().equals("png")&&f.name().startsWith("paint-")&&(f.name().contains("-wreck")||f.name().endsWith("-full.png")||f.name().endsWith("-ui.png"))).isEmpty(),"No recoloured wreck or full/UI icon copies in the bulk datapatch");
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
        for(String stationName:new String[]{"colouring-station","colouring-station-2"}){
            Fi imageDir=workConfig.child("assets/sprites/colouring-generated");
            check(imageDir.child(stationName+".png").exists()&&imageDir.child(stationName+"-top.png").exists()
                &&imageDir.child(stationName+"-full.png").exists()&&imageDir.child(stationName+"-ui.png").exists(),
                "Explicit vanilla-based placeholder layers and icons exist for "+stationName);
        }
        var tree=new coolplugin.techtree.TechTreeService(workConfig.child("tree-migration-test"));
        tree.reloadTree();
        var copper=tree.findNode("dp-copper-container");
        check(copper!=null&&"conveyor".equals(copper.parent.id),"Default Copper Container follows Conveyor directly");
        copper.parent.children.remove(copper,true);
        var router=tree.findNode("router");router.children.add(copper);copper.parent=router;
        copper.displayName="Migration sentinel";
        coolplugin.techtree.TechTreeConfigIO.save(workConfig.child("tree-migration-test/techtree.hjson"),tree.getRoot(),new Seq<>());
        tree.reloadTree();copper=tree.findNode("dp-copper-container");
        check("conveyor".equals(copper.parent.id)&&"Migration sentinel".equals(copper.displayName),
            "Saved router-child Copper Container is moved without replacing its configured node");
        tree.reloadTree();
        check("conveyor".equals(tree.findNode("dp-copper-container").parent.id),"Copper Container migration persists and is idempotent");
        var plugin=new CoolPlugin();
        var db=plugin.getPlayerDatabase();db.init();
        Player p=Player.create();p.name="Painter";p.con=new CapturingCon("painter");p.team(Team.sharded);p.add();
        Unit host=base.create(Team.sharded);host.set(160,160);host.add();p.unit(host);
        var system=new coolplugin.gameplay.ColouringSystem(workConfig,plugin.getPlayerItemSystem(),plugin.getPlayerUnitSystem());system.start();
        String emptyUi=new String(serializeUi(system.buildMenu(p,base,2,"")),StandardCharsets.UTF_8);
        check(emptyUi.contains("You dont have any paint cans on you")&&!emptyUi.contains("Apply"),"Empty inventory hides all palettes and apply action");
        var eq=plugin.getPlayerItemSystem().getEquipment(p);eq.equipped.add(palette.canId());plugin.getPlayerItemSystem().saveEquipment(p,eq);
        check(system.available(p,1).size==1&&system.available(p,2).size==1,"Owned cans remain visible at either station tier");
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
        // Model native handleUnitPayload: clear control, remove, grab, then assign a new ID
        // on the next app frame. The old suite bypassed this deferred ID transition.
        var unitSystemInstance=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("instance");unitSystemInstance.setAccessible(true);
        unitSystemInstance.set(null,plugin.getPlayerUnitSystem()); // normally set by PlayerUnitSystem.start()
        var ownerField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("owned");ownerField.setAccessible(true);
        @SuppressWarnings("unchecked") var owned=(arc.struct.ObjectMap<String,Unit>)ownerField.get(plugin.getPlayerUnitSystem());
        Unit oct=UnitTypes.oct.create(Team.sharded);oct.set(station.x,station.y);oct.add();p.unit(oct);
        owned.put(p.uuid(),oct);
        var recon=(mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)station;
        recon.handleUnitPayload(oct, pay->recon.payload=(mindustry.world.blocks.payloads.UnitPayload)pay);
        system.update();
        oct.id=mindustry.entities.EntityGroup.nextId();
        for(int frame=0;frame<10;frame++){recon.updateTile();system.update();}
        check(recon.payload!=null&&recon.payload.unit==oct&&!oct.isAdded()&&!recon.enabled,
            "Oct stays locked across native deferred payload ID reassignment and subsequent updates");
        system.open(p,recon,oct);
        check(recon.payload!=null&&recon.payload.unit==oct&&!recon.enabled,
            "Opening the same station session twice is idempotent, not an eject");
        system.release(p);
        check(oct.isAdded()&&oct.within(recon,0.1f),"Flying Oct exits on top of the station, not forced to its side");
        check(!recon.block.solid&&!recon.tile.solid(),"T2 footprint is walkable by grounded mech units");
        Unit mech=UnitTypes.mace.create(Team.sharded);mech.set(recon.x,recon.y);mech.add();p.unit(mech);owned.put(p.uuid(),mech);
        check(recon.canControlSelect(mech),"Grounded mech can enter T2 from on top of its walkable footprint");
        recon.handleUnitPayload(mech,pay->recon.payload=(mindustry.world.blocks.payloads.UnitPayload)pay);
        system.update();mech.id=mindustry.entities.EntityGroup.nextId();
        for(int frame=0;frame<10;frame++){recon.updateTile();system.update();}
        check(recon.payload!=null&&recon.payload.unit==mech&&!recon.enabled,"Mech stays held across payload entry ID change");
        system.release(p);
        check(mech.isAdded()&&mech.within(recon,0.1f),"Grounded mech can exit on the walkable T2 footprint");
        var conveyor=(mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild)smallTile.build;
        Unit small=UnitTypes.dagger.create(Team.sharded);small.set(conveyor.x,conveyor.y);small.add();p.unit(small);owned.put(p.uuid(),small);
        conveyor.handleUnitPayload(small,pay->conveyor.item=pay);
        system.update();small.id=mindustry.entities.EntityGroup.nextId();
        for(int frame=0;frame<10;frame++)system.update();
        check(conveyor.item instanceof mindustry.world.blocks.payloads.UnitPayload up&&up.unit==small&&!conveyor.enabled,
            "T1 remains locked after deferred unit ID change without null-payload crash");
        system.open(p,conveyor,small);
        check(conveyor.item!=null&&!conveyor.enabled,"Repeated T1 open does not release payload before lock");
        system.release(p);
        var lockMethod=coolplugin.gameplay.ColouringSystem.class.getDeclaredMethod("lock",Building.class,boolean.class);
        lockMethod.setAccessible(true);lockMethod.invoke(null,conveyor,true);lockMethod.invoke(null,conveyor,false);
        check(conveyor.item==null&&conveyor.enabled,"Empty T1 lock is null-safe after payload removal");
        owned.remove(p.uuid());
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
        check(emptyUi.contains("net-colour-preview-live"),"Empty inventory still displays the player unit preview");
        check(workConfig.child("colouring-cache/previews/base-dagger.png").exists(),"Unpainted full preview is cached server-side");
        String alreadyPainted=new String(serializeUi(system.buildMenu(p,copy,2,palette.id())),StandardCharsets.UTF_8);
        check(alreadyPainted.contains("net-colour-preview-live")&&!alreadyPainted.contains("unit-"+copy.name+"-full"),"Already-painted unit uses streamed preview, never missing variant atlas icon");
        var tier2=coolplugin.gameplay.ColouringAssets.palettes.find(pal->pal.tier()==2);
        if(tier2!=null){
            setEquipped(plugin,p,tier2.canId());
            String lockedUi=new String(serializeUi(system.buildMenu(p,base,1,"")),StandardCharsets.UTF_8);
            check(lockedUi.contains("Requires colouring station 2")&&lockedUi.contains("paint-can-"+tier2.id()),"T1 displays owned T2 can with requirement text");
            check(!system.apply(p,station,host,tier2.id()),"Tier restrictions remain server-authoritative");
        }
        check(paintedUi.contains("shop-inv-hex-workbench")&&!paintedUi.contains(palette.title()),"Paints use icon-only hex panel, not text palette buttons");
        check(coolplugin.gameplay.ColouringAssets.paletteAliases.get("unit-dp-corrode-unit").equals(coolplugin.gameplay.ColouringAssets.paletteAliases.get("unit-dp-dissociate-unit")),"Corrode and Dissociate share one scheme rather than duplicate cans");
        int[] target={0x101010,0x202020,0x303030,0x404040,0x505050,0x606060,0x800000,0xa00000,0xc00000,0xff0000};
        var pixels=new java.awt.image.BufferedImage(4,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0,0,0xff989aa4);pixels.setRGB(1,0,0xfff98dc9);pixels.setRGB(2,0,0x7f565666);pixels.setRGB(3,0,0x00123456);
        var mapped=coolplugin.gameplay.ColouringAssets.recolour(pixels,target);
        check((mapped.getRGB(0,0)&0xffffff)==target[3]&&(mapped.getRGB(1,0)&0xffffff)==target[8],"Armour maps to armour and accents map to accents, not one mixed brightness ramp");
        check((mapped.getRGB(2,0)>>>24)==127&&mapped.getRGB(3,0)==pixels.getRGB(3,0),"Recolour preserves alpha and transparent pixels exactly; no added edges or lines");
        int alphaErrors=0;
        for(var pal:coolplugin.gameplay.ColouringAssets.palettes)for(String unitName:new String[]{"dagger","toxopid","oct","dp-corrode-unit","dp-dissociate-unit"}){
            Fi originalIcon=workConfig.child("colouring-cache/previews/base-"+unitName+".png");
            Fi paintedIcon=workConfig.child("colouring-cache/previews/"+pal.spritePrefix()+unitName+".png");
            if(!originalIcon.exists()||!paintedIcon.exists()){alphaErrors++;continue;}
            var originalPixels=javax.imageio.ImageIO.read(originalIcon.file());var paintedPixels=javax.imageio.ImageIO.read(paintedIcon.file());
            if(originalPixels.getWidth()!=paintedPixels.getWidth()||originalPixels.getHeight()!=paintedPixels.getHeight()){alphaErrors++;continue;}
            for(int y=0;y<originalPixels.getHeight();y++)for(int x=0;x<originalPixels.getWidth();x++)
                if((originalPixels.getRGB(x,y)>>>24)!=(paintedPixels.getRGB(x,y)>>>24))alphaErrors++;
        }
        check(alphaErrors==0,"Actual body/full-icon recolours retain every source alpha pixel and dimensions across all schemes");
        var itemSystem=plugin.getPlayerItemSystem();var catalog=itemSystem.getCatalog();
        var ember=catalog.findItem("temp-ember-vfx");var injector=catalog.findItem("temp-overclock");
        check(ember!=null&&injector!=null&&catalog.findItem("temp-mending-vfx")!=null,"Three temporary craftable effect examples are loaded");
        check(ember.resolveHaloFx()==mindustry.content.Fx.burning&&ember.wearerStatuses.isEmpty(),"Cosmetic burning uses status VFX without applying burning");
        var entries=new arc.struct.Seq<coolplugin.gameplay.WorldItemLogicManager.CompiledVisualEntry>();
        coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(5000,null,host,arc.struct.Seq.with(ember.id),catalog,entries);
        check(entries.isEmpty(),"VFX-only item spawns no attachment sprite or geometric halo");
        Unit wearer=UnitTypes.dagger.create(Team.sharded);wearer.set(240,240);wearer.add();
        var equipped=arc.struct.Seq.with(injector.id);
        itemSystem.updateVisualRigForUnit("effect-check",wearer,equipped,itemSystem.computeStats(equipped));
        check(wearer.hasEffect(mindustry.content.StatusEffects.overclock),"Active wearer item applies real configured status");
        equipped=arc.struct.Seq.with("wb-01","wb-02","wb-03","wb-04",injector.id);
        itemSystem.updateVisualRigForUnit("effect-check",wearer,equipped,itemSystem.computeStats(equipped));
        check(wearer.getDuration(mindustry.content.StatusEffects.overclock)==0f,"Moving status item to slot 5 removes its granted status");
        wearer.apply(mindustry.content.StatusEffects.overclock,900f);
        equipped=arc.struct.Seq.with(injector.id);itemSystem.updateVisualRigForUnit("effect-check",wearer,equipped,itemSystem.computeStats(equipped));
        itemSystem.destroyRig("effect-check");
        check(wearer.hasEffect(mindustry.content.StatusEffects.overclock),"Removing item preserves a status that existed before equipping");
        wearer.unapply(mindustry.content.StatusEffects.overclock);
        injector.wearerStatuses.add("overdrive");
        itemSystem.updateVisualRigForUnit("effect-check",wearer,equipped,itemSystem.computeStats(equipped));itemSystem.destroyRig("effect-check");
        check(wearer.getDuration(mindustry.content.StatusEffects.overdrive)==0f,"Permanent statuses granted by an item are removed when the item rig is destroyed");
        injector.wearerStatuses.remove("overdrive");
        var cannon=catalog.findItem("wb-06");
        check(cannon.resolveShootSound()!=null,"Configured subweapon sound resolves to a real Sounds field");
        cannon.subweaponShootSound="invalid-nonexistent";
        check(cannon.resolveShootSound()==null,"Invalid shoot sounds are safely silent");cannon.subweaponShootSound="shoot";
        var oldNet=Vars.net;var sounds=new Seq<mindustry.gen.SoundAtCallPacket>();var muzzlePackets=new Seq<mindustry.gen.EffectCallPacket>();
        Vars.net=new Net(null){@Override public boolean server(){return true;}@Override public boolean active(){return true;}
            @Override public void send(Object packet,boolean reliable){if(packet instanceof mindustry.gen.SoundAtCallPacket sound)sounds.add(sound);if(packet instanceof mindustry.gen.EffectCallPacket fxPacket)muzzlePackets.add(fxPacket);}};
        cannon.subweaponSoundVolume=0.37f;cannon.subweaponSoundPitch=1.23f;
        wearer.isShooting=true;wearer.aimX=wearer.x+80;wearer.aimY=wearer.y;
        equipped=arc.struct.Seq.with(cannon.id);
        itemSystem.updateVisualRigForUnit("sound-check",wearer,equipped,itemSystem.computeStats(equipped));
        check(!sounds.isEmpty()&&Math.abs(sounds.first().volume-0.37f)<0.001f&&Math.abs(sounds.first().pitch-1.23f)<0.001f,
            "Firing a subweapon emits native positional sound packets with configured volume and pitch");
        check(muzzlePackets.size>=2,"Real subweapon shots emit both muzzle-flash and smoke effect packets");
        Vars.net=oldNet;
        var recoilManager=itemSystem.getWorldLogicManager();var recoilProc=recoilManager.getProcessorForOwner("sound-check");
        var recoilEntry=recoilManager.getCompiledEntries("sound-check").first();
        check(recoilProc.executor.optionalVar("shot"+recoilEntry.primaryMarkerId()).numval==Vars.state.tick+1d,
            "Authoritative shot timestamp is bound to its exact barrel marker");
        Time.delta=1f;Vars.logicVars.update();recoilProc.executor.optionalVar("hostUnit").setobj(wearer);for(int instruction=0;instruction<2000;instruction++)recoilProc.executor.runOnce();
        var kicked=(mindustry.game.MapObjectives.TextureMarker)Vars.state.markers.get(recoilEntry.primaryMarkerId());
        float kickedX=kicked.pos.x,kickedY=kicked.pos.y;
        double savedTick=Vars.state.tick;Vars.state.tick+=40;Time.delta=1f;Vars.logicVars.update();recoilProc.executor.optionalVar("hostUnit").setobj(wearer);for(int instruction=0;instruction<2000;instruction++)recoilProc.executor.runOnce();
        var recovered=(mindustry.game.MapObjectives.TextureMarker)Vars.state.markers.get(recoilEntry.primaryMarkerId());
        check(arc.math.Mathf.dst(kickedX,kickedY,recovered.pos.x,recovered.pos.y)>1f,
            "Native WorldProcessor marker visibly recoils then recovers after the server shot");
        Vars.state.tick=savedTick;Vars.logicVars.update();
        itemSystem.destroyRig("sound-check");wearer.isShooting=false;
        Fi saved=workConfig.child("effect-catalog-roundtrip.hjson");coolplugin.gameplay.ShopConfigIO.save(saved,catalog);
        var roundtrip=coolplugin.gameplay.ShopConfigIO.load(saved);
        check(roundtrip.findItem(injector.id).wearerStatuses.contains("overclock")&&"burning".equals(roundtrip.findItem(ember.id).vfxStatus)
            &&"shoot".equals(roundtrip.findItem("wb-06").subweaponShootSound),"Status, cosmetic VFX and sound configuration survive save/load");
        check(catalog.items.allMatch(item->item.weight>0),"Every shipped/generated item has positive carrying weight");
        var load=Seq.with("wb-01","wb-02","wb-03","wb-04","wb-05");
        Unit carrier=UnitTypes.oct.create(Team.sharded);
        var lightStats=itemSystem.computeStats(load,wearer);var heavyStats=itemSystem.computeStats(load,carrier);
        check(lightStats.totalWeight==50f&&heavyStats.totalWeight==50f,"Weight sums all slots, including slot 5");
        check(lightStats.weightSpeedFactor<heavyStats.weightSpeedFactor&&lightStats.speedMult<heavyStats.speedMult,
            "Same item load penalizes low-base-health units more than high-health units");
        wearer.health=1f;
        check(itemSystem.computeStats(load,wearer).weightSpeedFactor==lightStats.weightSpeedFactor,"Injuries do not change weight carrying capacity");
        float savedHealthBonus=catalog.findItem("wb-01").healthMultBonus;
        catalog.findItem("wb-01").healthMultBonus=100f;
        check(itemSystem.computeStats(load,wearer).weightSpeedFactor==lightStats.weightSpeedFactor,"Item HP buffs cannot inflate weight carrying capacity");
        catalog.findItem("wb-01").healthMultBonus=savedHealthBonus;
        check(itemSystem.weightFactor(0,150)==1f&&itemSystem.weightFactor(100000,150)==catalog.minimumWeightSpeedFactor,
            "Zero load has no weight penalty and extreme load respects the configured floor");
        check(roundtrip.findItem("wb-06").weight==cannon.weight&&"duo".equals(roundtrip.findItem("wb-06").worldTextureName()),
            "Weight and vanilla mounted sprite survive configuration roundtrip");
        String savedIcon=cannon.iconSprite,savedWorld=cannon.worldSprite;
        cannon.iconSprite="item-copper-ui";cannon.worldSprite="lancer";
        check("item-copper-ui".equals(cannon.uiDrawable())&&"lancer".equals(cannon.worldTextureName()),"Item icon and world sprite are independently selectable");
        cannon.iconSprite=savedIcon;cannon.worldSprite=savedWorld;
        var visualEntries=new Seq<coolplugin.gameplay.WorldItemLogicManager.CompiledVisualEntry>();
        var haloItem=catalog.items.find(item->item.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.HALO);
        String haloCode=coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(200000,null,wearer,Seq.with(haloItem.id),catalog,visualEntries);
        check(!visualEntries.isEmpty()&&visualEntries.allMatch(entry->entry.connectionLineMarkerId()>=0)
            &&haloCode.contains("innerRot"),"Each halo anchor has an outer polygon plus counter-rotating inner element");
        itemSystem.updateVisualRigForUnit("halo-check",wearer,Seq.with(haloItem.id),itemSystem.computeStats(Seq.with(haloItem.id),wearer));
        var haloProc=itemSystem.getWorldLogicManager().getProcessorForOwner("halo-check");haloProc.update();
        var haloEntry=itemSystem.getWorldLogicManager().getCompiledEntries("halo-check").first();
        check(Vars.state.markers.get(haloEntry.primaryMarkerId()) instanceof mindustry.game.MapObjectives.ShapeMarker
            &&Vars.state.markers.get(haloEntry.connectionLineMarkerId()) instanceof mindustry.game.MapObjectives.ShapeMarker,
            "Real WorldProcessor executes both halo shape elements");
        itemSystem.destroyRig("halo-check");
        check(catalog.items.select(item->item.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.SUBWEAPON)
            .allMatch(item->!item.worldTextureName().startsWith("dp-")),"Every shipped turret-mount item uses a vanilla mounted sprite");
        check(coolplugin.gameplay.WorldItemLogicManager.mountLayerOffset(-10)>coolplugin.gameplay.WorldItemLogicManager.mountLayerOffset(10),
            "Rear-mounted item depth is above front-mounted item depth, independent of slot order");
        itemSystem.applyStatsToUnit(wearer,lightStats);
        check(Math.abs(wearer.applyDynamicStatus().speedMultiplier-lightStats.speedMult)<0.0001f,
            "Runtime applies the health-based weight penalty exactly once");
        itemSystem.applyStatsToUnit(wearer,itemSystem.computeStats(load));
        check(Math.abs(wearer.applyDynamicStatus().speedMultiplier-lightStats.speedMult)<0.0001f,
            "Legacy unbound stats acquire the correct weight penalty when applied to a unit");
        var depthIds=Seq.with("wb-07","bz-13");
        itemSystem.updateVisualRigForUnit("depth-check",wearer,depthIds,itemSystem.computeStats(depthIds,wearer));
        var depthProc=itemSystem.getWorldLogicManager().getProcessorForOwner("depth-check");
        depthProc.executor.optionalVar("hostUnit").setobj(wearer);for(int instruction=0;instruction<2000;instruction++)depthProc.executor.runOnce();
        var depthEntries=itemSystem.getWorldLogicManager().getCompiledEntries("depth-check");
        var front=depthEntries.max(entry->entry.localForwardWorld());var rear=depthEntries.min(entry->entry.localForwardWorld());
        var layerField=mindustry.game.MapObjectives.ObjectiveMarker.class.getDeclaredField("drawLayer");layerField.setAccessible(true);
        check(front.localForwardWorld()>rear.localForwardWorld()&&layerField.getFloat(Vars.state.markers.get(front.primaryMarkerId()))<layerField.getFloat(Vars.state.markers.get(rear.primaryMarkerId())),
            "Executed native marker draw layers put rear mounts above front mounts");
        itemSystem.destroyRig("depth-check");
        int clippedHalos=0,maxHaloLines=0;
        var savedAnchor=haloItem.haloAnchor;
        for(UnitType unitType:Vars.content.units())if(unitType.playerControllable&&!unitType.hidden){
            Unit sample=unitType.create(Team.sharded);
            for(var anchor:coolplugin.gameplay.ShopItemDef.HaloAnchor.values()){
                haloItem.haloAnchor=anchor;
                var points=new Seq<arc.math.geom.Vec2>();coolplugin.gameplay.PlayerItemSystem.collectHaloAnchors(sample,anchor,points);
                String allHaloCode=coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(200000,null,sample,Seq.with(haloItem.id,haloItem.id,haloItem.id,haloItem.id),catalog,visualEntries);
                maxHaloLines=Math.max(maxHaloLines,allHaloCode.split("\n").length);
                if(visualEntries.size!=4*points.size){clippedHalos++;System.out.println("HALO CLIP "+unitType.name+" "+anchor+" points="+points.size+" got="+visualEntries.size);}
            }
        }
        haloItem.haloAnchor=savedAnchor;
        check(maxHaloLines<1000,"Shared halo subroutine stays within the native parser limit on every controllable base unit");
        check(clippedHalos==0,"Two-element halos fit all four active slots on every controllable base unit without marker clipping");
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
