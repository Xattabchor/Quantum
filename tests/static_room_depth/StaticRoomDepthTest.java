package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Exact native contracts + independent homogeneous clipping + illustrative
 * finite-depth quantization. NOT an emulation of the Nokia E65 depth format.
 */
public final class StaticRoomDepthTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void callKey(Object viewer,int key)throws Exception{Method m=viewer.getClass().getDeclaredMethod("keyPressed",Integer.TYPE);m.setAccessible(true);m.invoke(viewer,key);}
    static Object viewer(boolean depth)throws Exception {
        Class<?> app=depth?StaticRoomDepthPreview.class:StaticRoomPreview.class;
        Class<?> type=Class.forName(app.getName()+"$Viewer");Constructor<?> ctor=type.getDeclaredConstructor(app);ctor.setAccessible(true);
        Object result=ctor.newInstance(app.newInstance());Method load=type.getDeclaredMethod("load");load.setAccessible(true);load.invoke(result);return result;
    }
    static List<Graphics3D.Draw> snapshot(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void render(StaticRoomDepthMesh room,Matrix pose,Graphics target,int near,int far,boolean cull) {
        room.setNearPlane(near);room.setCullingEnabled(cull);Graphics3D.history.clear();room.render(target,pose,240,340,225,225,far);
        check(Graphics3D.target==null,"bound target leaked");
    }
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight};}
    static void exact(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b,boolean sameProjection) {
        check(a.size()==b.size(),"draw count changed");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            check(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),"geometry/UV/baked light changed");
            check(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"packing changed");
            check(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"primitive/strip order changed");
            check(Arrays.equals(state(x),state(y)) && Arrays.equals(x.source.image.pixels,y.source.image.pixels),"texture/render state changed");
            for(int p=0;p<16;p++)if(sameProjection || (p!=10 && p!=11))check(x.projection[p]==y.projection[p],"unexpected projection change");
        }
    }
    static long depth16(float[] p,double distance){return Math.round(((-p[10]+p[11]/distance)+1)*.5*65535);}
    static void precisionAndFailures() {
        Mesh source=StaticRoomTest.mesh(StaticRoomTest.face(),StaticRoomTest.texture(9));StaticRoomDepthMesh room=new StaticRoomDepthMesh(source,2,true);
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        for(int far:new int[]{60000,68500}) {
            render(room,pose,target,1,far,false);float[] old=Graphics3D.camera.projection.matrix.clone();
            render(room,pose,target,128,far,false);float[] better=Graphics3D.camera.projection.matrix.clone();
            check(depth16(old,10000)==depth16(old,10100),"illustrative old depth bin no longer reproduces tie");
            check(depth16(better,10000)<depth16(better,10100),"near128 did not separate example surfaces");
            for(int near:new int[]{1,128}) {
                render(room,pose,target,near,far,false);float[] p=Graphics3D.camera.projection.matrix;
                check(Math.abs(p[11]/(p[10]-1)-near)<.001,"near clip parameter wrong");
                check(Math.abs(p[11]/(p[10]+1)-far)<far*.005,"far clip parameter wrong");
                double close=near*.5,front=near*2;
                check(-p[10]+p[11]/close < -1 && -p[10]+p[11]/front > -1,"near clipping not modeled honestly");
            }
        }
        for(int invalid:new int[]{0,-1,4097}){boolean rejected=false;try{room.setNearPlane(invalid);}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"invalid near accepted");}
        room.setNearPlane(128);boolean rejected=false;try{room.render(target,pose,240,340,225,225,128);}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"near>=far accepted");
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;try{render(room,pose,target,128,60000,false);}catch(RuntimeException ex){failed=true;}catch(OutOfMemoryError ex){failed=true;}finally{System.clearProperty(flag);}
            check(failed && Graphics3D.target==null,"failed native target not released");
        }
        room.dispose();System.out.println("PASS illustrative 16-bit depth model: near1 ties depths10000/10100 at BOTH far60000 and68500; near128 separates them; explicit near clipping/validation/native target failure release. Actual E65 depth bits UNKNOWN");
    }
    static void sweep()throws Exception {
        Object loader=viewer(false);Mesh source=(Mesh)get(loader,"room");Texture river=((MultyTexture)get(loader,"materials")).textures[4];
        RawImage[] frames=new RawImage[4];for(int f=0;f<4;f++)frames[f]=river.animMIP[f].rImg;
        river.rImg=frames[0];StaticRoomDepthMesh room=new StaticRoomDepthMesh(source,2,true);StaticRoomMesh old=new StaticRoomMesh(source,2,true);
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,samples=0;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(int far:new int[]{60000,68500})for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];
            old.setCullingEnabled(true);Graphics3D.history.clear();old.render(target,pose,240,340,225,225,far);
            List<Graphics3D.Draw> expected=snapshot();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            render(room,pose,target,1,far,true);exact(expected,snapshot(),true);check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera changed");
            render(room,pose,target,1,far,false);List<Graphics3D.Draw> fullNear1=snapshot();
            for(int near:new int[]{1,128}) {
                render(room,pose,target,near,far,false);List<Graphics3D.Draw> whole=snapshot();exact(fullNear1,whole,near==1);
                render(room,pose,target,near,far,true);
                int removed=StaticRoomCullTest.verify(whole,snapshot(),Graphics3D.cameraTransform.matrix);
                check(room.submittedTriangles==4750-removed && room.draws<=3 && room.uploadedVertices==10032,"work counters changed");
                int indices=TriangleStripArray.created,masks=room.maskUploads;render(room,pose,target,near,far,true);
                check(indices==TriangleStripArray.created && masks==room.maskUploads,"fixed mask reallocated indices");samples++;
            }
            check(arrays==VertexArray.created && sets==VertexArray.sets && uploads==Graphics3D.uploads,"runtime vertex/texture allocation or upload");
        }
        river.rImg=frames[0];room.dispose();old.dispose();
        // Near change must not affect OOM safety, even when every partial mask fails.
        room=new StaticRoomDepthMesh(source,2,true);System.setProperty("canvas.indices.fail","true");
        try{render(room,new Matrix(),target,128,60000,true);}finally{System.clearProperty("canvas.indices.fail");}
        // Use the known start pose to ensure a partial selection needs allocation.
        if(!room.isCullingFallbackActive()) {pose.setIdentity();pose.setPosition(-77706,-1209,-103802);System.setProperty("canvas.indices.fail","true");try{render(room,pose,target,128,60000,true);}finally{System.clearProperty("canvas.indices.fail");}}
        check(room.isCullingFallbackActive() && room.submittedTriangles==4750,"depth diagnostic mask OOM did not keep whole native room");room.dispose();
        ((StaticRoomMesh)get(loader,"nativeRoom")).dispose();((DirectX7)get(loader,"legacy")).destroy();
        System.out.println("DEPTH_RESULT samples="+samples+" near=[1,128] far=[60000,68500]; near1 exact old-cull packets, both near domains independently double-clipped, same full XYZ/UV/RGBA/strips/state, stationary allocations/uploads=0");
    }
    static void coldViewer()throws Exception {
        Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);((Hashtable)table.get(null)).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Object viewer=viewer(true);check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"cold texture dependency missing");
        Graphics screen=Image.createImage(240,340).getGraphics();Method paint=viewer.getClass().getDeclaredMethod("paint",Graphics.class);paint.setAccessible(true);
        StaticRoomDepthMesh room=(StaticRoomDepthMesh)get(viewer,"nativeRoom");
        check(((Integer)get(viewer,"configuredFar"))==68500 && ((Integer)get(viewer,"far"))==60000,"authorized default or original far lost");
        for(int key:new int[]{-1,48,53,48,53}) {
            if(key>=0)callKey(viewer,key);paint.invoke(viewer,screen);check(get(viewer,"error")==null && Graphics3D.target==null,"cold near/far viewer failed");
        }
        check(((Integer)get(viewer,"far"))==60000 && room.getNearPlane()==128,"independent keys did not restore defaults");room.dispose();
        System.out.println("PASS cold Viewer defaults near128/far60000; key0 only near, key5 only distance, four combinations and restore, all 6 images and original source far68500 retained");
    }
    public static void main(String[] args)throws Exception{precisionAndFailures();sweep();coldViewer();System.out.println("NOT confirmed E65 bug reproduction, handset fix, actual depth-bit measurement, raster equivalence or FPS benchmark");}
}
