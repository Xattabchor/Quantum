package code.Rendering;

import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Native flash geometry/equation contracts, not GPU screenshots. */
public final class StormFlashTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static WeatherGenerator weather(int w,int h) {
        return new WeatherGenerator(w,h,0,0,0,0,0,true,null,1,1,w/2,h/2,1,1);
    }
    private static int horizon(DirectX7 g,WeatherGenerator w,float rotation) {
        int y=(int)(w.screenHeight/2-w.horizonLine*w.screenHeight/g.fovY+rotation*w.screenHeight/g.fovY);
        return (y-g.centreY)*g.distY/(11+g.distY)+g.centreY;
    }
    private static float y(Graphics3D.Draw d,int vertex,DirectX7 g) {
        return g.centreY-(float)d.positions[vertex*3+1]/-d.positions[vertex*3+2];
    }
    private static float brightness(Graphics3D.Draw d,float row,DirectX7 g) {
        for(int v:new int[]{0,4}) {
            float a=y(d,v,g),b=y(d,v+2,g);
            if(b>a && row>=a && row<=b) {
                return (d.colors[v*4]&255)+((d.colors[(v+2)*4]&255)-(d.colors[v*4]&255))*(row-a)/(b-a);
            }
        }
        return 0;
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int[] size:new int[][]{{32,32},{240,320},{320,240},{240,400}}) for(int fov:new int[]{40,74,100}) {
            DirectX7 g=new DirectX7(size[0],size[1]);g.setRenderer(DirectX7.M3G);g.updateFov(fov);
            WeatherGenerator w=weather(g.width,g.height);int uploads=Graphics3D.uploads;
            for(float rotation:new float[]{-100,-45,-10,0,20,45,100}) {
                int calls=Graphics3D.calls,top=horizon(g,w,rotation);g.clearDisplay(0x123456);
                w.lightingEffect(g,rotation);
                if(top>=g.height) {check(Graphics3D.calls==calls,"invisible storm flash submitted");continue;}
                check(g.isM3GActive() && Graphics3D.calls==calls+1,"flash not one native call");
                Graphics3D.Draw d=Graphics3D.history.lastElement();
                check(d.blend==CompositingMode.ALPHA_ADD && !d.fog && Graphics3D.lastAppearance.tex==null,"flash blend/texture/fog");
                check(Arrays.equals(d.lengths,new int[]{4,4}) && d.positions.length==24,"flash is not two independent strips");
                check(Math.abs(y(d,0,g)-Math.max(0,top))<0.07 && Math.abs(y(d,6,g)-g.height)<0.07,"flash horizon/height bounds");
                int ySize=w.horizonSize*g.height/g.fovY;
                for(int row=Math.max(0,top);row<g.height;row++) {
                    int expected=Math.min(100,(row-top)*255/ySize)&0xfe;
                    check(Math.abs(brightness(d,row,g)-expected)<=3.0,"flash gradient differs: "+g.width+"x"+g.height+" fov="+fov+" rot="+rotation+" row="+row+" top="+top+" expected="+expected+" actual="+brightness(d,row,g));
                }
                for(int i=0;i<8;i++) check((d.colors[i*4+3]&255)==255,"flash alpha not opaque additive");
                check(Graphics3D.uploads==uploads,"untextured flash uploaded bitmap");
            }
            g.destroy();
        }
        System.out.println("PASS storm flash native geometry / horizon+pitch / FOV+aspect / gradient cap / no texture upload / one draw");

        // Fully capped flash: native ADD saturation differs from the old masked
        // destination only in its low bit, not by an extra alpha attenuation.
        DirectX7 sample=M3GTest.renderer();WeatherGenerator sampleWeather=weather(32,32);
        for(int bg:new int[]{0,0x112337,0xf0a0ff,0xffffff}) {
            sample.clearDisplay(bg);sampleWeather.lightingEffect(sample,-200);
            int col=(bg&0xfefefe)+0x646464;
            int expected=col|(((col>>>8)&0x010101)*255);
            for(int shift=0;shift<=16;shift+=8)
                check(Math.abs(((sample.display[0]>>shift)&255)-((expected>>shift)&255))<=1,"flash additive saturation");
        }
        sample.destroy();

        // The screen effect must not consume the pending world queue or leak state.
        DirectX7 g=M3GTest.renderer();WeatherGenerator w=weather(32,32);
        M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xffabcdef),0);
        int calls=Graphics3D.calls;w.lightingEffect(g,0);check(Graphics3D.calls==calls+1,"flash consumed world queue");
        g.render();check(Graphics3D.calls==calls+2 && g.display[0]==0xffabcdef,"flash lost queue/order");
        check(Graphics3D.history.lastElement().blend==CompositingMode.REPLACE,"flash additive state leaked");
        g.resize(40,24);w=weather(40,24);w.lightingEffect(g,-10);check(g.isM3GActive(),"flash resize failed");g.destroy();
        System.out.println("PASS storm flash queue isolation / ordinary-state reset / resize");

        for(int fallback=0;fallback<5;fallback++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            WeatherGenerator a=weather(32,32),b=weather(32,32);
            b.lightingEffect(cpu,-10);
            if(fallback==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(fallback==1) System.setProperty("readback.fail","true");
            if(fallback==2) System.setProperty("m3g.oom","true");
            if(fallback==3) g.flushAlpha=true;
            if(fallback==4) DeveloperMenu.renderPolygonsOverwrite=true;
            a.lightingEffect(g,-10);
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");DeveloperMenu.renderPolygonsOverwrite=false;
            check(Arrays.equals(g.display,cpu.display),"flash fallback lost/doubled original effect");
            check(Graphics3D.target==null,"flash failure leaked target");g.destroy();cpu.destroy();
        }
        System.out.println("PASS storm flash original Software / native+readback+OOM rollback / alpha+debug fallback");
    }
}
