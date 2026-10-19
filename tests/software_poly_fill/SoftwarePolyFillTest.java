package code.Rendering;

import java.lang.reflect.*;
import java.io.*;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** REAL frozen Quantum software rasterization; LCDUI is doubled. No handset performance inference. */
public final class SoftwarePolyFillTest {
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
        Class c=Class.forName("code.Rendering.SoftwarePolyFillPreview$Viewer");Constructor k=c.getDeclaredConstructor(SoftwarePolyFillPreview.class);k.setAccessible(true);
        Object v=k.newInstance(new SoftwarePolyFillPreview());set(v,"running",Boolean.TRUE);set(v,"visible",Boolean.TRUE);return v;
    }
    static Graphics screen(){Graphics g=Image.createImage(Canvas.screenWidth,Canvas.screenHeight).getGraphics();g.screenTarget=true;return g;}
    static void raster()throws Exception {
        Texture texture=SoftwarePolyFillMesh.checker();
        for(boolean perspective:new boolean[]{true,false})for(boolean fill:new boolean[]{false,true})for(int level=0;level<SoftwarePolyFillMesh.levels(fill);level++) {
            SoftwarePolyFillMesh work=new SoftwarePolyFillMesh(fill,level,texture,240,230,perspective);profiles++;
            work.render(240,230);DirectX7 cpu=(DirectX7)get(work,"cpu");code.Rendering.Meshes.Mesh mesh=(code.Rendering.Meshes.Mesh)get(work,"mesh");
            eq(cpu.getRenderer(),DirectX7.SOFTWARE,"not SOFTWARE");ok(!cpu.isM3GActive() && !cpu.isM3GRequired(),"native path active");
            eq(mesh.getVertices().length,work.vertexCount,"shared vertices");eq(mesh.getPolygons().length,work.triangles,"triangle objects");
            long area=0;int visible=0;
            for(RenderObject object:mesh.getPolygons()) {
                code.Rendering.Meshes.LightedPolygon3V p=(code.Rendering.Meshes.LightedPolygon3V)object;
                if(p.isVisible(0,0,240,230))visible++;
                area+=(long)(p.b.sx-p.a.sx)*(p.c.sy-p.a.sy)-(long)(p.b.sy-p.a.sy)*(p.c.sx-p.a.sx);
                eq(perspective?p.getLight(p.a,p.la,cpu):p.la,220,"effective brightness");
            }
            eq(visible,work.triangles,"collapsed/invisible triangles counted");eq(area,2L*240*230*work.layers,"constant full rectangle times layers");
            int covered=0;for(int pixel:cpu.display)if(pixel!=0x102030)covered++;
            eq(covered,240*230,"real rasterizer left holes for "+fill+"/"+level+"/"+perspective);
            RenderObjectBuffer queue=(RenderObjectBuffer)get(cpu,"buffer");Object queueStorage=get(queue,"buffer");
            int[] buffer=cpu.display;Vertex[] vertices=mesh.getVertices();RenderObject[] faces=mesh.getPolygons();
            work.render(240,230);ok(buffer==cpu.display && vertices==mesh.getVertices() && faces==mesh.getPolygons(),"hot geometry/framebuffer rebuilding");ok(queueStorage==get(queue,"buffer"),"hot queue allocation");
            Graphics target=Image.createImage(240,340).getGraphics();int blits=Graphics.drawRGBCalls;work.present(target,0,60);eq(Graphics.drawRGBCalls-blits,1,"not original drawRGB presentation");
            for(int y=0;y<230;y++)for(int x=0;x<240;x++)eq(target.image.pixels[(y+60)*240+x]&0xffffff,cpu.display[y*240+x]&0xffffff,"drawRGB changed pixels");
            work.dispose();boolean failed=false;try{work.render(240,230);}catch(IllegalStateException e){failed=true;}ok(failed,"disposed render");
        }
    }
    static final class Probe extends RenderObject {
        final RenderObject delegate;int renders,visits;
        Probe(RenderObject d){delegate=d;}
        public boolean isVisible(int x1,int y1,int x2,int y2){visits++;boolean result=delegate.isVisible(x1,y1,x2,y2);sz=delegate.sz;return result;}
        public void render(DirectX7 cpu,Texture t){renders++;delegate.render(cpu,t);}
        public void renderFast(DirectX7 cpu,Texture t){renders++;delegate.renderFast(cpu,t);}
    }
    static void actualDispatch()throws Exception {
        SoftwarePolyFillMesh work=new SoftwarePolyFillMesh(true,5,SoftwarePolyFillMesh.checker(),240,230,true);
        code.Rendering.Meshes.Mesh old=(code.Rendering.Meshes.Mesh)get(work,"mesh");RenderObject[] probes=new RenderObject[work.triangles];
        for(int i=0;i<probes.length;i++)probes[i]=new Probe(old.getPolygons()[i]);
        set(work,"mesh",new code.Rendering.Meshes.Mesh(old.getVertices(),probes,old.getTexture()));work.render(240,230);
        for(RenderObject o:probes){Probe p=(Probe)o;eq(p.renders,1,"overdraw layer not really rasterized");eq(p.visits,1,"visibility not really tested");}work.dispose();
        for(int[] size:new int[][]{{241,231},{320,150}}){work=new SoftwarePolyFillMesh(false,7,SoftwarePolyFillMesh.checker(),size[0],size[1],true);work.render(size[0],size[1]);DirectX7 cpu=(DirectX7)get(work,"cpu");for(int pixel:cpu.display)ok(pixel!=0x102030,"odd/landscape hole");work.dispose();}
        boolean failed=false;try{new SoftwarePolyFillMesh(false,7,SoftwarePolyFillMesh.checker(),100,100,true);}catch(IllegalArgumentException e){failed=true;}ok(failed,"counting collapsed triangles at too small resolution");
    }
    static void window() {
        SoftwarePolyFillMesh work=new SoftwarePolyFillMesh(false,0,SoftwarePolyFillMesh.checker(),240,230,true);SoftwarePolyFillWindow w=new SoftwarePolyFillWindow();w.reset(0);
        ok(w.record(500,500,510,511,512,work,240,230)==null,"warm frame counted");ok(w.record(1000,1000,1010,1011,1012,work,240,230)==null,"anchor counted");
        SoftwarePolyFillWindow.Result r=null;for(int t=2000;t<=5000;t+=1000)r=w.record(t,t,t+10,t+11,t+12,work,240,230);
        ok(r!=null,"no completed window");eq(r.frames,4,"frames");eq(r.fps10,10,"fractional FPS");eq(r.renderMean,10,"R mean");eq(r.copyMean,1,"C mean");eq(r.trianglesPerSecond,128,"tri/s");eq(r.nominalPixelsPerSecond,55200,"nominal pixels/s");
        ok(w.record(100,100,110,111,112,work,240,230)==null,"backward clock");eq(w.frames,0,"clock reset");work.dispose();
    }
    static void controller()throws Exception {
        Object v=viewer();Graphics g=screen();paint(v,g);ok(get(v,"error")==null,"default paint failed");eq((Integer)get(v,"areaHeight"),Canvas.screenHeight-90,"viewport");
        key(v,53);ok((Boolean)get(v,"automatic"),"5 auto");
        for(int slot=0;slot<14;slot++) {
            paint(v,g);ok(get(v,"error")==null,"auto workload failed");
            SoftwarePolyFillMesh work=(SoftwarePolyFillMesh)get(v,"work");eq(SoftwarePolyFillMesh.slot(work.fill,work.level),slot,"auto order");
            SoftwarePolyFillWindow w=(SoftwarePolyFillWindow)get(v,"window");long now=System.currentTimeMillis();w.warmUntil=0;w.start=now-4000;w.last=now-100;w.seen=-1;w.frames=10;
            paint(v,g);
        }
        ok(!(Boolean)get(v,"automatic") && (Boolean)get(v,"showResults"),"auto completion/table");
        Object[] results=(Object[])get(v,"results");for(Object r:results)ok(r!=null,"missing auto result");
        key(v,42);ok(!(Boolean)get(v,"perspective"),"P0 toggle");for(Object r:results)ok(r==null,"mixed P4/P0 table");paint(v,g);ok(get(v,"error")==null,"affine paint failed");ok(!((SoftwarePolyFillMesh)get(v,"work")).perspective,"P0 UI kept P4 workload");
        call(v,"hideNotify",new Class[0],new Object[0]);int copies=Graphics.drawRGBCalls;paint(v,g);eq(Graphics.drawRGBCalls,copies,"hidden drawing");call(v,"showNotify",new Class[0],new Object[0]);ok((Boolean)get(v,"resetRequested"),"foreground measurement not reset");
        key(v,53);paint(v,g);Canvas.screenWidth=241;paint(v,g);ok(!(Boolean)get(v,"automatic"),"resize auto not stopped");Canvas.screenWidth=240;
        key(v,35);ok((Boolean)get(v,"closed"),"exit");
        v=viewer();key(v,53);paint(v,g);call(v,"failure",new Class[]{Throwable.class},new Object[]{new OutOfMemoryError("test")});eq((Integer)get(v,"autoSlot"),1,"OOM auto did not skip");ok(((String[])get(v,"failures"))[0].equals("OOM"),"OOM status missing");
        key(v,49);paint(v,g);call(v,"failure",new Class[]{Throwable.class},new Object[]{new IllegalStateException("test")});ok(get(v,"error")!=null,"manual failure hidden");key(v,48);paint(v,g);ok(get(v,"error")==null,"0 retry failed");key(v,35);
    }
    public static void main(String[] args)throws Exception {raster();actualDispatch();window();controller();System.out.println("PASS SoftwarePolyFill: "+checks+" checks; "+profiles+" REAL SOFTWARE raster workloads. No phone speed claims.");}
}
