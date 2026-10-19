package code.Rendering;

import code.Gameplay.Map.*;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.utils.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Exact ordered native strip/attribute/state comparison; NOT a rasterizer. */
public final class PortalMapFarTest {
    static long checks;static int cases;static List<Graphics3D.Draw> uiBaseline;static float[] uiCamera;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"A changed call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),"A packed geometry differs from immutable Tex3");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"A selected strip/index stream changed");
            ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"A packing changed");
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"A state/projection changed");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"A texture/river changed");
        }
    }
    static Mesh[] load()throws Exception{Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.m3gPerspective=true;return PortalMapData.load(PortalMapData.config());}
    static List<Graphics3D.Draw> draw(PortalMapMesh mesh,Graphics target,Matrix pose,int far){Graphics3D.history.clear();mesh.render(target,pose,240,320,212,212,far);ok(Graphics3D.target==null && Graphics3D.boundDepth,"depth/target");return capture();}
    static void projection(float[] p,int far){
        ok(p[10]==-(far+128.0f)/(far-128.0f),"wrong far z coefficient");ok(p[11]==-256.0f*far/(far-128.0f),"wrong far w coefficient");
        double near=(p[10]*-128+p[11])/128,edge=(p[10]*-far+p[11])/far;
        ok(Math.abs(near+1)<.00001 && Math.abs(edge-1)<.00001,"near/far clip endpoints");
        double depth25000=(p[10]*-25000+p[11])/25000;ok(far==20000?depth25000>1:depth25000<1,"cutoff does not actually move");
    }
    static void sameExceptFar(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b,int far){
        eq(a.size(),b.size(),"far changed calls");
        for(int i=0;i<a.size();i++){
            Graphics3D.Draw x=a.get(i),y=b.get(i);ok(x.vertexBuffer==y.vertexBuffer,"far replaced resident VB");
            ok(Arrays.equals(x.positions,y.positions)&&Arrays.equals(x.coords,y.coords)&&Arrays.equals(x.colors,y.colors),"far changed attributes");ok(Arrays.equals(x.indices,y.indices)&&Arrays.equals(x.lengths,y.lengths),"far changed indices/order");
            ok(Arrays.equals(state(x),state(y)),"far changed material/alpha/depth state");ok(Arrays.equals(x.bias,y.bias)&&x.scale==y.scale&&x.uvScale==y.uvScale,"packing");
            ok(x.source==y.source&&Arrays.equals(x.source.image.pixels,y.source.image.pixels),"texture");
            for(int k=0;k<16;k++)if(k!=10&&k!=11)ok(x.projection[k]==y.projection[k],"non-depth projection coefficient changed");projection(y.projection,far);
        }
    }
    static void paintFar(Object viewer,Graphics screen,int keyCode,int expected)throws Exception{
        set(viewer,"turn",1);set(viewer,"move",1);set(viewer,"strafe",1);key(viewer,keyCode);
        eq((Integer)get(viewer,"far"),expected,"key selects wrong far");eq((Integer)get(viewer,"turn"),0,"turn not frozen");eq((Integer)get(viewer,"move"),0,"walk not frozen");eq((Integer)get(viewer,"strafe"),0,"strafe not frozen");ok((Boolean)get(viewer,"resetRequested"),"switch did not reset warmup");
        Graphics3D.history.clear();paint(viewer,screen);ok(get(viewer,"error")==null,"UI error");ok(((String)get(viewer,"heading")).startsWith("FAR "+expected),"HUD mislabeled far");
        sameExceptFar(uiBaseline,capture(),expected);ok(Arrays.equals(uiCamera,Graphics3D.cameraTransform.matrix),"UI far moved native camera");
        PortalMapMesh mesh=(PortalMapMesh)get(viewer,"nativeRoom");ok(mesh.isPortalsEnabled(),"far key disabled portals");for(Graphics3D.Draw d:Graphics3D.history)projection(d.projection,expected);
        PortalMapStats stats=(PortalMapStats)get(viewer,"stats");eq(stats.frames,0,"switch frame included in measured window");
    }
    public static void main(String[] args)throws Exception {
        Mesh[] source=load();PortalMapMesh mesh=new PortalMapMesh(source,2,true);mesh.setNearPlane(128);mesh.setPortalsEnabled(true);
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,indices=TriangleStripArray.created;
        int[][] points={{-76338,-1033,-102034},{-75600,0,-70000},{-46000,0,-57000},{-27000,0,-96000},{-110000,0,-120000},{1000000,0,1000000}};
        int globalFar=DirectX7.drDist;
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);List<Graphics3D.Draw> base=draw(mesh,target,pose,40000);float[] camera=Graphics3D.cameraTransform.matrix.clone();int mask=mesh.roomMask,t=mesh.submittedTriangles,vb=mesh.submittedBufferVertices,rooms=mesh.selectedRooms;
            for(int far:new int[]{30000,20000,40000}){
                sameExceptFar(base,draw(mesh,target,pose,far),far);ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"far moved camera");eq(mesh.roomMask,mask,"far changed room selection");eq(mesh.submittedTriangles,t,"T");eq(mesh.submittedBufferVertices,vb,"VB");eq(mesh.selectedRooms,rooms,"Rooms");cases++;
            }
        }
        eq(DirectX7.drDist,globalFar,"renderer rewrote global config");eq(VertexArray.created,va,"hot vertex allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");eq(TriangleStripArray.created,indices,"hot index build");
        for(int far:new int[]{40000,30000,20000})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            System.setProperty(flag,"true");boolean failed=false;try{mesh.render(target,pose,240,320,212,212,far);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak "+flag);
        }
        Class<?> cls=Class.forName("code.Rendering.PortalMapFarPreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);Object viewer=ctor.newInstance(new PortalMapFarPreview());call(viewer,"load",new Class[0],new Object[0]);eq((Integer)get(viewer,"far"),40000,"default far");eq((Integer)get(viewer,"configuredFar"),40000,"original config");set(viewer,"visible",true);set(viewer,"running",true);
        Graphics screen=Image.createImage(240,340).getGraphics();Graphics3D.history.clear();paint(viewer,screen);uiBaseline=capture();uiCamera=Graphics3D.cameraTransform.matrix.clone();ok(get(viewer,"error")==null,"initial paint error");int x=(Integer)get(viewer,"x"),y=(Integer)get(viewer,"y"),z=(Integer)get(viewer,"z"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface"),nativeRoom=get(viewer,"nativeRoom");
        int beforeVa=VertexArray.created,beforeIndices=TriangleStripArray.created;
        paintFar(viewer,screen,48,30000);paintFar(viewer,screen,48,20000);paintFar(viewer,screen,48,40000);paintFar(viewer,screen,42,20000);paintFar(viewer,screen,51,30000);paintFar(viewer,screen,49,40000);
        eq((Integer)get(viewer,"x"),x,"key moved x");eq((Integer)get(viewer,"y"),y,"key moved y");eq((Integer)get(viewer,"z"),z,"key moved z");eq((Integer)get(viewer,"yaw"),yaw,"key changed yaw");ok(surface==get(viewer,"surface")&&nativeRoom==get(viewer,"nativeRoom"),"key recreated target/mesh");eq(VertexArray.created,beforeVa,"key allocated geometry");eq(TriangleStripArray.created,beforeIndices,"key rebuilt indices");eq(DirectX7.drDist,40000,"key changed original global distance");
        set(viewer,"x",x+1000);key(viewer,53);eq((Integer)get(viewer,"x"),x,"home key");
        boolean failed=false;try{call(viewer,"selectFar",new Class[]{int.class},new Object[]{0});}catch(InvocationTargetException e){failed=e.getCause() instanceof IllegalArgumentException;}ok(failed,"invalid preset accepted");eq((Integer)get(viewer,"far"),40000,"invalid preset changed far");
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");call(viewer,"showNotify",new Class[0],new Object[0]);ok((Boolean)get(viewer,"resetRequested"),"resume warmup");call(viewer,"release",new Class[0],new Object[0]);
        PortalMapStats stats=new PortalMapStats();stats.reset(0);ok(!stats.record(500,500,520,521,522),"warmup");ok(!stats.record(1000,1000,1020,1021,1022),"anchor");ok(!stats.record(2000,2000,2020,2021,2022),"short window");ok(stats.record(4000,4000,4020,4021,4022),"complete window");eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"N wall time");stats.reset(0);stats.record(1000,1000,1020,1021,1022);stats.record(2000,2000,2020,2021,2022);ok(!stats.record(1500,1500,1520,1521,1522),"backward clock");eq(stats.frames,0,"clock reset");
        mesh.dispose();System.out.println("PASS PortalMapFar: "+checks+" assertions / "+cases+" far comparisons at 432 camera poses. Only projection depth coefficients differ. NOT device performance or native rasterization.");
    }
}
