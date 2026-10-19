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
public final class L2DMapTest {
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
        StaticRoomTexturesPreview.loadLightmap(meshes,L2DMapTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],L2DMapTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
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
    static void same(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"parent/call count");
        for(int p=0;p<a.size();p++){
            Graphics3D.Draw x=a.get(p),y=b.get(p);eq(x.positionCapacity,y.positionCapacity,"VBO count");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"source strip order/quad diagonal");
            ok(Arrays.equals(x.projection,y.projection),"projection");
            int[] xs=state(x),ys=state(y);xs[xs.length-1]=ys[ys.length-1]=0;ok(Arrays.equals(xs,ys),"native state differs");
            eq(x.colorComponents,4,"Quantum RGBA");eq(y.colorComponents,3,"stock L2D RGB");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"texture/river pixels");
            for(int v=0;v<x.positionCapacity;v++){
                for(int k=0;k<3;k++)ok(x.positions[v*3+k]*x.scale+x.bias[k]==y.positions[v*3+k]*y.scale+y.bias[k],"world XYZ changed");
                for(int k=0;k<2;k++)ok(x.coords[v*2+k]*x.uvScale==y.coords[v*2+k]*y.uvScale,"UV changed");
                for(int k=0;k<3;k++)eq(x.colors[v*4+k]&255,y.colors[v*3+k]&255,"baked RGB changed");
                eq(x.colors[v*4+3]&255,255,"RGB needs nonopaque alpha");
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Mesh source=load();StaticRoomBvhMesh q=new StaticRoomBvhMesh(source,2,true);q.setNearPlane(128);q.setCullingEnabled(false);com.MapBenchmark l2d=new com.MapBenchmark();eq(l2d.triangles,4750,"all triangles");eq(l2d.vertices,10032,"all expanded vertices");eq(l2d.calls(),3,"native Node calls");
        Graphics g=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();int[][] points={{-77706,-1209,-103802},{-60000,1000,-75000},{-100000,5000,-125000}};
        int arrays=VertexArray.created,sets=VertexArray.sets;
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);Texture river=source.getTexture().textures[2];river.rImg=river.animMIP[frame].rImg;
            Graphics3D.history.clear();q.render(g,pose,240,320,212,212,60000);List<Graphics3D.Draw> a=capture();float[] cam=Graphics3D.cameraTransform.matrix.clone();int nodeCalls=Graphics3D.nodeCalls;
            Graphics3D.history.clear();l2d.render(g,pose,240,320,212,212,frame);same(a,capture());
            ok(Arrays.equals(cam,Graphics3D.cameraTransform.matrix),"camera world transform changed");eq(Graphics3D.nodeCalls-nodeCalls,3,"not real Node submission");ok(Graphics3D.target==null,"target leak");eq(q.submittedTriangles,4750,"unfair culling comparison");cases++;
        }
        eq(arrays,VertexArray.created,"hot geometry allocation");eq(sets,VertexArray.sets,"hot vertex upload");
        Object v=new L2DMapPreview.Viewer(new L2DMapPreview());call(v,"load",new Class[0],new Object[0]);set(v,"visible",Boolean.TRUE);set(v,"running",Boolean.TRUE);
        Graphics screen=Image.createImage(240,340).getGraphics();screen.screenTarget=true;
        eq((Integer)get(v,"mode"),1,"default is not actual L2D");paint(v,screen);ok(get(v,"error")==null,"default L2D paint failed");key(v,49);paint(v,screen);ok(get(v,"error")==null,"Quantum full paint failed");eq((Integer)get(v,"mode"),0,"1 Quantum");
        int x=(Integer)get(v,"x");Object surface=get(v,"surface");key(v,48);paint(v,screen);eq((Integer)get(v,"mode"),1,"toggle");eq((Integer)get(v,"x"),x,"mode moved camera");ok(surface==get(v,"surface"),"mode changed image target");
        key(v,42);System.setProperty("screen.bind.fail","true");try{paint(v,screen);}finally{System.clearProperty("screen.bind.fail");}ok(get(v,"error")!=null,"Canvas failure hidden");key(v,51);paint(v,screen);ok(get(v,"error")==null,"Image recovery");eq((Integer)get(v,"mode"),1,"recovery not B");
        for(String flag:new String[]{"canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){System.setProperty(flag,"true");boolean failed=false;try{l2d.render(g,pose,240,320,212,212,0);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed && Graphics3D.target==null,"L2D target leak on "+flag);}
        call(v,"release",new Class[0],new Object[0]);q.dispose();System.out.println("PASS L2DMap: "+checks+" checks, "+cases+" camera/frame cases; REAL Node-path input comparison, not rasterization or phone FPS.");
    }
}
