package code.Diagnostics;

import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.GameCanvas;

public final class FramePumpTest {
    static long checks;
    static void ok(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
    static void eq(long a,long b,String m){ok(a==b,m+": "+a+" != "+b);}
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void call(Object o,String n,Class[] ts,Object[] vs)throws Exception{Method m=o.getClass().getDeclaredMethod(n,ts);m.setAccessible(true);m.invoke(o,vs);}
    static void invoke(Object o,String n)throws Exception{call(o,n,new Class[0],new Object[0]);}
    static void key(Object app,int k)throws Exception{call(app,"key",new Class[]{int.class},new Object[]{k});}
    static void paints(Canvas c,int delta)throws Exception{c.awaitPaints(c.completed+delta,4000);}
    static void flushes(GameCanvas g,int delta)throws Exception{
        int target=g.flushes+delta;long end=System.currentTimeMillis()+4000;
        while(g.flushes<target && System.currentTimeMillis()<end)Thread.sleep(1);
        ok(g.flushes>=target,"flush loop stalled");
    }
    static void stable(FramePumpPreview app,Canvas c,GameCanvas g)throws Exception{
        int before,f;
        synchronized(get(app,"frame")){before=c.requests;f=g==null?0:g.flushes;}
        Thread.sleep(20);
        synchronized(get(app,"frame")){eq(c.requests,before,"inactive Canvas requested");if(g!=null)eq(g.flushes,f,"inactive flush");}
    }
    static long[] ops(int value){long[] o=new long[8];Arrays.fill(o,value);return o;}
    static void stats(){
        FramePumpStats s=new FramePumpStats();long[] a=ops(2);a[7]=-1;s.reset(0);
        ok(!s.record(500,507,a),"warmup admitted");ok(!s.record(1000,1007,a),"anchor published");
        for(int i=1;i<=96;i++){ok(!s.record(1000+31*i,1007+31*i,a),"short window published");eq(s.sumI,s.sumP+s.sumG,"interval identity");}
        ok(s.record(4007,4014,a),"window missing");eq(s.fps10,322,"fractional FPS");eq(s.meanI,31,"I");eq(s.meanP,7,"P");eq(s.meanG,24,"G");eq(s.meanI10,310,"I tenths");eq(s.meanP10,70,"P tenths");eq(s.meanG10,240,"G tenths");eq(s.mean[7],-1,"unknown queue");eq(s.queueSamples,0,"unknown samples");
        for(int i=0;i<7;i++)eq(s.mean[i],2,"previous operations");eq(s.minI,31,"minI");eq(s.maxI,31,"maxI");
        // Variable paint and operation costs: MUST sum the previous paint, not current.
        s.reset(0);long now=1000;int previousPaint=3;long[] prev=ops(1);prev[7]=4;s.record(now,now+previousPaint,prev);
        long expectedP=0,expectedGap=0,expectedF=0;int frames=0;
        while(true){int gap=13+(frames%7),cost=5+(frames%11);now+=previousPaint+gap;expectedP+=previousPaint;expectedGap+=gap;expectedF+=prev[0];frames++;
            long[] next=ops(cost);next[7]=frames%2==0?-1:6;boolean published=s.record(now,now+cost,next);
            if(published){eq(s.meanP,expectedP/frames,"previous P association");eq(s.meanG,expectedGap/frames,"gap association");eq(s.meanI,(expectedP+expectedGap)/frames,"I association");eq(s.mean[0],expectedF/frames,"previous ops association");ok(s.queueSamples>0&&s.queueSamples<frames,"queue sample denominator");break;}
            eq(s.sumI,s.sumP+s.sumG,"variable identity");previousPaint=cost;prev=next;
        }
        s.reset(0);s.record(1000,1010,ops(0));ok(!s.record(1005,1006,ops(0)),"overlap/clock rollback");eq(s.frames,0,"clock reset");
        s.reset(0);s.record(1000,1010,ops(0));long[] bad=ops(0);bad[3]=-1;ok(!s.record(2000,2001,bad),"negative operation");eq(s.fps10,0,"invalid measurement retained");
        s.reset(0);s.record(1000,1000,ops(0));ok(!s.record(1000,1000,ops(0)),"zero interval divided");
    }
    static void paths()throws Exception{
        Canvas.auto=true;FramePumpPreview app=new FramePumpPreview();
        app.startApp();Canvas c=(Canvas)get(app,"canvas");paints(c,20);
        Thread worker=(Thread)get(app,"worker");ok(worker!=null && worker.isAlive(),"worker absent");eq(c.services,0,"unexpected serviceRepaints");
        key(app,51);paints(c,20);eq((Integer)get(app,"mode"),1,"self shortcut");
        long end=System.currentTimeMillis()+3000;
        while(worker.getState()!=Thread.State.WAITING && System.currentTimeMillis()<end)Thread.sleep(1);
        ok(worker.getState()==Thread.State.WAITING,"self mode worker not parked");
        synchronized(get(app,"frame")){ok(c.requests<=c.completed+2,"self request flood");}
        // Compare MIN/FULL draws and restore the paint caller's Graphics state.
        synchronized(get(app,"frame")){
            Canvas.auto=false;Graphics g=new Graphics(true);g.translate(7,9);g.setClip(2,3,111,222);Font saved=g.getFont();
            call(app,"paintFrame",new Class[]{Graphics.class},new Object[]{g});eq(g.strings,9,"full HUD");eq(g.x,7,"x restore");eq(g.y,9,"y restore");eq(g.cx,2,"clip restore");ok(g.getFont()==saved,"font restore");
            key(app,55);Graphics min=new Graphics(true);call(app,"paintFrame",new Class[]{Graphics.class},new Object[]{min});eq(min.strings,2,"min HUD");
            Canvas.auto=true;
        }
        // Explicitly reselect resets pending and reseeds, including same-Canvas display.
        key(app,51);paints(c,10);
        app.pauseApp();stable(app,c,null);app.startApp();ok(get(app,"worker")==worker,"duplicate worker");paints(c,10);
        invoke(c,"hideNotify");stable(app,c,null);invoke(c,"showNotify");paints(c,10);
        key(app,53);GameCanvas game=(GameCanvas)get(app,"game");flushes(game,20);
        eq((Integer)get(app,"mode"),2,"flush shortcut");ok(game.owner==worker,"flush not on single worker");eq(game.requests,0,"flush uses repaint");eq(game.services,0,"flush uses serviceRepaints");
        synchronized(get(app,"frame")){
            FramePumpStats s=(FramePumpStats)get(app,"stats");long[] ops=(long[])get(app,"ops");eq(ops[7],-1,"flush queue sample");
            Canvas.width=321;Canvas.height=241;
        }
        flushes(game,10);synchronized(get(app,"frame")){eq((Integer)get(app,"width"),321,"resize width");eq((Integer)get(app,"height"),241,"resize height");Canvas.width=240;Canvas.height=320;}
        app.pauseApp();stable(app,c,game);app.startApp();flushes(game,10);
        invoke(game,"hideNotify");stable(app,c,game);invoke(game,"showNotify");flushes(game,10);
        for(int i=0;i<8;i++){key(app,49);paints(c,3);key(app,51);paints(c,3);key(app,53);flushes(game,3);}
        key(app,49);paints(c,10);eq((Integer)get(app,"mode"),0,"worker shortcut");
        key(app,48);eq((Integer)get(app,"mode"),1,"cycle self");key(app,48);eq((Integer)get(app,"mode"),2,"cycle flush");key(app,48);eq((Integer)get(app,"mode"),0,"cycle wrap");
        GameCanvas.fail=true;key(app,53);
        end=System.currentTimeMillis()+3000;while((Boolean)get(app,"running") && System.currentTimeMillis()<end)Thread.sleep(1);
        synchronized(get(app,"frame")){ok(get(app,"failure")!=null,"flush failure hidden");ok(!(Boolean)get(app,"running"),"failed flush still measured");ok(Display.getDisplay(app).current instanceof Alert,"failure not shown");}
        GameCanvas.fail=false;key(app,49);paints(c,10);ok(get(app,"failure")==null,"failure recovery");
        key(app,35);worker.join(3000);ok(!worker.isAlive(),"worker did not stop");ok(app.destroyed,"exit did not destroy");stable(app,c,game);
        app.startApp();ok(get(app,"worker")==null,"closed app restarted");
        // Destruction while parked, before start, and in a live flush.
        for(int mode=0;mode<3;mode++){
            FramePumpPreview other=new FramePumpPreview();other.startApp();key(other,mode==0?49:mode==1?51:53);
            Thread t=(Thread)get(other,"worker");other.destroyApp(true);t.join(3000);ok(!t.isAlive(),"destroy loop deadlock "+mode);
        }
        FramePumpPreview unopened=new FramePumpPreview();unopened.destroyApp(true);unopened.startApp();ok(get(unopened,"worker")==null,"destroy before start");
    }
    public static void main(String[] args)throws Exception{stats();paths();System.out.println("PASS "+checks+" checks; API doubles, NOT handset performance");}
}
