package code.Rendering;

import code.utils.Main;
import javax.microedition.m3g.Graphics3D;

/** Real counters against the instrumented API boundary, not estimated polygons. */
public final class RenderStatsTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static void submit(DirectX7 g,Texture t) {M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();}
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 g=M3GTest.renderer();Texture a=M3GTest.texture(0xffff0000),b=M3GTest.texture(0xff405060);
        g.beginFrameStats();int calls=Graphics3D.calls,uploads=Graphics3D.uploads;submit(g,a);
        check(g.getM3GDrawCalls()==Graphics3D.calls-calls && g.getM3GDrawCalls()==1,"draw counter estimates polygons");
        check(g.getM3GTextureUploads()==Graphics3D.uploads-uploads && g.getM3GTextureUploads()==1,"cold upload count");
        check(g.getM3GCpuBarriers()==0,"cold preload/final readback counted as CPU barrier");
        g.beginFrameStats();submit(g,a);check(g.getM3GTextureUploads()==0 && g.getM3GDrawCalls()==1,"warm cache/reset counters");
        g.beginFrameStats();M3GTest.submit(g,M3GTest.triangle(),a,-300);
        M3GTest.Marker marker=new M3GTest.Marker(true);M3GTest.submit(g,marker,null,-200);M3GTest.submit(g,M3GTest.triangle(),a,-100);g.render();
        check(marker.calls==1 && g.getM3GCpuBarriers()==1 && g.getM3GDrawCalls()==2 && g.getM3GTextureUploads()==0,"real CPU barrier counter");
        g.beginFrameStats();M3GTest.submit(g,new M3GTest.Marker(false),null,-300);M3GTest.submit(g,M3GTest.triangle(),a,-100);g.render();
        check(g.getM3GCpuBarriers()==0,"CPU-first no native pixels is not a barrier");
        g.beginFrameStats();M3GTest.submit(g,M3GTest.triangle(),a,-300);M3GTest.submit(g,M3GTest.triangle(),b,-100);g.render();
        check(g.getM3GDrawCalls()==2 && g.getM3GCpuBarriers()==1 && g.getM3GTextureUploads()==1,"cold texture after native segment not counted");
        System.out.println("PASS D actual native submissions / B actual intermediate GPU->CPU/resource readbacks / T Image2D creations / warm hits+CPU-first+final readback excluded / explicit reset");

        g.beginFrameStats();calls=Graphics3D.calls;uploads=Graphics3D.uploads;
        M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),a,0);
        ScreenLines rain=new ScreenLines();rain.rect(2,3,1,1,0xffffff);
        check(g.renderGameFrame(new byte[32*32],false,true,null,0,rain),"combined frame stats fixture");
        check(g.getM3GDrawCalls()==Graphics3D.calls-calls && g.getM3GDrawCalls()==4 && g.getM3GCpuBarriers()==0,"sky/world/post/weather missing in D or extra B");
        check(g.getM3GTextureUploads()==Graphics3D.uploads-uploads && g.getM3GTextureUploads()==1,"dedicated effect mask missing in T");
        String first=g.getM3GStatsText(1000);check(first.indexOf("M3G D:4 B:0 T:1")>=0,"HUD text wrong");
        g.beginFrameStats();submit(g,a);check(g.getM3GStatsText(1050)==first,"HUD allocates string every frame");
        g.beginFrameStats();submit(g,a);String next=g.getM3GStatsText(1250);check(next.indexOf("D:1 B:0 T:0")>=0,"HUD peak window didn't reset");
        g.beginFrameStats();Graphics3D.failAt=Graphics3D.calls+1;submit(g,a);Graphics3D.failAt=0;
        check(g.getM3GDrawCalls()==1 && !g.isM3GActive(),"failed API attempt lost during rollback/dispose");
        check(g.getM3GStatsText(1260).startsWith("CPU "),"failure status hidden by HUD throttle");
        g.beginFrameStats();submit(g,a);check(g.getM3GDrawCalls()==0 && g.getM3GTextureUploads()==0 && g.getM3GCpuBarriers()==0,"CPU replay increments native counters");
        g.destroy();
        g=new DirectX7(32,32);g.beginFrameStats();submit(g,a);
        check(g.getM3GDrawCalls()==0 && g.getM3GTextureUploads()==0,"Software grew native stats work");g.destroy();
        System.out.println("PASS combined frame/helper draws+mask uploads / cached 250ms per-frame peak HUD / immediate CPU failure status / failed draw retained / no CPU replay or Software counts");
    }
}
