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
public final class StaticRoomPerspectiveTest {
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
        StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomPerspectiveTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomPerspectiveTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
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
    static List<Graphics3D.Draw> draw(StaticRoomPerspectiveMesh mesh,Graphics target,Matrix pose){Graphics3D.history.clear();mesh.render(target,pose,240,320,212,212,60000);ok(Graphics3D.target==null,"bound target leak");return capture();}
    static void sameExceptHint(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"calls changed");
        for(int i=0;i<a.size();i++){
            Graphics3D.Draw x=a.get(i),y=b.get(i);int[] sx=state(x),sy=state(y);
            ok(x.perspective && !y.perspective,"expected ON/OFF flag not applied");sx[5]=sy[5]=0;ok(Arrays.equals(sx,sy),"non-hint state changed");
            ok(Arrays.equals(x.positions,y.positions)&&Arrays.equals(x.coords,y.coords)&&Arrays.equals(x.colors,y.colors),"vertex attributes changed");
            ok(Arrays.equals(x.indices,y.indices)&&Arrays.equals(x.lengths,y.lengths),"selected geometry/order changed");
            ok(Arrays.equals(x.bias,y.bias)&&x.scale==y.scale&&x.uvScale==y.uvScale,"packing changed");
            ok(Arrays.equals(x.projection,y.projection)&&Arrays.equals(x.source.image.pixels,y.source.image.pixels),"camera/river/texture changed");
        }
    }
    public static void main(String[] args)throws Exception {
        Mesh source=load();StaticRoomBvhMesh original=new StaticRoomBvhMesh(source,2,true);original.setNearPlane(128);original.setCullingEnabled(true);original.setBvhEnabled(false);
        StaticRoomPerspectiveMesh mesh=new StaticRoomPerspectiveMesh(source,2,true);mesh.setNearPlane(128);mesh.setCullingEnabled(true);ok(mesh.isPerspectiveEnabled(),"default not ON");
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();
        int[][] points={{-77706,-1209,-103802},{-60000,1000,-75000},{-100000,5000,-125000}};
        int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);Texture river=source.getTexture().textures[2];river.rImg=river.animMIP[frame].rImg;
            Graphics3D.history.clear();original.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> frozen=capture();
            mesh.setPerspectiveEnabled(true);List<Graphics3D.Draw> on=draw(mesh,target,pose);sameA(frozen,on);
            int t=mesh.submittedTriangles,c=mesh.submittedCorners,vb=mesh.submittedBufferVertices,dc=mesh.draws,tests=mesh.visibilityTests,indices=TriangleStripArray.created;
            float[] camera=Graphics3D.cameraTransform.matrix.clone();mesh.setPerspectiveEnabled(false);List<Graphics3D.Draw> off=draw(mesh,target,pose);sameExceptHint(on,off);
            eq(mesh.submittedTriangles,t,"T");eq(mesh.submittedCorners,c,"S");eq(mesh.submittedBufferVertices,vb,"VB");eq(mesh.draws,dc,"DC");eq(mesh.visibilityTests,tests,"V");eq(TriangleStripArray.created,indices,"mode toggle rebuilt indices");
            ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"view transform changed");mesh.setPerspectiveEnabled(true);sameA(on,draw(mesh,target,pose));cases++;
        }
        eq(VertexArray.created,va,"hot vertex allocation");eq(VertexArray.sets,sets,"hot vertex upload");eq(Graphics3D.uploads,uploads,"hot texture upload");
        int setters=PolygonMode.perspectiveCalls;mesh.setPerspectiveEnabled(true);eq(PolygonMode.perspectiveCalls,setters,"unchanged hint repeatedly dirtied state");
        for(boolean enabled:new boolean[]{true,false})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            mesh.setPerspectiveEnabled(enabled);System.setProperty(flag,"true");boolean failed=false;try{draw(mesh,target,pose);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed&&Graphics3D.target==null,"target leak on "+flag);
        }
        // A material opting out of correction must stay opted out under global ON.
        boolean prior=source.getTexture().textures[0].perspectiveCorrection;source.getTexture().textures[0].perspectiveCorrection=false;
        StaticRoomPerspectiveMesh limited=new StaticRoomPerspectiveMesh(source,2,true);limited.setNearPlane(128);limited.setCullingEnabled(false);limited.setPerspectiveEnabled(false);limited.setPerspectiveEnabled(true);
        List<Graphics3D.Draw> material=draw(limited,target,pose);ok(!material.get(0).perspective,"global ON overrode original material policy");limited.dispose();source.getTexture().textures[0].perspectiveCorrection=prior;
        Object viewer;Class<?> cls=Class.forName("code.Rendering.StaticRoomPerspectivePreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);viewer=ctor.newInstance(new StaticRoomPerspectivePreview());call(viewer,"load",new Class[0],new Object[0]);set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);
        Graphics screen=Image.createImage(240,340).getGraphics();paint(viewer,screen);ok(get(viewer,"error")==null,"ON paint failure");int x=(Integer)get(viewer,"x"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface");
        set(viewer,"turn",Integer.valueOf(1));set(viewer,"move",Integer.valueOf(1));key(viewer,51);eq((Integer)get(viewer,"turn"),0,"switch did not stop turn");eq((Integer)get(viewer,"move"),0,"switch did not stop walk");ok((Boolean)get(viewer,"resetRequested"),"switch did not reset warmup");paint(viewer,screen);ok(!(Boolean)get(viewer,"perspective"),"3 not OFF");StaticRoomPerspectiveMesh room=(StaticRoomPerspectiveMesh)get(viewer,"nativeRoom");ok(!room.isPerspectiveEnabled(),"OFF UI did not reach renderer");
        key(viewer,49);paint(viewer,screen);ok(room.isPerspectiveEnabled(),"1 not ON");key(viewer,48);paint(viewer,screen);ok(!room.isPerspectiveEnabled(),"0 not toggled");eq((Integer)get(viewer,"x"),x,"toggle moved x");eq((Integer)get(viewer,"yaw"),yaw,"toggle moved yaw");ok(surface==get(viewer,"surface"),"toggle changed target");
        call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");call(viewer,"release",new Class[0],new Object[0]);mesh.dispose();original.dispose();
        StaticRoomPerspectiveStats stats=new StaticRoomPerspectiveStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522),"warmup published");eq(stats.frames,0,"warmup counted");
        ok(!stats.record(1000,1000,1020,1021,1022),"anchor published");eq(stats.frames,0,"anchor counted");
        mesh.prepareMs=2;mesh.bindMs=3;mesh.clearMs=4;mesh.drawMs=5;mesh.releaseMs=6;
        ok(!stats.record(2000,2000,2020,2021,2022,mesh),"short window published");ok(stats.record(4000,4000,4020,4021,4022,mesh),"complete window missing");
        eq(stats.fps10,6,"fractional FPS");eq(stats.meanNative,20,"native wall time");eq(stats.meanCopy,1,"copy wall time");eq(stats.meanInterval,1500,"interval");
        eq(stats.meanJ,2,"J");eq(stats.meanB,3,"B");eq(stats.meanK,4,"K");eq(stats.meanR,5,"R");eq(stats.meanE,6,"E");
        stats.reset(0);stats.record(1000,1000,1020,1021,1022);stats.record(2000,2000,2020,2021,2022);
        ok(!stats.record(1500,1500,1520,1521,1522),"backwards clock published");eq(stats.frames,0,"backwards clock not reset");
        System.out.println("PASS StaticRoomPerspective: "+checks+" checks; "+cases+" camera/river cases. ONLY hint changes; doubles cannot establish device speed or actual affine interpolation.");
    }
}
