package coolplugin.gameplay;

import arc.Events;
import arc.struct.IntMap;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.ui.Menus;
import mindustry.world.blocks.campaign.LandingPad;
import mindustry.world.blocks.campaign.LandingPad.LandingPadBuild;
import static mindustry.ui.builder.UiBuilder.*;

/** Native landing animation, item selection and unloading, with account/faction orbital payment. */
public final class LandingPadSystem {
    private record Owner(long account,String faction,mindustry.game.Team team){}
    private final IntMap<Owner> owners=new IntMap<>(),pending=new IntMap<>();
    private final PlayerDatabase database;
    private final PlayerSessionManager sessions;
    private int menu;
    private final IntMap<arc.struct.IntSeq> decorations=new IntMap<>();
    private final IntMap<String> decorationNames=new IntMap<>();
    private final arc.struct.IntSet usedMarkers=new arc.struct.IntSet();
    private float decorationTimer;

    public LandingPadSystem(PlayerDatabase database,PlayerSessionManager sessions){this.database=database;this.sessions=sessions;}
    private Owner owner(Player player){return new Owner(sessions.getOrInitSession(player).id,sessions.getActiveFactionId(player.uuid()),player.team());}
    public void start(){
        LandingPad block=(LandingPad)Blocks.landingPad;
        block.buildVisibility=mindustry.world.meta.BuildVisibility.shown;
        block.description="Accepts item shippings from orbit";
        Events.run(Trigger.update,()->{
            if(Vars.state==null||!Vars.state.isGame()||Vars.state.isPaused())return;
            decorationTimer+=arc.util.Time.delta;if(decorationTimer<60f)return;decorationTimer=0f;
            var stale=new arc.struct.IntSeq();
            for(var entry:owners){
                var tile=Vars.world.tile(entry.key);
                if(tile==null||!(tile.build instanceof LandingPadBuild pad)||!pad.isValid()||pad.team!=entry.value.team)stale.add(entry.key);
                else ensureDecoration(pad,entry.value);
            }
            for(int i=0;i<stale.size;i++)removeOwner(stale.get(i));
        });
        // Suppress the vanilla custom-game infinite item source. Do not change its
        // client content type: clients retain the native landing animation and item dumping.
        block.buildType=()->block.new LandingPadBuild(){
            @Override public boolean accessible(){return true;}
            @Override public void updateTile(){
                Item selected=config;
                config=null; // super handles arrival/cooldown/dumping, never starts a free delivery
                try{super.updateTile();}finally{config=selected;}
                requestLanding(this);
            }
        };
        menu=Menus.registerMenuBuilder((player,result)->{
            if(player==null||result==null||result.wasCancelled())return;
            var tile=Vars.world.tile((int)result.token);
            if(tile==null||!(tile.build instanceof LandingPadBuild pad)||!canUse(player,pad))return;
            Item item=Vars.content.item(result.result);
            if(item!=null&&!item.hidden)pad.configure(item);
        });
        Events.on(TapEvent.class,e->{
            if(e.player==null||e.tile==null||!(e.tile.build instanceof LandingPadBuild pad)||!canUse(e.player,pad))return;
            var grid=table();int count=0;
            for(Item item:Vars.content.items())if(!item.hidden){
                grid.add(buttonTable().style("defaultt").clicked(item.name).size(48f)
                    .add(image("item-"+item.name+"-ui").size(32f)));
                if(++count%6==0)grid.row();
            }
            Call.menuBuilder(e.player.con,menu,pad.pos(),"Landing pad · Request item",true,true,false,
                table().add(pane().add(grid).height(300f)));
        });
        if(Vars.netServer!=null&&Vars.netServer.admins!=null)Vars.netServer.admins.addActionFilter(action->{
            if(action!=null&&action.type==mindustry.net.Administration.ActionType.placeBlock&&action.block==block&&action.player!=null&&action.tile!=null)
                pending.put(action.tile.pos(),owner(action.player));
            if(action!=null&&action.tile!=null&&action.tile.build instanceof LandingPadBuild pad&&action.player!=null
                &&action.type==mindustry.net.Administration.ActionType.configure)return canUse(action.player,pad);
            return true;
        });
        Events.on(BuildSelectEvent.class,e->{
            if(!e.breaking&&e.tile!=null&&e.builder!=null&&e.builder.getPlayer()!=null
                &&e.tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild c&&c.current==block)
                pending.put(e.tile.pos(),owner(e.builder.getPlayer()));
        });
        Events.on(BlockBuildEndEvent.class,e->{
            if(e.tile==null)return;
            if(e.breaking){removeOwner(e.tile.pos());pending.remove(e.tile.pos());return;}
            if(e.tile.build instanceof LandingPadBuild){
                Owner record=e.unit!=null&&e.unit.getPlayer()!=null?owner(e.unit.getPlayer()):pending.get(e.tile.pos());
                pending.remove(e.tile.pos());if(record!=null){owners.put(e.tile.pos(),record);ensureDecoration((LandingPadBuild)e.tile.build,record);}
            }
        });
        Events.on(BlockDestroyEvent.class,e->{if(e.tile!=null)removeOwner(e.tile.pos());});
        Events.on(WorldLoadEvent.class,e->{
            var positions=new arc.struct.IntSeq();for(var entry:decorations)positions.add(entry.key);
            for(int i=0;i<positions.size;i++)removeDecoration(positions.get(i));
            owners.clear();pending.clear();decorations.clear();decorationNames.clear();usedMarkers.clear();
        });
    }
    private boolean canUse(Player player,LandingPadBuild pad){
        Owner record=owners.get(pad.pos());
        return record!=null&&pad.team==record.team&&player.team()==record.team
            &&sessions.getOrInitSession(player).id==record.account&&sessions.getActiveFactionId(player.uuid()).equals(record.faction);
    }
    public void assignOwner(LandingPadBuild pad,Player player){
        removeOwner(pad.pos());Owner record=owner(player);owners.put(pad.pos(),record);ensureDecoration(pad,record);
    }
    private void removeDecoration(int pos){
        var ids=decorations.remove(pos);decorationNames.remove(pos);if(ids==null)return;
        for(int i=0;i<ids.size;i++){
            if(Vars.state!=null&&Vars.state.markers!=null&&Vars.state.markers.has(ids.get(i)))Call.removeMarker(ids.get(i));
            usedMarkers.remove(ids.get(i));
        }
    }
    private void removeOwner(int pos){removeDecoration(pos);owners.remove(pos);}
    private int markerId(){
        // Above the entire 320-processor item marker pool, separate from Launch pads.
        for(int id=500000;id<510000;id++)if(!usedMarkers.contains(id)&&!Vars.state.markers.has(id)){
            usedMarkers.add(id);return id;
        }
        return -1;
    }
    private void ensureDecoration(LandingPadBuild pad,Owner owner){
        if(Vars.state==null||Vars.state.markers==null)return;
        var account=database.getById(owner.account);
        String name=account==null?"Unknown":account.isRegistered()?account.login:account.lastName;
        if(name==null||name.isBlank())name="Player";
        var faction=FactionDef.byId(owner.faction);String color=faction==null?"ffd37f":faction.accentHex;
        String signature=name+"/"+color;
        var existing=decorations.get(pad.pos());boolean intact=existing!=null&&signature.equals(decorationNames.get(pad.pos()));
        if(existing!=null)for(int i=0;i<existing.size;i++)intact&=Vars.state.markers.has(existing.get(i));
        if(intact)return;
        removeDecoration(pad.pos());var ids=new arc.struct.IntSeq();
        for(var marker:ownerDecoration(pad.x,pad.y,name,color)){
            int id=markerId();
            if(id<0){for(int i=0;i<ids.size;i++){Call.removeMarker(ids.get(i));usedMarkers.remove(ids.get(i));}return;}
            ids.add(id);Call.createMarker(id,marker);
        }
        decorations.put(pad.pos(),ids);decorationNames.put(pad.pos(),signature);
    }
    /** Flat square receiver badge with inward arrows, deliberately not Launch pad's octagon. */
    public static arc.struct.Seq<mindustry.game.MapObjectives.ObjectiveMarker> ownerDecoration(float x,float y,String owner,String hex){
        var result=new arc.struct.Seq<mindustry.game.MapObjectives.ObjectiveMarker>();
        var plate=new mindustry.game.MapObjectives.ShapeMarker(x,y,9f,45f);
        plate.sides=4;plate.fill=true;plate.outline=false;plate.color=arc.graphics.Color.valueOf("252b32");
        plate.control(mindustry.logic.LMarkerControl.drawLayer,mindustry.graphics.Layer.blockOver+.1f,Double.NaN,Double.NaN);result.add(plate);
        var rim=new mindustry.game.MapObjectives.ShapeMarker(x,y,8.5f,45f);
        rim.sides=4;rim.stroke=.65f;rim.outline=false;rim.color=arc.graphics.Color.valueOf(hex);
        rim.control(mindustry.logic.LMarkerControl.drawLayer,mindustry.graphics.Layer.blockOver+.2f,Double.NaN,Double.NaN);result.add(rim);
        for(int side:new int[]{-1,1}){
            var arrow=new mindustry.game.MapObjectives.ShapeMarker(x+side*9f,y,2f,side<0?0f:180f);
            arrow.sides=3;arrow.fill=true;arrow.outline=false;arrow.color=arc.graphics.Color.valueOf(hex);
            arrow.control(mindustry.logic.LMarkerControl.drawLayer,mindustry.graphics.Layer.blockOver+.25f,Double.NaN,Double.NaN);result.add(arrow);
        }
        String clean=arc.util.Strings.stripColors(owner==null?"Player":owner).replace('\n',' ').replace('\r',' ').replace("[","[[");
        String text="[#"+hex+"]"+clean+"'s[]\n[white]landing pad[]";
        float size=Math.min(.42f,4.5f/Math.max(8,clean.length()+2));
        var label=new mindustry.game.MapObjectives.TextMarker(text,x,y,size,(byte)0);
        label.control(mindustry.logic.LMarkerControl.drawLayer,mindustry.graphics.Layer.blockOver+.3f,Double.NaN,Double.NaN);result.add(label);
        return result;
    }

    /** Full vanilla batches only. Debit exactly once, before starting the native arrival. */
    public boolean requestLanding(LandingPadBuild pad){
        Owner record=owners.get(pad.pos());
        if(record==null||pad.team!=record.team||!pad.enabled||pad.config==null||pad.arriving!=null
            ||pad.cooldown>0f||pad.efficiency<=0f||pad.items.total()!=0)return false;
        int amount=pad.block.itemCapacity;
        if(!database.takeOrbitalItem(record.account,record.faction,pad.config.name,amount))return false;
        if(Vars.net!=null&&Vars.net.server())Call.landingPadLanded(pad.tile);
        else pad.handleLanding();
        return true;
    }
}
