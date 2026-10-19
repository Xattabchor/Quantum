package code.Rendering;

import code.Gameplay.Map.Room;
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
public final class StaticRoomChunksTest {
    static long checks;static int cases,water,baseCalls,smallCalls;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Mesh load()throws Exception {
        Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        String text=StringTools.getStringFromResource("/level1.txt"),begin="[WTEX_BEGIN]",end="[WTEX_END]";int a=text.indexOf(begin),b=text.indexOf(end);
        String[] lines=text.substring(a+begin.length(),b).trim().split("\\r?\\n");MultyTexture textures=new MultyTexture(lines);
        GameIni config=new GameIni(text.substring(0,a)+text.substring(b+end.length()),true);DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);
        float scale=config.getFloat("WORLD_SCALE",1);Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,textures);
        StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomChunksTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomChunksTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
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
    static final class Group {int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};}
    static long floor(long a,long b){return Math.floorDiv(a,b);}
    static void oracle(List<Graphics3D.Draw> full,List<Graphics3D.Draw> actual,int size,int ox,int oz,Matrix pose,int focal) {
        StaticRoomVisibility frustum=new StaticRoomVisibility();
        // Match public camera contract, but derive bounds/grouping independently
        // from captured packed inputs, not renderer Part/grid metadata.
        float[] m=new float[16];float fp=Matrix.FP;
        m[0]=(pose.m00>>2)/(fp/4);m[1]=(pose.m01>>2)/(fp/4);m[2]=(pose.m02>>2)/(fp/4);m[3]=pose.m03;
        m[4]=(pose.m10>>2)/(fp/4);m[5]=(pose.m11>>2)/(fp/4);m[6]=(pose.m12>>2)/(fp/4);m[7]=pose.m13;
        m[8]=(pose.m20>>2)/(fp/4);m[9]=(pose.m21>>2)/(fp/4);m[10]=(pose.m22>>2)/(fp/4);m[11]=pose.m23;
        m[3]+=m[2]*focal;m[7]+=m[6]*focal;m[11]+=m[10]*focal;m[15]=1;
        float[] p=full.get(0).projection;frustum.set(m,p[0],p[5],p[10],p[11]);
        int out=0;
        for(Graphics3D.Draw d:full){
            int n=d.lengths.length,off=0;int[][] boxes=new int[n][6];String[] keys=new String[n];Map<String,Group> groups=new HashMap<String,Group>();
            for(int f=0;f<n;f++){
                int[] b=boxes[f];Arrays.fill(b,0,3,Integer.MAX_VALUE);Arrays.fill(b,3,6,Integer.MIN_VALUE);
                for(int j=0;j<d.lengths[f];j++){int v=d.indices[off+j];for(int k=0;k<3;k++){int value=(int)(d.positions[v*3+k]*d.scale+d.bias[k]);b[k]=Math.min(b[k],value);b[k+3]=Math.max(b[k+3],value);}}off+=d.lengths[f];
                String key=floor((long)b[0]+b[3]-2L*ox,2L*size)+":"+floor((long)b[2]+b[5]-2L*oz,2L*size);keys[f]=key;
                Group g=groups.get(key);if(g==null){g=new Group();groups.put(key,g);}for(int k=0;k<3;k++){g.b[k]=Math.min(g.b[k],b[k]);g.b[k+3]=Math.max(g.b[k+3],b[k+3]);}
            }
            ArrayList<Integer> ids=new ArrayList<Integer>(),lens=new ArrayList<Integer>();off=0;
            for(int f=0;f<n;f++){
                boolean visible=!frustum.outside(groups.get(keys[f]).b,0);
                if(!frustum.outside(boxes[f],0))ok(visible,"grid dropped a potentially visible whole face");
                if(visible){lens.add(d.lengths[f]);for(int j=0;j<d.lengths[f];j++)ids.add(d.indices[off+j]);}off+=d.lengths[f];
            }
            if(ids.isEmpty())continue;
            ok(out<actual.size(),"lost parent draw");Graphics3D.Draw a=actual.get(out++);
            ok(Arrays.equals(d.positions,a.positions) && Arrays.equals(d.coords,a.coords) && Arrays.equals(d.colors,a.colors),"chunk switch changed/uploaded attributes");
            ok(Arrays.equals(state(d),state(a)) && Arrays.equals(d.projection,a.projection),"native state/projection changed");
            ok(Arrays.equals(d.source.image.pixels,a.source.image.pixels),"river/texture changed");
            eq(ids.size(),a.indices.length,"selected corners");eq(lens.size(),a.lengths.length,"selected faces");
            for(int j=0;j<ids.size();j++)eq(ids.get(j),a.indices[j],"face/index/order differs from oracle");
            for(int j=0;j<lens.size();j++)eq(lens.get(j),a.lengths[j],"strip lengths/order");
        }eq(out,actual.size(),"extra parent/cell calls");
    }
    static List<Graphics3D.Draw> draw(StaticRoomChunksMesh r,Matrix p){Graphics3D.history.clear();r.render(Image.createImage(240,320).getGraphics(),p,240,320,212,212,60000);return capture();}
    static void grids(){
        int[] b={-5,0,-5,5,1,5, 60000,0,0,60000,2,0, -60001,-3,-60001,-60000,0,-60000, -100000,0,0,100000,5,0};
        for(int size:new int[]{60000,40000}){
            StaticRoomChunkGrid g=new StaticRoomChunkGrid(b,0,0,size);
            eq(g.faceCells.length,4,"fixture face count");
            for(int f=0;f<4;f++)for(int k=0;k<3;k++){int c=g.faceCells[f]*6;ok(g.bounds[c+k]<=b[f*6+k] && g.bounds[c+k+3]>=b[f*6+k+3],"whole crossing face not enclosed");}
            StaticRoomChunkGrid shifted=new StaticRoomChunkGrid(new int[]{-10,0,-10,10,1,10, 60000,0,0,60000,1,0},0,0,size);
            ok(shifted.faceCells[0]!=shifted.faceCells[1],"literal world-unit cell separation");
        }
        StaticRoomChunkGrid neg=new StaticRoomChunkGrid(new int[]{-1,0,0,-1,0,0, 0,0,0,0,0,0},0,0,60000);ok(neg.faceCells[0]!=neg.faceCells[1],"negative floor incorrectly truncates");
        boolean bad=false;try{new StaticRoomChunkGrid(b,0,0,0);}catch(IllegalArgumentException e){bad=true;}ok(bad,"invalid size");
    }
    public static void main(String[] args)throws Exception {
        grids();Mesh mesh=load();StaticRoomChunksMesh room=new StaticRoomChunksMesh(mesh,2,true);room.setNearPlane(128);room.setCullingEnabled(true);
        StaticRoomBvhMesh reference=new StaticRoomBvhMesh(mesh,2,true);reference.setNearPlane(128);reference.setBvhEnabled(false);
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();
        int[][] points={{-77706,-1209,-103802},{-60000,1000,-75000},{-100000,5000,-125000}};
        for(int frame=0;frame<4;frame++){
            for(Texture t:mesh.getTexture().textures)if(t.animMIP!=null)t.rImg=t.animMIP[frame].rImg;
            for(int[] point:points)for(int pitch:new int[]{-20,0,20})for(int yaw=0;yaw<360;yaw+=15){
                pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);
                reference.setCullingEnabled(false);Graphics3D.history.clear();reference.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> full=capture();
                for(int size:new int[]{60000,40000}){room.setChunkSize(size);oracle(full,draw(room,pose),size,mesh.minX(),mesh.minZ(),pose,212);cases++;}
                reference.setCullingEnabled(true);Graphics3D.history.clear();reference.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> legacy=capture();room.setChunkSize(0);sameA(legacy,draw(room,pose));
            }
        }
        pose.setRotY(0);pose.setPosition(points[0][0],points[0][1],points[0][2]);
        for(int size:new int[]{0,60000,40000}){room.setChunkSize(size);draw(room,pose);System.out.println("CONTROL size="+size+" T="+room.submittedTriangles+" DC="+room.draws+" VB="+room.submittedBufferVertices+" V="+room.visibilityTests+" CUT="+room.culledCells);}
        for(int size:new int[]{60000,40000}){room.setChunkSize(size);draw(room,pose);}
        int arrays=VertexArray.created,images=room.textureUploads,indices=TriangleStripArray.created;
        for(int i=0;i<20;i++){room.setChunkSize(i%2==0?60000:40000);List<Graphics3D.Draw> steady=draw(room,pose);sameA(steady,draw(room,pose));sameA(steady,draw(room,pose));}
        eq(arrays,VertexArray.created,"geometry upload on warm size switch");eq(images,room.textureUploads,"texture upload on switch");eq(indices,TriangleStripArray.created,"2-mask warmed switch rebuilt indices");
        boolean bad=false;try{room.setChunkSize(123);}catch(IllegalArgumentException e){bad=true;}ok(bad,"bad selector size");
        for(int size:new int[]{0,60000,40000})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            room.setChunkSize(size);System.setProperty(flag,"true");boolean failed=false;
            try{draw(room,pose);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"native target leaked on "+flag);eq(room.getChunkSize(),size,"failure silently changed size");
        }
        StaticRoomChunksMesh fresh=new StaticRoomChunksMesh(mesh,2,true);fresh.setNearPlane(128);fresh.setCullingEnabled(true);
        int binds=Graphics3D.binds;TriangleStripArray.failAt=TriangleStripArray.created+1;boolean oom=false;
        try{draw(fresh,pose);}catch(OutOfMemoryError e){oom=true;}finally{TriangleStripArray.failAt=0;}
        ok(oom && Graphics3D.binds==binds && Graphics3D.target==null,"index OOM bound partial frame / silently fell back");eq(fresh.getChunkSize(),60000,"OOM changed size");draw(fresh,pose);fresh.dispose();
        room.dispose();reference.dispose();
        Class<?> c=Class.forName("code.Rendering.StaticRoomChunksPreview$Viewer");Constructor<?> ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);Object viewer=ctor.newInstance(new StaticRoomChunksPreview());
        call(viewer,"load",new Class[0],new Object[0]);eq(((Integer)get(viewer,"chunkSize")).intValue(),60000,"default chunk");
        int home=((Integer)get(viewer,"x")).intValue();set(viewer,"turn",Integer.valueOf(1));key(viewer,48);eq(((Integer)get(viewer,"chunkSize")).intValue(),40000,"0 selects40k");eq(((Integer)get(viewer,"turn")).intValue(),0,"switch freezes movement");eq(((Integer)get(viewer,"x")).intValue(),home,"switch moved camera");
        key(viewer,48);eq(((Integer)get(viewer,"chunkSize")).intValue(),60000,"0 selects60k");key(viewer,49);eq(((Integer)get(viewer,"chunkSize")).intValue(),0,"1 legacy control");key(viewer,51);eq(((Integer)get(viewer,"chunkSize")).intValue(),40000,"3 direct40k");
        set(viewer,"x",Integer.valueOf(home+1000));key(viewer,53);eq(((Integer)get(viewer,"x")).intValue(),home,"home key");
        set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);paint(viewer,Image.createImage(240,320).getGraphics());ok(get(viewer,"error")==null,"viewer paint failure");
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,Image.createImage(240,320).getGraphics());eq(Graphics3D.history.size(),0,"hidden Canvas rendered");call(viewer,"release",new Class[0],new Object[0]);
        StaticRoomChunksStats stats=new StaticRoomChunksStats();stats.reset(0);ok(!stats.record(1000,1000,1020,1021,1022),"anchor");ok(!stats.record(2000,2000,2020,2021,2022),"short window");ok(!stats.record(1500,1500,1520,1521,1522),"backward clock published");eq(stats.frames,0,"backward clock not reset");
        System.out.println("PASS StaticRoomChunks: "+checks+" checks; "+cases+" chunk cases +864 immutable GRID controls. Not rasterization or handset FPS.");
    }
}
