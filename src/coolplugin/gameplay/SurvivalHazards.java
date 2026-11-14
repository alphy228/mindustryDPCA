package coolplugin.gameplay;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.logic.LMarkerControl;
import mindustry.type.StatusEffect;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.BaseShield.BaseShieldBuild;

/** Simulation-time escalation; no wall-clock timers survive a map reset. */
public final class SurvivalHazards {
    public SurvivalSettings settings;
    private boolean collapsed;
    private int marker=0x5c000000;
    private final ObjectMap<String,NukeClock> clocks=new ObjectMap<>();
    private final Seq<Impact> impacts=new Seq<>();
    private static final class NukeClock{float next;int count;NukeClock(float next){this.next=next;}}
    private record Impact(float x,float y,float due,int marker){}
    public SurvivalHazards(SurvivalSettings settings){this.settings=settings;}
    public void reset(){for(Impact i:impacts){Call.removeMarker(i.marker);Call.removeMarker(i.marker+1);Call.removeMarker(i.marker+2);}impacts.clear();clocks.clear();collapsed=false;}
    public static boolean sheltered(PlayerUnitSystem.Survivor player){
        if(!player.alive()||player.position()==null)return false;
        for(Building b:Groups.build)if(b.block==Blocks.largeShieldProjector&&b instanceof BaseShieldBuild shield&&
            b.team==player.unit().team&&!shield.broken&&b.efficiency>0&&player.position().within(b,shield.radius()))return true;
        return false;
    }
    public static float outsideHpFraction(Seq<PlayerUnitSystem.Survivor> players){
        double total=0,outside=0;
        for(var p:players)if(p.connected()&&p.alive()){
            double hp=Math.max(0,p.unit().health)*Math.max(0,p.unit().healthMultiplier)*Vars.state.rules.unitHealth(p.unit().team);
            total+=hp;if(!sheltered(p))outside+=hp;
        }
        return total<=0?0:(float)(outside/total);
    }
    /** E[increment] = 2 * exposed HP fraction, with exactly +1 at 50%. */
    public static int waveIncrement(float exposed,float roll){
        exposed=Mathf.clamp(exposed);
        if(exposed<.5f)return roll<1f-2f*exposed?0:1;
        return roll<2f*exposed-1f?2:1;
    }
    public int stacks(float elapsed){return elapsed<settings.radiation?0:elapsed<settings.radiationSecond?1:elapsed<settings.radiationThird?2:3;}
    public float cooldown(int launched){return Math.max(settings.nukeMinimum,settings.nukeCooldown-settings.nukeDecrease*launched);}
    public void update(float elapsed,Seq<PlayerUnitSystem.Survivor> players){update(elapsed,0f,players);}
    public void update(float elapsed,float seconds,Seq<PlayerUnitSystem.Survivor> players){
        if(elapsed>=settings.collapse){
            if(!collapsed){collapsed=true;Call.sendMessage("[scarlet]50-minute collapse: barriers destroyed. Death is now permanent.[]");}
            Seq<Building> remove=new Seq<>();
            for(Building b:Groups.build)if(b.block==Blocks.largeShieldProjector||b.block==Blocks.shieldProjector||
                b instanceof ConstructBuild c&&(c.previous==Blocks.largeShieldProjector||c.previous==Blocks.shieldProjector))remove.add(b);
            for(Building b:remove)if(b.isValid())b.tile.setNet(Blocks.air);
        }
        String[] names={"dp-radiation-sickness","dp-radiation-sickness-2","dp-radiation-sickness-3"};
        int stacks=stacks(elapsed);
        for(var p:players)if(p.alive()){
            for(int i=0;i<names.length;i++){
                StatusEffect effect=Vars.content.statusEffect(names[i]);
                if(effect!=null){effect.damage=settings.radiationDps/60f;if(i<stacks)p.unit().apply(effect,120f);}
            }
            if(elapsed>=settings.burning)p.unit().apply(StatusEffects.burning,120f);
            // Payload units do not receive native Unit.update/status ticks.
            if(!p.unit().isAdded()&&seconds>0){
                float dps=stacks*settings.radiationDps+(elapsed>=settings.burning?StatusEffects.burning.damage*60f:0);
                if(dps>0)p.unit().damagePierce(dps*seconds);
            }
        }
        for(int i=impacts.size-1;i>=0;i--){
            Impact impact=impacts.get(i);
            if(elapsed>=impact.due){
                Call.removeMarker(impact.marker);Call.removeMarker(impact.marker+1);Call.removeMarker(impact.marker+2);
                Call.effect(Fx.reactorExplosion,impact.x,impact.y,0,Color.white);
                Damage.damage(null,impact.x,impact.y,settings.nukeRadiusTiles*Vars.tilesize,settings.nukeDamage,true,true);
                impacts.remove(i);
            }else{
                Call.updateMarkerText(impact.marker+1,LMarkerControl.flushText,false,"[scarlet]NUKE "+Mathf.ceil(impact.due-elapsed)+"[]");
                float height=160f*Mathf.clamp((impact.due-elapsed)/settings.warning);
                Call.updateMarker(impact.marker+2,LMarkerControl.pos,impact.x,impact.y+height,0);
            }
        }
        if(elapsed<settings.nukes)return;
        for(var p:players)if(p.connected()&&p.alive()){
            NukeClock clock=clocks.get(p.uuid());
            if(clock==null){clock=new NukeClock(elapsed);clocks.put(p.uuid(),clock);}
            if(elapsed<clock.next)continue;
            float x=p.position().getX(),y=p.position().getY();
            if(!Mathf.chance(settings.nukeDirectChance)){
                float angle=Mathf.random(360f),radius=(float)Math.sqrt(Mathf.random())*settings.nukeScatterTiles*Vars.tilesize;
                x=Mathf.clamp(x+Mathf.cosDeg(angle)*radius,0,Vars.world.unitWidth()-1);
                y=Mathf.clamp(y+Mathf.sinDeg(angle)*radius,0,Vars.world.unitHeight()-1);
            }
            int id=marker;marker+=3;
            ShapeMarker warning=new ShapeMarker();warning.pos.set(x,y);warning.radius=settings.nukeRadiusTiles*Vars.tilesize;warning.color=Color.scarlet.cpy();warning.fill=false;warning.sides=48;
            Call.createMarker(id,warning);
            Call.createMarker(id+1,new TextMarker("[scarlet]NUKE "+Mathf.ceil(settings.warning)+"[]",x,y,0.7f,WorldLabel.flagOutline));
            TextureMarker falling=new TextureMarker("item-thorium",x,y+160f,12f,12f);
            Call.createMarker(id+2,falling);
            Call.effect(Fx.dropItem,x,y,0,Items.thorium.color,Items.thorium);
            impacts.add(new Impact(x,y,elapsed+settings.warning,id));
            clock.next=elapsed+cooldown(clock.count++);
        }
    }
    public int pendingNukes(){return impacts.size;}
    public int personalNukes(String uuid){NukeClock c=clocks.get(uuid);return c==null?0:c.count;}
}
