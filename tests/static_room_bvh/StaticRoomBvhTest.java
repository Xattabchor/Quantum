package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Independent homogeneous clipping validates omissions, not just BVH counts. */
public final class StaticRoomBvhTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void render(StaticRoomBvhMesh room,Matrix pose,Graphics target,boolean cull,boolean bvh) {
        room.setCullingEnabled(cull);room.setBvhEnabled(bvh);Graphics3D.history.clear();room.render(target,pose,240,340,225,225,60000);
        check(Graphics3D.target==null,"target leaked");
    }
    static int validateNode(int node,int[] bounds,int[] right,int[] start,int[] count,int[] order,int[] source,boolean[] seen) {
        if(count[node]>0) {
            check(count[node]<=8,"oversized leaf");
            for(int f=start[node];f<start[node]+count[node];f++) {
                int face=order[f];check(!seen[face],"duplicate leaf face");seen[face]=true;
                for(int k=0;k<3;k++)check(bounds[node*6+k]<=source[face*6+k] && bounds[node*6+3+k]>=source[face*6+3+k],"face crosses leaf AABB");
            }
            return node+1;
        }
        int next=validateNode(node+1,bounds,right,start,count,order,source,seen);check(next==right[node],"invalid preorder child");
        for(int child:new int[]{node+1,right[node]})for(int k=0;k<3;k++)check(bounds[node*6+k]<=bounds[child*6+k] && bounds[node*6+3+k]>=bounds[child*6+3+k],"child crosses parent AABB");
        return validateNode(right[node],bounds,right,start,count,order,source,seen);
    }
    static void trees()throws Exception {
        Random random=new Random(1971);int cases=0;
        for(int n=1;n<=4096;n=n<100?n+1:n<1000?n*2+1:4097)for(boolean coincident:new boolean[]{false,true}) {
            int[] source=new int[n*6];
            for(int f=0;f<n;f++)for(int k=0;k<3;k++){int p=coincident?17:random.nextInt(15000000)-7500000;source[f*6+k]=p;source[f*6+k+3]=p+(coincident?0:random.nextInt(10000));}
            int[] original=source.clone();StaticRoomBvh tree=new StaticRoomBvh(source);boolean[] seen=new boolean[n];
            int nodes=validateNode(0,(int[])StaticRoomDepthTest.get(tree,"bounds"),(int[])StaticRoomDepthTest.get(tree,"right"),(int[])StaticRoomDepthTest.get(tree,"start"),(int[])StaticRoomDepthTest.get(tree,"count"),(int[])StaticRoomDepthTest.get(tree,"order"),source,seen);
            check(nodes==((Integer)StaticRoomDepthTest.get(tree,"nodes")),"unreachable tree node");
            for(boolean present:seen)check(present,"missing leaf face");check(Arrays.equals(original,source),"source bounds reordered");
            StaticRoomVisibility invalid=new StaticRoomVisibility();invalid.set(new float[16],1,1,-1,-256);tree.select(invalid);
            check(tree.visible==n,"invalid camera removed faces");for(byte b:tree.selected)check(b==1,"unselected face on invalid transform");cases++;
        }
        System.out.println("PASS BVH tree invariants: "+cases+" random/coincident cardinalities, full face/child bounds, max8/leaf, source order untouched, invalid camera keeps all");
    }
    static void sweep()throws Exception {
        Object loader=StaticRoomDepthTest.viewer(false);Mesh source=(Mesh)StaticRoomDepthTest.get(loader,"room");
        Texture river=((MultyTexture)StaticRoomDepthTest.get(loader,"materials")).textures[4];RawImage[] frames=new RawImage[4];for(int f=0;f<4;f++)frames[f]=river.animMIP[f].rImg;
        river.rImg=frames[0];StaticRoomBvhMesh room=new StaticRoomBvhMesh(source,2,true);room.setNearPlane(128);
        StaticRoomDepthMesh grid=new StaticRoomDepthMesh(source,2,true);grid.setNearPlane(128);grid.setCullingEnabled(true);
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,samples=0,startBvh=-1,startGrid=-1,cutoutDraws=0,min=4750,max=0;
        long gridSum=0,bvhSum=0,testSum=0;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int f=0;f<4;f++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[f];
            render(room,pose,target,false,true);List<Graphics3D.Draw> whole=StaticRoomDepthTest.snapshot();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            grid.setCullingEnabled(false);Graphics3D.history.clear();grid.render(target,pose,240,340,225,225,60000);
            StaticRoomDepthTest.exact(StaticRoomDepthTest.snapshot(),whole,true);check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"whole camera differs from baseline");
            grid.setCullingEnabled(true);Graphics3D.history.clear();grid.render(target,pose,240,340,225,225,60000);List<Graphics3D.Draw> expected=StaticRoomDepthTest.snapshot();
            render(room,pose,target,true,false);StaticRoomDepthTest.exact(expected,StaticRoomDepthTest.snapshot(),true);check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"grid camera changed");gridSum+=room.submittedTriangles;
            if(point==points[0] && pitch==0 && yaw==0 && f==0)startGrid=room.submittedTriangles;
            render(room,pose,target,true,true);List<Graphics3D.Draw> selected=StaticRoomDepthTest.snapshot();
            check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"BVH camera changed");
            int removed=StaticRoomCullTest.verify(whole,selected,camera);
            for(Graphics3D.Draw d:selected)if(d.threshold>0)cutoutDraws++;
            check(room.submittedTriangles==4750-removed && room.uploadedVertices==10032 && room.draws<=3,"work counters/packet count changed");
            bvhSum+=room.submittedTriangles;testSum+=room.visibilityTests;min=Math.min(min,room.submittedTriangles);max=Math.max(max,room.submittedTriangles);
            if(point==points[0] && pitch==0 && yaw==0 && f==0)startBvh=room.submittedTriangles;
            int indices=TriangleStripArray.created,masks=room.maskUploads;render(room,pose,target,true,true);
            check(indices==TriangleStripArray.created && masks==room.maskUploads,"stationary BVH mask rebuilt");
            check(arrays==VertexArray.created && sets==VertexArray.sets && uploads==Graphics3D.uploads,"hot vertex/texture upload");samples++;
        }
        check(cutoutDraws>0,"alpha test lost on all real cutouts");check(bvhSum<gridSum,"BVH did not reduce aggregate submissions on fixture");
        // Both selection types share only TWO cached native masks per packet.
        for(Object part:(Vector)StaticRoomDepthTest.get(room,"parts"))check(((Object[])StaticRoomDepthTest.get(part,"masks")).length==2,"unbounded mask cache");
        pose.setIdentity();pose.setPosition(-77706,-1209,-103802);
        for(int i=0;i<6;i++)render(room,pose,target,true,(i&1)==0);
        int indices=TriangleStripArray.created,masks=room.maskUploads;
        for(int i=0;i<8;i++)render(room,pose,target,true,(i&1)==0);
        check(indices==TriangleStripArray.created && masks==room.maskUploads,"warm fixed A/B modes rebuilt native indices");
        room.dispose();grid.dispose();
        river.rImg=frames[0];room=new StaticRoomBvhMesh(source,2,true);room.setNearPlane(128);System.setProperty("canvas.indices.fail","true");
        try{render(room,pose,target,true,true);}finally{System.clearProperty("canvas.indices.fail");}
        check(room.isCullingFallbackActive() && room.submittedTriangles==4750,"index OOM lost geometry");
        indices=TriangleStripArray.created;for(boolean bvh:new boolean[]{false,true}){render(room,pose,target,true,bvh);check(room.submittedTriangles==4750 && indices==TriangleStripArray.created,"OOM fallback retried allocation");}room.dispose();
        ((StaticRoomMesh)StaticRoomDepthTest.get(loader,"nativeRoom")).dispose();((DirectX7)StaticRoomDepthTest.get(loader,"legacy")).destroy();
        System.out.println("BVH_RESULT samples="+samples+" gridSum="+gridSum+" bvhSum="+bvhSum+" nodeTestSum="+testSum+" min="+min+" max="+max+" startGrid="+startGrid+" startBvh="+startBvh+" cutoutDraws="+cutoutDraws);
        System.out.println("PASS every omitted triangle independently double-clipped outside frustum; source draw order/XYZ/UV/RGBA/alpha/blending/depth unchanged; original GRID exact; warm fixed A/B no uploads; OOM whole native room");
    }
    static void viewerAndFailures()throws Exception {
        boolean absent=false;try{StaticRoomBvhMesh.class.getMethod("setAlphaTestEnabled",Boolean.TYPE);}catch(NoSuchMethodException ex){absent=true;}check(absent,"alpha-disable method remains");
        Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);((Hashtable)table.get(null)).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class<?> type=Class.forName("code.Rendering.StaticRoomBvhPreview$Viewer");Constructor<?> ctor=type.getDeclaredConstructor(StaticRoomBvhPreview.class);ctor.setAccessible(true);
        Object viewer=ctor.newInstance(new StaticRoomBvhPreview());Method load=type.getDeclaredMethod("load");load.setAccessible(true);load.invoke(viewer);
        check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"cold texture dependency missing");
        StaticRoomBvhMesh room=(StaticRoomBvhMesh)StaticRoomDepthTest.get(viewer,"nativeRoom");check(room.isBvhEnabled(),"default not BVH");
        Method paint=type.getDeclaredMethod("paint",Graphics.class);paint.setAccessible(true);Graphics target=Image.createImage(240,340).getGraphics();
        for(int key:new int[]{-1,48,53,48,53}) {
            if(key>=0)StaticRoomDepthTest.callKey(viewer,key);paint.invoke(viewer,target);
            check(StaticRoomDepthTest.get(viewer,"error")==null && Graphics3D.target==null,"cold Viewer failed");
            check(room.getNearPlane()==128 && ((Integer)StaticRoomDepthTest.get(viewer,"far"))==60000,"near/far edited");
            boolean bvh=((Boolean)StaticRoomDepthTest.get(viewer,"bvh"));check(bvh==room.isBvhEnabled(),"mode flag diverged");
            check(((String)StaticRoomDepthTest.get(viewer,"heading")).startsWith(bvh?"A BVH":"B GRID"),"HUD wrong");
            for(Graphics3D.Draw d:Graphics3D.history)check(d.threshold>=0,"invalid alpha threshold");
        }
        check(room.isBvhEnabled(),"mode roundtrip failed");
        for(boolean bvh:new boolean[]{false,true})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;try{render(room,new Matrix(),target,true,bvh);}catch(RuntimeException ex){failed=true;}catch(OutOfMemoryError ex){failed=true;}finally{System.clearProperty(flag);}
            check(failed && Graphics3D.target==null,"native failure leaked target");
        }
        room.dispose();System.out.println("PASS cold BVH/GRID Viewer, alpha-disable API absent, original textures, fixed near/far/key5 no-op, mode HUD, native failure release");
    }
    public static void main(String[] args)throws Exception{trees();sweep();viewerAndFailures();System.out.println("NOT an occlusion/raster equivalence or E65 FPS benchmark; BVH adds CPU work and cold heap");}
}
