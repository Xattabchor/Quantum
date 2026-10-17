package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Selected-M3G mode 1 uses source alpha, not distance fog. */
public final class AlphaModeTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
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
    // Existing Software ALPHA is the CPU recovery reference, even when its
    // alphaMixing optimization flag is stale/false. Do not mutate assets permanently.
    static void reference(DirectX7 cpu,RenderObject p,Texture t) {
        byte mode=t.drawmode;boolean alpha=t.rImg.alphaMixing;
        t.drawmode=4;t.rImg.alphaMixing=true;
        try {M3GTest.submit(cpu,p,t,0);cpu.render();}
        finally {t.drawmode=mode;t.rImg.alphaMixing=alpha;}
    }
    private static int mix(int source,int background) {
        int out=0;int alpha=source>>>24;
        for(int shift=0;shift<24;shift+=8)
            out|=((((source>>>shift)&255)*alpha+((background>>>shift)&255)*(255-alpha))/255)<<shift;
        return out;
    }
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(boolean hw:new boolean[]{false,true}) for(int kind=0;kind<6;kind++) {
            Main.m3gPerspective=hw;DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x80c04020);t.drawmode=1;
            int[] source=t.rImg.img.clone();int uploads=Graphics3D.uploads;
            for(int pers:new int[]{0,4}) for(int fog:new int[]{0,1,2}) for(boolean mip:new boolean[]{false,true}) {
                Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=mip;
                DirectX7.fDist=0;DirectX7.fogc=0xff00ff;
                // Mode 1 must honor the actual bytes even if this flag is false.
                t.rImg.alphaMixing=false;g.clearDisplay(0x123456);
                int calls=Graphics3D.calls;M3GTest.submit(g,polygon(kind),t,0);g.render();
                Graphics3D.Draw d=Graphics3D.history.lastElement();
                check(g.isM3GActive() && Graphics3D.calls==calls+1,"ALPHA fell back or used extra pass");
                check(d.blend==CompositingMode.ALPHA && !d.fog && d.threshold==0 && d.textureAlpha==128,
                        "mode1 is not source ALPHA without Fog/cutout");
                check((g.display[0]&0xffffff)==mix(0x80c04020,0x123456),"ALPHA equation");
                check(t.drawmode==1 && Arrays.equals(t.rImg.img,source),"mode1 changed resource");
            }
            check(Graphics3D.uploads==uploads+1,"ALPHA cache rebuilt per state change");
            t.drawmode=4;t.rImg.alphaMixing=true;M3GTest.submit(g,polygon(kind),t,0);g.render();
            check(Graphics3D.uploads==uploads+1,"modes1/4 did not share ALPHA cache");g.destroy();
        }
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int alpha:new int[]{0,1,64,128,254,255}) {
            DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture((alpha<<24)|0xc04020);t.drawmode=1;
            g.clearDisplay(0x123456);M3GTest.submit(g,polygon(0),t,0);g.render();
            check((g.display[0]&0xffffff)==mix((alpha<<24)|0xc04020,0x123456),"ALPHA endpoints/fractional");g.destroy();
        }
        for(int pers:new int[]{0,4}) for(int kind=0;kind<6;kind++) for(int scenario=0;scenario<6;scenario++) {
            Main.persQ=pers;DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            int[] map={0,0xffffffff,0xff000000,0x80c04020,0x01ff0000,0x4000ff00,0xfe0000ff,0};
            Texture t=new Texture(new RawImage(map,4,2),true,null);t.drawmode=1;t.rImg.alphaMixing=false;
            RenderObject p=polygon(kind),ref=polygon(kind);
            if(scenario==0) ((kind%2==0)?((Polygon3V)p).a:((Polygon4V)p).a).rz=
                    ((kind%2==0)?((Polygon3V)ref).a:((Polygon4V)ref).a).rz=1;
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            reference(cpu,ref,t);M3GTest.submit(g,p,t,0);
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            if(scenario==4) System.setProperty("m3g.oom","true");
            if(scenario==5) g.renderSoftware();else g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(Arrays.equals(cpu.display,g.display),"CPU ALPHA fallback/replay mismatch "+pers+"/"+kind+"/"+scenario);
            check(t.drawmode==1 && !t.rImg.alphaMixing && Graphics3D.target==null,"CPU ALPHA scope/target leak");
            g.destroy();cpu.destroy();
        }
        // Adjacent ALPHA polygons still batch; never sort across other textures.
        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x80c04020);t.drawmode=1;
        int calls=Graphics3D.calls;
        for(int i=0;i<10;i++) M3GTest.submit(g,polygon(0),t,-i);
        g.render();check(g.isM3GActive() && Graphics3D.calls==calls+1,"ALPHA lost batching");g.destroy();
        Main.fogQ=0;Main.persQ=0;
        DirectX7 software=new DirectX7(32,32),plain=new DirectX7(32,32);
        software.clearDisplay(0x123456);plain.clearDisplay(0x123456);
        Texture original=M3GTest.texture(0x80c04020);original.drawmode=1;
        M3GTest.submit(software,polygon(0),original,0);software.render();
        original.drawmode=0;M3GTest.submit(plain,polygon(0),original,0);plain.render();
        check(Arrays.equals(software.display,plain.display),"Software mode1 was changed to ALPHA");
        software.destroy();plain.destroy();
        System.out.println("PASS mode1 source ALPHA / no Fog / six polygon types / HW+UV+mip+fogQ / alpha endpoints / stale flag / cache+batch / CPU failure replay");
    }
}
