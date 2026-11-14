import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.assets.AssetManager;
import arc.backend.sdl.SdlApplication;
import arc.backend.sdl.SdlConfig;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.graphics.Texture;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.ScreenUtils;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerDatabase.EquipmentData;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.BuildResourceStorage;
import coolplugin.gameplay.BuildSystem;
import coolplugin.gameplay.ItemShopMenu;
import coolplugin.gameplay.ItemShopSystem;
import coolplugin.gameplay.PlayerItemSystem;
import coolplugin.gameplay.ShopItemDef.StationTier;
import coolplugin.gameplay.UiSpriteSync;
import coolplugin.techtree.TechTreeService;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.FileTree;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.ctype.ContentType;
import mindustry.entities.EntityCollisions;
import mindustry.game.MapObjectives.ObjectiveMarker;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Drawc;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.Pal;
import mindustry.io.TypeIO;
import mindustry.logic.GlobalVars;
import mindustry.mod.DataPatcher;
import mindustry.mod.Mods;
import mindustry.mod.data.ContentAsset;
import mindustry.mod.data.PatchAsset;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.type.UnitType;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiTreeBuilder;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;

/** Native v160.7 SDL/OpenGL card art. No generated or composited stand-in sprites.
 * Usage: RenderUnitTreeArt <repo-with-generated-config> <output-directory>
 */
public class RenderUnitTreeArt {

    private static int streamChecks=0;
    public static final int WIDTH = 300;
    public static final int HEIGHT = 350;

    private static final class DummyCon extends NetConnection {
        DummyCon(String uuid) {
            super("127.0.0.1");
            this.uuid = uuid;
        }

        @Override
        public void send(Object object, boolean reliable) {}

        @Override
        public void close() {}

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    public static void main(String[] args) {
        String repoDir = args.length > 0 ? args[0] : "/tmp/mindustryDPCA";
        String outDir = args.length > 1 ? args[1] : "/home/user";

        SdlConfig cfg = new SdlConfig();
        cfg.title = "Mindustry v160.7 Fast Preview Tool (v15)";
        cfg.width = WIDTH;
        cfg.height = HEIGHT;
        cfg.disableAudio = true;
        cfg.decorated = false;
        cfg.resizable = false;
        cfg.vSyncEnabled = false;
        cfg.initialBackgroundColor = Color.valueOf("0d0e14");

        new SdlApplication(new ApplicationListener() {
            int frame = 0;

            @Override
            public void init() {
                try {
                    bootEngineAndRenderAll(new Fi(repoDir), new Fi(outDir));
                } catch (Throwable t) {
                    t.printStackTrace();
                    System.exit(1);
                }
            }

            @Override
            public void update() {
                frame++;
                if(streamChecks>=3)Core.app.exit();
                else if(frame>300){System.err.println("NATIVE STREAM TEST TIMEOUT checks="+streamChecks);System.exit(1);}
            }
        }, cfg);
    }

    private static void bootEngineAndRenderAll(Fi repoDir, Fi outDir) throws Exception {
        long t0 = System.currentTimeMillis();

        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi("/tmp/preview-tool-settings"));
        Scl.setProduct(1f);

        Core.batch = new SpriteBatch();
        Core.camera = new Camera();
        Core.camera.resize(WIDTH, HEIGHT);

        Core.assets = new AssetManager();
        Vars.tree = new FileTree();
        Vars.net = new Net(null);
        Vars.mods = new Mods();
        Vars.logicVars = new GlobalVars();
        CacheLayer.init();

        Fonts.loadDefaultFont();
        Core.assets.load("sprites/sprites.aatls", TextureAtlas.class);
        Core.assets.finishLoading();
        Core.atlas = Core.assets.get("sprites/sprites.aatls", TextureAtlas.class);
        Core.atlas.setErrorRegion("error");

        Fonts.mergeFontAtlas(Core.atlas);
        Fonts.loadFonts();
        Core.assets.finishLoading();
        mindustry.core.UI.loadColors();
        Fonts.outline.getData().markupEnabled = true;
        Fonts.def.getData().markupEnabled = true;
        Fonts.tech.getData().markupEnabled = true;

        // Register all custom PNG sprites from config/assets/sprites under <stem>, dp-<stem>, and net-<stem>
        Fi configDir = repoDir.child("config");
        Fi spritesDir = configDir.child("assets").child("sprites");
        int loadedSprites = 0;
        if (spritesDir.exists()) {
            for (Fi png : spritesDir.findAll(f -> "png".equalsIgnoreCase(f.extension()))) {
                String stem = png.nameWithoutExtension();
                if(stem.matches("item-mount-(?:wb|bz|ca)-[0-9]+")){
                    var dimensions=new Pixmap(png);float largest=Math.max(dimensions.width,dimensions.height);
                    coolplugin.gameplay.ColouringAssets.mountSizes.put(stem.substring("item-mount-".length()),new arc.math.geom.Vec2(dimensions.width*.25f,dimensions.height*.25f));dimensions.dispose();
                }
                Texture tex = new Texture(png);
                tex.setFilter(TextureFilter.nearest, TextureFilter.nearest);
                TextureRegion reg = new TextureRegion(tex);
                for (String key : new String[]{stem, "dp-" + stem, "net-" + stem}) {
                    if(key.startsWith("net-colour-preview-"))continue;
                    Core.atlas.addRegion(key, reg);
                    Core.atlas.getDrawables().put(key, new TextureRegionDrawable(reg));
                }
                loadedSprites++;
            }
        }

        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.loadColors();

        Core.scene = new Scene();
        Core.scene.resize(WIDTH, HEIGHT);
        Tex.load();
        Icon.load();
        Styles.load();
        Tex.loadStyles();
        Styles.techLabel.font.getData().markupEnabled = true;

        Vars.state = new GameState();
        Vars.collisions = new EntityCollisions();
        Vars.world = new World();
        Vars.indexer = new mindustry.ai.BlockIndexer();
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        Groups.init();

        Vars.content.init();
        Vars.logicVars.init();
        Vars.content.load();
        Fonts.loadContentIcons();

        // Build a real 48x48 Mindustry world
        Vars.headless = true;
        Vars.world.loadGenerator(48, 48, (Tiles tiles) -> {
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 48; x++) {
                    tiles.set(x, y, new Tile(x, y, ((x + y) % 5 == 0) ? Blocks.moss : Blocks.darkPanel3, Blocks.air, Blocks.air));
                }
            }
        });
        Vars.state.rules.unitCap = 9999;
        Vars.state.rules.fog = false;
        Vars.state.set(GameState.State.playing);

        // Apply DataPatcher for custom liquids, blocks (dp-workbench/dp-bazaar/dp-cursed-altar), and remaining non-item units
        Seq<ContentAsset> cAssets = new Seq<>();
        Seq<PatchAsset> pAssets = new Seq<>();
        String[][] customDirs = {
            {"liquids", "liquid"},
            {"blocks", "block"},
            {"units", "unit"}
        };
        for (String[] pair : customDirs) {
            Fi dir = configDir.child("assets").child("content").child(pair[0]);
            if (dir.exists()) {
                ContentType ct = ContentType.valueOf(pair[1]);
                for (Fi f : dir.findAll(file -> "hjson".equalsIgnoreCase(file.extension()))) {
                    if ("hjson".equalsIgnoreCase(f.extension())) {
                        cAssets.add(new ContentAsset(f.nameWithoutExtension(), ct, f.readString("UTF-8")));
                    }
                }
            }
        }
        Fi patchesDir = configDir.child("assets").child("patches");
        if (patchesDir.exists()) {
            for (Fi f : patchesDir.list()) {
                if ("hjson".equalsIgnoreCase(f.extension())) {
                    pAssets.add(new PatchAsset(f.readString("UTF-8")));
                }
            }
        }
        DataPatcher patcher = new DataPatcher();
        patcher.apply(pAssets, cAssets, false);
        Vars.headless = false;

        for (Block b : Vars.content.blocks()) {
            if (b.name != null && b.name.startsWith("dp-")) {
                b.init();
            }
            b.load();
            b.loadIcon();
        }
        for (UnitType u : Vars.content.units()) {
            if (u.name != null && u.name.startsWith("dp-")) {
                u.init();
            }
            u.load();
            u.loadIcon();
            if (u.shadowRegion == null) {
                u.shadowRegion = u.region;
            }
            if (u.weapons != null) {
                for (var w : u.weapons) {
                    w.init();
                    w.load();
                    if (w.bullet != null) {
                        w.bullet.init();
                        w.bullet.load();
                    }
                }
            }
        }

        renderTreeArt(outDir);System.out.println("TREE ART COMPLETE");Core.app.exit();
    }

    private static Unit nativeActor(String name,Team team,float x,float y,float angle,boolean shooting){
        Unit unit=Vars.content.unit(name).create(team);unit.set(x,y);unit.rotation=angle;
        unit.elevation=unit.type.flying?1f:0f;
        float tx=x+arc.math.Angles.trnsx(angle,100f),ty=y+arc.math.Angles.trnsy(angle,100f);
        unit.controller(new mindustry.entities.units.AIController(){
            @Override public void updateUnit(){unit.aim(tx,ty);unit.controlWeapons(shooting,shooting);}
        });
        unit.add();
        if(unit instanceof mindustry.gen.Legsc legs){legs.baseRotation(angle);legs.resetLegs();}
        return unit;
    }
    private static void nativeSceneWorld(boolean water){
        arc.util.Time.clear();Groups.clear();
        Vars.headless=true;
        Vars.world.loadGenerator(64,64,tiles->{for(int y=0;y<64;y++)for(int x=0;x<64;x++)tiles.set(x,y,new Tile(x,y,water?Blocks.water:Blocks.stone,Blocks.air,Blocks.air));});
        Vars.headless=false;Vars.state.set(GameState.State.playing);Vars.state.rules.unitCap=9999;
        Vars.state.rules.fog=false;Vars.state.rules.infiniteResources=false;Vars.state.rules.teams.get(Team.sharded).infiniteResources=true;
        Vars.state.rules.canGameOver=false;Vars.state.rules.waves=false;
    }
    /** Frames come from actual Weapon.update/Ability.update, bullet collisions and
     * native construction. Never instantiate a projectile or a muzzle/heal effect
     * for the screenshot, and never alter a weapon's range or bullet geometry. */
    private static void renderTreeArt(Fi output){
        if(Vars.renderer==null)Vars.renderer=new mindustry.core.Renderer();Vars.renderer.enableEffects=true;mindustry.graphics.Shaders.init();
        Vars.player=Player.create();Vars.player.team(Team.sharded);Vars.control=new mindustry.core.Control();Vars.fogControl=new mindustry.game.FogControl();Vars.pathfinder=new mindustry.ai.Pathfinder();Vars.controlPath=new mindustry.ai.ControlPathfinder();Vars.asyncCore=new mindustry.async.AsyncCore();
        if(Vars.ui==null)Vars.ui=new mindustry.core.UI();
        Core.settings.put("locale","en");
        if(Vars.ui.editor==null)Vars.ui.editor=new mindustry.editor.MapEditorDialog();
        for(var tree:coolplugin.gameplay.UnitTree.values()){
            boolean naval=tree==coolplugin.gameplay.UnitTree.ATTACK_NAVALS||tree==coolplugin.gameplay.UnitTree.SUPPORT_NAVALS;
            nativeSceneWorld(naval);Seq<Unit> actors=new Seq<>();
            int frames=18;float cameraX=190,cameraY=190,cameraWidth=100;
            switch(tree){
                case MECH -> {actors.add(nativeActor("mace",Team.crux,180,168,58,true));frames=8;cameraWidth=65;}
                case SUPPORT_MECH -> {
                    Unit first=nativeActor("vela",Team.sharded,168,164,65.2f,true),second=nativeActor("vela",Team.sharded,193,173,94.7f,true);
                    first.health*=.55f;second.health*=.55f;actors.add(first);actors.add(second);
                    // Native absorber: both real lasers hit the same map tile.
                    Tile targetTile=Vars.world.tile(24,27);targetTile.setBlock(Blocks.plastaniumWall,Team.crux);targetTile.build.health=1_000_000f;
                    for(Unit vela:actors)vela.controller(new mindustry.entities.units.AIController(){@Override public void updateUnit(){unit.aim(targetTile.worldx(),targetTile.worldy());unit.controlWeapons(true,true);}});
                    frames=92;cameraWidth=110;cameraY=190;
                }
                case ATTACK_AIR -> {actors.add(nativeActor("zenith",Team.sharded,180,168,58,true));frames=25;cameraWidth=100;}
                case SUPPORT_AIR -> {
                    Unit poly=nativeActor("poly",Team.sharded,176,168,37,false);actors.add(poly);
                    poly.addBuild(new mindustry.entities.units.BuildPlan(25,24,0,Blocks.copperWall));poly.updateBuilding=true;
                    frames=18;cameraWidth=65;cameraY=185;
                }
                case LEGGED -> {actors.add(nativeActor("arkyid",Team.sharded,180,168,58,true));frames=18;cameraWidth=120;}
                case ATTACK_NAVALS -> {actors.add(nativeActor("risso",Team.sharded,180,168,58,true));frames=15;cameraWidth=70;}
                case SUPPORT_NAVALS -> {
                    Unit retusa=nativeActor("retusa",Team.sharded,166,165,65,true),oxynoe=nativeActor("oxynoe",Team.sharded,191,177,65,true);
                    oxynoe.health*=.25f;retusa.health*=.6f;actors.add(retusa);actors.add(oxynoe);
                    // Native StatusFieldAbility has a 360-tick cooldown; capture just
                    // after its real pulse, while the real repair beam heals Oxynoe.
                    frames=367;cameraWidth=160;cameraY=176;
                }
                case CHEMICAL -> {
                    actors.add(nativeActor("dp-dissociate-unit",Team.sharded,180,168,58,true));frames=30;cameraWidth=130;
                    var liquid=Vars.content.liquids().find(l->l.name.contains("corrofluid"));
                    if(liquid==null)throw new IllegalStateException("Missing native corrofluid content");
                    for(int i=0;i<24;i++){float angle=i*137.5f,distance=22+(i%6)*4;Tile tile=Vars.world.tileWorld(180+arc.math.Angles.trnsx(angle,distance),168+arc.math.Angles.trnsy(angle,distance));mindustry.entities.Puddles.deposit(tile,liquid,55f);}
                }
                case TANK -> {actors.add(nativeActor("conquer",Team.sharded,180,165,58,true));frames=42;cameraWidth=125;}
                case INSECTOID -> {actors.add(nativeActor("cleroi",Team.sharded,164,165,62,true));actors.add(nativeActor("cleroi",Team.sharded,195,177,62,true));actors.add(nativeActor("merui",Team.sharded,181,145,62,true));frames=18;cameraWidth=110;}
                case FLYERS -> {actors.add(nativeActor("quell",Team.sharded,180,165,58,true));frames=26;cameraWidth=125;}
            }
            // Native simulation, including scheduled delayed shots, mirrored barrels,
            // repair-target selection, status fields, missile units and construction.
            arc.util.Time.delta=1f;
            for(int tick=0;tick<frames;tick++){
                arc.util.Time.update();Vars.state.tick++;
                Groups.updatePooling();Groups.unit.updatePhysics();Groups.bullet.updatePhysics();
                Groups.effect.update();Groups.all.update();Vars.state.teams.updateTeamStats();Groups.unit.update();Groups.bullet.update();Groups.build.update();Groups.bullet.collide();
            }
            if(tree==coolplugin.gameplay.UnitTree.SUPPORT_AIR){
                Tile tile=Vars.world.tile(25,24);
                if(!(tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild))throw new IllegalStateException("Poly frame must contain an actual in-progress native wall");
            }
            if(tree==coolplugin.gameplay.UnitTree.SUPPORT_NAVALS&&!actors.first().hasEffect(mindustry.content.StatusEffects.overclock))throw new IllegalStateException("Native Oxynoe pulse did not overclock Retusa");
            var buffer=new arc.graphics.gl.FrameBuffer(WIDTH,HEIGHT);buffer.begin(Color.clear);
            Gl.viewport(0,0,WIDTH,HEIGHT);Gl.clearColor(0,0,0,0);Gl.clear(Gl.colorBufferBit|Gl.depthBufferBit);
            Draw.scl=.25f;Core.camera.position.set(cameraX,cameraY);Core.camera.width=cameraWidth;Core.camera.height=cameraWidth*HEIGHT/WIDTH;Core.camera.update();Draw.proj(Core.camera);Draw.sort(true);
            for(var entity:Groups.all)if(entity instanceof mindustry.gen.Puddle puddle)puddle.draw();
            for(var building:Groups.build)building.draw();
            for(Unit unit:Groups.unit)unit.type.draw(unit);
            for(Unit unit:actors)unit.drawBuilding();
            for(var bullet:Groups.bullet)bullet.draw();
            for(var drawable:Groups.draw)if(drawable instanceof mindustry.gen.EffectState effect)effect.draw();
            Draw.sort(false);Draw.flush();Draw.reset();saveFramebufferPng(output.child(tree.art()+".png"));buffer.end();buffer.dispose();
            System.out.println("NATIVE CARD "+tree.id()+": "+frames+" ticks, "+Groups.bullet.size()+" actual bullets, "+Groups.unit.size()+" native units");
        }
    }

    private static void saveFramebufferPng(Fi outFile) {
        Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, WIDTH, HEIGHT, true);
        PixmapIO.writePng(outFile, raw);
        raw.dispose();
    }
}
