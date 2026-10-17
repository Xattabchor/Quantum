package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Native and CPU-replayed MODULATE, with original Software glass preserved. */
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
    private static int multiply(int source,int background) {
        int out=background&0xff000000;
        for(int shift=0;shift<24;shift+=8)
            out|=(((source>>shift)&255)*((background>>shift)&255)/255)<<shift;
        return out;
    }
    // Independent reference: original opaque CPU UV/mip rasterization on white,
    // then per-channel multiplication. White also leaves cutout pixels unchanged.
    static void reference(DirectX7 cpu,RenderObject p,Texture t) {
        int[] background=cpu.display.clone();byte mode=t.drawmode;t.drawmode=0;
        cpu.clearDisplay(0xffffff);
        try {M3GTest.submit(cpu,p,t,0);cpu.render();} finally {t.drawmode=mode;}
        for(int i=0;i<background.length;i++) cpu.display[i]=multiply(cpu.display[i],background[i]);
    }
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(boolean hw:new boolean[]{false,true}) {
            Main.m3gPerspective=hw;
            for(int kind=0;kind<6;kind++) {
                DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);Texture t=texture(0xff80b0df);
                int[] original=t.rImg.img.clone();int uploads=Graphics3D.uploads;
                for(int pers:new int[]{0,1,4}) for(int fog:new int[]{0,1,2}) for(boolean mip:new boolean[]{false,true}) {
                    Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=mip;DirectX7.fDist=0;
                    g.shootIntensity=999;g.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
                    t.drawmode=0;g.clearDisplay(0x123456);submit(g,p,t);
                    Graphics3D.Draw expected=last();int calls=Graphics3D.calls;
                    t.drawmode=7;g.clearDisplay(0x123456);submit(g,p,t);
                    Graphics3D.Draw actual=last();
                    check(Graphics3D.calls==calls+1 && actual.blend==CompositingMode.MODULATE && !actual.fog,
                            "mode7 did not use native MODULATE");
                    check(Graphics3D.lastAppearance.tex!=null && (g.display[0]&0xffffff)==multiply(0xff80b0df,0x123456)
                            && Arrays.equals(actual.colors,expected.colors) && Arrays.equals(actual.positions,expected.positions)
                            && Arrays.equals(actual.coords,expected.coords) && Arrays.equals(actual.indices,expected.indices),
                            "mode7 texture/geometry or multiply equation changed");
                    check(t.drawmode==7,"native changed material mode");
                }
                check(Graphics3D.uploads==uploads+1,"mode7 did not share opaque texture cache");
                check(Arrays.equals(original,t.rImg.img),"mode7 mutated original bitmap");
                t.drawmode=2;submit(g,p,t);check(last().blend==CompositingMode.ALPHA_ADD,"mode2 ADD changed");
                t.drawmode=0;submit(g,p,t);check(last().blend==CompositingMode.REPLACE,"MODULATE leaked into ordinary material");
                g.destroy();
            }
        }
        System.out.println("PASS mode7 MODULATE / six polygon types / perspective+fog+mip / HW ON+OFF / cache / mode2 unchanged");
        Main.m3gPerspective=true;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 batch=M3GTest.renderer();Texture shared=texture(0xff808080);
        int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
        for(int i=0;i<10;i++) M3GTest.submit(batch,polygon(0),shared,-100-i);
        batch.render();check(batch.isM3GActive() && Graphics3D.calls==calls+1 && Graphics3D.uploads==uploads+1,
                "mode7 lost batching/cache");batch.destroy();
        for(int pixel:new int[]{0,0xff000000,0xffffffff,0xff80b0df,0x4080b0df}) {
            DirectX7 g=M3GTest.renderer();Texture t=texture(pixel);g.clearDisplay(0x123456);submit(g,polygon(0),t);
            int expected=pixel==0?0x123456:multiply(pixel,0x123456);
            check((g.display[0]&0xffffff)==expected,"white/black/cutout/RGB multiplication");g.destroy();
        }
        // Every pixel, both UV paths, triangles and quads, all rollback boundaries.
        for(int pers:new int[]{0,4}) for(int kind=0;kind<6;kind++) for(int scenario=0;scenario<6;scenario++) {
            Main.persQ=pers;
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            int[] map={0,0xffffffff,0xff000000,0xff80b0df,
                    0xff204080,0x4080b0df,0xffc04080,0xffffffff,
                    0xff801020,0xff00ff00,0,0xffff00ff,
                    0xffcccccc,0xff102030,0xffddccbb,0xff000000};
            Texture t=new Texture(new RawImage(map,4,4),true,null);t.drawmode=7;
            Texture refTexture=new Texture(t.rImg,true,null);refTexture.drawmode=7;
            RenderObject p=polygon(kind),ref=polygon(kind);
            if(scenario==0) ((kind%2==0)?((Polygon3V)p).a:((Polygon4V)p).a).rz=
                    ((kind%2==0)?((Polygon3V)ref).a:((Polygon4V)ref).a).rz=1;
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            reference(cpu,ref,refTexture);M3GTest.submit(g,p,t,0);
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            if(scenario==4) System.setProperty("m3g.oom","true");
            if(scenario==5) g.renderSoftware();else g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(Arrays.equals(g.display,cpu.display),"mode7 fallback/replay differs from MODULATE, scenario="+scenario+" pers="+pers+" kind="+kind);
            check(t.drawmode==7 && Graphics3D.target==null,"fallback leaked material/target");
            g.destroy();cpu.destroy();
        }
        // Selected Software must still use original refraction, not multiplication.
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        Texture glass=texture(0xff80b0df);M3GTest.submit(cpu,polygon(0),glass,0);cpu.render();
        for(int pixel:cpu.display) check(pixel==0x123456,"Software glass was changed");cpu.destroy();
        System.out.println("PASS mode7 MODULATE CPU affine+perspective fallback/native/readback/OOM replay / Software refraction unchanged");
    }
}
