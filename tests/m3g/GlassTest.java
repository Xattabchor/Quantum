package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Cheapest mode 7 approximation: untextured tint, not framebuffer refraction. */
public final class GlassTest {
    private static void check(boolean b,String why) { M3GTest.check(b,why); }
    private static Graphics3D.Draw last() { return Graphics3D.history.lastElement(); }
    private static Texture texture(int pixel) {
        Texture t=M3GTest.texture(pixel);t.drawmode=7;return t;
    }
    private static RenderObject polygon(int kind) {
        Polygon3V p=M3GTest.triangle();
        Vertex a=p.a,b=p.b,c=p.c,d=M3GTest.vertex(10,10,-200);
        if(kind==0) return p;
        if(kind==1) return new Polygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==2) return new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==3) return new LightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==4) return new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        return new ColorLightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
    }
    private static void submit(DirectX7 g,RenderObject p,Texture t) {
        M3GTest.submit(g,p,t,0);g.render();check(g.isM3GActive(),"glass unexpectedly fell back: "+g.getRendererError());
    }
    private static int mix(int background) {
        int out=0xff000000;
        for(int shift=0;shift<=16;shift+=8)
            out|=(((((0xb0beca>>shift)&255)*32)+((background>>shift)&255)*223)/255)<<shift;
        return out;
    }
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);Texture t=texture(0xff808080);
            int[] original=t.rImg.img.clone();int uploads=Graphics3D.uploads;
            for(int pers:new int[]{0,1,4}) for(int fog:new int[]{0,1,2}) for(boolean mip:new boolean[]{false,true}) {
                Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=mip;DirectX7.fDist=0;
                g.shootIntensity=999;g.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
                int calls=Graphics3D.calls;g.clearDisplay(0x123456);submit(g,p,t);
                check(Graphics3D.calls==calls+1,"glass must be one native draw per triangle/quad");
                check(last().blend==CompositingMode.ALPHA && !last().fog && Graphics3D.lastAppearance.tex==null,
                        "glass is not an untextured ALPHA pass");
                for(int i=0;i<last().colors.length;i++) {
                    int expected=new int[]{176,190,202,32}[i%4];
                    check((last().colors[i]&255)==expected,"glass tint/opacity reacted to lighting");
                }
                check(g.display[0]==mix(0x123456),"untextured alpha equation");
            }
            check(Graphics3D.uploads==uploads,"glass uploaded/converted source texture");
            check(Arrays.equals(original,t.rImg.img),"glass mutated original bitmap");
            t.drawmode=0;submit(g,p,t);
            check(last().blend==CompositingMode.REPLACE && (g.display[0]&0xffffff)==0x808080,"glass tint/alpha leaked into ordinary material");
            Texture ordinary=new Texture(t.rImg,true,null);ordinary.drawmode=0;t.drawmode=7;
            int binds=Graphics3D.binds,releases=Graphics3D.releases;
            M3GTest.submit(g,polygon(kind),ordinary,-300);
            M3GTest.submit(g,polygon(kind),t,-200);M3GTest.submit(g,polygon(kind),t,-100);g.render();
            check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1,"glass inserted CPU/readback barrier");
            check(Graphics3D.uploads==uploads+1,"glass uploaded texture after ordinary material");g.destroy();
        }
        System.out.println("PASS untextured glass / six polygon types / affine+perspective+fog+mip / no lighting or upload / no extra barrier / state reset");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=true;
        DirectX7 g=M3GTest.renderer();Texture t=texture(0);int uploads=Graphics3D.uploads;
        for(int pixel:new int[]{0,0xff000000,0xff808080,0xffffff00,0x80808080}) {
            t.rImg=M3GTest.texture(pixel).rImg;g.clearDisplay(0x123456);submit(g,polygon(0),t);
            check(g.display[0]==mix(0x123456),"source map affected untextured glass");
        }
        // No M3G bitmap restrictions or UV processing needed for this material.
        t.rImg=new RawImage(new int[]{0,0,0},3,1);t.mip=new RawImage[]{null};
        Polygon3V p=(Polygon3V)polygon(0);p.au=Integer.MAX_VALUE;p.av=Integer.MIN_VALUE;
        g.clearDisplay(0x123456);submit(g,p,t);check(g.display[0]==mix(0x123456),"glass inspected mip/UV/texture dimensions");
        t.rImg=null;submit(g,p,t);check(Graphics3D.uploads==uploads,"glass needs source bitmap");
        g.resize(40,24);submit(g,p,t);check(last().blend==CompositingMode.ALPHA,"resize lost glass path");g.destroy();
        System.out.println("PASS glass ignores source RGBA / zero / mip / UV / NPOT / absent bitmap / resize");

        Main.mipMapping=false;
        for(int failure=0;failure<3;failure++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture glass=texture(0xff808080);
            M3GTest.submit(cpu,polygon(1),glass,0);cpu.render();M3GTest.submit(g,polygon(1),glass,0);
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            g.render();Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(!g.isM3GActive() && Graphics3D.target==null,"glass failure target/backend leak");
            check(Arrays.equals(g.display,cpu.display),"glass failure didn't replay original Software refraction");
            g.destroy();cpu.destroy();
        }
        System.out.println("PASS glass native/readback/OOM failure / original Software mode7 replay");
    }
}
