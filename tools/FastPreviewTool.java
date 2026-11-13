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
import coolplugin.gameplay.PlayerItemSystem;
import coolplugin.gameplay.PlayerItemSystem.ComputedStats;
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
import mindustry.game.Team;
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
import mindustry.world.Tile;
import mindustry.world.Tiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Fast (~3.5 second) authentic Mindustry v160.7 SDL2/OpenGL preview renderer.
 * Renders real Mindustry UI scenes (via TypeIO.writeUiBuilder -> TypeIO.readUiBuilder -> UiTreeBuilder.build)
 * and real Mindustry world units with MOUNT (symmetrical subtypes), HALO (center/weapons/engines HaloParts + Fx),
 * FOLLOW (standoff companions), and SUBWEAPON (rotating & firing turrets) directly from the OpenGL framebuffer.
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
        cfg.title = "Mindustry v160.7 Fast Preview Tool";
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
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        Groups.init();

        Vars.content.init();
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

        // Apply DataPatcher for custom liquids + units (including all 45 dp-shop-* MOUNT/FOLLOW/HALO/SUBWEAPON units!)
        Seq<ContentAsset> cAssets = new Seq<>();
        Seq<PatchAsset> pAssets = new Seq<>();
        Fi liquidsDir = configDir.child("assets").child("content").child("liquids");
        if (liquidsDir.exists()) {
            for (Fi f : liquidsDir.list()) {
                if ("hjson".equalsIgnoreCase(f.extension())) {
                    cAssets.add(new ContentAsset(f.nameWithoutExtension(), ContentType.liquid, f.readString("UTF-8")));
                }
            }
        }
        Fi unitsContentDir = configDir.child("assets").child("content").child("units");
        if (unitsContentDir.exists()) {
            for (Fi f : unitsContentDir.list()) {
                if ("hjson".equalsIgnoreCase(f.extension())) {
                    cAssets.add(new ContentAsset(f.nameWithoutExtension(), ContentType.unit, f.readString("UTF-8")));
                }
            }
        }
        DataPatcher patcher = new DataPatcher();
        patcher.apply(pAssets, cAssets, false);
        Vars.headless = false;
        for (UnitType u : Vars.content.units()) {
            u.init();
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
        ItemShopMenu shopMenu = new ItemShopMenu(itemSystem, buildSystem, db, sessions);
        shopMenu.register();

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

        // 1. Render WORKBENCH Menu Preview (4/10 gray counter, glowing selected slot #0, clean 2-col icon rectangles)
        EquipmentData eqWb = itemSystem.getEquipment(player);
        eqWb.equipped.clear();
        eqWb.equipped.addAll("wb-01", "wb-06", "wb-09", "wb-14");
        itemSystem.saveEquipment(player, eqWb);
        NodeBuilder<?> wbNode = shopMenu.buildForTest(player, StationTier.WORKBENCH, false, 0, "", "");
        int wbBytes = renderMenuDialogToPng(wbNode, outDir.child("preview-workbench-menu.png"), "151722");

        // 2. Render BAZAAR Menu Preview (6/10 RED over-4 counter, glowing slot #1, clicked item opening dsc panel!)
        EquipmentData eqBz = itemSystem.getEquipment(player);
        eqBz.equipped.clear();
        eqBz.equipped.addAll("bz-05", "bz-09", "bz-13", "wb-08", "bz-01", "bz-15");
        itemSystem.saveEquipment(player, eqBz);
        NodeBuilder<?> bzNode = shopMenu.buildForTest(player, StationTier.BAZAAR, false, 1, "bz-08", "");
        int bzBytes = renderMenuDialogToPng(bzNode, outDir.child("preview-bazaar-menu.png"), "111824");

        // 3. Render CURSED ALTAR Menu Preview (6/10 RED counter, glowing slot #3, clicked Apotheosis Halo dsc!)
        EquipmentData eqCa = itemSystem.getEquipment(player);
        eqCa.equipped.clear();
        eqCa.equipped.addAll("ca-04", "ca-08", "ca-05", "ca-15", "ca-07", "ca-02");
        itemSystem.saveEquipment(player, eqCa);
        NodeBuilder<?> caNode = shopMenu.buildForTest(player, StationTier.CURSED_ALTAR, false, 3, "ca-15", "");
        int caBytes = renderMenuDialogToPng(caNode, outDir.child("preview-altar-menu.png"), "1b1018");

        // Remove menu player's temp unit & vault before building clean 4-unit world showcase
        unit.remove();
        vaultTile.setNet(Blocks.air, Team.derelict, 0);
        Vars.indexer = new mindustry.ai.BlockIndexer();

        // 4. Render Live Engine Units with Symmetrical Mounts, Center/Weapon/Engine Halos, Followers & Firing Subweapons!
        renderUnitsWithEquippedItemsToPng(itemSystem, outDir.child("preview-items-on-units.png"));

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

    private static void renderUnitsWithEquippedItemsToPng(PlayerItemSystem itemSystem, Fi outFile) {
        if (Vars.renderer == null) {
            Vars.renderer = new mindustry.core.Renderer();
            Vars.renderer.enableEffects = true;
        }
        Groups.unit.clear();
        Groups.bullet.clear();
        Groups.draw.clear();
        itemSystem.clearAllRigs();

        float zoom = 5.6f;
        float worldW = WIDTH / zoom;
        float worldH = HEIGHT / zoom;

        Core.camera.position.set(worldW * 0.5f, worldH * 0.5f);
        Core.camera.width = worldW;
        Core.camera.height = worldH;
        Core.camera.update();

        // Spawn 4 showcase units with all 4 new systems: MOUNT (symmetrical subtypes), HALO (center/weapons/engines),
        // FOLLOW (floating standoff), and SUBWEAPON (actively aiming & firing real projectiles!)
        Unit u1 = spawnShowcaseUnit(UnitTypes.dagger, Team.sharded, worldW * 0.14f, worldH * 0.44f, 82f, false);
        // Dagger: Lead Buckler (SIDE_SHIELD mirrored), Silicon Sight (NOSE_PROW on-axis),
        // Graphite Striker (SUBWEAPON twin pods firing!), Copper Chunk (FOLLOW behind)
        Seq<String> load1 = Seq.with("wb-09", "wb-07", "wb-06", "wb-01");

        Unit u2 = spawnShowcaseUnit(UnitTypes.fortress, Team.sharded, worldW * 0.38f, worldH * 0.44f, 90f, false);
        // Fortress: Titanium Aegis (SIDE_SHIELD mirrored), Nano Fabricator (HALO on BOTH WEAPONS!),
        // Blast Cannon Pod (SUBWEAPON dorsal artillery firing!), Thorium Reactor Core (HALO on CENTER)
        Seq<String> load2 = Seq.with("bz-09", "bz-14", "bz-07", "bz-10");

        Unit u3 = spawnShowcaseUnit(UnitTypes.horizon, Team.crux, worldW * 0.63f, worldH * 0.46f, 65f, true);
        // Horizon: Astral Compass (HALO on ENGINES!), Phase Blink Drive (REAR_THRUSTER mirrored),
        // Phase Lance (SUBWEAPON wing railguns firing!), Titanium Crate + Silicon Pack (2 symmetric FOLLOWs!)
        Seq<String> load3 = Seq.with("bz-15", "bz-13", "bz-08", "bz-01", "bz-02");

        Unit u4 = spawnShowcaseUnit(UnitTypes.mega, Team.crux, worldW * 0.87f, worldH * 0.44f, 90f, true);
        // Mega: Apotheosis Halo (HALO on CENTER), Eye of Malice (HALO on all 4 WEAPONS!),
        // Abyssal Gatling (SUBWEAPON twin gatlings firing!), Bloodthirst Scythe (WING_BLADE mirrored)
        Seq<String> load4 = Seq.with("ca-15", "ca-07", "ca-05", "ca-04");

        ComputedStats st1 = itemSystem.computeStats(load1);
        ComputedStats st2 = itemSystem.computeStats(load2);
        ComputedStats st3 = itemSystem.computeStats(load3);
        ComputedStats st4 = itemSystem.computeStats(load4);

        // Simulate 5 half-steps so every subweapon fires its opening salvo and real bullets
        // are captured ~18-24 world units directly in front of each unit in open space!
        Time.delta = 0.5f;
        for (int tick = 0; tick < 5; tick++) {
            Time.time = tick * 3.0f;

            pinAndAimUnit(u1, worldW * 0.14f, worldH * 0.44f, 82f, false);
            pinAndAimUnit(u2, worldW * 0.38f, worldH * 0.44f, 90f, false);
            pinAndAimUnit(u3, worldW * 0.63f, worldH * 0.46f, 65f, true);
            pinAndAimUnit(u4, worldW * 0.87f, worldH * 0.44f, 90f, true);

            itemSystem.updateVisualRigForUnit("preview-1", u1, load1, st1);
            itemSystem.updateVisualRigForUnit("preview-2", u2, load2, st2);
            itemSystem.updateVisualRigForUnit("preview-3", u3, load3, st3);
            itemSystem.updateVisualRigForUnit("preview-4", u4, load4, st4);

            // Step subweapon AI & weapon mounts so real bullets spawn & travel forward
            for (Unit u : Groups.unit) {
                if (u == u1 || u == u2 || u == u3 || u == u4) continue;
                u.controller().updateUnit();
                if (u.mounts != null) {
                    for (var m : u.mounts) {
                        m.weapon.update(u, m);
                    }
                }
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

        Gl.viewport(0, 0, WIDTH, HEIGHT);
        Gl.clearColor(0.07f, 0.08f, 0.11f, 1f);
        Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);

        Draw.scl = 0.25f;
        Draw.proj(Core.camera);
        Draw.sort(true);

        // 1. Floor tiles at Z = 0
        Draw.z(0f);
        TextureRegion floorA = Core.atlas.find("dark-panel-3", Core.atlas.find("stone1"));
        TextureRegion floorB = Core.atlas.find("dark-panel-4", floorA);
        TextureRegion metalFloor = Core.atlas.find("metal-floor", floorA);
        for (int ty = 0; ty <= (int) (worldH / 8f) + 1; ty++) {
            for (int tx = 0; tx <= (int) (worldW / 8f) + 1; tx++) {
                TextureRegion fl = ((tx + ty) % 7 == 0) ? metalFloor : (((tx + ty) % 2 == 0) ? floorA : floorB);
                Draw.color(0.78f, 0.81f, 0.88f, 1f);
                Draw.rect(fl, tx * 8f + 4f, ty * 8f + 4f, 8f, 8f);
            }
        }
        Draw.reset();

        // 2. Shop buildings in upper showcase strip
        Draw.z(30f);
        drawShopBuildingPreview("dp-workbench", "reinforced-block-2", 38f, worldH - 25f, 12f, Color.valueOf("ffd37f"));
        drawShopBuildingPreview("dp-bazaar", "reinforced-block-3", worldW * 0.5f, worldH - 25f, 18f, Color.valueOf("7fe6ff"));
        drawShopBuildingPreview("dp-cursed-altar", "reinforced-block-5", worldW - 40f, worldH - 25f, 22f, Color.valueOf("ff4b6e"));

        // 3. Draw ALL real Mindustry units (host units + mounted sprites + HaloPart units + subweapon turrets),
        // real fired Bullets, and real Fx halo effects via Mindustry's native Z-sorted draw pipeline!
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

        Draw.sort(false);
        Draw.flush();

        // 4. Screen-space crisp caption cards & title banner
        Draw.scl = 1f;
        Draw.proj().setOrtho(0, 0, WIDTH, HEIGHT);
        Font font = Fonts.outline;
        font.getData().markupEnabled = true;

        drawCaptionCard(u1.x * zoom, 76f,
            "[#ffd37f]Dagger (Sym Mounts + Subweapon)[]\n[lightgray]Mirrored Lead Bucklers + Nose Sight (Mount)\nTwin Graphite Strikers firing + Copper Chunk (Follow)[]");
        drawCaptionCard(u2.x * zoom, 76f,
            "[#7fe6ff]Fortress (Weapon Halos + Dorsal Gun)[]\n[lightgray]Nano Halos on BOTH Weapons + Core Hex Halo\nMirrored Titanium Aegis + Dorsal Blast Cannon[]");
        drawCaptionCard(u3.x * zoom, 76f,
            "[#ff9f43]Horizon (Engine Halo + Wing Lances)[]\n[lightgray]Astral Halo on Engine + Twin Phase Blink Nacelles\nFiring Wing Phase Lances + 2 Standoff Followers[]");
        drawCaptionCard(u4.x * zoom, 76f,
            "[#ff4b6e]Mega (Center & Weapon Halos + Gatlings)[]\n[lightgray]Apotheosis Crown (Center) + 4 Malice Weapon Halos\nMirrored Scythes + Twin Firing Abyssal Gatlings[]");

        drawBannerTitle("[white]Items on Units[]  [gray](Symmetrical Hull Mounts / Center, Weapon & Engine Halos / Standoff Follow / Firing Subweapons)[]");

        Draw.reset();
        Draw.flush();
        saveFramebufferPng(outFile);
    }

    private static Unit spawnShowcaseUnit(UnitType type, Team team, float x, float y, float rot, boolean flying) {
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
        return u;
    }

    private static void pinAndAimUnit(Unit u, float x, float y, float rot, boolean flying) {
        u.set(x, y);
        u.rotation = rot;
        u.elevation = flying ? 1f : 0f;
        u.vel.setZero();
        u.aimX = x + Angles.trnsx(rot, 60f);
        u.aimY = y + Angles.trnsy(rot, 60f);
        u.isShooting = true;
    }

    private static void drawShopBuildingPreview(String spriteKey, String baseKey, float x, float y, float size, Color ringCol) {
        Draw.color(0f, 0f, 0f, 0.35f);
        Fill.rect(x + 1.2f, y - 1.2f, size, size);
        Draw.reset();
        TextureRegion base = Core.atlas.find(baseKey);
        if (base.found()) Draw.rect(base, x, y, size, size);
        TextureRegion top = Core.atlas.find(spriteKey + "-on", Core.atlas.find(spriteKey));
        if (top.found()) Draw.rect(top, x, y, size, size);

        Draw.color(ringCol, 0.65f);
        Lines.stroke(0.75f);
        for (int s = 0; s < 14; s++) {
            float a1 = s * (360f / 14f);
            float a2 = a1 + 14f;
            Lines.line(
                x + Angles.trnsx(a1, size * 0.78f + 4.5f),
                y + Angles.trnsy(a1, size * 0.78f + 4.5f),
                x + Angles.trnsx(a2, size * 0.78f + 4.5f),
                y + Angles.trnsy(a2, size * 0.78f + 4.5f)
            );
        }
        Draw.reset();
    }

    private static void drawCaptionCard(float cx, float cy, String markupText) {
        float w = 318f, h = 86f;
        Draw.color(0.05f, 0.06f, 0.09f, 0.88f);
        Fill.rect(cx, cy, w, h);
        Draw.color(Color.valueOf("45485a"));
        Lines.stroke(2f);
        Lines.rect(cx - w / 2f, cy - h / 2f, w, h);
        Draw.reset();

        Font f = Fonts.outline;
        float oldScaleX = f.getScaleX(), oldScaleY = f.getScaleY();
        f.getData().setScale(0.78f);
        f.draw(markupText, cx - w / 2f + 6f, cy + h / 2f - 12f, w - 12f, Align.center, true);
        f.getData().setScale(oldScaleX, oldScaleY);
    }

    private static void drawBannerTitle(String markupText) {
        float cx = WIDTH / 2f, cy = HEIGHT - 24f, w = 1080f, h = 34f;
        Draw.color(0.05f, 0.06f, 0.09f, 0.90f);
        Fill.rect(cx, cy, w, h);
        Draw.color(Pal.accent);
        Lines.stroke(2f);
        Lines.rect(cx - w / 2f, cy - h / 2f, w, h);
        Draw.reset();

        Font f = Fonts.outline;
        float oldScaleX = f.getScaleX(), oldScaleY = f.getScaleY();
        f.getData().setScale(0.90f);
        f.draw(markupText, cx - w / 2f, cy + 7f, w, Align.center, false);
        f.getData().setScale(oldScaleX, oldScaleY);
    }

    private static void saveFramebufferPng(Fi outFile) {
        Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, WIDTH, HEIGHT, true);
        PixmapIO.writePng(outFile, raw);
        raw.dispose();
    }
}
