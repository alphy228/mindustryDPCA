package coolplugin.gameplay;

import arc.Events;
import arc.files.Fi;
import arc.struct.*;
import arc.util.Scaling;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.UnitType;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.*;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import static mindustry.ui.builder.UiBuilder.*;

/** Server-authoritative payload lock, inventory payment and native menu builder UI. */
public final class ColouringSystem {
    private static ColouringSystem instance;
    private final PlayerItemSystem items;
    private final PlayerUnitSystem units;
    private final Fi data;
    private final ObjectMap<String, Session> sessions=new ObjectMap<>();
    private final IntSet seen=new IntSet();
    private int menu=-1;
    private long tokens;
    private static final class Session {
        Player player; Building station; Unit unit; String selected=""; long token;
    }
    public ColouringSystem(Fi data,PlayerItemSystem items,PlayerUnitSystem units){this.data=data;this.items=items;this.units=units;instance=this;}
    public static boolean isStation(Building b){return b!=null && (b.block.name.equals("dp-colouring-station")||b.block.name.equals("dp-colouring-station-2"));}
    public static boolean exit(Player p){return instance!=null && instance.release(p);}
    public static int tier(Building b){return b.block.name.equals("dp-colouring-station-2")?2:1;}
    public void start(){
        menu=Menus.registerMenuBuilder(this::result);
        Events.run(Trigger.update,this::update);
        Events.on(PlayerLeave.class,e->release(e.player));
        Events.on(WorldLoadEvent.class,e->{sessions.clear();seen.clear();});
    }
    public static UnitPayload payload(Building b){
        Payload p=b instanceof PayloadConveyorBuild c?c.item:b.getPayload();
        return p instanceof UnitPayload up?up:null;
    }
    public void update(){
        if(Vars.state==null || !Vars.state.isGame() || Vars.state.isPaused())return;
        Seq<String> stale=new Seq<>();
        for(var entry:sessions){Session s=entry.value;
            if(s.player.con==null||!s.player.con.isConnected()||!s.station.isValid()||payload(s.station)==null||payload(s.station).unit!=s.unit||s.station.team!=s.player.team())stale.add(entry.key);
            else lock(s.station,true);
        }
        for(String uuid:stale){Session s=sessions.remove(uuid);if(s!=null){
            lock(s.station,false);
            if(s.player.con!=null&&s.player.con.isConnected())Call.hideMenuBuilder(s.player.con,menu);
        }}
        for(Building b:Groups.build){
            if(!isStation(b))continue;
            UnitPayload up=payload(b);
            if(up==null){continue;}
            if(seen.contains(up.unit.id))continue;
            Player owner=null;
            for(Player p:Groups.player)if(PlayerUnitSystem.ownedUnit(p)==up.unit || p.unit()==up.unit){owner=p;break;}
            if(owner==null || owner.team()!=b.team){
                lock(b,false);
                if(b instanceof PayloadBlockBuild<?> pb)pb.moveOutPayload();
                continue;
            }
            seen.add(up.unit.id);
            open(owner,b,up.unit);
        }
        // Forget ejected units so they can enter a station again.
        IntSet held=new IntSet();
        for(Building b:Groups.build)if(isStation(b)&&payload(b)!=null)held.add(payload(b).unit.id);
        IntSet remove=new IntSet();
        var sit=seen.iterator();while(sit.hasNext){int id=sit.next();if(!held.contains(id))remove.add(id);}
        var rit=remove.iterator();while(rit.hasNext)seen.remove(rit.next());
    }
    private static void lock(Building b,boolean locked){
        boolean changed=b.enabled==locked;
        b.enabled=!locked;
        if(b instanceof PayloadConveyorBuild c && locked){c.progress=0f;c.item.set(b.x,b.y,c.item.rotation());}
        if(changed){sync(b);Time.run(2f,()->{if(b.isValid())sync(b);});}
    }
    public void open(Player player,Building station,Unit unit){
        if(player==null||player.con==null||!player.con.isConnected()||!isStation(station)||station.team!=player.team()||payload(station)==null||payload(station).unit!=unit)return;
        if(sessions.containsKey(player.uuid()))release(player);
        Session s=new Session();s.player=player;s.station=station;s.unit=unit;s.token=++tokens;
        var available=available(player,tier(station));if(!available.isEmpty())s.selected=available.first().id();
        sessions.put(player.uuid(),s);lock(station,true);show(s);
    }
    public Seq<ColouringAssets.Palette> available(Player p,int stationTier){
        var eq=items.getEquipment(p);
        return ColouringAssets.palettes.select(pal->pal.tier()<=stationTier && items.countOwnedTotal(eq,pal.canId())>0);
    }
    private void show(Session s){
        UiSpriteSync.ensureSent(s.player);
        UnitType preview=selectedType(s.unit.type,s.selected);
        if(preview!=null)streamPreview(s.player,preview);
        Call.menuBuilder(s.player.con,menu,s.token,null,true,true,false,buildMenu(s.player,s.unit.type,tier(s.station),s.selected));
    }
    private UnitType selectedType(UnitType original,String palette){
        var p=ColouringAssets.palette(palette);
        return p==null?original:Vars.content.unit(ColouringAssets.variant(original.name,p));
    }
    private void streamPreview(Player player,UnitType type){
        Fi png=data.child("colouring-cache/previews/"+type.name.replaceFirst("^dp-","")+".png");
        if(!png.exists()||Vars.netServer==null)return;
        try{Vars.netServer.sendTexture(player.con,"colour-preview-live",png.readBytes());}catch(Exception ignored){}
    }
    public TableBuilder buildMenu(Player player,UnitType unit,int stationTier,String selected){
        TableBuilder root=table().width(660f).margin(10f);
        root.add(label(stationTier==2?"Colouring Station II":"Colouring Station").style("outlineLabel")).row();
        var choices=available(player,stationTier);
        if(choices.isEmpty()){
            root.add(label("You dont have any paint cans on you").padTop(24f).padBottom(24f));return root;
        }
        TableBuilder list=table();int col=0;
        for(var palette:choices){
            list.add(buttonTable().style("defaultt").checked(palette.id().equals(selected)).clicked("palette:"+palette.id())
                .margin(5f).width(150f).add(image("net-paint-can-"+palette.id()).size(24f))
                .add(label(palette.id().equals(selected)?"[accent]"+palette.title()+"[]":palette.title()).wrap().width(105f)));
            if(++col%4==0)list.row();
        }
        root.add(pane().add(list).width(640f).height(Math.min(248f,64f*((choices.size+3)/4))).padTop(10f)).row();
        UnitType preview=selectedType(unit,selected);
        String drawable=preview!=null && !preview.name.equals(unit.name)?"net-colour-preview-live":"unit-"+unit.name+"-full";
        root.add(image(drawable).placeholder("unit-"+unit.name+"-ui").scaling(Scaling.fit).size(250f).padTop(14f).padBottom(8f)).row();
        root.add(label(unit.localizedName)).row();
        root.add(buttonTable().style("defaultt").clicked("apply").disabled(preview==null).margin(8f)
            .add(label("Apply · 1 paint can")).padTop(10f));
        return root;
    }
    private void result(Player player,MenuResult result){
        if(player==null)return;Session s=sessions.get(player.uuid());
        if(s==null||result==null||result.token!=s.token)return;
        if(result.wasCancelled()){release(player);return;}
        if(!s.station.isValid()||payload(s.station)==null||payload(s.station).unit!=s.unit||s.station.team!=player.team()){release(player);return;}
        if(result.result.startsWith("palette:")){
            String id=result.result.substring(8);
            if(available(player,tier(s.station)).contains(p->p.id().equals(id)))s.selected=id;
        }else if(result.is("apply"))apply(player,s.station,s.unit,s.selected);
        var choices=available(player,tier(s.station));
        if(!choices.contains(p->p.id().equals(s.selected)))s.selected=choices.isEmpty()?"":choices.first().id();
        show(s);
    }
    public boolean apply(Player p,Building station,Unit unit,String paletteId){
        if(!isStation(station)||payload(station)==null||payload(station).unit!=unit||station.team!=p.team())return false;
        var palette=ColouringAssets.palette(paletteId);
        if(palette==null||palette.tier()>tier(station))return false;
        UnitType type=selectedType(unit.type,paletteId);
        if(type==null||type==unit.type)return false;
        var eq=items.getEquipment(p);
        if(items.countOwnedTotal(eq,palette.canId())<1)return false;
        int slot=eq.equipped.indexOf(palette.canId());if(slot<0)return false;
        eq.equipped.remove(slot);items.saveEquipment(p,eq);
        var oldMounts=unit.mounts();
        float health=unit.health,shield=unit.shield;
        unit.setType(type);unit.setupWeapons(type);
        for(int i=0;i<Math.min(oldMounts.length,unit.mounts().length);i++){
            // Keep reload, warmup, recoil, charge and targeting state; only replace the weapon definition.
            for(var field:oldMounts[i].getClass().getFields()) {
                int modifiers=field.getModifiers();
                if(java.lang.reflect.Modifier.isStatic(modifiers)||java.lang.reflect.Modifier.isFinal(modifiers)||field.getName().equals("weapon"))continue;
                try {field.set(unit.mounts()[i],field.get(oldMounts[i]));}
                catch(IllegalAccessException|IllegalArgumentException ignored){}
            }
        }
        unit.health=health;unit.shield=shield;
        units.persistColour(p,unit);
        sync(station);return true;
    }
    public boolean release(Player player){
        if(player==null)return false;Session s=sessions.remove(player.uuid());if(s==null)return false;
        if(player.con!=null && player.con.isConnected())Call.hideMenuBuilder(player.con,menu);
        Building b=s.station;lock(b,false);
        if(!b.isValid()||payload(b)==null||payload(b).unit!=s.unit)return true;
        UnitPayload up=payload(b);Payload taken=b.takePayload();
        if(taken==null && b instanceof PayloadConveyorBuild c){taken=c.item;c.item=null;}
        if(taken!=up){if(taken!=null)b.handlePayload(b,taken);return true;}
        float edge=b.block.size*Vars.tilesize/2f+s.unit.hitSize/2f+3f;
        boolean dumped=false;
        for(int i=0;i<8&&!dumped;i++){
            double angle=i*Math.PI/4;
            up.set(b.x+(float)Math.cos(angle)*edge,b.y+(float)Math.sin(angle)*edge,up.rotation());dumped=up.dump();
        }
        if(!dumped){b.handlePayload(b,up);seen.add(up.unit.id);
            if(player.con!=null&&player.con.isConnected())player.sendMessage("[lightgray]Exit blocked. Clear space beside the station and use /exit.[]");}
        sync(b);return true;
    }
    private static void sync(Building b){if(b!=null&&b.isValid()&&Vars.net!=null&&Vars.net.server())NetServer.syncBuilding(b);}
}
