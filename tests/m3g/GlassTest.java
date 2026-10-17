package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Mode 7 is deliberately a translucent approximation, not framebuffer refraction. */
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
    private static int mix(int background,byte[] texel) {
        int alpha=texel[3]&255,out=0xff000000;
        for(int i=0;i<3;i++) out|=(((texel[i]&255)*alpha+((background>>(16-i*8))&255)*(255-alpha))/255)<<(16-i*8);
        return out;
    }
    public static void main(String[] args) {
        Main.mipMapping=false;Main.forceLQFog=false;
        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);Texture t=texture(0xff808080);
            int[] original=t.rImg.img.clone();int uploads=Graphics3D.uploads;
            for(int pers:new int[]{0,1,4}) for(int fog:new int[]{0,1,2}) {
                Main.persQ=pers;Main.fogQ=fog;DirectX7.fDist=0;
                g.shootIntensity=999;g.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
                int calls=Graphics3D.calls;g.clearDisplay(0x123456);submit(g,p,t);
                check(Graphics3D.calls==calls+1,"glass must be one native draw per triangle/quad");
                check(last().blend==CompositingMode.ALPHA && !last().fog && last().function==Texture2D.FUNC_MODULATE,
                        "glass blend/fog/function");
                check(last().filter==Texture2D.FILTER_NEAREST,"glass unexpectedly uses more expensive filtering");
                for(byte c:last().colors) check((c&255)==255,"glass reacted to lightmap/shot/torch/camera");
                byte[] rgba=Graphics3D.lastAppearance.tex.image.pixels;
                check((rgba[0]&255)==176 && (rgba[1]&255)==190 && (rgba[2]&255)==202 && (rgba[3]&255)==8,
                        "neutral displacement must become nearly transparent cool glass");
                check(g.display[0]==mix(0x123456,rgba),"native glass alpha equation");
            }
            check(Graphics3D.uploads==uploads+1,"glass reprocessed/reuploaded every frame");
            check(Arrays.equals(original,t.rImg.img),"glass conversion mutated original bitmap");
            // Ordinary cached material shares the source bitmap, not the converted texture.
            t.drawmode=0;submit(g,p,t);
            check(last().blend==CompositingMode.REPLACE && (g.display[0]&0xffffff)==0x808080,"glass tint/alpha leaked into ordinary texture");
            t.drawmode=7;submit(g,p,t);check(last().textureAlpha==8,"ordinary upload replaced glass cache variant");
            int binds=Graphics3D.binds,releases=Graphics3D.releases;
            t.drawmode=7;Texture ordinary=new Texture(t.rImg,true,null);ordinary.drawmode=0;
            M3GTest.submit(g,polygon(kind),ordinary,-300);
            M3GTest.submit(g,polygon(kind),t,-200);M3GTest.submit(g,polygon(kind),t,-100);g.render();
            check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1,"cached glass inserted CPU/readback barrier");
            check(Graphics3D.uploads==uploads+2,"glass variants not reused");g.destroy();
        }
        System.out.println("PASS glass single-pass / six polygon types / affine+perspective+fog OFF / no lighting / shared bounded cache / no extra barrier");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 g=M3GTest.renderer();
        for(int pixel:new int[]{0,0xff000000,0xff808000,0xff8080ff,0xffffff00,0xffff0000,0xff00ff00,0x80808080}) {
            Texture t=texture(pixel);int[] original=t.rImg.img.clone();submit(g,polygon(0),t);
            byte[] rgba=Graphics3D.lastAppearance.tex.image.pixels;
            check((rgba[3]&255)>=8 && (rgba[3]&255)<=48,"glass opacity is outside cheap subtle range");
            check((rgba[2]&255)>(rgba[0]&255),"displacement map rendered as original red/green colors");
            check(Arrays.equals(original,t.rImg.img),"conversion changed Software source");
            if(pixel==0) check(last().textureAlpha==48,"zero displacement texel incorrectly treated as cutout");
            if((pixel&0xffff00)==0x808000) check(last().textureAlpha==8,"blue/source alpha affected displacement opacity");
        }
        Texture t=texture(0xff808080);submit(g,polygon(0),t);
        int uploads=Graphics3D.uploads;
        t.rImg.img=new int[]{0xffff0000,0xffff0000,0xffff0000,0xffff0000};submit(g,polygon(0),t);
        check(Graphics3D.uploads==uploads+1 && last().textureAlpha>8,"replacement bitmap didn't invalidate glass variant");
        t.rImg=new RawImage(new int[]{0xff808080,0xff808080,0xff808080,0xff808080},2,2);submit(g,polygon(0),t);
        check(last().textureAlpha==8,"animation frame didn't refresh glass");
        t.mip=new RawImage[]{new RawImage(new int[256*256],256,256),new RawImage(new int[128*128],128,128),t.rImg};
        Main.mipMapping=true;submit(g,polygon(0),t);check(last().textureAlpha==8,"glass mip selection");Main.mipMapping=false;
        g.resize(40,24);submit(g,polygon(0),t);check(last().blend==CompositingMode.ALPHA,"resize lost glass path");g.destroy();
        System.out.println("PASS glass opacity bounds / non-cutout zero / blue+alpha ignored / source unchanged / replacement+animation+mip+resize");

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
