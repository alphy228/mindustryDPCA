import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Recolours the existing frame asset, retaining geometry, alpha and neutral body. */
public class GenerateBranchFrames {
    public static void main(String[] args) throws Exception {
        String[] ids={"mech","support_mech","attack_air","support_air","legged","attack_navals","support_navals","chemical","tank","insectoid","flyers"};
        String[] colors={"ff9c29","65ff65","ffd329","65ff65","d66bff","398fff","38ffe0","ff44c8","ffac29","b6ff38","ff5345"};
        File dir=new File(args.length==0?"config/assets/sprites":args[0]);
        BufferedImage base=ImageIO.read(new File(dir,"faction-col-sharded.png"));
        for(int i=0;i<ids.length;i++){
            BufferedImage out=new BufferedImage(base.getWidth(),base.getHeight(),BufferedImage.TYPE_INT_ARGB);
            int rgb=Integer.parseInt(colors[i],16);
            for(int y=0;y<base.getHeight();y++)for(int x=0;x<base.getWidth();x++){
                int pixel=base.getRGB(x,y),r=(pixel>>>16)&255,g=(pixel>>>8)&255,b=pixel&255;
                if(r>90&&r>b*1.25&&g>b*1.15)pixel=(pixel&0xff000000)|rgb;
                out.setRGB(x,y,pixel);
            }
            ImageIO.write(out,"png",new File(dir,"unit-branch-frame-"+ids[i]+".png"));
        }
    }
}
