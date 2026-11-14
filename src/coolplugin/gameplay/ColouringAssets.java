package coolplugin.gameplay;

import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.struct.*;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.ctype.*;
import mindustry.entities.Effect;
import mindustry.gen.Sounds;
import mindustry.mod.DataPatcher;
import mindustry.mod.data.*;
import mindustry.type.UnitType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Generates unit-family datapatch content and flat-colour PNGs; assets never enter the plugin JAR. */
public final class ColouringAssets {
    public record Palette(String id, String title, int tier, int[] ramp) {
        public String canId() { return "paint-" + id; }
        public String spritePrefix() { return "paint-" + id + "-"; }
    }
    public static final Seq<Palette> palettes = new Seq<>();
    public static final ObjectMap<String, String> originals = new ObjectMap<>();
    public static final int MAX_PALETTES = 20;
    public static final long BULK_RGBA_BUDGET_BYTES = 144L * 1024 * 1024;
    public static final ObjectMap<String,String> paletteAliases = new ObjectMap<>();
    private long bulkPixelsPerPalette;
    private static final Map<String,Double> schemeDistances=new HashMap<>();
    private static final String DIR = "colouring-generated";
    private final Map<Class<?>,Object> defaults=new HashMap<>();
    private final Fi data;
    private final ObjectMap<String,String> unitTemplates=new ObjectMap<>();
    private final ObjectMap<String, BufferedImage> sprites = new ObjectMap<>();
    private final IdentityHashMap<Object, String> constants = new IdentityHashMap<>();

    public ColouringAssets(Fi data) { this.data = data; }
    public static String variant(String original, Palette palette) {
        return "dp-" + palette.spritePrefix() + baseName(original);
    }
    public static String baseName(String name) {
        String known=originals.get(name);
        if(known!=null)return known;
        if(name.startsWith("dp-paint-")&&Vars.content!=null){
            String best="";
            for(UnitType u:Vars.content.units())if(!u.name.startsWith("dp-paint-")&&name.endsWith("-"+u.name)&&u.name.length()>best.length())best=u.name;
            if(!best.isEmpty())return best;
        }
        return name;
    }
    public static Palette palette(String id) { return palettes.find(p -> p.id().equals(id)); }

    public void generate() {
        DataPatcher probe = new DataPatcher();
        try {
            loadVanilla();
            Fi source = data.child("assets/sprites");
            source.walk(f -> {
                if (f.extension().equalsIgnoreCase("png") && !f.path().contains("/" + DIR + "/")) {
                    try { sprites.put("dp-" + f.nameWithoutExtension(), ImageIO.read(f.file())); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            Seq<ContentAsset> content = new Seq<>();
            Seq<PatchAsset> patches = new Seq<>();
            data.child("assets/content").walk(f -> {
                if (!f.extension().equalsIgnoreCase("hjson") || f.path().contains("/" + DIR + "/")) return;
                String parent = f.parent().name();
                ContentType type = switch(parent) {
                    case "units" -> ContentType.unit; case "blocks" -> ContentType.block;
                    case "items" -> ContentType.item; case "liquids" -> ContentType.liquid;
                    default -> null;
                };
                if (type != null) content.add(new ContentAsset(f.nameWithoutExtension(), type, f.readString()));
            });
            data.child("assets/patches").walk(f -> {
                if (f.extension().equalsIgnoreCase("hjson") && !f.name().startsWith("zzzz-colouring")) patches.add(new PatchAsset(f.readString()));
            });
            probe.apply(patches, content, false);
            Seq<UnitType> units = Vars.content.units().select(u -> !UnitPatchManager.isHiddenUnit(u) && u.playerControllable && !u.name.startsWith("dp-paint-"));
            palettes.clear(); originals.clear(); paletteAliases.clear(); schemeDistances.clear();
            // A candidate is a complete controllable-unit family scheme, never a turret,
            // internal projectile, or a new palette per component sprite.
            for (UnitType unit : units) addFamilyPalette(unit);
            Seq<String> names = unitSpriteNames(units); names.sort();
            int budgetCount = (int)Math.min(MAX_PALETTES, BULK_RGBA_BUDGET_BYTES / Math.max(1,bulkPixelsPerPalette*4));
            if(budgetCount<1)throw new IllegalStateException("One unit-family texture set exceeds the colouring memory budget");
            consolidatePalettes(budgetCount);
            palettes.sort(Comparator.comparing(Palette::id));
            Log.info("[Colouring] @ distinct whole-unit schemes (hard cap @); @ MiB bulk RGBA, @ MiB conservative packing estimate.",
                palettes.size,MAX_PALETTES,String.format(Locale.ROOT,"%.1f",bulkPixelsPerPalette*4.0*palettes.size/1048576),
                String.format(Locale.ROOT,"%.1f",bulkPixelsPerPalette*4.0*palettes.size*1.9/1048576));
            if (palettes.isEmpty()) throw new IllegalStateException("No palettes extracted");
            indexConstants(Fx.class); indexConstants(Sounds.class); indexConstants(mindustry.entities.UnitSorts.class); indexConstants(arc.math.Interp.class); indexConstants(mindustry.entities.part.DrawPart.PartProgress.class);
            Fi liveImages = data.child("assets/sprites/" + DIR);
            Fi target = data.child("colouring-cache/staging/images");
            Fi unitDir = data.child("colouring-cache/staging/units");
            unitDir.deleteDirectory(); unitDir.mkdirs();
            // A source/content digest invalidates every recoloured output when inputs change.
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (Palette p : palettes) {
                hash.update(p.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for(int c:p.ramp()){hash.update((byte)(c>>>16));hash.update((byte)(c>>>8));hash.update((byte)c);}
            }
            for (String name : names) {
                hash.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                BufferedImage image = sprites.get(name);
                for (int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                    int c=image.getRGB(x,y); hash.update((byte)(c>>>24));hash.update((byte)(c>>>16));hash.update((byte)(c>>>8));hash.update((byte)c);
                }
            }
            String digest = HexFormat.of().formatHex(hash.digest()) + "-whole-unit-schemes-v4";
            Fi stamp = data.child("colouring-cache/digest");
            boolean cached = stamp.exists() && stamp.readString().equals(digest);
            if (cached) target=liveImages;
            else { target.deleteDirectory(); target.mkdirs(); }
            for (String name : sprites.keys().toSeq()) {
                String destination = null;
                if (name.startsWith("payload-conveyor")) destination = "colouring-station" + name.substring("payload-conveyor".length());
                if (name.startsWith("multiplicative-reconstructor")) destination = "colouring-station-2" + name.substring("multiplicative-reconstructor".length());
                if (destination != null) ImageIO.write(sprites.get(name), "png", target.child(destination + ".png").file());
            }
            Fi previews=data.child("colouring-cache/staging/previews");
            previews.deleteDirectory();previews.mkdirs();
            int limit = Integer.getInteger("coolplugin.colouring.testPaletteLimit", Integer.MAX_VALUE);
            int count = 0;
            for (Palette p : palettes) {
                if (count++ >= limit) break;
                Log.info("[Colouring] Generating palette @/@: @ (@ source sprites)", count, palettes.size, p.id(), names.size);
                for (String name : names) {
                    Fi out = target.child(p.spritePrefix() + name + ".png");
                    if (!cached || !out.exists()) ImageIO.write(recolour(sprites.get(name), p.ramp()), "png", out.file());
                }
                // Paint can artwork is generated from the same fixed palette, not a UI mockup.
                Fi can = target.child("paint-can-" + p.id() + ".png");
                ImageIO.write(paintCan(p.ramp()), "png", can.file());
                for (UnitType u : units) {
                    String name = p.spritePrefix() + u.name;
                    Jval json = unitJson(u, p);
                    unitDir.child(name + ".hjson").writeString(json.toString(Jval.Jformat.formatted));
                    originals.put("dp-" + name, u.name);
                    // Full menu icons stay on the server and are streamed only when selected.
                    for(String icon:new String[]{"unit-"+u.name+"-full",u.name+"-full",u.name}){
                        if(sprites.containsKey(icon)){
                            ImageIO.write(recolour(sprites.get(icon),p.ramp()),"png",previews.child(name+".png").file());break;
                        }
                    }

                }
            }
            writeUpgradePatch();
            writeStations(units);
            writeManifest();
            // Publish only after every requested palette and definition was generated successfully.
            if (!cached) replaceDirectory(target,liveImages);
            replaceDirectory(previews,data.child("colouring-cache/previews"));
            replaceDirectory(unitDir,data.child("assets/content/units/"+DIR));
            replaceDirectory(data.child("colouring-cache/staging/blocks"),data.child("assets/content/blocks/"+DIR));
            data.child("assets/patches/zzzz-colouring-upgrades.hjson").writeString(data.child("colouring-cache/staging/upgrades.hjson").readString());
            if (limit == Integer.MAX_VALUE) stamp.writeString(digest);
            Log.info("[Colouring] @ palettes, @ controllable base units, @ sprite sources. Generated assets are outside the JAR.", palettes.size, units.size, names.size);
        } catch (Exception e) {
            throw new IllegalStateException("Colouring datapatch generation failed; no incomplete colouring system will be started", e);
        } finally { probe.unapply(false); sprites.clear(); }
    }

    private static void replaceDirectory(Fi source,Fi target) {
        target.deleteDirectory();target.parent().mkdirs();source.moveTo(target);
    }

    private void loadVanilla() throws Exception {
        Fi jar = data.child("colouring-cache/Mindustry-v160.7.jar");
        String supplied = System.getProperty("coolplugin.colouring.vanillaJar", "");
        if (!supplied.isEmpty()) jar = new Fi(supplied);
        if (!jar.exists()) {
            jar.parent().mkdirs();
            Fi temp = jar.sibling(jar.name() + ".part");
            Log.info("[Colouring] Downloading pinned vanilla v160.7 sprite source (first run only).");
            var request=java.net.http.HttpRequest.newBuilder(URI.create("https://github.com/Anuken/Mindustry/releases/download/v160.7/Mindustry.jar")).build();
            var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofFile(temp.file().toPath()));
            if(response.statusCode()!=200) throw new java.io.IOException("Vanilla sprite download HTTP " + response.statusCode());
            temp.moveTo(jar);
        }
        Fi extracted = data.child("colouring-cache/atlas"); extracted.mkdirs();
        try (ZipFile zip = new ZipFile(jar.file())) {
            var it=zip.entries();
            while(it.hasMoreElements()) {
                var entry=it.nextElement();
                if(entry.isDirectory() || !entry.getName().startsWith("sprites/") || !(entry.getName().endsWith(".aatls") || entry.getName().endsWith(".png"))) continue;
                Fi out=extracted.child(entry.getName().substring("sprites/".length()));
                out.parent().mkdirs();
                try(var in=zip.getInputStream(entry)) { Files.copy(in,out.file().toPath(),StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Fi atlas=extracted.child("sprites.aatls");
        TextureAtlasData metadata=new TextureAtlasData(atlas,extracted,false);
        // Process a page at a time instead of retaining all full-sized atlas pages.
        Set<String> pages=new HashSet<>();
        for(var page:metadata.getPages())pages.add(page.textureFile.name());
        extracted.walk(f->{
            if(f.extension().equals("png")&&!pages.contains(f.name())) {
                try {BufferedImage img=ImageIO.read(f.file());if(img!=null && !sprites.containsKey(f.nameWithoutExtension()))sprites.put(f.nameWithoutExtension(),img);}
                catch(Exception e){throw new RuntimeException(e);}
            }
        });
        for(var page:metadata.getPages()) {
            BufferedImage image=ImageIO.read(page.textureFile.file());
            for(var r:metadata.getRegions()) if(r.page==page) {
                int rw=Math.max(r.width,r.originalWidth), rh=Math.max(r.height,r.originalHeight);
                BufferedImage region=new BufferedImage(rw,rh,BufferedImage.TYPE_INT_ARGB);
                int oy=rh-r.height-Math.round(r.offsetY), ox=Math.round(r.offsetX);
                for(int y=0;y<r.height;y++) for(int x=0;x<r.width;x++) {
                    int px=r.rotate ? r.left+y : r.left+x;
                    int py=r.rotate ? r.top+r.width-1-x : r.top+y;
                    if(ox+x>=0 && oy+y>=0 && ox+x<region.getWidth() && oy+y<region.getHeight()) region.setRGB(ox+x,oy+y,image.getRGB(px,py));
                }
                sprites.put(r.name,region);
            }
            image.flush();
        }
    }

    /** Match source families and explicit weapon/part sprite references, never UI/terrain families. */
    private Seq<String> unitSpriteNames(Seq<UnitType> units) throws Exception {
        ObjectSet<String> families=new ObjectSet<>();
        for(UnitType unit:units){
            families.add(unit.name);families.add("unit-"+unit.name);
            collectSpriteFamilies(unit,unit.name,0,new IdentityHashMap<>(),families);
        }
        for(String shared:new String[]{"power-cell","ring-item","minelaser","minelaser-end"})if(sprites.containsKey(shared))families.add(shared);
        Seq<String> out=new Seq<>();long pixels=0;
        for(String name:sprites.keys()) {
            if(isMenuIconOrWreck(name))continue;
            boolean include=false;
            for(String family:families)if(!family.isEmpty() && (name.equals(family)||name.startsWith(family+"-"))){include=true;break;}
            if(include){out.add(name);BufferedImage image=sprites.get(name);pixels+=(long)image.getWidth()*image.getHeight();}
        }
        bulkPixelsPerPalette=pixels;
        double mib=pixels*4.0/1048576.0;
        Log.info("[Colouring] Shared active-unit sprite set: @ regions, @ MiB RGBA per scheme (no wreck or menu-icon copies).",out.size,String.format(Locale.ROOT,"%.1f",mib));
        return out;
    }
    private void collectSpriteFamilies(Object object,String owner,int depth,IdentityHashMap<Object,Boolean> seen,ObjectSet<String> families)throws Exception{
        if(object==null||depth>9||seen.put(object,true)!=null)return;
        if(object instanceof mindustry.type.Weapon weapon && !weapon.name.isEmpty()) {owner=weapon.name;families.add(owner);}
        for(Field field:object.getClass().getFields()){
            if(Modifier.isStatic(field.getModifiers())||Modifier.isTransient(field.getModifiers()))continue;
            Object value=field.get(object);
            if(value instanceof String str){
                if(sprites.containsKey(str))families.add(str);
                if(field.getName().equals("suffix")&&!str.isEmpty())families.add(owner+str);
            }else if(value instanceof Seq<?> seq){
                for(Object entry:seq)collectSpriteChild(entry,owner,depth,seen,families);
            }else collectSpriteChild(value,owner,depth,seen,families);
        }
    }
    private void collectSpriteChild(Object value,String owner,int depth,IdentityHashMap<Object,Boolean> seen,ObjectSet<String> families)throws Exception{
        if(value==null||value instanceof MappableContent)return;
        String name=value.getClass().getName();
        if(value instanceof mindustry.type.Weapon || value instanceof mindustry.entities.bullet.BulletType || value instanceof mindustry.entities.part.DrawPart)
            collectSpriteFamilies(value,owner,depth+1,seen,families);
    }

    private static boolean isMenuIconOrWreck(String name){
        return name.endsWith("-full")||name.endsWith("-ui")||name.endsWith("-icon")||name.matches(".*-wreck[0-9]*$");
    }
    private void addFamilyPalette(UnitType unit)throws Exception{
        ObjectSet<String> family=new ObjectSet<>();family.add(unit.name);
        collectSpriteFamilies(unit,unit.name,0,new IdentityHashMap<>(),family);
        Map<Integer,Integer> histogram=new HashMap<>();
        for(String name:sprites.keys()){
            if(isMenuIconOrWreck(name)||name.endsWith("-cell")||name.contains("-cell-"))continue;
            boolean belongs=false;
            for(String root:family)if(!root.isEmpty()&&(name.equals(root)||name.startsWith(root+"-"))){belongs=true;break;}
            if(!belongs)continue;
            BufferedImage img=sprites.get(name);
            for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++){
                int c=img.getRGB(x,y);if((c>>>24)<240)continue;
                histogram.merge(c&0xffffff,1,Integer::sum);
            }
        }
        int[] colours=histogram.entrySet().stream().sorted(Map.Entry.<Integer,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(24).map(Map.Entry::getKey).sorted(Comparator.comparingDouble(ColouringAssets::luminance)).mapToInt(Integer::intValue).toArray();
        if(colours.length>1)palettes.add(new Palette("unit-"+unit.name,unit.localizedName+" scheme",unit.isPatchContent()?2:1,colours));
    }
    private static double colourDistance(int a,int b){
        double r=((a>>16)&255)-((b>>16)&255),g=((a>>8)&255)-((b>>8)&255),bl=(a&255)-(b&255);
        return Math.sqrt(0.2126*r*r+0.7152*g*g+0.0722*bl*bl);
    }
    private static double schemeDistance(Palette a,Palette b){
        String key=a.id().compareTo(b.id())<0?a.id()+"|"+b.id():b.id()+"|"+a.id();
        Double cached=schemeDistances.get(key);if(cached!=null)return cached;
        double sum=0;
        for(int c:a.ramp()){double nearest=Double.POSITIVE_INFINITY;for(int d:b.ramp())nearest=Math.min(nearest,colourDistance(c,d));sum+=nearest;}
        for(int c:b.ramp()){double nearest=Double.POSITIVE_INFINITY;for(int d:a.ramp())nearest=Math.min(nearest,colourDistance(c,d));sum+=nearest;}
        double result=sum/(a.ramp().length+b.ramp().length);schemeDistances.put(key,result);return result;
    }
    private void consolidatePalettes(int cap){
        List<List<Palette>> groups=new ArrayList<>();
        palettes.sort(Comparator.comparing(Palette::id));
        for(Palette p:palettes){List<Palette> group=new ArrayList<>();group.add(p);groups.add(group);}
        while(groups.size()>1){
            int ai=-1,bi=-1;double closest=Double.POSITIVE_INFINITY;
            for(int a=0;a<groups.size();a++)for(int b=a+1;b<groups.size();b++){
                if(groups.get(a).get(0).tier()!=groups.get(b).get(0).tier())continue;
                double distance=schemeDistance(representative(groups.get(a)),representative(groups.get(b)));
                if(distance<closest){closest=distance;ai=a;bi=b;}
            }
            if(ai<0 || (groups.size()<=cap&&closest>2.0))break;
            groups.get(ai).addAll(groups.remove(bi));
        }
        if(groups.size()>cap)throw new IllegalStateException("Palette budget cannot fit both station tiers");
        palettes.clear();
        for(List<Palette> group:groups){
            Palette chosen=representative(group);palettes.add(chosen);
            for(Palette member:group)paletteAliases.put(member.id(),chosen.id());
        }
    }
    private static Palette representative(List<Palette> group){
        Palette best=group.get(0);double score=Double.POSITIVE_INFINITY;
        for(Palette candidate:group){double sum=0;for(Palette member:group)sum+=schemeDistance(candidate,member);
            if(sum<score){score=sum;best=candidate;}}
        return best;
    }
    private static double luminance(int c) { return (((c>>16)&255)*0.2126+((c>>8)&255)*0.7152+(c&255)*0.0722)/255.0; }
    public static BufferedImage recolour(BufferedImage src,int[] ramp) {
        BufferedImage out=new BufferedImage(src.getWidth(),src.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<src.getHeight();y++) for(int x=0;x<src.getWidth();x++) {
            int c=src.getRGB(x,y), a=c>>>24;
            int index=Math.min(ramp.length-1,(int)Math.round(luminance(c)*(ramp.length-1)));
            out.setRGB(x,y,a==0?0:(a<<24)|ramp[index]);
        }
        return out;
    }
    private static BufferedImage paintCan(int[] ramp) {
        BufferedImage out=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);
        for(int y=3;y<22;y++) for(int x=4;x<20;x++) {
            if((y==3||y==21)&&(x<6||x>17))continue;
            int col=y<6||y>18?0x5b6574:ramp[Math.min(ramp.length-1,ramp.length*2/3)];
            if(x<6||x>17)col=0x303743;
            out.setRGB(x,y,0xff000000|col);
        }
        return out;
    }
    private void indexConstants(Class<?> type) throws Exception {
        for(Field f:type.getFields()) if(Modifier.isStatic(f.getModifiers())) { Object o=f.get(null); if(o!=null)constants.put(o,f.getName()); }
    }
    private Jval unitJson(UnitType u,Palette p) throws Exception {
        String template=unitTemplates.get(u.name);
        if(template==null){
            Jval definition=encodeFields(u,0,new IdentityHashMap<>());
            if(u.engineSize>0 && definition.get("engines")!=null && definition.get("engines").asArray().size>0)
                definition.get("engines").asArray().remove(definition.get("engines").asArray().size-1);
            Class<?> cls=u.getClass();while(cls.isAnonymousClass())cls=cls.getSuperclass();
            if(cls!=UnitType.class)definition.add("template",Jval.valueOf(cls.getSimpleName()));
            template=definition.toString();unitTemplates.put(u.name,template);
        }
        Jval j=Jval.read(template);
        Class<?> entityClass=u.constructor.get().getClass();
        while(entityClass.getSimpleName().contains("Legacy"))entityClass=entityClass.getSuperclass();
        String kind=switch(entityClass.getSimpleName()) {
            case "MechUnit"->"mech";case "LegsUnit"->"legs";case "UnitWaterMove"->"naval";
            case "PayloadUnit"->"payload";case "BuildingTetherPayloadUnit"->"tether";case "TankUnit"->"tank";case "ElevationMoveUnit"->"hover";
            case "CrawlUnit"->"crawl"; default->"flying";
        };
        j.add("type",Jval.valueOf(kind));
        j.add("hidden",Jval.valueOf(true));j.add("internal",Jval.valueOf(true));
        j.add("generateIcons",Jval.valueOf(false));
        j.add("fullOverride",Jval.valueOf("dp-"+p.spritePrefix()+u.name));
        remapNames(j,p);
        j.add("name",Jval.valueOf(u.localizedName));j.add("localizedName",Jval.valueOf(u.localizedName));
        return j;
    }
    private void remapNames(Jval j,Palette p) {
        if(j.isObject())for(var entry:j.asObject()) {
            if((entry.key.equals("name") || entry.key.equals("sprite") || entry.key.endsWith("Sprite") || entry.key.equals("fullOverride")) && entry.value.isString() && sprites.containsKey(entry.value.asString()))
                entry.value=Jval.valueOf("dp-"+p.spritePrefix()+entry.value.asString());
            else remapNames(entry.value,p);
        }else if(j.isArray())for(Jval child:j.asArray())remapNames(child,p);
    }
    private static final Set<String> SKIP=Set.of("id","minfo","name","localizedName","description","details","stats","bars","uiIcon","fullIcon","commands","stances","defaultCommand","controller","aiController","constructor","researchRequirements","dependencies","wreckRegions","envDisabled");
    private Object defaultsFor(Object object) {
        Class<?> cls=object.getClass();while(cls.isAnonymousClass())cls=cls.getSuperclass();
        if(defaults.containsKey(cls))return defaults.get(cls);
        Object value=null;
        try {
            if(object instanceof UnitType) {
                value=cls.getConstructor(String.class).newInstance("__colouring_serialization_default");
                Vars.content.remove((UnitType)value);
            } else value=cls.getDeclaredConstructor().newInstance();
        }catch(ReflectiveOperationException ignored){}
        defaults.put(cls,value);return value;
    }
    private static boolean sameDefault(Object a,Object b) {
        if(a==b)return true;
        if(a==null||b==null)return false;
        if(a instanceof Seq<?> x && b instanceof Seq<?> y)return x.isEmpty()&&y.isEmpty();
        if(a instanceof ObjectSet<?> x && b instanceof ObjectSet<?> y)return x.isEmpty()&&y.isEmpty();
        if(a.getClass().isArray()&&b.getClass().isArray())return Array.getLength(a)==0&&Array.getLength(b)==0;
        return a.equals(b);
    }

    private Jval encodeFields(Object o,int depth,IdentityHashMap<Object,Boolean> seen) throws Exception {
        Jval result=Jval.newObject();
        if(depth>12 || seen.put(o,true)!=null)return result;
        Object defaultObject=defaultsFor(o);
        for(Field f:o.getClass().getFields()) {
            int m=f.getModifiers();
            if(Modifier.isStatic(m)||Modifier.isTransient(m)||SKIP.contains(f.getName()))continue;
            Object value=f.get(o);
            if(defaultObject!=null && f.getDeclaringClass().isInstance(defaultObject) && sameDefault(value,f.get(defaultObject)))continue;
            Jval encoded=encode(value,depth+1,seen);
            if(encoded!=null) result.add(f.getName(),encoded);
        }
        if (o instanceof mindustry.type.Weapon w) {
            result.add("name",Jval.valueOf(w.name));
            result.add("mirror",Jval.valueOf(false));
        }
        seen.remove(o); return result;
    }
    private Jval encode(Object o,int depth,IdentityHashMap<Object,Boolean> seen)throws Exception {
        if(o==null)return null;
        if(o instanceof String s)return Jval.valueOf(s);
        if(o instanceof Boolean b)return Jval.valueOf(b);
        if(o instanceof Number n)return Double.isFinite(n.doubleValue())?Jval.valueOf(n.doubleValue()):null;
        if(o instanceof Enum<?> e)return Jval.valueOf(e.name());
        if(o instanceof Color c)return Jval.valueOf(c.toString());
        if(o instanceof MappableContent c)return Jval.valueOf(c.name);
        if(constants.containsKey(o))return Jval.valueOf(constants.get(o));
        if(o instanceof Effect)return Jval.valueOf("none");
        if(o instanceof ObjectSet<?> set) {Jval a=Jval.newArray();for(Object v:set){Jval e=encode(v,depth+1,seen);if(e!=null)a.add(e);}return a;}
        if(o instanceof Seq<?> seq) {Jval a=Jval.newArray();for(Object v:seq){Jval e=encode(v,depth+1,seen);if(e!=null)a.add(e);}return a;}
        if(o.getClass().isArray()){Jval a=Jval.newArray();for(int i=0;i<Array.getLength(o);i++){Jval e=encode(Array.get(o,i),depth+1,seen);if(e!=null)a.add(e);}return a;}
        Class<?> actual=o.getClass();
        while(actual.isAnonymousClass())actual=actual.getSuperclass();
        if(actual.isSynthetic() || actual.getName().contains("$$Lambda$")) return null;
        String cls=actual.getName();
        if(!cls.startsWith("mindustry.entities.")&&!mindustry.type.Weapon.class.isAssignableFrom(actual) && !cls.startsWith("mindustry.type.UnitType$") && !cls.startsWith("arc.math.geom."))return null;
        Jval j=encodeFields(o,depth,seen);
        if(!cls.equals("mindustry.type.Weapon") && !cls.startsWith("mindustry.type.UnitType$") && !cls.startsWith("arc.math.geom."))j.add("type",Jval.valueOf(actual.getSimpleName()));
        return j;
    }
    private void writeUpgradePatch() {
        Jval patch=Jval.newObject();patch.add("name",Jval.valueOf("Colour-preserving unit upgrades"));
        for(var b:Vars.content.blocks()) if(b instanceof mindustry.world.blocks.units.Reconstructor r) {
            Jval extra=Jval.newArray();
            for(var pair:r.upgrades)for(Palette p:palettes) {
                String from=variant(pair[0].name,p),to=variant(pair[1].name,p);
                if(originals.containsKey(from)&&originals.containsKey(to)) {
                    Jval values=Jval.newArray();values.add(Jval.valueOf(from));values.add(Jval.valueOf(to));extra.add(values);
                }
            }
            if(extra.asArray().size>0)patch.add("block."+b.name+".upgrades.+",extra);
        }
        data.child("colouring-cache/staging/upgrades.hjson").writeString(patch.toString(Jval.Jformat.formatted));
    }

    private void writeStations(Seq<UnitType> units) {
        Fi dir=data.child("colouring-cache/staging/blocks");dir.mkdirs();
        dir.child("colouring-station.hjson").writeString("{\"type\":\"PayloadConveyor\",\"name\":\"Colouring Station\",\"size\":3,\"health\":650,\"payloadLimit\":3,\"moveTime\":60,\"category\":\"units\",\"buildVisibility\":\"shown\",\"requirements\":[\"copper/200\",\"lead/150\",\"silicon/100\"]}");
        Jval pairs=Jval.newArray();
        for(UnitType u:units){Jval pair=Jval.newArray();pair.add(Jval.valueOf(u.name));pair.add(Jval.valueOf(u.name));pairs.add(pair);}
        for(var entry:originals){Jval pair=Jval.newArray();pair.add(Jval.valueOf(entry.key));pair.add(Jval.valueOf(entry.key));pairs.add(pair);}
        Jval j=Jval.newObject();j.add("type",Jval.valueOf("Reconstructor"));j.add("name",Jval.valueOf("Colouring Station II"));j.add("size",Jval.valueOf(5));
        j.add("health",Jval.valueOf(1800));j.add("constructTime",Jval.valueOf(1e30));j.add("upgrades",pairs);j.add("category",Jval.valueOf("units"));j.add("buildVisibility",Jval.valueOf("shown"));
        Jval req=Jval.newArray();for(String v:new String[]{"copper/600","lead/500","silicon/400","titanium/250"})req.add(Jval.valueOf(v));j.add("requirements",req);
        dir.child("colouring-station-2.hjson").writeString(j.toString());
    }
    private void writeManifest() {
        Jval root=Jval.newObject(),list=Jval.newArray();
        for(Palette p:palettes){Jval j=Jval.newObject();j.add("id",Jval.valueOf(p.id()));j.add("name",Jval.valueOf(p.title()));j.add("tier",Jval.valueOf(p.tier()));
            Jval colours=Jval.newArray();for(int colour:p.ramp())colours.add(Jval.valueOf(colour));j.add("colours",colours);
            list.add(j);}
        root.add("palettes",list);
        root.add("bulkRgbaBytes",Jval.valueOf(bulkPixelsPerPalette*4*palettes.size));
        root.add("maxPalettes",Jval.valueOf(MAX_PALETTES));
        data.child("colouring-cache/manifest.json").writeString(root.toString(Jval.Jformat.formatted));
    }
}
