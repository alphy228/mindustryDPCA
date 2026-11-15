package coolplugin.techtree;

import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import coolplugin.gameplay.ShopConfigIO;
import coolplugin.gameplay.ShopItemDef;
import coolplugin.gameplay.UnitTree;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.TechTree;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.meta.BuildVisibility;

/** Mixed native industry, equipment, resource and T1-branch progression.
 * Existing node objects retain their IDs, prices, flags and special conditions.
 */
public final class MixedTechTree {
    private MixedTechTree(){}
    public static boolean usable(Block b){
        return b!=null&&b.placeablePlayer&&b.buildVisibility==BuildVisibility.shown&&b.requirements.length>0&&
            !(b instanceof CoreBlock)&&b!=Blocks.advancedLaunchPad&&!ResearchPolicy.isErekirContent(b);
    }
    private static void flatten(TechNodeDef node,ObjectMap<String,TechNodeDef> out){
        out.put(node.id,node);for(var child:node.children)flatten(child,out);
    }
    private static TechNodeDef make(String id,String type,String content,UnlockableContent entry){
        TechNodeDef node=new TechNodeDef();node.id=id;node.contentType=type;node.contentName=content;node.content=entry;
        if(entry!=null){
            TechTree.TechNode nativeNode=TechTree.all.find(n->n.content==entry);
            node.costs=nativeNode!=null?ItemStack.copy(nativeNode.requirements):ItemStack.copy(entry.researchRequirements());
        }
        return node;
    }
    private static TechNodeDef take(ObjectMap<String,TechNodeDef> old,String id,String type,UnlockableContent content){
        TechNodeDef node=old.get(id);
        if(node==null)for(var saved:old.values())if(type.equals(saved.contentType)&&id.equals(saved.contentName)){node=saved;break;}
        if(node==null)node=make(id,type,id,content);
        node.contentType=type;node.contentName=id;node.content=content;return node;
    }
    public static TechNodeDef remake(TechNodeDef previous,ShopConfigIO.Catalog catalog){
        ObjectMap<String,TechNodeDef> old=new ObjectMap<>(),seed=new ObjectMap<>(),nodes=new ObjectMap<>();
        flatten(DefaultSerpuloPreThoriumTree.createDefaultRoot(),seed);flatten(previous,old);
        for(var entry:seed)if(!old.containsKey(entry.key))old.put(entry.key,entry.value);
        for(var node:old.values()){node.children.clear();node.parent=null;}
        TechNodeDef root=take(old,"dagger","unit",mindustry.content.UnitTypes.dagger);
        root.alwaysUnlocked=true;root.costs=ItemStack.empty;root.direction=TechNodeDef.DIR_DOWN;nodes.put(root.id,root);
        for(Block b:Vars.content.blocks())if(usable(b))nodes.put(b.name,take(old,b.name,"block",b));
        // DataPatch content may become resolvable only on world load.
        for(String name:new String[]{"dp-copper-container","dp-workbench","dp-bazaar","dp-cursed-altar","dp-colouring-station","dp-colouring-station-2"})
            if(!nodes.containsKey(name)){
                TechNodeDef node=take(old,name,"block",Vars.content.block(name));
                if(!old.containsKey(name)&&name.equals("dp-colouring-station"))node.costs=ItemStack.with(Items.copper,300,Items.silicon,200);
                if(!old.containsKey(name)&&name.equals("dp-colouring-station-2"))node.costs=ItemStack.with(Items.silicon,800,Items.titanium,500);
                nodes.put(name,node);
            }
        for(String name:new String[]{"dp-colouring-station","dp-colouring-station-2"}){
            TechNodeDef node=nodes.get(name);
            if(node!=null&&!old.containsKey(name))node.costs=name.endsWith("-2")?ItemStack.with(Items.silicon,800,Items.titanium,500):ItemStack.with(Items.copper,300,Items.silicon,200);
        }
        for(UnitTree branch:UnitTree.values())if(branch.available&&branch!=UnitTree.MECH){
            String name=branch.units[0];TechNodeDef node=take(old,name,"unit",branch.starter());
            if(!old.containsKey(name))node.costs=ItemStack.with(Items.copper,200,Items.lead,150);
            nodes.put(node.id,node);
        }
        // Preserve existing item/plugin nodes exactly, including custom unlock conditions.
        for(var node:old.values())if("item".equals(node.contentType))nodes.put(node.id,node);
        for(ShopItemDef item:catalog.items){
            String id="shop-"+item.id;TechNodeDef node=old.get(id);
            if(node==null){
                node=make(id,"shop-item",item.id,null);Seq<ItemStack> price=new Seq<>();
                for(var cost:item.resourceCosts){Item resource=cost.resolveItem();if(resource!=null&&cost.amount()>0)price.add(new ItemStack(resource,cost.amount()));}
                node.costs=price.isEmpty()?ItemStack.with(Items.copper,150,Items.silicon,50):price.toArray(ItemStack.class);
            }
            node.contentType="shop-item";node.contentName=item.id;
            node.displayName=item.name;node.description=item.description;node.icon=item.uiDrawable();nodes.put(node.id,node);
        }
        // Existing explicit shop research IDs win over generated aliases.
        for(var saved:old.values())if("shop-item".equals(saved.contentType)&&catalog.findItem(saved.contentName)!=null){
            TechNodeDef alias=nodes.get("shop-"+saved.contentName);
            if(alias!=saved)nodes.remove("shop-"+saved.contentName);
            nodes.put(saved.id,saved);
        }
        Seq<TechNodeDef> ordered=nodes.values().toSeq();ordered.sort((a,b)->a.id.compareTo(b.id));
        for(var node:ordered)if(node!=root){
            String parent=parentFor(node,nodes,catalog);TechNodeDef at=nodes.get(parent,root);
            // Guard against malformed/modded native research graphs.
            ObjectSet<TechNodeDef> seen=new ObjectSet<>();TechNodeDef check=at;
            while(check!=null&&seen.add(check)){if(check==node){at=root;break;}check=check.parent;}
            node.parent=at;at.children.add(node);node.direction=TechNodeDef.DIR_UP;
        }
        // Four readable mixed spines; descendants retain native prerequisite chains.
        for(var child:root.children)child.direction=switch(child.id){
            case "conveyor","mechanical-drill","duo","copper-wall"->TechNodeDef.DIR_UP;
            case "combustion-generator","mechanical-pump"->TechNodeDef.DIR_LEFT;
            default->TechNodeDef.DIR_RIGHT;
        };
        ResearchPolicy.apply(root);
        return root;
    }
    /** Add content registered after plugin initialization without moving existing nodes. */
    public static boolean expand(TechNodeDef root,ShopConfigIO.Catalog catalog){
        ObjectMap<String,TechNodeDef> nodes=new ObjectMap<>();flatten(root,nodes);
        Seq<TechNodeDef> added=new Seq<>();
        for(Block block:Vars.content.blocks())if(usable(block)&&!nodes.containsKey(block.name)){
            TechNodeDef node=make(block.name,"block",block.name,block);nodes.put(node.id,node);added.add(node);
        }
        for(var node:added){TechNodeDef parent=nodes.get(parentFor(node,nodes,catalog),root);if(parent==node)parent=root;node.parent=parent;parent.children.add(node);node.direction=TechNodeDef.DIR_UP;}
        return !added.isEmpty();
    }
    private static String parentFor(TechNodeDef node,ObjectMap<String,TechNodeDef> nodes,ShopConfigIO.Catalog catalog){
        String id=node.id;
        if("shop-item".equals(node.contentType)){
            var item=catalog.findItem(node.contentName);return item==null?"dp-workbench":item.tier.blockName;
        }
        if("unit".equals(node.contentType))return switch(node.contentName){
            case "flare","mono"->"air-factory";case "nova"->"ground-factory";default->"dp-bazaar";
        };
        if("item".equals(node.contentType))return switch(node.contentName){
            case "graphite"->"graphite-press";case "silicon"->"silicon-smelter";case "metaglass"->"kiln";
            case "titanium"->"pneumatic-drill";case "thorium","beryllium","tungsten"->"laser-drill";
            case "plastanium"->"plastanium-compressor";case "phase-fabric"->"phase-weaver";case "surge-alloy"->"surge-smelter";
            default->"mechanical-drill";
        };
        String special=switch(id){
            case "dp-copper-container"->"conveyor";case "dp-workbench"->"dp-copper-container";
            case "dp-bazaar"->"silicon-smelter";case "dp-cursed-altar"->"phase-weaver";
            case "dp-colouring-station"->"dp-workbench";case "dp-colouring-station-2"->"dp-colouring-station";
            case "landing-pad"->"launch-pad";case "launch-pad"->"titanium-conveyor";default->null;
        };
        if(special!=null)return special;
        if(node.content!=null){
            TechTree.TechNode nativeNode=TechTree.all.find(n->n.content==node.content);
            ObjectSet<TechTree.TechNode> seen=new ObjectSet<>();
            for(var ancestor=nativeNode==null?null:nativeNode.parent;ancestor!=null&&seen.add(ancestor);ancestor=ancestor.parent)
                if(ancestor.content instanceof Block&&nodes.containsKey(ancestor.content.name)&&!ancestor.content.name.equals(id))return ancestor.content.name;
        }
        return "dagger";
    }
}
