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
        // Centres stay on the hull. On very small units, several full-size weapons
        // physically cannot be disjoint; minimize overlap instead of moving them off-body.
        float extent=Math.max(1.5f,radius*.70f);
        for(int i=0;i<Math.min(4,ids.size);i++){
            ShopItemDef item=catalog.findItem(ids.get(i));
            if(item==null||(item.attachMode!=ShopItemDef.AttachMode.MOUNT&&item.attachMode!=ShopItemDef.AttachMode.SUBWEAPON))continue;
            var size=ColouringAssets.mountSizes.get(item.id);
            float width=size==null?6f:size.x,height=size==null?7f:size.y;
            boolean dual=item.isSymmetricallyDuplicated(radius);
            float preferredForward=Mathf.clamp(item.computeForwardOffset(radius),-extent,extent);
            float preferredSide=dual?Mathf.clamp(Math.abs(item.computeSideOffset(radius)),extent*.35f,extent*.85f):0;
            float chosenForward=preferredForward,chosenSide=preferredSide,best=Float.POSITIVE_INFINITY;
            for(int row=0;row<9;row++)for(int column=0;column<(dual?5:1);column++){
                float forward=-extent+row*extent/4f;
                float side=dual?extent*(.3f+column*.15f):0;
                float score=.025f*(Mathf.sqr(forward-preferredForward)+Mathf.sqr(side-preferredSide));
                for(Placement other:result){
                    var otherSize=ColouringAssets.mountSizes.get(other.item.id);
                    float ow=otherSize==null?6f:otherSize.x,oh=otherSize==null?7f:otherSize.y;
                    float overlapY=Math.max(0,(height+oh)*.5f+.2f-Math.abs(forward-other.forward));
                    score+=Math.max(0,(width+ow)*.5f+.2f-Math.abs(side-other.side))*overlapY;
                    if(dual)score+=Math.max(0,(width+ow)*.5f+.2f-Math.abs(-side-other.side))*overlapY;
                    if(Mathf.dst(forward,side,other.forward,other.side)<.4f)score+=10000;
                    if(dual&&Mathf.dst(forward,-side,other.forward,other.side)<.4f)score+=10000;
                }
                if(score<best){best=score;chosenForward=forward;chosenSide=side;}
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
