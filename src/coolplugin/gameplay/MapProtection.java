package coolplugin.gameplay;

import arc.Core;
import arc.Events;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.*;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.net.Administration.ActionType;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.BaseShield;
import mindustry.world.consumers.ConsumePower;

/** Authoritative spawn-floor clearance and indestructible map barriers. */
public final class MapProtection {
    private final ObjectSet<Tile> spawnTiles = new ObjectSet<>();
    private boolean started;

    public void start(){
        if(started)return;
        started=true;
        protectProjector();
        Events.on(WorldLoadEvent.class,e->{protectProjector();cacheSpawns();clearSpawnBuildings();});
        // Tile listeners must not mutate tiles in the middle of setBlock/linking.
        Events.on(TileChangeEvent.class,e->{
            if(e.tile.build!=null&&overlapsSpawn(e.tile.build)){
                Tile tile=e.tile.build.tile;
                Core.app.post(()->clearAt(tile));
            }
        });
        Events.on(TileFloorChangeEvent.class,e->refreshSpawn(e.tile));
        Events.on(TileOverlayChangeEvent.class,e->refreshSpawn(e.tile));
        Events.on(BlockBuildBeginEvent.class,e->{
            if(e.breaking&&e.tile.build instanceof ConstructBuild build&&build.previous==Blocks.largeShieldProjector){
                var team=build.team;int rotation=build.rotation;Tile tile=e.tile;
                // beginBreak fires this event after it finishes replacing the block.
                // Restore synchronously before any builder can advance deconstruction.
                tile.setBlock(Blocks.largeShieldProjector,team,rotation);
                Core.app.post(()->{
                    if(tile.block()==Blocks.largeShieldProjector)
                        tile.setNet(Blocks.largeShieldProjector,team,rotation);
                });
            }
        });
        if(Vars.netServer!=null)Vars.netServer.admins.addActionFilter(action->
            !(action.type==ActionType.breakBlock&&action.tile!=null&&action.tile.block()==Blocks.largeShieldProjector));
        Events.run(Trigger.update,()->{
            if(Vars.net.client()||!Vars.state.isGame()||Vars.world.isGenerating())return;
            clearSpawnBuildings();
            for(var unit:Groups.unit)for(int i=unit.plans().size-1;i>=0;i--){
                var plan=unit.plans().get(i);
                Tile tile=Vars.world.tile(plan.x,plan.y);
                if(plan.breaking&&tile!=null&&tile.block()==Blocks.largeShieldProjector)unit.plans().removeIndex(i);
            }
        });
        if(Vars.state.isGame()){cacheSpawns();clearSpawnBuildings();}
    }

    public static void protectProjector(){
        BaseShield shield=(BaseShield)Blocks.largeShieldProjector;
        shield.destructible=false;shield.breakable=false;shield.targetable=false;
        shield.consumesPower=false;shield.connectedPower=false;
        shield.removeConsumers(consumer->consumer instanceof ConsumePower);
        shield.reinitializeConsumers();shield.hasPower=false;
        shield.buildType=()->shield.new BaseShieldBuild(){
            @Override public float handleDamage(float amount){return 0f;}
            @Override public void damage(float amount){}
            @Override public boolean collide(Bullet bullet){return false;}
            @Override public boolean collision(Bullet bullet){return false;}
            @Override public void kill(){}
            @Override public void killed(){}
        };
    }

    private static boolean isSpawn(Tile tile){
        for(FactionDef faction:FactionDef.values())if(faction.matchesSpawnTile(tile))return true;
        return false;
    }
    private void refreshSpawn(Tile tile){
        if(tile==null)return;
        if(isSpawn(tile))spawnTiles.add(tile);else spawnTiles.remove(tile);
        // Floor/overlay event objects are reused; retain only the actual tile.
        if(isSpawn(tile)&&tile.build!=null)Core.app.post(()->clearAt(tile));
    }
    private void cacheSpawns(){
        spawnTiles.clear();
        for(Tile tile:Vars.world.tiles)if(isSpawn(tile))spawnTiles.add(tile);
    }
    public static boolean overlapsSpawn(Building building){
        if(building==null||building.tile==null)return false;
        int offset=-(building.block.size-1)/2;
        for(int dx=0;dx<building.block.size;dx++)for(int dy=0;dy<building.block.size;dy++)
            if(isSpawn(Vars.world.tile(building.tile.x+offset+dx,building.tile.y+offset+dy)))return true;
        return false;
    }
    private static void clearAt(Tile tile){
        if(Vars.net.client()||tile==null||!overlapsSpawn(tile.build))return;
        // Removal rather than damage also removes invulnerable buildings, without
        // explosion damage, rubble, or a reconstruction loop on the spawn tile.
        tile.build.tile.setNet(Blocks.air);
    }
    public void clearSpawnBuildings(){
        for(Tile tile:spawnTiles)if(isSpawn(tile)&&tile.build!=null)clearAt(tile.build.tile);
    }
}
