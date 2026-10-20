package code.Rendering;

import code.Math.Matrix;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** BVH/GRID + HUD UI contracts over native doubles; NOT raster/FPS. */
public final class RiverMapBvhHudTest {
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
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b,String label) {
        eq(a.size(),b.size(),label+" changed native call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),label+" changed packed geometry");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),label+" changed selected strip/index stream");
            ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),label+" changed packing");
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),label+" changed state/projection");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),label+" changed texture/river frame");
        }
    }
    static List<Graphics3D.Draw> render(StaticRoomBvhMesh m,Matrix pose,Graphics target,boolean cull,boolean bvh){
        m.setCullingEnabled(cull);m.setBvhEnabled(bvh);Graphics3D.history.clear();m.render(target,pose,240,340,225,225,60000);
        ok(Graphics3D.target==null && Graphics3D.boundDepth,"target/depth");return capture();
    }
    static void resetScreen(Graphics screen){screen.stringCalls=0;screen.rectCalls=0;screen.lastString=null;Graphics3D.history.clear();}
    static void freezeKeys(Object viewer)throws Exception{set(viewer,"move",Integer.valueOf(1));set(viewer,"turn",Integer.valueOf(1));set(viewer,"strafe",Integer.valueOf(1));}
    static void assertFrozen(Object viewer)throws Exception{eq(((Integer)get(viewer,"move")).intValue(),0,"move not frozen");eq(((Integer)get(viewer,"turn")).intValue(),0,"turn not frozen");eq(((Integer)get(viewer,"strafe")).intValue(),0,"strafe not frozen");}
    static boolean bool(Object viewer,String name)throws Exception{return ((Boolean)get(viewer,name)).booleanValue();}
    static int integer(Object viewer,String name)throws Exception{return ((Integer)get(viewer,name)).intValue();}

    public static void main(String[] args)throws Exception{
        Class<?> cls=Class.forName("code.Rendering.RiverMapBvhHudPreview$Viewer");
        Constructor<?> ctor=cls.getDeclaredConstructor(RiverMapBvhHudPreview.class);ctor.setAccessible(true);
        Object viewer=ctor.newInstance(new RiverMapBvhHudPreview());
        call(viewer,"load",new Class[0],new Object[0]);
        StaticRoomBvhMesh room=(StaticRoomBvhMesh)get(viewer,"nativeRoom");
        MultyTexture materials=(MultyTexture)get(viewer,"materials");
        Texture river=materials.textures[2];
        eq(room.triangles,4750,"river map triangle count");eq(room.uploadedVertices,10032,"river map native corners");eq(room.parts(),3,"river map packets");
        eq(integer(viewer,"far"),60000,"far");eq(integer(viewer,"configuredFar"),68500,"configured far");
        ok(bool(viewer,"bvh"),"viewer default not BVH");ok(room.isBvhEnabled(),"room default not BVH");
        eq(river.animMIP.length,4,"river animation frames");
        int homeX=integer(viewer,"x"),homeY=integer(viewer,"y"),homeZ=integer(viewer,"z"),homeYaw=integer(viewer,"yaw");

        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{-28998,432,12168}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=60)for(int frame=0;frame<4;frame++){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=river.animMIP[frame].rImg;
            render(room,pose,target,false,true);eq(room.submittedTriangles,4750,"whole river map with culling disabled");
            render(room,pose,target,true,true);int bvhT=room.submittedTriangles;ok(room.draws<=3,"extra BVH native calls");
            int indices=TriangleStripArray.created,masks=room.maskUploads;render(room,pose,target,true,true);
            eq(TriangleStripArray.created,indices,"stationary BVH indices rebuilt");eq(room.maskUploads,masks,"stationary BVH mask rebuilt");eq(room.submittedTriangles,bvhT,"stationary BVH T changed");
            render(room,pose,target,true,false);int gridT=room.submittedTriangles;ok(room.draws<=3,"extra GRID native calls");ok(bvhT<=gridT,"BVH submitted more than GRID in same pose");
            indices=TriangleStripArray.created;masks=room.maskUploads;render(room,pose,target,true,false);
            eq(TriangleStripArray.created,indices,"stationary GRID indices rebuilt");eq(room.maskUploads,masks,"stationary GRID mask rebuilt");eq(room.submittedTriangles,gridT,"stationary GRID T changed");
            cases++;
        }
        eq(VertexArray.created,va,"hot geometry allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");

        Matrix fixed=new Matrix();fixed.setRotY(homeYaw);fixed.setPosition(homeX,homeY,homeZ);river.rImg=river.animMIP[2].rImg;
        render(room,fixed,target,true,true);List<Graphics3D.Draw> beforeBvh=render(room,fixed,target,true,true);int homeBvhT=room.submittedTriangles;
        render(room,fixed,target,true,false);List<Graphics3D.Draw> beforeGrid=render(room,fixed,target,true,false);int homeGridT=room.submittedTriangles;
        ok(homeBvhT>0 && homeBvhT<homeGridT,"home BVH should submit fewer triangles than GRID: "+homeBvhT+"/"+homeGridT);
        room.setBvhEnabled(true);

        set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);
        Graphics screen=Image.createImage(240,340).getGraphics();
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"first BVH FULL paint");
        eq(integer(viewer,"hudMode"),0,"default HUD FULL");ok(bool(viewer,"bvh"),"default viewer BVH");ok(room.isBvhEnabled(),"paint did not apply BVH");
        eq(screen.stringCalls,6,"FULL HUD strings");eq(screen.rectCalls,1,"FULL HUD panel");ok(((String)get(viewer,"heading")).startsWith("BVH FULL"),"BVH FULL heading");
        int x=integer(viewer,"x"),y=integer(viewer,"y"),z=integer(viewer,"z"),yaw=integer(viewer,"yaw");Object surface=get(viewer,"surface");

        freezeKeys(viewer);key(viewer,57);assertFrozen(viewer);ok(bool(viewer,"resetRequested"),"GRID switch warmup");ok(!bool(viewer,"bvh"),"key9 not GRID");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"GRID FULL paint");ok(!room.isBvhEnabled(),"paint did not apply GRID");
        eq(screen.stringCalls,6,"GRID FULL strings");ok(((String)get(viewer,"heading")).startsWith("GRID FULL"),"GRID FULL heading");

        freezeKeys(viewer);key(viewer,55);assertFrozen(viewer);ok(bool(viewer,"bvh"),"key7 not BVH");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"returned BVH FULL paint");ok(room.isBvhEnabled(),"paint did not reapply BVH");
        ok(((String)get(viewer,"heading")).startsWith("BVH FULL"),"returned BVH FULL heading");

        freezeKeys(viewer);key(viewer,51);assertFrozen(viewer);eq(integer(viewer,"hudMode"),1,"key3 HUD MIN");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"BVH MIN paint");
        eq(screen.stringCalls,2,"MIN HUD strings");eq(screen.rectCalls,1,"MIN HUD panel");ok(((String)get(viewer,"heading")).startsWith("BVH MIN"),"BVH MIN heading");

        freezeKeys(viewer);key(viewer,48);assertFrozen(viewer);eq(integer(viewer,"hudMode"),2,"key0 HUD OFF");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"BVH OFF paint");
        eq(screen.stringCalls,0,"OFF HUD strings");eq(screen.rectCalls,0,"OFF HUD panel");ok(((String)get(viewer,"heading")).startsWith("BVH OFF"),"BVH OFF heading");
        ok(!bool(viewer,"hasResult"),"OFF warmup should not publish");

        set(viewer,"heading","BVH OFF FPS 9.9");set(viewer,"timings","N 1 C 2 I 3 ms");set(viewer,"counts","T 1 DC 1 S 1");
        set(viewer,"extra","V 1 CUT 1 U 1");set(viewer,"hasResult",Boolean.TRUE);
        freezeKeys(viewer);key(viewer,42);assertFrozen(viewer);ok(bool(viewer,"showResult"),"star did not freeze OFF result");
        key(viewer,52);eq(integer(viewer,"turn"),0,"movement accepted while result frozen");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"frozen result paint");
        eq(Graphics3D.history.size(),0,"frozen result rendered native map");eq(screen.stringCalls,6,"frozen result strings");eq(screen.rectCalls,1,"frozen result panel");
        ok("* resume;1/3/0 HUD".equals(screen.lastString),"frozen result help line");

        key(viewer,57);ok(!bool(viewer,"showResult"),"GRID key did not leave frozen result");ok(!bool(viewer,"bvh"),"frozen GRID key not applied");ok(bool(viewer,"resetRequested"),"frozen GRID warmup");
        resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"GRID OFF paint after frozen result");
        eq(screen.stringCalls,0,"GRID OFF strings after frozen result");eq(screen.rectCalls,0,"GRID OFF panel after frozen result");
        ok(((String)get(viewer,"heading")).startsWith("GRID OFF"),"GRID OFF heading after frozen result");ok(!room.isBvhEnabled(),"GRID OFF paint did not apply GRID");ok(!bool(viewer,"hasResult"),"GRID OFF did not clear result");

        key(viewer,55);ok(bool(viewer,"bvh"),"key7 after GRID OFF not BVH");resetScreen(screen);paint(viewer,screen);
        eq(screen.stringCalls,0,"BVH OFF strings after selection return");ok(((String)get(viewer,"heading")).startsWith("BVH OFF"),"BVH OFF heading after selection return");ok(room.isBvhEnabled(),"BVH OFF paint did not apply BVH");

        key(viewer,49);eq(integer(viewer,"hudMode"),0,"key1 HUD FULL");resetScreen(screen);paint(viewer,screen);ok(get(viewer,"error")==null,"returned BVH FULL paint 2");
        eq(screen.stringCalls,6,"returned FULL strings");eq(screen.rectCalls,1,"returned FULL panel");ok(((String)get(viewer,"heading")).startsWith("BVH FULL"),"returned BVH FULL heading 2");
        eq(integer(viewer,"x"),x,"switches moved x");eq(integer(viewer,"y"),y,"switches moved y");eq(integer(viewer,"z"),z,"switches moved z");eq(integer(viewer,"yaw"),yaw,"switches moved yaw");
        ok(surface==get(viewer,"surface"),"switches rebuilt surface");eq(((StaticRoomPerspectiveStats)get(viewer,"stats")).frames,0,"switch frame measured");

        river.rImg=river.animMIP[2].rImg;sameA(beforeBvh,render(room,fixed,target,true,true),"BVH after UI");
        river.rImg=river.animMIP[2].rImg;sameA(beforeGrid,render(room,fixed,target,true,false),"GRID after UI");room.setBvhEnabled(true);

        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            System.setProperty(flag,"true");boolean failed=false;try{render(room,fixed,target,true,true);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak "+flag);
        }
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");
        call(viewer,"showNotify",new Class[0],new Object[0]);ok(bool(viewer,"resetRequested"),"resume warmup after hide");

        StaticRoomPerspectiveStats stats=new StaticRoomPerspectiveStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522),"warmup published");eq(stats.frames,0,"warmup counted");
        ok(!stats.record(1000,1000,1020,1021,1022),"anchor published");eq(stats.frames,0,"anchor counted");
        ok(!stats.record(2000,2000,2020,2021,2022),"short window published");ok(stats.record(4000,4000,4020,4021,4022),"complete window missing");
        eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"native wall time");eq(stats.meanCopy,1,"copy wall time");eq(stats.meanInterval,1500,"interval");

        eq(VertexArray.created,va,"UI geometry allocation");eq(VertexArray.sets,sets,"UI attribute upload");eq(Graphics3D.uploads,uploads,"UI texture upload");
        call(viewer,"release",new Class[0],new Object[0]);
        System.out.println("PASS RiverMapBvhHud: "+checks+" checks / "+cases+" poses/river frames. BVH/HUD contract doubles only; NOT device FPS or rasterization.");
    }
}
