package code.Rendering;

import code.Gameplay.Map.House;
import code.Gameplay.Map.Room;
import code.Gameplay.Map.Skybox;
import code.Rendering.Meshes.*;
import code.Math.Matrix;
import code.HUD.DeveloperMenu;
import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Actual background/world queues and native transfer counters, not FPS tests. */
public final class CombinedPassTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Object field(Object o,String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void pending(DirectX7 g,boolean value) throws Exception {
        check(((Boolean)field(g,"backgroundPending")).booleanValue()==value,"background pending state");
        RenderObjectBuffer q=(RenderObjectBuffer)field(g,"backgroundBuffer");
        if(!value && q!=null) {
            check(q.getSize()==0,"sky queue not reset");
            for(TMPElement e:q.getBuffer()) check(e.obj==null && e.tex==null,"sky queue retains old resources");
        }
    }
    private static Skybox sky(int kind) {
        if(kind==0) return new Skybox(0xff0000);
        if(kind==1) {
            Skybox p=new Skybox(M3GTest.texture(0xffff0000),2,1,-91,0.3f,0,0xff0000,0xff0000);
            p.skyboxAlways=true;return p;
        }
        Skybox flash=new Skybox(0xff0000);flash.lighting=true;return flash;
    }
    private static void frame(DirectX7 g,Skybox sky,Texture world,int depth) {
        sky.render(null,g);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,depth);g.render();
    }
    private static SkyMesh model() {
        Vertex a=new Vertex(-40,-40,-100),b=new Vertex(40,-40,-100),c=new Vertex(-40,40,-100);
        Polygon3V p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture t=new MultyTexture();t.textures=new Texture[]{M3GTest.texture(0xffff0000)};
        SkyMesh sky=new SkyMesh(new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p},t));
        sky.set(new Matrix(),0,0,32,32);return sky;
    }
    public static void main(String[] args) throws Exception {
        for(int kind=0;kind<3;kind++) {
            DirectX7 g=M3GTest.renderer();Texture world=M3GTest.texture(0xff00ff00);
            // Warm all image variants first; cold texture uploads can still need
            // an existing native/CPU barrier, independently of the old sky pass.
            frame(g,sky(kind),world,Integer.MIN_VALUE);
            Object backup=field(g,"frameBackup"),queue=field(g,"backgroundBuffer");
            for(int depth:new int[]{Integer.MIN_VALUE,-1,0,Integer.MAX_VALUE}) {
                int calls=Graphics3D.calls,binds=Graphics3D.binds,releases=Graphics3D.releases;
                int reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls,uploads=Graphics3D.uploads;
                Skybox s=sky(kind);
                // Panorama texture identity must remain stable on the warm run.
                if(kind==1) {
                    s=sky(1);frame(g,s,world,depth);
                    calls=Graphics3D.calls;binds=Graphics3D.binds;releases=Graphics3D.releases;
                    reads=Image.getRGBCalls;copies=Graphics.drawRGBCalls;uploads=Graphics3D.uploads;
                }
                s.render(null,g);g.renderBackground();pending(g,true);
                check(Graphics3D.calls==calls && Image.getRGBCalls==reads && Graphics.drawRGBCalls==copies,"sky rendered/read back before world");
                M3GTest.submit(g,M3GTest.triangle(),world,depth);g.render();
                check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1 && Image.getRGBCalls==reads+1,
                        "warm sky+world didn't share one target/readback");
                check(Graphics.drawRGBCalls==copies && Graphics3D.uploads==uploads,"combined opaque sky uploaded old frame/texture");
                check(g.display[0]==0xff00ff00,"world sorted behind sky at depth "+depth);
                check(field(g,"frameBackup")==backup && field(g,"backgroundBuffer")==queue,"per-frame backup/sky queue allocation");
                pending(g,false);
                calls=Graphics3D.calls;g.render();check(Graphics3D.calls==calls,"stale sky replayed on empty frame");
            }
            g.destroy();
        }
        System.out.println("PASS solid+panorama+storm prefix / independent world sorting / one warm bind+release+readback / no intermediate upload / reused backup+queue");

        DirectX7 g=M3GTest.renderer();SkyMesh model=model();Texture world=M3GTest.texture(0xff0000ff);
        M3GTest.submit(g,model,null,0);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
        int binds=Graphics3D.binds,reads=Image.getRGBCalls,modelCalls=Graphics3D.calls;
        M3GTest.submit(g,model,null,0);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,Integer.MIN_VALUE);g.render();
        check(Graphics3D.calls>=modelCalls+2 && Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1 && g.display[0]==0xff0000ff,"model sky forces a separate pass");
        Graphics3D.Draw last=Graphics3D.history.lastElement();
        check(Math.abs(last.projection[0]-2.0f/g.width)<0.0001,"model sky camera leaked into world");g.destroy();
        System.out.println("PASS model sky+world share bound target / world camera restored");

        // Exercise the real House entry points, including early sky-only exits.
        for(boolean array:new boolean[]{false,true}) {
            g=M3GTest.renderer();House house=new House(new Room[0],new Room[0][]);house.setSkybox(new Skybox(0xff0000));
            int calls=Graphics3D.calls;int[] before=g.display.clone();
            int count=array?house.render(null,g,new int[0],0,0):house.render(null,g,-1,0,0);
            check(count==0 && Graphics3D.calls==calls && Arrays.equals(before,g.display),"House still renders sky immediately");pending(g,true);
            g.render();check((g.display[0]&0xffffff)==0xff0000,"sky-only early exit dropped sky");pending(g,false);
            for(TMPElement e:((RenderObjectBuffer)field(g,"buffer")).getBuffer()) check(e.obj==null && e.tex==null,"world pool retained duplicate sky after transfer");
            house.setSkybox(new Skybox(0xff0000));house.render(null,g,-1,0,0);
            M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff00ff00),Integer.MIN_VALUE);g.render();
            check(g.display[0]==0xff00ff00,"House merged sorting occluded level");house.destroy();g.destroy();
        }
        System.out.println("PASS actual House single/array entry points / deferred sky-only frame / world over background");

        // A failure in sky, first world draw, or final readback must roll back
        // BOTH stages, not replay the world on a partially native background.
        for(int kind=0;kind<3;kind++) for(int failure=0;failure<7;failure++) {
            if(failure==6 && kind!=2) continue;
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture t=M3GTest.texture(0x8080a0c0);t.drawmode=4;
            cpu.setRenderer(DirectX7.M3G);sky(kind).render(null,cpu);cpu.renderBackground();
            M3GTest.submit(cpu,M3GTest.triangle(),t,Integer.MIN_VALUE);cpu.renderSoftware();
            Skybox s=sky(kind);frame(g,s,t,Integer.MIN_VALUE);g.clearDisplay(0x123456);
            if(kind==2) s.lighting=true;
            s.render(null,g);g.renderBackground();
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(failure==1) Graphics3D.failAt=Graphics3D.calls+(kind==1?4:kind==2?1:2);
            if(failure==2) System.setProperty("readback.fail","true");
            if(failure==3) System.setProperty("m3g.oom","true");
            if(failure==4) g.flushAlpha=true;
            if(failure==5) g.setRenderer(DirectX7.SOFTWARE);
            if(failure==6) System.setProperty("clear.fail","true");
            M3GTest.submit(g,M3GTest.triangle(),t,Integer.MIN_VALUE);g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");System.clearProperty("clear.fail");
            check(Arrays.equals(cpu.display,g.display),"combined rollback differed from serial CPU: "+kind+"/"+failure);
            check(Graphics3D.target==null,"combined failure target leak");pending(g,false);g.destroy();cpu.destroy();
        }
        System.out.println("PASS warm sky/world draw+final-readback+OOM+storm-clear failures replay both queues / pending alpha+renderer switch / complete rollback+cleanup");

        // A real CPU barrier still sees the already drawn native sky; preserve
        // the original barrier rather than trying to eliminate required copies.
        g=M3GTest.renderer();new Skybox(0xff0000).render(null,g);g.renderBackground();
        M3GTest.submit(g,new M3GTest.Marker(true),null,-200);
        M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff0000ff),-100);g.render();
        check(g.display[0]==0xff0000ff && Graphics3D.before==0xff00ff00,"sky/CPU/world painter barrier");pending(g,false);g.destroy();
        // Panorama owner viewport can be changed while gathering next-frame
        // portal visibility. The deferred fallback must use its captured bounds.
        g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        Skybox panorama=sky(1),reference=sky(1);panorama.skyboxAlways=reference.skyboxAlways=false;
        panorama.x1=reference.x1=2;panorama.y1=reference.y1=3;panorama.x2=reference.x2=29;panorama.y2=reference.y2=30;
        reference.render(null,cpu);panorama.render(null,g);g.renderBackground();
        panorama.x1=7;panorama.y1=8;panorama.x2=20;panorama.y2=21;g.flushAlpha=true;g.render();
        check(Arrays.equals(cpu.display,g.display) && panorama.x1==7 && panorama.y2==21,"deferred panorama used/damaged next portal viewport");pending(g,false);g.destroy();cpu.destroy();
        System.out.println("PASS necessary CPU barriers preserved / deferred panorama captures old viewport+restores new portal state");

        for(int bypass=0;bypass<3;bypass++) {
            g=M3GTest.renderer();if(bypass==0) g.setRenderer(DirectX7.SOFTWARE);
            if(bypass==1) g.flushAlpha=true;if(bypass==2) DeveloperMenu.renderPolygonsOverwrite=true;
            new Skybox(0x123456).render(null,g);g.renderBackground();
            check((g.display[0]&0xffffff)==0x123456,"original immediate sky bypass changed");pending(g,false);
            DeveloperMenu.renderPolygonsOverwrite=false;g.destroy();
        }
        g=M3GTest.renderer();new Skybox(0xff0000).render(null,g);g.renderBackground();g.resize(40,24);pending(g,false);
        int calls=Graphics3D.calls;g.render();check(Graphics3D.calls==calls,"resize retained old sky queue");g.destroy();
        g=M3GTest.renderer();new Skybox(0xff0000).render(null,g);g.renderBackground();g.destroy();pending(g,false);
        // Prefix cleanup even when the original CPU object itself throws.
        g=M3GTest.renderer();new Skybox(0xff0000).render(null,g);g.renderBackground();
        M3GTest.submit(g,new Throwing(),null,0);boolean thrown=false;
        try {g.render();} catch(IllegalStateException ex) {thrown=true;}
        check(thrown && Graphics3D.target==null,"CPU exception swallowed/leaked native target");pending(g,false);g.destroy();
        System.out.println("PASS Software/debug/alpha immediate behavior / resize+destroy+throw cleanup / no stale queued sky");
    }
    private static final class Throwing extends RenderObject {
        public boolean isVisible(int a,int b,int c,int d) {return true;}
        public void render(DirectX7 g,Texture t) {throw new IllegalStateException("CPU failure");}
        public void renderFast(DirectX7 g,Texture t) {render(g,t);}
    }
}
