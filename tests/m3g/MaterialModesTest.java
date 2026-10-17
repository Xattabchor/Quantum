package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Math.MathUtils;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Native state/equation contracts. The fake API does not rasterize triangles. */
public final class MaterialModesTest {
    private static void check(boolean ok,String why) { M3GTest.check(ok,why); }
    private static Graphics3D.Draw last() { return Graphics3D.history.lastElement(); }
    private static Texture texture(int mode,int rgb) {
        Texture t=M3GTest.texture(rgb);t.drawmode=(byte)mode;return t;
    }
    private static void submit(DirectX7 g,RenderObject p,Texture t) {
        M3GTest.submit(g,p,t,0);g.render();
        check(g.isM3GActive(),"native mode disabled: "+g.getRendererError());
    }
    private static RenderObject polygon(int kind) {
        Vertex a=M3GTest.vertex(2,2,-100),b=M3GTest.vertex(25,2,-200),
                c=M3GTest.vertex(25,25,-300),d=M3GTest.vertex(2,25,-400);
        if(kind%2==0) c=M3GTest.vertex(2,25,-300);
        RenderObject p;
        if(kind==0) p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        else if(kind==1) p=new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        else if(kind==2) p=new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        else if(kind==3) p=new LightedPolygon4V(a,b,c,d,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        else if(kind==4) p=new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        else p=new ColorLightedPolygon4V(a,b,c,d,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        p.nx=0;p.ny=0;p.nz=4096;return p;
    }
    private static void near(int a,int b,int tolerance,String why) {
        for(int shift=0;shift<=16;shift+=8)
            check(Math.abs(((a>>shift)&255)-((b>>shift)&255))<=tolerance,why);
    }
    private static int cpuAdd(int src,int dst) {
        int col=(src&0xfefefe)+(dst&0xfefefe);
        return col|(((col>>>8)&0x010101)*255);
    }
    private static void ordinary(DirectX7 g,RenderObject p,Texture t) {
        t.drawmode=0;submit(g,p,t);
        check(last().blend==CompositingMode.REPLACE && last().function==Texture2D.FUNC_MODULATE && !last().fog,
                "special material blend/function/fog leaked");
        for(byte c:last().colors) check((c&255)==255,"special material vertex color leaked");
    }
    public static void main(String[] args) {
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;Main.forceLQFog=false;
        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);
            Texture t=texture(2,0x804181f1);int uploads=Graphics3D.uploads;
            for(int quality:new int[]{0,2}) for(int pers:new int[]{0,4}) {
                Main.fogQ=quality;Main.persQ=pers;
                for(int bg:new int[]{0,0x112337,0xf0a0ff,0xffffff}) {
                    g.clearDisplay(bg);int calls=Graphics3D.calls;submit(g,p,t);
                    check(Graphics3D.calls==calls+1 && last().blend==CompositingMode.ALPHA_ADD,"mode2 not single additive draw");
                    check(last().textureAlpha==255,"mode2 incorrectly uses fractional texture alpha");
                    near(g.display[0],cpuAdd(0x4181f1,bg),1,"mode2 saturation/low-bit tolerance");
                }
            }
            check(Graphics3D.uploads==uploads+1,"mode2 repeated texture uploads");
            Texture hole=texture(2,0);g.clearDisplay(0x112337);submit(g,p,hole);
            check(g.display[0]==0x112337,"mode2 transparent hole changed destination");
            ordinary(g,p,t);g.destroy();
        }
        System.out.println("PASS mode2 native additive / saturation / cutout / fractional-alpha ignored / cache / six polygon types");

        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);Texture t=texture(5,0xffdf9153);
            for(int pers:new int[]{0,4}) for(int quality:new int[]{0,1,2}) for(int[] direction:new int[][]{
                    {0,0,4096},{0,0,-4096},{4096,0,0},{4096,0,2048},{0,0,0}}) {
                Main.persQ=pers;Main.fogQ=quality;
                DirectX7.lightdirx=(short)direction[0];DirectX7.lightdiry=(short)direction[1];DirectX7.lightdirz=(short)direction[2];
                int calls=Graphics3D.calls;submit(g,p,t);
                int light=MathUtils.calcLight(p.nx,p.ny,p.nz,direction[0],direction[1],direction[2]);
                int shade=pers==4 && quality==0?255:(light*255+128)>>8;
                check(Graphics3D.calls==calls+1 && !last().fog && last().blend==CompositingMode.REPLACE,"mode5 not flat native draw");
                for(int i=0;i<last().colors.length;i++) check((last().colors[i]&255)==(i%4==3?255:shade),"mode5 coefficient");
                int cpu=pers==4 && quality==0?0xdf9153:(((0xdf*light)>>8)<<16)|(((0x91*light)>>8)<<8)|((0x53*light)>>8);
                near(g.display[0],cpu,1,"mode5 /256 rounding tolerance");
                byte[] baseline=last().colors.clone();g.shootIntensity=999;g.flashlightEnabled=true;
                LightMapper.cameraVectorLight=true;g.camera.m03=500;submit(g,p,t);
                check(Arrays.equals(baseline,last().colors),"mode5 reacted to shot/torch/camera");
            }
            ordinary(g,p,t);g.destroy();
        }
        System.out.println("PASS mode5 flat normal/lightdir / zero+opposed+oblique / fogQ0 affine-perspective / no dynamic light");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);
            Texture t=new Texture(new RawImage(new int[]{0xff112233,0xffaabbcc,0xff778899,0xff445566},2,2),true,null);
            t.drawmode=8;int uploads=Graphics3D.uploads,calls=Graphics3D.calls;
            submit(g,p,t);
            int count=kind%2==0?1:2;
            check(Graphics3D.calls==calls+count,"mode8 must preserve first-UV color per triangle");
            Graphics3D.Draw first=Graphics3D.history.elementAt(Graphics3D.history.size()-count);
            check((first.colors[0]&255)==0x11 && (last().colors[0]&255)==(count==1?0x11:0xaa),"mode8 first-vertex sample");
            if(count==2) check(Arrays.equals(first.indices,new int[]{0,1,3}) && Arrays.equals(last().indices,new int[]{1,2,3}),"mode8 diagonal/order");
            check(Graphics3D.lastAppearance.tex==null && !last().fog && last().blend==CompositingMode.REPLACE,"mode8 is not untextured fill");
            check(Graphics3D.uploads==uploads,"mode8 uploaded texture unnecessarily");
            Texture black=texture(8,0);g.clearDisplay(0xabcdef);calls=Graphics3D.calls;submit(g,p,black);
            check(g.display[0]==0xff000000 && Graphics3D.calls==calls+1,"mode8 zero is opaque black, not a hole");
            ordinary(g,p,t);g.destroy();
        }
        // Sampling follows the legacy wrap and the 255->256 small-texture rule.
        DirectX7 g=M3GTest.renderer();Polygon3V p=(Polygon3V)polygon(0);
        Texture t=new Texture(new RawImage(new int[]{0xff112233,0xffaabbcc,0xff778899,0xff445566},2,2),true,null);t.drawmode=8;
        for(int pers:new int[]{0,4}) {
            Main.persQ=pers;p.au=255;p.av=128;submit(g,p,t);
            check((g.display[0]&0xffffff)==0x112233,"mode8 255 endpoint remapping/wrap");
        }
        // Mip choice affects fill color just as in software, but needs no upload.
        p.au=p.av=0;t.mip=new RawImage[]{new RawImage(new int[256*256],256,256),
                new RawImage(new int[128*128],128,128),new RawImage(new int[]{0xff123456,0xff123456,0xff123456,0xff123456},2,2)};
        Main.mipMapping=true;submit(g,p,t);
        check((g.display[0]&0xffffff)==0x123456,"mode8 mip sample");Main.mipMapping=false;
        // Native fill doesn't need the original bitmap to fit M3G texture limits.
        t.mip=null;t.rImg=new RawImage(new int[]{0xff654321,0,0},3,1);submit(g,p,t);
        check((g.display[0]&0xffffff)==0x654321,"mode8 unnecessary texture-size fallback");g.destroy();
        System.out.println("PASS mode8 untextured / per-triangle quad colors / opaque zero / UV wrapping / mip / no upload / state reset");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int mode:new int[]{2,5,8}) for(boolean readback:new boolean[]{false,true}) {
            DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            DirectX7 nativeG=M3GTest.renderer();RenderObject cpuP=polygon(1),nativeP=polygon(1);
            Texture cpuT=texture(mode,0xffd08153),nativeT=texture(mode,0xffd08153);
            M3GTest.submit(cpu,cpuP,cpuT,0);cpu.render();
            M3GTest.submit(nativeG,nativeP,nativeT,0);
            if(readback) System.setProperty("readback.fail","true");else Graphics3D.failAt=Graphics3D.calls+1;
            nativeG.render();Graphics3D.failAt=0;System.clearProperty("readback.fail");
            check(!nativeG.isM3GActive() && Graphics3D.target==null,"new mode failure did not disable/release native backend");
            check(Arrays.equals(cpu.display,nativeG.display),"new mode full software replay mismatch");
            cpu.destroy();nativeG.destroy();
        }
        // Same-frame order and resetting material state around a CPU barrier.
        g=M3GTest.renderer();final int background=0x123456;
        M3GTest.submit(g,polygon(0),texture(2,0xff204060),-500);
        RenderObject barrier=new RenderObject() {
            public boolean isVisible(int a,int b,int c,int d) {return true;}
            public void renderFast(DirectX7 g,Texture t) {render(g,t);}
            public void render(DirectX7 g,Texture t) {
                near(g.display[0],cpuAdd(0x204060,background),1,"mode2 readback before CPU barrier");
                g.display[0]=0xffaabbcc;
            }
        };
        M3GTest.submit(g,barrier,null,-400);
        M3GTest.submit(g,polygon(0),texture(5,0xffc08040),-300);
        M3GTest.submit(g,polygon(0),texture(8,0xff123456),-200);
        M3GTest.submit(g,polygon(0),texture(0,0xffabcdef),-100);
        g.render();check(g.isM3GActive() && g.display[0]==0xffabcdef,"same-frame material order/state reset");g.destroy();
        System.out.println("PASS mode2/5/8 same-frame painter order / CPU barrier / ordinary-material reset");

        // A second fill-triangle error must also undo its first native draw.
        g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        Texture a=new Texture(new RawImage(new int[]{0xff112233,0xffaabbcc,0,0},2,2),true,null);a.drawmode=8;
        M3GTest.submit(cpu,polygon(1),a,0);cpu.render();M3GTest.submit(g,polygon(1),a,0);
        Graphics3D.failAt=Graphics3D.calls+2;g.render();Graphics3D.failAt=0;
        check(!g.isM3GActive() && Arrays.equals(cpu.display,g.display),"mode8 second triangle rollback");g.destroy();cpu.destroy();
        System.out.println("PASS mode2/5/8 native-error / readback / second-fill rollback to original Software");
    }
}
