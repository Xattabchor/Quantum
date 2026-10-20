package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** HUD/UI contracts over immutable river renderer doubles; NOT raster/FPS. */
public final class RiverMapHudTest {
    static long checks;static int cases;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message+" ("+x+" != "+y+")");}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"HUD switch changed native call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),"HUD switch changed packed geometry");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"HUD switch changed selected strip/index stream");
            ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"HUD switch changed packing");
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"HUD switch changed state/projection");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"HUD switch changed texture/river frame");
        }
    }
    static List<Graphics3D.Draw> render(StaticRoomPerspectiveMesh m,Matrix pose,Graphics target,boolean cull){
        m.setCullingEnabled(cull);Graphics3D.history.clear();m.render(target,pose,240,340,225,225,60000);ok(Graphics3D.target==null && Graphics3D.boundDepth,"target/depth");return capture();
    }
    static void resetScreen(Graphics screen){screen.stringCalls=0;screen.rectCalls=0;screen.lastString=null;Graphics3D.history.clear();}
    static void freezeKeys(Object viewer)throws Exception{set(viewer,"move",Integer.valueOf(1));set(viewer,"turn",Integer.valueOf(1));set(viewer,"strafe",Integer.valueOf(1));}
    static void assertFrozen(Object viewer)throws Exception{eq(((Integer)get(viewer,"move")).intValue(),0,"move not frozen");eq(((Integer)get(viewer,"turn")).intValue(),0,"turn not frozen");eq(((Integer)get(viewer,"strafe")).intValue(),0,"strafe not frozen");}

    public static void main(String[] args)throws Exception{
        Class<?> cls=Class.forName("code.Rendering.RiverMapHudPreview$Viewer");
        Constructor<?> ctor=cls.getDeclaredConstructor(RiverMapHudPreview.class);ctor.setAccessible(true);
        Object viewer=ctor.newInstance(new RiverMapHudPreview());
        call(viewer,"load",new Class[0],new Object[0]);
        StaticRoomPerspectiveMesh room=(StaticRoomPerspectiveMesh)get(viewer,"nativeRoom");
        MultyTexture materials=(MultyTexture)get(viewer,"materials");
        Texture river=materials.textures[2];
        eq(room.triangles,4750,"river map triangle count");eq(room.uploadedVertices,10032,"river map native corners");eq(room.parts(),3,"river map packets");
        eq(((Integer)get(viewer,"far")).intValue(),60000,"far");eq(((Integer)get(viewer,"configuredFar")).intValue(),68500,"configured far");
        ok(room.isPerspectiveEnabled(),"perspective must stay ON in HUD diagnostic");
        eq(river.animMIP.length,4,"river animation frames");
        int homeX=((Integer)get(viewer,"x")).intValue(),homeY=((Integer)get(viewer,"y")).intValue(),homeZ=((Integer)get(viewer,"z")).intValue(),homeYaw=((Integer)get(viewer,"yaw")).intValue();

        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{-28998,432,12168}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=60)for(int frame=0;frame<4;frame++){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=river.animMIP[frame].rImg;
            render(room,pose,target,false);eq(room.submittedTriangles,4750,"whole river map with culling disabled");
            render(room,pose,target,true);ok(room.draws<=3,"extra native calls");int indices=TriangleStripArray.created,masks=room.maskUploads;
            render(room,pose,target,true);eq(TriangleStripArray.created,indices,"stationary indices rebuilt");eq(room.maskUploads,masks,"stationary mask rebuilt");
            cases++;
        }
        eq(VertexArray.created,va,"hot geometry allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");

        Matrix fixed=new Matrix();fixed.setRotY(homeYaw);fixed.setPosition(homeX,homeY,homeZ);river.rImg=river.animMIP[2].rImg;
        render(room,fixed,target,true);List<Graphics3D.Draw> before=render(room,fixed,target,true);int beforeIndices=TriangleStripArray.created;

        set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);
        Graphics screen=Image.createImage(240,340).getGraphics();
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"first FULL paint");
        eq(((Integer)get(viewer,"hudMode")).intValue(),0,"default HUD FULL");eq(screen.stringCalls,6,"FULL HUD strings");eq(screen.rectCalls,1,"FULL HUD panel");
        ok(((String)get(viewer,"heading")).startsWith("RIVER FULL"),"FULL heading");
        int x=((Integer)get(viewer,"x")).intValue(),y=((Integer)get(viewer,"y")).intValue(),z=((Integer)get(viewer,"z")).intValue(),yaw=((Integer)get(viewer,"yaw")).intValue();
        Object surface=get(viewer,"surface");

        freezeKeys(viewer);key(viewer,51);assertFrozen(viewer);ok(((Boolean)get(viewer,"resetRequested")).booleanValue(),"MIN switch warmup");
        eq(((Integer)get(viewer,"hudMode")).intValue(),1,"key3 HUD MIN");resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"MIN paint");
        eq(screen.stringCalls,2,"MIN HUD strings");eq(screen.rectCalls,1,"MIN HUD panel");ok(((String)get(viewer,"heading")).startsWith("RIVER MIN"),"MIN heading");

        freezeKeys(viewer);key(viewer,48);assertFrozen(viewer);ok(((Boolean)get(viewer,"resetRequested")).booleanValue(),"OFF switch warmup");
        eq(((Integer)get(viewer,"hudMode")).intValue(),2,"key0 HUD OFF");resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"OFF paint");
        eq(screen.stringCalls,0,"OFF HUD strings");eq(screen.rectCalls,0,"OFF HUD panel");ok(((String)get(viewer,"heading")).startsWith("RIVER OFF"),"OFF heading");
        ok(!((Boolean)get(viewer,"hasResult")).booleanValue(),"OFF warmup should not publish");

        set(viewer,"heading","RIVER OFF FPS 9.9");set(viewer,"timings","N 1 C 2 I 3 ms");set(viewer,"counts","T 1 DC 1 VB 1");
        set(viewer,"extra","S 1 V 1 CUT 1");set(viewer,"stages","J/B/K/R/E 1/1/1/1/1");set(viewer,"hasResult",Boolean.TRUE);
        freezeKeys(viewer);key(viewer,42);assertFrozen(viewer);ok(((Boolean)get(viewer,"showResult")).booleanValue(),"star did not freeze OFF result");
        key(viewer,52);eq(((Integer)get(viewer,"turn")).intValue(),0,"movement accepted while result frozen");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"frozen result paint");
        eq(Graphics3D.history.size(),0,"frozen result rendered native map");eq(screen.stringCalls,6,"frozen result strings");eq(screen.rectCalls,1,"frozen result panel");
        ok("* resume;1/3/0 HUD;# exit".equals(screen.lastString),"frozen result help line");

        key(viewer,42);ok(!((Boolean)get(viewer,"showResult")).booleanValue(),"star did not resume");ok(((Boolean)get(viewer,"resetRequested")).booleanValue(),"resume warmup");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"resumed OFF paint");
        eq(screen.stringCalls,0,"resumed OFF strings");eq(screen.rectCalls,0,"resumed OFF panel");ok(((String)get(viewer,"heading")).startsWith("RIVER OFF"),"resumed OFF heading");
        ok(!((Boolean)get(viewer,"hasResult")).booleanValue(),"resume did not clear result");

        key(viewer,49);eq(((Integer)get(viewer,"hudMode")).intValue(),0,"key1 HUD FULL");resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"returned FULL paint");
        eq(screen.stringCalls,6,"returned FULL strings");eq(screen.rectCalls,1,"returned FULL panel");
        eq(((Integer)get(viewer,"x")).intValue(),x,"HUD switches moved x");eq(((Integer)get(viewer,"y")).intValue(),y,"HUD switches moved y");
        eq(((Integer)get(viewer,"z")).intValue(),z,"HUD switches moved z");eq(((Integer)get(viewer,"yaw")).intValue(),yaw,"HUD switches moved yaw");
        ok(surface==get(viewer,"surface"),"HUD switches rebuilt surface");
        eq(((StaticRoomPerspectiveStats)get(viewer,"stats")).frames,0,"switch frame measured");

        river.rImg=river.animMIP[2].rImg;List<Graphics3D.Draw> after=render(room,fixed,target,true);sameA(before,after);
        eq(TriangleStripArray.created,beforeIndices,"HUD switches rebuilt stationary indices");

        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            System.setProperty(flag,"true");boolean failed=false;try{render(room,fixed,target,true);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak "+flag);
        }
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");
        call(viewer,"showNotify",new Class[0],new Object[0]);ok(((Boolean)get(viewer,"resetRequested")).booleanValue(),"resume warmup after hide");

        StaticRoomPerspectiveStats stats=new StaticRoomPerspectiveStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522),"warmup published");eq(stats.frames,0,"warmup counted");
        ok(!stats.record(1000,1000,1020,1021,1022),"anchor published");eq(stats.frames,0,"anchor counted");
        room.prepareMs=2;room.bindMs=3;room.clearMs=4;room.drawMs=5;room.releaseMs=6;
        ok(!stats.record(2000,2000,2020,2021,2022,room),"short window published");ok(stats.record(4000,4000,4020,4021,4022,room),"complete window missing");
        eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"native wall time");eq(stats.meanCopy,1,"copy wall time");eq(stats.meanInterval,1500,"interval");
        eq(stats.meanJ,2,"J");eq(stats.meanB,3,"B");eq(stats.meanK,4,"K");eq(stats.meanR,5,"R");eq(stats.meanE,6,"E");

        eq(VertexArray.created,va,"UI geometry allocation");eq(VertexArray.sets,sets,"UI attribute upload");eq(Graphics3D.uploads,uploads,"UI texture upload");
        call(viewer,"release",new Class[0],new Object[0]);
        System.out.println("PASS RiverMapHud: "+checks+" checks / "+cases+" poses/river frames. HUD contract doubles only; NOT device FPS or rasterization.");
    }
}
