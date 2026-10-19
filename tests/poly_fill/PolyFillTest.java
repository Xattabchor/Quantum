package code.Rendering;

import java.lang.reflect.*;
import java.io.*;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Contract/analytical tests only. Doubles do NOT rasterize or measure a GPU. */
public final class PolyFillTest {
    static int checks,profiles;
    static void ok(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static void eq(long actual,long expected,String why){ok(actual==expected,why+": "+actual+" != "+expected);}
    static Field field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    static Object get(Object o,String name)throws Exception{return field(o,name).get(o);}
    static void set(Object o,String name,Object value)throws Exception{field(o,name).set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Object viewer()throws Exception{
        Class c=Class.forName("code.Rendering.PolyFillPreview$Viewer");Constructor k=c.getDeclaredConstructor(PolyFillPreview.class);k.setAccessible(true);
        Object v=k.newInstance(new PolyFillPreview());set(v,"running",Boolean.TRUE);set(v,"visible",Boolean.TRUE);return v;
    }
    static Graphics screen(){Graphics g=Image.createImage(Canvas.screenWidth,Canvas.screenHeight).getGraphics();g.screenTarget=true;return g;}
    static Graphics3D.Draw draw(PolyFillMesh mesh,Graphics target,int width,int height) {
        Graphics3D.history.clear();int calls=Graphics3D.calls,binds=Graphics3D.binds,releases=Graphics3D.releases;
        mesh.render(target,width,height);
        eq(Graphics3D.calls-calls,1,"exactly one render");eq(Graphics3D.binds-binds,1,"one bind");eq(Graphics3D.releases-releases,1,"one release");
        ok(Graphics3D.target==null,"no bound target leak");return Graphics3D.history.lastElement();
    }
    static void geometry(Texture2D checker) {
        Graphics target=Image.createImage(240,230).getGraphics();
        for(int mode=0;mode<2;mode++)for(int level=0;level<PolyFillMesh.levels(mode==1);level++) {
            PolyFillMesh mesh=new PolyFillMesh(mode==1,level,checker);profiles++;
            Graphics3D.Draw d=draw(mesh,target,240,230);
            eq(d.positionCapacity,mesh.vertexCount,"vertex capacity");eq(d.indices.length,mesh.indexCount,"index count");eq(d.lengths.length,mesh.stripCount,"strip count");
            ok(mesh.vertexCount<=16384,"engine packet limit");ok(d.depthTest==!mesh.fill && d.depthWrite==!mesh.fill,"depth policy");
            ok(d.cull==PolygonMode.CULL_BACK && d.winding==PolygonMode.WINDING_CW,"back/CW");ok(d.threshold==.5f,"alpha always ON");
            ok(d.blend==CompositingMode.REPLACE && d.function==Texture2D.FUNC_MODULATE,"opaque modulate");
            ok(!d.lit && !d.fog && d.lights==0 && d.normals==null,"unlit no fog");ok(d.filter==Texture2D.FILTER_NEAREST,"nearest");
            eq(d.viewportWidth,240,"viewport width");eq(d.viewportHeight,230,"viewport height");
            ok(d.source==checker,"one shared texture");ok(d.scale==1.0f/16384 && d.uvScale==1.0f/256,"engine-style scales");
            boolean[] used=new boolean[mesh.vertexCount];int offset=0,triangles=0;long signedAreaTwice=0;
            for(int strip=0;strip<d.lengths.length;strip++) {
                int length=d.lengths[strip];ok(length>=3,"valid strip");
                for(int i=0;i<length;i++){int index=d.indices[offset+i];ok(index>=0 && index<mesh.vertexCount,"index bounds");used[index]=true;}
                for(int i=2;i<length;i++) {
                    int a=d.indices[offset+i-2],b=d.indices[offset+i-1],c=d.indices[offset+i];if((i&1)!=0){int swap=a;a=b;b=swap;}
                    long ax=d.positions[a*3],ay=d.positions[a*3+1],bx=d.positions[b*3],by=d.positions[b*3+1],cx=d.positions[c*3],cy=d.positions[c*3+1];
                    long area=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
                    ok(area<0,"every actual strip triangle clockwise/nondegenerate");signedAreaTwice+=area;triangles++;
                }
                offset+=length;
            }
            eq(offset,mesh.indexCount,"all indices consumed");eq(triangles,mesh.triangles,"actual nondegenerate triangles");
            eq(signedAreaTwice,-2147483648L*mesh.layers,"constant fullscreen area times layers");
            for(int i=0;i<used.length;i++) {
                ok(used[i],"no uploaded unused vertex");
                int grid=mesh.fill?1:PolyFillMesh.GRIDS[level],local=mesh.fill?i%4:i,col=local%(grid+1),row=local/(grid+1);
                eq(d.positions[i*3],-16384+32768*col/grid,"grid x");eq(d.positions[i*3+1],16384-32768*row/grid,"grid y");eq(d.positions[i*3+2],-16384,"plane z");
                eq(d.coords[i*2],256*col/grid,"u");eq(d.coords[i*2+1],256*row/grid,"v");
                double x=d.positions[i*3]*d.scale,y=d.positions[i*3+1]*d.scale,z=d.positions[i*3+2]*d.scale;
                double projectedZ=d.projection[10]*z+d.projection[11];
                ok(x>=-1 && x<=1 && y>=-1 && y<=1 && projectedZ==0,"no clipped/offscreen vertices");
                eq(d.colors[i*4]&255,220,"baked RGB");eq(d.colors[i*4+3]&255,255,"opaque vertex alpha");
            }
            int arrays=VertexArray.created,sets=VertexArray.sets,indexBuffers=TriangleStripArray.created,uploads=Graphics3D.uploads;
            for(int repeat=0;repeat<3;repeat++)draw(mesh,target,240,230);
            eq(VertexArray.created,arrays,"no hot vertex allocation");eq(VertexArray.sets,sets,"no hot vertex upload");eq(TriangleStripArray.created,indexBuffers,"no hot index allocation");eq(Graphics3D.uploads,uploads,"no hot image upload");
            mesh.dispose();mesh.dispose();boolean rejected=false;try{mesh.render(target,240,230);}catch(IllegalStateException expected){rejected=true;}ok(rejected,"disposed workload rejected");
        }
        eq(profiles,14,"profile count");
    }
    static void faults(Texture2D checker) {
        Graphics target=Image.createImage(32,32).getGraphics();PolyFillMesh mesh=new PolyFillMesh(false,0,checker);
        String[] flags={"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"};
        for(int i=0;i<flags.length;i++) {
            System.setProperty(flags[i],"true");boolean rejected=false;
            try{mesh.render(target,32,32);}catch(Throwable expected){rejected=true;}finally{System.clearProperty(flags[i]);}
            ok(rejected,"injected native failure surfaced: "+flags[i]);ok(Graphics3D.target==null,"failure releases target: "+flags[i]);
        }
        Graphics3D.failAt=Graphics3D.calls+1;boolean rejected=false;try{mesh.render(target,32,32);}catch(IllegalStateException expected){rejected=true;}finally{Graphics3D.failAt=0;}
        ok(rejected && Graphics3D.target==null,"render failure releases target");draw(mesh,target,32,32);mesh.dispose();
        VertexArray.failAt=VertexArray.created+1;rejected=false;try{new PolyFillMesh(false,7,checker);}catch(OutOfMemoryError expected){rejected=true;}finally{VertexArray.failAt=0;}
        ok(rejected,"cold allocation OOM");
        for(int level=-1;level<=8;level+=9){rejected=false;try{new PolyFillMesh(false,level,checker);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"invalid level");}
    }
    static void windows(Texture2D checker) {
        for(int mode=0;mode<2;mode++) {
            PolyFillMesh mesh=new PolyFillMesh(mode==1,mode==0?0:5,checker);PolyFillWindow w=new PolyFillWindow();w.reset(0);
            for(int time=0;time<1000;time+=100)ok(w.record(time,time+2,time+14,time+17,time+20,mesh,240,230)==null,"warmup excluded");
            eq(w.frames,0,"zero warm frames");ok(w.record(1000,1002,1014,1017,1020,mesh,240,230)==null,"anchor excluded");eq(w.frames,0,"anchor not counted");
            PolyFillWindow.Result result=null;
            for(int time=1100;time<=4000;time+=100){PolyFillWindow.Result r=w.record(time,time+2,time+14,time+17,time+20,mesh,240,230);if(time<4000)ok(r==null,"full 3 seconds required");else result=r;}
            ok(result!=null,"window completed");eq(result.frames,30,"counted full intervals");eq(result.elapsed,3000,"steady wall time");eq(result.fps10,100,"10.0 FPS");
            eq(result.nativeMean,12,"native mean");eq(result.copyMean,3,"copy mean");eq(result.intervalMean,100,"begin-begin interval");
            eq(result.trianglesPerSecond,(long)mesh.triangles*10,"tri throughput");eq(result.nominalPixelsPerSecond,240L*230*mesh.layers*10,"nominal pixels throughput");
            w.repeat(5000);eq(w.frames,0,"repeat resets");eq(w.warmUntil,5000,"repeat without warmup");
            w.reset(100000);w.record(100010,100010,100011,100012,100013,mesh,240,230);w.record(1000,1000,1001,1002,1003,mesh,240,230);
            eq(w.warmUntil,2003,"clock rollback during warmup resets");eq(w.frames,0,"clock rollback loses no fake frames");
            w.record(3000,3001,2999,3002,3003,mesh,240,230);eq(w.warmUntil,4003,"invalid intra-frame clock rejected");
            w.reset(1000000);eq(w.frames,0,"pause/resume reset");eq(w.start,-1,"paused duration excluded");
            PolyFillWindow.Result huge=new PolyFillWindow.Result(mesh,240,230,100000000,3000,0,0,300000000L);
            eq(huge.trianglesPerSecond,(long)mesh.triangles*100000000*1000/3000,"64-bit rate arithmetic");mesh.dispose();
        }
        ok("8.7".equals(PolyFillPreview.decimal(87,10)),"fractional FPS formatting");ok("15.70".equals(PolyFillPreview.hundredths(15700000,1000000)),"Mpix formatting");ok("0.49".equals(PolyFillPreview.hundredths(496800,1000000)),"sub-million fillrate precision");
    }
    static void completeNext(Object v,Graphics screen)throws Exception {
        paint(v,screen);PolyFillWindow w=(PolyFillWindow)get(v,"window");long now=System.currentTimeMillis();
        w.warmUntil=0;w.start=now-4000;w.last=now-100;w.seen=now-100;w.frames=20;w.nativeMs=240;w.copyMs=60;w.intervalMs=2000;
        paint(v,screen);
    }
    static void controller()throws Exception {
        Object v=viewer();Graphics screen=screen();screen.translate(13,9);screen.setClip(7,8,99,88);
        int rgb=Graphics.drawRGBCalls;Font savedFont=screen.getFont();paint(v,screen);ok(screen.getFont()==savedFont,"restore font");
        ok(get(v,"error")==null,"cold viewer");eq(screen.getTranslateX(),13,"restore translate x");eq(screen.getTranslateY(),9,"restore translate y");eq(screen.getClipX(),7,"restore clip x");eq(screen.getClipWidth(),99,"restore clip width");
        ok(Graphics3D.lastBoundTarget==get(v,"target") && Graphics3D.lastBoundTarget!=screen,"engine offscreen Image target");
        Graphics3D.Draw first=Graphics3D.history.lastElement();eq(first.viewportWidth,240,"viewer width");eq(first.viewportHeight,250,"HUD excluded from measured viewport");
        PolyFillMesh original=(PolyFillMesh)get(v,"work");int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        for(int i=0;i<12;i++)paint(v,screen);
        ok(get(v,"work")==original,"steady work reused");eq(VertexArray.created,arrays,"steady viewer no arrays");eq(VertexArray.sets,sets,"steady viewer no uploads");eq(Graphics3D.uploads,uploads,"steady checker reused");eq(Graphics.drawRGBCalls,rgb,"no software drawRGB fallback");
        key(v,48);paint(v,screen);eq(VertexArray.created,arrays,"reset does not rebuild");
        key(v,51);paint(v,screen);ok(((PolyFillMesh)get(v,"work")).fill,"3 selects fill");ok(!Graphics3D.history.lastElement().depthTest && !Graphics3D.history.lastElement().depthWrite,"fill Z OFF in viewer");
        key(v,50);paint(v,screen);eq(((PolyFillMesh)get(v,"work")).layers,2,"2 increases workload");key(v,56);paint(v,screen);eq(((PolyFillMesh)get(v,"work")).layers,1,"8 decreases workload");
        key(v,49);paint(v,screen);ok(!((PolyFillMesh)get(v,"work")).fill,"1 selects polygon");
        key(v,55);int calls=Graphics3D.calls;paint(v,screen);eq(Graphics3D.calls,calls,"results page stops rendering");key(v,52);paint(v,screen);key(v,54);paint(v,screen);key(v,55);paint(v,screen);
        // Automatic OOM skip, stop to table, then manual retry/recovery.
        key(v,53);VertexArray.failAt=VertexArray.created+1;paint(v,screen);VertexArray.failAt=0;
        ok("OOM".equals(((String[])get(v,"failures"))[0]),"auto records OOM");eq(((Integer)get(v,"autoSlot")).intValue(),1,"auto skips unavailable workload");ok(((Boolean)get(v,"automatic")).booleanValue(),"auto survives OOM");paint(v,screen);
        key(v,53);ok(((Boolean)get(v,"showResults")).booleanValue(),"5 stops to partial results");key(v,55);key(v,56);paint(v,screen);
        key(v,50);VertexArray.failAt=VertexArray.created+1;paint(v,screen);VertexArray.failAt=0;
        ok(get(v,"error")!=null && get(v,"work")==null,"manual OOM waits without retry loop");calls=Graphics3D.calls;paint(v,screen);eq(Graphics3D.calls,calls,"failed page idle");
        key(v,56);paint(v,screen);ok(get(v,"error")==null && get(v,"work")!=null,"lower workload recovers OOM");
        Graphics3D.failAt=Graphics3D.calls+1;paint(v,screen);Graphics3D.failAt=0;ok(get(v,"error")!=null && Graphics3D.target==null,"viewer catches native failure, releases target");key(v,48);paint(v,screen);ok(get(v,"error")==null,"0 retries native failure");
        PrintStream stdout=System.out;System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            completeNext(v,screen);ok(((PolyFillWindow.Result[])get(v,"results"))[0]!=null,"manual result saved");
            key(v,53);
            for(int i=0;i<PolyFillMesh.total();i++) {
                eq(((Integer)get(v,"autoSlot")).intValue(),i,"auto profile order");completeNext(v,screen);
                PolyFillWindow.Result r=((PolyFillWindow.Result[])get(v,"results"))[i];ok(r!=null,"auto result saved");
                ok(r.fill==(i>=PolyFillMesh.GRIDS.length),"auto result mode");eq(r.width,240,"saved width");eq(r.height,250,"saved height");
            }
        }finally{System.setOut(stdout);}
        ok(!((Boolean)get(v,"automatic")).booleanValue() && ((Boolean)get(v,"showResults")).booleanValue(),"auto completes to table");
        calls=Graphics3D.calls;int copies=Graphics.drawImageCalls;for(int i=0;i<3;i++)paint(v,screen);eq(Graphics3D.calls,calls,"completed table no native work");eq(Graphics.drawImageCalls,copies,"table no image copies");
        call(v,"stop",new Class[0],new Object[0]);paint(v,screen);eq(Graphics3D.calls,calls,"pause no native work");
        set(v,"running",Boolean.TRUE);key(v,55);paint(v,screen);PolyFillWindow w=(PolyFillWindow)get(v,"window");eq(w.frames,0,"resume discards paused timing");
        call(v,"hideNotify",new Class[0],new Object[0]);calls=Graphics3D.calls;paint(v,screen);eq(Graphics3D.calls,calls,"hidden Canvas no native work even without pauseApp");
        call(v,"showNotify",new Class[0],new Object[0]);paint(v,screen);eq(w.frames,0,"Canvas show discards hidden duration");
        Canvas.screenWidth=176;Canvas.screenHeight=208;paint(v,screen());
        for(PolyFillWindow.Result r:(PolyFillWindow.Result[])get(v,"results"))ok(r==null,"resize clears incompatible results");
        eq(((Integer)get(v,"areaHeight")).intValue(),118,"resized pixel area");ok(!((Boolean)get(v,"automatic")).booleanValue(),"resize stops auto");
        key(v,55);key(v,54);paint(v,screen());ok(((Integer)get(v,"page")).intValue()>0,"small screen result paging");
        key(v,35);ok(((Boolean)get(v,"closed")).booleanValue() && get(v,"work")==null && get(v,"texture")==null,"# releases and closes");
        Canvas.screenWidth=240;Canvas.screenHeight=340;
        PolyFillPreview app=new PolyFillPreview();app.startApp();Object live=get(app,"viewer");Thread thread=(Thread)get(live,"worker");
        app.pauseApp();ok(!((Boolean)get(live,"running")).booleanValue(),"MIDlet pause");app.startApp();ok(get(live,"worker")==thread,"resume has one worker");app.destroyApp(true);thread.join(2000);ok(!thread.isAlive(),"MIDlet destroy joins worker");

    }
    public static void main(String[] args)throws Exception {
        System.setProperty("m3g.maxTexture","32");boolean rejected=false;try{PolyFillMesh.checker();}catch(IllegalArgumentException expected){rejected=true;}finally{System.clearProperty("m3g.maxTexture");}ok(rejected,"texture cap validated");
        Texture2D checker=PolyFillMesh.checker();eq(checker.image.width,64,"checker width");eq(checker.image.height,64,"checker height");
        for(int i=3;i<checker.image.pixels.length;i+=4)eq(checker.image.pixels[i]&255,255,"opaque checker");
        geometry(checker);faults(checker);windows(checker);controller();
        System.out.println("PASS PolyFill: "+profiles+" workloads, "+checks+" contract/analytical checks; NOT rasterization or handset FPS.");
    }
}
