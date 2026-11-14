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
  "version": 2,
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
        "b0bac0": 5,
        "545464": 2,
        "585868": 2,
        "5d5d6c": 2,
        "606170": 2,
        "727484": 3,
        "757887": 3,
        "6b6d7d": 3,
        "717383": 3,
        "6a6c7c": 3,
        "686a7a": 3,
        "777988": 3,
        "7c808e": 3,
        "9a9da7": 4,
        "9395a0": 4,
        "8e909c": 4,
        "9da1aa": 4,
        "999ca6": 4,
        "92949f": 4,
        "90929d": 4,
        "adb6bc": 5,
        "a8b1b8": 5,
        "aab2b9": 5,
        "a6aeb6": 5,
        "a1a9b1": 5,
        "9ca3ac": 5,
        "cc735f": 6,
        "c9705c": 6,
        "c56b58": 6,
        "da7857": 6,
        "c87c6b": 6,
        "c37565": 6,
        "ba6c5d": 6,
        "cb725f": 6,
        "c46a57": 6,
        "d97757": 6,
        "c86f5c": 6,
        "c27465": 6,
        "da7856": 6,
        "b96b5c": 6,
        "c27464": 6,
        "f99f62": 8,
        "f3a46b": 8,
        "eea067": 8,
        "de9a6a": 8,
        "f49861": 8,
        "f49860": 8,
        "ee9f68": 8,
        "e8a373": 8,
        "f49761": 8,
        "e4ac83": 8
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
        "b0bac0": 5,
        "545464": 2,
        "585868": 2,
        "5d5d6c": 2,
        "606170": 2,
        "727484": 3,
        "757887": 3,
        "6b6d7d": 3,
        "717383": 3,
        "6a6c7c": 3,
        "686a7a": 3,
        "777988": 3,
        "7c808e": 3,
        "9a9da7": 4,
        "9395a0": 4,
        "8e909c": 4,
        "9da1aa": 4,
        "999ca6": 4,
        "92949f": 4,
        "90929d": 4,
        "adb6bc": 5,
        "a8b1b8": 5,
        "aab2b9": 5,
        "a6aeb6": 5,
        "a1a9b1": 5,
        "9ca3ac": 5,
        "6aaf86": 6,
        "63a77f": 6,
        "73b08d": 6,
        "68ab83": 6,
        "64a07f": 6,
        "69bd83": 6,
        "6ea987": 6,
        "67ab83": 6,
        "63a77e": 6,
        "7cb294": 6,
        "64a07e": 6,
        "66997f": 6,
        "74a78b": 6,
        "6da986": 6,
        "81e58f": 8,
        "8de79b": 8,
        "86ea93": 8,
        "7fd68d": 8,
        "88e095": 8,
        "81e58e": 8,
        "92e0a0": 8,
        "7ed68c": 8,
        "86e992": 8,
        "7cc88b": 8,
        "7ee28c": 8
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
        "b0bac0": 5,
        "545464": 2,
        "585868": 2,
        "5d5d6c": 2,
        "606170": 2,
        "727484": 3,
        "757887": 3,
        "6b6d7d": 3,
        "717383": 3,
        "6a6c7c": 3,
        "686a7a": 3,
        "777988": 3,
        "7c808e": 3,
        "9a9da7": 4,
        "9395a0": 4,
        "8e909c": 4,
        "9da1aa": 4,
        "999ca6": 4,
        "92949f": 4,
        "90929d": 4,
        "adb6bc": 5,
        "a8b1b8": 5,
        "aab2b9": 5,
        "a6aeb6": 5,
        "a1a9b1": 5,
        "9ca3ac": 5,
        "7968b3": 6,
        "6e66a2": 6,
        "676097": 6,
        "645b98": 6,
        "7670a6": 6,
        "70699f": 6,
        "7169a0": 6,
        "676098": 6,
        "625a92": 6,
        "686294": 6,
        "7e7baa": 6,
        "bd96f2": 8,
        "ab86e4": 8,
        "b38be8": 8,
        "bb9aec": 8,
        "a784d8": 8,
        "ba92ef": 8,
        "b58eeb": 8,
        "ac8add": 8,
        "b693e5": 8,
        "ab85e4": 8,
        "b68eeb": 8,
        "b693e6": 8,
        "9c7ec8": 8,
        "a180da": 8
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
        "b0bac0": 5,
        "545464": 2,
        "585868": 2,
        "5d5d6c": 2,
        "606170": 2,
        "727484": 3,
        "757887": 3,
        "6b6d7d": 3,
        "717383": 3,
        "6a6c7c": 3,
        "686a7a": 3,
        "777988": 3,
        "7c808e": 3,
        "9a9da7": 4,
        "9395a0": 4,
        "8e909c": 4,
        "9da1aa": 4,
        "999ca6": 4,
        "92949f": 4,
        "90929d": 4,
        "adb6bc": 5,
        "a8b1b8": 5,
        "aab2b9": 5,
        "a6aeb6": 5,
        "a1a9b1": 5,
        "9ca3ac": 5,
        "d08774": 6,
        "bd7d6f": 6,
        "dd936f": 6,
        "cd8371": 6,
        "c87f6d": 6,
        "c68677": 6,
        "cc8d7d": 6,
        "c0897e": 6,
        "f5c07a": 8,
        "e8c687": 8,
        "f3cc83": 8,
        "eec77f": 8,
        "debc7f": 8,
        "edcd8d": 8
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
        "fdb380": 8,
        "30323b": 2,
        "343640": 2,
        "383a44": 2,
        "4e4f58": 3,
        "52535c": 3,
        "464750": 3,
        "43444d": 3,
        "515563": 3,
        "4d4c5b": 3,
        "727484": 4,
        "6a6b7b": 4,
        "666776": 4,
        "777988": 4,
        "5f6170": 4,
        "9395a0": 5,
        "8f919b": 5,
        "8c8e98": 5,
        "8e909c": 5,
        "9293a3": 5,
        "80828c": 5,
        "b85b5b": 6,
        "af5252": 6,
        "b35757": 6,
        "ca695c": 6,
        "b46364": 6,
        "a25252": 6,
        "f2b084": 8,
        "eaa77b": 8,
        "edab80": 8,
        "ef9d75": 8,
        "e7ad88": 8,
        "dea480": 8,
        "d69b76": 8
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
        "b58cee": 8,
        "30323b": 2,
        "343640": 2,
        "383a44": 2,
        "4e4f58": 3,
        "52535c": 3,
        "464750": 3,
        "43444d": 3,
        "515563": 3,
        "4d4c5b": 3,
        "727484": 4,
        "6a6b7b": 4,
        "666776": 4,
        "777988": 4,
        "5f6170": 4,
        "9395a0": 5,
        "8f919b": 5,
        "8c8e98": 5,
        "8e909c": 5,
        "9293a3": 5,
        "80828c": 5,
        "625a96": 6,
        "5f588e": 6,
        "7169a0": 6,
        "7968b3": 6,
        "676098": 6,
        "595288": 6,
        "5f5793": 6,
        "5f5693": 6,
        "686294": 6,
        "b28ae6": 8,
        "ba92ef": 8,
        "ab86e4": 8,
        "a482d4": 8,
        "b68eeb": 8,
        "b693e6": 8,
        "ad8ade": 8,
        "ab85e4": 8,
        "9e7cce": 8,
        "ae87e3": 8,
        "a582d4": 8,
        "987ac1": 8,
        "a180db": 8,
        "8e71b8": 8
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
        "c5e0f0": 8,
        "30323b": 2,
        "343640": 2,
        "383a44": 2,
        "4e4f58": 3,
        "52535c": 3,
        "464750": 3,
        "43444d": 3,
        "515563": 3,
        "4d4c5b": 3,
        "727484": 4,
        "6a6b7b": 4,
        "666776": 4,
        "777988": 4,
        "5f6170": 4,
        "9395a0": 5,
        "8f919b": 5,
        "8c8e98": 5,
        "8e909c": 5,
        "9293a3": 5,
        "80828c": 5,
        "93b0ea": 6,
        "849ed7": 6,
        "7d94c6": 6,
        "8da7e0": 6,
        "8ea5d8": 6,
        "9bb8ed": 6,
        "88a2dc": 6,
        "859cd0": 6,
        "819bd4": 6,
        "768dc1": 6,
        "c9e7fc": 8,
        "c1dff9": 8
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
        "fac97c": 8,
        "30323b": 2,
        "343640": 2,
        "383a44": 2,
        "4e4f58": 3,
        "52535c": 3,
        "464750": 3,
        "43444d": 3,
        "515563": 3,
        "4d4c5b": 3,
        "727484": 4,
        "6a6b7b": 4,
        "666776": 4,
        "777988": 4,
        "5f6170": 4,
        "9395a0": 5,
        "8f919b": 5,
        "8c8e98": 5,
        "8e909c": 5,
        "9293a3": 5,
        "80828c": 5,
        "d08774": 6,
        "bd7d6f": 6,
        "dd936f": 6,
        "cd8371": 6,
        "c87f6d": 6,
        "c68677": 6,
        "cc8d7d": 6,
        "c0897e": 6,
        "f5c07a": 8,
        "e8c687": 8,
        "f3cc83": 8,
        "eec77f": 8,
        "debc7f": 8,
        "edcd8d": 8
      }
    }
  ]
}
        """;
    public static void load(Fi data){
        Fi file=data.child("alphystuff/unit-palettes.hjson");if(!file.exists())file.writeString(DEFAULTS);
        Jval root=Jval.read(file.readString());
        if(root.getInt("version",1)<2){
            Jval defaults=Jval.read(DEFAULTS);
            for(String section:new String[]{"schemes","sources"}){
                Jval rows=root.get(section);if(rows==null)continue;
                for(Jval row:rows.asArray())for(Jval shipped:defaults.get(section).asArray()){
                    // Preserve custom roles and aliases; only migrate matching shipped sources.
                    if(!row.get("roles").toString().equals(shipped.get("roles").toString()))continue;
                    if(!row.get("units").toString().equals(shipped.get("units").toString()))continue;
                    Jval aliases=row.get("aliases");if(aliases==null){aliases=Jval.newObject();row.add("aliases",aliases);}
                    for(var alias:shipped.get("aliases").asObject())if(aliases.get(alias.key)==null)aliases.add(alias.key,alias.value);
                }
            }
            root.add("version",Jval.valueOf(2));file.writeString(root.toString(Jval.Jformat.formatted));
        }
        sources.clear();aliasesBySource.clear();
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
