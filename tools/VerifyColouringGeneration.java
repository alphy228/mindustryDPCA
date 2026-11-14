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
        String kickMessage="";
        @Override public void kick(String message){kickMessage=message;kicked=true;}

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
        boolean paletteOutlines=true;
        for(var scheme:coolplugin.gameplay.ColouringAssets.palettes){
            var variant=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant("dagger",scheme));
            paletteOutlines &= (variant.outlineColor.rgba()>>>8)==scheme.ramp()[0];
        }
        check(paletteOutlines,"Generated unit outline colour matches each selected palette's manual outline role");
        check(copy.health==base.health && copy.speed==base.speed && copy.weapons.size==base.weapons.size,
            "Variant retains gameplay and exact weapon count: base="+base.weapons.size+" copy="+copy.weapons.size);
        check(errors==0,"All generated content parses without errors");
        var diss=Vars.content.unit("dp-dissociate-unit");
        var paintedDiss=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant(diss.name,palette));
        var sourceDiss=arc.util.serialization.Jval.read(workConfig.child("assets/content/units/dissociate-unit.hjson").readString());
        var outputDiss=arc.util.serialization.Jval.read(workConfig.child("assets/content/units/colouring-generated/"+paintedDiss.name.substring(3)+".hjson").readString());
        boolean authoredWeapons=true;
        for(int wi=0;wi<sourceDiss.get("weapons").asArray().size;wi++){
            var sw=sourceDiss.get("weapons").asArray().get(wi);
            var pw=outputDiss.get("weapons").asArray().get(wi);
            for(var field:sw.asObject())if(!field.key.equals("name")){
                String expected=field.value.toString(arc.util.serialization.Jval.Jformat.formatted);
                String actual=pw.get(field.key).toString(arc.util.serialization.Jval.Jformat.formatted)
                    .replace("dp-"+palette.spritePrefix(),"");
                // Jval interprets unquoted hexadecimal colours beginning with e as Infinity.
                expected=expected.replace("Infinity", "\"6e7080\"");
                boolean equal=expected.equals(actual);
                authoredWeapons &= equal;
            }
        }
        check(authoredWeapons,"Every authored Dissociate weapon field survives recolouring, including sounds and nested effects");
        check(diss.weapons.size==paintedDiss.weapons.size && diss.parts.size==paintedDiss.parts.size,
            "Dissociate retains weapon and glow-part counts after parsing");

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
        check(tree.findNode("dp-copper-container")==null&&tree.findNode("conveyor")==null,"Building nodes are removed from the research tree");
        check(tree.findNode("mace")==null&&tree.findNode("dagger")!=null,"Only tier-one unit research nodes remain");
        tree.reloadTree();
        check(tree.findNode("dp-copper-container")==null,"Unit/item projection remains idempotent on reload");
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
        check(coolplugin.gameplay.UiSpriteSync.resolveMenuDrawable("unit-dp-dissociate-unit-ui").equals("net-colour-preview-dp-dissociate-unit")
            &&coolplugin.gameplay.UiSpriteSync.resolveMenuDrawable("unit-"+paintedDiss.name+"-ui").equals("net-colour-preview-"+paintedDiss.name),
            "Faction picker resolves real streamed icons for original and painted Dissociate");

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
        equipped=legacySlots(arc.struct.Seq.with("wb-01","wb-02","wb-03","wb-04",injector.id));
        itemSystem.updateVisualRigForUnit("effect-check",wearer,equipped,itemSystem.computeStats(equipped));
        check(wearer.getDuration(mindustry.content.StatusEffects.overclock)==0f,"Moving status item into carry removes its granted status");
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
        check(catalog.items.allMatch(item->item.size>=1),"Every shipped/generated item has an explicit positive size");
        check(catalog.findItem("wb-06").size==2&&catalog.items.contains(item->item.size==1),"Graphite gun is size 2 and real size-1 items exist");
        var load=legacySlots(Seq.with("wb-01","wb-02","wb-03","wb-04","wb-05"));
        Unit carrier=UnitTypes.oct.create(Team.sharded);
        var lightStats=itemSystem.computeStats(load,wearer);var heavyStats=itemSystem.computeStats(load,carrier);
        check(lightStats.carryUsed==catalog.findItem("wb-05").size&&heavyStats.carryUsed==lightStats.carryUsed,"Carry fullness sums item sizes, not mass");
        check(lightStats.carrySpeedFactor<heavyStats.carrySpeedFactor,"Same carry size fills a smaller inventory more");
        wearer.health=1f;
        check(itemSystem.computeStats(load,wearer).carrySpeedFactor==lightStats.carrySpeedFactor,"Injuries do not change inventory capacity");
        float savedHealthBonus=catalog.findItem("wb-01").healthMultBonus;
        catalog.findItem("wb-01").healthMultBonus=100f;
        check(itemSystem.computeStats(load,wearer).carrySpeedFactor==lightStats.carrySpeedFactor,"Item HP bonuses cannot inflate capacity");
        catalog.findItem("wb-01").healthMultBonus=savedHealthBonus;
        check(roundtrip.findItem("wb-06").size==2&&"dp-item-mount-wb-06".equals(roundtrip.findItem("wb-06").worldTextureName()),"Size and native mount sprite survive catalog roundtrip");
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
            "Runtime applies carry fullness slowdown exactly once");
        itemSystem.applyStatsToUnit(wearer,itemSystem.computeStats(load,wearer));
        check(Math.abs(wearer.applyDynamicStatus().speedMultiplier-lightStats.speedMult)<0.0001f,
            "Applying stats repeatedly does not compound slowdown");
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
            &&duplicates.equipped.get(0).equals(cannon.id)&&duplicates.equipped.get(30).equals(cannon.id)
            &&duplicates.equipped.get(1).isBlank(),"Second purchased copy automatically bypasses empty active slots into carry");
        var duplicateStats=itemSystem.computeStats(duplicates.equipped,wearer);
        check(duplicateStats.totalEquipped==2&&duplicateStats.activeSlotsUsed==1&&duplicateStats.overloadCount==1,
            "Empty active placeholders do not add item count or carry fullness");
        for(int i=0;i<5;i++)coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon);
        String beforeFull=duplicates.equipped.toString();
        check(!coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(duplicates,cannon)&&beforeFull.equals(duplicates.equipped.toString()),
            "Full carry rejects another duplicate without modifying inventory despite empty active slots");
        var capacityWearer=UnitTypes.mega.spawn(Team.sharded,180,180);p.unit(capacityWearer);
        owned.put(p.uuid(),capacityWearer);
        itemSystem.saveEquipment(p,duplicates);
        var restoredDuplicates=itemSystem.getEquipment(p);
        check(restoredDuplicates.equipped.get(0).equals(cannon.id)&&restoredDuplicates.equipped.get(1).isBlank()
            &&restoredDuplicates.equipped.get(30).equals(cannon.id),"Carry routing and empty active slots persist through database roundtrip");
        check(!itemSystem.promoteSlotToActive(p,30).success()&&itemSystem.getEquipment(p).equipped.get(1).isBlank(),
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
        while(allGuns.size<10)allGuns.add(cannon.id);allGuns=legacySlots(allGuns);
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
        check(gunCodeLines>0,"Full gun diagnostic code is generated before safe 500-instruction sharding");
        boolean multiBudget=true;int maxShardLines=0,maxPrograms=0;
        Seq<String> allFollowers=new Seq<>();for(int i=0;i<10;i++)allFollowers.add(cannon.id);
        var followItem=catalog.items.find(item->item.attachMode==coolplugin.gameplay.ShopItemDef.AttachMode.FOLLOW);
        if(followItem!=null)for(int i=0;i<4;i++)allFollowers.set(i,followItem.id);allFollowers=legacySlots(allFollowers);
        for(UnitType type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            for(var loadout:Seq.with(allGuns,allFollowers)){
                var programs=coolplugin.gameplay.WorldItemLogicManager.compileRigPrograms(300000,null,type.create(Team.sharded),loadout,catalog);
                maxPrograms=Math.max(maxPrograms,programs.size);
                for(String code:programs){int lines=(int)code.lines().count();maxShardLines=Math.max(maxShardLines,lines);multiBudget&=coolplugin.gameplay.WorldItemLogicManager.instructionCount(code)<=500&&programs.size<=10;}
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
        check(parts.size<=10&&parts.allMatch(part->coolplugin.gameplay.WorldItemLogicManager.instructionCount(part.code)<=500),
            "Every live program, including client-local cross-processor follower collisions, fits 500 instructions");
        check(parts.contains(part->part.code.contains("read peerX peer")),"Follower collisions read neighbouring processor state locally, not through server physics sync");
        int realMax=0;boolean nativeBudgets=true;
        for(UnitType candidate:Vars.content.units())if(candidate.playerControllable&&!candidate.hidden){
            Unit stress=candidate.spawn(Team.sharded,180,180);
            manager.syncRig("budget-live",stress,allFollowers,catalog);
            var liveParts=manager.getProcessorsForOwner("budget-live");nativeBudgets&=!liveParts.isEmpty()&&liveParts.size<=10;
            for(var part:liveParts){realMax=Math.max(realMax,coolplugin.gameplay.WorldItemLogicManager.instructionCount(part.code));nativeBudgets&=coolplugin.gameplay.WorldItemLogicManager.instructionCount(part.code)<=500;}
            manager.clearRig("budget-live");stress.remove();
        }
        check(nativeBudgets,"Every playable unit's full follower loadout fits 10 real processors with local peer collisions and no program over 500 instructions");
        System.out.println("LIVE MAX PROCESSOR INSTRUCTIONS="+realMax);

        float savedRecoil=cannon.subweaponRecoilTime;cannon.subweaponRecoilTime=37f;
        manager.syncRig("recoil-live",wearer,Seq.with(cannon.id),catalog);String recoilBefore=manager.getLastMlogForOwner("recoil-live");
        cannon.subweaponRecoilTime=83f;manager.syncRig("recoil-live",wearer,Seq.with(cannon.id),catalog);
        check(!recoilBefore.equals(manager.getLastMlogForOwner("recoil-live")),"Changing per-item recoil recovery time regenerates live processor code");
        cannon.subweaponRecoilTime=savedRecoil;manager.clearRig("recoil-live");

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
        int originalSize=cannon.size;
        var originalDimensions=coolplugin.gameplay.ColouringAssets.mountSizes.get(cannon.id).cpy();
        coolplugin.gameplay.ColouringAssets.mountSizes.put(cannon.id,new arc.math.geom.Vec2(40,80));
        check(cannon.size==originalSize,"Native sprite dimensions no longer silently add weight or inventory size");
        coolplugin.gameplay.ColouringAssets.mountSizes.put(cannon.id,originalDimensions);
        itemSystem.destroyRig("flash-check");
        var tech=plugin.getTechTree();tech.reloadTree();var progress=tech.getProgressStore();var researchNode=tech.findNode("nova");
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
        check(tech.buildPlayerRules(p).researched.contains(mindustry.content.Blocks.graphitePress),
            "Buildings are available without research in every faction");
        boolean otherFactionsIsolated=true;
        for(var faction:new coolplugin.gameplay.FactionDef[]{coolplugin.gameplay.FactionDef.MALIS,coolplugin.gameplay.FactionDef.NEOPLASM}){
            p.team(faction.team());otherFactionsIsolated&=!progress.isUnlocked(p.uuid(),researchNode)
                &&progress.getPaidAmount(p.uuid(),"faction-payment-probe","copper")==0&&!progress.hasFlag(p.uuid(),"faction-flag-probe");
        }
        for(String mechName:new String[]{"merui","cleroi","anthicus","tecta","collaris"}){
            int[] mechSource=coolplugin.gameplay.ManualUnitPalettes.source(mechName);
            int[] mechTarget=coolplugin.gameplay.ManualUnitPalettes.source("dagger");
            check(mechSource[6]==0x8ca8e8&&mechSource[8]==0xd0efff
                &&coolplugin.gameplay.ManualUnitPalettes.map(0x8ca8e8,mechSource,mechTarget)==mechTarget[6]
                &&coolplugin.gameplay.ManualUnitPalettes.map(0xd0efff,mechSource,mechTarget)==mechTarget[8]
                &&coolplugin.gameplay.ManualUnitPalettes.map(0xc1dceb,mechSource,mechTarget)==mechTarget[8]
                &&coolplugin.gameplay.ManualUnitPalettes.map(0x797e8e,mechSource,mechTarget)==0x797e8e,
                mechName+" exact cyan accents repaint without changing unrelated blue-grey armour");
        }
        int[] seamSource=coolplugin.gameplay.ManualUnitPalettes.source("dagger");
        int[] seamTarget=coolplugin.gameplay.ManualUnitPalettes.source("oct");
        check(coolplugin.gameplay.ManualUnitPalettes.map(0xf99f62,seamSource,seamTarget)==seamTarget[8]
            &&coolplugin.gameplay.ManualUnitPalettes.map(0xcc735f,seamSource,seamTarget)==seamTarget[6],
            "Explicit intermediate orange shades no longer leave original-colour pixel seams");
        check(coolplugin.gameplay.ManualUnitPalettes.map(0x123456,seamSource,seamTarget)==0x123456,
            "Seam corrections leave unknown RGB values untouched");
        check(Math.abs(coolplugin.gameplay.UnitPatchManager.computeBuildSpeedFallback(UnitTypes.nova.health)-UnitTypes.nova.buildSpeed)<.0001f
            &&Math.abs(coolplugin.gameplay.UnitPatchManager.computeBuildSpeedFallback(UnitTypes.mega.health)-UnitTypes.mega.buildSpeed/2f)<.0001f
            &&Math.abs(coolplugin.gameplay.UnitPatchManager.computeBuildSpeedFallback(UnitTypes.quad.health)-UnitTypes.quad.buildSpeed/3f)<.0001f
            &&Math.abs(coolplugin.gameplay.UnitPatchManager.computeBuildSpeedFallback(UnitTypes.oct.health)-UnitTypes.oct.buildSpeed/3f)<.0001f,
            "Build fallback curve exactly matches Nova, Mega/2, Quad/3 and Oct/3 anchors");
        check(coolplugin.gameplay.UnitPatchManager.computeBuildSpeedFallback(UnitTypes.arkyid.health)>.2f,
            "Arkyid health-based build fallback is no longer the stale 20 percent override");
        check(tech.findNode("dp-colouring-station")==null&&tech.findNode("landing-pad")==null,
            "Colouring stations and landing pads no longer require building research");
        var demoCatalog=coolplugin.gameplay.ShopConfigIO.loadOrCreate(workConfig.child("alphystuff/shop.hjson"));
        var reignTest=demoCatalog.findItem("bz-test-reign");var preceptTest=demoCatalog.findItem("bz-test-precept");
        check(UnitTypes.reign.weapons.first().name.equals(coolplugin.gameplay.ColouringAssets.mountSources.get(reignTest.id)),
            "Reign test mount uses the actual Reign weapon region, never the generic fallback");
        var testSources=new arc.struct.ObjectSet<String>();var testRecovery=new arc.struct.ObjectSet<Float>();
        boolean allGated=true;
        for(String unit:coolplugin.gameplay.ShopConfigIO.TEST_WEAPON_UNITS){
            var test=demoCatalog.findItem("bz-test-"+unit);testSources.add(coolplugin.gameplay.ColouringAssets.mountSources.get(test.id));testRecovery.add(test.subweaponRecoilTime);
            var node=tech.findNode("shop-"+test.id);allGated&=node!=null&&!node.virtual&&node.contentType.equals("shop-item")&&!itemSystem.isItemResearched(p,test);
        }
        check(testSources.size>=6&&testRecovery.size>=4,"Eight test weapons provide distinct native weapon art and varied recoil recovery times");
        check(allGated,"New Bazaar test items have real locked, faction-scoped shop-item research nodes");
        var gated=demoCatalog.findItem("bz-test-quasar");
        int gateResources=plugin.getBuildSystem().getAvailableAmount(p,Items.copper);
        check(!itemSystem.craft(p,gated.id,StationTier.BAZAAR).success()&&gateResources==plugin.getBuildSystem().getAvailableAmount(p,Items.copper),
            "Crafting unresearchable test equipment is rejected before consuming resources");
        var gatedNode=tech.findNode("shop-"+gated.id);progress.unlockSingle(p.uuid(),p.plainName(),gatedNode,tech.getRoot());
        check(itemSystem.isItemResearched(p,gated),"Unlocking a shop-item research node enables that catalog item");
        p.team(Team.crux);check(!itemSystem.isItemResearched(p,gated),"Shop-item research remains independent for other factions");p.team(Team.sharded);
        tech.reloadTree();check(tech.findNode("shop-"+gated.id)!=null&&tech.findNode("shop-"+gated.id).contentName.equals(gated.id),"Shop-item node type and catalog ID survive tree save/reload");

        check(reignTest!=null&&preceptTest!=null&&reignTest.tier==StationTier.BAZAAR&&preceptTest.tier==StationTier.BAZAAR
            &&reignTest.isSymmetricallyDuplicated(4f)&&!preceptTest.isSymmetricallyDuplicated(4f),
            "Bazaar migrates dual Reign and single Precept test mounts into existing catalogs");
        check(PlayerItemSystem.resolveSubweaponBullet("reign")==UnitTypes.reign.weapons.first().bullet
            &&PlayerItemSystem.resolveSubweaponBullet("precept")==UnitTypes.precept.weapons.first().bullet,
            "Test mounts use actual native Reign and Precept projectile types");
        var chosenField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("chosenFactions");chosenField.setAccessible(true);
        ((arc.struct.ObjectMap<String,coolplugin.gameplay.FactionDef>)chosenField.get(plugin.getPlayerUnitSystem())).put(p.uuid(),coolplugin.gameplay.FactionDef.SHARDED);
         p.team(Team.sharded);Unit adminOld=UnitTypes.dagger.spawn(Team.sharded,200,200);p.unit(adminOld);owned.put(p.uuid(),adminOld);
        var adminAccount=plugin.getSessionManager().getOrInitSession(p);
        var accountHandler=new CommandHandler("");coolplugin.database.AccountCommands.registerServerCommands(accountHandler,plugin.getSessionManager());
        accountHandler.handleMessage("account setunit "+adminAccount.uuid+" sharded mega");
        check(p.unit().type==UnitTypes.mega&&!adminOld.isAdded()&&owned.get(p.uuid())==p.unit(),
            "account setunit immediately replaces the online controlled unit without reload");
        accountHandler.handleMessage("account setunit "+adminAccount.uuid+" crux nova");
        check(p.unit().type==UnitTypes.mega,"Setting another faction unit does not replace the currently active unit");
        var landingSystem=new coolplugin.gameplay.LandingPadSystem(db,plugin.getSessionManager());landingSystem.start();
        var landingTile=Vars.world.tile(38,35);landingTile.setBlock(Blocks.landingPad,Team.sharded,0);
        var landing=(mindustry.world.blocks.campaign.LandingPad.LandingPadBuild)landingTile.build;
        check(landingSystem.claimUnowned(landing,p)&&!landingSystem.claimUnowned(landing,p),
            "Unowned map/reloaded Landing pads can be explicitly claimed once, never reassigned by another claim");
        landing.config=Items.copper;landing.efficiency=1f;landing.cooldown=0f;
        check(landingSystem.deliveryStatus(landing).contains("1500"),"Landing menu explains the actual native 1500-water requirement");
        int batch=landing.block.itemCapacity;db.setOrbitalItemAmount(adminAccount.id,"sharded","copper",batch*3);
        check(landingSystem.requestLanding(landing)&&landing.arriving==Items.copper
            &&db.getOrbitalItemAmount(adminAccount.id,"sharded","copper")==batch*2,
            "Landing request debits exactly one full native batch from owner's faction orbital storage");
        check(!landingSystem.requestLanding(landing)&&db.getOrbitalItemAmount(adminAccount.id,"sharded","copper")==batch*2,
            "An arrival already in progress cannot debit twice");
        landing.arrivingTimer=1f;landing.updateTile();
        check(landing.items.get(Items.copper)==batch&&landing.arriving==null,"Paid native landing arrival fills the pad for vanilla conveyor unloading");
        landing.items.clear();landing.cooldown=0f;
        landing.liquids.add(mindustry.content.Liquids.water,((mindustry.world.blocks.campaign.LandingPad)landing.block).consumeLiquidAmount);
        landing.efficiency=0f;landing.potentialEfficiency=0f;
        landing.update();
        check(landing.arriving==Items.copper,"Water-supplied Landing pad requests delivery through real Building.update without forced efficiency");
        landing.arrivingTimer=1f;landing.update();landing.items.clear();landing.cooldown=0f;landing.efficiency=1f;
        db.setOrbitalItemAmount(adminAccount.id,"sharded","copper",0);landing.updateTile();
        check(landing.arriving==null&&landing.items.total()==0,"Orbital Landing pad never acts as vanilla's custom-game infinite item source");
        db.setOrbitalItemAmount(adminAccount.id,"crux","copper",batch*10);
        check(!landingSystem.requestLanding(landing),"Landing pad cannot draw the same account's other faction orbital stock");
        db.setOrbitalItemAmount(adminAccount.id,"sharded","copper",batch-1);
        check(!landingSystem.requestLanding(landing)&&db.getOrbitalItemAmount(adminAccount.id,"sharded","copper")==batch-1,
            "Insufficient orbital stock waits for a full batch without consuming anything");
        db.setOrbitalItemAmount(adminAccount.id,"sharded","copper",batch*2);landing.items.add(Items.lead,1);
        check(!landingSystem.requestLanding(landing),"Landing pad waits for conveyor output to empty before another delivery");
        check(Blocks.landingPad.description.equals("Accepts item shippings from orbit."),"Landing pad description matches the requested wording");
        var badge=coolplugin.gameplay.LandingPadSystem.ownerDecoration(0f,0f,"[red]Painter[]","ffd37f");
        check(badge.size==5&&((ShapeMarker)badge.get(0)).sides==4&&((ShapeMarker)badge.get(2)).sides==3
            &&((ShapeMarker)badge.get(3)).rotation==180f,"Landing badge has a square receiver plate and opposing inward triangles, unlike Launch pad's octagon");
        var badgeField=coolplugin.gameplay.LandingPadSystem.class.getDeclaredField("decorations");badgeField.setAccessible(true);
        var badgeMap=(arc.struct.IntMap<arc.struct.IntSeq>)badgeField.get(landingSystem);
        var badgeIds=new arc.struct.IntSeq();badgeIds.addAll(badgeMap.get(landing.pos()));
        boolean badgeVisible=badgeIds.size==5;
        for(int i=0;i<badgeIds.size;i++)badgeVisible&=Vars.state.markers.has(badgeIds.get(i));
        check(badgeVisible,"Owned Landing pad creates five persistent native world markers");
        arc.Events.fire(new mindustry.game.EventType.BlockDestroyEvent(landing.tile));
        boolean badgeRemoved=true;for(int i=0;i<badgeIds.size;i++)badgeRemoved&=!Vars.state.markers.has(badgeIds.get(i));
        check(badgeRemoved&&badgeMap.isEmpty(),"Destroying a Landing pad removes its entire owner decoration");


        check(otherFactionsIsolated,"Malis and Neoplasm also retain their own independent research profiles");
        p.team(Team.sharded);Vars.state.rules.researched.remove(mindustry.content.Blocks.graphitePress);
        var admission=new WorldItemLogicManager();admission.start();
        var allocate=WorldItemLogicManager.class.getDeclaredMethod("obtainSlot",String.class);allocate.setAccessible(true);
        for(int i=0;i<250;i++)if(allocate.invoke(admission,"reserved-"+i)==null)throw new AssertionError("early pool exhaustion");
        check(allocate.invoke(admission,"overflow")==null,"Processor pool refuses a 251st allocation");
        ((CapturingCon)p.con).kickMessage="";
        arc.Events.fire(new mindustry.game.EventType.PlayerConnectionConfirmed(p));
        check(((CapturingCon)p.con).kickMessage.contains("250"),"New joiner receives an explicit capacity kick when processor pool is full");
        admission.clearRig("reserved-0");check(allocate.invoke(admission,"replacement")!=null,"A released processor slot can be allocated again");
        admission.clearAllRigs();
        verifyInventoryBudgets(plugin,p,workConfig);
        verifyMountAimAndInventory(plugin,p,workConfig);
        verifyTreeLoadouts(plugin,p,workConfig);
        verifyNeoplasmSurvival(plugin,workConfig);
        verifyMapProtection();
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

    private static final class SurvivalProbe extends coolplugin.gameplay.PlayerUnitSystem {
        final Seq<coolplugin.gameplay.PlayerUnitSystem.Survivor> samples=new Seq<>();
        boolean deployed;
        SurvivalProbe(BuildSystem build){super(build,null);}
        @Override public Seq<coolplugin.gameplay.PlayerUnitSystem.Survivor> survivalPlayers(){return samples;}
        @Override public boolean hasDeployedPlayers(){return deployed;}
        void live(Unit unit,int count,float health){samples.clear();for(int i=0;i<count;i++)samples.add(new coolplugin.gameplay.PlayerUnitSystem.Survivor("probe-"+i,unit,unit,true,true,health));deployed=true;}
    }
    @SuppressWarnings("unchecked")
    private static void verifyNeoplasmSurvival(CoolPlugin plugin,Fi config)throws Exception{
        Vars.state.gameOver=false;
        Vars.world.loadGenerator(48,48,tiles->{for(int y=0;y<48;y++)for(int x=0;x<48;x++)tiles.set(x,y,new Tile(x,y,Blocks.stone,Blocks.air,Blocks.air));});
        Vars.state.set(GameState.State.playing);Vars.state.rules.unitCap=99999;
        Vars.world.tile(3,3).setOverlay(Blocks.spawn);
        var probe=new SurvivalProbe(plugin.getBuildSystem());Unit hero=UnitTypes.dagger.spawn(Team.sharded,240,240);probe.live(hero,1,1f);
        var director=new coolplugin.gameplay.NeoplasmDirector(config.child("survival-probe-waves.json"),probe);director.reset();
        check(director.spawnPointCount()==1&&!Vars.state.rules.waves&&Vars.state.rules.waveTeam==Team.neoplastic,"Survival uses only vanilla spawn overlays and disables native duplicate wave spawning");
        director.update(59.99f);check(director.continuousSpawned()==0&&director.waveNumber()==0,"No enemies spawn before sixty seconds");
        director.update(.02f);check(director.continuousSpawned()==1,"The first continuous Neoplasm unit spawns at sixty seconds");
        check(Math.abs(director.continuousInterval(1)-7f)<.001f&&Math.abs(director.continuousInterval(4)-7f/(float)Math.pow(4,.8))<.001f,"Continuous interval follows 7 / playercount^0.8 at full health");
        probe.live(hero,1,.01f);director.update(0f);
        check(director.healthReliefMultiplier()>3.96f&&director.healthReliefMultiplier()<=4f,"Near-zero player health slows continuous spawning by almost four times, bounded at four");
        probe.live(hero,1,1f);probe.samples.add(new coolplugin.gameplay.PlayerUnitSystem.Survivor("dead-probe",null,null,true,false,0f));director.update(0f);
        check(Math.abs(director.healthReliefMultiplier()-2f)<.001f,"A newly dead player contributes half-weight as a zero-health player");
        check(Math.abs(director.deathWeight(300)-.25f)<.001f&&director.deathWeight(600)==0f&&director.deathWeight(900)==0f,"Dead-player contribution decays linearly to nothing after ten minutes");
        probe.live(hero,1,1f);director.reset();director.update(219.99f);check(director.waveNumber()==0,"The first organised wave does not start before 220 seconds");
        director.update(.02f);check(director.waveNumber()==1&&Math.abs(director.nextWaveSeconds()-370f)<.001f,"Wave one starts at 220 seconds, scheduling wave two 150 seconds later");
        while(director.queuedSpawns()>0)director.update(0f);
        check(director.waveSpawned()==10,"Wave one contains one boss plus its one-player swarm");
        Unit boss=Groups.unit.find(unit->unit.team==Team.neoplastic&&unit.getDuration(mindustry.content.StatusEffects.boss)>0f);
        check(boss!=null&&boss.health==boss.maxHealth&&boss.healthMultiplier==1.5f,"Native boss health scaling starts full, without multiplying both raw HP and damage resistance");
        check(Math.abs(director.bossHealthScale(4,1)-2.95f)<.001f,"Boss effective HP increases with player count");
        director.update(149.98f);check(director.waveNumber()==1,"The second wave waits for its complete 150-second interval");
        director.update(.02f);check(director.waveNumber()==2,"Wave two runs at 370 seconds independently of remaining enemies");
        probe.live(hero,4,1f);director.reset();director.update(220f);while(director.queuedSpawns()>0)director.update(0f);
        check(director.waveSpawned()==28,"Four players increase the swarm, while the wave still has only one boss");
        var settings=coolplugin.gameplay.NeoplasmWaveConfig.load(config.child("survival-probe-waves.json"));
        boolean validBosses=true;for(var wave:settings.waves){int n=0;for(var group:wave.bosses())n+=group.base();validBosses&=n>=1&&n<=3&&!wave.continuous().isEmpty();}
        check(settings.waves.size==25&&validBosses,"All 25 editable waves have one to three bosses and a nonempty random continuous pool");
        boolean badRejected=false;try{coolplugin.gameplay.NeoplasmWaveConfig.parse("{wave1:{bosses:[{unit:dagger,count:4}],swarm:[],continuous:[dagger]}}");}catch(IllegalArgumentException expected){badRejected=true;}
        check(badRejected,"Invalid boss counts are rejected instead of silently loading bad wave data");
        check(director.bossHealthScale(1,30)>director.bossHealthScale(1,25),"After the final authored wave, difficulty continues increasing without exceeding three bosses");
        probe.live(hero,1,1f);director.reset();director.update(0f);
        probe.samples.clear();probe.samples.add(new coolplugin.gameplay.PlayerUnitSystem.Survivor("last",hero,hero,false,true,1f));
        director.update(39f);check(!Vars.state.gameOver,"The last logout does not immediately end the round or despawn the survivor");
        probe.live(hero,1,1f);director.update(0f);director.update(2f);check(!Vars.state.gameOver,"Rejoining during the empty-server grace cancels the pending game over");
        probe.samples.clear();probe.samples.add(new coolplugin.gameplay.PlayerUnitSystem.Survivor("last",hero,hero,false,true,1f));director.update(0f);director.update(39.99f);check(!Vars.state.gameOver,"A new last-logout grace lasts a full forty seconds");
        director.update(.02f);check(Vars.state.gameOver,"An empty server ends the round after forty seconds");
        Vars.state.gameOver=false;probe.live(hero,2,1f);director.reset();director.update(0f);
        probe.samples.set(0,new coolplugin.gameplay.PlayerUnitSystem.Survivor("probe-0",null,null,true,false,0f));director.update(0f);check(!Vars.state.gameOver,"One player's death does not end the game while another survivor remains");
        probe.samples.set(1,new coolplugin.gameplay.PlayerUnitSystem.Survivor("probe-1",null,null,true,false,0f));director.update(0f);check(Vars.state.gameOver,"A complete party wipe ends the game immediately instead of waiting out respawn timers");
        Vars.state.gameOver=false;
        var lifecycle=plugin.getPlayerUnitSystem();
        var ownedField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("owned");ownedField.setAccessible(true);var owned=(arc.struct.ObjectMap<String,Unit>)ownedField.get(lifecycle);
        var factionField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("chosenFactions");factionField.setAccessible(true);var factions=(arc.struct.ObjectMap<String,coolplugin.gameplay.FactionDef>)factionField.get(lifecycle);
        var treeField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("chosenTrees");treeField.setAccessible(true);var trees=(arc.struct.ObjectMap<String,coolplugin.gameplay.UnitTree>)treeField.get(lifecycle);
        Player player=Player.create();player.name="Survivor";player.con=new CapturingCon("survival-player");player.team(Team.sharded);player.add();factions.put(player.uuid(),coolplugin.gameplay.FactionDef.SHARDED);trees.put(player.uuid(),coolplugin.gameplay.UnitTree.SUPPORT_MECH);
        Unit advanced=UnitTypes.vela.spawn(Team.sharded,200,180);owned.put(player.uuid(),advanced);player.unit(advanced);
        Tile holderTile=Vars.world.tile(25,23);holderTile.setBlock(Blocks.additiveReconstructor,Team.sharded);
        var holder=(mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)holderTile.build;
        advanced.remove();holder.payload=new mindustry.world.blocks.payloads.UnitPayload(advanced);
        var snapshot=lifecycle.survivalPlayers().find(survivor->survivor.uuid().equals(player.uuid()));
        check(snapshot!=null&&snapshot.alive()&&snapshot.position()==holder,"A live player inside a payload building is alive and targeted at the actual holder location");
        probe.samples.clear();probe.samples.add(snapshot);probe.deployed=true;director.reset();director.update(0f);
        check(director.nearestPlayer(20,20,true,true)==holder,"Neoplasm target acquisition finds players inside payloads");
        Unit carrier=UnitTypes.oct.create(Team.sharded);carrier.set(holder.x,holder.y);((mindustry.gen.Payloadc)carrier).payloads().add(new mindustry.world.blocks.payloads.UnitPayload(advanced));holder.payload=new mindustry.world.blocks.payloads.UnitPayload(carrier);
        snapshot=lifecycle.survivalPlayers().find(survivor->survivor.uuid().equals(player.uuid()));
        check(snapshot!=null&&snapshot.alive()&&snapshot.position()==holder,"Nested carrier payloads resolve to the outer holder, not stale stored unit coordinates");
        holder.payload=null;advanced.add();
        arc.Events.fire(new mindustry.game.EventType.UnitDestroyEvent(advanced));advanced.remove();
        check(lifecycle.respawnUnlockSeconds==40f&&lifecycle.respawnSecondsRemaining(player.uuid())==40f,"Player death installs the forty-second respawn cooldown");
        var ticks=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("gameTicks");ticks.setAccessible(true);ticks.setFloat(lifecycle,ticks.getFloat(lifecycle)+2400f);
        var respawn=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredMethod("offerRespawnCommand");respawn.setAccessible(true);respawn.invoke(lifecycle);
        check(owned.get(player.uuid())!=null&&owned.get(player.uuid()).type==UnitTypes.nova,"A surviving party member automatically respawns as their selected tree's tier one after forty seconds");
        probe.samples.clear();probe.deployed=false;director.reset();director.update(0f);
        Unit attacker=UnitTypes.flare.spawn(Team.neoplastic,140,140);director.installControllers();
        check(attacker.controller() instanceof coolplugin.gameplay.NeoplasmAI&&attacker.type.circleTarget,"Neoplasm uses the survival AI while retaining Flare's native dive-and-turn movement mode");
        Vars.state.teams.updateTeamStats();Groups.unit.updatePhysics();
        var ai=(coolplugin.gameplay.NeoplasmAI)attacker.controller();mindustry.gen.Teamc target=ai.findTarget(140,140,500,true,true);
        check(target!=null&&target.team()!=Team.neoplastic,"Non-player enemy units remain valid native weapon targets");
        player.remove();Unit returned=owned.remove(player.uuid());if(returned!=null)returned.remove();factions.remove(player.uuid());trees.remove(player.uuid());hero.remove();carrier.remove();
        for(Unit enemy:Groups.unit.copy(new Seq<>()))if(enemy.team==Team.neoplastic)enemy.remove();
        Vars.state.gameOver=false;
    }

    @SuppressWarnings("unchecked")
    private static void verifyMapProtection(){
        var floor=Vars.content.block("dp-sharded-spawnpoint");
        if(floor==null)floor=new mindustry.world.blocks.environment.Floor("dp-sharded-spawnpoint");
        Vars.state.gameOver=false;
        Vars.world.loadGenerator(32,32,tiles->{for(int y=0;y<32;y++)for(int x=0;x<32;x++)tiles.set(x,y,new Tile(x,y,Blocks.stone,Blocks.air,Blocks.air));});
        Vars.state.set(GameState.State.playing);
        coolplugin.gameplay.MapProtection.protectProjector();
        Tile tile=Vars.world.tile(16,16);
        tile.setBlock(Blocks.largeShieldProjector,Team.sharded,0);
        Building barrier=tile.build;float health=barrier.health;
        barrier.damage(1000000f);barrier.damagePierce(1000000f);barrier.kill();
        check(barrier.health==health&&tile.build==barrier,"Large barrier rejects direct/piercing damage and kill calls");
        check(!barrier.collide(null)&&!barrier.collision(null)&&!barrier.block.targetable&&!barrier.block.destructible&&!barrier.block.breakable,
            "Large barrier is unhittable, untargetable and marked indestructible");
        check(!barrier.block.hasPower&&barrier.block.consPower==null&&!barrier.block.hasConsumers,
            "Large barrier has no power consumer or power requirement");
        barrier.updateConsumption();
        check(barrier.efficiency==1f,"Large barrier runs at full efficiency without a power network");
        var guard=new coolplugin.gameplay.MapProtection();guard.start();
        mindustry.world.Build.beginBreak(null,Team.sharded,16,16);
        check(tile.block()==Blocks.largeShieldProjector,"Direct deconstruction is cancelled before a builder can dismantle a barrier");
        barrier=tile.build;
        check(floor!=null,"Faction spawn floor fixture exists for map protection checks");
        if(floor!=null){
            Vars.world.tile(17,17).setFloor(floor.asFloor());
            check(coolplugin.gameplay.MapProtection.overlapsSpawn(barrier),"Spawn overlap includes the off-centre corner of multiblock buildings");
            guard.clearSpawnBuildings();
            check(tile.block()==Blocks.air,"Protection discovers faction floors painted after its initial load scan");
            var protection=new coolplugin.gameplay.MapProtection();protection.start();protection.clearSpawnBuildings();
            check(tile.block()==Blocks.air,"Even an invulnerable large barrier is removed from a faction spawnpoint");
            Vars.world.tile(17,17).setBlock(Blocks.copperWall,Team.sharded,0);
            protection.clearSpawnBuildings();
            check(Vars.world.tile(17,17).block()==Blocks.air,"Ordinary buildings placed on faction spawnpoints are removed");
        }
        Vars.world.tile(5,5).setBlock(Blocks.copperWall,Team.sharded,0);
        check(!coolplugin.gameplay.MapProtection.overlapsSpawn(Vars.world.tile(5,5).build),"Buildings outside faction spawnpoints remain allowed");
        Vars.world.tile(17,17).setFloor(Blocks.stone.asFloor());
        Vars.world.tile(5,5).setFloor(floor.asFloor());
        guard.clearSpawnBuildings();
        check(Vars.world.tile(5,5).block()==Blocks.air,"Moving a spawn floor during the match protects its new location");
        Vars.world.tile(5,5).setFloor(Blocks.stone.asFloor());
        Vars.world.tile(5,5).setBlock(Blocks.copperWall,Team.sharded,0);
        guard.clearSpawnBuildings();
        check(Vars.world.tile(5,5).block()==Blocks.copperWall,"Former spawn locations stop being protected after the floor moves");
        var units=new coolplugin.gameplay.PlayerUnitSystem(new coolplugin.gameplay.BuildSystem(null),null);
        Player delayed=Player.create();delayed.name="Delayed floor";delayed.con=new CapturingCon("delayed-floor-player");delayed.add();
        units.spawnFor(delayed,coolplugin.gameplay.FactionDef.SHARDED,UnitTypes.dagger);
        check(delayed.unit()==null||!delayed.unit().isAdded(),"Initial deployment waits instead of using fallback before delayed floors appear");
        Vars.world.tile(20,20).setFloor(floor.asFloor());
        units.spawnFor(delayed,coolplugin.gameplay.FactionDef.SHARDED,UnitTypes.dagger);
        check(delayed.unit()!=null&&delayed.unit().within(Vars.world.tile(20,20),4f),"Pending player deployment uses the newly painted faction floor");
        Vars.world.tile(20,20).setFloor(Blocks.stone.asFloor());
        Vars.world.tile(24,24).setFloor(floor.asFloor());
        check(units.findFactionSpawnTile(coolplugin.gameplay.FactionDef.SHARDED,UnitTypes.dagger)==Vars.world.tile(24,24),"Later respawn lookups follow the map's current floor location");
        delayed.unit().remove();delayed.remove();
    }

    private static void verifyTreeLoadouts(CoolPlugin plugin,Player player,Fi config)throws Exception{
        var system=plugin.getPlayerUnitSystem();var items=plugin.getPlayerItemSystem();var db=plugin.getPlayerDatabase();
        var klass=coolplugin.gameplay.PlayerUnitSystem.class;
        var field=klass.getDeclaredField("owned");field.setAccessible(true);
        var owners=(arc.struct.ObjectMap<String,Unit>)field.get(system);
        Unit old=owners.get(player.uuid());
        Unit dagger=UnitTypes.dagger.spawn(Team.sharded,80,80);owners.put(player.uuid(),dagger);player.unit(dagger);
        var data=new EquipmentData();data.equipped.add("wb-01");while(data.equipped.size<30)data.equipped.add("");data.equipped.add("wb-02");items.saveEquipment(player,data);
        long account=plugin.getSessionManager().getOrInitSession(player).id;
        check(db.getUnitLoadout(account,"dagger").equipped.size<=30&&!db.getUnitLoadout(account,"dagger").equipped.contains("wb-02"),"Carried slots never enter the loadout database");
        Unit mace=UnitTypes.mace.spawn(Team.sharded,80,80);owners.put(player.uuid(),mace);player.unit(mace);
        var maceGear=items.getEquipment(player);
        check(maceGear.equipped.size>30&&maceGear.equipped.get(30).equals("wb-02")&&!maceGear.equipped.contains("wb-01"),"Unit change loads independent equipped gear and transfers carried gear");
        maceGear.equipped.set(0,"wb-03");items.saveEquipment(player,maceGear);
        owners.put(player.uuid(),dagger);player.unit(dagger);
        check(items.getEquipment(player).equipped.get(0).equals("wb-01"),"Changing back restores that unit's saved equipment");
        var painted=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant("dagger",coolplugin.gameplay.ColouringAssets.palettes.first())).spawn(Team.sharded,80,80);
        owners.put(player.uuid(),painted);player.unit(painted);
        check(items.getEquipment(player).equipped.get(0).equals("wb-01"),"All paint variants share the canonical unit loadout");
        plugin.getSessionManager().saveUnitType(player,coolplugin.gameplay.FactionDef.SHARDED,UnitTypes.reign);
        check(db.getFactionUnitType(account,"sharded",null).equals("tree:mech")&&plugin.getSessionManager().resolveSavedUnitType(player,coolplugin.gameplay.FactionDef.SHARDED)==UnitTypes.dagger,
            "Progression save stores the tree, not the upgraded unit or its recolour");
        items.start();arc.Events.fire(new mindustry.game.EventType.GameOverEvent(Team.sharded));var cleared=items.getEquipment(player);
        check(cleared.equipped.size<=30&&cleared.equipped.get(0).equals("wb-01"),"Game-over carry reset leaves permanent equipped gear intact");
        check(!coolplugin.gameplay.UnitTree.TANK.available&&!coolplugin.gameplay.UnitTree.LEGGED.available&&!coolplugin.gameplay.UnitTree.ATTACK_NAVALS.available,
            "Erekir, legged and naval tree cards are disabled");
        String ui=new String(serializeUi(system.getTreeMenu().build(player,coolplugin.gameplay.FactionDef.SHARDED)),StandardCharsets.ISO_8859_1);
        check(ui.contains("Choose your unit tree")&&ui.contains("Support mech")&&ui.contains("Insectoid")&&ui.contains("Chemical"),"Tree picker serializes all horizontal faction-style columns");
        system.start();
        var factionField=klass.getDeclaredField("chosenFactions");factionField.setAccessible(true);
        ((arc.struct.ObjectMap<String,coolplugin.gameplay.FactionDef>)factionField.get(system)).put(player.uuid(),coolplugin.gameplay.FactionDef.SHARDED);
        arc.Events.fire(new mindustry.game.EventType.PlayerLeave(player));
        check(owners.get(player.uuid())==painted&&painted.isAdded(),"Disconnect leaves the same equipped unit alive in the world");
        player.remove();items.updateUnitsAndVisuals();
        check(!items.getWorldLogicManager().getProcessorsForOwner(player.uuid()).isEmpty(),"Offline surviving units keep their equipped item visual rigs");
        player.add();
        var confirmed=klass.getDeclaredMethod("handleConnectionConfirmed",Player.class);confirmed.setAccessible(true);confirmed.invoke(system,player);
        check(player.unit()==painted&&owners.get(player.uuid())==painted,"Rejoin restores control of the original surviving unit");
        var ticks=klass.getDeclaredField("gameTicks");ticks.setAccessible(true);ticks.setFloat(system,18000f);
        check(!system.canChangeChoice(),"Choice-change exception closes at exactly five minutes of game time");
        ticks.setFloat(system,17999f);check(system.canChangeChoice(),"Choice changes remain possible before the five-minute deadline");
        var choiceField=klass.getDeclaredField("chosenTrees");choiceField.setAccessible(true);
        ((arc.struct.ObjectMap<String,coolplugin.gameplay.UnitTree>)choiceField.get(system)).put(player.uuid(),coolplugin.gameplay.UnitTree.MECH);
        system.onTreeChosen(player,coolplugin.gameplay.FactionDef.SHARDED,coolplugin.gameplay.UnitTree.MECH);
        check(owners.get(player.uuid())==painted,"An already-chosen tree cannot be selected again without the /menu exception");
        system.requestOpenFactionMenu(player);
        system.onTreeChosen(player,coolplugin.gameplay.FactionDef.SHARDED,coolplugin.gameplay.UnitTree.MECH);
        Unit changed=owners.get(player.uuid());
        check(changed!=painted&&changed.type==UnitTypes.dagger,"The /menu exception permits a fresh tree deployment during the first five minutes");
        system.onTreeChosen(player,coolplugin.gameplay.FactionDef.SHARDED,coolplugin.gameplay.UnitTree.MECH);
        check(owners.get(player.uuid())==changed,"Confirming a correction consumes its one-shot choice permission");
        ticks.setFloat(system,18000f);system.requestOpenFactionMenu(player);
        system.onTreeChosen(player,coolplugin.gameplay.FactionDef.SHARDED,coolplugin.gameplay.UnitTree.MECH);
        check(owners.get(player.uuid())==changed,"After five minutes even /menu cannot reset the chosen unit tree");
        arc.Events.fire(new mindustry.game.EventType.UnitDestroyEvent(changed));changed.remove();
        check(!owners.containsKey(player.uuid()),"An offline or online death destroys ownership instead of retaining a reconnect target");
        owners.put(player.uuid(),old);player.unit(old);dagger.remove();mace.remove();painted.remove();
        verifyUnitSaveMigration(config);
        verifyTreeResearchNotifications(plugin);

    }

    private static void verifyTreeResearchNotifications(CoolPlugin plugin)throws Exception{
        Player player=Player.create();player.name="Research probe";var con=new CapturingCon("tree-research-probe");player.con=con;player.team(Team.sharded);player.add();
        var tech=plugin.getTechTree();
        arc.util.Time.clear();float delta=arc.util.Time.delta;arc.util.Time.delta=1f;
        check(tech.unlockNode(player,"nova")&&tech.isUnitUnlockedForPlayer(player,UnitTypes.corvus),"Researching a tier-one unit unlocks its entire upgrade tree");
        check(tech.buildPlayerRules(player).researched.contains(UnitTypes.corvus),"Client rules expose higher tiers immediately after tier-one research");
        arc.util.Time.update();
        check(con.sentPackets.count(packet->packet instanceof mindustry.gen.ResearchedCallPacket)==1,"Tier-one content unlock notification is emitted first");
        arc.util.Time.delta=178f;arc.util.Time.update();
        check(con.sentPackets.count(packet->packet instanceof mindustry.gen.ResearchedCallPacket)==1,"Upgrade unlock notification waits for the full three-second interval");
        arc.util.Time.delta=1f;arc.util.Time.update();
        check(con.sentPackets.count(packet->packet instanceof mindustry.gen.ResearchedCallPacket)==2,"First upgrade notification arrives three seconds after the tier-one notification");
        arc.util.Time.delta=180f;for(int i=0;i<3;i++)arc.util.Time.update();
        check(con.sentPackets.count(packet->packet instanceof mindustry.gen.ResearchedCallPacket)==5,"All five unit tiers receive separate spaced content unlock notifications");
        arc.util.Time.delta=delta;arc.util.Time.clear();player.remove();
        check(tech.isBlockAllowedForPlayer(null,Blocks.graphitePress),"Buildings require no research even for an uninitialized player");
        var field=coolplugin.techtree.TechTreeService.class.getDeclaredField("mapBannedBlocks");field.setAccessible(true);
        @SuppressWarnings("unchecked") var bans=(arc.struct.ObjectSet<Block>)field.get(tech);bans.add(Blocks.graphitePress);
        check(!tech.isBlockAllowedForPlayer(null,Blocks.graphitePress),"Unlocking all buildings does not override explicit map bans");bans.remove(Blocks.graphitePress);
    }

    private static void verifyUnitSaveMigration(Fi config)throws Exception{
        Fi file=config.child("migration-probe.db");file.delete();
        var db=new coolplugin.database.PlayerDatabase(file);db.init();
        var account=db.getOrCreateGuestByUuid("old-loadout","Legacy");
        var gear=new EquipmentData();gear.equipped.add("wb-01");while(gear.equipped.size<30)gear.equipped.add("");gear.equipped.add("wb-02");gear.stash.put("wb-03",4);
        db.saveEquipmentData(account.id,"sharded",gear);db.setFactionUnitType(account.id,"sharded","reign");
        var field=coolplugin.database.PlayerDatabase.class.getDeclaredField("connection");field.setAccessible(true);
        try(var st=((java.sql.Connection)field.get(db)).createStatement()){
            st.executeUpdate("UPDATE account_factions SET unit_type='dp-paint-unit-oct-reign'");
            st.executeUpdate("DELETE FROM plugin_migrations WHERE id='unit-tree-loadouts-v1'");
        }
        db.close();db.init();
        check(db.getFactionUnitType(account.id,"sharded",null).equals("tree:mech"),"Legacy coloured tier-five save migrates to a stable tree ID");
        var loadout=db.getUnitLoadout(account.id,"reign");
        check(loadout.equipped.size==30&&loadout.equipped.get(0).equals("wb-01")&&!loadout.equipped.contains("wb-02")&&loadout.stash.isEmpty(),
            "Migration preserves equipped slots on the old exact unit, never persisted carry or stash");
        db.close();db.init();
        check(db.getUnitLoadout(account.id,"reign").equipped.equals(loadout.equipped),"Save migration is idempotent across database reopen");db.close();
    }

    private static void verifyInventoryBudgets(CoolPlugin plugin,Player p,Fi config)throws Exception{
        var system=plugin.getPlayerItemSystem();var catalog=system.getCatalog();
        String[][] tiers={{"dagger","nova","crawler","flare","mono","risso","retusa"},
            {"mace","pulsar","atrax","horizon","poly","minke","oxynoe","stell","merui","elude"},
            {"fortress","quasar","spiroct","zenith","mega","bryde","cyerce","locus","cleroi","avert"},
            {"precept","anthicus","obviate"},{"scepter","vela","arkyid","antumbra","quad","sei","aegires","vanquish","tecta","quell"},
            {"reign","corvus","toxopid","eclipse","oct","omura","navanax","conquer","collaris","disrupt"}};
        int[] equips={1,2,4,6,12,30},carries={2,3,6,7,20,60},minimum={1,1,1,1,2,4};
        boolean vanilla=true;
        for(int row=0;row<tiers.length;row++)for(String name:tiers[row]){
            var c=coolplugin.gameplay.InventoryRules.capacity(Vars.content.unit(name));
            vanilla &=c.equip()==equips[row]&&c.carry()==carries[row]&&c.minimumEquipSize()==minimum[row];
        }
        check(vanilla,"Every vanilla ground/air/naval branch receives the exact requested tier capacities");
        var custom=new UnitType("capacity-custom-probe");custom.health=10000;
        check(coolplugin.gameplay.InventoryRules.capacity(custom).equip()==12,"Custom units use documented immutable base-health capacity bands");Vars.content.remove(custom);
        var small=catalog.findItem("wb-01");var gun=catalog.findItem("wb-06");
        var tiny=new EquipmentData();tiny.equipCapacity=1;tiny.carryCapacity=2;
        check(coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(tiny,gun)&&tiny.equipped.get(30).equals(gun.id),"Size-2 graphite gun goes into carry when T1 equip budget is only one");
        check(!coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(tiny,gun),"Full size-budget carry rejects purchases without partial changes");
        var slow=system.computeStats(tiny.equipped,UnitTypes.dagger.create(Team.sharded));
        check(Math.abs(slow.carrySpeedFactor-.25f)<.0001f,"Full carry inventory produces exactly 75 percent slowdown");
        var half=new Seq<String>();while(half.size<30)half.add("");half.add(small.id);
        check(Math.abs(system.computeStats(half,UnitTypes.dagger.create(Team.sharded)).carrySpeedFactor-.625f)<.0001f,"Half-full carry inventory produces 37.5 percent slowdown");
        check(system.computeStats(Seq.with(small.id),UnitTypes.dagger.create(Team.sharded)).carrySpeedFactor==1f,"Equipped items alone cause no inventory slowdown");
        var carriedOnly=new Seq<String>();while(carriedOnly.size<30)carriedOnly.add("");carriedOnly.add(gun.id);carriedOnly.add("temp-overclock");
        Unit passiveHost=UnitTypes.reign.spawn(Team.sharded,180,180);passiveHost.isShooting=true;
        var carriedStats=system.computeStats(carriedOnly,passiveHost);int bulletsBefore=Groups.bullet.size();
        system.updateVisualRigForUnit("carry-gameplay-probe",passiveHost,carriedOnly,carriedStats);
        check(carriedStats.healthMult==1f&&carriedStats.damageMult==1f&&carriedStats.reloadMult==1f
            &&!passiveHost.hasEffect(mindustry.content.StatusEffects.overclock)&&Groups.bullet.size()==bulletsBefore,
            "Carried guns/status items never fire or grant passives, even on large units");
        system.destroyRig("carry-gameplay-probe");passiveHost.remove();
        boolean paintCapacity=true;for(var scheme:coolplugin.gameplay.ColouringAssets.palettes){
            var painted=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant("reign",scheme));
            paintCapacity &=coolplugin.gameplay.InventoryRules.capacity(painted).equals(coolplugin.gameplay.InventoryRules.capacity(UnitTypes.reign));
        }
        check(paintCapacity,"Every painted T5 retains its base unit's equip/carry capacity and minimum equip cost");

        var tierFour=new EquipmentData();tierFour.equipCapacity=12;tierFour.carryCapacity=20;tierFour.minimumEquipSize=2;
        for(var item:catalog.items)if(item.size==1)coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(tierFour,item);
        check(coolplugin.gameplay.PlayerItemSystem.usedCapacity(tierFour,true)==12,"T4 charges two equip points for small items without forbidding them");
        var big=new EquipmentData();big.equipCapacity=30;big.carryCapacity=60;big.minimumEquipSize=4;
        for(var item:catalog.items)big.sizes.put(item.id,item.size);
        for(var item:catalog.items)if(item.size==1)coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(big,item);
        int equippedCount=0;for(int i=0;i<Math.min(30,big.equipped.size);i++)if(!big.equipped.get(i).isBlank())equippedCount++;
        check(coolplugin.gameplay.PlayerItemSystem.usedCapacity(big,true)==28&&equippedCount==7,
            "T5 charges a minimum of four equip points even for real size-1 items");
        var old=new EquipmentData();old.layoutVersion=1;old.equipped.addAll("wb-01","wb-06","wb-01","wb-06","wb-06","wb-01","wb-01","wb-01","wb-01","wb-01");
        old.equipCapacity=1;old.carryCapacity=2;old.sizes.put(gun.id,2);
        coolplugin.gameplay.PlayerItemSystem.normalizeEquipment(old);
        int owned=old.equipped.count(id->!id.isBlank());for(var e:old.stash)owned+=e.value;
        check(owned==10&&old.layoutVersion==2,"Legacy ten-slot migration preserves all copies through downsizing and duplicate routing");
        var upgraded=coolplugin.gameplay.PlayerItemSystem.copyInventory(old);upgraded.equipCapacity=30;upgraded.carryCapacity=60;upgraded.minimumEquipSize=4;
        for(String id:upgraded.stash.keys().toSeq()){
            int count=upgraded.stash.get(id,0);while(count>0&&coolplugin.gameplay.PlayerItemSystem.grantCraftedOrTradedItem(upgraded,catalog.findItem(id)))count--;
            if(count==0)upgraded.stash.remove(id,0);else upgraded.stash.put(id,count);
        }
        check(upgraded.stash.isEmpty()&&upgraded.equipped.count(id->!id.isBlank())==10,"Returning to a larger unit can recover preserved overflow without loss");
        var ownedField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("owned");ownedField.setAccessible(true);
        @SuppressWarnings("unchecked") var ownerMap=(arc.struct.ObjectMap<String,Unit>)ownedField.get(plugin.getPlayerUnitSystem());
        var savedOwned=ownerMap.get(p.uuid());var savedPlayerUnit=p.unit();
        Unit huge=UnitTypes.reign.spawn(Team.sharded,180,180);ownerMap.put(p.uuid(),huge);p.unit(huge);
        system.saveEquipment(p,upgraded);var largeInventory=system.getEquipment(p);
        int largeCount=largeInventory.equipped.count(id->!id.isBlank());for(var e:largeInventory.stash)largeCount+=e.value;
        var savedLargeGear=dbLoadout(plugin,p,"reign");
        Unit little=UnitTypes.dagger.spawn(Team.sharded,180,180);ownerMap.put(p.uuid(),little);p.unit(little);
        var smallInventory=system.getEquipment(p);int smallCount=smallInventory.equipped.count(id->!id.isBlank());for(var e:smallInventory.stash)smallCount+=e.value;
        check(coolplugin.gameplay.PlayerItemSystem.usedCapacity(smallInventory,true)<=1
            &&coolplugin.gameplay.PlayerItemSystem.usedCapacity(smallInventory,false)<=2,"Changing unit loads its own equipment and rebalances carried capacity");
        ownerMap.put(p.uuid(),huge);p.unit(huge);var returnedInventory=system.getEquipment(p);
        check(dbLoadout(plugin,p,"reign").equals(savedLargeGear),
            "Returning to a large unit restores its separate persistent loadout");
        ownerMap.put(p.uuid(),savedOwned);p.unit(savedPlayerUnit);huge.remove();little.remove();

        var account=plugin.getSessionManager().getOrInitSession(p);var db=plugin.getPlayerDatabase();
        db.saveEquipmentData(account.id,"capacity-roundtrip",big);var restored=db.getEquipmentData(account.id,"capacity-roundtrip");
        check(restored.layoutVersion==2&&restored.equipped.equals(big.equipped),"Versioned SQLite serialization preserves every empty hole and carry boundary");
        var connectionField=coolplugin.database.PlayerDatabase.class.getDeclaredField("connection");connectionField.setAccessible(true);
        try(var stmt=((java.sql.Connection)connectionField.get(db)).prepareStatement("UPDATE account_equipped_items SET equipped_json = ? WHERE account_id = ? AND faction_id = ?")){
            stmt.setString(1,"[\"wb-01\",\"\",\"\",\"wb-06\",\"wb-06\"]");stmt.setLong(2,account.id);stmt.setString(3,"capacity-roundtrip");stmt.executeUpdate();
        }
        var legacyRead=db.getEquipmentData(account.id,"capacity-roundtrip");coolplugin.gameplay.PlayerItemSystem.normalizeEquipment(legacyRead);
        check(legacyRead.layoutVersion==2&&legacyRead.equipped.get(30).equals("wb-06")&&legacyRead.equipped.get(1).isBlank(),
            "Actual pre-upgrade SQLite arrays migrate without moving carried items into active holes");

        String ui=new String(serializeUi(new coolplugin.gameplay.PlayerInventoryDrawer(system).buildHexInventory(big,30,coolplugin.gameplay.PlayerInventoryDrawer.DrawerTheme.DEFAULT,true)),java.nio.charset.StandardCharsets.ISO_8859_1);
        check(ui.contains("E ")&&!ui.contains("Carry slow")&&!ui.contains("Min equip")&&!ui.contains("Weight"),"Large inventory UI is scrollable, size-budget aware, and contains no weight readout");
        var manager=system.getWorldLogicManager();manager.clearAllRigs();
        Seq<String> stressIds=new Seq<>();int budget=0;
        for(var item:catalog.items)if(item.size==1&&item.attachMode!=coolplugin.gameplay.ShopItemDef.AttachMode.VFX&&budget+4<=30){stressIds.add(item.id);budget+=4;}
        while(stressIds.size<30)stressIds.add("");for(int i=0;i<60;i++)stressIds.add("wb-01");
        var stressSets=new Seq<Seq<String>>();stressSets.add(stressIds);
        for(var mode:new coolplugin.gameplay.ShopItemDef.AttachMode[]{coolplugin.gameplay.ShopItemDef.AttachMode.FOLLOW,coolplugin.gameplay.ShopItemDef.AttachMode.HALO,coolplugin.gameplay.ShopItemDef.AttachMode.SUBWEAPON}){
            Seq<String> set=new Seq<>();int used=0;
            for(var item:catalog.items)if(item.attachMode==mode&&used+Math.max(4,item.size)<=30){set.add(item.id);used+=Math.max(4,item.size);}
            while(set.size<30)set.add("");for(int i=0;i<60;i++)set.add("wb-01");stressSets.add(set);
        }
        boolean programs=true,capped=true,alive=true;int max=0,partsMax=0;
        for(var stressSet:stressSets)for(var type:Vars.content.units())if(type.playerControllable&&!type.hidden){
            Unit stress=type.spawn(Team.sharded,180,180);manager.syncRig("capacity-live",stress,stressSet,catalog);
            var parts=manager.getProcessorsForOwner("capacity-live");partsMax=Math.max(partsMax,parts.size);alive &=!parts.isEmpty();
            capped &=manager.getCompiledEntries("capacity-live").count(e->e.inventorySlot()>=30)==6;
            for(var part:parts){max=Math.max(max,coolplugin.gameplay.WorldItemLogicManager.instructionCount(part.code));programs &=coolplugin.gameplay.WorldItemLogicManager.instructionCount(part.code)<=500;part.update();}
            programs &=parts.size<=10;manager.clearRig("capacity-live");stress.remove();
        }
        check(alive&&programs,"Maximum T5 mixed/follower/halo/gun loadouts plus 60 carries fit live 10-processor/500-instruction limits on every playable host");
        check(capped,"Only six carried items get markers; the other 54 are never drawn");
        System.out.println("CAPACITY LIVE maxInstructions="+max+" maxProcessors="+partsMax);
    }

    private static void verifyMountAimAndInventory(CoolPlugin plugin,Player player,Fi config)throws Exception{
        var system=plugin.getPlayerItemSystem();var catalog=system.getCatalog();var gun=catalog.findItem("wb-06");
        check(!gun.subweaponAutoTarget&&catalog.findItem("bz-05").subweaponAutoTarget&&catalog.findItem("ca-05").subweaponAutoTarget
            &&!catalog.findItem("bz-test-reign").subweaponAutoTarget,"Most mounts aim manually; only the two explicitly selected autonomous guns auto-target by default");
        var savedFile=config.child("aim-roundtrip.hjson");coolplugin.gameplay.ShopConfigIO.save(savedFile,catalog);
        check(!coolplugin.gameplay.ShopConfigIO.load(savedFile).findItem("wb-06").subweaponAutoTarget
            &&coolplugin.gameplay.ShopConfigIO.load(savedFile).findItem("bz-05").subweaponAutoTarget,"Per-item auto-target opt-in survives config serialization");
        var bullets=new Seq<mindustry.gen.CreateBulletCallPacket>();var originalNet=Vars.net;
        Vars.net=new Net(null){@Override public boolean server(){return true;}@Override public boolean active(){return true;}
            @Override public void send(Object object,boolean reliable){if(object instanceof mindustry.gen.CreateBulletCallPacket b)bullets.add(b);}};
        Unit host=UnitTypes.reign.spawn(Team.sharded,180,180),enemy=UnitTypes.dagger.spawn(Team.crux,180,300);
        host.aimX=300;host.aimY=180;host.isShooting=false;float oldDelta=Time.delta;Time.delta=12;
        var load=Seq.with(gun.id);var computed=system.computeStats(load,host);
        system.updateVisualRigForUnit("aim-probe",host,load,computed);
        check(bullets.isEmpty(),"Manual-only mount never autonomously fires at a nearby enemy");
        gun.subweaponAutoTarget=true;
        system.updateVisualRigForUnit("aim-probe",host,load,computed);
        var rigsField=system.getClass().getDeclaredField("serverRigs");rigsField.setAccessible(true);
        var rig=((arc.struct.ObjectMap<String,?>)rigsField.get(system)).get("aim-probe");
        var barrelsField=rig.getClass().getDeclaredField("subweapons");barrelsField.setAccessible(true);
        for(Object barrel:(Seq<?>)barrelsField.get(rig)){
            var target=barrel.getClass().getDeclaredField("cachedTarget");target.setAccessible(true);target.set(barrel,enemy);
            var scan=barrel.getClass().getDeclaredField("targetScanTimer");scan.setAccessible(true);scan.setFloat(barrel,-1000f);
        }
        bullets.clear();system.updateVisualRigForUnit("aim-probe",host,load,computed);
        var manager=system.getWorldLogicManager();var entries=manager.getCompiledEntries("aim-probe");var parts=manager.getProcessorsForOwner("aim-probe");
        boolean matched=!bullets.isEmpty();for(var part:parts){part.executor.optionalVar("hostUnit").setobj(host);for(int i=0;i<4000;i++)part.executor.runOnce();}
        for(var entry:entries)if(entry.kind()==coolplugin.gameplay.WorldItemLogicManager.RenderedKind.SUBWEAPON){
            float mx=host.x+arc.math.Angles.trnsx(host.rotation,entry.localForwardWorld(),entry.localSideWorld());
            float my=host.y+arc.math.Angles.trnsy(host.rotation,entry.localForwardWorld(),entry.localSideWorld());
            float desired=arc.math.Angles.angle(mx,my,enemy.x,enemy.y);
            var marker=(mindustry.game.MapObjectives.TextureMarker)Vars.state.markers.get(entry.primaryMarkerId());
            matched &=marker!=null&&arc.math.Angles.angleDist(marker.rotation+90,desired)<.1f;
            matched &=bullets.contains(b->arc.math.Angles.angleDist(b.angle,desired)<.1f);
        }
        check(matched,"Autonomous projectile angles match actual executed turret marker rotation instead of the unrelated player cursor");
        host.isShooting=true;bullets.clear();system.updateVisualRigForUnit("aim-probe",host,load,computed);
        boolean manualOverride=!bullets.isEmpty();for(var part:parts)for(var entry:entries){var flag=part.executor.optionalVar("autoAim"+entry.primaryMarkerId());if(flag!=null)manualOverride &=flag.numval==0;}
        check(manualOverride,"Player shooting immediately overrides autonomous aim and clears the client auto-aim flag");
        gun.subweaponAutoTarget=false;system.destroyRig("aim-probe");host.remove();enemy.remove();Vars.net=originalNet;Time.delta=oldDelta;
        var body=new java.awt.image.BufferedImage(7,7,java.awt.image.BufferedImage.TYPE_INT_ARGB);body.setRGB(3,3,0xffddaa66);
        var old=coolplugin.gameplay.ColouringAssets.outlineMount(body,0x565666,3);
        var stripped=coolplugin.gameplay.ColouringAssets.stripMountOutline(old,0x565666);
        var outlined=coolplugin.gameplay.ColouringAssets.outlineMount(stripped,0x123456,3);
        boolean single=true;int ink=0;
        for(int y=0;y<outlined.getHeight();y++)for(int x=0;x<outlined.getWidth();x++)if((outlined.getRGB(x,y)>>>24)>0){int rgb=outlined.getRGB(x,y)&0xffffff;if(rgb!=0xddaa66){single &=rgb==0x123456;ink++;}}
        check(single&&ink==28,"Old outline is removed and replaced by exactly one uniform circular native 3-pixel outline, not two coloured bands");
        boolean paletteEdges=true;
        for(var scheme:coolplugin.gameplay.ColouringAssets.palettes){
            var image=javax.imageio.ImageIO.read(config.child("assets/sprites/item-mounts/item-mount-wb-06-scheme-"+scheme.id()+".png").file());
            for(int y=1;y<image.getHeight()-1;y++)for(int x=1;x<image.getWidth()-1;x++)if((image.getRGB(x,y)>>>24)>0
                &&((image.getRGB(x-1,y)>>>24)==0||(image.getRGB(x+1,y)>>>24)==0||(image.getRGB(x,y-1)>>>24)==0||(image.getRGB(x,y+1)>>>24)==0))
                paletteEdges &=(image.getRGB(x,y)&0xffffff)==scheme.ramp()[0];
        }
        check(paletteEdges,"Generated mount outline edges use the exact selected palette outline colour for every scheme");
        var ownerField=coolplugin.gameplay.PlayerUnitSystem.class.getDeclaredField("owned");ownerField.setAccessible(true);
        @SuppressWarnings("unchecked") var owners=(arc.struct.ObjectMap<String,Unit>)ownerField.get(plugin.getPlayerUnitSystem());
        var savedOwner=owners.get(player.uuid());var savedUnit=player.unit();Unit invHost=UnitTypes.reign.spawn(Team.sharded,180,180);owners.put(player.uuid(),invHost);player.unit(invHost);
        var data=new EquipmentData();data.equipped.add("wb-01");system.saveEquipment(player,data);
        var menu=new coolplugin.gameplay.InventoryMenu(system);
        String ui=new String(serializeUi(menu.build(player,0,false,"")),java.nio.charset.StandardCharsets.ISO_8859_1);
        check(ui.contains("Item too small for slot!")&&!ui.contains("Carry slow")&&!ui.contains("Min equip")&&ui.contains("Destroy"),"Standalone inventory has item-size warning and destroy control without removed footer labels");
        var handler=new CommandHandler("/");menu.registerCommands(handler);
        check(handler.handleMessage("/i",player).type==CommandHandler.ResponseType.valid&&handler.handleMessage("/inv",player).type==CommandHandler.ResponseType.valid
            &&handler.handleMessage("/inventory",player).type==CommandHandler.ResponseType.valid,"All three inventory command aliases open the standalone menu");
        var sessionsField=menu.getClass().getDeclaredField("sessions");sessionsField.setAccessible(true);
        var session=((arc.struct.ObjectMap<String,?>)sessionsField.get(menu)).get(player.uuid());
        var tokenField=session.getClass().getDeclaredField("token");tokenField.setAccessible(true);
        var select=new mindustry.ui.builder.MenuResult("inv:0");select.token=tokenField.getLong(session);menu.handleResult(player,select);
        var destroy=new mindustry.ui.builder.MenuResult("destroy");destroy.token=tokenField.getLong(session);menu.handleResult(player,destroy);
        check(system.getEquipment(player).equipped.get(0).equals("wb-01"),"First Destroy click arms confirmation without removing the item");
        menu.handleResult(player,destroy);check(system.getEquipment(player).equipped.get(0).equals("wb-01"),"Replaying an old Destroy token cannot bypass confirmation");
        destroy.token=tokenField.getLong(session);menu.handleResult(player,destroy);
        check(system.getEquipment(player).equipped.isEmpty(),"Second confirmed Destroy click removes exactly the selected item");
        data=new EquipmentData();data.equipped.add("wb-01");system.saveEquipment(player,data);menu.open(player);
        session=((arc.struct.ObjectMap<String,?>)sessionsField.get(menu)).get(player.uuid());
        select.token=tokenField.getLong(session);menu.handleResult(player,select);
        var carryAction=new mindustry.ui.builder.MenuResult("carry");carryAction.token=tokenField.getLong(session);menu.handleResult(player,carryAction);
        check(system.getEquipment(player).equipped.get(30).equals("wb-01")&&system.getEquipment(player).equipped.get(0).isBlank(),"Standalone Carry button moves equipment into carry without losing it");
        String carryUi=new String(serializeUi(menu.build(player,30,false,"")),java.nio.charset.StandardCharsets.ISO_8859_1);
        check(!carryUi.contains("Item too small for slot!"),"Small-item warning is shown only while equipped, never while carried");
        select=new mindustry.ui.builder.MenuResult("inv:30");select.token=tokenField.getLong(session);menu.handleResult(player,select);
        var equipAction=new mindustry.ui.builder.MenuResult("equip");equipAction.token=tokenField.getLong(session);menu.handleResult(player,equipAction);
        var equippedAgain=system.getEquipment(player);
        check(equippedAgain.equipped.get(0).equals("wb-01")&&coolplugin.gameplay.PlayerItemSystem.usedCapacity(equippedAgain,true)==4,
            "Standalone Equip button accepts a small item and charges the T5 minimum four spaces");
        select=new mindustry.ui.builder.MenuResult("inv:0");select.token=tokenField.getLong(session);menu.handleResult(player,select);
        destroy=new mindustry.ui.builder.MenuResult("destroy");destroy.token=tokenField.getLong(session);menu.handleResult(player,destroy);
        select.token=tokenField.getLong(session);menu.handleResult(player,select); // selecting again cancels any armed destruction
        destroy.token=tokenField.getLong(session);menu.handleResult(player,destroy);
        check(system.getEquipment(player).equipped.get(0).equals("wb-01"),"Changing selection clears destroy confirmation and requires two fresh clicks");

        owners.put(player.uuid(),savedOwner);player.unit(savedUnit);invHost.remove();
    }

    private static Seq<String> dbLoadout(CoolPlugin plugin,Player p,String name){return plugin.getPlayerDatabase().getUnitLoadout(plugin.getSessionManager().getOrInitSession(p).id,name).equipped.copy();}

    private static Seq<String> legacySlots(Seq<String> old){
        Seq<String> result=new Seq<>();
        for(int i=0;i<Math.min(4,old.size);i++)result.add(old.get(i));
        if(old.size>4){while(result.size<30)result.add("");for(int i=4;i<old.size;i++)result.add(old.get(i));}
        return result;
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
