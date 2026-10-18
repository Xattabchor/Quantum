package code.Rendering;

import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Native rain retains the bounded simulation and safe LCDUI failure path. */
public final class CheapRainTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static WeatherGenerator rain(int count) {
        WeatherGenerator w=new WeatherGenerator(32,32,0,0x90a0b0,0x304050,0xffffff,0xcaccdf,false,null,4,22,16,16,1,1);
        w.coords=new float[count*2];w.info=new int[count*2];w.generate=1;
        for(int i=0;i<count;i++) {w.coords[i*2]=10;w.coords[i*2+1]=5;w.info[i*2]=1;w.info[i*2+1]=i%101;}
        return w;
    }
    private static Graphics lcd() {return Image.createImage(32,64).getGraphics();}
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int count:new int[]{0,1,15,16,17,32,65,2048}) {
            WeatherGenerator w=rain(count);DirectX7 g=M3GTest.renderer();
            int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
            float[] coords=w.coords.clone();int[] info=w.info.clone();
            boolean nativeWeather=w.paintM3G(g);check(nativeWeather,"native rain failed");
            check(Graphics3D.calls==calls+(count==0?0:1),"rain isn't a single bounded native batch");
            check(Graphics3D.uploads==uploads,"rain uploaded a texture");
            if(count>0) {
                Graphics3D.Draw draw=Graphics3D.history.lastElement();
                check(draw.colorComponents==3 && !draw.textured,"rain lost untextured RGB path");
                ScreenLines expected=new ScreenLines();expected.line(10,5,5,27,1,0x90a0b0,false);
                ScreenLineGeometry geometry=new ScreenLineGeometry();geometry.build(expected,0,32,32);
                for(int v=0;v<4;v++) {
                    check(Math.abs(draw.positions[v*3]*draw.scale+16-geometry.xy[v*2])<0.1,"rain X geometry changed");
                    check(Math.abs(16-draw.positions[v*3+1]*draw.scale-geometry.xy[v*2+1])<0.1,"rain Y/letterbox geometry changed");
                }
                int live=0;
                for(int i=0;i<draw.indices.length/4;i++) if(draw.positions[i*12]!=0 || draw.positions[i*12+1]!=0) live++;
                check(live==Math.min(16,count),"native rain cap isn't 16");
                check((draw.colors[0]&255)==0x90 && (draw.colors[1]&255)==0xa0 && (draw.colors[2]&255)==0xb0,"native depth color changed");
            }
            Graphics out=lcd();w.paintAfterScene(out,13,g.getRenderer(),nativeWeather);
            check(out.lineCalls==0 && out.rectCalls==0 && out.color==0xffffff,"successful native weather duplicated in LCDUI");
            check(Arrays.equals(coords,w.coords) && Arrays.equals(info,w.info),"painting mutated simulation");g.destroy();
        }
        System.out.println("PASS native rain <=16 RGB strips / one batch / original diagonal+depth color / no texture / no LCDUI duplication");

        WeatherGenerator w=rain(2);w.info[2]=2;w.coords[3]=20;
        DirectX7 g=M3GTest.renderer();int calls=Graphics3D.calls;
        check(w.paintM3G(g) && Graphics3D.calls==calls+1,"rain and snow didn't share a batch");
        w.coords=new float[0];w.info=new int[0];calls=Graphics3D.calls;
        check(w.paintM3G(g) && Graphics3D.calls==calls,"empty frame reused stale weather");g.destroy();
        for(int fallback=0;fallback<4;fallback++) {
            g=M3GTest.renderer();w=rain(64);w.info[2]=2;w.coords[3]=20;
            int[] frame=g.display.clone();
            if(fallback==0) g.flushAlpha=true;
            if(fallback==1) DeveloperMenu.drawPortals=true;
            if(fallback==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(fallback==3) System.setProperty("readback.fail","true");
            boolean nativeWeather=w.paintM3G(g);Graphics3D.failAt=0;System.clearProperty("readback.fail");DeveloperMenu.drawPortals=false;
            check(!nativeWeather,"native failure/debug not reported");Graphics out=lcd();
            w.paintAfterScene(out,13,g.getRenderer(),nativeWeather);
            check(out.lineCalls==16 && out.rectCalls==1,"fallback lost bounded rain or snow");
            check(Graphics3D.target==null && Arrays.equals(frame,g.display),"weather failure leaked target/corrupted framebuffer");g.destroy();
        }
        System.out.println("PASS shared native rain+snow / stale batch / draw+readback+alpha+debug fallback / bounded LCDUI replay");

        w=rain(1);w.lighting=true;w.lastLighting=System.currentTimeMillis();g=M3GTest.renderer();
        check(w.paintM3G(g),"storm rain failed");
        check((Graphics3D.history.lastElement().colors[0]&255)==255,"rain lightning color lost");g.destroy();
        w=rain(100);Graphics original=lcd(),software=lcd();w.paint(original,13);
        w.paintAfterScene(software,13,DirectX7.SOFTWARE,false);
        check(software.lineCalls==100,"Software rain was capped");
        for(int i=0;i<100;i++) check(Arrays.equals(original.lineHistory.elementAt(i),software.lineHistory.elementAt(i)),"Software rain changed");
        System.out.println("PASS storm color / unchanged Software density+geometry+colors");
    }
}
