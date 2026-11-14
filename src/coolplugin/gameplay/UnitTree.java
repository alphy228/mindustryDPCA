package coolplugin.gameplay;

import mindustry.Vars;
import mindustry.type.UnitType;

/** Stable tree IDs are persisted instead of tiers or paint variants. */
public enum UnitTree {
    MECH("Mech",true,"dagger","mace","fortress","scepter","reign"),
    SUPPORT_MECH("Support mech",true,"nova","pulsar","quasar","vela","corvus"),
    ATTACK_AIR("Attack Air",true,"flare","horizon","zenith","antumbra","eclipse"),
    SUPPORT_AIR("Support Air",true,"mono","poly","mega","quad","oct"),
    LEGGED("Legged",false,"crawler","atrax","spiroct","arkyid","toxopid"),
    ATTACK_NAVALS("Attack Navals",false,"risso","minke","bryde","sei","omura"),
    SUPPORT_NAVALS("Support Navals",false,"retusa","oxynoe","cyerce","aegires","navanax"),
    CHEMICAL("Chemical",true,"dp-oxidise-unit","dp-corrode-unit","dp-dissociate-unit"),
    TANK("Tank",false,"stell","locus","precept","vanquish","conquer"),
    INSECTOID("Insectoid",false,"merui","cleroi","anthicus","tecta","collaris"),
    FLYERS("Flyers",false,"elude","avert","obviate","quell","disrupt");

    public final String title;
    public final boolean available;
    public final String[] units;
    UnitTree(String title,boolean available,String... units){this.title=title;this.available=available;this.units=units;}
    /** Native weapon/ability accent hues, made bright/saturated without changing hue. */
    public String accentHex(){
        String source=switch(this){
            case SUPPORT_MECH, SUPPORT_AIR, SUPPORT_NAVALS -> "98ffa9"; // Pal.heal
            case LEGGED, FLYERS -> "bf92f9"; // Pal.sapBullet, Erekir purple hover/air line
            case INSECTOID -> "8ca9e8"; // Pal.techBlue, Merui/Cleroi/Anthicus projectiles
            case CHEMICAL -> "e4ffd6"; // shipped corrosive liquid
            default -> "f68021"; // Pal.lightOrange, orange attack lines
        };
        float[] hsv=arc.graphics.Color.valueOf(source).toHsv(new float[3]);
        return new arc.graphics.Color().fromHsv(hsv[0],Math.max(hsv[1],0.7f),1f).toString().substring(0,6);
    }
    public String id(){return name().toLowerCase(java.util.Locale.ROOT);}
    public String savedId(){return "tree:"+id();}
    public String art(){return "unit-tree-"+id();}
    public UnitType starter(){return Vars.content.unit(units[0]);}
    public static UnitTree byId(String id){for(UnitTree tree:values())if(tree.id().equals(id)||tree.savedId().equals(id))return tree;return null;}
    public static UnitTree forUnit(String name){
        String base=ColouringAssets.baseName(name);
        for(UnitTree tree:values())for(String unit:tree.units)if(unit.equals(base))return tree;
        return null;
    }
    public static UnitTree fromSave(String name){UnitTree tree=byId(name);if(tree==null&&name!=null)tree=forUnit(name);return tree==null?MECH:tree;}
    public static String canonical(UnitType unit){return unit==null?"dagger":ColouringAssets.baseName(unit.name);}
}
