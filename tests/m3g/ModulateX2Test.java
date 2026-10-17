package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Mode10 native MODULATE_X2 and API-free CPU recovery contracts. */
public final class ModulateX2Test {
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
    private static int multiply(int source,int background) {
        int out=background&0xff000000;
        for(int shift=0;shift<24;shift+=8)
            out|=Math.min(255,2*((source>>>shift)&255)*((background>>>shift)&255)/255)<<shift;
        return out;
    }
    static void reference(DirectX7 cpu,RenderObject p,Texture t) {
        int[] background=cpu.display.clone();byte mode=t.drawmode;t.drawmode=0;
        cpu.clearDisplay(0);
        try {M3GTest.submit(cpu,p,t,0);cpu.render();} finally {t.drawmode=mode;}
        for(int i=0;i<background.length;i++) cpu.display[i]=cpu.display[i]==0?background[i]:multiply(cpu.display[i],background[i]);
    }
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(boolean hw:new boolean[]{false,true}) for(int kind=0;kind<6;kind++) {
            Main.m3gPerspective=hw;DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff80b0df);
            int uploads=Graphics3D.uploads;int[] original=t.rImg.img.clone();
            for(int pers:new int[]{0,4}) for(int fog:new int[]{0,1,2}) for(boolean mip:new boolean[]{false,true}) {
                Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=mip;DirectX7.fDist=0;
                t.drawmode=0;g.clearDisplay(0x80c0ff);M3GTest.submit(g,polygon(kind),t,0);g.render();
                Graphics3D.Draw expected=Graphics3D.history.lastElement();
                t.drawmode=10;g.clearDisplay(0x80c0ff);int calls=Graphics3D.calls;
                M3GTest.submit(g,polygon(kind),t,0);g.render();Graphics3D.Draw actual=Graphics3D.history.lastElement();
                check(g.isM3GActive() && Graphics3D.calls==calls+1 && actual.blend==CompositingMode.MODULATE_X2
                        && !actual.fog,"mode10 not single-pass native MODULATE_X2");
                check((g.display[0]&0xffffff)==multiply(0xff80b0df,0x80c0ff),"MODULATE_X2 equation/clamp");
                check(Arrays.equals(actual.positions,expected.positions) && Arrays.equals(actual.coords,expected.coords)
                        && Arrays.equals(actual.colors,expected.colors) && Arrays.equals(actual.indices,expected.indices),"mode10 UV/geometry changed");
            }
            check(Graphics3D.uploads==uploads+1 && Arrays.equals(original,t.rImg.img) && t.drawmode==10,"mode10 cache/asset changed");
            t.drawmode=7;M3GTest.submit(g,polygon(kind),t,0);g.render();
            check(Graphics3D.history.lastElement().blend==CompositingMode.MODULATE,"mode7 changed");
            t.drawmode=2;M3GTest.submit(g,polygon(kind),t,0);g.render();
            check(Graphics3D.history.lastElement().blend==CompositingMode.ALPHA_ADD,"mode2 changed");g.destroy();
        }
        Main.m3gPerspective=true;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int pixel:new int[]{0,0xff000000,0xff808080,0xffffffff,0x4080b0df}) {
            DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(pixel);t.drawmode=10;
            g.clearDisplay(0x80c0ff);M3GTest.submit(g,polygon(0),t,0);g.render();
            check((g.display[0]&0xffffff)==(pixel==0?0x80c0ff:multiply(pixel,0x80c0ff)),"mode10 black/white/cutout/alpha/clamp");g.destroy();
        }
        for(int pers:new int[]{0,4}) for(int kind=0;kind<6;kind++) for(int scenario=0;scenario<6;scenario++) for(boolean mip:new boolean[]{false,true}) {
            Main.persQ=pers;Main.mipMapping=mip;
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            int[] map={0,0xffffffff,0xff000000,0xff80b0df,0xff102030,0x40a0b0c0,0xff40ff20,0xffcccccc,
                       0xffeeeeee,0xff99aabb,0,0xff001100,0xff808080,0xff404040,0xff00ffff,0xff111111};
            Texture t=new Texture(new RawImage(map,4,4),true,null);t.drawmode=10;
            if(mip) t.mip=new RawImage[]{t.rImg,new RawImage(new int[]{0xffabcdef,0,0xff808080,0xffffffff},2,2),new RawImage(new int[]{0xff80a0c0},1,1)};
            Texture refTexture=new Texture(t.rImg,true,null);refTexture.drawmode=10;refTexture.mip=t.mip;
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
            check(Arrays.equals(cpu.display,g.display),"CPU X2 replay mismatch "+pers+"/"+kind+"/"+scenario+" mip="+mip);
            check(t.drawmode==10 && Graphics3D.target==null,"mode10 target/asset leak");g.destroy();cpu.destroy();
        }
        Main.mipMapping=false;DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff808080);t.drawmode=10;
        int calls=Graphics3D.calls;for(int i=0;i<10;i++) M3GTest.submit(g,polygon(0),t,-i);
        g.render();check(g.isM3GActive() && Graphics3D.calls==calls+1,"mode10 lost batching");g.destroy();
        Main.persQ=0;Main.fogQ=2;
        DirectX7 software=new DirectX7(32,32),selected=M3GTest.renderer();
        software.clearDisplay(0x123456);selected.clearDisplay(0x123456);
        Texture original=M3GTest.texture(0xff808080);original.drawmode=10;
        M3GTest.submit(software,polygon(0),original,0);software.render();
        M3GTest.submit(selected,polygon(0),original,0);selected.renderSoftware();
        check(!Arrays.equals(software.display,selected.display) && original.drawmode==10,
                "Software mode10 was changed to MODULATE_X2");software.destroy();selected.destroy();
        System.out.println("PASS mode10 MODULATE_X2 / six polygon types / HW+UV+fogQ+mip / saturation+cutout / batching+cache / CPU affine+perspective+MIP failure replay");
    }
}
