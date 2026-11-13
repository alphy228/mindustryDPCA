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

/**
 * Fast (~3.5 second) authentic Mindustry v160.7 SDL2/OpenGL preview renderer for v15.
 * Renders:
 *  - Real Mindustry UI scenes (via TypeIO.writeUiBuilder -> TypeIO.readUiBuilder -> UiTreeBuilder.build)
 *    including hidden unowned salvage relics vs visible owned salvage relics without 'Salvage only' text.
 *  - Real WorldProcessor + Plugin item rigs (TextureMarker / ShapeMarker / LineMarker in Vars.state.markers.worldMarkers),
 *    steady halved 5-tile (40f) shop circles, tail-following overburdened slots 5..10 with connection lines,
 *    and server-fired Bullets — with ZERO item carrier Units spawned!
 */
public class FastPreviewTool {

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
                if (frame >= 2) {
                    Core.app.exit();
                }
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
                Texture tex = new Texture(png);
                tex.setFilter(TextureFilter.nearest, TextureFilter.nearest);
                TextureRegion reg = new TextureRegion(tex);
                for (String key : new String[]{stem, "dp-" + stem, "net-" + stem}) {
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
                for (Fi f : dir.list()) {
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

        Unit unit = UnitTypes.dagger.create(Team.sharded);
        unit.set(20f * 8f, 20f * 8f);
        unit.add();
        player.unit(unit);

        Player camViewer = Player.create();
        camViewer.team(Team.sharded);
        Vars.player = camViewer;

        Vars.headless = true;
        Tile vaultTile = Vars.world.tile(23, 20);
        vaultTile.setNet(Blocks.vault, Team.sharded, 0);
        vaultTile.build.items.set(Items.copper, 1450);
        vaultTile.build.items.set(Items.lead, 980);
        vaultTile.build.items.set(Items.graphite, 520);
        vaultTile.build.items.set(Items.metaglass, 260);
        vaultTile.build.items.set(Items.silicon, 840);
        vaultTile.build.items.set(Items.titanium, 690);
        vaultTile.build.items.set(Items.thorium, 430);
        vaultTile.build.items.set(Items.plastanium, 310);
        vaultTile.build.items.set(Items.phaseFabric, 240);
        vaultTile.build.items.set(Items.surgeAlloy, 195);
        Vars.headless = false;

        AccountRecord acc = sessions.getOrInitSession(player);
        db.addOrbitalItem(acc.id, "sharded", "copper", 2400);
        db.addOrbitalItem(acc.id, "sharded", "lead", 1800);
        db.addOrbitalItem(acc.id, "sharded", "silicon", 950);
        db.addOrbitalItem(acc.id, "sharded", "titanium", 720);

        // 1. Render WORKBENCH Menu Preview (player does NOT own wb-04 -> wb-04 salvage relic is hidden!)
        EquipmentData eqWb = itemSystem.getEquipment(player);
        eqWb.equipped.clear();
        eqWb.equipped.addAll("wb-01", "wb-06", "wb-09", "wb-14");
        itemSystem.saveEquipment(player, eqWb);
        NodeBuilder<?> wbNode = shopMenu.buildForTest(player, StationTier.WORKBENCH, false, 0, "", "");
        int wbBytes = renderMenuDialogToPng(wbNode, outDir.child("preview-workbench-menu.png"), "151722");

        // 2. Render BAZAAR Menu Preview (player OWNS salvage relic bz-04 -> visible & opened in dsc WITHOUT 'Salvage only' text!)
        EquipmentData eqBz = itemSystem.getEquipment(player);
        eqBz.equipped.clear();
        eqBz.equipped.addAll("bz-05", "bz-04", "bz-13", "wb-08", "bz-01", "bz-15");
        itemSystem.saveEquipment(player, eqBz);
        NodeBuilder<?> bzNode = shopMenu.buildForTest(player, StationTier.BAZAAR, false, 1, "bz-04", "");
        int bzBytes = renderMenuDialogToPng(bzNode, outDir.child("preview-bazaar-menu.png"), "111824");

        // 3. Render CURSED ALTAR Menu Preview (6/10 RED counter, glowing slot #3, clicked Apotheosis Halo dsc!)
        EquipmentData eqCa = itemSystem.getEquipment(player);
        eqCa.equipped.clear();
        eqCa.equipped.addAll("ca-04", "ca-08", "ca-05", "ca-15", "ca-07", "ca-02");
        itemSystem.saveEquipment(player, eqCa);
        NodeBuilder<?> caNode = shopMenu.buildForTest(player, StationTier.CURSED_ALTAR, false, 3, "ca-15", "");
        int caBytes = renderMenuDialogToPng(caNode, outDir.child("preview-altar-menu.png"), "1b1018");

        // Remove menu player & vault before building clean 4-unit WorldProcessor showcase
        player.clearUnit();
        player.remove();
        unit.remove();
        vaultTile.setNet(Blocks.air, Team.derelict, 0);
        Vars.indexer = new mindustry.ai.BlockIndexer();

        // 4. Render Live WorldProcessor + Plugin Item System Showcase!
        renderUnitsWithEquippedItemsToPng(itemSystem, shopSystem, sessions, outDir.child("preview-items-on-units.png"));

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf(
            "FAST PREVIEW TOOL COMPLETE in %d ms (sprites=%d, wireBytes: workbench=%dB, bazaar=%dB, altar=%dB)%n",
            elapsed, loadedSprites, wbBytes, bzBytes, caBytes
        );
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

    private static void renderUnitsWithEquippedItemsToPng(
        PlayerItemSystem itemSystem, ItemShopSystem shopSystem, PlayerSessionManager sessions, Fi outFile
    ) throws Exception {
        Groups.unit.clear();
        Groups.bullet.clear();
        Groups.draw.clear();
        itemSystem.clearAllRigs();
        Vars.state.markers.clear();
        if (Vars.ui == null) {
            Vars.ui = new mindustry.core.UI();
        }

        float zoom = 4.5f;
        float worldW = WIDTH / zoom;   // ~302.2 world units (~37.8 tiles)
        float worldH = HEIGHT / zoom;  // ~177.8 world units (~22.2 tiles)

        // Center camera away from map border so WorldProcessors at y=0 stay off-screen
        float originX = 48f;
        float originY = 48f;
        Core.camera.position.set(originX + worldW * 0.5f, originY + worldH * 0.5f);
        Core.camera.width = worldW;
        Core.camera.height = worldH;
        Core.camera.update();

        // Place real dp-workbench, dp-bazaar, and dp-cursed-altar buildings in upper strip and generate their halved 5-tile (40f) ShapeMarkers!
        Block wbBlock = Vars.content.block("dp-workbench");
        Block bzBlock = Vars.content.block("dp-bazaar");
        Block caBlock = Vars.content.block("dp-cursed-altar");
        int stationTy = Math.round((originY + worldH - 50f) / 8f);
        Vars.headless = true;
        if (wbBlock != null) Vars.world.tile(Math.round((originX + 52f) / 8f), stationTy).setNet(wbBlock, Team.sharded, 0);
        if (bzBlock != null) Vars.world.tile(Math.round((originX + worldW * 0.50f) / 8f), stationTy).setNet(bzBlock, Team.sharded, 0);
        if (caBlock != null) Vars.world.tile(Math.round((originX + worldW - 52f) / 8f), stationTy).setNet(caBlock, Team.sharded, 0);
        Vars.headless = false;

        shopSystem.syncShopCircleMarkersNow();

        // Spawn 4 live showcase players & units — their item visuals are driven 100% by real WorldProcessors!
        // 1) Dagger (Active 4/10): Lead Buckler (SIDE_SHIELD mirrored), Silicon Sight (NOSE_PROW),
        //    Graphite Striker (firing twin SUBWEAPONs), Copper Chunk (FOLLOW + LineMarker tether)
        Player p1 = spawnShowcasePlayer(sessions, itemSystem, "p1-dagger", Team.sharded,
            UnitTypes.dagger, originX + worldW * 0.13f, originY + worldH * 0.38f, 85f, false,
            Seq.with("wb-09", "wb-07", "wb-06", "wb-01"));

        // 2) Fortress (Active 4/10): Titanium Aegis (SIDE_SHIELD mirrored), Nano Fabricator (WEAPONS diamond halos),
        //    Blast Cannon Pod (dorsal SUBWEAPON firing), Thorium Reactor Core (CENTER hex halo)
        Player p2 = spawnShowcasePlayer(sessions, itemSystem, "p2-fortress", Team.sharded,
            UnitTypes.fortress, originX + worldW * 0.37f, originY + worldH * 0.38f, 90f, false,
            Seq.with("bz-09", "bz-14", "bz-07", "bz-10"));

        // 3) Horizon (Overburdened 7/10 — Items 5..7 forced to TAIL_FOLLOW with crimson LineMarkers!):
        //    Slots 1..4: Astral Compass (ENGINES halo), Phase Blink Drive (REAR_THRUSTER), Phase Lance (firing SUBWEAPON), Titanium Crate (FOLLOW)
        //    Slots 5..7: Leviathan Husk (MOUNT->TAIL), Apotheosis Halo (HALO->TAIL), Abyssal Gatling (SUBWEAPON->TAIL)
        Player p3 = spawnShowcasePlayer(sessions, itemSystem, "p3-horizon", Team.crux,
            UnitTypes.horizon, originX + worldW * 0.63f, originY + worldH * 0.43f, 82f, true,
            Seq.with("bz-15", "bz-13", "bz-08", "bz-01", "ca-08", "ca-15", "ca-05"));

        // 4) Mega (Active 4/10): Apotheosis Halo (CENTER), Eye of Malice (4x WEAPONS halos),
        //    Abyssal Gatling (twin SUBWEAPONs firing), Bloodthirst Scythe (WING_BLADE mirrored)
        Player p4 = spawnShowcasePlayer(sessions, itemSystem, "p4-mega", Team.crux,
            UnitTypes.mega, originX + worldW * 0.88f, originY + worldH * 0.38f, 90f, true,
            Seq.with("ca-15", "ca-07", "ca-05", "ca-04"));

        Player[] showcasePlayers = {p1, p2, p3, p4};

        // Simulate 5 half-steps of real server plugin ticks + client WorldProcessor MLOG updates + bullet flight!
        Time.delta = 0.5f;
        for (int tick = 0; tick < 5; tick++) {
            Time.time = tick * 3.5f;
            pinAndAimPlayer(p1, originX + worldW * 0.13f, originY + worldH * 0.38f, 85f, false);
            pinAndAimPlayer(p2, originX + worldW * 0.37f, originY + worldH * 0.38f, 90f, false);
            pinAndAimPlayer(p3, originX + worldW * 0.63f, originY + worldH * 0.43f, 82f, true);
            pinAndAimPlayer(p4, originX + worldW * 0.88f, originY + worldH * 0.38f, 90f, true);

            Vars.headless = true;
            itemSystem.updateUnitsAndVisuals();
            Vars.headless = false;
            for (Player sp : showcasePlayers) {
                LogicBuild proc = itemSystem.getWorldLogicManager().getProcessorForOwner(sp.uuid());
                if (proc != null) {
                    proc.update();
                }
            }
            if (Vars.renderer == null) {
                Vars.renderer = new mindustry.core.Renderer();
                Vars.renderer.enableEffects = true;
            }
            for (Bullet b : Groups.bullet) {
                b.update();
            }
            for (Drawc d : Groups.draw) {
                if (d instanceof mindustry.gen.Timedc tc) {
                    tc.update();
                }
            }
        }

        if (Groups.unit.size() != 4) {
            throw new IllegalStateException("Expected exactly 4 host units (0 item units), got " + Groups.unit.size());
        }

        Gl.viewport(0, 0, WIDTH, HEIGHT);
        Gl.clearColor(0.07f, 0.08f, 0.11f, 1f);
        Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);

        // Set camera AFTER simulation loop because new Renderer() replaces Core.camera!
        Core.camera.position.set(originX + worldW * 0.5f, originY + worldH * 0.5f);
        Core.camera.width = worldW;
        Core.camera.height = worldH;
        Core.camera.update();

        Draw.scl = 0.25f;
        Draw.proj(Core.camera);
        Draw.sort(true);

        // 1. Floor tiles at Z = 0
        Draw.z(0f);
        TextureRegion floorA = Core.atlas.find("dark-panel-3", Core.atlas.find("stone1"));
        TextureRegion floorB = Core.atlas.find("dark-panel-4", floorA);
        TextureRegion metalFloor = Core.atlas.find("metal-floor", floorA);
        int minTx = Math.max(0, (int) (originX / 8f) - 2);
        int maxTx = Math.min(Vars.world.width() - 1, (int) ((originX + worldW) / 8f) + 2);
        int minTy = Math.max(0, (int) (originY / 8f) - 2);
        int maxTy = Math.min(Vars.world.height() - 1, (int) ((originY + worldH) / 8f) + 2);
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                TextureRegion fl = ((tx + ty) % 7 == 0) ? metalFloor : (((tx + ty) % 2 == 0) ? floorA : floorB);
                Draw.color(0.78f, 0.81f, 0.88f, 1f);
                Draw.rect(fl, tx * 8f + 4f, ty * 8f + 4f, 8f, 8f);
            }
        }
        Draw.reset();

        // 2. Real placed shop buildings (dp-workbench, dp-bazaar, dp-cursed-altar)
        Draw.z(30f);
        Seq<Building> drawnBuilds = new Seq<>();
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                Tile t = Vars.world.tile(tx, ty);
                if (t != null && t.build != null && !(t.build instanceof LogicBuild) && !drawnBuilds.contains(t.build)) {
                    drawnBuilds.add(t.build);
                    Draw.reset();
                    t.build.draw();
                }
            }
        }

        // 3. Draw ONLY the 4 real host units + server-fired Bullets + ALL WorldProcessor & Shop ObjectiveMarkers!
        for (Drawc d : Groups.draw) {
            Draw.reset();
            d.draw();
        }
        for (Unit u : Groups.unit) {
            Draw.reset();
            u.type.draw(u);
        }
        for (Bullet b : Groups.bullet) {
            Draw.reset();
            b.draw();
        }

        // Reset Draw.scl = 1f for ObjectiveMarkers since TextureMarker/ShapeMarker/LineMarker coordinates are already in world units!
        Draw.scl = 1f;
        for (ObjectiveMarker m : Vars.state.markers.worldMarkers) {
            Draw.reset();
            m.draw(1f);
        }

        Draw.sort(false);
        Draw.flush();

        // 4. Screen-space crisp caption cards & title banner
        Draw.scl = 1f;
        Draw.proj().setOrtho(0, 0, WIDTH, HEIGHT);
        Font font = Fonts.outline;
        font.getData().markupEnabled = true;

        drawCaptionCard((p1.unit().x - originX) * zoom, 56f,
            "[#ffd37f]Dagger [gray](4/10 Active)[]\n[lightgray]WorldProc Mounts + Firing Twin Strikers\nCopper Chunk Follow + Tether LineMarker[]");
        drawCaptionCard((p2.unit().x - originX) * zoom, 56f,
            "[#7fe6ff]Fortress [gray](4/10 Active)[]\n[lightgray]Steady Weapon & Core ShapeMarker Halos\nMirrored Aegis + Firing Dorsal Blast Pod[]");
        drawCaptionCard((p3.unit().x - originX) * zoom, 56f,
            "[#ff9f43]Horizon [scarlet](7/10 Overburdened)[]\n[lightgray]Slots 1-4 Active | [#ff6b6b]Slots 5-7 Forced to Tail\nwith Crimson LineMarker Tethers![]");
        drawCaptionCard((p4.unit().x - originX) * zoom, 56f,
            "[#ff4b6e]Mega [gray](4/10 Active)[]\n[lightgray]Steady Center + 4x Weapon Polygon Halos\nMirrored Scythes + Twin Firing Gatlings[]");

        drawBannerTitle("[white]v15 WorldProcessor + Plugin Items[]  [gray](0 Item Units | Steady 5-Tile Shop Rings | Local Mounts | Tail Connection Lines)[]");

        Draw.reset();
        Draw.flush();
        saveFramebufferPng(outFile);
    }

    private static Player spawnShowcasePlayer(
        PlayerSessionManager sessions, PlayerItemSystem itemSystem, String uuid, Team team,
        UnitType type, float x, float y, float rot, boolean flying, Seq<String> equipped
    ) {
        Player p = Player.create();
        p.name = uuid;
        p.con = new DummyCon(uuid);
        p.team(team);
        p.add();

        Unit u = type.create(team);
        u.set(x, y);
        u.rotation = rot;
        u.elevation = flying ? 1f : 0f;
        if (u.mounts != null) {
            for (var m : u.mounts) {
                m.rotation = rot;
            }
        }
        u.add();
        p.unit(u);

        sessions.getOrInitSession(p);
        EquipmentData eq = itemSystem.getEquipment(p);
        eq.equipped.clear();
        eq.equipped.addAll(equipped);
        itemSystem.saveEquipment(p, eq);
        pinAndAimPlayer(p, x, y, rot, flying);
        return p;
    }

    private static void pinAndAimPlayer(Player p, float x, float y, float rot, boolean flying) {
        Unit u = p.unit();
        u.set(x, y);
        u.rotation = rot;
        u.elevation = flying ? 1f : 0f;
        u.vel.setZero();
        float aimX = x + Angles.trnsx(rot, 60f);
        float aimY = y + Angles.trnsy(rot, 60f);
        u.aimX = aimX;
        u.aimY = aimY;
        u.isShooting = true;
        p.mouseX = aimX;
        p.mouseY = aimY;
        p.shooting = true;
    }

    private static void drawCaptionCard(float cx, float cy, String markupText) {
        float w = 322f, h = 86f;
        Draw.color(0.05f, 0.06f, 0.09f, 0.88f);
        Fill.rect(cx, cy, w, h);
        Draw.color(Color.valueOf("45485a"));
        Lines.stroke(2f);
        Lines.rect(cx - w / 2f, cy - h / 2f, w, h);
        Draw.reset();

        Font f = Fonts.outline;
        float oldScaleX = f.getScaleX(), oldScaleY = f.getScaleY();
        f.getData().setScale(0.76f);
        f.draw(markupText, cx - w / 2f + 6f, cy + h / 2f - 12f, w - 12f, Align.center, true);
        f.getData().setScale(oldScaleX, oldScaleY);
    }

    private static void drawBannerTitle(String markupText) {
        float cx = WIDTH / 2f, cy = HEIGHT - 24f, w = 1140f, h = 34f;
        Draw.color(0.05f, 0.06f, 0.09f, 0.90f);
        Fill.rect(cx, cy, w, h);
        Draw.color(Pal.accent);
        Lines.stroke(2f);
        Lines.rect(cx - w / 2f, cy - h / 2f, w, h);
        Draw.reset();

        Font f = Fonts.outline;
        float oldScaleX = f.getScaleX(), oldScaleY = f.getScaleY();
        f.getData().setScale(0.86f);
        f.draw(markupText, cx - w / 2f, cy + 7f, w, Align.center, false);
        f.getData().setScale(oldScaleX, oldScaleY);
    }

    private static void saveFramebufferPng(Fi outFile) {
        Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, WIDTH, HEIGHT, true);
        PixmapIO.writePng(outFile, raw);
        raw.dispose();
    }
}
