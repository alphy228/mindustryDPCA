import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import coolplugin.gameplay.UnitTree;

/** Preserves existing frame geometry and derives hue from the unit-line native palette.
 * Also emits actual grayscale card artwork: a colour tint alone cannot desaturate it.
 * Run with the plugin and Mindustry JARs on the classpath.
 */
public class GenerateBranchFrames {
    public static void main(String[] args) throws Exception {
        File dir=new File(args.length==0?"config/assets/sprites":args[0]);
        BufferedImage base=ImageIO.read(new File(dir,"faction-col-sharded.png"));
        for(UnitTree branch:UnitTree.values()){
            BufferedImage out=new BufferedImage(base.getWidth(),base.getHeight(),BufferedImage.TYPE_INT_ARGB);
            int rgb=Integer.parseInt(branch.accentHex(),16);
            for(int y=0;y<base.getHeight();y++)for(int x=0;x<base.getWidth();x++){
                int pixel=base.getRGB(x,y),r=(pixel>>>16)&255,g=(pixel>>>8)&255,b=pixel&255;
                if(r>90&&r>b*1.25&&g>b*1.15)pixel=(pixel&0xff000000)|rgb;
                out.setRGB(x,y,pixel);
            }
            ImageIO.write(out,"png",new File(dir,"unit-branch-frame-"+branch.id()+".png"));
            BufferedImage art=ImageIO.read(new File(dir,"unit-trees/"+branch.art()+".png"));
            for(int y=0;y<art.getHeight();y++)for(int x=0;x<art.getWidth();x++){
                int pixel=art.getRGB(x,y);
                int gray=(((pixel>>>16)&255)*54+((pixel>>>8)&255)*183+(pixel&255)*19)/256;
                art.setRGB(x,y,(pixel&0xff000000)|(gray<<16)|(gray<<8)|gray);
            }
            ImageIO.write(art,"png",new File(dir,"unit-trees/"+branch.art()+"-disabled.png"));
        }
    }
}
