package coolplugin.gameplay;

import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.Teamc;
import mindustry.world.Tile;

/** Native weapons, player-priority pursuit, native dynamic-goal ground pathfinding. */
public final class NeoplasmAI extends AIController {
    private final NeoplasmDirector director;
    private final Vec2 destination=new Vec2(),waypoint=new Vec2(),flightBefore=new Vec2(),steerBefore=new Vec2();
    public NeoplasmAI(NeoplasmDirector director){this.director=director;}
    @Override public boolean isLogicControllable(){return false;}
    @Override public void updateVisuals(){
        // Native wobble changes actual coordinates, not just the sprite. Do not let it
        // walk a nearly stopped aircraft sideways into a wall after safe steering.
        if(unit.isFlying()&&!FlyingTerrainGuard.flightPassable(unit,unit.x,unit.y)){
            unit.lookAt(unit.prefRotation());return;
        }
        super.updateVisuals();
    }
    @Override public Teamc findMainTarget(float x,float y,float range,boolean air,boolean ground){
        Teamc player=director.nearestPlayer(x,y,air,ground);
        if(player!=null&&!checkTarget(player,x,y,range))return player;
        // Single-target native units use the main target for all weapons too.
        if(ground){
            var building=Units.findEnemyTile(unit.team,x,y,range,b->b.block.targetable);
            if(building!=null)return building;
        }
        return player!=null?player:Units.closestTarget(unit.team,x,y,Float.MAX_VALUE,u->u.checkTarget(air,ground),b->ground&&b.block.targetable);
    }
    @Override public Teamc findTarget(float x,float y,float range,boolean air,boolean ground){
        Teamc player=director.nearestPlayer(x,y,air,ground);
        if(player!=null&&!checkTarget(player,x,y,range))return player;
        if(ground){
            var building=Units.findEnemyTile(unit.team,x,y,range,b->b.block.targetable);
            if(building!=null)return building;
        }
        return Units.closestTarget(unit.team,x,y,range,u->u.checkTarget(air,ground),b->ground&&b.block.targetable);
    }
    @Override public void updateMovement(){
        Teamc chase=director.nearestPlayer(unit.x,unit.y,unit.type.targetAir,unit.type.targetGround);
        if(chase==null)chase=target;
        if(chase==null)return;
        if(unit.isFlying()){
            if(!FlyingTerrainGuard.flightClear(unit,chase.x(),chase.y())){
                Vec2 detour=FlyingTerrainGuard.waypoint(unit,chase.x(),chase.y());
                if(detour==null){unit.approach(Vec2.ZERO);return;}
                steerFlight(detour,chase);return;
            }
            flightBefore.set(unit.vel);float oldRotation=unit.rotation;
            if(unit.type.circleTarget){
                Teamc saved=target;target=chase;circleAttack(unit.type.circleTargetRadius);target=saved;
            }else moveTo(chase,0f,8f);
            // A clear line to the target does not make the native circle/dive trajectory safe.
            if(!flightMomentumClear()){
                unit.vel.set(flightBefore);unit.rotation=oldRotation;
                waypoint.set(chase);steerFlight(waypoint,chase);
            }else if(unit.type.omniMovement)unit.lookAt(chase);
            return;
        }
        destination.set(chase);
        Tile tile=Vars.world.tileWorld(destination.x,destination.y);
        // A payload holder is often a solid building. Approach its perimeter, not
        // an unreachable tile at its centre; the weapons may attack the holder.
        if(tile!=null&&!unit.canPass(tile.x,tile.y)){
            Tile approach=null;float best=Float.MAX_VALUE;
            int size=chase instanceof mindustry.gen.Building build?build.block.size:1;
            for(int radius=1;radius<=size/2+3&&approach==null;radius++){
                for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                    if(Math.abs(dx)!=radius&&Math.abs(dy)!=radius)continue;
                    Tile candidate=Vars.world.tile(tile.x+dx,tile.y+dy);
                    if(candidate!=null&&unit.canPass(candidate.x,candidate.y)&&unit.dst2(candidate)<best){approach=candidate;best=unit.dst2(candidate);}
                }
            }
            if(approach!=null)destination.set(approach.worldx(),approach.worldy());
        }
        if(Vars.controlPath!=null){
            var path=Vars.controlPath.getPathPosition(unit,destination);
            if(path.move){waypoint.set(path.dest);moveTo(waypoint,0f,8f);}
        }else moveTo(destination,0f,8f);
        if(unit.type.omniMovement)unit.lookAt(chase);else faceMovement();
    }
    private void steerFlight(Vec2 point,Teamc chase){
        steerBefore.set(unit.vel);
        moveTo(point,0f,8f);
        // Do not rotate banked/non-omnidirectional flyers back toward a target behind a wall.
        if(unit.type.omniMovement)unit.lookAt(chase);
        if(!flightMomentumClear()){
            // Discard unsafe thrust before braking; otherwise thrust can cancel the brake
            // each tick and preserve an inward drift all the way through the wall.
            unit.vel.set(steerBefore);unit.approach(Vec2.ZERO);
        }
    }
    private boolean flightMomentumClear(){
        float speed=unit.vel.len();if(speed==0f)return true;
        if(FlyingTerrainGuard.forbidden(unit.x+unit.vel.x,unit.y+unit.vel.y))return false;
        float acceleration=Math.max(.001f,unit.type.accel*unit.speed());
        float distance=speed*speed/(2f*acceleration)+speed*2f;
        return FlyingTerrainGuard.flightClear(unit,unit.x+unit.vel.x/speed*distance,unit.y+unit.vel.y/speed*distance);
    }

}
