package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** API/packet contract test, NOT a handset raster or FPS measurement. */
public final class StaticRoomAlphaTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void render(StaticRoomAlphaMesh room,Matrix pose,Graphics target,boolean alpha) {
        room.setAlphaTestEnabled(alpha);Graphics3D.history.clear();room.render(target,pose,240,340,225,225,60000);
        check(Graphics3D.target==null,"native target leaked");
    }
    static void exactExceptThreshold(List<Graphics3D.Draw> on,List<Graphics3D.Draw> off) {
        check(on.size()==off.size(),"alpha toggle changed draw count");
        for(int i=0;i<on.size();i++) {
            Graphics3D.Draw a=on.get(i),b=off.get(i);
            check(b.threshold==0,"OFF threshold not zero");
            check(Arrays.equals(a.positions,b.positions) && Arrays.equals(a.coords,b.coords) && Arrays.equals(a.colors,b.colors),"XYZ/UV/light changed");
            check(a.scale==b.scale && a.uvScale==b.uvScale && Arrays.equals(a.bias,b.bias),"packing changed");
            check(Arrays.equals(a.indices,b.indices) && Arrays.equals(a.lengths,b.lengths),"strip/index order changed");
            check(Arrays.equals(a.projection,b.projection),"near/far changed");
            check(Arrays.equals(a.source.image.pixels,b.source.image.pixels),"texture RGBA changed");
            int[] x=StaticRoomDepthTest.state(a),y=StaticRoomDepthTest.state(b);
            for(int k=0;k<x.length;k++)if(k!=10)check(x[k]==y[k],"state OTHER than alpha threshold changed");
        }
    }
    static void syntheticAnimation() {
        Graphics target=Image.createImage(240,340).getGraphics();Matrix pose=new Matrix();
        for(int mode:new int[]{0,7,9,13}) {
            Texture cut=StaticRoomTest.texture(mode);
            Texture opaque=new Texture(new RawImage(new int[]{-1,-1,-1,-1},2,2),true,null);opaque.drawmode=(byte)mode;
            RawImage first=cut.rImg;cut.animMIP=new Texture[]{cut,opaque};
            StaticRoomAlphaMesh room=new StaticRoomAlphaMesh(StaticRoomTest.mesh(StaticRoomTest.face(),cut),2,true);
            room.setNearPlane(128);check(room.isAlphaTestEnabled(),"mesh default must match Depth baseline");
            int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
            // Switch frames WHILE OFF, then restore with NO image change.
            for(boolean alpha:new boolean[]{true,false,false,true,false,true})for(RawImage raw:new RawImage[]{first,opaque.rImg,first}) {
                cut.rImg=raw;render(room,pose,target,alpha);
                check(Graphics3D.history.size()==1,"synthetic face disappeared");Graphics3D.Draw d=Graphics3D.history.get(0);
                check(d.threshold==(alpha && raw==first?.5f:0),"animation overwrote OFF or restored stale threshold");
                check(d.depthTest && d.depthWrite==(mode!=7),"depth state changed");
                check(d.blend==(mode==7?CompositingMode.MODULATE:CompositingMode.REPLACE),"blending changed");
            }
            check(arrays==VertexArray.created && sets==VertexArray.sets && uploads==Graphics3D.uploads,"toggle uploaded vertices/textures");
            for(boolean alpha:new boolean[]{false,true})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
                room.setAlphaTestEnabled(alpha);System.setProperty(flag,"true");boolean failed=false;
                try{render(room,pose,target,alpha);}catch(RuntimeException ex){failed=true;}catch(OutOfMemoryError ex){failed=true;}finally{System.clearProperty(flag);}
                check(failed && Graphics3D.target==null,"target leaked on failure");
            }
            room.dispose();boolean rejected=false;try{room.setAlphaTestEnabled(false);}catch(IllegalStateException ex){rejected=true;}check(rejected,"disposed toggle accepted");
        }
        System.out.println("PASS synthetic alpha/opaque animated frames in modes0/7/9/13; OFF persists on frame changes, ON restores current-image threshold; blending/depth unchanged; native failures release target");
    }
    static void sweep()throws Exception {
        Object loader=StaticRoomDepthTest.viewer(false);Mesh source=(Mesh)StaticRoomDepthTest.get(loader,"room");
        Texture river=((MultyTexture)StaticRoomDepthTest.get(loader,"materials")).textures[4];RawImage[] frames=new RawImage[4];
        for(int f=0;f<4;f++)frames[f]=river.animMIP[f].rImg;
        river.rImg=frames[0];StaticRoomAlphaMesh room=new StaticRoomAlphaMesh(source,2,true);StaticRoomDepthMesh baseline=new StaticRoomDepthMesh(source,2,true);
        baseline.setNearPlane(128);room.setNearPlane(128);baseline.setCullingEnabled(true);room.setCullingEnabled(true);
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,samples=0,cutoutDraws=0;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int f=0;f<4;f++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[f];
            Graphics3D.history.clear();baseline.render(target,pose,240,340,225,225,60000);List<Graphics3D.Draw> expected=StaticRoomDepthTest.snapshot();
            render(room,pose,target,true);List<Graphics3D.Draw> on=StaticRoomDepthTest.snapshot();StaticRoomDepthTest.exact(expected,on,true);
            for(Graphics3D.Draw d:on)if(d.threshold>0)cutoutDraws++;
            int indices=TriangleStripArray.created,masks=room.maskUploads;
            render(room,pose,target,false);exactExceptThreshold(on,StaticRoomDepthTest.snapshot());
            render(room,pose,target,true);StaticRoomDepthTest.exact(on,StaticRoomDepthTest.snapshot(),true);
            check(indices==TriangleStripArray.created && masks==room.maskUploads,"alpha toggle allocated mask/indices");
            check(arrays==VertexArray.created && sets==VertexArray.sets && uploads==Graphics3D.uploads,"hot toggle allocated/uploaded geometry or texture");
            check(room.draws<=3 && room.uploadedVertices==10032 && room.submittedTriangles==baseline.submittedTriangles,"geometry/work counters changed");samples++;
        }
        check(cutoutDraws>0,"real-model alpha test was already zero in every compared draw");
        room.dispose();baseline.dispose();
        river.rImg=frames[0];room=new StaticRoomAlphaMesh(source,2,true);room.setNearPlane(128);room.setCullingEnabled(true);
        pose.setIdentity();pose.setPosition(-77706,-1209,-103802);System.setProperty("canvas.indices.fail","true");
        try{render(room,pose,target,false);}finally{System.clearProperty("canvas.indices.fail");}
        check(room.isCullingFallbackActive() && room.submittedTriangles==4750,"mask OOM failed to keep native whole room");
        List<Graphics3D.Draw> off=StaticRoomDepthTest.snapshot();render(room,pose,target,true);exactExceptThreshold(StaticRoomDepthTest.snapshot(),off);room.dispose();
        ((StaticRoomMesh)StaticRoomDepthTest.get(loader,"nativeRoom")).dispose();((DirectX7)StaticRoomDepthTest.get(loader,"legacy")).destroy();
        System.out.println("ALPHA_RESULT samples="+samples+" cutoutDraws="+cutoutDraws+"; ON exactly Depth near128/far60000; OFF changes ONLY threshold; no hot vertex/texture/mask uploads; max3 native calls");
    }
    static void coldViewer()throws Exception {
        Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);((Hashtable)table.get(null)).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class<?> type=Class.forName("code.Rendering.StaticRoomAlphaPreview$Viewer");Constructor<?> ctor=type.getDeclaredConstructor(StaticRoomAlphaPreview.class);ctor.setAccessible(true);
        Object viewer=ctor.newInstance(new StaticRoomAlphaPreview());Method load=type.getDeclaredMethod("load");load.setAccessible(true);load.invoke(viewer);
        check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"cold image dependency missing");
        StaticRoomAlphaMesh room=(StaticRoomAlphaMesh)StaticRoomDepthTest.get(viewer,"nativeRoom");
        check(!room.isAlphaTestEnabled() && !((Boolean)StaticRoomDepthTest.get(viewer,"alphaTest")),"viewer did not default OFF");
        Method paint=type.getDeclaredMethod("paint",Graphics.class);paint.setAccessible(true);Graphics screen=Image.createImage(240,340).getGraphics();
        for(int key:new int[]{-1,48,53,48,53}) {
            if(key>=0)StaticRoomDepthTest.callKey(viewer,key);paint.invoke(viewer,screen);
            check(StaticRoomDepthTest.get(viewer,"error")==null && Graphics3D.target==null,"cold viewer failed");
            check(room.getNearPlane()==128 && ((Integer)StaticRoomDepthTest.get(viewer,"far"))==60000,"key changed near/far");
            boolean enabled=((Boolean)StaticRoomDepthTest.get(viewer,"alphaTest"));check(enabled==room.isAlphaTestEnabled(),"key did not apply alpha mode");
            check(((String)StaticRoomDepthTest.get(viewer,"heading")).startsWith(enabled?"B alpha ON":"A alpha OFF"),"HUD mode wrong");
        }
        check(!room.isAlphaTestEnabled(),"OFF/ON/OFF roundtrip failed");room.dispose();
        System.out.println("PASS cold Viewer: default A alpha OFF; key0 B ON/A OFF; key5 no-op; fixed near128/far60000; all6 original textures; no CPU framebuffer");
    }
    public static void main(String[] args)throws Exception{syntheticAnimation();sweep();coldViewer();System.out.println("NOT an E65 FPS benchmark or isolated GPU instruction-cost proof: filled cutout holes also change occlusion/overdraw");}
}
