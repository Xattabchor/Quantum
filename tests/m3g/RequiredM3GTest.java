package code.Rendering;

import code.HUD.DeveloperMenu;
import code.Rendering.Meshes.SkyMesh;
import code.utils.Main;
import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Required runtime contracts. Not a GPU renderer or a phone benchmark. */
public final class RequiredM3GTest {
    static void check(boolean value,String message) {M3GTest.check(value,message);}
    static DirectX7 required() {DirectX7 g=M3GTest.renderer();g.requireM3G();return g;}
    static void finish(DirectX7 g) {g.renderGameFrameImage(null,false,false,null,0,null);}
    static void scene(DirectX7 g) {
        M3GTest.submit(g,new SolidSky(0x345678),null,-10000);g.renderBackground();
        M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff908070),0);
    }
    static void failed(DirectX7 g) throws Exception {
        check(g.isM3GRequired() && !g.isM3GActive() && g.getRendererError()!=null,"failure not latched");
        check(Graphics3D.target==null,"target leaked after required failure");
        Field backup=DirectX7.class.getDeclaredField("frameBackup");backup.setAccessible(true);
        check(backup.get(g)==null,"required path allocated a replay backup");
        boolean blocked=false;
        try {g.getDisplay();}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"failed frame exposed through snapshot API");
        blocked=false;
        try {g.flush(Image.createImage(32,32).getGraphics(),0,0);}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"failed frame was presented");
        int calls=Graphics3D.calls;blocked=false;
        try {scene(g);finish(g);}catch(IllegalStateException expected){blocked=true;}
        check(blocked && Graphics3D.calls==calls,"latched backend silently retried");
        g.retryM3G();
        check(g.isM3GRequired() && g.getRendererError()==null,"explicit retry lost policy");
        for(int c:g.display)check(c==0,"retry retained partial failure pixels");
        scene(g);finish(g);check(g.isM3GActive(),"explicit retry failed");
        check(backup.get(g)==null,"retry created CPU backup");g.destroy();
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int mode=0;mode<=13;mode++) {
            DirectX7 legacy=M3GTest.renderer(),g=required();
            Texture texture=M3GTest.texture(0xff8090a0);texture.drawmode=(byte)mode;
            DirectX7.setFogDist(10000);
            for(DirectX7 r:new DirectX7[]{legacy,g}) {
                M3GTest.submit(r,M3GTest.triangle(),texture,0);r.render();
                check(r.isM3GActive(),"mode not native: "+mode+" "+r.getRendererError());
            }
            check(Arrays.equals(legacy.display,g.display),"mode changed in required path: "+mode);
            legacy.destroy();g.destroy();
        }
        DirectX7 g=required();scene(g);finish(g);g.getDisplay();
        // Warm texture resources; closed backgrounds need no final/next-frame readback.
        Texture warm=M3GTest.texture(0xff908070);
        M3GTest.submit(g,M3GTest.triangle(),warm,0);g.render();
        int reads=Image.getRGBCalls;
        for(int i=0;i<4;i++) {
            M3GTest.submit(g,new SolidSky(0x345678),null,-10000);g.renderBackground();
            M3GTest.submit(g,M3GTest.triangle(),warm,0);finish(g);
            g.flush(Image.createImage(32,32).getGraphics(),0,0);
        }
        check(Image.getRGBCalls==reads,"warm required Image path read pixels");
        boolean blocked=false;
        try {g.setRenderer(DirectX7.SOFTWARE);}catch(IllegalArgumentException expected){blocked=true;}
        check(blocked && g.getRenderer()==DirectX7.M3G,"required renderer changed to Software");
        g.destroy();
        System.out.println("PASS required material modes 0-13 unchanged / warm drawImage / no automatic Software switch");

        // A recognized wrapper can prepare/project children, but not rasterize them.
        g=required();code.Rendering.Meshes.Polygon3V p=M3GTest.triangle();
        MultyTexture materials=new MultyTexture();materials.textures=new Texture[]{M3GTest.texture(0xff112233)};
        code.Rendering.Meshes.Mesh mesh=new code.Rendering.Meshes.Mesh(new Vertex[]{p.a,p.b,p.c},new RenderObject[]{p},materials);
        code.Rendering.Meshes.MeshImage image=new code.Rendering.Meshes.MeshImage(mesh);
        M3GTest.submit(g,image,null,0);finish(g);check(g.isM3GActive(),"MeshImage wrapper rejected");g.destroy();

        g=required();DirectX7.fDist=1000;
        for(int mode=0;mode<=3;mode++) {
            M3GTest.submit(g,SpriteTest.sprite(M3GTest.texture(0x8090a0b0),mode),null,mode);
        }
        ScreenLines weather=new ScreenLines();weather.rect(2,3,1,1,0x99d49f);
        check(g.renderGameFrameImage(new byte[1024],true,true,null,0,weather),"native tail was rejected");
        Graphics3D.Draw last=Graphics3D.history.lastElement();
        check(!last.textured && last.colorComponents==3,"weather did not follow post-effects");
        Field backup=DirectX7.class.getDeclaredField("frameBackup");backup.setAccessible(true);
        check(backup.get(g)==null,"successful required tail allocated replay backup");g.destroy();
        g=required();SkyMesh supported=SkyTest.cube();supported.set(new code.Math.Matrix(),0,0,32,32);
        M3GTest.submit(g,supported,null,0);finish(g);check(g.isM3GActive(),"compatible mesh sky rejected");g.destroy();
        System.out.println("PASS required sprites 0-3 / native post-effects + RGB weather / compatible mesh sky / no replay backup");

        // Rejection after an earlier native segment must not call the CPU object.
        g=required();M3GTest.Marker marker=new M3GTest.Marker(false);
        M3GTest.queue(g,false);M3GTest.submit(g,marker,null,0);
        blocked=false;try {finish(g);}catch(IllegalStateException expected){blocked=true;}
        check(blocked && marker.calls==0,"unsupported object rasterized or silently dropped");failed(g);
        g=required();marker=new M3GTest.Marker(false);blocked=false;
        try {g.drawSoftwareObject(marker,null,false);}catch(IllegalStateException expected){blocked=true;}
        check(blocked && marker.calls==0,"direct CPU entry bypassed policy");failed(g);
        g=required();scene(g);blocked=false;
        try {g.renderSoftware();}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"renderSoftware bypassed policy");failed(g);
        System.out.println("PASS wrapper child dispatch / rejected object, explicit CPU dispatch and renderSoftware fail closed / retry");

        // Whole SkyMesh rejection must not enter its CPU clear/project/faces path.
        g=required();SkyMesh sky=SkyTest.cube();sky.mesh.getTexture().textures[0].drawmode=12;
        sky.set(new code.Math.Matrix(),0,0,32,32);M3GTest.submit(g,sky,null,0);
        blocked=false;try {finish(g);}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"unsupported EXP sky silently rendered/replayed");failed(g);
        g=required();ScreenLines bad=new ScreenLines();bad.line(0,0,10,10,1000,0xffffff,false);
        blocked=false;try {g.renderGameFrameImage(null,false,false,null,0,bad);}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"unsupported weather requested LCDUI fallback");failed(g);
        g=required();marker=new M3GTest.Marker(false);blocked=false;
        try {g.renderOverlay(marker);}catch(IllegalStateException expected){blocked=true;}
        check(blocked && marker.calls==0,"unsupported overlay ignored");failed(g);

        g=required();scene(g);g.flushAlpha=true;blocked=false;
        try {finish(g);}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"flushAlpha silently selected Software");g.flushAlpha=false;failed(g);
        g=required();DeveloperMenu.renderPolygonsOverwrite=true;blocked=false;
        try {finish(g);}catch(IllegalStateException expected){blocked=true;}
        finally {DeveloperMenu.renderPolygonsOverwrite=false;}
        check(blocked,"CPU debug rasterizer bypassed policy");failed(g);

        for(String fault:new String[]{"texture.upload.fail","effects.upload.fail","m3g.oom","release.fail","readback.fail"}) {
            g=required();scene(g);System.setProperty(fault,"true");blocked=false;
            try {
                if(fault.equals("readback.fail"))g.render();
                else g.renderGameFrameImage(new byte[1024],false,true,null,0,null);
            }catch(IllegalStateException expected){blocked=true;}
            finally {System.clearProperty(fault);}
            check(blocked,"native fault silently recovered on CPU: "+fault);failed(g);
        }
        g=required();scene(g);Graphics3D.failAt=Graphics3D.calls+1;blocked=false;
        try {finish(g);}catch(IllegalStateException expected){blocked=true;}
        finally {Graphics3D.failAt=0;}
        check(blocked,"native render exception silently replayed");failed(g);
        g=required();scene(g);finish(g);System.setProperty("readback.fail","true");blocked=false;
        try {g.getDisplay();}catch(IllegalStateException expected){blocked=true;}
        finally {System.clearProperty("readback.fail");}
        check(blocked,"lazy snapshot readback failure did not latch");failed(g);
        g=required();scene(g);finish(g);
        System.setProperty("image.blit.fail","true");System.setProperty("readback.fail","true");blocked=false;
        try {g.flush(Image.createImage(32,32).getGraphics(),0,0);}catch(IllegalStateException expected){blocked=true;}
        finally {System.clearProperty("image.blit.fail");System.clearProperty("readback.fail");}
        check(blocked,"image blit + eager presentation failure did not latch");failed(g);
        g=required();blocked=false;
        try {g.drawLine(0,0,10,10,1,0xffffff);}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"CPU line rasterizer bypassed policy");failed(g);
        g=required();code.utils.WeatherGenerator wg=new code.utils.WeatherGenerator(32,32,0,0,0,0,0,false,null,1,1,1,1,1,1);
        DeveloperMenu.drawPortals=true;blocked=false;
        try {wg.prepareM3G(g);}catch(IllegalStateException expected){blocked=true;}
        finally {DeveloperMenu.drawPortals=false;}
        check(blocked,"required weather preparation requested LCDUI fallback");g.destroy();
        g=required();scene(g);finish(g);g.resize(48,40);
        check(g.isM3GRequired(),"resize cleared required policy");scene(g);finish(g);g.destroy();
        System.out.println("PASS unsupported sky/weather/overlay, alpha/debug, texture/post OOM, render/release/readback failures / no replay backup / no stale presentation / resize");
    }
}
