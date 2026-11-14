import arc.*;import arc.backend.headless.*;import arc.files.*;import arc.graphics.g2d.*;import arc.util.*;import java.util.*;import java.io.*;
public class MeasureColouringAtlas {
 record Image(String name,int w,int h){}
 public static void main(String[] args){new HeadlessApplication(new ApplicationListener(){public void init(){try{
  ArcNativesLoader.load();List<Image> images=new ArrayList<>();
  for(Fi f:new Fi(args[0]).findAll(p->p.extension().equals("png"))){try(DataInputStream in=new DataInputStream(f.read())){in.skipNBytes(16);images.add(new Image(f.nameWithoutExtension(),in.readInt(),in.readInt()));}}
  long worst=0;
  for(int order=0;order<5;order++){
   List<Image> ordered=new ArrayList<>(images);
   if(order==0)ordered.sort(Comparator.comparing(Image::name));
   else if(order==1)ordered.sort(Comparator.comparingInt(i->i.w*i.h));
   else if(order==2)ordered.sort(Comparator.comparingInt((Image i)->i.w*i.h).reversed());
   else Collections.shuffle(ordered,new Random(order));
   PixmapPacker packer=new PixmapPacker(4096,4096,2,true);
   for(Image image:ordered)packer.pack(image.name,image.w,image.h);
   long bytes=(long)packer.getPages().size*4096*4096*4;worst=Math.max(worst,bytes);
   System.out.println("ORDER="+order+" IMAGES="+images.size()+" PAGES="+packer.getPages().size+" RGBA_MiB="+(bytes/1048576));packer.forceDispose();
  }
  System.out.println("WORST_ATLAS_MiB="+(worst/1048576));System.exit(worst<=300L*1048576?0:1);
 }catch(Throwable t){t.printStackTrace();System.exit(2);}}});}
}
