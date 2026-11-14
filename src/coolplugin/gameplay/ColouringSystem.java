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
    // Unit IDs change on the app frame after native payload entry. Track object identity.
    private final Seq<Unit> seen=new Seq<>();
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
        Events.on(BlockDestroyEvent.class,e->{
            // Close immediately, including while simulation updates are paused.
            Seq<Player> affected=new Seq<>();
            for(Session s:sessions.values())if(s.station.tile==e.tile)affected.add(s.player);
            for(Player player:affected){
                sessions.remove(player.uuid());
                if(player.con!=null&&player.con.isConnected())Call.hideMenuBuilder(player.con,menu);
            }
        });
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
            if(seen.contains(up.unit,true))continue;
            Player owner=null;
            for(Player p:Groups.player)if(PlayerUnitSystem.ownedUnit(p)==up.unit || p.unit()==up.unit){owner=p;break;}
            if(owner==null || owner.team()!=b.team){
                lock(b,false);
                if(b instanceof PayloadBlockBuild<?> pb)pb.moveOutPayload();
                continue;
            }
            seen.add(up.unit);
            open(owner,b,up.unit);
        }
        // Forget ejected units so they can enter a station again.
        Seq<Unit> held=new Seq<>();
        for(Building b:Groups.build)if(isStation(b)&&payload(b)!=null)held.add(payload(b).unit);
        for(int i=seen.size-1;i>=0;i--)if(!held.contains(seen.get(i),true))seen.remove(i);
    }
    private static void lock(Building b,boolean locked){
        boolean changed=b.enabled==locked;
        b.enabled=!locked;
        if(b instanceof PayloadConveyorBuild c && locked && c.item!=null){c.progress=0f;c.item.set(b.x,b.y,c.item.rotation());}
        if(changed){sync(b);Time.run(2f,()->{if(b.isValid())sync(b);});}
    }
    public void open(Player player,Building station,Unit unit){
        if(player==null||player.con==null||!player.con.isConnected()||!isStation(station)||station.team!=player.team()||payload(station)==null||payload(station).unit!=unit)return;
        Session existing=sessions.get(player.uuid());
        if(existing!=null && existing.station==station && existing.unit==unit){lock(station,true);return;}
        if(existing!=null)release(player);
        Session s=new Session();s.player=player;s.station=station;s.unit=unit;s.token=++tokens;
        var available=available(player,tier(station)).select(pal->pal.tier()<=tier(station));if(!available.isEmpty())s.selected=available.first().id();
        sessions.put(player.uuid(),s);lock(station,true);show(s);
    }
    public Seq<ColouringAssets.Palette> available(Player p,int stationTier){
        var eq=items.getEquipment(p);
        return ColouringAssets.palettes.select(pal->items.countOwnedTotal(eq,pal.canId())>0);
    }
    private Seq<ColouringAssets.Palette> visible(Player p,int tier){
        var owned=available(p,tier);
        return ColouringAssets.palettes.select(pal->{
            var item=items.getCatalog().findItem(pal.canId());
            return owned.contains(pal) || (item!=null&&!item.hidden);
        });
    }
    private String previewKey(UnitType type){return "colour-preview-"+type.name;}
    private void show(Session s){
        s.token=++tokens; // reject late results from a replaced menu
        UiSpriteSync.ensureSent(s.player);
        UnitType preview=selectedType(s.unit.type,s.selected);
        if(preview!=null)streamPreview(s.player,preview);
        Call.menuBuilder(s.player.con,menu,s.token,null,true,true,false,buildMenu(s.player,s.unit.type,tier(s.station),s.selected));
    }
    private UnitType selectedType(UnitType original,String palette){
        var p=ColouringAssets.palette(palette);
        return p==null?original:Vars.content.unit(ColouringAssets.variant(original.name,p));
    }
    private Fi previewFile(UnitType type){
        String name=type.name.startsWith("dp-paint-")?type.name.substring(3):"base-"+type.name;
        return data.child("colouring-cache/previews/"+name+".png");
    }
    private void streamPreview(Player player,UnitType type){
        Fi png=previewFile(type);
        if(!png.exists()||Vars.netServer==null)return;
        try{Vars.netServer.sendTexture(player.con,previewKey(type),png.readBytes());}
        catch(Exception e){arc.util.Log.warn("[Colouring] Preview stream failed for @: @",type.name,e.toString());}
    }
    public TableBuilder buildMenu(Player player,UnitType unit,int stationTier,String selected){
        TableBuilder root=table().width(760f).margin(10f);
        root.add(label(stationTier==2?"Colouring Station II":"Colouring Station").style("outlineLabel")).row();
        TableBuilder body=table();
        UnitType preview=selectedType(unit,selected);
        String base=ColouringAssets.baseName(unit.name);
        TableBuilder left=table().width(330f);
        // Always draw the stream, even if no cans exist or selection equals current unit.
        left.add(image("net-"+previewKey(preview==null?unit:preview)).placeholder("dp-colour-preview-"+(preview==null?unit:preview).name)
            .scaling(Scaling.fit).size(280f).padBottom(12f)).row();
        left.add(label(unit.localizedName)).row();
        var chosen=ColouringAssets.palette(selected);
        boolean canApply=chosen!=null&&chosen.tier()<=stationTier&&preview!=null&&preview!=unit&&available(player,stationTier).contains(chosen);
        if(chosen!=null)left.add(buttonTable().style("defaultt").clicked("apply").disabled(!canApply).margin(8f)
            .add(label("Apply · 1 paint can")).padTop(12f));
        body.add(left.padRight(20f));
        var choices=visible(player,stationTier);
        StackBuilder hex=stack().width(380f).height(420f);
        hex.add(image(UiSpriteSync.resolveMenuDrawable("shop-inv-hex-workbench")).scaling(Scaling.stretch));
        TableBuilder right=table().margin(30f).align("top");
        right.add(label("[accent]Paints[]").padTop(14f).padBottom(12f)).row();
        if(choices.isEmpty()){
            right.add(label("You dont have any paint cans on you").wrap().width(290f).padTop(90f));
        }else{
            TableBuilder grid=table();int col=0;
            for(var palette:choices){
                boolean unavailable=!available(player,stationTier).contains(palette);
                boolean locked=palette.tier()>stationTier,active=palette.id().equals(selected)&&!locked&&!unavailable;
                TableBuilder tile=table().width(140f).height(locked||unavailable?98f:76f);
                ButtonTableBuilder button=buttonTable().style("defaultt").checked(active).disabled(locked||unavailable)
                    .clicked("palette:"+palette.id()).size(58f).margin(6f);
                button.add(image(UiSpriteSync.resolveMenuDrawable("paint-can-"+palette.id()))
                    .placeholder("item-silicon-ui").color(locked||unavailable?"666670ff":"ffffffff").scaling(Scaling.fit).size(40f));
                StackBuilder framed=stack().size(72f);
                if(active)framed.add(image(UiSpriteSync.resolveMenuDrawable(PlayerInventoryDrawer.GLOW_SPRITE))
                    .color(mindustry.graphics.Pal.accent.toString()).scaling(Scaling.stretch));
                framed.add(table().add(button));
                tile.add(framed).row();
                if(locked)tile.add(table().add(label("Requires").style("defaultLabel").color("9999aaff")).add(image(UiSpriteSync.resolveMenuDrawable("colouring-station-2-ui")).scaling(Scaling.fit).size(16f).padLeft(3f)));
                else if(unavailable)tile.add(label("No paint cans owned").wrap().width(135f).labelAlign("center").color("9999aaff"));
                grid.add(tile);
                if(++col%2==0)grid.row();
            }
            right.add(pane().add(grid).width(306f).height(310f));
        }
        hex.add(right);body.add(hex);root.add(body.padTop(12f));
        return root;
    }
    private void result(Player player,MenuResult result){
        if(player==null)return;Session s=sessions.get(player.uuid());
        if(s==null||result==null||result.token!=s.token)return;
        if(result.wasCancelled()){release(player);return;}
        if(!s.station.isValid()||payload(s.station)==null||payload(s.station).unit!=s.unit||s.station.team!=player.team()){release(player);return;}
        if(result.result.startsWith("palette:")){
            String id=result.result.substring(8);
            if(available(player,tier(s.station)).contains(p->p.id().equals(id)&&p.tier()<=tier(s.station)))s.selected=id;
        }else if(result.is("apply")&&apply(player,s.station,s.unit,s.selected)){
            // Empty selection means the ACTUAL held unit, not the next owned paint.
            s.selected="";
        }
        if(!s.selected.isEmpty()&&!available(player,tier(s.station)).contains(pal->pal.id().equals(s.selected)&&pal.tier()<=tier(s.station)))s.selected="";
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
        // Flying and stepping units can leave on top. dump() checks native terrain/collision
        // rules; only units that cannot stand here need an adjacent fallback.
        up.set(b.x,b.y,up.rotation());
        boolean dumped=up.dump();
        for(int i=0;i<8&&!dumped;i++){
            double angle=i*Math.PI/4;
            up.set(b.x+(float)Math.cos(angle)*edge,b.y+(float)Math.sin(angle)*edge,up.rotation());dumped=up.dump();
        }
        if(!dumped){b.handlePayload(b,up);seen.add(up.unit);
            if(player.con!=null&&player.con.isConnected())player.sendMessage("[lightgray]Exit blocked. Clear space beside the station and use /exit.[]");}
        sync(b);return true;
    }
    private static void sync(Building b){if(b!=null&&b.isValid()&&Vars.net!=null&&Vars.net.server())NetServer.syncBuilding(b);}
}
