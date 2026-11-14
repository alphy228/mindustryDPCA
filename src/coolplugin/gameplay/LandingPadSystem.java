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
    public LandingPadSystem(PlayerDatabase database,PlayerSessionManager sessions){this.database=database;this.sessions=sessions;}
    private Owner owner(Player player){return new Owner(sessions.getOrInitSession(player).id,sessions.getActiveFactionId(player.uuid()),player.team());}
    public void start(){
        LandingPad block=(LandingPad)Blocks.landingPad;
        block.buildVisibility=mindustry.world.meta.BuildVisibility.shown;
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
            if(e.breaking){owners.remove(e.tile.pos());pending.remove(e.tile.pos());return;}
            if(e.tile.build instanceof LandingPadBuild){
                Owner record=e.unit!=null&&e.unit.getPlayer()!=null?owner(e.unit.getPlayer()):pending.get(e.tile.pos());
                pending.remove(e.tile.pos());if(record!=null)owners.put(e.tile.pos(),record);
            }
        });
        Events.on(BlockDestroyEvent.class,e->{if(e.tile!=null)owners.remove(e.tile.pos());});
        Events.on(WorldLoadEvent.class,e->{owners.clear();pending.clear();});
    }
    private boolean canUse(Player player,LandingPadBuild pad){
        Owner record=owners.get(pad.pos());
        return record!=null&&pad.team==record.team&&player.team()==record.team
            &&sessions.getOrInitSession(player).id==record.account&&sessions.getActiveFactionId(player.uuid()).equals(record.faction);
    }
    public void assignOwner(LandingPadBuild pad,Player player){owners.put(pad.pos(),owner(player));}
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
