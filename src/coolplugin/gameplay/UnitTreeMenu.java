package coolplugin.gameplay;

import arc.struct.ObjectMap;
import arc.util.Scaling;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import static mindustry.ui.builder.UiBuilder.*;

/** Horizontal faction-style columns; tokens and pending factions are server-owned. */
public final class UnitTreeMenu {
    private record Pending(FactionDef faction,long token){}
    private final ObjectMap<String,Pending> pending=new ObjectMap<>();
    private final PlayerUnitSystem units;
    private int menu=-1;
    private long token;
    public UnitTreeMenu(PlayerUnitSystem units){this.units=units;}
    public void clear(){pending.clear();}
    public void open(Player player,FactionDef faction){
        if(menu<0)menu=Menus.registerMenuBuilder(this::handle);
        long nonce=++token;pending.put(player.uuid(),new Pending(faction,nonce));
        UiSpriteSync.ensureSent(player);
        Call.menuBuilder(player.con,menu,nonce,null,true,true,false,build(player,faction));
    }
    private void handle(Player player,MenuResult result){
        Pending state=pending.get(player.uuid());
        if(state==null||result==null||result.token!=state.token)return;
        if(result.wasCancelled()){pending.remove(player.uuid());units.cancelTreeSelection(player);return;}
        String action=result.result;
        UnitTree tree=action!=null&&action.startsWith("tree:")?UnitTree.byId(action):null;
        if(tree==null||!tree.available||!units.canSelectTree(player,state.faction,tree))return;
        pending.remove(player.uuid());units.onTreeChosen(player,state.faction,tree);
    }
    public NodeBuilder<?> build(Player player,FactionDef faction){
        var root=table().margin(6f);
        root.add(table().background("pane").margin(14f).add(label("[accent]Choose your unit branch[]")).width(FactionJoinMenu.TOTAL_WIDTH)).row();
        var columns=table();
        for(UnitTree tree:UnitTree.values()){
            boolean enabled=tree.available&&units.canSelectTree(player,faction,tree);
            var col=buttonTable().style("emptyi").margin(0f).width(FactionJoinMenu.COL_WIDTH).height(FactionJoinMenu.COL_HEIGHT).padRight(FactionJoinMenu.COL_GAP);
            if(enabled)col.clicked(tree.savedId());else col.disabled(true);
            var layers=stack().width(FactionJoinMenu.COL_WIDTH).height(FactionJoinMenu.COL_HEIGHT);
            String frame=enabled?"unit-branch-frame-"+tree.id():FactionDef.MALIS.colBgRegion;
            layers.add(image(UiSpriteSync.resolveMenuDrawable(frame)).placeholder("button").scaling(Scaling.stretch));
            var contents=table().margin(0f).align("top");
            contents.add(label((enabled?"[#"+tree.accentHex()+"]":"[#9696a4]")+tree.title+"[]").labelAlign("center").padTop(36f).padBottom(12f)).row();
            contents.add(image("whiteui").color(enabled?tree.accentHex()+"88":"525260cc").width(200f).height(2f)).row();
            var art=image(UiSpriteSync.resolveMenuDrawable(enabled?tree.art():tree.art()+"-disabled")).placeholder("unit-"+tree.units[0]+"-ui").scaling(Scaling.fit).width(212f).height(244f);
            if(!enabled)art.color("b4b4c0e8");
            contents.add(art).row();
            contents.add(label(tree.available?(enabled?"[lightgray]Select branch[]":"[gray]Research to unlock[]"):"[gray]Unavailable[]").labelAlign("center").padTop(14f)).row();
            layers.add(contents);col.add(layers);columns.add(col);
        }
        root.add(pane().style("defaultPane").add(columns).width(FactionJoinMenu.TOTAL_WIDTH).height(FactionJoinMenu.COL_HEIGHT+24f));
        return root;
    }
}
