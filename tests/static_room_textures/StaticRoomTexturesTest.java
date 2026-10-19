package code.Rendering;

import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.GameIni;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Compare the edited room to ORIGINAL ZIP bytes, not to the edited resources. */
public final class StaticRoomTexturesTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static byte[] read(ZipFile zip,String name)throws Exception {
        InputStream in=zip.getInputStream(zip.getEntry(name));ByteArrayOutputStream out=new ByteArrayOutputStream();
        try{byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);}finally{in.close();}return out.toByteArray();
    }
    static Mesh original(ZipFile zip)throws Exception {
        String text=new String(read(zip,"level1.txt"),"ISO-8859-1"),begin="[WTEX_BEGIN]",end="[WTEX_END]";
        String[] lines=text.substring(text.indexOf(begin)+begin.length(),text.indexOf(end)).trim().split("\\r?\\n");
        MultyTexture materials=new MultyTexture(lines);check(materials.textures.length==5,"reference lost old slots");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(read(zip,"maps/kordos/kordos.3d")));
        check(in.readInt()==1,"reference meshes");
        Method method=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);method.setAccessible(true);
        Mesh mesh=(Mesh)method.invoke(null,"original ZIP",in,6f,6f,6f,materials);check(in.read()==-1,"reference trailing bytes");
        StaticRoomTexturesPreview.loadLightmap(new Mesh[]{mesh},new ByteArrayInputStream(read(zip,"lightmap/kordod.vla")));return mesh;
    }
    static Object coldViewer()throws Exception {
        Field table=code.utils.Asset.class.getDeclaredField("table");table.setAccessible(true);((Hashtable)table.get(null)).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class<?> type=Class.forName("code.Rendering.StaticRoomTexturesPreview$Viewer");Constructor<?> ctor=type.getDeclaredConstructor(StaticRoomTexturesPreview.class);ctor.setAccessible(true);
        Object viewer=ctor.newInstance(new StaticRoomTexturesPreview());Method load=type.getDeclaredMethod("load");load.setAccessible(true);load.invoke(viewer);
        check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"cold PNG/river resource failure");
        MultyTexture materials=(MultyTexture)StaticRoomDepthTest.get(viewer,"materials");check(materials.textures.length==3,"not three slots");
        Texture river=materials.textures[2];check(river.drawmode==7 && river.animation_speed==6 && river.animMIP.length==4,"river parameters changed");
        check(materials.textures[0].addsz==-20000 && materials.textures[1].addsz==0,"forest/retained atlas options changed");
        StaticRoomBvhMesh room=(StaticRoomBvhMesh)StaticRoomDepthTest.get(viewer,"nativeRoom");check(!room.isBvhEnabled(),"default not GRID");
        Method paint=type.getDeclaredMethod("paint",Graphics.class);paint.setAccessible(true);Graphics target=Image.createImage(240,340).getGraphics();
        for(int key:new int[]{-1,48,48}) {
            if(key>=0)StaticRoomDepthTest.callKey(viewer,key);paint.invoke(viewer,target);
            check(StaticRoomDepthTest.get(viewer,"error")==null && Graphics3D.target==null,"cold Viewer failed");
            check(room.getNearPlane()==128 && ((Integer)StaticRoomDepthTest.get(viewer,"far"))==60000,"projection changed");
            check(((String)StaticRoomDepthTest.get(viewer,"heading")).startsWith(room.isBvhEnabled()?"A BVH Tex3":"B GRID Tex3"),"mode HUD wrong");
        }
        check(!room.isBvhEnabled(),"mode rollback failed");return viewer;
    }
    static void permutationFailures(Mesh mesh,byte[] valid)throws Exception {
        RenderObject[] before=mesh.getPolygons().clone();
        byte[] duplicate=valid.clone();duplicate[6]=duplicate[7]=0;
        byte[] negative=valid.clone();negative[4]=negative[5]=(byte)255;
        byte[] trailing=Arrays.copyOf(valid,valid.length+1);
        for(byte[] bad:new byte[][]{new byte[4],Arrays.copyOf(valid,valid.length-1),duplicate,negative,trailing}) {
            boolean failed=false;try{StaticRoomTexturesPreview.restoreDrawOrder(mesh,new ByteArrayInputStream(bad));}catch(IOException ex){failed=true;}
            check(failed && Arrays.equals(before,mesh.getPolygons()),"bad permutation partly reordered faces");
        }
        check(StaticRoomTexturesPreview.restoreDrawOrder(mesh,new ByteArrayInputStream(valid))==2641,"valid permutation failed");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(valid));in.readInt();int index=0;
        for(int i=0;i<before.length;i++){index+=in.readShort();check(mesh.getPolygons()[i]==before[index],"wrong reference permutation");}
        System.out.println("PASS transactional draw-order permutation: after lightmap only, no UV/light edits; duplicate/out-of-range/truncated/trailing/count mismatch rejected");
    }
    public static void main(String[] args)throws Exception {
        Object viewer=coldViewer();StaticRoomBvhMesh edited=(StaticRoomBvhMesh)StaticRoomDepthTest.get(viewer,"nativeRoom");
        MultyTexture materials=(MultyTexture)StaticRoomDepthTest.get(viewer,"materials");ZipFile zip=new ZipFile(args[0]);
        Mesh source;try{source=original(zip);}finally{zip.close();}
        Texture river=source.getTexture().textures[4],newRiver=materials.textures[2];RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=newRiver.animMIP[i].rImg;
        river.rImg=frames[0];StaticRoomBvhMesh baseline=new StaticRoomBvhMesh(source,2,true);baseline.setNearPlane(128);baseline.setCullingEnabled(true);
        check(baseline.parts()==3 && edited.parts()==3,"native packet count changed");
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,340).getGraphics();int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,samples=0,water=0;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(boolean bvh:new boolean[]{false,true})for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int f=0;f<4;f++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[f];newRiver.rImg=frames[f];baseline.setBvhEnabled(bvh);edited.setBvhEnabled(bvh);
            Graphics3D.history.clear();baseline.render(target,pose,240,340,225,225,60000);List<Graphics3D.Draw> expected=StaticRoomDepthTest.snapshot();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            Graphics3D.history.clear();edited.render(target,pose,240,340,225,225,60000);List<Graphics3D.Draw> actual=StaticRoomDepthTest.snapshot();
            StaticRoomDepthTest.exact(expected,actual,true);check(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera changed");
            check(edited.submittedTriangles==baseline.submittedTriangles && edited.draws==baseline.draws && edited.uploadedVertices==10032,"work counters changed");
            for(Graphics3D.Draw d:actual)if(d.blend==CompositingMode.MODULATE){check(!d.depthWrite && d.depthTest,"river depth state changed");water++;}
            int indices=TriangleStripArray.created,masks=edited.maskUploads;edited.render(target,pose,240,340,225,225,60000);
            check(indices==TriangleStripArray.created && masks==edited.maskUploads,"fixed mask rebuilt");check(arrays==VertexArray.created && sets==VertexArray.sets && uploads==Graphics3D.uploads,"hot upload");samples++;
        }
        check(water>0,"no river draw checked");
        InputStream order=StaticRoomTexturesTest.class.getResourceAsStream("/maps/kordos/kordos.draworder");ByteArrayOutputStream orderBytes=new ByteArrayOutputStream();int n;while((n=order.read())>=0)orderBytes.write(n);order.close();permutationFailures(source,orderBytes.toByteArray());
        baseline.dispose();edited.dispose();
        System.out.println("TEX3_RESULT samples="+samples+" riverDraws="+water+" slots5->3; original native XYZ/UV/RGBA/primitive order/indices/alpha/blending/depth/camera EXACT in GRID and BVH, all4 river frames; max3 calls, 10032 corners, no hot uploads");
        System.out.println("No E65 FPS gain claimed: original PNG cache and native atlas batching were already shared");
    }
}
