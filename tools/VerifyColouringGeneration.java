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
        for(String folder:new String[]{"liquids","items","blocks","units","statuses"}) {
            ContentType ct=folder.equals("statuses")?ContentType.status:ContentType.valueOf(folder.substring(0,folder.length()-1));
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
        system.open(p,station,host);
        var sessionsField=coolplugin.gameplay.ColouringSystem.class.getDeclaredField("sessions");sessionsField.setAccessible(true);
        var sessionsMap=(arc.struct.ObjectMap<?,?>)sessionsField.get(system);
        Object paintSession=sessionsMap.values().next();
        var previewTextureField=paintSession.getClass().getDeclaredField("previewTexture");previewTextureField.setAccessible(true);
        String firstPreviewKey=(String)previewTextureField.get(paintSession);
        var showMethod=coolplugin.gameplay.ColouringSystem.class.getDeclaredMethod("show",paintSession.getClass());showMethod.setAccessible(true);
        showMethod.invoke(system,paintSession);
        check(!firstPreviewKey.isEmpty()&&!firstPreviewKey.equals(previewTextureField.get(paintSession)),
            "Every colouring dialog gets a distinct streamed texture key, avoiding replacement/disposal races");

        var selectedField=paintSession.getClass().getDeclaredField("selected");selectedField.setAccessible(true);selectedField.set(paintSession,palette.id());
        var tokenField=paintSession.getClass().getDeclaredField("token");tokenField.setAccessible(true);
        var menuResult=new mindustry.ui.builder.MenuResult();menuResult.token=tokenField.getLong(paintSession);menuResult.result="apply";
        var resultMethod=coolplugin.gameplay.ColouringSystem.class.getDeclaredMethod("result",Player.class,mindustry.ui.builder.MenuResult.class);resultMethod.setAccessible(true);
        resultMethod.invoke(system,p,menuResult);
        check("".equals(selectedField.get(paintSession)),"Successful paint clears hypothetical selection and shows actual recoloured unit");
        check(host.type==copy&&host.health==61&&host.shield==23,
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
        host.remove();station.handlePayload(station,new mindustry.world.blocks.payloads.UnitPayload(host));system.open(p,station,host);
        boolean destructionSessionOpen=!sessionsMap.isEmpty();
        arc.Events.fire(new mindustry.game.EventType.BlockDestroyEvent(station.tile));
        check(destructionSessionOpen&&sessionsMap.isEmpty(),"Station destruction immediately discards the open menu session");
        station.takePayload();station.enabled=true;
        coolplugin.gameplay.UiSpriteSync.reloadSprites(workConfig);
        check(coolplugin.gameplay.UiSpriteSync.hasSprite("colouring-station-2-ui")
            &&coolplugin.gameplay.UiSpriteSync.resolveMenuDrawable("colouring-station-2-ui").equals("net-colouring-station-2-ui"),
            "Generated T2 station icon is included in reload-safe UI texture streams");
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
        check(transferred==0x5b739a,"Unknown RGB colours stay unchanged rather than being guessed");
        migrationEq=plugin.getPlayerItemSystem().getEquipment(p);migrationEq.equipped.add("paint-turret-duo");plugin.getPlayerItemSystem().saveEquipment(p,migrationEq);
        plugin.getPlayerItemSystem().getEquipment(p);
        var retired=plugin.getPlayerItemSystem().getCatalog().findItem("paint-turret-duo");
        check(retired!=null&&!retired.craftable&&retired.decraftable,"Retired turret cans remain salvageable without regenerating turret palettes");
        check(emptyUi.contains("net-colour-preview-"),"Empty inventory still displays the player unit preview");
        check(workConfig.child("colouring-cache/previews/base-dagger.png").exists(),"Unpainted full preview is cached server-side");
        String alreadyPainted=new String(serializeUi(system.buildMenu(p,copy,2,palette.id())),StandardCharsets.UTF_8);
        check(alreadyPainted.contains("net-colour-preview-")&&!alreadyPainted.contains("unit-"+copy.name+"-full"),"Already-painted unit uses streamed preview, never missing variant atlas icon");
        var tier2=coolplugin.gameplay.ColouringAssets.palettes.find(pal->pal.tier()==2);
        if(tier2!=null){
            setEquipped(plugin,p,tier2.canId());
            String lockedUi=new String(serializeUi(system.buildMenu(p,base,1,"")),StandardCharsets.UTF_8);
            check(lockedUi.contains("Requires")&&lockedUi.contains("colouring-station-2-ui")&&lockedUi.contains("paint-can-"+tier2.id()),"T1 displays owned T2 can with requirement text");
            check(!system.apply(p,station,host,tier2.id()),"Tier restrictions remain server-authoritative");
        }
        check(paintedUi.contains("shop-inv-hex-workbench")&&!paintedUi.contains(palette.title()),"Paints use icon-only hex panel, not text palette buttons");
        check(coolplugin.gameplay.ColouringAssets.paletteAliases.get("unit-dp-corrode-unit").equals(coolplugin.gameplay.ColouringAssets.paletteAliases.get("unit-dp-dissociate-unit")),"Corrode and Dissociate share one scheme rather than duplicate cans");
        int[] target={0x101010,0x202020,0x303030,0x404040,0x505050,0x606060,0x800000,0xa00000,0xc00000,0xff0000};
        var pixels=new java.awt.image.BufferedImage(4,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0,0,0xff989aa4);pixels.setRGB(1,0,0xfff98dc9);pixels.setRGB(2,0,0x7f565666);pixels.setRGB(3,0,0x00123456);
        var mapped=coolplugin.gameplay.ColouringAssets.recolour(pixels,target);
        check((mapped.getRGB(0,0)&0xffffff)==target[4]&&(mapped.getRGB(1,0)&0xffffff)==target[8],"Armour maps to armour and accents map to accents, not one mixed brightness ramp");
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
        check(roundtrip.findItem("wb-06").weight==cannon.weight&&"dp-item-mount-wb-06".equals(roundtrip.findItem("wb-06").worldTextureName()),
            "Weight and repaletted mounted sprite survive configuration roundtrip");
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
            .allMatch(item->workConfig.child("assets/sprites/item-mounts/item-mount-"+item.id+".png").exists()),"Every shipped turret-mount item has a generated repaletted weapon sprite");
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
        var mixed=Seq.with(haloItem.id,haloItem.id,haloItem.id,haloItem.id);
        haloItem.haloAnchor=coolplugin.gameplay.ShopItemDef.HaloAnchor.WEAPONS;
        int maxMixed=0;
        for(int n=0;n<=6;n++){
            if(n>0)mixed.add(cannon.id);
            for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
                String code=coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(200000,null,type.create(Team.sharded),mixed,catalog,visualEntries);
                maxMixed=Math.max(maxMixed,(int)code.lines().count());
                mindustry.logic.LAssembler assembler=mindustry.logic.LAssembler.assemble(code,true);
                if(assembler.instructions.length==0)throw new AssertionError("Empty mixed rig");
            }
        }
        haloItem.haloAnchor=savedAnchor;
        check(maxMixed<1000,"Purchase progression from 4 to 10 slots preserves complete native MLOG, including six spring/rope follows");
        boolean followsAfterPurchase=true;
        mixed=Seq.with(haloItem.id,haloItem.id,haloItem.id,haloItem.id);
        for(int n=0;n<=6;n++){
            if(n>0)mixed.add(cannon.id);
            wearer.set(180,180);
            itemSystem.updateVisualRigForUnit("purchase-follow",wearer,mixed,itemSystem.computeStats(mixed,wearer));
            var proc=itemSystem.getWorldLogicManager().getProcessorForOwner("purchase-follow");
            proc.executor.optionalVar("hostUnit").setobj(wearer);
            for(int step=0;step<10000;step++)proc.executor.runOnce();
            wearer.set(240,240);
            for(int step=0;step<10000;step++)proc.executor.runOnce();
            var ux=proc.executor.optionalVar("ux");var uy=proc.executor.optionalVar("uy");
            followsAfterPurchase&=ux!=null&&uy!=null&&Math.abs(ux.num()-30)<0.01&&Math.abs(uy.num()-30)<0.01;
            for(var entry:itemSystem.getWorldLogicManager().getCompiledEntries("purchase-follow")){
                var marker=Vars.state.markers.get(entry.primaryMarkerId());
                if(marker instanceof mindustry.game.MapObjectives.PosMarker pos)followsAfterPurchase&=Float.isFinite(pos.pos.x)&&Float.isFinite(pos.pos.y);
                else followsAfterPurchase=false;
            }
        }
        itemSystem.destroyRig("purchase-follow");
        check(followsAfterPurchase,"Real native executor keeps tracking the moving host after each inventory rebuild from 4 through 10 slots");
        var visibleCan=catalog.findItem(palette.canId());
        setEquipped(plugin,p);visibleCan.hidden=false;
        String unavailableUi=new String(serializeUi(system.buildMenu(p,base,1,"")),StandardCharsets.UTF_8);
        check(unavailableUi.contains("paint-can-"+palette.id())&&unavailableUi.contains("No paint cans owned"),"Unavailable hidden:false paints remain listed and disabled");
        visibleCan.hidden=true;
        var groundFx=Vars.content.statusEffect("dp-mount-ground-flash");
        var airFx=Vars.content.statusEffect("dp-mount-air-flash");
        System.out.println("MOUNT FX LAYERS "+(groundFx==null?"null":groundFx.effect.layer)+" / "+(airFx==null?"null":airFx.effect.layer));
        check(groundFx!=null&&airFx!=null&&groundFx.effect.layer>60.55f&&airFx.effect.layer>115.55f,
            "Native datapatch shooting effects render above ground and airborne mounted weapons");
        check(Vars.content.block("dp-colouring-station").category==mindustry.type.Category.effect
            &&Vars.content.block("dp-colouring-station-2").category==mindustry.type.Category.effect,
            "Both colouring stations share Workbench/Bazaar's effect build category");
        String fallbackUi=new String(serializeUi(system.buildMenu(p,base,2,palette.id())),StandardCharsets.UTF_8);
        check(fallbackUi.contains("dp-colour-preview-"+copy.name)
            &&workConfig.child("assets/sprites/colouring-generated/colour-preview-"+copy.name+".png").exists(),
            "Station preview has a real packed fallback image even if its dynamic stream never arrives");
        int missingHostTextures=0;
        for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            String texture=coolplugin.gameplay.MountLayout.texture(type.create(Team.sharded),cannon);
            if(!workConfig.child("assets/sprites/item-mounts/"+texture.substring(3)+".png").exists())missingHostTextures++;
        }
        check(missingHostTextures==0,"Every controllable original and painted host resolves an existing generated mount texture");
        int outlinedErrors=0;
        for(var def:catalog.items)if(def.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.MOUNT||def.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.SUBWEAPON){
            var image=javax.imageio.ImageIO.read(workConfig.child("assets/sprites/item-mounts/item-mount-"+def.id+".png").file());
            if(!coolplugin.gameplay.ColouringAssets.hasOutline(image))outlinedErrors++;
        }
        check(outlinedErrors==0,"Every mounted weapon source has a real visible outline; unoutlined sources are excluded");
        String nativeMount=coolplugin.gameplay.MountLayout.texture(wearer,cannon);
        Unit paintedHost=copy.create(Team.sharded);
        String paintedMount=coolplugin.gameplay.MountLayout.texture(paintedHost,cannon);
        check(!nativeMount.equals(paintedMount)&&nativeMount.contains("-host-dagger")&&paintedMount.contains("-scheme-"+palette.id())
            &&workConfig.child("assets/sprites/item-mounts/"+paintedMount.substring(3)+".png").exists(),
            "Mount texture follows the host's native or painted palette, not the item's old colour");
        var layoutIds=Seq.with("wb-06","bz-07","wb-07","bz-13");
        boolean bounded=true,distinct=true;
        for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            var placements=coolplugin.gameplay.MountLayout.resolve(type.create(Team.sharded),layoutIds,catalog);
            float extent=Math.max(1.5f,Math.max(4f,type.hitSize*.5f)*.70f);
            for(var placement:placements)bounded&=Math.abs(placement.forward())<=extent+.01f&&Math.abs(placement.side())<=extent+.01f;
            for(int i=0;i<placements.size;i++)for(int j=i+1;j<placements.size;j++){
                var a=placements.get(i);var b=placements.get(j);
                distinct&=arc.math.Mathf.dst(a.forward(),a.side(),b.forward(),b.side())>.01f;
            }
        }
        check(bounded&&distinct,"Multiple native-size mounts stay at distinct hull-bounded offsets instead of spreading off-body");
        itemSystem.updateVisualRigForUnit("mirror-test",wearer,Seq.with(cannon.id),itemSystem.computeStats(Seq.with(cannon.id),wearer));
        var mirrorProc=itemSystem.getWorldLogicManager().getProcessorForOwner("mirror-test");mirrorProc.executor.optionalVar("hostUnit").setobj(wearer);
        for(int step=0;step<3000;step++)mirrorProc.executor.runOnce();
        var mirrorEntries=itemSystem.getWorldLogicManager().getCompiledEntries("mirror-test");
        boolean positive=false,negative=false;
        for(var entry:mirrorEntries){var marker=(mindustry.game.MapObjectives.TextureMarker)Vars.state.markers.get(entry.primaryMarkerId());positive|=marker.width>0;negative|=marker.width<0;}
        check(positive&&negative,"Real dual texture markers mirror horizontally, not merely by rotation");itemSystem.destroyRig("mirror-test");
        var duplicates=new coolplugin.database.PlayerDatabase.EquipmentData();
        check(coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon)
            &&coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon)
            &&duplicates.equipped.get(0).equals(cannon.id)&&duplicates.equipped.get(4).equals(cannon.id)
            &&duplicates.equipped.get(1).isBlank(),"Second purchased copy automatically bypasses empty active slots into carry");
        var duplicateStats=itemSystem.computeStats(duplicates.equipped,wearer);
        check(duplicateStats.totalEquipped==2&&duplicateStats.activeSlotsUsed==1&&duplicateStats.overloadCount==1,
            "Empty active placeholders do not add item count, weight or overload penalties");
        for(int i=0;i<5;i++)coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon);
        String beforeFull=duplicates.equipped.toString();
        check(!coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon)&&beforeFull.equals(duplicates.equipped.toString()),
            "Full carry rejects another duplicate without modifying inventory despite empty active slots");
        itemSystem.saveEquipment(p,duplicates);
        var restoredDuplicates=itemSystem.getEquipment(p);
        check(restoredDuplicates.equipped.get(0).equals(cannon.id)&&restoredDuplicates.equipped.get(1).isBlank()
            &&restoredDuplicates.equipped.get(4).equals(cannon.id),"Carry routing and empty active slots persist through database roundtrip");
        check(itemSystem.promoteSlotToActive(p,4).success()&&itemSystem.getEquipment(p).equipped.get(1).isBlank(),
            "Promoting a duplicate never enables a second active copy");
        for(var cost:cannon.resourceCosts)if(cost.resolveItem()!=null)plugin.getBuildSystem().giveItems(p,cost.resolveItem(),cost.amount()*5);
        int resourcesBefore=plugin.getBuildSystem().getAvailableAmount(p,mindustry.content.Items.graphite);
        var rejectedCraft=itemSystem.craft(p,cannon.id,cannon.tier);
        check(!rejectedCraft.success()&&resourcesBefore==plugin.getBuildSystem().getAvailableAmount(p,mindustry.content.Items.graphite),
            "Rejected duplicate purchase consumes no map resources");
        var legacyDuplicates=new coolplugin.database.PlayerDatabase.EquipmentData();legacyDuplicates.equipped.addAll("wb-06","wb-06","wb-06","wb-06","wb-06","wb-06","wb-06","wb-06","wb-06","wb-06");
        coolplugin.gameplay.PlayerItemSystem.normalizeEquipment(legacyDuplicates);
        check(legacyDuplicates.equipped.count(id->id.equals("wb-06"))+legacyDuplicates.stash.get("wb-06",0)==10
            &&legacyDuplicates.equipped.get(1).isBlank(),"Legacy duplicate migration preserves overflow instead of deleting owned items");
        int[] sourceRoles={0x606068,0x787880,0x909098,0xa8a8b0,0xc0c0c8,0xd8d8e0,0x806040,0xa07848,0xc09050,0xe0a858};
        int[] targetRoles={0x545464,0x686878,0x7c7c8c,0x9090a0,0xa4a4b4,0xb8b8c8,0x805080,0xa060a0,0xc070c0,0xe080e0};
        var rolePixels=new java.awt.image.BufferedImage(11,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int i=0;i<10;i++)rolePixels.setRGB(i,0,0xff000000|sourceRoles[i]);rolePixels.setRGB(10,0,0xff404049);
        var roleOutput=coolplugin.gameplay.ColouringAssets.recolour(rolePixels,targetRoles,sourceRoles);
        boolean exactRoles=true;for(int i=0;i<10;i++)exactRoles&=(roleOutput.getRGB(i,0)&0xffffff)==targetRoles[i];
        check(exactRoles&&roleOutput.getRGB(10,0)==0xff404049,"Source-aware recolouring replaces each material role exactly and preserves the outline");
        var allGuns=new Seq<String>();for(var item:catalog.items)if(item.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.SUBWEAPON&&item.isSymmetricallyDuplicated(4f)&&allGuns.size<4)allGuns.add(item.id);
        while(allGuns.size<10)allGuns.add(cannon.id);
        int gunCodeLines=0;
        for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            String gunCode=coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(200000,null,type.create(Team.sharded),allGuns,catalog,visualEntries);
            gunCodeLines=Math.max(gunCodeLines,(int)gunCode.lines().count());
        }
        boolean correctMountLayers=true;
        for(UnitType layerType:new UnitType[]{UnitTypes.dagger,UnitTypes.flare,UnitTypes.zenith}){
            String layerCode=coolplugin.gameplay.WorldItemLogicManager.compileRigMlog(200000,null,layerType.create(Team.sharded),allGuns,catalog,visualEntries);
            correctMountLayers&=layerCode.contains("op min mountBase baseLayer 99")&&layerCode.contains("op add mountLayer mountBase ");
        }
        check(correctMountLayers,"Ground, low and high altitude mounts are ordered below native bullets while preserving mount depth");
        check(gunCodeLines<1000,"Four dual-shooting mounts plus all six carry items fit the native parser including local muzzle flashes");
        boolean multiBudget=true;int maxShardLines=0,maxPrograms=0;
        Seq<String> allFollowers=new Seq<>();for(int i=0;i<10;i++)allFollowers.add(cannon.id);
        var followItem=catalog.items.find(item->item.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.FOLLOW);
        if(followItem!=null)for(int i=0;i<4;i++)allFollowers.set(i,followItem.id);
        for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            for(var loadout:Seq.with(allGuns,allFollowers)){
                var programs=coolplugin.gameplay.WorldItemLogicManager.compileRigPrograms(300000,null,type.create(Team.sharded),loadout,catalog);
                maxPrograms=Math.max(maxPrograms,programs.size);
                for(String code:programs){int lines=(int)code.lines().count();maxShardLines=Math.max(maxShardLines,lines);multiBudget&=lines<1000;}
            }
        }
        check(multiBudget&&maxPrograms>1,"Automatic multi-processor plans stay below parser limit across every playable unit with guns or full followers");
        System.out.println("MULTI PROCESSORS maxParts="+maxPrograms+" maxLines="+maxShardLines);
        var manager=itemSystem.getWorldLogicManager();
        manager.syncRig("multi-check",wearer,allGuns,catalog);
        var parts=manager.getProcessorsForOwner("multi-check");
        var multiEntries=manager.getCompiledEntries("multi-check");
        boolean distinctMarkers=true,allBound=true;var markerIds=new arc.struct.IntSet();
        for(var part:parts){
            allBound&=part.executor.optionalVar("hostUnit").obj()==wearer;
            for(int step=0;step<4000;step++)part.executor.runOnce();
        }
        for(var entry:multiEntries){distinctMarkers&=markerIds.add(entry.primaryMarkerId());if(entry.connectionLineMarkerId()>=0)distinctMarkers&=markerIds.add(entry.connectionLineMarkerId());}
        check(parts.size>1&&distinctMarkers&&allBound,"Live rig allocates distinct processors and marker IDs, all bound to same host");
        boolean childShots=true;
        for(var entry:multiEntries)if(entry.kind()==coolplugin.gameplay.WorldItemLogicManager.RenderedKind.SUBWEAPON){
            manager.kickSubweapon("multi-check",entry.def(),entry.localForwardWorld(),entry.localSideWorld());
            boolean foundShot=false;
            for(var part:parts){var shot=part.executor.optionalVar("shot"+entry.primaryMarkerId());if(shot!=null)foundShot|=shot.num()>0;}
            childShots&=foundShot;
        }
        check(childShots,"Authoritative shoot tokens route to the owning processor including child processors");
        boolean allVisible=true;for(var entry:multiEntries)allVisible&=Vars.state.markers.get(entry.primaryMarkerId())!=null;
        check(allVisible,"Native executors create every visual across all processor shards");
        manager.syncRig("multi-check",wearer,Seq.with(cannon.id),catalog);
        check(manager.getProcessorsForOwner("multi-check").size==1,"Shrinking equipment frees surplus child processors");
        manager.clearRig("multi-check");boolean cleaned=true;
        for(var entry:multiEntries){cleaned&=Vars.state.markers.get(entry.primaryMarkerId())==null;if(entry.connectionLineMarkerId()>=0)cleaned&=Vars.state.markers.get(entry.connectionLineMarkerId())==null;}
        check(cleaned&&manager.getProcessorsForOwner("multi-check").isEmpty(),"Clearing a rig removes every shard and all old markers");
        var octScheme=coolplugin.gameplay.ColouringAssets.palette("unit-oct");
        var octDagger=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant("dagger",octScheme));
        check(octDagger!=null&&octDagger.weapons.size==UnitTypes.dagger.weapons.size
            &&octDagger.weapons.allMatch(w->w.name.startsWith("dp-paint-unit-oct-")
                &&workConfig.child("assets/sprites/colouring-generated/"+w.name.substring(3)+".png").exists()),
            "Dagger painted with Oct retains both normal weapons with resolvable recoloured sprite names");
        var daggerPixels=javax.imageio.ImageIO.read(workConfig.child("colouring-cache/previews/base-dagger.png").file());
        var greenPixels=javax.imageio.ImageIO.read(workConfig.child("colouring-cache/previews/paint-unit-oct-dagger.png").file());
        int exactPaintErrors=0,orangePixels=0;
        for(int y=0;y<daggerPixels.getHeight();y++)for(int x=0;x<daggerPixels.getWidth();x++){
            int rgb=daggerPixels.getRGB(x,y)&0xffffff;if((daggerPixels.getRGB(x,y)>>>24)==0)continue;
            if(rgb==0xffa664){orangePixels++;if((greenPixels.getRGB(x,y)&0xffffff)!=0x84f490)exactPaintErrors++;}
            if(rgb==0xd06b53&&((greenPixels.getRGB(x,y)&0xffffff)!=0x62ae7e))exactPaintErrors++;
        }
        check(orangePixels>0&&exactPaintErrors==0,"Dagger-to-Oct maps exact orange light/dark accents to Oct's actual green light/dark RGB values");
        check(workConfig.child("alphystuff/unit-palettes.hjson").exists()&&coolplugin.gameplay.ManualUnitPalettes.source("oct")[8]==0x84f490,
            "Editable manual palette tables, not histogram extraction, define the shipped schemes");
        itemSystem.updateVisualRigForUnit("flash-check",wearer,Seq.with(cannon.id),itemSystem.computeStats(Seq.with(cannon.id),wearer));
        var flashProc=itemSystem.getWorldLogicManager().getProcessorForOwner("flash-check");flashProc.executor.optionalVar("hostUnit").setobj(wearer);
        for(int step=0;step<3000;step++)flashProc.executor.runOnce();
        var flashEntries=itemSystem.getWorldLogicManager().getCompiledEntries("flash-check");
        for(var entry:flashEntries)itemSystem.getWorldLogicManager().kickSubweapon("flash-check",entry.def(),entry.localForwardWorld(),entry.localSideWorld());
        for(int step=0;step<3000;step++)flashProc.executor.runOnce();
        boolean visibleFlashes=true,nativeScale=true;
        var nativeSize=coolplugin.gameplay.ColouringAssets.mountSizes.get(cannon.id);
        for(var entry:flashEntries){
            var sprite=(mindustry.game.MapObjectives.TextureMarker)Vars.state.markers.get(entry.primaryMarkerId());
            var flash=(mindustry.game.MapObjectives.ShapeMarker)Vars.state.markers.get(entry.connectionLineMarkerId());
            visibleFlashes&=flash.radius>0&&flash.color.a>0&&layerField.getFloat(flash)>layerField.getFloat(sprite);
            nativeScale&=Math.abs(Math.abs(sprite.width)/nativeSize.x-1f)<.001f&&Math.abs(sprite.height/nativeSize.y-1f)<.001f;
        }
        check(visibleFlashes,"Both dual mounts display real local muzzle flashes above the weapon layer after authoritative shots");
        check(nativeScale,"Mount markers render at 100 percent native sprite dimensions, within the requested 10 percent limit");
        float originalMass=cannon.weight;
        var originalDimensions=coolplugin.gameplay.ColouringAssets.mountSizes.get(cannon.id).cpy();
        coolplugin.gameplay.ColouringAssets.mountSizes.put(cannon.id,new arc.math.geom.Vec2(40,80));
        check(cannon.carryingWeight()>=500,"Large native sprites receive a size-based weight floor instead of being shrunk into tiny mounts");
        coolplugin.gameplay.ColouringAssets.mountSizes.put(cannon.id,originalDimensions);cannon.weight=originalMass;
        itemSystem.destroyRig("flash-check");
        var tech=plugin.getTechTree();tech.reloadTree();var progress=tech.getProgressStore();var researchNode=tech.findNode("graphite-press");
        p.team(Team.sharded);progress.unlockSingle(p.uuid(),p.plainName(),researchNode,tech.getRoot());
        progress.addPaidAmount(p.uuid(),p.plainName(),"faction-payment-probe","copper",37,tech.getRoot());
        progress.setFlag(p.uuid(),p.plainName(),"faction-flag-probe",true,tech.getRoot());
        String shardedScope=progress.scope(p.uuid());
        p.team(Team.crux);
        check(!progress.isUnlocked(p.uuid(),researchNode)&&progress.getPaidAmount(p.uuid(),"faction-payment-probe","copper")==0
            &&!progress.hasFlag(p.uuid(),"faction-flag-probe")&&!shardedScope.equals(progress.scope(p.uuid())),
            "Switching live team to Crux isolates research unlocks, partial payments and condition flags from Sharded");
        progress.addPaidAmount(p.uuid(),p.plainName(),"faction-payment-probe","copper",9,tech.getRoot());
        p.team(Team.sharded);
        check(progress.isUnlocked(p.uuid(),researchNode)&&progress.getPaidAmount(p.uuid(),"faction-payment-probe","copper")==37
            &&progress.hasFlag(p.uuid(),"faction-flag-probe"),"Switching back restores only Sharded's research progress");
        Vars.state.rules.researched.add(mindustry.content.Blocks.graphitePress);
        p.team(Team.crux);
        check(!tech.buildPlayerRules(p).researched.contains(mindustry.content.Blocks.graphitePress),
            "Per-faction client rules cannot inherit another faction's researched set from global world rules");
        boolean otherFactionsIsolated=true;
        for(var faction:new coolplugin.gameplay.FactionDef[]{coolplugin.gameplay.FactionDef.MALIS,coolplugin.gameplay.FactionDef.NEOPLASM}){
            p.team(faction.team());otherFactionsIsolated&=!progress.isUnlocked(p.uuid(),researchNode)
                &&progress.getPaidAmount(p.uuid(),"faction-payment-probe","copper")==0&&!progress.hasFlag(p.uuid(),"faction-flag-probe");
        }
        int[] seamSource=coolplugin.gameplay.ManualUnitPalettes.source("dagger");
        int[] seamTarget=coolplugin.gameplay.ManualUnitPalettes.source("oct");
        check(coolplugin.gameplay.ManualUnitPalettes.map(0xf99f62,seamSource,seamTarget)==seamTarget[8]
            &&coolplugin.gameplay.ManualUnitPalettes.map(0xcc735f,seamSource,seamTarget)==seamTarget[6],
            "Explicit intermediate orange shades no longer leave original-colour pixel seams");
        check(coolplugin.gameplay.ManualUnitPalettes.map(0x123456,seamSource,seamTarget)==0x123456,
            "Seam corrections leave unknown RGB values untouched");
        check(otherFactionsIsolated,"Malis and Neoplasm also retain their own independent research profiles");
        p.team(Team.sharded);Vars.state.rules.researched.remove(mindustry.content.Blocks.graphitePress);
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
