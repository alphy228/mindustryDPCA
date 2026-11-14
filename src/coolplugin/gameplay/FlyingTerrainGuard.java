package coolplugin.gameplay;

import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.Astar;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.Tile;

/** Spawn-zone style acceleration away from fully black terrain, independent of leg passability. */
public final class FlyingTerrainGuard {
    public static final float fullDarkness=3.5f, hardDepth=6f*Vars.tilesize;
    private static int[] nearest;
    private static int width,height;
    private static Object tiles;
    private static boolean dirty=true;
    private static final IntMap<Route> routes=new IntMap<>();
    private static float routeTick=-1;
    private static int searches;
    private final WorldItemLogicManager visuals;
    public FlyingTerrainGuard(WorldItemLogicManager visuals){this.visuals=visuals;}
    public void start(){
        Events.on(WorldLoadEvent.class,e->invalidate());
        Events.on(TileChangeEvent.class,e->dirty=true);
        Events.on(UnitDestroyEvent.class,e->routes.remove(e.unit.id));
        Events.run(Trigger.update,()->{
            if(Vars.net.client()||!Vars.state.isGame()||Vars.state.isPaused())return;
            for(Unit unit:Groups.unit){
                if(!unit.isFlying()||unit.dead)continue;
                // Native, command and logic-controlled AI also steer around black terrain.
                if(!unit.isPlayer()&&unit.vel.len2()>.01f){
                    Vec2 goal=new Vec2(unit.vel).setLength(12f*Vars.tilesize).add(unit);
                    Vec2 waypoint=waypoint(unit,goal.x,goal.y);
                    if(waypoint!=null)unit.vel.set(waypoint).sub(unit).setLength(Math.max(unit.speed(),unit.vel.len()));
                }
                correct(unit);
            }
        });
    }
    public static void invalidate(){dirty=true;tiles=null;routes.clear();}
    public static boolean forbidden(float x,float y){
        if(!Float.isFinite(x)||!Float.isFinite(y))return true;
        Tile tile=Vars.world.tileWorld(x,y);
        return tile==null||Vars.world.getDarkness(tile.x,tile.y)>=fullDarkness;
    }
    private static void field(){
        if(!dirty&&tiles==Vars.world.tiles)return;
        width=Vars.world.width();height=Vars.world.height();tiles=Vars.world.tiles;dirty=false;routes.clear();
        nearest=new int[width*height];java.util.Arrays.fill(nearest,-1);
        // Exact squared-Euclidean nearest-open transform. A plain eight-neighbour flood fill
        // chooses diagonal exits on flat walls, weakening resistance and making idle units slide.
        for(int y=0;y<height;y++){
            int left=-1;
            for(int x=0;x<width;x++){
                if(!forbidden(x*Vars.tilesize,y*Vars.tilesize))left=x;
                if(left>=0)nearest[x+y*width]=left+y*width;
            }
            int right=-1;
            for(int x=width-1;x>=0;x--){
                if(!forbidden(x*Vars.tilesize,y*Vars.tilesize))right=x;
                int p=x+y*width,old=nearest[p];
                if(right>=0&&(old<0||right-x<x-old%width))nearest[p]=right+y*width;
            }
        }
        int[] source=new int[height],v=new int[height];
        float[] f=new float[height],z=new float[height+1];
        for(int x=0;x<width;x++){
            int k=-1;
            for(int y=0;y<height;y++){
                source[y]=nearest[x+y*width];
                if(source[y]<0)continue;
                float dx=source[y]%width-x;f[y]=dx*dx;
                float cross=Float.NEGATIVE_INFINITY;
                while(k>=0){int q=v[k];cross=((f[y]+(float)y*y)-(f[q]+(float)q*q))/(2f*(y-q));if(cross>z[k])break;k--;}
                v[++k]=y;z[k]=k==0?Float.NEGATIVE_INFINITY:cross;z[k+1]=Float.POSITIVE_INFINITY;
            }
            if(k<0)continue;
            int at=0;for(int y=0;y<height;y++){while(at<k&&z[at+1]<y)at++;nearest[x+y*width]=source[v[at]];}
        }
    }

    private static Vec2 open(float x,float y){
        field();if(width==0||height==0)return null;
        int tx=Mathf.clamp(Math.round(x/Vars.tilesize),0,width-1),ty=Mathf.clamp(Math.round(y/Vars.tilesize),0,height-1);
        int p=nearest[tx+ty*width];return p<0?null:new Vec2((p%width)*Vars.tilesize,(p/width)*Vars.tilesize);
    }
    /** Gentle outward speed target scales with effective unit speed, not an accumulating impulse. */
    public static float pushStrength(float speed,float depth){
        float t=Mathf.clamp(depth/(2f*Vars.tilesize));
        return Math.max(.1f,speed)*(.18f+.17f*t*t*(3f-2f*t));
    }
    /** Bounded relaxation toward a small outward speed: repeated frames cannot accumulate a launch. */
    public static float pushDelta(float outwardSpeed,float target,float delta){
        return pushDelta(outwardSpeed,target,delta,.25f);
    }
    public static float pushDelta(float outwardSpeed,float target,float delta,float resistance){
        float blend=1f-(float)Math.pow(1f-Mathf.clamp(resistance),Mathf.clamp(delta,0f,3f));
        return Math.max(0f,(target-outwardSpeed)*blend);
    }
    /** Cancel sustained inward input by two tiles, without increasing the outward drift speed. */
    public static float resistance(float depth){
        float t=Mathf.clamp(depth/(2f*Vars.tilesize));
        return .25f+.75f*t*t*(3f-2f*t);
    }
    public static float correctionSpeed(float outward,float speed,float depth){
        return Math.max(0f,-outward)*resistance(depth)+pushStrength(speed,depth);
    }
    public boolean correct(Player player){return player!=null&&correct(player.unit());}
    public boolean correct(Unit unit){
        if(unit==null||!unit.isAdded()||unit.dead||!unit.isFlying())return false;
        if(!forbidden(unit.x,unit.y))return false;
        Vec2 exit=open(unit.x,unit.y);if(exit==null)return false;
        Vec2 toward=new Vec2(exit).sub(unit);
        // Measure from the near edge of the non-black tile, not its centre.
        float depth=Math.max(0f,toward.len()-Vars.tilesize/2f);
        float strength=pushStrength(unit.speed(),depth);
        Vec2 normal=toward.cpy().nor();
        float outward=unit.vel.dot(normal),dt=Mathf.clamp(Time.delta,0f,3f);
        float change=pushDelta(outward,strength,dt,resistance(depth));
        unit.vel.add(normal.x*change,normal.y*change);
        if(unit.isPlayer()&&!unit.isLocal()){
            // Remote player positions come from client snapshots, not server velocity integration.
            // Move the CURRENT position incrementally and synchronize it on every push, including
            // idle input. Do not restore a safe point or repeatedly add an unbounded impulse.
            float displacement=correctionSpeed(outward,unit.speed(),depth)*dt;
            unit.set(unit.x+normal.x*displacement,unit.y+normal.y*displacement);
            Player player=unit.getPlayer();player.set(unit.x,unit.y);
            if(player.con!=null)Call.setPosition(player.con,unit.x,unit.y);
        }
        if(visuals!=null&&unit.isPlayer())visuals.pushTerrain(unit,normal.x*strength,normal.y*strength,resistance(depth));
        // Emergency only: normal acceleration already overpowers inward thrust well before this.
        // Preserve velocity; never stop the unit or roll it back to a previous frame.
        if(depth>=hardDepth){
            Vec2 boundary=exit.cpy().sub(toward.setLength(hardDepth-2f));
            unit.set(boundary);
            if(unit.isPlayer()){
                Player player=unit.getPlayer();player.set(boundary);
                if(visuals!=null)visuals.correctTerrain(unit,boundary.x,boundary.y);
                if(player.con!=null)Call.setPosition(player.con,boundary.x,boundary.y);
            }
        }
        return true;
    }
    private static final class Route{int goal;float until;Seq<Tile> path=new Seq<>();}
    private static boolean clear(float x,float y,float tx,float ty){
        int count=Math.max(1,(int)(Mathf.dst(x,y,tx,ty)/(Vars.tilesize/2f)));
        for(int i=1;i<=count;i++)if(forbidden(Mathf.lerp(x,tx,(float)i/count),Mathf.lerp(y,ty,(float)i/count)))return false;
        return true;
    }
    /** Cached black-terrain-excluding A*, with a global two-search-per-tick budget. Null preserves native motion. */
    public static Vec2 waypoint(Unit unit,float x,float y){
        field();if(forbidden(unit.x,unit.y))return open(unit.x,unit.y);
        Route route=routes.get(unit.id);
        if(route!=null&&route.until>Time.time&&route.path.isEmpty())return null;
        if(route!=null&&route.until>Time.time&&!route.path.isEmpty()){
            while(!route.path.isEmpty()&&unit.within(route.path.first(),Vars.tilesize*.8f))route.path.remove(0);
            if(!route.path.isEmpty())return new Vec2(route.path.first().worldx(),route.path.first().worldy());
        }
        if(clear(unit.x,unit.y,x,y))return null;
        Tile from=Vars.world.tileWorld(unit.x,unit.y);Vec2 end=open(x,y);if(from==null||end==null)return null;
        Tile to=Vars.world.tileWorld(end.x,end.y);if(to==null)return null;
        if(routeTick!=Time.time){routeTick=Time.time;searches=0;}
        if(searches++>=2)return null;
        route=new Route();route.goal=to.pos();route.until=Time.time+45f;
        route.path.addAll(Astar.pathfind(from,to,t->1f,t->!forbidden(t.worldx(),t.worldy())));
        routes.put(unit.id,route);
        return route.path.isEmpty()?null:new Vec2(route.path.first().worldx(),route.path.first().worldy());
    }
    public static String mlog(){return """
jump terrainDone equal @client 0
jump terrainDone notEqual u @clientUnit
jump terrainDone equal u null
sensor terrainDead u @dead
jump terrainDone notEqual terrainDead 0
jump terrainPush lessThanEq terrainUntil 0
jump terrainPush lessThan terrainUntil @tick
setprop @x u terrainX
setprop @y u terrainY
set terrainUntil 0
terrainPush:
jump terrainDone lessThanEq terrainPushUntil @tick
jump terrainDone equal terrainLastTick @tick
op sub terrainDt @tick terrainLastTick
op min terrainDt terrainDt 3
op max terrainDt terrainDt 0
set terrainLastTick @tick
sensor terrainVx u @velocityX
sensor terrainVy u @velocityY
op len terrainTarget terrainPushX terrainPushY
jump terrainDone lessThanEq terrainTarget 0
op div terrainNx terrainPushX terrainTarget
op div terrainNy terrainPushY terrainTarget
op mul terrainDotX terrainVx terrainNx
op mul terrainDotY terrainVy terrainNy
op add terrainOutward terrainDotX terrainDotY
op sub terrainChange terrainTarget terrainOutward
op max terrainChange terrainChange 0
op sub terrainBlend 1 terrainResistance
op pow terrainBlend terrainBlend terrainDt
op sub terrainBlend 1 terrainBlend
op mul terrainChange terrainChange terrainBlend
op mul terrainDx terrainNx terrainChange
op mul terrainDy terrainNy terrainChange
op add terrainVx terrainVx terrainDx
op add terrainVy terrainVy terrainDy
setprop @velocityX u terrainVx
setprop @velocityY u terrainVy
terrainDone:
""";}
}
