package coolplugin.gameplay;

import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.Tile;

/** Only rejects terrain that native leg movement cannot step onto. No server item physics. */
public final class FlyingTerrainGuard {
    private final IntMap<Vec2> safe=new IntMap<>();
    private final WorldItemLogicManager visuals;
    public FlyingTerrainGuard(WorldItemLogicManager visuals){this.visuals=visuals;}
    public void start(){
        Events.on(WorldLoadEvent.class,e->safe.clear());
        Events.on(UnitDestroyEvent.class,e->safe.remove(e.unit.id));
        Events.run(Trigger.update,()->{
            if(Vars.net.client()||!Vars.state.isGame()||Vars.state.isPaused())return;
            for(Player player:Groups.player)if(player.con!=null&&player.con.isConnected()&&!player.dead())correct(player);
        });
    }
    public static boolean forbidden(float x,float y){if(!Float.isFinite(x)||!Float.isFinite(y))return true;Tile tile=Vars.world.tileWorld(x,y);return tile==null||tile.legSolid();}
    public boolean correct(Player player){
        Unit unit=player.unit();if(unit==null||!unit.isAdded()||!unit.isFlying())return false;
        Vec2 last=safe.get(unit.id);boolean blocked=forbidden(unit.x,unit.y);
        if(last!=null&&!blocked){
            int steps=Math.min(4096,Math.max(1,(int)Math.ceil(last.dst(unit)/4f)));
            for(int i=1;i<=steps;i++)if(forbidden(last.x+(unit.x-last.x)*i/steps,last.y+(unit.y-last.y)*i/steps)){blocked=true;break;}
        }
        if(!blocked){if(last==null)safe.put(unit.id,new Vec2(unit.x,unit.y));else last.set(unit);return false;}
        if(last==null||forbidden(last.x,last.y)){
            Tile origin=Vars.world.tileWorld(unit.x,unit.y);if(origin==null)return false;
            outer:for(int r=1;r<Math.max(Vars.world.width(),Vars.world.height());r++)for(int dx=-r;dx<=r;dx++)for(int dy=-r;dy<=r;dy++){
                if(Math.max(Math.abs(dx),Math.abs(dy))!=r)continue;
                Tile t=Vars.world.tile(origin.x+dx,origin.y+dy);
                if(t!=null&&!t.legSolid()){last=new Vec2(t.worldx(),t.worldy());safe.put(unit.id,last);break outer;}
            }
        }
        if(last==null)return false;
        unit.set(last.x,last.y);unit.vel.setZero();player.set(last.x,last.y);
        // Local world logic applies the same correction for ordinary clients;
        // this authoritative packet still corrects clients that disabled logic.
        if(visuals!=null)visuals.correctTerrain(unit,last.x,last.y);
        Call.setPosition(player.con,last.x,last.y);
        return true;
    }
    public static String mlog(){return """
jump terrainDone lessThanEq terrainUntil 0
jump terrainDone lessThan terrainUntil @tick
jump terrainDone equal u null
sensor terrainDead u @dead
jump terrainDone notEqual terrainDead 0
setprop @x u terrainX
setprop @y u terrainY
terrainDone:
""";}
}
