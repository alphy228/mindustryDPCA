package coolplugin.gameplay;

import arc.Events;
import arc.struct.ObjectMap;
import arc.util.CommandHandler;
import arc.util.Scaling;
import coolplugin.database.PlayerDatabase.EquipmentData;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import static mindustry.ui.builder.UiBuilder.*;

/** Standalone inventory. Every response is scoped to the latest displayed menu. */
public final class InventoryMenu {
    private static final class Session {
        int selected=-1;
        long token;
        String context="",confirmId="",confirmSnapshot="",message="";
    }
    private final ObjectMap<String,Session> sessions=new ObjectMap<>();
    private final PlayerItemSystem items;
    private final PlayerInventoryDrawer drawer;
    private final int menu;
    private long nextToken=1;
    public InventoryMenu(PlayerItemSystem items){
        this.items=items;drawer=new PlayerInventoryDrawer(items);
        menu=Menus.registerMenuBuilder(this::handleResult);
        Events.on(PlayerLeave.class,e->{if(e.player!=null)sessions.remove(e.player.uuid());});
        Events.on(WorldLoadEvent.class,e->sessions.clear());
    }
    public void registerCommands(CommandHandler handler){
        for(String command:new String[]{"i","inv","inventory"})
            handler.<Player>register(command,"Open your equipment and carry inventory.",(args,player)->open(player));
    }
    public void open(Player player){sessions.put(player.uuid(),new Session());show(player);}
    private void show(Player player){
        var s=sessions.get(player.uuid());if(s==null)return;
        s.context=items.inventoryContext(player);s.token=nextToken++;
        UiSpriteSync.ensureSent(player);
        Call.menuBuilder(player.con,menu,s.token,"Inventory",true,true,false,build(player,s.selected,!s.confirmId.isEmpty(),s.message));
    }
    /** Public for native UI previews and wire-format regression checks. */
    public TableBuilder build(Player player,int selected,boolean confirming,String message){
        var data=items.getEquipment(player);
        var root=table().margin(8f);
        var left=table().align("top").width(300f);
        var controls=table().width(300f);
        ShopItemDef item=selected>=0&&selected<data.equipped.size?items.getCatalog().findItem(data.equipped.get(selected)):null;
        if(item==null){left.add(label("[lightgray]Select an item[]").labelAlign("center")).row();}
        else{
            left.add(label("[#"+item.tier.accentHex+"]"+item.name+"[]").wrap().width(292f).labelAlign("center")).row();
            left.add(image(UiSpriteSync.resolveMenuDrawable(item.uiDrawable())).placeholder("item-"+item.iconItem+"-ui").scaling(Scaling.fit).size(96f).pad(8f)).row();
            left.add(label(item.description).style("monoLabel").color("b8becaff").wrap().width(292f).labelAlign("left")).row();
            if(selected<30&&item.size<data.minimumEquipSize)
                left.add(label("[red]Item too small for slot![]").wrap().width(292f).padTop(5f)).row();
            left.add(label("[gray]Size:[] "+item.size+(selected<30?"  [gray]Equip space:[] "+Math.max(item.size,data.minimumEquipSize):""))).row();
            var stats=table().padTop(8f);
            stat(stats,"Health",item.healthMultBonus*100f,"%");stat(stats,"Damage",item.damageMultBonus*100f,"%");
            stat(stats,"Speed",item.speedMultBonus*100f,"%");stat(stats,"Reload",item.reloadMultBonus*100f,"%");
            stat(stats,"Build speed",item.buildSpeedMultBonus*100f,"%");stat(stats,"Armor",item.armorBonus,"");stat(stats,"Regeneration",item.regenPerSec,"/s");
            left.add(stats).row();
            if(item.attachMode==ShopItemDef.AttachMode.SUBWEAPON){
                left.add(label("[gray]Shot damage:[] "+item.subweaponDamage).style("defaultLabel")).row();
                left.add(label("[gray]Reload:[] "+item.subweaponReload+" ticks").style("defaultLabel")).row();
                left.add(label("[gray]Range:[] "+item.subweaponRange+"  [gray]Aim:[] "+(item.subweaponAutoTarget?"Auto + player":"Player")).style("defaultLabel")).row();
            }
            if(!item.wearerStatuses.isEmpty())left.add(label("[gray]Wearer:[] "+item.wearerStatuses.toString(", ")).style("defaultLabel").wrap().width(292f)).row();
            if(selected>=30)left.add(label("[gray]Carried: no equipped bonuses or firing.[]").style("defaultLabel").wrap().width(292f)).row();
            var buttons=table().padTop(10f);
            buttons.add(buttonTable().style("defaultt").clicked("equip").disabled(selected<30).add(label("Equip")).width(92f).height(32f));
            buttons.add(buttonTable().style("defaultt").clicked("carry").disabled(selected>=30).add(label("Carry")).width(92f).height(32f));
            controls.add(buttons).row();
            controls.add(buttonTable().style("defaultt").color("d45b66ff").clicked("destroy").add(label(confirming?"[white]Are you sure?[]":"[white]Destroy[]")).width(190f).height(32f).padTop(8f)).row();
        }
        if(message!=null&&!message.isEmpty())left.add(label(message).wrap().width(292f).padTop(8f)).row();
        var detailColumn=table().width(324f);
        detailColumn.add(pane().add(left).height(360f).width(324f)).row();
        detailColumn.add(controls);
        root.add(detailColumn.padRight(20f));
        root.add(drawer.buildHexInventory(data,selected,PlayerInventoryDrawer.DrawerTheme.DEFAULT,false,false));
        return root;
    }
    private static void stat(TableBuilder table,String name,float value,String suffix){
        if(value==0f)return;
        table.add(label("[lightgray]"+name+"[]").labelAlign("left").width(150f));
        table.add(label((value>0?"[stat]+":"[scarlet]")+String.format(java.util.Locale.ROOT,"%.1f",value)+suffix+"[]").labelAlign("right")).row();
    }
    public void handleResult(Player player,MenuResult result){
        if(player==null||result==null)return;
        var s=sessions.get(player.uuid());if(s==null||result.token!=s.token)return;
        if(result.wasCancelled()){sessions.remove(player.uuid());return;}
        if(!s.context.equals(items.inventoryContext(player))){sessions.remove(player.uuid());open(player);return;}
        var data=items.getEquipment(player);String action=result.result;
        if(action==null)return;
        if(action.startsWith("inv:")){
            try{s.selected=Integer.parseInt(action.substring(4));}catch(NumberFormatException ignored){s.selected=-1;}
            s.confirmId="";s.message="";
        }else if(s.selected>=0&&s.selected<data.equipped.size&&!data.equipped.get(s.selected).isBlank()){
            String id=data.equipped.get(s.selected);
            if(action.equals("destroy")){
                if(s.confirmId.equals(id)&&s.confirmSnapshot.equals(data.equipped.toString())){
                    items.discardAtSlot(player,s.selected);s.selected=-1;s.confirmId="";s.message="";
                }else{s.confirmId=id;s.confirmSnapshot=data.equipped.toString();}
            }else{
                s.confirmId="";
                if((action.equals("equip")&&s.selected>=30)||(action.equals("carry")&&s.selected<30)){
                    var outcome=items.promoteSlotToActive(player,s.selected);s.message=outcome.message();
                    if(outcome.success())s.selected=-1;
                }
            }
        }else{s.selected=-1;s.confirmId="";}
        show(player);
    }
}
