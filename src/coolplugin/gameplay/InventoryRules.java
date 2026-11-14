package coolplugin.gameplay;

import mindustry.Vars;
import mindustry.type.UnitType;

/** Capacities use immutable base types, never current HP or temporary status health. */
public final class InventoryRules {
    public static final int EQUIP_END=30, TOTAL_END=90, MAX_DRAWN_CARRY=6;
    public record Capacity(int equip,int carry,int minimumEquipSize){}
    private static final String[][] serp={
        {"dagger","nova","crawler","flare","mono","risso","retusa","alpha"},
        {"mace","pulsar","atrax","horizon","poly","minke","oxynoe","beta"},
        {"fortress","quasar","spiroct","zenith","mega","bryde","cyerce","gamma"},
        {"scepter","vela","arkyid","antumbra","quad","sei","aegires"},
        {"reign","corvus","toxopid","eclipse","oct","omura","navanax"}};
    private static final String[][] ere={
        {"stell","merui","elude","evoke"},{"locus","cleroi","avert","incite"},
        {"precept","anthicus","obviate","emanate"},{"vanquish","tecta","quell"},
        {"conquer","collaris","disrupt"}};
    public static Capacity capacity(UnitType type){
        String name=type==null?"dagger":ColouringAssets.baseName(type.name);
        for(int i=0;i<5;i++)for(String n:serp[i])if(n.equals(name))return switch(i){
            case 0->new Capacity(1,2,1);case 1->new Capacity(2,3,1);case 2->new Capacity(4,6,1);
            case 3->new Capacity(12,20,2);default->new Capacity(30,60,4);};
        for(int i=0;i<5;i++)for(String n:ere[i])if(n.equals(name))return switch(i){
            case 0->new Capacity(2,3,1);case 1->new Capacity(4,6,1);case 2->new Capacity(6,7,1);
            case 3->new Capacity(12,20,2);default->new Capacity(30,60,4);};
        UnitType base=Vars.content==null?null:Vars.content.unit(name);
        float hp=base!=null?base.health:type==null?0:type.health;
        // Explicit custom-unit bands, calibrated to the main combat progression.
        return hp<400?new Capacity(1,2,1):hp<800?new Capacity(2,3,1):hp<2000?new Capacity(4,6,1)
            :hp<6000?new Capacity(6,7,1):hp<18000?new Capacity(12,20,2):new Capacity(30,60,4);
    }
    private InventoryRules(){}
}
