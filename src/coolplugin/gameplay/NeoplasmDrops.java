package coolplugin.gameplay;

import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.EventType.*;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.logic.LMarkerControl;
import mindustry.type.Item;
import mindustry.type.UnitType;

/** CruxDrops probability/quantity model, adapted to Neoplasm and marker-only loot. */
public final class NeoplasmDrops {
    public static final class GroundDrop {
        public final int id;public final float x,y;public final Item item;public int amount;
        GroundDrop(int id,float x,float y,Item item,int amount){this.id=id;this.x=x;this.y=y;this.item=item;this.amount=amount;}
    }
    public record Entry(Item item,int base,float chance,float[] amounts,float[] chances,float[] fluctuation){}
    private final Seq<Entry> entries=new Seq<>();
    private final Seq<GroundDrop> drops=new Seq<>();
    private final BuildSystem storage;
    private int nextId=0x4d000000,generation;
    private float scan;
    public NeoplasmDrops(BuildSystem storage){this.storage=storage;configure();}
    public void start(){
        Events.on(WorldLoadEvent.class,e->reset());
        Events.on(ResetEvent.class,e->reset());
        Events.on(UnitDestroyEvent.class,e->{
            Unit u=e.unit;if(u==null||u.team!=NeoplasmDirector.enemyTeam)return;
            int tier=tier(u.type);if(tier<0)return;
            Entry choice=select(tier,Mathf.random());if(choice==null)return;
            int amount=amount(choice,tier,Mathf.random(-1f,1f));
            if(amount<=0)return;
            float x=u.x,y=u.y;int world=generation;
            Time.run(1f,()->{if(world==generation&&Vars.state.isGame())create(x,y,choice.item,amount);});
        });
        Events.run(Trigger.update,()->{
            if(Vars.net.client()||!Vars.state.isGame()||Vars.state.isPaused()||Vars.state.gameOver)return;
            scan+=Time.delta;if(scan<6f)return;scan=0;updatePickups();
        });
    }
    public void reset(){for(GroundDrop d:drops){Call.removeMarker(d.id);Call.removeMarker(d.id+1);}drops.clear();generation++;scan=0;}
    public GroundDrop create(float x,float y,Item item,int amount){
        if(item==null||amount<=0)return null;
        GroundDrop drop=new GroundDrop(nextId,x,y,item,amount);nextId+=2;drops.add(drop);
        TextureMarker icon=new TextureMarker("item-"+item.name,x,y,6f,6f);icon.control(LMarkerControl.drawLayer,mindustry.graphics.Layer.flyingUnit+1f,Double.NaN,Double.NaN);
        Call.createMarker(drop.id,icon);
        Call.createMarker(drop.id+1,new TextMarker(Integer.toString(amount),x,y-7f,.55f,WorldLabel.flagOutline));
        return drop;
    }
    public void updatePickups(){
        for(int i=drops.size-1;i>=0;i--){
            GroundDrop drop=drops.get(i);int before=drop.amount;
            for(Player player:Groups.player){
                Unit u=player.unit();
                if(player.con==null||!player.con.isConnected()||u==null||u.dead()||!u.isValid()||!u.within(drop.x,drop.y,4f*Vars.tilesize+u.hitSize))continue;
                int accepted=storage.depositDrop(player,drop.item,drop.amount);
                if(accepted<=0)continue;
                drop.amount-=accepted;
                Call.effect(mindustry.content.Fx.itemTransfer,drop.x,drop.y,0f,drop.item.color,new arc.math.geom.Vec2(u.x,u.y));
                if(drop.amount==0)break;
            }
            if(drop.amount==0){Call.removeMarker(drop.id);Call.removeMarker(drop.id+1);drops.remove(i);}
            else if(drop.amount!=before)Call.updateMarkerText(drop.id+1,LMarkerControl.flushText,false,Integer.toString(drop.amount));
        }
    }
    public Seq<GroundDrop> groundDrops(){return drops;}
    /** Same eight columns as the supplied drop table. */
    public static int tier(UnitType type){
        String name=ColouringAssets.baseName(type.name);UnitTree line=UnitTree.forUnit(name);
        if(line!=null){
            int n=-1;for(int i=0;i<line.units.length;i++)if(line.units[i].equals(name))n=i;
            if(n>=3)return n+3;
            boolean erekir=line==UnitTree.TANK||line==UnitTree.INSECTOID||line==UnitTree.FLYERS;
            if(line==UnitTree.ATTACK_NAVALS||line==UnitTree.SUPPORT_NAVALS)return n==0?1:n;
            return n+(erekir?3:0);
        }
        return switch(name){case "alpha"->0;case "beta"->1;case "gamma"->2;case "evoke"->3;case "incite"->4;case "emanate"->5;default->-1;};
    }
    public static int amount(Entry e,int tier,float signedRoll){
        double base=Math.round(e.base*(double)e.amounts[tier]*4d);
        return (int)Math.min(Integer.MAX_VALUE,Math.max(base<=0?0:1,Math.round(base*(1f+Mathf.clamp(e.fluctuation[tier])*Mathf.clamp(signedRoll,-1f,1f)))));
    }
    public Entry select(int tier,float roll){
        float total=0;for(Entry e:entries)if(e.amounts[tier]>0)total+=Mathf.clamp(e.chance*e.chances[tier]);
        float cursor=roll*Math.max(1,total);if(cursor>=total)return null;
        for(Entry e:entries)if(e.amounts[tier]>0){cursor-=Mathf.clamp(e.chance*e.chances[tier]);if(cursor<0)return e;}return null;
    }
    public Seq<Entry> table(){return entries;}
    private static float[] a(float... values){return values;}
    private void add(Item item,int base,float chance,float[] amounts,float[] chances,float[] fluctuation){entries.add(new Entry(item,base,chance,amounts,chances,fluctuation));}
    private void configure(){
        float[] common=a(1,.9f,.8f,.95f,.85f,.75f,.6f,.5f),noHigh=a(1,.9f,.8f,.95f,.85f,0,0,0);
        float[] cf=a(.2f,.2f,.25f,.2f,.2f,.25f,.3f,.35f),tf=a(.15f,.15f,.2f,.15f,.15f,.2f,.25f,.3f);
        float[] tech=a(.45f,.7f,1,.55f,.8f,1.1f,1.35f,1.6f),rare=a(0,.15f,.6f,0,.3f,.9f,1.5f,2.1f),end=a(0,0,.2f,0,0,.7f,1.8f,2.8f);
        add(Items.copper,10,.35f,a(1,3,8,2,4,6,12,20),common,cf);
        add(Items.lead,8,.35f,a(1,3,8,2,4,6,12,20),common,cf);
        add(Items.graphite,6,.35f,a(1,2,5,2,4,6,10,18),common,tf);
        for(Item item:new Item[]{Items.sand,Items.scrap,Items.coal})add(item,8,.35f,a(1,2,4,2,4,0,0,0),noHigh,cf);
        add(Items.titanium,5,.35f,a(1,1,3,1,2,4,8,14),common,tf);
        add(Items.silicon,6,.18f,a(1,2,5,2,4,7,12,22),tech,tf);
        add(Items.metaglass,4,.12f,a(1,2,4,1,3,5,8,14),tech,tf);
        add(Items.thorium,4,.06f,a(0,0,1,0,1,2,6,12),rare,a(0,0,.1f,0,.1f,.15f,.2f,.25f));
        add(Items.plastanium,3,.05f,a(0,0,1,0,1,2,5,10),rare,a(0,0,.1f,0,.1f,.15f,.15f,.2f));
        add(Items.phaseFabric,2,.025f,a(0,0,0,0,0,1,3,7),end,a(0,0,0,0,0,.1f,.1f,.15f));
        add(Items.surgeAlloy,2,.02f,a(0,0,0,0,0,1,3,6),end,a(0,0,0,0,0,.1f,.1f,.15f));
    }
}
