package coolplugin.gameplay;
import arc.files.Fi;
import arc.struct.*;
import arc.util.serialization.Jval;

/** Exact hand-authored RGB substitutions. No clustering, HSV, nearest match or shade inference. */
public final class ManualUnitPalettes {
    private static final ObjectMap<String,int[]> sources=new ObjectMap<>();
    private static final java.util.IdentityHashMap<int[],ObjectMap<String,Integer>> aliasesBySource=new java.util.IdentityHashMap<>();
    private static final String DEFAULTS="""
{
  "version": 1,
  "description": "Hand-authored RGB material tables. Roles: outline, outline, armour dark/mid/light/highlight, accent dark/dark/light/light. Unknown RGB values remain unchanged.",
  "schemes": [
    {
      "id": "unit-dagger",
      "title": "Serpulo orange",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "565666",
        "6e7080",
        "989aa4",
        "b0b9c0",
        "d06b53",
        "d06b53",
        "ffa664",
        "ffa664"
      ],
      "units": [
        "dagger",
        "mace",
        "fortress",
        "scepter",
        "reign",
        "flare",
        "horizon",
        "zenith",
        "antumbra",
        "eclipse",
        "risso",
        "minke",
        "bryde",
        "sei",
        "omura"
      ],
      "aliases": {
        "d06b53": 6,
        "cf6a53": 6,
        "d57155": 6,
        "d47055": 6,
        "c86f5b": 6,
        "ffa664": 8,
        "ffa566": 8,
        "f99e63": 8,
        "e8a372": 8,
        "edaa79": 8,
        "f6a86f": 8,
        "6c6f7e": 3,
        "9798a3": 4,
        "b0bac0": 5
      }
    },
    {
      "id": "unit-oct",
      "title": "Serpulo green (Oct)",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "565666",
        "6e7080",
        "989aa4",
        "b0b9c0",
        "62ae7e",
        "62ae7e",
        "84f490",
        "84f490"
      ],
      "units": [
        "nova",
        "pulsar",
        "quasar",
        "vela",
        "corvus",
        "mono",
        "poly",
        "mega",
        "quad",
        "oct",
        "retusa",
        "oxynoe",
        "cyerce",
        "aegires",
        "navanax"
      ],
      "aliases": {
        "62ae7e": 6,
        "65b581": 6,
        "84f490": 8,
        "80ec8e": 8,
        "7ce48c": 8,
        "88ed96": 8,
        "6c6f7e": 3,
        "9798a3": 4,
        "b0bac0": 5
      }
    },
    {
      "id": "unit-toxopid",
      "title": "Serpulo purple",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "565666",
        "6e7080",
        "989aa4",
        "b0b9c0",
        "665c9f",
        "665c9f",
        "bf92f8",
        "bf92f8"
      ],
      "units": [
        "crawler",
        "atrax",
        "spiroct",
        "arkyid",
        "toxopid"
      ],
      "aliases": {
        "665c9f": 6,
        "6f62a9": 6,
        "665e9b": 6,
        "6b629f": 6,
        "bf92f8": 8,
        "b58cef": 8,
        "b58cee": 8,
        "6c6f7e": 3,
        "9798a3": 4,
        "b0bac0": 5
      }
    },
    {
      "id": "unit-alpha",
      "title": "Core gold",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "565666",
        "6e7080",
        "989aa4",
        "b0b9c0",
        "d4806b",
        "d4806b",
        "ffd27e",
        "ffd27e"
      ],
      "units": [
        "alpha",
        "beta",
        "gamma"
      ],
      "aliases": {
        "d4806b": 6,
        "d88a6d": 6,
        "ffd27e": 8,
        "fccf7e": 8,
        "facc7d": 8,
        "fac97c": 8,
        "6c6f7e": 3,
        "9798a3": 4,
        "b0bac0": 5
      }
    },
    {
      "id": "unit-stell",
      "title": "Erekir orange",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "2d2f39",
        "4a4b53",
        "6e7080",
        "989aa4",
        "bc5452",
        "bc5452",
        "fdb380",
        "fdb380"
      ],
      "units": [
        "stell",
        "locus",
        "precept",
        "vanquish",
        "conquer",
        "merui",
        "tecta",
        "collaris"
      ],
      "aliases": {
        "bc5452": 6,
        "c35e57": 6,
        "f6a87a": 8,
        "fdb380": 8
      }
    },
    {
      "id": "unit-elude",
      "title": "Erekir purple",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "2d2f39",
        "4a4b53",
        "6e7080",
        "989aa4",
        "665c9f",
        "665c9f",
        "bf92f8",
        "bf92f8"
      ],
      "units": [
        "elude",
        "avert",
        "obviate",
        "quell",
        "disrupt"
      ],
      "aliases": {
        "665c9f": 6,
        "6f62a9": 6,
        "665e9b": 6,
        "6b629f": 6,
        "bf92f8": 8,
        "b58cef": 8,
        "b58cee": 8
      }
    },
    {
      "id": "unit-cleroi",
      "title": "Erekir cyan",
      "tier": 1,
      "roles": [
        "404049",
        "404049",
        "2d2f39",
        "4a4b53",
        "6e7080",
        "989aa4",
        "8ca8e8",
        "8ca8e8",
        "d0efff",
        "d0efff"
      ],
      "units": [
        "cleroi",
        "anthicus"
      ],
      "aliases": {
        "8ca8e8": 6,
        "d0efff": 8,
        "c5e0f0": 8
      }
    },
    {
      "id": "unit-dp-dissociate-unit",
      "title": "Corrode / Dissociate pink",
      "tier": 2,
      "roles": [
        "404049",
        "404049",
        "565666",
        "6e7080",
        "989aa4",
        "b0bac0",
        "a8527d",
        "a8527d",
        "f98dc9",
        "f98dc9"
      ],
      "units": [
        "dp-corrode-unit",
        "dp-dissociate-unit",
        "dp-oxidise-unit"
      ],
      "aliases": {
        "a8527d": 6,
        "f98dc9": 8,
        "6c6f7e": 3,
        "9798a3": 4,
        "b0bac0": 5
      }
    }
  ],
  "sources": [
    {
      "units": [
        "evoke",
        "incite",
        "emanate"
      ],
      "roles": [
        "404049",
        "404049",
        "2d2f39",
        "4a4b53",
        "6e7080",
        "989aa4",
        "d4806b",
        "d4806b",
        "ffd27e",
        "ffd27e"
      ],
      "paintAlias": "unit-alpha",
      "aliases": {
        "d4806b": 6,
        "d88a6d": 6,
        "ffd27e": 8,
        "fccf7e": 8,
        "facc7d": 8,
        "fac97c": 8
      }
    }
  ]
}
        """;
    public static void load(Fi data){
        Fi file=data.child("alphystuff/unit-palettes.hjson");if(!file.exists())file.writeString(DEFAULTS);
        Jval root=Jval.read(file.readString());sources.clear();aliasesBySource.clear();
        ColouringAssets.palettes.clear();ColouringAssets.paletteAliases.clear();
        for(Jval row:root.get("schemes").asArray()){
            int[] roles=new int[10];if(row.get("roles").asArray().size!=10)throw new IllegalArgumentException("Palette must have ten roles");
            for(int i=0;i<10;i++)roles[i]=Integer.parseInt(row.get("roles").asArray().get(i).asString(),16);
            loadAliases(row,roles);
            var palette=new ColouringAssets.Palette(row.getString("id"),row.getString("title"),row.getInt("tier",1),roles);
            ColouringAssets.palettes.add(palette);
            for(Jval unit:row.get("units").asArray()){
                sources.put(unit.asString(),roles);ColouringAssets.paletteAliases.put("unit-"+unit.asString(),palette.id());
            }
        }
        Jval extra=root.get("sources");if(extra!=null)for(Jval row:extra.asArray()){
            int[] roles=new int[10];for(int i=0;i<10;i++)roles[i]=Integer.parseInt(row.get("roles").asArray().get(i).asString(),16);
            loadAliases(row,roles);
            for(Jval unit:row.get("units").asArray()){
                sources.put(unit.asString(),roles);ColouringAssets.paletteAliases.put("unit-"+unit.asString(),row.getString("paintAlias"));
            }
        }
    }
    public static int[] source(String name){return sources.get(ColouringAssets.baseName(name));}
    public static int map(int rgb,int[] source,int[] target){
        if(source==null||target==null||source.length!=10||target.length!=10)return rgb;
        // The source's exact values take precedence over explicitly listed cross-family aliases.
        for(int i=0;i<10;i++)if(rgb==source[i])return target[i];
        var aliases=aliasesBySource.get(source);
        if(aliases==null)for(var entry:aliasesBySource.entrySet())if(java.util.Arrays.equals(source,entry.getKey())){aliases=entry.getValue();break;}
        Integer role=aliases==null?null:aliases.get(String.format(java.util.Locale.ROOT,"%06x",rgb));
        return role==null?rgb:target[role];
    }
    private static void loadAliases(Jval row,int[] source){
        var aliases=new ObjectMap<String,Integer>();Jval json=row.get("aliases");
        if(json!=null)for(var entry:json.asObject()){
            int index=entry.value.asInt();if(index<0||index>9)throw new IllegalArgumentException("Invalid material role index");aliases.put(entry.key,index);
        }
        aliasesBySource.put(source,aliases);
    }
    private ManualUnitPalettes(){}
}
