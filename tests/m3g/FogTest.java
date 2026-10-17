package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import javax.microedition.m3g.*;

/** Native Fog state/equation contracts, NOT GPU image or performance tests. */
public final class FogTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static DirectX7 start() {
        Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;Main.forceLQFog=false;
        Main.m3gPerspective=true;LightMapper.cameraVectorLight=false;
        DirectX7 g=M3GTest.renderer();DirectX7.fDist=1000;DirectX7.fogc=0x326496;return g;
    }
    private static Texture texture(int mode,int pixel) {
        Texture t=M3GTest.texture(pixel);t.drawmode=(byte)mode;return t;
    }
    private static Polygon3V flat(int depth) {
        Polygon3V p=M3GTest.triangle();p.a.rz=p.b.rz=p.c.rz=-depth;return p;
    }
    private static Polygon4V quad() {
        return new Polygon4V(M3GTest.vertex(2,2,-100),M3GTest.vertex(10,2,-200),
                M3GTest.vertex(10,10,-900),M3GTest.vertex(2,10,-400),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }
    private static void submit(DirectX7 g,RenderObject p,Texture t) {
        M3GTest.submit(g,p,t,0);g.render();check(g.isM3GActive(),"native Fog failed: "+g.getRendererError());
    }
    private static int mix(int rgb,int fog,int amount) {
        int result=0;
        for(int shift=0;shift<=16;shift+=8) result|=((((rgb>>shift)&255)*(255-amount)+((fog>>shift)&255)*amount)/255)<<shift;
        return result;
    }
    private static void nearColor(int actual,int expected) {
        for(int shift=0;shift<=16;shift+=8) check(Math.abs(((actual>>shift)&255)-((expected>>shift)&255))<=2,"Fog equation mismatch");
    }
    public static void main(String[] args) {
        int[] modes={3,6};
        for(int mode:modes) for(int perspective:new int[]{0,4}) for(int quality:new int[]{1,2}) {
            DirectX7 g=start();Main.persQ=perspective;Main.fogQ=quality;
            Texture t=texture(mode,0xffd06020);
            for(boolean force:new boolean[]{false,true}) for(boolean hardware:new boolean[]{false,true}) {
                Main.forceLQFog=force;Main.m3gPerspective=hardware;
                for(int depth:new int[]{0,100,500,1000,2000}) {
                    int calls=Graphics3D.calls;submit(g,flat(depth),t);
                    check(Graphics3D.calls==calls+1,"fog must be single pass for every native material/quality");
                    Graphics3D.Draw d=last();int color=(mode==3)?DirectX7.fogc:0;
                    check(d.fog && d.fogColor==color,"missing/wrong native Fog material "+mode);
                    check(d.near==1 && d.far==1.0f+(float)DirectX7.fDist/TexturingPers.move,"wrong native Fog endpoints");
                    check(d.function==Texture2D.FUNC_MODULATE && d.blend==CompositingMode.REPLACE,"legacy ADD/overlay path still active");
                    check(d.perspective==(perspective==4 && hardware),"affine UV mode or HW toggle lost");
                    for(byte c:d.colors) check((c&255)==255,"CPU fog attenuation still packed in colors");
                    nearColor(g.display[0],mix(0xd06020,color,Math.min(255,depth*255/1000)));
                }
            }
            // No texture upload for color, range or FOV changes; native state only.
            int uploads=Graphics3D.uploads;
            DirectX7.fDist=200;DirectX7.fogc=0x002244;g.updateFov(40);submit(g,flat(100),t);
            check(Graphics3D.uploads==uploads,"Fog state change rebuilt texture");
            nearColor(g.display[0],mix(0xd06020,(mode==3)?0x002244:0,127));
            Main.fogQ=0;submit(g,flat(100),t);check(!last().fog,"fogQ=0 must disable native Fog");
            nearColor(g.display[0],0xd06020);g.destroy();
        }
        System.out.println("PASS single-pass native Fog 3/6 / HQ+LQ+forceLQ / affine+perspective / HW ON+OFF / distance+color+zoom / OFF / no CPU RGB fading");

        for(int mode:modes) {
            DirectX7 g=start();Main.persQ=0;
            Polygon3V p=M3GTest.triangle();submit(g,p,texture(mode,0xffffffff));Graphics3D.Draw d=last();
            Vertex[] vertices={p.a,p.b,p.c};
            for(int i=0;i<3;i++) {
                float expected=1.0f-(float)vertices[i].rz/TexturingPers.move;
                float actual=-d.positions[i*3+2]*d.scale;
                check(Math.abs(expected-actual)<=expected/128,"affine Fog reused artificial constant depth");
                check((double)d.positions[i*3]/-d.positions[i*3+2]==vertices[i].sx-g.centreX,"screen X drift");
                check((double)d.positions[i*3+1]/-d.positions[i*3+2]==g.centreY-vertices[i].sy,"screen Y drift");
            }
            check(d.positions[2]!=d.positions[8] && !d.perspective,"affine Fog flattened real depth or enabled perspective UVs");
            // Interleave native-affine Fog, ordinary world, perspective Fog and helpers.
            submit(g,p,texture(0,0xffffffff));check(!last().fog && last().perspective,"affine material state leaked");
            Main.persQ=4;submit(g,p,texture(mode,0xffffffff));check(last().fog && last().perspective,"native world mode not restored");
            submit(g,new SolidSky(0x123456),null);check(!last().fog && last().cull==PolygonMode.CULL_NONE,"Fog leaked into sky");
            Main.persQ=0;Main.m3gPerspective=false;submit(g,p,texture(mode,0xffffffff));
            Main.m3gPerspective=true;submit(g,p,texture(mode,0xffffffff));check(!last().perspective,"HW preference overwrote affine Fog UV mode");
            check(Main.persQ==0,"native Fog changed legacy PERSQ");g.destroy();
        }
        System.out.println("PASS affine real eye Z / exact screen endpoints / no flattening of varying weights / affine UV mode isolated from cached HW modes / world+sky state restoration");

        for(int mode:modes) for(int perspective:new int[]{0,4}) {
            DirectX7 g=start();Main.persQ=perspective;
            g.clearDisplay(0xffabcdef);submit(g,flat(500),texture(mode,0));
            check(g.display[0]==0xffabcdef,"native Fog filled a transparent hole");
            submit(g,flat(500),texture(mode,0x80d06020));
            check(last().textureAlpha==255,"native Fog changed nonzero/opaque semantics");
            nearColor(g.display[0],mix(0xd06020,(mode==3)?DirectX7.fogc:0,127));
            for(int plain:new int[]{0,1,2,4,5,7,8,9,10,11,12,13}) {
                submit(g,M3GTest.triangle(),texture(plain,0x80abcdef));check(!last().fog,"Fog leaked into material "+plain);
            }
            Main.fogQ=1;Main.forceLQFog=true;
            int calls=Graphics3D.calls;submit(g,quad(),texture(mode,0xffd06020));
            check(Graphics3D.calls==calls+1 && last().fog,"LQ quad still uses per-triangle CPU fog passes");
            g.destroy();
        }
        System.out.println("PASS texture cutouts / legacy nonzero alpha / no fog in unrelated materials / single-pass LQ quads");

        DirectX7 g=start();Texture t=texture(6,0xffffffff);Polygon3V p=M3GTest.triangle();
        submit(g,p,t);byte[] original=last().colors.clone();
        LightMapper.cameraVectorLight=true;g.flashlightEnabled=true;g.shootIntensity=500;
        submit(g,p,t);check(last().fog && java.util.Arrays.equals(original,last().colors),"native Fog evaluated legacy camera lighting");g.destroy();
        LightMapper.cameraVectorLight=false;

        for(int mode:modes) {
            g=start();Main.persQ=0;t=texture(mode,0xffd06020);
            DirectX7 cpu=new DirectX7(32,32);DirectX7.fDist=1000;cpu.clearDisplay(0x123456);
            M3GTest.submit(cpu,M3GTest.triangle(),t,0);cpu.render();
            Graphics3D.failAt=Graphics3D.calls+1;
            M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();Graphics3D.failAt=0;
            check(!g.isM3GActive() && Graphics3D.target==null,"Fog failure did not disable/release native backend");
            check(java.util.Arrays.equals(cpu.display,g.display),"Fog failure didn't replay original full CPU frame mode "+mode);
            g.destroy();cpu.destroy();
            g=start();M3GSceneRenderer backend=new M3GSceneRenderer();
            for(int distance:new int[]{-1,0,1}) {
                DirectX7.fDist=distance;check(!backend.draw(g,p,t,false),"invalid native range must fall back");
            }
            backend.dispose();g.destroy();
        }
        Main.forceLQFog=false;Main.persQ=4;Main.m3gPerspective=true;
        System.out.println("PASS native-only Fog without camera lighting / failure releases target + original full CPU replay / degenerate range fallback");
    }
}
