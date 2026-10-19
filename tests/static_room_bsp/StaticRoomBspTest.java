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
public final class StaticRoomBspTest {
    static long checks;static int cases,water,baseCalls,smallCalls;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Mesh load()throws Exception{return StaticRoomBspExport.load();}
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
    static List<Graphics3D.Draw> draw(StaticRoomBspMesh mesh,int mode,Graphics g,Matrix pose){mesh.setMode(mode);Graphics3D.history.clear();mesh.render(g,pose,240,320,212,212,60000);ok(Graphics3D.target==null,"target leak");ok(Graphics3D.boundDepth==(mode!=1),"bind depth does not match mode");return capture();}
    static void sameB(List<Graphics3D.Draw> b,List<Graphics3D.Draw> c){
        eq(b.size(),c.size(),"B/C calls");
        for(int i=0;i<b.size();i++){
            Graphics3D.Draw x=b.get(i),y=c.get(i);int[] sx=state(x),sy=state(y);
            ok(!x.depthTest&&!x.depthWrite&&y.depthTest,"B/C depth state");ok(y.depthWrite==(y.blend!=CompositingMode.MODULATE),"river depth write policy");sx[6]=sy[6]=sx[7]=sy[7]=0;
            ok(Arrays.equals(sx,sy),"B/C non-depth state");ok(Arrays.equals(x.positions,y.positions)&&Arrays.equals(x.coords,y.coords)&&Arrays.equals(x.colors,y.colors),"B/C attributes");
            ok(Arrays.equals(x.indices,y.indices)&&Arrays.equals(x.lengths,y.lengths),"B/C painter order");ok(Arrays.equals(x.bias,y.bias)&&x.scale==y.scale&&x.uvScale==y.uvScale,"B/C packing");ok(Arrays.equals(x.projection,y.projection)&&Arrays.equals(x.source.image.pixels,y.source.image.pixels),"B/C camera/river");
            ok(x.vertexBuffer==y.vertexBuffer,"mode changed resident buffer");for(int n:x.lengths)eq(n,3,"triangle strip winding must not alternate across triangles");
        }
    }
    static void walk(int n,float[] plane,int[] children,int[] ranges,float[] camera,List<Integer> order){
        if(n<0)return;int p=n*4;boolean front=plane[p]*camera[3]+plane[p+1]*camera[7]+plane[p+2]*camera[11]+plane[p+3]>=0;
        walk(children[n*2+(front?1:0)],plane,children,ranges,camera,order);for(int s=ranges[n*2];s<ranges[n*2]+ranges[n*2+1];s++)order.add(s);walk(children[n*2+(front?0:1)],plane,children,ranges,camera,order);
    }
    static void verifyOrder(StaticRoomBspMesh mesh,List<Graphics3D.Draw> draws)throws Exception{
        List<Integer> expected=new ArrayList<Integer>();walk(0,(float[])get(mesh,"planes"),(int[])get(mesh,"children"),(int[])get(mesh,"spanRanges"),Graphics3D.cameraTransform.matrix,expected);
        int[] spans=(int[])get(mesh,"spans");Object[] pages=(Object[])get(mesh,"pages");int d=0,offset=0,vertices=0;
        for(int s:expected){int page=spans[s*3],first=spans[s*3+1],count=spans[s*3+2];for(int i=0;i<count;i++){
            Graphics3D.Draw draw=draws.get(d);ok(draw.vertexBuffer==get(pages[page],"vertices"),"painter globally regrouped pages");eq(draw.indices[offset++],first+i,"painter index order");vertices++;
            if(offset==draw.indices.length){d++;offset=0;}
        }}
        eq(d,draws.size(),"extra painter calls");eq(offset,0,"unconsumed indices");eq(vertices,28773,"offline full coverage");
    }
    static void badAssets(Mesh source)throws Exception {
        java.io.InputStream in=StaticRoomBspMesh.class.getResourceAsStream("/maps/kordos/kordos.bsp");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))>=0)out.write(chunk,0,n);in.close();byte[] good=out.toByteArray();
        java.nio.file.Path path=java.nio.file.Paths.get(System.getProperty("bsp.badAssetDir"),"maps/kordos/kordos.bsp");java.nio.file.Files.createDirectories(path.getParent());
        int[] offsets={0,4,8,12,16,20,24,28,32,48,56,64,32+5827*56};
        int[] values={0,2,4751,8193,1025,32769,100001,121,0x7fc00000,0,-1,8000004,-1};
        for(int i=0;i<offsets.length+3;i++){
            byte[] bad=good.clone();
            if(i<offsets.length)java.nio.ByteBuffer.wrap(bad).putInt(offsets[i],values[i]);
            else if(i==offsets.length)bad=Arrays.copyOf(bad,6);
            else if(i==offsets.length+1)bad=Arrays.copyOf(bad,bad.length-1);
            else bad=Arrays.copyOf(bad,bad.length+1);
            java.nio.file.Files.write(path,bad);boolean failed=false;
            try{new StaticRoomBspMesh(source,2,true);}catch(java.io.IOException e){failed=true;}
            ok(failed && Graphics3D.target==null,"malformed asset was not rejected without fallback: "+i);
        }
        java.nio.file.Files.write(path,good);StaticRoomBspMesh valid=new StaticRoomBspMesh(source,2,true);valid.dispose();java.nio.file.Files.delete(path);
    }
    public static void main(String[] args)throws Exception {
        Mesh source=load();StaticRoomBvhMesh baseline=new StaticRoomBvhMesh(source,2,true);baseline.setNearPlane(128);baseline.setCullingEnabled(true);baseline.setBvhEnabled(false);
        StaticRoomBspMesh mesh=new StaticRoomBspMesh(source,2,true);eq(mesh.totalTriangles,9591,"split triangles");eq(mesh.uploadedVertices,28773,"cold vertices");
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{-60000,1000,-75000},{-100000,5000,-125000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=30)for(int frame=0;frame<4;frame++){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);Texture river=source.getTexture().textures[2];river.rImg=river.animMIP[frame].rImg;
            Graphics3D.history.clear();baseline.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> frozen=capture();sameA(frozen,draw(mesh,0,target,pose));float[] aCamera=Graphics3D.cameraTransform.matrix.clone();
            List<Graphics3D.Draw> b=draw(mesh,1,target,pose);ok(Arrays.equals(aCamera,Graphics3D.cameraTransform.matrix),"A/B camera");
            int t=mesh.submittedTriangles,v=mesh.submittedBufferVertices,dc=mesh.draws,indices=TriangleStripArray.created;
            List<Graphics3D.Draw> c=draw(mesh,2,target,pose);sameB(b,c);eq(mesh.submittedTriangles,t,"B/C T");eq(mesh.submittedBufferVertices,v,"B/C VB");eq(mesh.draws,dc,"B/C DC");
            eq(TriangleStripArray.created,indices,"B/C rebuilt indices");sameB(draw(mesh,1,target,pose),c);eq(TriangleStripArray.created,indices,"stationary rebuilt indices");cases++;
        }
        mesh.setCullingEnabled(false);
        for(int yaw=0;yaw<360;yaw+=60){pose.setRotY(yaw);pose.setPosition(-77706,-1209,-103802);List<Graphics3D.Draw> full=draw(mesh,1,target,pose);verifyOrder(mesh,full);eq(mesh.submittedTriangles,9591,"full T");}
        mesh.setCullingEnabled(true);eq(VertexArray.created,va,"hot vertex allocation");eq(VertexArray.sets,sets,"hot vertex upload");eq(Graphics3D.uploads,uploads,"hot texture upload");
        pose.setRotY(0);pose.setPosition(-77706,-1209,-103802);draw(mesh,0,target,pose);System.out.println("Desktop API counters ONLY: A T="+mesh.submittedTriangles+" DC="+mesh.draws+" VB="+mesh.submittedBufferVertices);
        draw(mesh,1,target,pose);System.out.println("Desktop API counters ONLY: B/C T="+mesh.submittedTriangles+" DC="+mesh.draws+" VB="+mesh.submittedBufferVertices);
        for(int mode=0;mode<3;mode++)for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            mesh.setMode(mode);System.setProperty(flag,"true");boolean failed=false;try{mesh.render(target,pose,240,320,212,212,60000);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed && Graphics3D.target==null,"transaction failure "+flag);
        }
        Class<?> cls=Class.forName("code.Rendering.StaticRoomBspPreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);Object viewer=ctor.newInstance(new StaticRoomBspPreview());call(viewer,"load",new Class[0],new Object[0]);set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);
        Graphics screen=Image.createImage(240,340).getGraphics();paint(viewer,screen);ok(get(viewer,"error")==null,"A UI error");int x=(Integer)get(viewer,"x"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface");
        set(viewer,"turn",1);set(viewer,"move",1);key(viewer,51);eq((Integer)get(viewer,"turn"),0,"switch turn");eq((Integer)get(viewer,"move"),0,"switch walk");ok((Boolean)get(viewer,"resetRequested"),"warmup reset");paint(viewer,screen);StaticRoomBspMesh room=(StaticRoomBspMesh)get(viewer,"nativeRoom");eq(room.getMode(),1,"B UI did not reach renderer");
        key(viewer,42);paint(viewer,screen);eq(room.getMode(),2,"C UI");key(viewer,42);paint(viewer,screen);eq(room.getMode(),1,"star B/C");key(viewer,48);paint(viewer,screen);eq(room.getMode(),0,"0 A");key(viewer,48);paint(viewer,screen);eq(room.getMode(),1,"0 B");key(viewer,49);paint(viewer,screen);eq(room.getMode(),0,"1 A");
        eq((Integer)get(viewer,"x"),x,"switch moved x");eq((Integer)get(viewer,"yaw"),yaw,"switch moved yaw");ok(surface==get(viewer,"surface"),"switch changed target");ok(get(viewer,"error")==null,"B/C UI error");
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");call(viewer,"release",new Class[0],new Object[0]);
        StaticRoomBspStats stats=new StaticRoomBspStats();stats.reset(0);ok(!stats.record(500,500,520,521,522),"warmup");ok(!stats.record(1000,1000,1020,1021,1022),"anchor");ok(!stats.record(2000,2000,2020,2021,2022),"short window");ok(stats.record(4000,4000,4020,4021,4022),"window");eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"N");eq(stats.meanInterval,1500,"I");stats.reset(0);stats.record(1000,1000,1020,1021,1022);stats.record(2000,2000,2020,2021,2022);ok(!stats.record(1500,1500,1520,1521,1522),"backwards clock");eq(stats.frames,0,"clock reset");
        mesh.dispose();baseline.dispose();boolean failed=false;try{mesh.setMode(1);}catch(IllegalStateException e){failed=true;}ok(failed,"disposed accepted mode");
        badAssets(source);
        System.out.println("PASS StaticRoomBsp: "+checks+" checks; "+cases+" camera/river cases. Contract doubles, NOT native rasterization or phone speed.");
    }
}
