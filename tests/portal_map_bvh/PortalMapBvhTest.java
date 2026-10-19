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
public final class PortalMapBvhTest {
    static long checks;static int cases,water,baseCalls,smallCalls;
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
    static Mesh[] load()throws Exception{Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.m3gPerspective=true;return PortalMapBvhData.load(PortalMapData.config());}
    static List<Graphics3D.Draw> render(PortalMapBvhMesh m,Matrix pose,Graphics target,boolean bvh,int w,int h,int fx,int fy,int far){
        m.setBvhEnabled(bvh);Graphics3D.history.clear();m.render(target,pose,w,h,fx,fy,far);ok(Graphics3D.target==null && Graphics3D.boundDepth,"depth/target");return capture();
    }
    static Map<VertexBuffer,int[]> windows(PortalMapBvhMesh m,int w,int h)throws Exception{
        Map<VertexBuffer,int[]> result=new HashMap<VertexBuffer,int[]>();PortalMapBvhVisibility selector=(PortalMapBvhVisibility)get(m,"selector");
        for(Object p:(Vector)get(m,"parts")){
            int room=(Integer)get(p,"room");int[] box={0,0,w,h};if(m.currentRoom>=0)for(int k=0;k<4;k++)box[k]=selector.window(room,k);result.put((VertexBuffer)get(p,"vertices"),box);
            eq(((Object[])get(p,"masks")).length,2,"unbounded index cache");
        }return result;
    }
    static long offSum,onSum;static int startOff,startOn,min=3761,max;
    static void sample(PortalMapBvhMesh m,PortalMapMesh parent,Matrix pose,Graphics target,int w,int h,int fx,int fy,int far,boolean start)throws Exception{
        List<Graphics3D.Draw> off=render(m,pose,target,false,w,h,fx,fy,far);float[] camera=Graphics3D.cameraTransform.matrix.clone();int total=m.submittedTriangles,mask=m.roomMask,rooms=m.selectedRooms;
        Graphics3D.history.clear();parent.render(target,pose,w,h,fx,fy,far);sameA(off,capture());eq(parent.roomMask,mask,"BVH OFF changed parent room mask");ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"OFF changed native camera");
        PortalMapVisibility ps=(PortalMapVisibility)get(parent,"selector");PortalMapBvhVisibility bs=(PortalMapBvhVisibility)get(m,"selector");
        if(m.currentRoom>=0)ok(Arrays.equals((int[])get(ps,"windows"),(int[])get(bs,"windows")),"portal windows changed");
        List<Graphics3D.Draw> on=render(m,pose,target,true,w,h,fx,fy,far);ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"ON moved camera");eq(m.roomMask,mask,"BVH changed selected rooms");eq(m.selectedRooms,rooms,"rooms counter");
        int removed=PortalMapBvhOracle.verify(off,on,camera,windows(m,w,h));eq(m.submittedTriangles,total-removed,"triangle counter");ok(m.draws<=off.size(),"per-leaf calls appeared");
        min=Math.min(min,m.submittedTriangles);max=Math.max(max,m.submittedTriangles);offSum+=total;onSum+=m.submittedTriangles;if(start){startOff=total;startOn=m.submittedTriangles;}
        int indices=TriangleStripArray.created,masks=m.maskUploads;render(m,pose,target,true,w,h,fx,fy,far);ok(m.bvhCached,"stationary BVH not cached");eq(m.bvhTests,0,"stationary BVH retraversed");eq(TriangleStripArray.created,indices,"stationary indices rebuilt");eq(m.maskUploads,masks,"stationary masks rebuilt");cases++;
    }
    static void trees()throws Exception{
        Random random=new Random(9134);
        for(int n=1;n<=4096;n=n<100?n+1:n<2000?n*2+1:4097)for(boolean flat:new boolean[]{false,true}){
            int[] bounds=new int[n*6];for(int i=0;i<n;i++)for(int k=0;k<3;k++){int v=flat?0:random.nextInt(15000000)-7500000;bounds[i*6+k]=v;bounds[i*6+3+k]=v+(flat?0:random.nextInt(30000));}
            int[] original=bounds.clone();PortalMapBvh tree=new PortalMapBvh(bounds);boolean[] seen=new boolean[n];int nodes=validateNode(0,(int[])get(tree,"bounds"),(int[])get(tree,"right"),(int[])get(tree,"start"),(int[])get(tree,"count"),(int[])get(tree,"order"),bounds,seen);
            eq(nodes,(Integer)get(tree,"nodes"),"unreachable nodes");for(boolean present:seen)ok(present,"missing leaf face");ok(Arrays.equals(original,bounds),"source bounds reordered");
            PortalMapBvhFrustum invalid=new PortalMapBvhFrustum();invalid.set(new float[16],-1,-256,240,320,212,212,0,0,240,320);tree.select(invalid);eq(tree.visible,n,"invalid frustum rejected geometry");
        }
    }
    static int validateNode(int node,int[] bounds,int[] right,int[] start,int[] count,int[] order,int[] source,boolean[] seen) {
        if(count[node]>0) {
            ok(count[node]<=8,"oversized leaf");
            for(int f=start[node];f<start[node]+count[node];f++) {
                int face=order[f];ok(!seen[face],"duplicate leaf face");seen[face]=true;
                for(int k=0;k<3;k++)ok(bounds[node*6+k]<=source[face*6+k] && bounds[node*6+3+k]>=source[face*6+3+k],"face crosses leaf AABB");
            }
            return node+1;
        }
        int next=validateNode(node+1,bounds,right,start,count,order,source,seen);ok(next==right[node],"invalid preorder child");
        for(int child:new int[]{node+1,right[node]})for(int k=0;k<3;k++)ok(bounds[node*6+k]<=bounds[child*6+k] && bounds[node*6+3+k]>=bounds[child*6+3+k],"child crosses parent AABB");
        return validateNode(right[node],bounds,right,start,count,order,source,seen);
    }
    static void ui()throws Exception{
        Class<?> cls=Class.forName("code.Rendering.PortalMapBvhPreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructor(PortalMapBvhPreview.class);ctor.setAccessible(true);Object viewer=ctor.newInstance(new PortalMapBvhPreview());call(viewer,"load",new Class[0],new Object[0]);set(viewer,"running",true);set(viewer,"visible",true);
        PortalMapBvhMesh mesh=(PortalMapBvhMesh)get(viewer,"nativeRoom");Graphics screen=Image.createImage(240,340).getGraphics();paint(viewer,screen);ok(get(viewer,"error")==null,"initial paint");int x=(Integer)get(viewer,"x"),y=(Integer)get(viewer,"y"),z=(Integer)get(viewer,"z"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface");int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        for(int k:new int[]{48,48,51,49,51,49}){
            set(viewer,"move",1);set(viewer,"turn",1);set(viewer,"strafe",1);key(viewer,k);ok((Boolean)get(viewer,"resetRequested"),"switch warmup");eq((Integer)get(viewer,"move"),0,"walk not stopped");eq((Integer)get(viewer,"turn"),0,"turn not stopped");eq((Integer)get(viewer,"strafe"),0,"strafe not stopped");
            paint(viewer,screen);ok(get(viewer,"error")==null,"paint after switch");ok(mesh.isPortalsEnabled(),"BVH key disabled portals");boolean bvh=(Boolean)get(viewer,"bvh");ok(mesh.isBvhEnabled()==bvh,"mode mismatch");ok(((String)get(viewer,"heading")).startsWith(bvh?"BVH ON":"BVH OFF"),"HUD wrong mode");
            eq(((PortalMapBvhStats)get(viewer,"stats")).frames,0,"switch frame measured");eq((Integer)get(viewer,"x"),x,"x moved");eq((Integer)get(viewer,"y"),y,"y moved");eq((Integer)get(viewer,"z"),z,"z moved");eq((Integer)get(viewer,"yaw"),yaw,"yaw moved");eq((Integer)get(viewer,"far"),40000,"far changed");eq((Integer)get(viewer,"configuredFar"),40000,"config changed");eq(mesh.getNearPlane(),128,"near changed");ok(surface==get(viewer,"surface"),"surface recreated");
        }
        int indices=TriangleStripArray.created;for(int k:new int[]{51,49,51,49}){key(viewer,k);paint(viewer,screen);}eq(TriangleStripArray.created,indices,"warm fixed ON/OFF rebuilt indices");eq(VertexArray.created,va,"UI geometry rebuilt");eq(VertexArray.sets,sets,"UI geometry uploaded");eq(Graphics3D.uploads,uploads,"UI texture uploaded");
        for(int expected:new int[]{30000,20000,40000}){
            set(viewer,"move",1);set(viewer,"turn",1);set(viewer,"strafe",1);key(viewer,42);ok((Boolean)get(viewer,"resetRequested"),"far switch warmup");
            eq((Integer)get(viewer,"move"),0,"far walk");eq((Integer)get(viewer,"turn"),0,"far turn");eq((Integer)get(viewer,"strafe"),0,"far strafe");
            paint(viewer,screen);eq((Integer)get(viewer,"far"),expected,"far cycle");eq((Integer)get(viewer,"x"),x,"far x");eq((Integer)get(viewer,"y"),y,"far y");eq((Integer)get(viewer,"z"),z,"far z");eq((Integer)get(viewer,"yaw"),yaw,"far yaw");ok(surface==get(viewer,"surface"),"far surface");ok(mesh.isBvhEnabled() && mesh.isPortalsEnabled(),"far changed BVH/portals");ok(((String)get(viewer,"heading")).contains("F"+expected),"far HUD");eq(DirectX7.drDist,40000,"far changed global distance");
        }
        set(viewer,"x",x+1000);key(viewer,53);eq((Integer)get(viewer,"x"),x,"home");call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden rendering");call(viewer,"showNotify",new Class[0],new Object[0]);ok((Boolean)get(viewer,"resetRequested"),"resume warmup");call(viewer,"release",new Class[0],new Object[0]);
        PortalMapBvhStats stats=new PortalMapBvhStats();stats.reset(0);ok(!stats.record(500,500,520,521,522),"warmup");ok(!stats.record(1000,1000,1020,1021,1022),"anchor");stats.record(2000,2000,2020,2021,2022);ok(stats.record(4000,4000,4020,4021,4022),"window");eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"native walltime");
    }
    public static void main(String[] args)throws Exception {
        trees();Mesh[] source=load();ok(Image.resourceReads.contains("/portal-map-bvh/map-selo.png"),"requested atlas not loaded");ok(!Image.resourceReads.contains("/portal-map/maps/kordon.png"),"original atlas loaded instead");
        PortalMapBvhMesh m=new PortalMapBvhMesh(source,2,true);m.setNearPlane(128);PortalMapMesh parent=new PortalMapMesh(source,2,true);parent.setNearPlane(128);Graphics target=Image.createImage(240,340).getGraphics();Matrix pose=new Matrix();int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-76338,-1033,-102034},{-75600,0,-70000},{-46000,0,-57000},{-27000,0,-96000},{-110000,0,-120000},{1000000,0,1000000}};
        for(int[] p:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15){pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(p[0],p[1],p[2]);sample(m,parent,pose,target,240,340,225,225,40000,p==points[0]&&pitch==0&&yaw==0);}
        // Doorway centres, one-unit eye shifts, high pitch, odd/wide viewports and far changes.
        PortalMapBvhVisibility selector=(PortalMapBvhVisibility)get(m,"selector");
        for(double[] portal:selector.vertices)for(int delta:new int[]{-1,0,1}){
            int x=0,y=0,z=0;for(int i=0;i<portal.length;i+=3){x+=(int)portal[i];y+=(int)portal[i+1];z+=(int)portal[i+2];}int n=portal.length/3;
            pose.setRotX(delta*75);pose.rotY((cases*37)%360);pose.setPosition(x/n+delta,y/n,z/n-delta);sample(m,parent,pose,target,321,241,170,170,delta==0?20000:30000,false);
        }
        pose.setRotY(0);pose.setPosition(-76338,-1033,-102034);
        render(m,pose,target,true,240,340,225,225,40000);int fixedIndices=TriangleStripArray.created;
        for(boolean bvh:new boolean[]{false,true,false,true})render(m,pose,target,bvh,240,340,225,225,40000);
        eq(TriangleStripArray.created,fixedIndices,"fixed ON/OFF cache roundtrip rebuilt masks");
        render(m,pose,target,true,240,340,225,225,30000);ok(!m.bvhCached,"far omitted from cache key");
        render(m,pose,target,true,240,340,225,225,30000);ok(m.bvhCached,"identical far not cached");
        m.setNearPlane(256);render(m,pose,target,true,240,340,225,225,30000);ok(!m.bvhCached,"near omitted from cache key");m.setNearPlane(128);
        render(m,pose,target,true,241,341,225,225,30000);ok(!m.bvhCached,"viewport omitted from cache key");
        render(m,pose,target,true,241,341,226,225,30000);ok(!m.bvhCached,"focal omitted from cache key");
        pose.m00-=1;render(m,pose,target,true,241,341,226,225,30000);ok(!m.bvhCached,"camera16 omitted from cache key");
        eq(VertexArray.created,va,"hot vertex allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");ok(onSum<offSum,"fixture BVH reduced no aggregate triangles");ok(!m.memoryFallback,"unexpected memory fallback");
        for(boolean bvh:new boolean[]{false,true})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            System.setProperty(flag,"true");boolean failed=false;try{render(m,pose,target,bvh,240,340,225,225,40000);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak "+flag);
        }
        m.dispose();parent.dispose();
        // First partial-mask allocation fails; whole original selected rooms survive.
        m=new PortalMapBvhMesh(source,2,true);m.setNearPlane(128);pose.setRotY(0);pose.setPosition(-76338,-1033,-102034);System.setProperty("canvas.indices.fail","true");try{render(m,pose,target,true,240,340,225,225,40000);}finally{System.clearProperty("canvas.indices.fail");}
        ok(m.memoryFallback,"OOM fallback not exercised");eq(m.submittedTriangles,1384,"OOM lost room geometry");int indices=TriangleStripArray.created;
        for(boolean bvh:new boolean[]{false,true}){render(m,pose,target,bvh,240,340,225,225,40000);eq(m.submittedTriangles,1384,"OOM roundtrip geometry");eq(TriangleStripArray.created,indices,"OOM retried index build");}m.dispose();ui();
        System.out.println("PASS PortalMapBvh: "+checks+" checks / "+cases+" poses; independent double triangle+portal-window clipping; cold vertices/textures; bounded index cache; native failures/OOM/UI.");
        System.out.println("SUBMISSIONS start OFF="+startOff+" ON="+startOn+"; sweep OFF="+offSum+" ON="+onSum+"; min="+min+" max="+max+". NOT phone FPS / not a rasterizer.");
    }
}
