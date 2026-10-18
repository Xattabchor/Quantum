package code.Rendering;

import code.Rendering.Meshes.Sprite;
import code.Math.Matrix;
import code.utils.Main;
import javax.microedition.m3g.Graphics3D;

/** Walk through a billboard; no suppression, fake collision or CPU fallback. */
public final class SpriteCrossingTest {
    static void check(boolean b,String why){M3GTest.check(b,why);}
    public static void main(String[] args) throws Exception {
        DirectX7.fDist=1000;DirectX7.fogc=0x507090;Main.fogQ=2;
        int move=TexturingPers.move;
        check(move>0,"invalid fixture perspective distance");
        java.lang.reflect.Field pending=DirectX7.class.getDeclaredField("frameImage");pending.setAccessible(true);
        int frames=0;
        for(int material:new int[]{0,3,6})for(int mode:new int[]{0,1,2,3})for(boolean hardware:new boolean[]{false,true}) {
            DirectX7.standartDrawmode=(byte)material;Main.m3gPerspective=hardware;
            DirectX7 g=new DirectX7(240,320);g.requireM3G();
            Texture texture=M3GTest.texture(0xffc08040);
            Sprite s=new Sprite(texture,44);s.mode=(byte)mode;s.fog=true;
            // Valid projected near tree; positive rz remains visible under legacy cutoff rules.
            s.pos.sx=-200;s.pos.sy=-200;s.size.sx=400;s.size.sy=500;
            for(int rz:new int[]{-1000,-1,0,move,move-1,1,move+1,move*2,1000}) {
                s.pos.rz=s.size.rz=rz;int first=Graphics3D.history.size();
                g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();M3GTest.submit(g,s,null,0);
                g.renderGameFrameImage(null,false,false,null,0,null);
                check(g.getRendererError()==null && g.isM3GActive(),"crossing disabled native sprite at rz="+rz);
                check(Graphics3D.history.size()==first+2,"near sprite disappeared or duplicated");
                Graphics3D.Draw d=Graphics3D.history.lastElement();
                boolean fog=mode!=1 && mode!=2;
                check(d.fog==fog,"ADD/MUL fog semantics changed");
                float eye=fog?1-(float)Math.min(0,rz)/move:1;
                check(Math.abs(-d.positions[2]*d.scale-eye)<0.0001,"near sprite fog depth is not safely clamped");
                if(rz>=0) {
                    check(-d.positions[2]*d.scale>=1,"billboard submitted at/behind generic near plane");
                    if(fog)check((((javax.microedition.lcdui.Image)pending.get(g)).pixels[0]&0xffffff)==0xc08040,"positive depth added fog to a near tree");
                }
                check(rz==-1000 || g.getM3GCpuBarriers()==0,"crossing inserted world readback");frames++;
            }
            g.destroy();
        }
        Main.m3gPerspective=true;DirectX7.standartDrawmode=3;
        // Production Sprite.project + isVisible, both travel directions, not just edited rectangle coordinates.
        Texture texture=new Texture(new RawImage(new int[64*128],64,128),false,null);
        java.util.Arrays.fill(texture.rImg.img,0xffc08040);
        frames+=walk(texture,44);
        if(args.length!=0) {
            String[] paths={"/gamedata/objects/textree1.png","/gamedata/objects/textree2.png"};int[] scales={44,50};
            for(int i=0;i<paths.length;i++) {
                java.io.InputStream input=SpriteCrossingTest.class.getResourceAsStream(paths[i]);
                check(input!=null,"missing real tree: "+paths[i]);
                java.awt.image.BufferedImage image;
                try {image=javax.imageio.ImageIO.read(input);}finally {input.close();}
                int w=image.getWidth(),h=image.getHeight();
                Texture real=new Texture(new RawImage(image.getRGB(0,0,w,h,null,0,w),w,h),false,null);
                frames+=walk(real,scales[i]);
                System.out.println("PASS real tree crossing "+paths[i]+" "+w+"x"+h+" scale="+scales[i]+" / original PNG pixels");
            }
        }
        System.out.println("PASS "+frames+" native billboard crossing frames / positive and zero fog eye regression / modes 0-3 / fog 0,3,6 / HW perspective both / production project+visibility / cutoff both / forward+reverse / zero warm CPU barriers");
    }
    private static int walk(Texture texture,int scale) {
        int frames=0;
        for(int cutoff:new int[]{0,1}) {
            DirectX7 g=new DirectX7(240,320);g.requireM3G();Sprite s=new Sprite(texture,scale);s.fog=true;s.cutoff=(byte)cutoff;
            s.getPosition().set(0,-320,0);Matrix view=new Matrix();view.setIdentity();
            for(int step=0;step<=160;step++) {
                int depth=step<=80?-400+step*10:400-(step-80)*10;view.m23=depth;
                s.project(view,g);boolean visible=s.isVisible(0,0,g.width,g.height);
                check(visible,"fixture tree must remain visible through crossing at "+depth);
                int first=Graphics3D.history.size();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();g.addRenderObject(s);g.renderGameFrameImage(null,false,false,null,0,null);
                check(g.getRendererError()==null && Graphics3D.history.size()==first+2,"projected tree crossing failed at "+depth);
                frames++;
            }
            g.destroy();
        }
        return frames;
    }
}
