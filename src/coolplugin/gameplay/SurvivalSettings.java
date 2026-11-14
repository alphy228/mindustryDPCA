package coolplugin.gameplay;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;

/** Operator-editable late-game tuning, separate from the per-wave roster. */
public final class SurvivalSettings {
    public float collapse=3000, radiation=3600, radiationSecond=3900, radiationThird=4200, burning=4500, nukes=4800;
    public float radiationDps=1, fastMultiplier=2, warning=5, nukeCooldown=60, nukeDecrease=2, nukeMinimum=10;
    public float nukeDirectChance=.2f,nukeScatterTiles=100,nukeRadiusTiles=19,nukeDamage=5000;
    public static SurvivalSettings load(Fi file){
        SurvivalSettings out=new SurvivalSettings();
        if(!file.exists())file.writeString(DEFAULT_JSON);
        try{
            Jval j=Jval.read(file.readString());
            out.collapse=number(j,"collapseSeconds",3000,1,86400);
            out.radiation=number(j,"radiationSeconds",3600,1,86400);
            out.radiationSecond=number(j,"radiationSecondSeconds",3900,out.radiation,86400);
            out.radiationThird=number(j,"radiationThirdSeconds",4200,out.radiationSecond,86400);
            out.burning=number(j,"burningSeconds",4500,1,86400);out.nukes=number(j,"nukeStartSeconds",4800,1,86400);
            out.radiationDps=number(j,"radiationDpsPerStack",1,0,100);
            out.fastMultiplier=number(j,"fastFlyingMultiplier",2,1,10);
            out.warning=number(j,"nukeWarningSeconds",5,1,60);
            out.nukeCooldown=number(j,"nukeInitialCooldownSeconds",60,1,3600);
            out.nukeMinimum=number(j,"nukeMinimumCooldownSeconds",10,1,out.nukeCooldown);
            out.nukeDecrease=number(j,"nukeCooldownDecreaseSeconds",2,0,60);
            out.nukeDirectChance=number(j,"nukeDirectChance",.2f,0,1);
            out.nukeScatterTiles=number(j,"nukeScatterTiles",100,1,1000);
            out.nukeRadiusTiles=number(j,"nukeRadiusTiles",19,1,100);
            out.nukeDamage=number(j,"nukeDamage",5000,1,1000000);
        }catch(Exception e){Log.err("Invalid survival.json; using defaults without replacing your file.",e);return new SurvivalSettings();}
        return out;
    }
    private static float number(Jval j,String name,float fallback,float min,float max){float n=j.getFloat(name,fallback);if(!Float.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(name);return n;}
    public static final String DEFAULT_JSON="""
{"collapseSeconds":3000,"radiationSeconds":3600,"radiationSecondSeconds":3900,"radiationThirdSeconds":4200,
 "burningSeconds":4500,"nukeStartSeconds":4800,"radiationDpsPerStack":1,"fastFlyingMultiplier":2,
 "nukeWarningSeconds":5,"nukeInitialCooldownSeconds":60,"nukeCooldownDecreaseSeconds":2,"nukeMinimumCooldownSeconds":10,
 "nukeDirectChance":0.2,"nukeScatterTiles":100,"nukeRadiusTiles":19,"nukeDamage":5000}
""";
}
