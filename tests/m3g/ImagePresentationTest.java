package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Presentation/ownership/rollback contracts, NOT a GPU or phone benchmark.
 * The fake native renderer samples pixel zero; no visual rasterization claims. */
public final class ImagePresentationTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static final Texture RED=M3GTest.texture(0xffff0000);
    private static DirectX7 warm() {
        DirectX7 g=M3GTest.renderer();
        M3GTest.submit(g,M3GTest.triangle(),RED,0);g.render();
        g.clearDisplay(0x123456);return g;
    }
    private static void scene(DirectX7 g) {
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();
        M3GTest.submit(g,M3GTest.triangle(),RED,0);
    }
    private static boolean finish(DirectX7 g) {
        return g.renderGameFrameImage(null,false,false,null,0,null);
    }
    private static Graphics screen() {return Image.createImage(50,50).getGraphics();}
    private static void fullFrame(DirectX7 g) {scene(g);finish(g);}
    private static void same(DirectX7 g,DirectX7 cpu,String why) {
        check(Arrays.equals(g.getDisplay(),cpu.display),why);
    }
    private static void cpuScene(DirectX7 cpu,byte[] mask,boolean marker) {
        scene(cpu);
        if(marker) M3GTest.submit(cpu,new M3GTest.Marker(false),null,-10);
        cpu.renderSoftware();
        if(mask!=null) {FrameEffects effect=new FrameEffects();effect.set(mask,false,true);effect.render(cpu,null);}
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        DirectX7 g=warm();Graphics lcd=screen();byte[] mask=new byte[32*32];
        ScreenLines weather=new ScreenLines();weather.rect(10,10,1,1,0x80a0c0);
        int reads=Image.getRGBCalls,creates=Image.creates,copies=Graphics.drawRGBCalls,blits=Graphics.drawImageCalls;
        // A readback fault must not affect native-only full-background frames.
        System.setProperty("readback.fail","true");
        for(int i=0;i<4;i++) {
            g.beginFrameStats();scene(g);
            check(g.renderGameFrameImage(mask,false,true,null,0,weather),"native weather success lost");
            check(Graphics3D.target==null,"presented a still-bound Image");
            g.flush(lcd,0,0);g.flush(lcd,0,0);
            check(g.getM3GCpuBarriers()==0,"hidden final/next-frame CPU barrier");
        }
        System.clearProperty("readback.fail");
        check(Image.getRGBCalls==reads && Graphics.drawRGBCalls==copies && Graphics.drawImageCalls==blits+8,"native-only roundtrip remains");
        check(Image.creates==creates,"second framebuffer allocated");
        int nativePixel=lcd.image.pixels[0];
        // Pause snapshot materializes once; returned buffer is writable.
        check(g.getDisplay()[0]==nativePixel && Image.getRGBCalls==reads+1,"pause snapshot stale");
        g.getDisplay()[0]=0xff010203;check(Image.getRGBCalls==reads+1,"repeat getter rereads image");
        g.flush(lcd,0,0);check(lcd.image.pixels[0]==0xff010203 && Graphics.drawImageCalls==blits+8,"CPU mutation hidden by stale image");
        g.destroy();
        System.out.println("PASS repeated full sky/world/cold post/weather -> drawImage / zero final or next-frame getRGB / single Image / pause snapshot + writable CPU pixels");

        g=warm();fullFrame(g);Graphics direct=screen(),reference=screen();
        Arrays.fill(direct.image.pixels,0xabcdef);Arrays.fill(reference.image.pixels,0xabcdef);
        direct.setClip(2,4,23,20);reference.setClip(2,4,23,20);
        direct.translate(3,2);reference.translate(3,2);
        reads=Image.getRGBCalls;g.flush(direct,5,7);
        check(Image.getRGBCalls==reads,"clipped image blit read pixels");
        int[] pixels=g.getDisplay();reference.drawRGB(pixels,0,32,5,7,32,32,false);
        check(Arrays.equals(direct.image.pixels,reference.image.pixels),"offset/translation/clip differ from drawRGB");g.destroy();
        System.out.println("PASS drawImage TOP|LEFT / screen offset + translated LCDUI clip equals original drawRGB");

        // A partial/no-sky frame must preserve previous Image pixels BEFORE its
        // single native target is overwritten, including when native fails.
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.beginFrameStats();
        M3GTest.Marker marker=new M3GTest.Marker(true);M3GTest.submit(g,marker,null,0);finish(g);
        check(marker.calls==1 && Image.getRGBCalls==reads+1 && g.getM3GCpuBarriers()==1,"CPU-only next frame didn't synchronize previous Image");
        g.flush(lcd,0,0);check(lcd.image.pixels[0]==0xff00ff00,"CPU-only final frame used stale image");
        fullFrame(g);reads=Image.getRGBCalls;g.beginFrameStats();
        M3GTest.queue(g,true);finish(g);
        check(Image.getRGBCalls==reads+2 && g.getM3GCpuBarriers()==2,"previous-frame sync + native/CPU barrier counts");
        reads=Image.getRGBCalls;g.flush(lcd,0,0);
        check(lcd.image.pixels[0]==0xff0000ff && Image.getRGBCalls==reads,"mixed final native segment not presented directly");
        g.destroy();
        g=warm();fullFrame(g);DirectX7 cpu=new DirectX7(32,32);System.arraycopy(g.getDisplay(),0,cpu.display,0,1024);
        fullFrame(g); // Recreate pending Image without reading it.
        M3GTest.queue(g,false);M3GTest.queue(cpu,false);cpu.renderSoftware();
        Graphics3D.failAt=Graphics3D.calls+2;finish(g);Graphics3D.failAt=0;
        check(!g.isM3GActive() && Graphics3D.target==null,"no-sky failure not latched/released");
        same(g,cpu,"no-sky previous framebuffer rollback");g.destroy();cpu.destroy();
        System.out.println("PASS Image -> CPU / native -> CPU -> native / exact preserved-background rollback / lazy readbacks counted as B");

        Texture panoTexture=M3GTest.texture(0xff123456);
        for(boolean partial:new boolean[]{false,true}) {
            g=warm();Skybox owner=new Skybox(panoTexture,1,1,0,1,0,0x112233,0x445566);
            PanoramaSky pano=new PanoramaSky(owner,panoTexture,0x112233,0x445566);
            pano.set(partial?3:0,partial?4:0,partial?28:32,partial?29:32,0,8,24,4096,4096);
            // Warm panorama texture, then leave a previous image pending.
            M3GTest.submit(g,pano,null,0);g.renderBackground();finish(g);g.getDisplay();fullFrame(g);
            reads=Image.getRGBCalls;M3GTest.submit(g,pano,null,0);g.renderBackground();finish(g);
            check(Image.getRGBCalls==reads+(partial?1:0),"panorama crop wrongly considered full coverage");
            g.destroy();owner.destroy();
        }
        g=warm();fullFrame(g);reads=Image.getRGBCalls;
        M3GTest.submit(g,new SkyFlash(),null,0);g.renderBackground();finish(g);
        check(Image.getRGBCalls==reads,"sky flash did not discard previous frame safely");g.destroy();
        g=warm();fullFrame(g);M3GTest.submit(g,new SkyFlash(),null,0);g.renderBackground();
        System.setProperty("clear.fail","true");finish(g);System.clearProperty("clear.fail");
        for(int c:g.display) check(c==SkyFlash.COLOR,"clear failure did not replay full sky flash");g.destroy();
        System.out.println("PASS full panorama + sky flash skip old pixels / cropped panorama preserves outside pixels / clear failure replay");
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.beginFrameStats();scene(g);
        M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff102030),1);finish(g);
        check(Image.getRGBCalls==reads+1 && g.getM3GCpuBarriers()==1,"cold texture barrier changed or final read remained");
        reads=Image.getRGBCalls;g.flush(lcd,0,0);check(Image.getRGBCalls==reads,"cold frame final flush read pixels");g.destroy();
        System.out.println("PASS cold world texture keeps exactly its required barrier, no added final readback");

        // Every native packet and releaseTarget must still replay the COMPLETE
        // current frame, even when the previous Java buffer was deliberately stale.
        ScreenLines many=new ScreenLines();for(int i=0;i<65;i++) many.rect(i%20,i%20,1,1,0x80a0c0);
        for(int failure=0;failure<7;failure++) {
            g=warm();fullFrame(g);cpu=new DirectX7(32,32);cpuScene(cpu,mask,false);
            scene(g);
            if(failure<6) Graphics3D.failAt=Graphics3D.calls+failure+1;
            else System.setProperty("release.fail","true");
            check(!g.renderGameFrameImage(mask,false,true,null,0,many),"failed frame claimed weather success");
            Graphics3D.failAt=0;System.clearProperty("release.fail");
            check(!g.isM3GActive() && Graphics3D.target==null,"failure didn't release/disable native");
            same(g,cpu,"full-sky stale-backup replay "+failure);
            blits=Graphics.drawImageCalls;g.flush(lcd,0,0);check(Graphics.drawImageCalls==blits,"failed image presented");
            g.destroy();cpu.destroy();
        }
        // Failure of an actual intermediate CPU read remains atomic too.
        g=warm();fullFrame(g);cpu=new DirectX7(32,32);cpuScene(cpu,null,true);scene(g);
        M3GTest.submit(g,new M3GTest.Marker(false),null,-10);
        System.setProperty("readback.fail","true");finish(g);System.clearProperty("readback.fail");
        same(g,cpu,"CPU barrier readback failure replay");g.destroy();cpu.destroy();
        g=warm();fullFrame(g);cpu=new DirectX7(32,32);cpuScene(cpu,mask,false);scene(g);
        System.setProperty("m3g.oom","true");g.renderGameFrameImage(mask,false,true,null,0,weather);System.clearProperty("m3g.oom");
        same(g,cpu,"image path OOM replay");g.destroy();cpu.destroy();
        System.out.println("PASS failures in sky/world/post/weather/release + intermediate getRGB + native OOM / atomic full CPU replay / no stale image or weather success");

        // A completed frame cannot be CPU-replayed after queues have gone. If a
        // later snapshot/read-before-reuse fails, do NOT return stale int pixels
        // or overwrite the last good Image. Preserve it and propagate the error.
        g=warm();fullFrame(g);System.setProperty("readback.fail","true");
        boolean thrown=false;try {g.getDisplay();}catch(IllegalStateException ex){thrown=true;}
        check(thrown,"snapshot returned stale pixels on read failure");
        marker=new M3GTest.Marker(false);M3GTest.submit(g,marker,null,0);
        thrown=false;try {finish(g);}catch(IllegalStateException ex){thrown=true;}
        check(thrown && marker.calls==0 && Graphics3D.target==null,"failed pre-frame sync touched target/ran CPU");
        reads=Image.getRGBCalls;g.flush(lcd,0,0);
        check(lcd.image.pixels[0]==0xffff0000 && Image.getRGBCalls==reads,"lost last completed image after read failure");
        System.clearProperty("readback.fail");finish(g);
        check(marker.calls==0 && g.getDisplay()[0]==0xffff0000,"failed sync retained queued objects");g.destroy();
        System.out.println("PASS deferred snapshot/read-before-reuse errors propagate, preserve completed Image and clear abandoned queue (never return stale CPU pixels)");

        for(String property:new String[]{"image.blit.fail","image.blit.oom"}) {
            g=warm();fullFrame(g);reads=Image.getRGBCalls;blits=Graphics.drawImageCalls;
            System.setProperty(property,"true");g.flush(lcd,0,0);System.clearProperty(property);
            check(g.isM3GActive() && lcd.image.pixels[0]==0xffff0000 && Image.getRGBCalls==reads+1,"drawImage failure lost RGB fallback");
            fullFrame(g);g.flush(lcd,0,0);
            check(Graphics.drawImageCalls==blits+1 && Image.getRGBCalls==reads+2,"unsupported blit was retried or stopped rasterizer");g.destroy();
        }
        System.out.println("PASS drawImage runtime/OOM fallback -> original readback/drawRGB, no repeated failed blit, M3G rasterizer retained");

        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.drawLine(1,1,2,8,1,0x445566);
        check(Image.getRGBCalls==reads+1 && g.display[0]==0xffff0000,"CPU line did not synchronize Image");
        fullFrame(g);reads=Image.getRGBCalls;g.clearDisplay(0x112233);g.flush(lcd,0,0);
        check(Image.getRGBCalls==reads && lcd.image.pixels[0]==0x112233,"clear read or retained image");
        fullFrame(g);reads=Image.getRGBCalls;g.setRenderer(DirectX7.SOFTWARE);
        check(Image.getRGBCalls==reads+1 && g.display[0]==0xffff0000,"renderer switch lost last image");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.resize(40,24);
        check(Image.getRGBCalls==reads && g.display.length==960,"resize read discarded old image");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.destroy();check(Image.getRGBCalls==reads,"destroy read discarded image");
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.display=new int[1024];g.display[0]=0x998877;g.flush(lcd,0,0);
        check(Image.getRGBCalls==reads && lcd.image.pixels[0]==0x998877,"public replacement buffer overwritten by image");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;g.releaseRenderer();
        check(Image.getRGBCalls==reads+1 && g.display[0]==0xffff0000,"releaseRenderer lost Image");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;
        check(g.renderScreenLines(weather),"standalone weather overlay rejected");
        check(Image.getRGBCalls==reads+2,"overlay didn't preserve pending Image/eager output");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;
        marker=new M3GTest.Marker(true);M3GTest.submit(g,marker,null,0);g.renderSoftware();
        check(marker.calls==1 && Image.getRGBCalls==reads+1 && g.display[0]==0xff00ff00,"legacy Software entry didn't synchronize");g.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;marker=new M3GTest.Marker(true);
        M3GTest.submit(g,marker,null,-100);M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();finish(g);
        check(marker.calls==1 && Image.getRGBCalls==reads+1,"later full sky discarded pixels required by earlier background object");g.destroy();
        System.out.println("PASS CPU line/clear / renderer switch+release / resize+destroy discard / replacement buffer / standalone overlay+Software / only FIRST background allows discard");

        for(boolean debug:new boolean[]{false,true}) {
            g=warm();fullFrame(g);reads=Image.getRGBCalls;blits=Graphics.drawImageCalls;
            g.flushAlpha=!debug;DeveloperMenu.renderPolygonsOverwrite=debug;fullFrame(g);g.flush(lcd,0,0);
            check(Image.getRGBCalls==reads+1 && Graphics.drawImageCalls==blits,"alpha/debug didn't synchronize/bypass image presentation");
            DeveloperMenu.renderPolygonsOverwrite=false;g.destroy();
        }
        cpu=new DirectX7(32,32);scene(cpu);reads=Image.getRGBCalls;blits=Graphics.drawImageCalls;
        check(!finish(cpu),"Software claimed native weather");cpu.flush(lcd,0,0);
        check(Image.getRGBCalls==reads && Graphics.drawImageCalls==blits,"Software grew Image path");cpu.destroy();
        g=warm();fullFrame(g);reads=Image.getRGBCalls;scene(g);g.renderGameFrame(null,false,false,null,0,null);
        check(Image.getRGBCalls==reads+2 && g.display[0]==0xffff0000,"legacy eager API not preserved");g.destroy();
        System.out.println("PASS flushAlpha + polygon debug isolation / original Software drawRGB / eager legacy API interoperates with pending Image");
    }
}
