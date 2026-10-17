package code.Rendering;

import code.Gameplay.Map.House;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import java.util.Arrays;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Full game tail ordering, transfers, replay and explicit LCDUI weather fallback. */
public final class FrameTransactionTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw draw(int index) {return Graphics3D.history.elementAt(index);}
    private static ScreenLines particles(int count) {
        ScreenLines lines=new ScreenLines();
        for(int i=0;i<count;i++) lines.rect(i%20,2+i%10,1,1,0x80a0c0);
        return lines;
    }
    private static byte[] mask() {return new byte[32*32];}
    private static void scene(DirectX7 g,Texture t) {
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();
        M3GTest.submit(g,M3GTest.triangle(),t,0);
    }
    private static void cpuScene(DirectX7 g,Texture t,byte[] mask) {
        g.clearDisplay(0x345678);M3GTest.submit(g,M3GTest.triangle(),t,0);g.renderSoftware();
        FrameEffects effect=new FrameEffects();effect.set(mask,false,true);effect.render(g,null);
    }
    private static WeatherGenerator weather() {
        WeatherGenerator w=new WeatherGenerator(32,32,0,0,0,0,0,false,null,4,22,16,16,1,1);
        w.coords=new float[]{10,5,15,9};w.info=new int[]{1,20,2,30};return w;
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        House house=MuzzleGlowTest.wall(1000);Texture t=M3GTest.texture(0xffa0b0c0);
        ScreenLines weather=particles(1);byte[] mask=mask();DirectX7 g=M3GTest.renderer();
        // Warm only the world. Mask is deliberately COLD on the combined frame.
        M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
        int binds=Graphics3D.binds,reads=Image.getRGBCalls,first=Graphics3D.history.size();
        int copies=Graphics.drawRGBCalls;
        g.startMuzzleGlow();scene(g,t);
        check(g.renderGameFrame(mask,false,true,house,0,weather),"combined weather not acknowledged");
        check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,"cold mask split native transaction");
        check(Graphics.drawRGBCalls==copies,"full opaque sky should not upload old framebuffer");
        check(Graphics3D.history.size()==first+4,"expected sky/world/post/weather draws");
        check(!draw(first).textured && draw(first+1).cull==PolygonMode.CULL_BACK,"sky/world order");
        check(draw(first+2).blend==CompositingMode.MODULATE,"postprocessing not before weather");
        check(!draw(first+3).textured && draw(first+3).colorComponents==3,"weather not last RGB stage");
        check(Graphics3D.target==null,"combined target leak");
        int uploads=Graphics3D.uploads;
        for(boolean flag:new boolean[]{false,true,false,true}) {
            Main.m3gPerspective=flag;binds=Graphics3D.binds;reads=Image.getRGBCalls;first=Graphics3D.history.size();
            g.startMuzzleGlow();scene(g,t);check(g.renderGameFrame(mask,false,true,house,0,weather),"warm weather failure");
            check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1 && Graphics3D.uploads==uploads,"warm tail reuploads/extra transfers");
            for(int i=first;i<Graphics3D.history.size();i++) check(draw(i).perspective==flag,"tail didn't follow cached hardware preference");
        }
        g.startMuzzleGlow();first=Graphics3D.history.size();scene(g,t);
        check(g.renderGameFrame(mask,true,true,house,0,weather),"dead weather unavailable");
        check(Graphics3D.history.size()==first+4,"dead frame changed tail");g.destroy();
        System.out.println("PASS sky -> world -> post -> RGB weather / no fake flash / one bind+readback even cold mask / warm no extra uploads / HW ON+OFF / death");

        // No sky: exactly ONE CPU background upload, not one per tail overlay.
        g=M3GTest.renderer();M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
        copies=Graphics.drawRGBCalls;binds=Graphics3D.binds;reads=Image.getRGBCalls;
        M3GTest.submit(g,M3GTest.triangle(),t,0);check(g.renderGameFrame(null,false,false,null,0,weather),"weather without sky");
        check(Graphics.drawRGBCalls==copies+1 && Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,"tail adds background copies");
        // CPU material barrier is legitimate; tail must not add any more barriers.
        M3GTest.Marker marker=new M3GTest.Marker(false);binds=Graphics3D.binds;reads=Image.getRGBCalls;
        M3GTest.submit(g,M3GTest.triangle(),t,-300);M3GTest.submit(g,marker,null,-200);M3GTest.submit(g,M3GTest.triangle(),t,-100);
        check(g.renderGameFrame(mask,false,true,null,0,weather),"CPU barrier lost weather");
        check(marker.calls==1 && Graphics3D.binds==binds+2 && Image.getRGBCalls==reads+2,"tail adds transfers beyond required CPU barrier");
        ScreenLines bad=new ScreenLines();bad.line(0,0,10,10,1000,0xffffff,false);
        M3GTest.submit(g,M3GTest.triangle(),t,0);check(!g.renderGameFrame(null,false,false,null,0,bad) && g.isM3GActive(),"unsupported weather must return LCDUI fallback without disabling world");
        first=Graphics3D.history.size();check(g.renderGameFrame(null,false,false,null,0,new ScreenLines()),"empty weather status");
        check(Graphics3D.history.size()==first,"empty frame drew stale weather");g.destroy();
        System.out.println("PASS one CPU background upload / legitimate CPU barriers only / unsupported weather -> LCDUI without killing world / empty frame no stale draw");

        g=M3GTest.renderer();Texture[] full=new Texture[16];
        for(int i=0;i<full.length;i++) {full[i]=M3GTest.texture(0xff102030+i);M3GTest.submit(g,M3GTest.triangle(),full[i],i);}
        g.render();
        for(int frame=0;frame<2;frame++) {
            g.startMuzzleGlow();for(int i=0;i<full.length;i++) M3GTest.submit(g,M3GTest.triangle(),full[i],i);
            binds=Graphics3D.binds;reads=Image.getRGBCalls;uploads=Graphics3D.uploads;
            check(g.renderGameFrame(null,false,false,house,0,weather),"full cache tail failed");
            check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,"shot split full world FIFO transaction");
            check(Graphics3D.uploads==uploads,"shot uploaded fake flash or evicted world textures");
        }
        g.destroy();
        System.out.println("PASS no flash cache/upload / 16 full world entries remain warm / no eviction cascade or tail barrier");

        for(int failure=0;failure<7;failure++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpuScene(cpu,t,mask);
            g.startMuzzleGlow();scene(g,t);
            // 1 sky, 2 world, 3 post, 4/5/6 weather packets.
            if(failure<6) Graphics3D.failAt=Graphics3D.calls+failure+1;
            else System.setProperty("readback.fail","true");
            check(!g.renderGameFrame(mask,false,true,house,0,particles(65)),"partial tail failure reported weather success "+failure);
            Graphics3D.failAt=0;System.clearProperty("readback.fail");
            check(!g.isM3GActive() && Graphics3D.target==null && Arrays.equals(cpu.display,g.display),"complete scene+post CPU replay mismatch "+failure);
            int calls=Graphics3D.calls;check(!g.renderGameFrame(null,false,false,house,0,weather),"disabled renderer returned stale weather success");
            check(Graphics3D.calls==calls,"failed frame retried native tail");
            g.destroy();cpu.destroy();
        }
        // Native OOM in preparation/draw still falls back as a whole transaction.
        g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpuScene(cpu,t,mask);scene(g,t);
        System.setProperty("m3g.oom","true");check(!g.renderGameFrame(mask,false,true,house,0,weather),"OOM reported success");System.clearProperty("m3g.oom");
        check(Arrays.equals(cpu.display,g.display) && Graphics3D.target==null,"OOM replay");g.destroy();cpu.destroy();
        System.out.println("PASS failure in each sky/world/post/weather packet + final readback / full CPU scene+effects replay once / no cosmetic flash / no stale weather success / OOM");

        g=M3GTest.renderer();WeatherGenerator w=weather();first=Graphics3D.history.size();copies=Graphics.drawRGBCalls;
        ScreenLines prepared=w.prepareM3G(g);
        check(prepared!=null && prepared.count==2 && Graphics3D.history.size()==first && Graphics.drawRGBCalls==copies,"weather prepare must not draw/copy");
        check(w.prepareM3G(g)==prepared && prepared.count==2,"weather snapshot not reused or accumulated");
        M3GTest.submit(g,M3GTest.triangle(),t,0);boolean success=g.renderGameFrame(null,false,false,null,0,prepared);
        Graphics lcd=Image.createImage(32,64).getGraphics();w.paintAfterScene(lcd,13,g.getRenderer(),success);
        check(success && lcd.lineCalls==0 && lcd.rectCalls==0,"weather duplicated after successful readback");
        w.paintAfterScene(lcd,13,g.getRenderer(),false);check(lcd.lineCalls==1 && lcd.rectCalls==1,"failed weather lost LCDUI fallback");
        DeveloperMenu.drawPortals=true;check(w.prepareM3G(g)==null,"portal debug ordering changed");DeveloperMenu.drawPortals=false;
        g.flushAlpha=true;check(w.prepareM3G(g)==null,"ARGB framebuffer entered native weather");g.flushAlpha=false;
        g.setRenderer(DirectX7.SOFTWARE);check(w.prepareM3G(g)==null,"Software weather entered native frame");
        first=Graphics3D.history.size();M3GTest.submit(g,M3GTest.triangle(),t,0);
        check(!g.renderGameFrame(mask,true,true,house,0,weather) && Graphics3D.history.size()==first,"Software grew native effects");g.destroy();house.destroy();
        Main.m3gPerspective=true;
        System.out.println("PASS reusable preparation without submission / native weather suppressed only on final success / LCDUI snow+bounded rain fallback / portal+alpha+Software isolation");
    }
}
