package code.Diagnostics;

import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.Graphics3D;

public final class EmptyFrameTest {
    static long checks;
    static void ok(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
    static void eq(long a,long b,String m){ok(a==b,m+": "+a+" != "+b);}
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static void call(Object o,String n,Class[] ts,Object[] vs)throws Exception{Method m=o.getClass().getDeclaredMethod(n,ts);m.setAccessible(true);m.invoke(o,vs);}
    static void invoke(Object o,String n)throws Exception{call(o,n,new Class[0],new Object[0]);}
    static void key(Object o,int k)throws Exception{call(o,"keyPressed",new Class[]{int.class},new Object[]{k});}
    static void paint(Object o,Graphics g)throws Exception{call(o,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Object viewer()throws Exception{Class<?> c=Class.forName("code.Diagnostics.EmptyFramePreview$Viewer");Constructor<?> ctor=c.getDeclaredConstructor(EmptyFramePreview.class);ctor.setAccessible(true);Object v=ctor.newInstance(new EmptyFramePreview());set(v,"running",true);set(v,"visible",true);return v;}
    static long[] ops(int value){long[] o=new long[8];Arrays.fill(o,value);return o;}
    static void stats(){
        EmptyFrameStats s=new EmptyFrameStats();long[] a=ops(2);a[7]=-1;s.reset(0);
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
        Object v=viewer();Graphics g=new Graphics(true);g.translate(7,9);g.setClip(2,3,111,222);Font saved=g.getFont();
        for(int mode:new int[]{0,1,2,8}){
            set(v,"mode",mode);set(v,"resetRequested",true);Graphics3D.trace.clear();int copies=g.copies;paint(v,g);
            ok(Graphics3D.trace.isEmpty(),"MIDP called M3G");eq(Graphics3D.instances,0,"M3G eager init");eq(g.copies-copies,mode==1||mode==2?1:0,"MIDP copy count");
            eq(g.x,7,"translate x");eq(g.y,9,"translate y");eq(g.cx,2,"clip x");eq(g.cy,3,"clip y");eq(g.cw,111,"clip width");eq(g.ch,222,"clip height");ok(g.getFont()==saved,"font restore");
        }
        for(int mode=3;mode<=7;mode++){
            set(v,"mode",mode);set(v,"resetRequested",true);Graphics3D.trace.clear();int copies=g.copies,clears=Graphics3D.clears;
            paint(v,g);ok(Graphics3D.target==null,"bound target after paint");eq(g.copies-copies,mode==7?0:1,"native copy count");eq(Graphics3D.clears-clears,mode==3?0:1,"clear count");
            List<String> expected=new ArrayList<String>();expected.add("bind:"+(mode!=6)+":"+(mode==7));expected.add("viewport");expected.add("camera");expected.add("lights");
            if(mode!=3)expected.add("clear:true:"+(mode==5||mode==7));expected.add("release");ok(expected.equals(Graphics3D.trace),"wrong native path "+mode+Graphics3D.trace);
            ok(((String[])get(v,"failures"))[mode]==null,"unexpected failure");
        }
        eq((Integer)get(v,"nativeAllocations"),1,"native reallocation");eq((Integer)get(v,"imageAllocations"),1,"Image reallocation");
        int images=Image.created;for(int i=0;i<30;i++){key(v,48);paint(v,g);key(v,42);key(v,55);paint(v,g);}eq(Image.created,images,"hot Image creation");eq((Integer)get(v,"nativeAllocations"),1,"hot helper creation");
        key(v,49);eq((Integer)get(v,"mode"),0,"Canvas shortcut");key(v,51);eq((Integer)get(v,"mode"),1,"Image shortcut");key(v,53);eq((Integer)get(v,"mode"),5,"RGBZ shortcut");
        set(v,"mode",0);key(v,50);eq((Integer)get(v,"mode"),8,"previous wrap");key(v,48);eq((Integer)get(v,"mode"),0,"next wrap");
        boolean sync=(Boolean)get(v,"forceService"),hud=(Boolean)get(v,"fullHud");key(v,42);ok((Boolean)get(v,"forceService")!=sync,"pump toggle");key(v,55);ok((Boolean)get(v,"fullHud")!=hud,"HUD toggle");ok((Boolean)get(v,"resetRequested"),"toggle warmup");
        final Object change=v;set(v,"mode",0);set(v,"resetRequested",false);set(v,"statsVersion",get(v,"controlVersion"));
        EmptyFrameStats timing=(EmptyFrameStats)get(v,"stats");timing.reset(0);
        Graphics transition=new Graphics(true){boolean once;public void drawString(String s,int x,int y,int a){super.drawString(s,x,y,a);if(!once){once=true;try{key(change,51);}catch(Exception e){throw new RuntimeException(e);}}}};
        paint(v,transition);eq((Long)get(timing,"lastStart"),-1,"transition frame entered timing window");
        set(v,"pending",true);set(v,"requestedAt",System.currentTimeMillis());final long oldTicket=(Long)get(v,"requestId");
        Graphics late=new Graphics(true){boolean once;public void drawString(String s,int x,int y,int a){super.drawString(s,x,y,a);if(!once){once=true;try{set(change,"requestId",oldTicket+1);set(change,"pending",true);}catch(Exception e){throw new RuntimeException(e);}}}};
        paint(v,late);ok((Boolean)get(v,"pending"),"stale paint consumed a newer request");set(v,"pending",false);
        boolean sleeping=(Boolean)get(v,"sleepEnabled");key(v,57);ok((Boolean)get(v,"sleepEnabled")!=sleeping,"sleep toggle");ok((Boolean)get(v,"resetRequested"),"sleep warmup");
        Canvas.width=321;Canvas.height=241;set(v,"mode",1);paint(v,g);eq(Image.created,images+1,"resize Image");Canvas.width=240;Canvas.height=320;
        set(v,"mode",8);set(v,"fullHud",true);set(v,"resetRequested",true);int n=g.strings;paint(v,g);eq(g.strings-n,10,"FULL HUD");set(v,"fullHud",false);set(v,"resetRequested",true);n=g.strings;paint(v,g);eq(g.strings-n,2,"MIN HUD");
        EmptyFrameStats shown=(EmptyFrameStats)get(v,"stats");shown.fps10=316;shown.meanI10=316;shown.meanP10=71;shown.meanG10=245;shown.mean10[7]=-1;invoke(v,"resultText");
        String[] text=(String[])get(v,"lines");ok(text[0].endsWith("FPS 31.6"),"FPS formatting");ok(text[1].startsWith("I/P/G 31.6/7.1/24.5 ms "),"tenths-ms formatting");ok(text[2].endsWith("/-"),"unknown Q display");
        invoke(v,"hideNotify");Graphics3D.trace.clear();paint(v,g);ok(Graphics3D.trace.isEmpty(),"hidden native calls");invoke(v,"showNotify");ok((Boolean)get(v,"resetRequested"),"resume reset");invoke(v,"close");ok(get(v,"image")==null&&get(v,"nativeApi")==null,"release");
    }
    static void failures()throws Exception{
        for(int mode=3;mode<=7;mode++)for(String flag:new String[]{"bind.fail","viewport.fail","camera.fail","lights.fail","release.fail","clear.fail"}){
            if(mode==3&&flag.equals("clear.fail"))continue;
            Object v=viewer();set(v,"mode",mode);System.setProperty(flag,"true");try{paint(v,new Graphics(true));}finally{System.clearProperty(flag);}
            ok(((String[])get(v,"failures"))[mode]!=null,"failure hidden "+flag);ok(Graphics3D.target==null,"target leaked "+flag);
            int calls=Graphics3D.binds;paint(v,new Graphics(true));eq(Graphics3D.binds,calls,"failed mode retried");key(v,49);paint(v,new Graphics(true));ok(((String[])get(v,"failures"))[0]==null,"other mode broken");invoke(v,"close");
        }
        for(int mode:new int[]{1,5}){Object copy=viewer();set(copy,"mode",mode);System.setProperty("copy.fail","true");try{paint(copy,new Graphics(true));}finally{System.clearProperty("copy.fail");}ok(((String[])get(copy,"failures"))[mode]!=null,"copy error hidden");ok(Graphics3D.target==null,"copy failed with bound target");invoke(copy,"close");}
        Object v=viewer();set(v,"mode",7);int images=Image.created;System.setProperty("direct.fail","true");try{paint(v,new Graphics(true));}finally{System.clearProperty("direct.fail");}
        eq(Image.created,images,"silent direct->Image fallback");ok(((String[])get(v,"failures"))[7]!=null,"unsupported direct mislabeled");invoke(v,"close");
    }
    static void pumps()throws Exception{
        Canvas.auto=true;
        try{
            for(boolean force:new boolean[]{true,false})for(boolean sleep:new boolean[]{true,false}){
                Object v=viewer();set(v,"forceService",force);set(v,"sleepEnabled",sleep);Canvas c=(Canvas)v;invoke(v,"start");c.awaitPaints(12,5000);Thread worker=(Thread)get(v,"worker");ok(worker!=null&&worker.isAlive(),"worker died");
                invoke(v,"hideNotify");int old=c.completed;invoke(v,"showNotify");c.awaitPaints(old+6,5000);
                invoke(v,"stop");invoke(v,"start");ok(worker==get(v,"worker"),"resume created duplicate worker");c.awaitPaints(c.completed+6,5000);
                invoke(v,"close");worker.join(3000);ok(!worker.isAlive(),"worker deadlocked on close");ok(get(v,"workerFailure")==null,"pump failure");
                ok(c.requests<=c.completed+5,"request flooding across lifecycle transitions");if(force)ok(c.services>0,"SYNC skipped serviceRepaints");else eq(c.services,0,"QUEUED forced serviceRepaints");
            }
        }finally{Canvas.auto=false;}
    }
    public static void main(String[] args)throws Exception{stats();paths();failures();pumps();System.out.println("PASS EmptyFrame: "+checks+" checks; exact I=P+G/previous-frame accounting; 9 paths; failure release; real cross-thread pump/lifecycle. NOT handset FPS or GPU timings.");}
}
