import arc.Events;
import arc.files.Fi;
import coolplugin.gameplay.UiSpriteSync;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.game.EventType.*;
import mindustry.gen.Player;
import mindustry.net.NetConnection;

/** Headless transport regression: client texture unloads are represented by lifecycle events. */
public class VerifyUiSpriteReload {
    public static class Transport extends NetServer {
        int sends; boolean fail;
        @Override public void sendTexture(NetConnection con, String name, byte[] bytes) {
            if(fail) throw new IllegalStateException("simulated transport failure");
            sends++;
        }
    }
    static class Connection extends NetConnection {
        Connection(){super("test");}
        @Override public boolean isConnected(){return true;}
        public void send(Object o, boolean reliable){}
        public void close(){}
    }
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    public static void main(String[] args) throws Exception {
        // Avoid initializing an actual network server: this test exercises only sendTexture.
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)field.get(null);
        Transport transport=(Transport)unsafe.allocateInstance(Transport.class);Vars.netServer=transport;
        Fi dir=Fi.tempDirectory("ui-sprite-reload");
        try {
            dir.child("assets/sprites/probe.png").writeBytes(new byte[]{1});
            UiSpriteSync.init(dir);
            Player player=Player.create();player.name="test";player.con=new Connection();
            Events.fire(new PlayerConnectionConfirmed(player));check(transport.sends==1,"initial confirmation");
            UiSpriteSync.ensureSent(player);check(transport.sends==1,"menu open deduplicates");
            for(int i=0;i<3;i++)Events.fire(new PlayerConnectionConfirmed(player));
            check(transport.sends==4,"repeated sync on SAME connection resends");
            Events.fire(new ResetEvent());check(transport.sends==4,"reset must not send before unload");
            UiSpriteSync.ensureSent(player);check(transport.sends==5,"menu after reset resends");
            Events.fire(new WorldLoadEvent());UiSpriteSync.ensureSent(player);
            check(transport.sends==6,"world change invalidates old cache");
            Events.fire(new PlayerLeave(player));Events.fire(new PlayerConnectionConfirmed(player));
            check(transport.sends==7,"rejoin resends");
            Events.fire(new ResetEvent());transport.fail=true;UiSpriteSync.ensureSent(player);
            transport.fail=false;UiSpriteSync.ensureSent(player);
            check(transport.sends==8,"failed send remains retryable");
            System.out.println("UI SPRITE RELOAD: "+checks+" passed, 0 failed");
        } finally {dir.deleteDirectory();}
    }
}
