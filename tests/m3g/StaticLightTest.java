package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.Graphics3D;

/** Baked vertex-light contracts, not visual tests or a replacement lightmap loader. */
public final class StaticLightTest {
    private static void check(boolean ok,String message) { M3GTest.check(ok,message); }
    private static RenderObject polygon(boolean rgb,boolean quad) {
        Vertex a=M3GTest.vertex(2,2,-100),b=M3GTest.vertex(10,2,-200),
                c=M3GTest.vertex(10,10,-300),d=M3GTest.vertex(2,10,-400);
        if(!quad) c=M3GTest.vertex(2,10,-300);
        RenderObject p;
        if(rgb && quad) {
            ColorLightedPolygon4V q=new ColorLightedPolygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            q.ar=20;q.ag=100;q.ab=200;q.br=80;q.bg=160;q.bb=240;q.cr=255;q.cg=64;q.cb=0;q.dr=50;q.dg=60;q.db=70;p=q;
        } else if(rgb) {
            ColorLightedPolygon3V q=new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
            q.ar=20;q.ag=100;q.ab=200;q.br=80;q.bg=160;q.bb=240;q.cr=255;q.cg=64;q.cb=0;p=q;
        } else if(quad) {
            LightedPolygon4V q=new LightedPolygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            q.la=20;q.lb=80;q.lc=255;q.ld=50;p=q;
        } else {
            LightedPolygon3V q=new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
            q.la=20;q.lb=80;q.lc=255;p=q;
        }
        p.nx=p.ny=0;p.nz=-4096;return p;
    }
    private static void submit(DirectX7 g,RenderObject p,Texture t) { M3GTest.submit(g,p,t,0);g.render(); }
    private static int expected(int value,int depth,int distance) {
        value=Math.max(0,Math.min(255,value));
        int fog=distance<=1?0:Math.min(255,Math.max(0,depth*255/distance));
        return value*(255-fog)/255;
    }
    private static void colors(byte[] colors,boolean rgb,int count,int quality,int distance) {
        int[][] values={{20,100,200},{80,160,240},{255,64,0},{50,60,70}};
        for(int i=0;i<count;i++) {
            int r=expected(values[i][0],(i+1)*100,distance);
            int g=rgb?expected(values[i][1],(i+1)*100,distance):r;
            int b=rgb?expected(values[i][2],(i+1)*100,distance):r;
            if(quality==0) r=g=b=255;
            else if(rgb && quality==1) r=g=b=(r+g+b)/3;
            check((colors[i*4]&255)==r && (colors[i*4+1]&255)==g && (colors[i*4+2]&255)==b,
                    "wrong baked RGB at vertex "+i);
            check((colors[i*4+3]&255)==255,"lightmap changed vertex alpha");
        }
    }
    public static void main(String[] args) {
        Main.forceLQFog=false;
        for(boolean rgb:new boolean[]{false,true}) for(boolean quad:new boolean[]{false,true}) {
            DirectX7 g=M3GTest.renderer();DirectX7.fDist=1000;
            RenderObject p=polygon(rgb,quad);Texture t=M3GTest.texture(0xffffffff);t.drawmode=(byte)(rgb?13:9);
            int uploads=Graphics3D.uploads;
            for(int perspective:new int[]{0,1,4}) for(boolean mip:new boolean[]{false,true}) for(int quality:new int[]{0,1,2}) {
                Main.persQ=perspective;Main.mipMapping=mip;Main.fogQ=quality;
                g.shootIntensity=0;g.shootLength=100;g.flashlightEnabled=false;LightMapper.cameraVectorLight=false;
                int calls=Graphics3D.calls;submit(g,p,t);
                check(Graphics3D.calls==calls+1,"lightmap mode fell back to CPU");
                byte[] baseline=Graphics3D.history.lastElement().colors.clone();
                colors(baseline,rgb,quad?4:3,quality,1000);
                g.shootIntensity=500;g.shootLength=0;g.flashlightEnabled=false;LightMapper.cameraVectorLight=true;
                g.camera.m03=999;g.camera.m13=-999;submit(g,p,t);
                check(Arrays.equals(baseline,Graphics3D.history.lastElement().colors),"muzzle/camera changed static lightmap");
                g.flashlightEnabled=true;submit(g,p,t);
                check(Arrays.equals(baseline,Graphics3D.history.lastElement().colors),"flashlight changed static lightmap");
            }
            check(Graphics3D.uploads==uploads+1,"lighting state caused texture reuploads");
            Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;
            for(int distance:new int[]{-1,0,1,200,2000}) {
                DirectX7.fDist=distance;submit(g,p,t);
                colors(Graphics3D.history.lastElement().colors,rgb,quad?4:3,2,distance);
            }
            g.destroy();
        }
        System.out.println("PASS static grayscale9 / RGB13 / triangle+quad / quality+fast / no muzzle+torch+camera / distance fade");

        DirectX7 cutout=M3GTest.renderer();Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;
        Texture hole=M3GTest.texture(0);hole.drawmode=13;cutout.clearDisplay(0xff123456);
        submit(cutout,polygon(true,false),hole);
        check(cutout.display[0]==0xff123456,"RGB lightmap filled transparent texture holes");
        Texture opaque=M3GTest.texture(0x80ffffff);opaque.drawmode=13;submit(cutout,polygon(true,false),opaque);
        check(Graphics3D.history.lastElement().textureAlpha==255,"mode13 lost nonzero/opaque semantics");
        opaque.drawmode=0;submit(cutout,polygon(true,false),opaque);
        for(byte c:Graphics3D.history.lastElement().colors) check((c&255)==255,"baked RGB leaked into ordinary material");
        cutout.destroy();

        Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;DirectX7.fDist=1000;
        for(boolean rgb:new boolean[]{false,true}) {
            Texture t=M3GTest.texture(0xffffffff);t.drawmode=(byte)(rgb?13:9);
            RenderObject p=polygon(rgb,false);((Polygon3V)p).a.rz=1; // Force the original CPU geometry path.
            DirectX7 off=M3GTest.renderer(),on=M3GTest.renderer();DirectX7.fDist=1000;
            LightMapper.cameraVectorLight=false;submit(off,p,t);
            on.shootIntensity=500;on.shootLength=0;on.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
            int calls=Graphics3D.calls;submit(on,p,t);
            check(Graphics3D.calls==calls && Arrays.equals(off.display,on.display),"CPU material fallback re-enabled dynamic light");
            check(!on.isStaticLightmapPass() && on.shootIntensity==500 && on.shootLength==0 && on.flashlightEnabled
                    && LightMapper.cameraVectorLight,"fallback leaked light state");
            // Native failure must replay the same static fallback, not flash for a frame.
            ((Polygon3V)p).a.rz=-100;off.clearDisplay(0x123456);on.clearDisplay(0x123456);
            on.releaseRenderer();Graphics3D.failAt=Graphics3D.calls+1;submit(on,p,t);Graphics3D.failAt=0;
            off.setRenderer(DirectX7.SOFTWARE);off.setRenderer(DirectX7.M3G);
            Graphics3D.failAt=Graphics3D.calls+1;submit(off,p,t);Graphics3D.failAt=0;
            check(Arrays.equals(off.display,on.display) && !on.isM3GActive(),"failure replay changed baked light");
            off.destroy();on.destroy();
        }
        System.out.println("PASS static lighting through CPU material fallback / failed native replay / state restoration");

        DirectX7 g=M3GTest.renderer();DirectX7.fDist=1000;
        g.flashlightEnabled=true;g.shootIntensity=100;g.shootLength=0;LightMapper.cameraVectorLight=true;
        RenderObject probe=new RenderObject() {
            public boolean isVisible(int a,int b,int c,int d) { return true; }
            public void renderFast(DirectX7 g,Texture t) { render(g,t); }
            public void render(DirectX7 g,Texture t) {
                check(g.isStaticLightmapPass(),"missing CPU static scope");
                check(getLight(M3GTest.vertex(0,0,-100),100,g)==expected(100,100,1000),"CPU static scalar");
                int[] c=getLight(M3GTest.vertex(0,0,-100),20,100,200,g);
                check(c[0]==expected(20,100,1000) && c[2]==expected(200,100,1000),"CPU static RGB");
                throw new IllegalStateException("scope test");
            }
        };
        Texture t=M3GTest.texture(0xffffffff);t.drawmode=13;
        try {g.drawRenderObject(probe,t,false);throw new AssertionError("missing probe exception");}
        catch(IllegalStateException expected) { }
        check(!g.isStaticLightmapPass() && g.flashlightEnabled && LightMapper.cameraVectorLight,"exception leaked static scope");g.destroy();

        // Software keeps its existing response to the same dynamic inputs.
        DirectX7 cpu=new DirectX7(32,32);DirectX7.fDist=1000;
        LightedPolygon3V p=(LightedPolygon3V)polygon(false,false);t.drawmode=9;
        LightMapper.cameraVectorLight=false;submit(cpu,p,t);int[] dark=cpu.display.clone();
        cpu.flashlightEnabled=true;cpu.shootIntensity=cpu.shootLength=100;submit(cpu,p,t);
        check(!Arrays.equals(dark,cpu.display),"Software lost original flashlight response");
        check(!cpu.isStaticLightmapPass(),"Software entered static-only scope");cpu.destroy();
        LightMapper.cameraVectorLight=false;Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;
        System.out.println("PASS exception-safe CPU scope / unchanged original Software dynamic light");
    }
}
