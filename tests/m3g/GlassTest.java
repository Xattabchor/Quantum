package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** M3G mode 7 is a mode 2 alias, including fallback; Software retains mode 7. */
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
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(boolean hw:new boolean[]{false,true}) {
            Main.m3gPerspective=hw;
            for(int kind=0;kind<6;kind++) {
                DirectX7 g=M3GTest.renderer();RenderObject p=polygon(kind);Texture t=texture(0xff808080);
                int[] original=t.rImg.img.clone();int uploads=Graphics3D.uploads;
                for(int pers:new int[]{0,1,4}) for(int fog:new int[]{0,1,2}) for(boolean mip:new boolean[]{false,true}) {
                    Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=mip;DirectX7.fDist=0;
                    g.shootIntensity=999;g.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
                    t.drawmode=2;g.clearDisplay(0x123456);submit(g,p,t);
                    Graphics3D.Draw expected=last();int pixel=g.display[0],calls=Graphics3D.calls;
                    t.drawmode=7;g.clearDisplay(0x123456);submit(g,p,t);
                    Graphics3D.Draw actual=last();
                    check(Graphics3D.calls==calls+1 && actual.blend==CompositingMode.ALPHA_ADD && !actual.fog,
                            "mode7 did not use native ADD");
                    check(Graphics3D.lastAppearance.tex!=null && g.display[0]==pixel
                            && Arrays.equals(actual.colors,expected.colors) && Arrays.equals(actual.positions,expected.positions)
                            && Arrays.equals(actual.coords,expected.coords) && Arrays.equals(actual.indices,expected.indices),
                            "mode7 differs from mode2");
                    check(t.drawmode==7,"native alias leaked into texture");
                }
                check(Graphics3D.uploads==uploads+1,"mode7 did not share ADD texture cache");
                check(Arrays.equals(original,t.rImg.img),"mode7 mutated original bitmap");
                t.drawmode=0;submit(g,p,t);check(last().blend==CompositingMode.REPLACE,"ADD leaked into ordinary material");
                g.destroy();
            }
        }
        System.out.println("PASS mode7 = mode2 native ADD / six polygon types / perspective+fog+mip / HW ON+OFF / shared texture cache / state reset");
        Main.m3gPerspective=true;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 mixed=M3GTest.renderer();Texture shared=texture(0xff808080);
        Texture add=new Texture(shared.rImg,true,null);add.drawmode=2;
        int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
        for(int i=0;i<10;i++) M3GTest.submit(mixed,polygon(0),i%2==0?shared:add,-100-i);
        mixed.render();
        check(mixed.isM3GActive() && Graphics3D.calls==calls+1 && Graphics3D.uploads==uploads+1,
                "alternating modes 7/2 split batch or texture cache");mixed.destroy();

        for(int scenario=0;scenario<6;scenario++) {
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture t=texture(0xff808080),reference=texture(0xff808080);reference.drawmode=2;
            RenderObject p=polygon(1),ref=polygon(1);
            if(scenario==0) ((Polygon4V)p).a.rz=((Polygon4V)ref).a.rz=1;
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            M3GTest.submit(cpu,ref,reference,0);cpu.render();M3GTest.submit(g,p,t,0);
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            if(scenario==4) System.setProperty("m3g.oom","true");
            if(scenario==5) g.renderSoftware();else g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(Arrays.equals(g.display,cpu.display),"mode7 fallback/replay differs from ADD, scenario="+scenario);
            check(t.drawmode==7 && Graphics3D.target==null,"fallback leaked material/target");
            g.destroy();cpu.destroy();
        }
        for(int backend:new int[]{DirectX7.SOFTWARE,DirectX7.M3G}) {
            DirectX7 g=new DirectX7(32,32);g.setRenderer(backend);Texture t=texture(0xffffffff);
            RenderObject probe=new RenderObject() {
                public boolean isVisible(int a,int b,int c,int d) {return true;}
                public void renderFast(DirectX7 g,Texture t) {render(g,t);}
                public void render(DirectX7 g,Texture t) {
                    check(t.drawmode==(g.getRenderer()==DirectX7.M3G?2:7),"incorrect alias scope");
                    throw new IllegalStateException("scope probe");
                }
            };
            try {g.drawRenderObject(probe,t,false);throw new AssertionError("no probe exception");}
            catch(IllegalStateException expected) { }
            check(t.drawmode==7,"exception leaked ADD alias");g.destroy();
        }
        System.out.println("PASS mode7 ADD fallback+native/readback/OOM replay / exception-safe alias / Software mode7 retained");
    }
}
