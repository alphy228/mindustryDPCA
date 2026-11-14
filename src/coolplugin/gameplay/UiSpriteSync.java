package coolplugin.gameplay;

import arc.Events;
import arc.files.Fi;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.game.EventType.PlayerConnectionConfirmed;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Player;
import mindustry.net.NetConnection;

/**
 * Streams custom UI/building/unit PNGs from {@code config/assets/sprites/} via
 * {@link mindustry.core.NetServer#sendTexture(NetConnection, String, byte[])} ({@code "net-<name>"}).
 *
 * <p>Workaround for a Mindustry v160.7 client bug where {@code DataImagePacker.unload()} removes
 * disposed {@code dp-*} regions from {@code patchAtlas.getDrawables()} rather than
 * {@code Core.atlas.getDrawables()}, causing {@code UiTreeBuilder} menus to display disposed blank
 * textures when rejoining a server without restarting the client. {@code NetClient} properly removes
 * {@code "net-*"} entries from {@code Core.atlas.getDrawables()} on disconnect and recreates them cleanly.
 */
public final class UiSpriteSync {

    private static final ObjectMap<String, byte[]> SPRITE_BYTES = new ObjectMap<>();
    private static final IntSet SENT_CONNECTIONS = new IntSet();
    private static boolean initialized = false;

    private UiSpriteSync() {}

    public static synchronized void init(Fi dataDirectory) {
        reloadSprites(dataDirectory);
        if (initialized) return;
        initialized = true;

        Events.on(PlayerConnectionConfirmed.class, e -> {
            if (e.player != null) {
                ensureSent(e.player);
            }
        });

        Events.on(PlayerLeave.class, e -> {
            if (e.player != null && e.player.con != null) {
                synchronized (UiSpriteSync.class) {
                    SENT_CONNECTIONS.remove(System.identityHashCode(e.player.con));
                }
            }
        });
    }

    public static synchronized void reloadSprites(@Nullable Fi dataDirectory) {
        SPRITE_BYTES.clear();
        SENT_CONNECTIONS.clear();
        Fi base = dataDirectory != null
            ? dataDirectory
            : (Vars.dataDirectory != null ? Vars.dataDirectory : new Fi("config"));
        Fi spritesDir = base.child("assets").child("sprites");
        if (!spritesDir.exists()) return;

        spritesDir.walk(file -> {
            if (!"png".equalsIgnoreCase(file.extension())) return;
            if (file.path().contains("/item-mounts/")) return; // World-only variants belong in the datapatch, not thousands of UI streams.
            if (file.path().contains("/colouring-generated/") && !file.name().startsWith("paint-can-")) return;
            String stem = file.nameWithoutExtension();
            try {
                byte[] bytes = file.readBytes();
                if (bytes.length > 0 && bytes.length <= 60_000) {
                    SPRITE_BYTES.put(stem, bytes);
                }
            } catch (Throwable t) {
                Log.warn("[CoolPlugin] Could not cache UI sprite @: @", file.path(), t.getMessage());
            }
        });

        Log.info("[CoolPlugin] Cached @ custom UI sprite(s) for reconnect-safe streaming.", SPRITE_BYTES.size);
    }

    public static synchronized void ensureSent(@Nullable Player player) {
        if (player == null || player.con == null || !player.con.isConnected()) return;
        if (Vars.netServer == null || SPRITE_BYTES.isEmpty()) return;

        int conHash = System.identityHashCode(player.con);
        if (!SENT_CONNECTIONS.add(conHash)) return;

        for (ObjectMap.Entry<String, byte[]> entry : SPRITE_BYTES) {
            try {
                Vars.netServer.sendTexture(player.con, entry.key, entry.value);
            } catch (Throwable t) {
                Log.warn("[CoolPlugin] Failed streaming UI texture '@' to @: @",
                    entry.key, player.plainName(), t.getMessage());
            }
        }
    }

    public static synchronized boolean hasSprite(@Nullable String stemOrDpName) {
        String stem = stripDpPrefix(stemOrDpName);
        return stem != null && SPRITE_BYTES.containsKey(stem);
    }

    /**
     * Converts a {@code dp-<stem>} (or raw {@code <stem>}) sprite name into {@code "net-<stem>"}
     * when that PNG exists in {@code config/assets/sprites/}; otherwise returns {@code original}.
     */
    public static synchronized String resolveMenuDrawable(@Nullable String original) {
        if (original == null || original.isEmpty()) return "clear";
        String stem = stripDpPrefix(original);
        if (stem != null && SPRITE_BYTES.containsKey(stem)) {
            return "net-" + stem;
        }
        return original;
    }

    private static @Nullable String stripDpPrefix(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        String s = name;
        if (s.startsWith("unit-dp-") && s.endsWith("-ui")) {
            s = s.substring("unit-dp-".length(), s.length() - "-ui".length());
        } else if (s.startsWith("block-dp-") && s.endsWith("-ui")) {
            s = s.substring("block-dp-".length(), s.length() - "-ui".length());
        } else if (s.startsWith("dp-") && s.endsWith("-ui")) {
            String candidate = s.substring("dp-".length(), s.length() - "-ui".length());
            if (SPRITE_BYTES.containsKey(candidate)) {
                return candidate;
            }
            s = s.substring("dp-".length());
        } else if (s.startsWith("dp-")) {
            s = s.substring("dp-".length());
        }
        return s;
    }
}
