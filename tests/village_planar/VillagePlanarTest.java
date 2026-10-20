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
public final class VillagePlanarTest {
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
    static List<Graphics3D.Draw> render(StaticRoomPerspectiveMesh m,Matrix pose,Graphics target,boolean cull){
        m.setCullingEnabled(cull);Graphics3D.history.clear();m.render(target,pose,240,340,225,225,60000);ok(Graphics3D.target==null && Graphics3D.boundDepth,"target/depth");return capture();
    }
    static void stateAndProtected(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b){
        eq(a.size(),b.size(),"call count");
        for(int i=0;i<a.size();i++){
            Graphics3D.Draw x=a.get(i),y=b.get(i);ok(Arrays.equals(state(x),state(y)),"materials/state");ok(Arrays.equals(x.projection,y.projection),"projection");ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"texture/frame");
            if(i!=1){ok(Arrays.equals(x.positions,y.positions)&&Arrays.equals(x.coords,y.coords)&&Arrays.equals(x.colors,y.colors),"protected forest/river attributes");ok(Arrays.equals(x.indices,y.indices)&&Arrays.equals(x.lengths,y.lengths),"protected forest/river order");}
        }
    }
    public static void main(String[] args)throws Exception{
        Class<?> cls=Class.forName("code.Rendering.VillagePlanarPreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructor(VillagePlanarPreview.class);ctor.setAccessible(true);Object viewer=ctor.newInstance(new VillagePlanarPreview());call(viewer,"load",new Class[0],new Object[0]);
        StaticRoomPerspectiveMesh original=(StaticRoomPerspectiveMesh)get(viewer,"originalRoom"),simple=(StaticRoomPerspectiveMesh)get(viewer,"simpleRoom");
        eq(original.triangles,4750,"original triangle count");eq(simple.triangles,4748,"simple triangle count");eq(original.uploadedVertices,10032,"original native corners");eq(simple.uploadedVertices,10028,"simple native corners");eq(original.parts(),3,"original packets");eq(simple.parts(),3,"simple packets");
        MultyTexture materials=(MultyTexture)get(viewer,"materials");Texture river=materials.textures[2];Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{-28998,432,12168},{-31000,2000,11000},{1000000,0,1000000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=30)for(int frame=0;frame<4;frame++){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=river.animMIP[frame].rImg;
            List<Graphics3D.Draw> a=render(original,pose,target,false);float[] camera=Graphics3D.cameraTransform.matrix.clone();List<Graphics3D.Draw> b=render(simple,pose,target,false);stateAndProtected(a,b);ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera");
            eq(original.submittedTriangles,4750,"whole original");eq(simple.submittedTriangles,4748,"whole simple");
            for(StaticRoomPerspectiveMesh mesh:new StaticRoomPerspectiveMesh[]{original,simple}){
                render(mesh,pose,target,true);ok(mesh.draws<=3,"extra calls");int indices=TriangleStripArray.created,masks=mesh.maskUploads;render(mesh,pose,target,true);eq(TriangleStripArray.created,indices,"stationary indices rebuilt");eq(mesh.maskUploads,masks,"stationary mask rebuilt");
            }
            cases++;
        }
        eq(VertexArray.created,va,"hot geometry allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");
        // Real UI only switches pointers to the two cold native meshes.
        set(viewer,"visible",true);set(viewer,"running",true);Graphics screen=Image.createImage(240,340).getGraphics();paint(viewer,screen);ok(get(viewer,"error")==null,"first paint");int x=(Integer)get(viewer,"x"),y=(Integer)get(viewer,"y"),z=(Integer)get(viewer,"z"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface");
        for(int k:new int[]{48,48,51,49,51,49}){
            set(viewer,"move",1);set(viewer,"turn",1);set(viewer,"strafe",1);key(viewer,k);ok((Boolean)get(viewer,"resetRequested"),"switch warmup");eq((Integer)get(viewer,"move"),0,"move not frozen");eq((Integer)get(viewer,"turn"),0,"turn not frozen");eq((Integer)get(viewer,"strafe"),0,"strafe not frozen");paint(viewer,screen);
            ok(get(viewer,"error")==null,"switch paint");boolean selected=(Boolean)get(viewer,"simplified");ok(get(viewer,"nativeRoom")== (selected?simple:original),"wrong native mesh");ok(((String)get(viewer,"heading")).startsWith(selected?"SIMPLE NEUTRAL":"ORIGINAL NEUTRAL"),"wrong HUD");
            eq((Integer)get(viewer,"x"),x,"x moved");eq((Integer)get(viewer,"y"),y,"y moved");eq((Integer)get(viewer,"z"),z,"z moved");eq((Integer)get(viewer,"yaw"),yaw,"yaw moved");ok(surface==get(viewer,"surface"),"surface rebuilt");eq(((StaticRoomPerspectiveStats)get(viewer,"stats")).frames,0,"switch frame measured");eq((Integer)get(viewer,"far"),60000,"far");eq((Integer)get(viewer,"configuredFar"),68500,"source far");
        }
        int warm=TriangleStripArray.created;for(int k:new int[]{51,49,51,49}){key(viewer,k);paint(viewer,screen);}eq(TriangleStripArray.created,warm,"fixed A/B rebuilt indices");
        key(viewer,42);eq((Integer)get(viewer,"x"),-28998,"focus x");eq((Integer)get(viewer,"y"),432,"focus y");eq((Integer)get(viewer,"z"),12168,"focus z");paint(viewer,screen);ok(get(viewer,"error")==null,"focus paint");key(viewer,53);eq((Integer)get(viewer,"x"),x,"home x");eq((Integer)get(viewer,"z"),z,"home z");
        for(StaticRoomPerspectiveMesh mesh:new StaticRoomPerspectiveMesh[]{original,simple})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            System.setProperty(flag,"true");boolean failed=false;try{render(mesh,pose,target,true);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak "+flag);
        }
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");call(viewer,"showNotify",new Class[0],new Object[0]);ok((Boolean)get(viewer,"resetRequested"),"resume warmup");
        eq(VertexArray.created,va,"UI geometry allocation");eq(VertexArray.sets,sets,"UI attribute upload");eq(Graphics3D.uploads,uploads,"UI texture upload");call(viewer,"release",new Class[0],new Object[0]);
        System.out.println("PASS VillagePlanar: "+checks+" checks / "+cases+" poses/river frames. Geometry proof is separate exact Fraction area/UV audit. NOT device FPS or rasterization.");
    }
}
