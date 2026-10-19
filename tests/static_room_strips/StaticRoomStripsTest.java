package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Independent strip expansion and exact indexed packet comparison.
 * Skips only triangles with repeated INDEX identities, not geometric heuristics.
 * Source expanded-face strips have distinct indices even at coincident points.
 */
public final class StaticRoomStripsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void key(Object object)throws Exception{Method m=object.getClass().getDeclaredMethod("keyPressed",Integer.TYPE);m.setAccessible(true);m.invoke(object,48);}
    static void paint(Object object,Graphics target)throws Exception{Method m=object.getClass().getDeclaredMethod("paint",Graphics.class);m.setAccessible(true);m.invoke(object,target);check(get(object,"error")==null,"viewer paint: "+get(object,"error"));}
    static Object viewer(boolean strips)throws Exception {
        Class<?> app=strips?StaticRoomStripsPreview.class:StaticRoomPreview.class;
        Class<?> type=Class.forName(app.getName()+"$Viewer");Constructor<?> ctor=type.getDeclaredConstructor(app);ctor.setAccessible(true);
        Object result=ctor.newInstance(app.newInstance());Method load=type.getDeclaredMethod("load");load.setAccessible(true);load.invoke(result);return result;
    }
    static int[] triangles(Graphics3D.Draw draw) {
        int[] out=new int[draw.indices.length*3];int dest=0,offset=0;
        for(int length:draw.lengths) {
            check(length>=3,"short strip");
            for(int i=2;i<length;i++) {
                int a=draw.indices[offset+i-2],b=draw.indices[offset+i-1],c=draw.indices[offset+i];
                if((i&1)!=0){int swap=a;a=b;b=swap;}
                if(a==b || b==c || c==a)continue;
                out[dest++]=a;out[dest++]=b;out[dest++]=c;
            }
            offset+=length;
        }
        check(offset==draw.indices.length,"strip lengths do not cover indices");return Arrays.copyOf(out,dest);
    }
    static int[] state(Graphics3D.Draw d) {
        return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,
            d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight};
    }
    static void same(List<Graphics3D.Draw> original,List<Graphics3D.Draw> candidate,boolean joined) {
        check(original.size()==candidate.size(),"draw count/order changed");
        for(int i=0;i<original.size();i++) {
            Graphics3D.Draw a=original.get(i),b=candidate.get(i);
            check(Arrays.equals(a.positions,b.positions) && Arrays.equals(a.coords,b.coords) && Arrays.equals(a.colors,b.colors),"VB attributes changed");
            check(a.scale==b.scale && a.uvScale==b.uvScale && Arrays.equals(a.bias,b.bias),"packing scale/bias changed");
            check(Arrays.equals(a.projection,b.projection) && Arrays.equals(state(a),state(b)) && Arrays.equals(a.source.image.pixels,b.source.image.pixels),"camera/material/texture changed");
            check(Arrays.equals(triangles(a),triangles(b)),"non-degenerate indexed primitives/order/winding changed");
            check(b.lengths.length==(joined?1:a.lengths.length),"joined strip count wrong");
            if(!joined)check(Arrays.equals(a.indices,b.indices) && Arrays.equals(a.lengths,b.lengths),"original fallback indices changed");
        }
    }
    static List<Graphics3D.Draw> snapshot(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void draw(StaticRoomStripsMesh mesh,Matrix pose,Graphics target,boolean cull,boolean joined) {
        mesh.setCullingEnabled(cull);mesh.setStitchingEnabled(joined);Graphics3D.history.clear();
        mesh.render(target,pose,240,340,225,225,68500);check(Graphics3D.target==null,"target leaked");
        check(mesh.prepareMs>=0 && mesh.bindClearMs>=0 && mesh.drawMs>=0 && mesh.releaseMs>=0,"negative stage timing");
    }
    static void draw(StaticRoomMesh mesh,Matrix pose,Graphics target,boolean cull) {
        mesh.setCullingEnabled(cull);Graphics3D.history.clear();mesh.render(target,pose,240,340,225,225,68500);
    }
    static void synthetic() {
        Graphics target=Image.createImage(240,340).getGraphics();Matrix pose=new Matrix();
        for(int mode:new int[]{0,7,9,13})for(int pattern=0;pattern<32;pattern++) {
            Texture texture=StaticRoomTest.texture(mode);RenderObject[] faces=new RenderObject[1+pattern%17];
            for(int i=0;i<faces.length;i++) {
                if(((pattern>>>(i%5))&1)==0)faces[i]=StaticRoomTest.face();
                else faces[i]=new Polygon4V(new Vertex(0,0,-1200),new Vertex(1200,0,-1200),new Vertex(1200,600,-1800),new Vertex(0,600,-1800),
                    (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            }
            code.Rendering.Meshes.Mesh source=new code.Rendering.Meshes.Mesh(new Vertex[]{},faces,new MultyTexture(texture));
            StaticRoomMesh old=new StaticRoomMesh(source,2,true);StaticRoomStripsMesh next=new StaticRoomStripsMesh(source,2,true);
            draw(old,pose,target,false);List<Graphics3D.Draw> expected=snapshot();
            draw(next,pose,target,false,true);same(expected,snapshot(),true);
            check(next.triangles==old.triangles && next.submittedStrips==1,"mixed 3/4 count");
            draw(next,pose,target,false,false);same(expected,snapshot(),false);next.dispose();old.dispose();
        }
        System.out.println("PASS 128 mixed triangle/quad winding-parity patterns across modes0/7/9/13; original non-degenerate primitive sequence exact");
    }
    static void real()throws Exception {
        Object loader=viewer(false);code.Rendering.Meshes.Mesh source=(code.Rendering.Meshes.Mesh)get(loader,"room");
        MultyTexture materials=(MultyTexture)get(loader,"materials");Texture river=materials.textures[4];
        RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
        river.rImg=frames[0];StaticRoomStripsMesh next=new StaticRoomStripsMesh(source,2,true);StaticRoomMesh old=new StaticRoomMesh(source,2,true);
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,textures=Graphics3D.uploads,samples=0;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];
            draw(old,pose,target,true);List<Graphics3D.Draw> expected=snapshot();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            draw(next,pose,target,true,true);same(expected,snapshot(),true);
            check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera transform changed");
            check(next.submittedTriangles==old.submittedTriangles && next.draws==old.draws && next.submittedStrips==next.draws && next.uploadedVertices==10032,"culling/triangles/call/vertex count changed");
            int indices=TriangleStripArray.created,joins=next.stitchUploads,masks=next.maskUploads;
            draw(next,pose,target,true,false);same(expected,snapshot(),false);
            draw(next,pose,target,true,true);same(expected,snapshot(),true);
            check(indices==TriangleStripArray.created && joins==next.stitchUploads && masks==next.maskUploads,"warm A/B/A rebuilt indices");
            check(VertexArray.created==arrays && VertexArray.sets==sets && Graphics3D.uploads==textures,"runtime vertex/texture upload");samples++;
        }
        river.rImg=frames[0];draw(old,pose,target,false);List<Graphics3D.Draw> full=snapshot();
        int beforeStrips=0,beforeIndices=0,afterIndices=0;for(Graphics3D.Draw d:full){beforeStrips+=d.lengths.length;beforeIndices+=d.indices.length;}
        draw(next,pose,target,false,true);same(full,snapshot(),true);for(Graphics3D.Draw d:Graphics3D.history)afterIndices+=d.indices.length;
        check(beforeStrips==2641 && next.submittedStrips==3 && next.submittedTriangles==4750,"full map topology counts");
        int unique=0;
        for(Graphics3D.Draw d:full) {
            Set<String> keys=new HashSet<String>();
            for(int v=0;v<d.positionCapacity;v++) {
                int[] attributes={d.positions[v*3],d.positions[v*3+1],d.positions[v*3+2],d.coords[v*2],d.coords[v*2+1],
                    d.colors[v*4],d.colors[v*4+1],d.colors[v*4+2],d.colors[v*4+3]};
                keys.add(Arrays.toString(attributes));
            }
            unique+=keys.size();
        }
        check(unique==9149,"dedup feasibility input changed");
        System.out.println("DEDUP_FEASIBILITY uploaded=10032 exactUnique="+unique+" unusedPotential=883; no vertex dedup applied in this build");
        System.out.println("STRIPS_RESULT poses="+samples+" fullOriginalStrips="+beforeStrips+" fullJoinedStrips="+next.submittedStrips+" fullOriginalIndices="+beforeIndices+" fullJoinedIndices="+afterIndices+" sourceTriangles=4750 sourceVertices=10032");
        Field parts=StaticRoomStripsMesh.class.getDeclaredField("parts");parts.setAccessible(true);
        for(Object part:(Vector)parts.get(next))check(((Object[])get(part,"masks")).length==2,"unbounded mask cache");
        next.dispose();old.dispose();
        river.rImg=frames[0];oom(source,target);
        ((StaticRoomMesh)get(loader,"nativeRoom")).dispose();((DirectX7)get(loader,"legacy")).destroy();
        System.out.println("PASS 864 real poses/river frames vs pinned original cull: exact buffers/camera/ordered visible primitives/state, <=3 API calls, warm A/B/A zero native index/vertex/texture allocation/uploads");
    }
    static void oom(code.Rendering.Meshes.Mesh source,Graphics target) {
        Matrix pose=new Matrix();pose.setPosition(-77706,-1209,-103802);
        StaticRoomStripsMesh mesh=new StaticRoomStripsMesh(source,2,true);
        draw(mesh,pose,target,false,false);List<Graphics3D.Draw> full=snapshot();
        TriangleStripArray.failAt=TriangleStripArray.created+2;
        try{draw(mesh,pose,target,false,true);}finally{TriangleStripArray.failAt=0;}
        check(mesh.isStitchingFallbackActive() && !mesh.isCullingFallbackActive(),"join OOM did not select original native strips");same(full,snapshot(),false);
        int indices=TriangleStripArray.created;draw(mesh,pose,target,false,true);check(indices==TriangleStripArray.created,"join OOM retried allocation");mesh.dispose();
        mesh=new StaticRoomStripsMesh(source,2,true);System.setProperty("canvas.indices.fail","true");
        try{draw(mesh,pose,target,true,true);}finally{System.clearProperty("canvas.indices.fail");}
        check(mesh.isCullingFallbackActive() && mesh.isStitchingFallbackActive(),"partial index OOM missing full original fallback");same(full,snapshot(),false);
        indices=TriangleStripArray.created;draw(mesh,pose,target,true,true);check(indices==TriangleStripArray.created,"mask OOM retried allocation");
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;
            try{draw(mesh,pose,target,false,false);}catch(RuntimeException ex){failed=true;}catch(OutOfMemoryError ex){failed=true;}finally{System.clearProperty(flag);}
            check(failed && Graphics3D.target==null,"native failure/target release "+flag);
        }
        mesh.dispose();System.out.println("PASS joined-buffer OOM after first successful packet -> all original native strips; partial-mask OOM -> whole native; persistent no-retry/cache bound; failure target release");
    }
    static void coldViewer()throws Exception {
        Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);((Hashtable)table.get(null)).clear();
        Image.resourceReads.clear();Image.missingResources.clear();Object viewer=viewer(true);
        check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"new viewer missing texture resources");
        Graphics target=Image.createImage(240,340).getGraphics();StaticRoomStripsMesh mesh=(StaticRoomStripsMesh)get(viewer,"nativeRoom");
        paint(viewer,target);int triangles=mesh.submittedTriangles;Object surface=get(viewer,"surface");
        check(mesh.submittedStrips==mesh.draws,"viewer A not joined");key(viewer);paint(viewer,target);
        check(mesh.submittedTriangles==triangles && mesh.submittedStrips>mesh.draws && get(viewer,"surface")==surface,"viewer B changed workload or target");
        key(viewer);paint(viewer,target);check(mesh.submittedTriangles==triangles && mesh.submittedStrips==mesh.draws,"viewer A not restored");mesh.dispose();
        System.out.println("PASS cold new Viewer A/B/A uses identical cached geometry/culling/Image target and all 6 textures; toggles only native strip layout");
    }
    public static void main(String[] args)throws Exception{synthetic();real();coldViewer();System.out.println("NOT a raster/pixel, actual GPU draw count, handset heap or FPS benchmark; additional degenerate primitives may cost time");}
}
