package coolplugin.techtree;

import arc.struct.ObjectSet;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.Block;

/** Content policy applied on load and after late catalogue/DataPatch registration. */
public final class ResearchPolicy{
    private ResearchPolicy(){}
    public static boolean erekir(TechNodeDef node){
        return isErekirContent(node.content);
    }
    public static boolean isErekirContent(mindustry.ctype.UnlockableContent content){
        if(content==null||content.name.startsWith("dp-"))return false;
        for(var nativeNode:TechTree.all)if(nativeNode.content==content){
            var root=nativeNode;var seen=new ObjectSet<TechTree.TechNode>();
            while(root.parent!=null&&seen.add(root))root=root.parent;
            if(root.content==Blocks.coreBastion)return true;
        }
        return false;
    }
    private static boolean high(Item item){return item==Items.thorium||item==Items.plastanium||item==Items.phaseFabric||item==Items.surgeAlloy;}
    private static boolean advanced(TechNodeDef n){
        if(n.isAdvanced()||"dp-bazaar".equals(n.contentName)||"dp-cursed-altar".equals(n.contentName))return true;
        if(n.content instanceof Item item&&high(item))return true;
        if(n.content==Blocks.plastaniumCompressor||n.content==Blocks.laserDrill)return true;
        if(n.content instanceof Block block)for(var req:block.requirements)if(high(req.item))return true;
        for(var cost:n.costs)if(high(cost.item))return true;
        return false;
    }
    public static boolean apply(TechNodeDef root){return visit(root,false);}
    private static boolean visit(TechNodeDef n,boolean inherited){
        boolean changed=false,orbital=inherited||advanced(n);
        if(orbital&&!n.isAdvanced()){n.researchType=TechNodeDef.ResearchType.advanced;changed=true;}
        for(int i=0;i<n.children.size;){
            TechNodeDef child=n.children.get(i);changed|=visit(child,orbital);
            if(child.content instanceof Item||"item".equals(child.contentType)||"resource".equals(child.contentType)||erekir(child)){
                n.children.remove(i);for(var grandchild:child.children){grandchild.parent=n;n.children.insert(i++,grandchild);}
                child.children.clear();changed=true;
            }else i++;
        }
        return changed;
    }
}
