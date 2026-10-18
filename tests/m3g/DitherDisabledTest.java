package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Mode 12 remains a mode 0 alias; 11 now has native DECAL semantics. */
public final class DitherDisabledTest {
    private static void check(boolean b,String why) { M3GTest.check(b,why); }
    static RenderObject polygon(int kind) {
        Polygon3V p=M3GTest.triangle();Vertex a=p.a,b=p.b,c=p.c,d=M3GTest.vertex(10,10,-200);
        if(kind==0) return p;
        if(kind==1) return new Polygon4V(a,b,d,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        if(kind==2) return new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        if(kind==3) return new LightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        if(kind==4) return new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        return new ColorLightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }
    private static void submit(DirectX7 g,RenderObject p,Texture t) {M3GTest.submit(g,p,t,0);g.render();}
    public static void main(String[] args) {
        Main.mipMapping=false;Main.forceLQFog=false;
        for(int kind=0;kind<6;kind++) for(int mode:new int[]{12}) {
            DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x80a1b2c3);RenderObject p=polygon(kind);
            int uploads=Graphics3D.uploads;
            for(int pers:new int[]{0,4}) for(int quality:new int[]{0,2}) for(boolean mip:new boolean[]{false,true}) {
                Main.persQ=pers;Main.fogQ=quality;Main.mipMapping=mip;
                // These divisors have no role in the mode 0 alias.
                DirectX7.fDist=0;DirectX7.waterDistance=0;
                t.drawmode=0;g.clearDisplay(0x123456);submit(g,p,t);
                Graphics3D.Draw expected=Graphics3D.history.lastElement();int pixel=g.display[0],calls=Graphics3D.calls;
                t.drawmode=(byte)mode;g.clearDisplay(0x123456);submit(g,p,t);
                Graphics3D.Draw actual=Graphics3D.history.lastElement();
                check(g.isM3GActive() && Graphics3D.calls==calls+1,"dithering didn't use one native mode0 draw");
                check(actual.blend==CompositingMode.REPLACE && !actual.fog && actual.textureAlpha==255,"alias isn't opaque nonzero texture");
                check(g.display[0]==pixel && Arrays.equals(actual.colors,expected.colors)
                        && Arrays.equals(actual.positions,expected.positions) && Arrays.equals(actual.coords,expected.coords)
                        && Arrays.equals(actual.indices,expected.indices),"alias differs from mode0");
                check(t.drawmode==mode,"material mode was changed permanently");
            }
            check(Graphics3D.uploads==uploads+1,"alias created separate/repeated texture uploads");
            Texture hole=M3GTest.texture(0);hole.drawmode=(byte)mode;g.clearDisplay(0x123456);submit(g,p,hole);
            check(g.display[0]==0x123456,"alias filled mode0 cutout");g.destroy();
        }
        System.out.println("PASS disabled dithering12 = native mode0 / six polygon types / perspective+fog+mip / zero distances / cache+cutout");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;DirectX7.fDist=1000;DirectX7.waterDistance=1000;
        for(int mode:new int[]{12}) for(int scenario=0;scenario<4;scenario++) {
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture t=M3GTest.texture(0xffa1b2c3),zero=M3GTest.texture(0xffa1b2c3);t.drawmode=(byte)mode;
            Polygon3V p=M3GTest.triangle(),reference=M3GTest.triangle();
            if(scenario==0) p.a.rz=reference.a.rz=1;
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            submit(cpu,reference,zero);
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            submit(g,p,t);Graphics3D.failAt=0;System.clearProperty("readback.fail");
            check(Arrays.equals(g.display,cpu.display),"dithering revived in CPU fallback/replay");
            check(t.drawmode==mode,"fallback leaked aliased mode");g.destroy();cpu.destroy();
        }
        System.out.println("PASS mode0 aliases through geometry/alpha fallback and native/readback replay");

        for(final int mode:new int[]{11,12}) {
            Texture t=M3GTest.texture(0xffffffff);t.drawmode=(byte)mode;
            RenderObject probe=new RenderObject() {
                public boolean isVisible(int a,int b,int c,int d) {return true;}
                public void renderFast(DirectX7 g,Texture t) {render(g,t);}
                public void render(DirectX7 g,Texture t) {
                    check(t.drawmode==(g.getRenderer()==DirectX7.M3G?0:mode),"incorrect alias scope");
                    throw new IllegalStateException("scope probe");
                }
            };
            for(int backend:new int[]{DirectX7.SOFTWARE,DirectX7.M3G}) {
                DirectX7 g=new DirectX7(32,32);g.setRenderer(backend);
                try {g.drawRenderObject(probe,t,false);throw new AssertionError("no probe exception");}
                catch(IllegalStateException expected) { }
                check(t.drawmode==mode,"exception leaked aliased mode");g.destroy();
            }
            DirectX7 g=M3GTest.renderer();SkyMesh sky=SkyTest.cube();
            sky.mesh.getTexture().textures[0].drawmode=(byte)mode;sky.set(new Matrix(),0,0,32,32);
            int calls=Graphics3D.calls;submit(g,sky,null);
            check(g.isM3GActive() && Graphics3D.calls>calls,"sky alias fell back");g.destroy();
        }
        System.out.println("PASS exception-safe alias scope / original Software mode retained / native sky aliases");
    }
}
