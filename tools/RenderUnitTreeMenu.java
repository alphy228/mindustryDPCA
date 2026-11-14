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

/** Native Core.scene previews of all three horizontal scroll positions.
 * Usage: RenderUnitTreeMenu <repo-with-generated-config> <output-directory>
 */
public class RenderUnitTreeMenu {

    private static int streamChecks=0;
    public static final int WIDTH = 1360;
    public static final int HEIGHT = 800;

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

        // Initialize CoolPlugin systems against a clean temp SQLite DB + real config/alphystuff/shop.hjson
        Fi tempAlphy = new Fi("/tmp/preview-tool-alphy-" + System.nanoTime());
        tempAlphy.mkdirs();
        configDir.child("alphystuff").child("shop.hjson").copyTo(tempAlphy.child("shop.hjson"));
        configDir.child("alphystuff").child("techtree.hjson").copyTo(tempAlphy.child("techtree.hjson"));

        UiSpriteSync.init(configDir);
        PlayerDatabase db = new PlayerDatabase(tempAlphy.child("players.db"));
        db.init();
        PlayerSessionManager sessions = new PlayerSessionManager(db);
        sessions.start();
        TechTreeService techTree = new TechTreeService(tempAlphy, db, sessions);
        BuildSystem buildSystem = new BuildSystem(techTree);
        techTree.setStorage(new BuildResourceStorage(buildSystem));
        PlayerItemSystem itemSystem = new PlayerItemSystem(tempAlphy, db, sessions, buildSystem);
        coolplugin.gameplay.ColouringAssets.palettes.clear();
        var manifest=arc.util.serialization.Jval.read(configDir.child("colouring-cache/manifest.json").readString());
        for(var scheme:manifest.get("palettes").asArray()){
            var values=scheme.get("colours").asArray();int[] ramp=new int[values.size];
            for(int i=0;i<ramp.length;i++)ramp[i]=values.get(i).asInt();
            coolplugin.gameplay.ColouringAssets.palettes.add(new coolplugin.gameplay.ColouringAssets.Palette(scheme.get("id").asString(),scheme.get("name").asString(),scheme.get("tier").asInt(),ramp));
        }
        itemSystem.start();
        ItemShopSystem shopSystem = new ItemShopSystem(itemSystem, buildSystem, null, techTree, db, sessions);
        shopSystem.start();
        ItemShopMenu shopMenu = shopSystem.getShopMenu();

        // Create preview player with nearby storage resources & orbital storage
        Player player = Player.create();
        player.name = "Alphy";
        player.con = new DummyCon("preview-uuid-001");
        player.team(Team.sharded);
        player.add();

        if(outDir!=null){
            Vars.player=player;techTree.reloadTree();
            for(var tree:coolplugin.gameplay.UnitTree.values())if(tree.starter()!=null)techTree.unlockNode(player,tree.starter().name);
            var units=new coolplugin.gameplay.PlayerUnitSystem(buildSystem,techTree,sessions);
            var picker=units.getTreeMenu();
            for(int page=0;page<3;page++){treeScroll=page*.5f;renderMenuDialogToPng(picker.build(player,coolplugin.gameplay.FactionDef.SHARDED),outDir.child("preview-unit-trees-"+(page+1)+".png"),"151722");}
            Core.app.post(()->{
                try{
                    for(int page=0;page<3;page++){treeScroll=page*.5f;renderMenuDialogToPng(picker.build(player,coolplugin.gameplay.FactionDef.SHARDED),outDir.child("preview-unit-trees-"+(page+1)+".png"),"151722");}
                    System.out.println("TREE MENU PREVIEWS COMPLETE");Core.app.exit();
                }catch(Exception e){throw new RuntimeException(e);}
            });return;
        }
    }

    private static float treeScroll;
    private static void scrollTree(arc.scene.Element element){
        if(element instanceof arc.scene.ui.ScrollPane pane){pane.setScrollPercentX(treeScroll);pane.updateVisualScroll();}
        if(element instanceof arc.scene.Group group)for(var child:group.getChildren())scrollTree(child);
    }
    private static int renderMenuDialogToPng(NodeBuilder<?> serverTree, Fi outFile, String bgTintHex) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        TypeIO.writeUiBuilder(new Writes(new DataOutputStream(bos)), serverTree);
        byte[] wire = bos.toByteArray();
        NodeBuilder<?> clientTree = TypeIO.readUiBuilder(new Reads(new DataInputStream(new ByteArrayInputStream(wire))));

        Draw.scl = 1f;
        Draw.sort(false);
        Core.scene.clear();

        Table backdrop = new Table();
        backdrop.setFillParent(true);
        backdrop.setBackground(Styles.black8);
        Core.scene.add(backdrop);

        Dialog dialog = new Dialog("");
        dialog.setFillParent(true);
        dialog.titleTable.remove();
        dialog.addCloseButton();
        UiTreeBuilder.build(dialog.cont, clientTree, res -> {});
        dialog.show(Core.scene, null);

        Core.scene.act(0.016f);
        dialog.pack();
        dialog.setBounds(0, 0, WIDTH, HEIGHT);
        dialog.validate();
        Core.scene.act(0.016f);

        scrollTree(dialog);
        Color bg = Color.valueOf(bgTintHex);
        Gl.viewport(0, 0, WIDTH, HEIGHT);
        Gl.clearColor(bg.r, bg.g, bg.b, 1f);
        Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);

        Draw.proj().setOrtho(0, 0, WIDTH, HEIGHT);
        Draw.color(Color.valueOf("222636"), 0.55f);
        Lines.stroke(1f);
        for (int x = 0; x < WIDTH; x += 32) Lines.line(x, 0, x, HEIGHT);
        for (int y = 0; y < HEIGHT; y += 32) Lines.line(0, y, WIDTH, y);
        Draw.reset();
        Draw.flush();

        Core.scene.draw();
        Draw.flush();

        saveFramebufferPng(outFile);
        dialog.hide(null);
        return wire.length;
    }

    private static void saveFramebufferPng(Fi outFile) {
        Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, WIDTH, HEIGHT, true);
        PixmapIO.writePng(outFile, raw);
        raw.dispose();
    }
}
