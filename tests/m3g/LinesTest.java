package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.CompositingMode;

/** Instrumented strip/ordering/rollback tests, NOT GPU-rendered screenshots. */
public final class LinesTest {
    private static void check(boolean ok,String message) { M3GTest.check(ok,message); }
    private static Graphics3D.Draw last() { return Graphics3D.history.lastElement(); }
    private static ScreenLines batch(int count) {
        ScreenLines b=new ScreenLines();
        for(int i=0;i<count;i++) b.line(10,2,8,22,1,0xabcdef,false);
        return b;
    }
    private static WeatherGenerator weather() {
        WeatherGenerator w=new WeatherGenerator(32,32,0,0xaabbdd,0x666666,0xffffff,0xcaccdf,false,null,4,22,16,16,1,1);
        w.coords=new float[]{10,3,12,20};w.info=new int[]{1,0,2,0};return w;
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        ScreenLines b=new ScreenLines();ScreenLineGeometry geometry=new ScreenLineGeometry();
        b.line(10,2,10,22,1,0xffffff,false);
        check(geometry.build(b,0,32,32),"vertical rain strip");
        check(Math.abs(geometry.xy[0]-geometry.xy[2])==1,"one-pixel rain thickness");
        b.clear();b.line(1,10,22,10,1,0,false);
        check(geometry.build(b,0,32,32),"horizontal standard line");
        b.clear();b.line(5,5,5,5,1,0,false);
        check(geometry.build(b,0,32,32) && geometry.xy[6]-geometry.xy[0]==1,"point must be a pixel quad");
        b.clear();b.line(-100,-100,-90,-80,1,0,false);
        check(!geometry.build(b,0,32,32),"offscreen strip not rejected");
        b.clear();b.line(10,-10000,10,10000,1,0,false);
        check(geometry.build(b,0,32,32) && geometry.xy[1]>-10 && geometry.xy[7]<42,"offscreen endpoints not clipped");
        b.clear();b.line(10,2,12,20,3,0,true);
        check(geometry.build(b,0,32,32) && geometry.xy[2]-geometry.xy[0]==2,"legacy lightning thickness");
        b.clear();b.line(1,10,22,10,3,0,true);
        check(!geometry.build(b,0,32,32),"legacy horizontal behavior changed");
        System.out.println("PASS rain strips / degenerate point / offscreen clipping / lightning widths");

        DirectX7 g=M3GTest.renderer();int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
        check(g.renderScreenLines(batch(65)),"native rain overlay unavailable");
        check(Graphics3D.calls==calls+3,"65 segments must use 3 calls, not 65");
        check(last().indices.length==128 && last().lengths.length==32,"independent strip indices");
        for(int i=0;i<32;i++) check(last().lengths[i]==4,"strip must contain four vertices");
        check(!last().fog && (last().colors[0]&255)==0xab && (last().colors[1]&255)==0xcd
                && (last().colors[2]&255)==0xef,"opaque RGB / fog isolation");
        check(last().colorComponents==3 && last().colors.length==128*3,"weather still sends alpha components");
        check(!last().textured && last().coords==null && last().blend==CompositingMode.REPLACE,
                "weather still uses texture/UV/alpha blending");
        check(Graphics3D.uploads==uploads,"weather uploaded a bitmap");
        check(g.display[0]==0xffabcdef,"RGB colors lost implicit opaque alpha");
        for(int i=4;i<128;i++) check(last().positions[i*3]==0 && last().positions[i*3+1]==0,"unused strips contain stale geometry");
        check(Graphics3D.target==null,"overlay target not released");
        M3GTest.Marker marker=new M3GTest.Marker(false);M3GTest.submit(g,marker,null,0);
        check(g.renderScreenLines(batch(1)) && marker.calls==0,"overlay consumed the scene queue");
        g.render();check(marker.calls==1,"pending object queue lost");
        b=batch(1);b.line(0,0,5,5,1000,0,false);calls=Graphics3D.calls;
        check(!g.renderScreenLines(b) && Graphics3D.calls==calls && g.getRendererError()==null,"partial unsupported batch");
        g.resize(240,320);check(g.renderScreenLines(batch(1)),"resize lost line renderer");g.destroy();
        System.out.println("PASS 32-strip batching / RGB / no bridges / degenerate tail / queue and resize");

        // Alternating RGB, including black and ARGB input, stays opaque. State
        // must remain independent of textured/transparent world materials.
        g=M3GTest.renderer();b=new ScreenLines();
        int[] palette={0x00112233,0x80445566,0xff000000,0xffaabbcc};
        for(int color:palette) b.line(10,2,10,22,1,color,false);
        uploads=Graphics3D.uploads;check(g.renderScreenLines(b),"mixed RGB particles unavailable");
        for(int i=0;i<palette.length;i++) for(int v=0;v<4;v++) for(int channel=0;channel<3;channel++)
            check((last().colors[(i*4+v)*3+channel]&255)==((palette[i]>>(16-channel*8))&255),"RGB particle color/stride changed");
        check(Graphics3D.uploads==uploads && !last().textured,"RGB batch created texture");
        Texture alpha=M3GTest.texture(0x80ffffff);alpha.drawmode=4;
        M3GTest.submit(g,M3GTest.triangle(),alpha,0);g.render();
        check(last().textured && last().blend==CompositingMode.ALPHA && last().colorComponents==4,"RGB batch contaminated world alpha");
        uploads=Graphics3D.uploads;check(g.renderScreenLines(b),"RGB batch after textured alpha unavailable");
        check(!last().textured && last().coords==null && last().colorComponents==3
                && last().blend==CompositingMode.REPLACE && Graphics3D.uploads==uploads,"world texture/alpha contaminated weather");
        g.destroy();
        System.out.println("PASS texture-free RGB weather / ARGB ignored / black opaque / no uploads / alpha-state isolation");

        g=M3GTest.renderer();WeatherGenerator w=weather();calls=Graphics3D.calls;
        check(w.paintM3G(g) && Graphics3D.calls==calls+1,"weather did not use native strips");
        check((last().colors[0]&255)==0xaa && (last().colors[1]&255)==0xbb,"rain depth color");
        float scale=last().scale;
        // Second particle is a 3x3 snow rectangle at (10,18), framebuffer-local.
        check(Math.abs(last().positions[12]*scale+16-10)<0.01
                && Math.abs(16-last().positions[13]*scale-18)<0.01,"snow coordinates / letterbox offset");
        w.coords=new float[0];w.info=new int[0];calls=Graphics3D.calls;
        check(w.paintM3G(g) && Graphics3D.calls==calls,"empty weather drew stale particles");
        w=weather();g.flushAlpha=true;check(!w.paintM3G(g),"ARGB framebuffer must keep LCDUI weather");g.flushAlpha=false;
        DeveloperMenu.drawPortals=true;check(!w.paintM3G(g),"portal debug ordering changed");DeveloperMenu.drawPortals=false;
        g.setRenderer(DirectX7.SOFTWARE);check(!w.paintM3G(g),"Software weather entered native path");
        Graphics lcd=Image.createImage(32,64).getGraphics();w.paint(lcd,13);
        check(lcd.lineCalls==1 && lcd.rectCalls==1 && lcd.color==0xffffff,"original LCDUI weather changed");
        check(lcd.lastY==18+13,"original LCDUI letterbox offset changed");g.destroy();
        System.out.println("PASS native rain+snow / frame-local coordinates / LCDUI fallback / debug+alpha");

        g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);Skybox sky=new Skybox();
        int first=Graphics3D.history.size();
        for(int seed=1;seed<100;seed++) {sky.lastLighting=seed;sky.drawLighting(g);g.render();}
        check(Graphics3D.history.size()==first,"M3G storm still generated bolts");g.destroy();
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        g=M3GTest.renderer();b=new ScreenLines();b.line(10,0,20,31,3,0xffffff,true);b.render(cpu,null);
        Graphics3D.failAt=Graphics3D.calls+1;M3GTest.submit(g,b,null,0);g.render();Graphics3D.failAt=0;
        check(java.util.Arrays.equals(cpu.display,g.display) && !g.isM3GActive(),"lightning failed to replay original CPU lines");
        cpu.destroy();g.destroy();
        System.out.println("PASS M3G bolt suppression / legacy line batch CPU replay after native failure");

        g=M3GTest.renderer();int[] before=g.display.clone();
        Graphics3D.failAt=Graphics3D.calls+2;
        check(!g.renderScreenLines(batch(65)),"partial weather failure reported success");Graphics3D.failAt=0;
        check(java.util.Arrays.equals(before,g.display) && Graphics3D.target==null && !g.isM3GActive(),"partial weather not fully rolled back");
        calls=Graphics3D.calls;check(!weather().paintM3G(g) && Graphics3D.calls==calls,"failed weather retried native");
        lcd=Image.createImage(32,64).getGraphics();weather().paint(lcd,13);
        check(lcd.lineCalls==1,"weather failure lost original LCDUI fallback");g.destroy();
        g=M3GTest.renderer();before=g.display.clone();System.setProperty("readback.fail","true");
        check(!g.renderScreenLines(batch(1)),"weather readback failure ignored");System.clearProperty("readback.fail");
        check(java.util.Arrays.equals(before,g.display) && Graphics3D.target==null,"weather readback failed to restore buffer");g.destroy();
        g=M3GTest.renderer();before=g.display.clone();System.setProperty("m3g.oom","true");
        check(!g.renderScreenLines(batch(1)),"weather OOM ignored");System.clearProperty("m3g.oom");
        check(java.util.Arrays.equals(before,g.display) && Graphics3D.target==null,"weather OOM did not restore buffer");g.destroy();
        System.out.println("PASS partial rain failure / buffer rollback / LCDUI replay / readback failure");
    }
}
