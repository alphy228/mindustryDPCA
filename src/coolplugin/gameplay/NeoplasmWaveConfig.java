package coolplugin.gameplay;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.type.UnitType;

/** Strict editable JSON; malformed operator files are never overwritten. */
public final class NeoplasmWaveConfig {
    public record Group(String unit,int base,int perExtraPlayer){}
    public record Wave(Seq<Group> bosses,Seq<Group> swarm,Seq<String> continuous){}
    public final Seq<Wave> waves=new Seq<>();
    public float continuousStart=60,firstWave=220,waveInterval=150,baseInterval=7,playerExponent=.8f,
        maxRelief=4,deadWeight=.5f,deadDecay=600,emptyGrace=40,bossHealthPerPlayer=.65f,endlessGrowth=.08f;
    public static NeoplasmWaveConfig load(Fi file){
        if(!file.exists())file.writeString(DEFAULT_JSON);
        try{return parse(file.readString());}
        catch(RuntimeException failure){Log.err("[Neoplasm] Invalid "+file+"; using safe defaults for this world, leaving your file untouched.",failure);return parse(DEFAULT_JSON);}
    }
    public static NeoplasmWaveConfig parse(String text){
        var root=Jval.read(text);var out=new NeoplasmWaveConfig();Jval settings=root.get("settings");
        if(settings!=null){
            out.continuousStart=number(settings,"continuousStartSeconds",60,0,86400);
            out.firstWave=number(settings,"firstWaveSeconds",220,0,86400);
            out.waveInterval=number(settings,"waveIntervalSeconds",150,1,86400);
            out.baseInterval=number(settings,"continuousBaseSeconds",7,.1f,3600);
            out.playerExponent=number(settings,"playerExponent",.8f,0,3);
            out.maxRelief=number(settings,"maxHealthReliefMultiplier",4,1,10);
            out.deadWeight=number(settings,"deadPlayerInitialWeight",.5f,0,1);
            out.deadDecay=number(settings,"deadPlayerDecaySeconds",600,1,86400);
            out.emptyGrace=number(settings,"emptyServerGraceSeconds",40,0,3600);
            out.bossHealthPerPlayer=number(settings,"bossHealthPerExtraPlayer",.65f,0,10);
            out.endlessGrowth=number(settings,"endlessGrowthPerWave",.08f,0,1);
        }
        for(int i=1;root.get("wave"+i)!=null;i++){
            Jval row=root.get("wave"+i);Seq<Group> bosses=new Seq<>(),swarm=new Seq<>();int count=0;
            for(Jval entry:row.get("bosses").asArray()){
                String unit=unitName(entry);int amount=entry.getInt("count",1);if(amount<1||amount>3)throw new IllegalArgumentException("Invalid boss count at wave"+i);
                count+=amount;bosses.add(new Group(unit,amount,0));
            }
            if(count<1||count>3)throw new IllegalArgumentException("Each wave must have 1..3 bosses");
            for(Jval entry:row.get("swarm").asArray()){
                int base=entry.getInt("base",0),per=entry.getInt("perExtraPlayer",0);
                if(base<0||base>1000||per<0||per>100)throw new IllegalArgumentException("Invalid swarm count");
                swarm.add(new Group(unitName(entry),base,per));
            }
            Seq<String> continuous=new Seq<>();for(Jval name:row.get("continuous").asArray())continuous.add(name.asString());
            if(continuous.isEmpty())throw new IllegalArgumentException("Empty continuous pool");
            out.waves.add(new Wave(bosses,swarm,continuous));
        }
        if(out.waves.isEmpty())throw new IllegalArgumentException("Missing wave1");
        int expected=out.waves.size;
        for(var entry:root.asObject())if(entry.key.startsWith("wave")&&!entry.key.equals("waves")){
            int index=Integer.parseInt(entry.key.substring(4));if(index<1||index>expected)throw new IllegalArgumentException("Wave keys must be contiguous from wave1");
        }
        return out;
    }
    private static String unitName(Jval entry){String name=entry.getString("unit");if(name.isBlank())throw new IllegalArgumentException("Blank unit");return name;}
    private static float number(Jval row,String key,float fallback,float min,float max){float value=row.getFloat(key,fallback);if(!Float.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Invalid "+key);return value;}
    /** Call only after the datapatch has registered its custom units. */
    public void validateUnits(){for(Wave wave:waves){for(Group group:wave.bosses)resolve(group.unit);for(Group group:wave.swarm)resolve(group.unit);for(String name:wave.continuous)resolve(name);}}
    public static UnitType resolve(String name){UnitType unit=Vars.content.unit(name);if(unit==null||unit.hidden||unit.internal)throw new IllegalArgumentException("Unknown or hidden wave unit: "+name);return unit;}
    public Wave wave(int number){return waves.get(Math.min(Math.max(1,number),waves.size)-1);}
    public static final String DEFAULT_JSON="""
{
  "settings": {
    "continuousStartSeconds": 60,
    "firstWaveSeconds": 220,
    "waveIntervalSeconds": 150,
    "continuousBaseSeconds": 7,
    "playerExponent": 0.8,
    "maxHealthReliefMultiplier": 4,
    "deadPlayerInitialWeight": 0.5,
    "deadPlayerDecaySeconds": 600,
    "emptyServerGraceSeconds": 40,
    "bossHealthPerExtraPlayer": 0.65,
    "endlessGrowthPerWave": 0.08
  },
  "wave1": {
    "bosses": [
      {
        "unit": "mace",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "dagger",
        "base": 3,
        "perExtraPlayer": 2
      },
      {
        "unit": "crawler",
        "base": 3,
        "perExtraPlayer": 2
      },
      {
        "unit": "flare",
        "base": 3,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "dagger",
      "crawler"
    ]
  },
  "wave2": {
    "bosses": [
      {
        "unit": "mace",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "dagger",
        "base": 3,
        "perExtraPlayer": 2
      },
      {
        "unit": "crawler",
        "base": 3,
        "perExtraPlayer": 2
      },
      {
        "unit": "flare",
        "base": 3,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "dagger",
      "crawler"
    ]
  },
  "wave3": {
    "bosses": [
      {
        "unit": "mace",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "dagger",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "crawler",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "flare",
        "base": 4,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "dagger",
      "mace",
      "flare"
    ]
  },
  "wave4": {
    "bosses": [
      {
        "unit": "mace",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "dagger",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "crawler",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "flare",
        "base": 4,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "dagger",
      "mace",
      "flare"
    ]
  },
  "wave5": {
    "bosses": [
      {
        "unit": "fortress",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "pulsar",
        "base": 4,
        "perExtraPlayer": 2
      },
      {
        "unit": "horizon",
        "base": 4,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "dagger",
      "mace",
      "flare"
    ]
  },
  "wave6": {
    "bosses": [
      {
        "unit": "fortress",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 5,
        "perExtraPlayer": 2
      },
      {
        "unit": "pulsar",
        "base": 5,
        "perExtraPlayer": 2
      },
      {
        "unit": "horizon",
        "base": 5,
        "perExtraPlayer": 2
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave7": {
    "bosses": [
      {
        "unit": "fortress",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 5,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 5,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 5,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave8": {
    "bosses": [
      {
        "unit": "fortress",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 5,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 5,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 5,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave9": {
    "bosses": [
      {
        "unit": "spiroct",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 6,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave10": {
    "bosses": [
      {
        "unit": "spiroct",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 6,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave11": {
    "bosses": [
      {
        "unit": "spiroct",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 6,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 6,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave12": {
    "bosses": [
      {
        "unit": "spiroct",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "mace",
        "base": 7,
        "perExtraPlayer": 3
      },
      {
        "unit": "pulsar",
        "base": 7,
        "perExtraPlayer": 3
      },
      {
        "unit": "horizon",
        "base": 7,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "mace",
      "pulsar",
      "horizon"
    ]
  },
  "wave13": {
    "bosses": [
      {
        "unit": "scepter",
        "count": 1
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 7,
        "perExtraPlayer": 3
      },
      {
        "unit": "quasar",
        "base": 7,
        "perExtraPlayer": 3
      },
      {
        "unit": "zenith",
        "base": 7,
        "perExtraPlayer": 3
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave14": {
    "bosses": [
      {
        "unit": "scepter",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 7,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 7,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 7,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave15": {
    "bosses": [
      {
        "unit": "scepter",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 8,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave16": {
    "bosses": [
      {
        "unit": "scepter",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 8,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave17": {
    "bosses": [
      {
        "unit": "arkyid",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 8,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 8,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave18": {
    "bosses": [
      {
        "unit": "arkyid",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 9,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave19": {
    "bosses": [
      {
        "unit": "arkyid",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 9,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave20": {
    "bosses": [
      {
        "unit": "arkyid",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "quasar",
        "base": 9,
        "perExtraPlayer": 4
      },
      {
        "unit": "zenith",
        "base": 9,
        "perExtraPlayer": 4
      }
    ],
    "continuous": [
      "fortress",
      "quasar",
      "zenith"
    ]
  },
  "wave21": {
    "bosses": [
      {
        "unit": "reign",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "quasar",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "zenith",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "spiroct",
        "base": 10,
        "perExtraPlayer": 5
      }
    ],
    "continuous": [
      "fortress",
      "spiroct",
      "zenith",
      "scepter"
    ]
  },
  "wave22": {
    "bosses": [
      {
        "unit": "reign",
        "count": 2
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "quasar",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "zenith",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "spiroct",
        "base": 10,
        "perExtraPlayer": 5
      }
    ],
    "continuous": [
      "fortress",
      "spiroct",
      "zenith",
      "scepter"
    ]
  },
  "wave23": {
    "bosses": [
      {
        "unit": "reign",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "quasar",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "zenith",
        "base": 10,
        "perExtraPlayer": 5
      },
      {
        "unit": "spiroct",
        "base": 10,
        "perExtraPlayer": 5
      }
    ],
    "continuous": [
      "fortress",
      "spiroct",
      "zenith",
      "scepter"
    ]
  },
  "wave24": {
    "bosses": [
      {
        "unit": "reign",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "quasar",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "zenith",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "spiroct",
        "base": 11,
        "perExtraPlayer": 5
      }
    ],
    "continuous": [
      "fortress",
      "spiroct",
      "zenith",
      "scepter"
    ]
  },
  "wave25": {
    "bosses": [
      {
        "unit": "reign",
        "count": 3
      }
    ],
    "swarm": [
      {
        "unit": "fortress",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "quasar",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "zenith",
        "base": 11,
        "perExtraPlayer": 5
      },
      {
        "unit": "spiroct",
        "base": 11,
        "perExtraPlayer": 5
      }
    ],
    "continuous": [
      "fortress",
      "spiroct",
      "zenith",
      "scepter"
    ]
  }
}
""";
}
