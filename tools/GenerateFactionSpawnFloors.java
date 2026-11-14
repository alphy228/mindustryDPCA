import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Pixel-art floor assets, not rendered gameplay/menu previews. */
public class GenerateFactionSpawnFloors {
    public static void main(String[] args)throws Exception{
        File dir=new File(args.length==0?"config/assets/sprites":args[0]);
        String[] names={"sharded","crux"};int[] accents={0xffd37f,0xf25555};
        for(int i=0;i<names.length;i++){
            BufferedImage image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++){
                int c=(x==0||y==0)?0x53545e:(x==31||y==31)?0x272830:0x383a43;
                if(x>4&&x<27&&y>4&&y<27)c=0x30323a;
                // Broken inset square, with arrows pointing into the deployment tile.
                if(((x==3||x==28)&&(y>=3&&y<=9||y>=22&&y<=28))||
                   ((y==3||y==28)&&(x>=3&&x<=9||x>=22&&x<=28)))c=accents[i];
                int cx=Math.abs(x-15),cy=Math.abs(y-15);
                if((y>=5&&y<=9&&cx==9-y)||(y>=21&&y<=25&&cx==y-21)||
                   (x>=5&&x<=9&&cy==9-x)||(x>=21&&x<=25&&cy==x-21))c=accents[i];
                if(cx+cy==4)c=accents[i];
                image.setRGB(x,y,0xff000000|c);
            }
            ImageIO.write(image,"png",new File(dir,names[i]+"-spawnpoint.png"));
        }
    }
}
