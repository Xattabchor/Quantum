package code.Rendering;

import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Bounded LCDUI rain avoids the native overlay transaction entirely. */
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
        for(int count:new int[]{0,1,31,32,33,65,500,2048}) {
            WeatherGenerator w=rain(count);DirectX7 g=M3GTest.renderer();
            int calls=Graphics3D.calls,binds=Graphics3D.binds,releases=Graphics3D.releases;
            int uploads=Graphics3D.uploads,copies=Graphics.drawRGBCalls;
            float[] coords=w.coords.clone();int[] info=w.info.clone(),frame=g.display.clone();
            boolean nativeSnow=w.paintM3G(g);check(nativeSnow,"rain-only frame should not need snow fallback");
            Graphics out=lcd();w.paintAfterScene(out,13,g.getRenderer(),nativeSnow);
            check(out.lineCalls<=32 && (count==0 || out.lineCalls>0),"rain draw budget exceeded/missing");
            check(out.rectCalls==0 && out.color==0xffffff && out.colorCalls==2,"rain changes color per drop or doesn't restore state");
            for(int[] line:out.lineHistory) {
                check(line[0]==10 && line[2]==10 && line[1]==18 && line[3]==21,"rain is not fixed vertical 4-pixel stroke/offset");
                check(line[4]==0x607080,"cheap rain didn't use one shared midpoint color");
            }
            check(Graphics3D.calls==calls && Graphics3D.binds==binds && Graphics3D.releases==releases
                    && Graphics3D.uploads==uploads && Graphics.drawRGBCalls==copies,"rain still causes native draw/upload/readback transaction");
            check(Arrays.equals(frame,g.display) && Arrays.equals(coords,w.coords) && Arrays.equals(info,w.info),"rain mutated world/simulation");
            check(!g.isM3GActive(),"rain initialized optional backend");g.destroy();
        }
        System.out.println("PASS cheap rain <=32 LCDUI strokes / shared color / letterbox / no M3G bind+draw+upload+frame copy / simulation unchanged");

        // Survives transitions with native snow, without duplicate snow or rain.
        WeatherGenerator w=rain(2);w.info[2]=2;w.coords[3]=20;
        DirectX7 g=M3GTest.renderer();boolean nativeSnow=w.paintM3G(g);
        check(nativeSnow && Graphics3D.history.lastElement().colorComponents==3,"mixed snow lost native RGB path");
        Graphics out=lcd();w.paintAfterScene(out,13,g.getRenderer(),nativeSnow);
        check(out.lineCalls==1 && out.rectCalls==0,"mixed particles painted snow twice");
        w.info[2]=1;int calls=Graphics3D.calls;check(w.paintM3G(g) && Graphics3D.calls==calls,"rain reused stale native snow batch");
        g.destroy();
        for(int fallback=0;fallback<4;fallback++) {
            g=M3GTest.renderer();w=rain(64);w.info[2]=2;w.coords[3]=20;
            if(fallback==0) g.flushAlpha=true;
            if(fallback==1) DeveloperMenu.drawPortals=true;
            if(fallback==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(fallback==3) System.setProperty("readback.fail","true");
            nativeSnow=w.paintM3G(g);Graphics3D.failAt=0;System.clearProperty("readback.fail");DeveloperMenu.drawPortals=false;
            check(!nativeSnow,"snow failure/debug not reported");out=lcd();
            w.paintAfterScene(out,13,g.getRenderer(),nativeSnow);
            check(out.lineCalls<=32 && out.rectCalls==1,"rain fallback exceeded budget or lost snow");
            check(Graphics3D.target==null,"snow failure leaked native target");g.destroy();
        }
        System.out.println("PASS mixed rain/snow / no stale snow / native+readback+alpha+debug fallback keeps cheap rain");

        w=rain(3);w.coords[0]=-2;w.coords[2]=40;w.coords[5]=40;
        out=lcd();w.paintAfterScene(out,0,DirectX7.M3G,true);check(out.lineCalls==0,"offscreen rain not rejected");
        w=rain(1);w.lighting=true;w.lastLighting=System.currentTimeMillis();out=lcd();
        w.paintAfterScene(out,0,DirectX7.M3G,true);check(out.lineHistory.elementAt(0)[4]==0xffffff,"rain lightning color lost");
        // Software retains full density, per-depth color and original diagonal lengths.
        w=rain(100);Graphics original=lcd(),software=lcd();w.paint(original,13);
        w.paintAfterScene(software,13,DirectX7.SOFTWARE,false);
        check(software.lineCalls==100 && original.lineCalls==software.lineCalls,"Software rain was capped");
        for(int i=0;i<100;i++) check(Arrays.equals(original.lineHistory.elementAt(i),software.lineHistory.elementAt(i)),"Software rain changed");
        System.out.println("PASS offscreen / storm color / unchanged original Software density+geometry+colors");
    }
}
