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
    public static final ObjectMap<String,arc.math.geom.Vec2> mountSizes=new ObjectMap<>();
    public static final ObjectMap<String, String> originals = new ObjectMap<>();
    public static final int MAX_PALETTES = 20;
    public static final long BULK_RGBA_BUDGET_BYTES = 144L * 1024 * 1024;
    public static final ObjectMap<String,String> paletteAliases = new ObjectMap<>();
    private long bulkPixelsPerPalette;
    private final ObjectMap<String,int[]> sourceRamps=new ObjectMap<>();
    private final ObjectMap<String,int[]> hostRamps=new ObjectMap<>();
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
            palettes.clear(); originals.clear(); paletteAliases.clear();
            // A candidate is a complete controllable-unit family scheme, never a turret,
            // internal projectile, or a new palette per component sprite.
            ManualUnitPalettes.load(data);
            Seq<String> names = unitSpriteNames(units); names.sort();
            indexSourceRamps(units,names);
            int budgetCount = (int)Math.min(MAX_PALETTES, BULK_RGBA_BUDGET_BYTES / Math.max(1,bulkPixelsPerPalette*4));
            if(budgetCount<1)throw new IllegalStateException("One unit-family texture set exceeds the colouring memory budget");
            if(palettes.size>budgetCount)throw new IllegalStateException("Manual palette list exceeds sprite budget: "+palettes.size+" > "+budgetCount);
            palettes.sort(Comparator.comparing(Palette::id));
            generateItemMounts(units);
            Log.info("[Colouring] @ distinct whole-unit schemes (hard cap @); @ MiB bulk RGBA, @ MiB conservative packing estimate.",
                palettes.size,MAX_PALETTES,String.format(Locale.ROOT,"%.1f",bulkPixelsPerPalette*4.0*palettes.size/1048576),
                String.format(Locale.ROOT,"%.1f",bulkPixelsPerPalette*4.0*palettes.size*1.9/1048576));
            if (palettes.isEmpty()) throw new IllegalStateException("Manual palette list is empty");
            indexConstants(Fx.class); indexConstants(Sounds.class); indexConstants(mindustry.entities.UnitSorts.class); indexConstants(arc.math.Interp.class); indexConstants(mindustry.entities.part.DrawPart.PartProgress.class);
            Fi liveImages = data.child("assets/sprites/" + DIR);
            Fi target = data.child("colouring-cache/staging/images");
            Fi unitDir = data.child("colouring-cache/staging/units");
            unitDir.deleteDirectory(); unitDir.mkdirs();
            // A source/content digest invalidates every recoloured output when inputs change.
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(data.child("alphystuff/unit-palettes.hjson").readBytes());
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
            String digest = HexFormat.of().formatHex(hash.digest()) + "-manual-exact-palettes-weapons-v14";
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
            writeStationPlaceholders(target);
            Fi previews=data.child("colouring-cache/staging/previews");
            previews.deleteDirectory();previews.mkdirs();
            int limit = Integer.getInteger("coolplugin.colouring.testPaletteLimit", Integer.MAX_VALUE);
            int count = 0;
            for (Palette p : palettes) {
                if (count++ >= limit) break;
                Log.info("[Colouring] Generating palette @/@: @ (@ source sprites)", count, palettes.size, p.id(), names.size);
                for (String name : names) {
                    Fi out = target.child(p.spritePrefix() + name + ".png");
                    if (!cached || !out.exists()) ImageIO.write(isMask(name)?sprites.get(name):recolour(sprites.get(name), p.ramp(),sourceRamps.get(name)), "png", out.file());
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
                            BufferedImage painted=recolour(sprites.get(icon),p.ramp(),hostRamps.get(u.name));
                            ImageIO.write(painted,"png",previews.child(name+".png").file());
                            ImageIO.write(smallPreview(painted),"png",target.child("colour-preview-dp-"+name+".png").file());break;
                        }
                    }

                }
            }
            // Cache unchanged full icons too: empty inventory and already-painted entry use
            // the same reliable streamed-image path as selected paints.
            for(UnitType u:units)for(String icon:new String[]{"unit-"+u.name+"-full",u.name+"-full",u.name}){
                if(sprites.containsKey(icon)){
                    ImageIO.write(sprites.get(icon),"png",previews.child("base-"+u.name+".png").file());
                    ImageIO.write(smallPreview(sprites.get(icon)),"png",target.child("colour-preview-"+u.name+".png").file());break;}
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

    private static BufferedImage smallPreview(BufferedImage source){
        float scale=Math.min(1f,80f/Math.max(source.getWidth(),source.getHeight()));
        BufferedImage out=new BufferedImage(Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),BufferedImage.TYPE_INT_ARGB);
        var graphics=out.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(source,0,0,out.getWidth(),out.getHeight(),null);graphics.dispose();return out;
    }
    private static boolean outlineColour(int rgb){
        int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
        return Math.max(r,Math.max(g,b))<=82 && Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=24;
    }
    public static boolean hasOutline(BufferedImage image){
        if(image==null)return false;
        int edge=0,dark=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int c=image.getRGB(x,y);if((c>>>24)<200)continue;
            boolean boundary=x==0||y==0||x==image.getWidth()-1||y==image.getHeight()-1;
            if(!boundary)boundary=(image.getRGB(x-1,y)>>>24)<100||(image.getRGB(x+1,y)>>>24)<100||(image.getRGB(x,y-1)>>>24)<100||(image.getRGB(x,y+1)>>>24)<100;
            if(boundary){edge++;if(outlineColour(c))dark++;}
        }
        return edge>0 && dark>=edge*0.55f;
    }
    private void indexSourceRamps(Seq<UnitType> units,Seq<String> names)throws Exception{
        sourceRamps.clear();hostRamps.clear();
        for(UnitType unit:units){
            int[] ramp=ManualUnitPalettes.source(unit.name);if(ramp==null)continue;
            hostRamps.put(unit.name,ramp);
            ObjectSet<String> family=new ObjectSet<>();family.add(unit.name);
            collectSpriteFamilies(unit,unit.name,0,new IdentityHashMap<>(),family);
            for(String name:names)for(String root:family)if(name.equals(root)||name.startsWith(root+"-")){
                sourceRamps.put(name,ramp);break;
            }
        }
        for(String name:names){
            if(name.equals("large-weapon")||name.startsWith("large-weapon-"))sourceRamps.put(name,ManualUnitPalettes.source("dagger"));
            if(name.equals("small-weapon")||name.startsWith("small-weapon-"))sourceRamps.put(name,ManualUnitPalettes.source("alpha"));
        }
    }
    public static BufferedImage recolour(BufferedImage source,int[] target,int[] original){
        BufferedImage out=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        Map<Integer,Integer> lookup=new HashMap<>();
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++){
            int c=source.getRGB(x,y),rgb=c&0xffffff;
            if((c>>>24)==0){out.setRGB(x,y,c);continue;}
            int mapped=lookup.computeIfAbsent(rgb,key->ManualUnitPalettes.map(key,original,target));
            out.setRGB(x,y,(c&0xff000000)|mapped);
        }return out;
    }

    public static final ObjectMap<String,String> mountSources=new ObjectMap<>();
    private BufferedImage outlinedWeapon(String name){
        BufferedImage source=sprites.get(name);if(source==null)return null;
        if(hasOutline(source))return source;
        BufferedImage outline=sprites.get(name+"-outline");
        if(outline==null){
            outline=new BufferedImage(source.getWidth()+2,source.getHeight()+2,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)if((source.getRGB(x,y)>>>24)>0)
                for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++)outline.setRGB(x+1+ox,y+1+oy,0xff404049);
        }
        BufferedImage combined=new BufferedImage(Math.max(source.getWidth(),outline.getWidth()),Math.max(source.getHeight(),outline.getHeight()),BufferedImage.TYPE_INT_ARGB);
        var graphics=combined.createGraphics();
        graphics.drawImage(outline,(combined.getWidth()-outline.getWidth())/2,(combined.getHeight()-outline.getHeight())/2,null);
        graphics.drawImage(source,(combined.getWidth()-source.getWidth())/2,(combined.getHeight()-source.getHeight())/2,null);graphics.dispose();
        if(hasOutline(combined))return combined;
        BufferedImage padded=new BufferedImage(combined.getWidth()+2,combined.getHeight()+2,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<combined.getHeight();y++)for(int x=0;x<combined.getWidth();x++)if((combined.getRGB(x,y)>>>24)>0)
            for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++)padded.setRGB(x+1+ox,y+1+oy,0xff404049);
        var pen=padded.createGraphics();pen.drawImage(combined,1,1,null);pen.dispose();return padded;
    }

    private void generateItemMounts(Seq<UnitType> units) throws Exception {
        mountSizes.clear();mountSources.clear();
        Fi target=data.child("assets/sprites/item-mounts");target.deleteDirectory();target.mkdirs();
        var catalog=ShopConfigIO.loadOrCreate(data.child("alphystuff/shop.hjson"));
        // Use the same catalog path as PlayerItemSystem.
        for(var item:catalog.items){
            if(item.attachMode!=ShopItemDef.AttachMode.MOUNT && item.attachMode!=ShopItemDef.AttachMode.SUBWEAPON)continue;
            String family=switch(item.subweaponPreset.toLowerCase(java.util.Locale.ROOT)){
                case "reign","precept","quasar","scepter","locus","vanquish","spiroct","anthicus" -> item.subweaponPreset.toLowerCase(java.util.Locale.ROOT);
                case "mortar","artillery","blast" -> "elude";
                case "pierce","lance","phase" -> "cleroi";
                case "incendiary","pyratite","gatling" -> "cleroi";
                case "blaster","homing","plastanium" -> "elude";
                default -> "cleroi";
            };
            if(item.mountWeaponSprite.isBlank()){
                String[] varied=switch(item.tier){
                    case WORKBENCH->new String[]{"dagger","mace","pulsar"};
                    case BAZAAR->new String[]{"fortress","precept","locus","mega"};
                    case CURSED_ALTAR->new String[]{"reign","vanquish","scepter","anthicus"};
                };
                family=varied[Math.floorMod(item.id.hashCode(),varied.length)];
            }
            UnitType unit=Vars.content.unit(family);
            BufferedImage source=null;String sourceName="";
            if(unit!=null)for(var weapon:unit.weapons){
                source=outlinedWeapon(weapon.name);
                if(source!=null){sourceName=weapon.name;break;}
            }
            boolean overrideAccepted=false;
            if(!item.mountWeaponSprite.isBlank()){source=outlinedWeapon(item.mountWeaponSprite);overrideAccepted=source!=null;if(overrideAccepted)sourceName=item.mountWeaponSprite;}
            if(source==null){source=outlinedWeapon("cleroi-weapon");family="cleroi";sourceName="cleroi-weapon";}
            if(source==null)throw new IllegalStateException("No outlined weapon source for "+item.id);
            // Prefer a real outlined weapon; small unoutlined barrels are not suitable mounts.
            if(source==null)throw new IllegalStateException("No outlined weapon sprite for "+item.id);
            mountSources.put(item.id,sourceName);
            mountSizes.put(item.id,new arc.math.geom.Vec2(source.getWidth()*.25f,source.getHeight()*.25f));
            int[] weaponRamp=overrideAccepted?sourceRamps.get(item.mountWeaponSprite,ManualUnitPalettes.source(family)):ManualUnitPalettes.source(family);
            ImageIO.write(source,"png",target.child("item-mount-"+item.id+".png").file());
            for(UnitType host:units){
                int[] nativeRamp=hostRamps.get(host.name);
                if(nativeRamp!=null)ImageIO.write(recolour(source,nativeRamp,weaponRamp),"png",
                    target.child("item-mount-"+item.id+"-host-"+host.name+".png").file());
            }
            for(Palette palette:palettes)ImageIO.write(recolour(source,palette.ramp(),weaponRamp),"png",
                target.child("item-mount-"+item.id+"-scheme-"+palette.id()+".png").file());
        }
        Fi effects=data.child("assets/content/statuses/item-mounts");effects.deleteDirectory();effects.mkdirs();
        for(String domain:new String[]{"ground","air"})for(String kind:new String[]{"flash","smoke"}){
            boolean smoke=kind.equals("smoke");
            String json="""
                {show:false,effect:{type:"ParticleEffect",layer:%s,lifetime:%s,particles:%s,
                cone:24,length:%s,sizeFrom:%s,sizeTo:0,colorFrom:"%s",colorTo:"ffffff00"}}
                """.formatted(domain.equals("air")?116:61,smoke?20:8,smoke?4:6,smoke?9:15,smoke?2:1.5,smoke?"a0a0a0aa":"fff1b8");
            effects.child("mount-"+domain+"-"+kind+".hjson").writeString(json);
        }
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
    private static boolean isMask(String name){
        return name.endsWith("-cell")||name.contains("-cell-")||name.endsWith("-heat")||name.endsWith("-glow")||name.endsWith("-light");
    }
    public static BufferedImage recolour(BufferedImage src,int[] ramp){
        return recolour(src,ramp,new int[]{0x404049,0x404049,0x565666,0x6e7080,0x989aa4,0xb0bac0,0xa8527d,0xa8527d,0xf98dc9,0xf98dc9});
    }
    private static BufferedImage paintCan(int[] ramp) {
        BufferedImage out=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);
        for(int y=3;y<22;y++) for(int x=4;x<20;x++) {
            if((y==3||y==21)&&(x<6||x>17))continue;
            int col=y<6||y>18?0x5b6574:ramp[ramp.length==10?8:Math.min(ramp.length-1,ramp.length*2/3)];
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
            // Runtime reflection cannot round-trip custom Sound, Effect or PartProgress
            // instances. Keep authored nested definitions, including their parser defaults.
            String stem=u.name.startsWith("dp-")?u.name.substring(3):u.name;
            Fi authored=data.child("assets/content/units/"+stem+".hjson");
            if(authored.exists()){
                Jval source=Jval.read(authored.readString().replaceAll(
                    "(?m)([A-Za-z]*[Cc]olor[A-Za-z]*\\s*:\\s*)([0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?)(?=\\s*(?:[,}\\r\\n]|$))", "$1\"$2\""));
                for(var field:source.asObject()){
                    String key=field.key;
                    if(key.equals("weapons")||key.equals("parts")||key.equals("abilities")
                        ||key.endsWith("Effect")||key.endsWith("Sound"))
                        definition.add(key,field.value);
                }
                qualifySpriteNames(definition);
            }
            if(u.engineSize>0 && definition.get("engines")!=null && definition.get("engines").asArray().size>0)
                definition.get("engines").asArray().remove(definition.get("engines").asArray().size-1);
            Class<?> cls=u.getClass();while(cls.isAnonymousClass())cls=cls.getSuperclass();
            if(cls!=UnitType.class)definition.add("template",Jval.valueOf(cls.getSimpleName()));
            template=definition.toString(Jval.Jformat.formatted);unitTemplates.put(u.name,template);
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
        j.add("outlineColor",Jval.valueOf(String.format(Locale.ROOT,"%06x",p.ramp()[0]&0xffffff)));
        j.add("fullOverride",Jval.valueOf("dp-"+p.spritePrefix()+u.name));
        remapNames(j,p);
        Jval weapons=j.get("weapons");
        if(weapons!=null)for(Jval weapon:weapons.asArray()){
            String name=weapon.getString("name","");
            if(name.startsWith("dp-"))weapon.add("name",Jval.valueOf(name.substring(3)));
        }
        j.add("name",Jval.valueOf(u.localizedName));j.add("localizedName",Jval.valueOf(u.localizedName));
        return j;
    }
    private void qualifySpriteNames(Jval j){
        if(j.isObject())for(var entry:j.asObject()){
            if((entry.key.equals("name")||entry.key.equals("sprite")||entry.key.endsWith("Sprite"))
                &&entry.value.isString()&&!sprites.containsKey(entry.value.asString())
                &&sprites.containsKey("dp-"+entry.value.asString()))
                j.add(entry.key,Jval.valueOf("dp-"+entry.value.asString()));
            else qualifySpriteNames(entry.value);
        }else if(j.isArray())for(Jval child:j.asArray())qualifySpriteNames(child);
    }

    private void remapNames(Jval j,Palette p) {
        if(j.isObject())for(var entry:j.asObject()) {
            if((entry.key.equals("name") || entry.key.equals("sprite") || entry.key.endsWith("Sprite") || entry.key.equals("fullOverride")) && entry.value.isString() && sprites.containsKey(entry.value.asString()))
                j.add(entry.key,Jval.valueOf("dp-"+p.spritePrefix()+entry.value.asString()));
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

    /** Explicit vanilla-based placeholder layers and icons; generated outside the plugin JAR. */
    private void writeStationPlaceholders(Fi target) throws java.io.IOException {
        for(int tier=1;tier<=2;tier++){
            String source=tier==1?"payload-conveyor":"multiplicative-reconstructor";
            String name=tier==1?"colouring-station":"colouring-station-2";
            BufferedImage base=sprites.get(source);
            if(base==null)throw new java.io.IOException("Missing station placeholder source: "+source);
            BufferedImage full=new BufferedImage(base.getWidth(),base.getHeight(),BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g=full.createGraphics();g.drawImage(base,0,0,null);
            BufferedImage top=sprites.get(source+"-top");
            if(top!=null)g.drawImage(top,(base.getWidth()-top.getWidth())/2,(base.getHeight()-top.getHeight())/2,null);
            g.dispose();
            for(String suffix:new String[]{"-full","-ui","-icon"})ImageIO.write(full,"png",target.child(name+suffix+".png").file());
            if(tier==2)for(String suffix:new String[]{"-in","-out"}){
                BufferedImage layer=sprites.get("factory"+suffix+"-5");
                if(layer!=null)ImageIO.write(layer,"png",target.child(name+suffix+".png").file());
            }
        }
    }

    private void writeStations(Seq<UnitType> units) {
        Fi dir=data.child("colouring-cache/staging/blocks");dir.mkdirs();
        dir.child("colouring-station.hjson").writeString("{\"type\":\"PayloadConveyor\",\"name\":\"Colouring Station\",\"size\":3,\"health\":650,\"payloadLimit\":3,\"moveTime\":60,\"category\":\"effect\",\"buildVisibility\":\"shown\",\"requirements\":[\"copper/200\",\"lead/150\",\"silicon/100\"]}");
        Jval pairs=Jval.newArray();
        for(UnitType u:units){Jval pair=Jval.newArray();pair.add(Jval.valueOf(u.name));pair.add(Jval.valueOf(u.name));pairs.add(pair);}
        for(var entry:originals){Jval pair=Jval.newArray();pair.add(Jval.valueOf(entry.key));pair.add(Jval.valueOf(entry.key));pairs.add(pair);}
        Jval j=Jval.newObject();j.add("type",Jval.valueOf("Reconstructor"));j.add("name",Jval.valueOf("Colouring Station II"));j.add("size",Jval.valueOf(5));
        j.add("solid",Jval.valueOf(false)); // T2 is a walkable paint bay, including for grounded mechs.
        j.add("health",Jval.valueOf(1800));j.add("constructTime",Jval.valueOf(1e30));j.add("upgrades",pairs);j.add("category",Jval.valueOf("effect"));j.add("buildVisibility",Jval.valueOf("shown"));
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
