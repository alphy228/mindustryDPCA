package coolplugin.gameplay;

import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.gen.Unit;

/** One deterministic layout shared by native markers and authoritative barrels. */
public final class MountLayout {
    public record Placement(int slot, ShopItemDef item, float forward, float side, boolean mirrored) {}
    public static Seq<Placement> resolve(Unit unit, Seq<String> ids, ShopConfigIO.Catalog catalog){
        Seq<Placement> result=new Seq<>();
        float radius=Math.max(4f,unit.hitSize*0.5f);
        // Circumscribed sprite disk + maximum recoil: no overlap at any aim angle.
        float diameter=Mathf.clamp(5.4f+unit.hitSize*.14f,5.76f,10.56f)*1.04f;
        float separation=diameter*1.415f+5.5f;
        for(int i=0;i<Math.min(4,ids.size);i++){
            ShopItemDef item=catalog.findItem(ids.get(i));
            if(item==null||(item.attachMode!=ShopItemDef.AttachMode.MOUNT&&item.attachMode!=ShopItemDef.AttachMode.SUBWEAPON))continue;
            float preferredForward=item.computeForwardOffset(radius),preferredSide=Math.abs(item.computeSideOffset(radius));
            boolean dual=item.isSymmetricallyDuplicated(radius);
            float chosenForward=preferredForward,chosenSide=dual?Math.max(preferredSide,separation/2f):0;
            search: for(int row=0;row<16;row++)for(int column=0;column<8;column++){
                if(!dual&&column>0)continue;
                float forward=preferredForward-row*separation;
                float side=dual?Math.max(preferredSide,separation/2f)+column*separation:0;
                boolean clear=true;
                for(Placement other:result){
                    if(Mathf.dst(forward,side,other.forward,other.side)<separation-.01f)clear=false;
                    if(dual&&Mathf.dst(forward,-side,other.forward,other.side)<separation-.01f)clear=false;
                }
                if(clear){chosenForward=forward;chosenSide=side;break search;}
            }
            result.add(new Placement(i,item,chosenForward,chosenSide,false));
            if(dual)result.add(new Placement(i,item,chosenForward,-chosenSide,true));
        }
        return result;
    }
    public static String texture(Unit unit,ShopItemDef item){
        if(!item.worldSprite.isBlank()&&!item.worldSprite.startsWith("dp-item-mount-"))return item.worldSprite;
        String original=ColouringAssets.baseName(unit.type.name);
        for(var palette:ColouringAssets.palettes)if(unit.type.name.equals(ColouringAssets.variant(original,palette)))
            return "dp-item-mount-"+item.id+"-scheme-"+palette.id();
        return "dp-item-mount-"+item.id+"-host-"+original;
    }
    private MountLayout(){}
}
