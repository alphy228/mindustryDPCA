package coolplugin.gameplay;

import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.ai.types.MissileAI;
import mindustry.content.Blocks;
import mindustry.content.StatusEffects;
import mindustry.entities.Units;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.UnitType;
import mindustry.world.Tile;

/** One authoritative simulation-clock survival director per loaded world. */
public final class NeoplasmDirector {
    public static final Team enemyTeam=Team.neoplastic;
    private record SpawnOrder(UnitType type,boolean boss,int players,int wave){}
    private final PlayerUnitSystem players;
    private final Fi configFile;
    private NeoplasmWaveConfig config;
    private final Seq<Tile> spawnPoints=new Seq<>();
    private final Queue<SpawnOrder> queue=new Queue<>();
    private final ObjectMap<String,Float> deaths=new ObjectMap<>();
    private final ObjectSet<String> warned=new ObjectSet<>();
    private Seq<PlayerUnitSystem.Survivor> roster=new Seq<>();
    private float elapsed,nextWave,continuousProgress,emptySince=-1f,aiRefresh;
    private int waveNumber,continuousSpawned,waveSpawned;
    private boolean started,armed;

    public NeoplasmDirector(Fi configFile,PlayerUnitSystem players){this.configFile=configFile;this.players=players;}
    public void start(){
        if(started)return;started=true;
        reset();
        Events.on(WorldLoadEvent.class,e->reset());
        Events.on(PlayEvent.class,e->configureRules());
        Events.on(GameOverEvent.class,e->queue.clear());
        Events.run(Trigger.update,()->{
            if(Vars.net!=null&&Vars.net.client())return;
            if(Vars.state==null||!Vars.state.isGame()||Vars.state.isPaused()||Vars.state.gameOver||Vars.world.isGenerating())return;
            update(Math.max(0f,arc.util.Time.delta/60f));
        });
    }
    public void reset(){
        config=NeoplasmWaveConfig.load(configFile);
        try{config.validateUnits();}catch(RuntimeException failure){
            Log.err("[Neoplasm] Invalid wave unit list; using defaults for this world.",failure);
            config=NeoplasmWaveConfig.parse(NeoplasmWaveConfig.DEFAULT_JSON);config.validateUnits();
        }
        elapsed=0f;nextWave=config.firstWave;continuousProgress=1f;emptySince=-1f;aiRefresh=0f;
        waveNumber=continuousSpawned=waveSpawned=0;armed=false;queue.clear();deaths.clear();warned.clear();roster=new Seq<>();
        spawnPoints.clear();
        if(Vars.world!=null&&Vars.world.tiles!=null)for(Tile tile:Vars.world.tiles)if(tile.overlay()==Blocks.spawn)spawnPoints.add(tile);
        configureRules();
        if(spawnPoints.isEmpty())Log.warn("[Neoplasm] Map has no vanilla enemy spawn overlays; enemy spawning is disabled until the next map. No arbitrary fallback tiles are used.");
    }
    private void configureRules(){
        if(Vars.state==null)return;
        var rules=Vars.state.rules;
        rules.canGameOver=false;rules.waves=false;rules.waveTimer=false;rules.winWave=0;rules.waveTeam=enemyTeam;
        rules.cleanupDeadTeams=false;
        Vars.state.wave=1;
    }
    /** Kept public for deterministic headless integration tests; seconds are simulation time. */
    public void update(float seconds){
        if(!Float.isFinite(seconds)||seconds<0f)throw new IllegalArgumentException("Invalid simulation delta");
        if(Vars.state.gameOver)return;
        float before=elapsed;elapsed+=seconds;
        roster=players.survivalPlayers();
        int connected=0,alive=0;
        for(var survivor:roster){
            if(survivor.connected())connected++;
            if(survivor.alive()){alive++;armed=true;deaths.remove(survivor.uuid());}
            else if(!deaths.containsKey(survivor.uuid()))deaths.put(survivor.uuid(),elapsed);
        }
        armed|=players.hasDeployedPlayers();
        if(armed){
            if(connected==0){
                if(emptySince<0f)emptySince=before;
                if(elapsed-emptySince>=config.emptyGrace){endGame();return;}
                // Retained units remain in the world, but do not spawn an endless
                // unattended army during the last-logout grace window.
                return;
            }
            emptySince=-1f;
            if(alive==0){endGame();return;}
        }
        aiRefresh-=seconds;
        if(aiRefresh<=0f){installControllers();aiRefresh=.5f;}
        if(!armed||connected==0)return;
        if(elapsed>=nextWave){
            waveNumber++;
            enqueueWave(waveNumber,connected);
            nextWave+=config.waveInterval;
            Vars.state.wave=waveNumber;
            Events.fire(new WaveEvent());
            Call.sendMessage("[accent]Wave "+waveNumber+"[] [lightgray]— Neoplasm incoming.[]");
        }
        // Integrate spawn progress rather than fixing a deadline at the previous
        // health value: damage, healing and player-count changes affect the rate now.
        if(elapsed>=config.continuousStart){
            float activeSeconds=elapsed-Math.max(before,config.continuousStart);
            continuousProgress+=activeSeconds/continuousInterval(connected);
            if(continuousProgress>=1f){
                var pool=config.wave(Math.max(1,waveNumber)).continuous();
                if(spawn(NeoplasmWaveConfig.resolve(pool.random()),false,connected,waveNumber)!=null)continuousSpawned++;
                continuousProgress-=1f;
            }
        }
        // Spread large swarms over successive updates; never monopolise a server tick.
        for(int i=0;i<8&&!queue.isEmpty();i++){
            SpawnOrder order=queue.removeFirst();
            if(spawn(order.type,order.boss,order.players,order.wave)!=null)waveSpawned++;
        }
    }
    public float continuousInterval(int playerCount){
        return config.baseInterval/(float)Math.pow(Math.max(1,playerCount),config.playerExponent)*healthReliefMultiplier();
    }
    public float deathWeight(float age){return config.deadWeight*Mathf.clamp(1f-Math.max(0f,age)/config.deadDecay);}
    public float healthReliefMultiplier(){
        float deficit=0f,weight=0f;
        for(var survivor:roster){
            if(survivor.alive()){weight+=1f;deficit+=1f-survivor.healthFraction();}
            else{
                Float death=deaths.get(survivor.uuid());float contribution=death==null?0f:deathWeight(elapsed-death);
                weight+=contribution;deficit+=contribution;
            }
        }
        return 1f+(config.maxRelief-1f)*(weight==0f?0f:Mathf.clamp(deficit/weight));
    }
    public float bossHealthScale(int playerCount,int wave){
        return (1f+config.bossHealthPerPlayer*Math.max(0,playerCount-1))*endlessScale(wave);
    }
    private float endlessScale(int number){return 1f+Math.max(0,number-config.waves.size)*config.endlessGrowth;}
    private void enqueueWave(int number,int count){
        var definition=config.wave(number);
        for(var group:definition.bosses())for(int i=0;i<group.base();i++)queue.addLast(new SpawnOrder(NeoplasmWaveConfig.resolve(group.unit()),true,count,number));
        for(var group:definition.swarm()){
            int amount=Mathf.ceil((group.base()+group.perExtraPlayer()*Math.max(0,count-1))*endlessScale(number));
            for(int i=0;i<amount;i++)queue.addLast(new SpawnOrder(NeoplasmWaveConfig.resolve(group.unit()),false,count,number));
        }
    }
    private Unit spawn(UnitType type,boolean boss,int count,int number){
        if(spawnPoints.isEmpty())return null;
        Tile marker=spawnPoints.random();Unit unit=type.create(enemyTeam);Tile tile=null;
        if(type.flying||unit.canPass(marker.x,marker.y))tile=marker;
        else for(int radius=1;radius<=12&&tile==null;radius++){
            Seq<Tile> usable=new Seq<>();
            for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                if(Math.abs(dx)!=radius&&Math.abs(dy)!=radius)continue;
                Tile candidate=Vars.world.tile(marker.x+dx,marker.y+dy);
                if(candidate!=null&&unit.canPass(candidate.x,candidate.y))usable.add(candidate);
            }
            if(!usable.isEmpty())tile=usable.random();
        }
        if(tile==null){if(warned.add(type.name))Log.warn("[Neoplasm] No compatible terrain within 12 tiles of spawn marker for @; skipping that spawn.",type.name);return null;}
        unit.set(tile.worldx(),tile.worldy());unit.rotation=Mathf.random(360f);unit.spawnedByCore=false;
        if(boss){
            unit.apply(StatusEffects.boss,Float.POSITIVE_INFINITY);
            unit.applyDynamicStatus().healthMultiplier=bossHealthScale(count,number);
            unit.healthMultiplier=StatusEffects.boss.healthMultiplier*bossHealthScale(count,number);
            // Native health multipliers scale effective HP by dividing incoming
            // damage. Keep raw health full, not overfull (which would double-scale).
            unit.health=unit.maxHealth;
        }
        unit.controller(new NeoplasmAI(this));unit.add();Units.notifyUnitSpawn(unit);
        return unit;
    }
    public void installControllers(){
        for(Unit unit:Groups.unit)if(unit.team==enemyTeam&&!unit.dead()&&!unit.isPlayer()
            &&!(unit.controller() instanceof MissileAI)&&!(unit.controller() instanceof NeoplasmAI))unit.controller(new NeoplasmAI(this));
    }
    public Teamc nearestPlayer(float x,float y,boolean air,boolean ground){
        Teamc nearest=null;float distance=Float.MAX_VALUE;
        for(var survivor:roster){
            if(!survivor.alive()||!(survivor.position() instanceof Teamc target)||target.team()==enemyTeam)continue;
            if(target instanceof Unit unit&&!unit.checkTarget(air,ground))continue;
            if(target instanceof Building&&!ground)continue;
            float candidate=target.dst2(x,y);if(candidate<distance){distance=candidate;nearest=target;}
        }
        return nearest;
    }
    private void endGame(){
        if(Vars.state.gameOver)return;
        Vars.state.gameOver=true;Vars.state.stats.wavesLasted=waveNumber;queue.clear();
        Call.updateGameOver(enemyTeam);Events.fire(new GameOverEvent(enemyTeam));
    }
    public float elapsedSeconds(){return elapsed;}
    public float nextWaveSeconds(){return nextWave;}
    public int waveNumber(){return waveNumber;}
    public int continuousSpawned(){return continuousSpawned;}
    public int waveSpawned(){return waveSpawned;}
    public int queuedSpawns(){return queue.size;}
    public int spawnPointCount(){return spawnPoints.size;}
}
