package code.Rendering;

import code.Math.Matrix;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Stock L2D metro-level contracts over native doubles; NOT raster/FPS. */
public final class L2DMetroTest {
    static long checks;static int cases;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message+" ("+x+" != "+y+")");}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}

    static void sweep()throws Exception {
        com.MetroBenchmark metro=new com.MetroBenchmark();
        eq(metro.calls(),22,"metro meshes/DC");eq(metro.triangles,1640,"metro triangles");eq(metro.vertices,3245,"metro vertices");
        eq(com.MetroBenchmark.EYE_HEIGHT,2005,"eye height");
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();
        // Waypoints from upstream level1 (START, FINISH and ENSNEMIES) ground points; camera adds the 2005 eye offset.
        int[][] points={{475,0,-1773},{9045,-2218,8168},{29601,-4200,18049},{75066,-10725,42713},{114100,-14075,53185}};
        pose.setPosition(475,2005,-1773);metro.render(target,pose,240,320,212,212); // warm: cameraTransform batches the first setCamera call
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=30) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1]+2005,point[2]);
            Graphics3D.history.clear();int nodeCalls=Graphics3D.nodeCalls;
            metro.render(target,pose,240,320,212,212);List<Graphics3D.Draw> draws=capture();float[] cam=Graphics3D.cameraTransform.matrix.clone();
            eq(draws.size(),22,"draws per frame");eq(Graphics3D.nodeCalls-nodeCalls,22,"not real Node submission");
            ok(Graphics3D.target==null,"target leak");
            for(int i=0;i<draws.size();i++) {
                Graphics3D.Draw d=draws.get(i);
                eq(d.colorComponents,3,"stock L2D RGB "+i);
                eq(d.source.image.width,256,"metro texture width "+i);eq(d.source.image.height,256,"metro texture height "+i);
                ok(d.perspective,"perspective "+i);ok(d.depthTest && d.depthWrite,"depth "+i);eq(Float.floatToIntBits(d.threshold),0,"threshold "+i);
                ok(d.scale==7500.0f/256.0f,"world scale "+i);
                for(int k=0;k<d.lengths.length;k++)eq(d.lengths[k],4,"metro is quads "+i+":"+k);
            }
            // Deterministic second pass: same state stream, no hot allocation.
            Graphics3D.history.clear();metro.render(target,pose,240,320,212,212);List<Graphics3D.Draw> again=capture();
            eq(again.size(),draws.size(),"repeat call count");
            ok(Arrays.equals(cam,Graphics3D.cameraTransform.matrix),"camera world transform changed on repeat");
            for(int i=0;i<draws.size();i++) {
                Graphics3D.Draw x=draws.get(i),y=again.get(i);
                ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"repeat geometry changed "+i);
                ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"repeat texture changed "+i);
            }
            cases++;
        }
        eq(arrays,VertexArray.created,"hot geometry allocation");eq(sets,VertexArray.sets,"hot vertex upload");eq(uploads,Graphics3D.uploads,"hot texture upload");
        int nodeCalls=Graphics3D.nodeCalls;Graphics3D.history.clear();
        metro.render(target,pose,120,160,106,106);
        eq(Graphics3D.nodeCalls-nodeCalls,22,"small viewport Node count");ok(Graphics3D.target==null,"small target leak");
        for(Graphics3D.Draw d:capture()){eq(d.viewportWidth,120,"small viewport width");eq(d.viewportHeight,160,"small viewport height");}
    }

    static void failures()throws Exception {
        com.MetroBenchmark metro=new com.MetroBenchmark();
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();pose.setPosition(475,2005,-1773);
        metro.render(target,pose,240,320,212,212);
        for(String flag:new String[]{"canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;
            try{metro.render(target,pose,240,320,212,212);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"metro target leak "+flag);
        }
        metro.render(target,pose,240,320,212,212);ok(true,"recovered after failures");
    }

    static void viewerChecks()throws Exception {
        Class c=Class.forName("code.Rendering.L2DMetroPreview$Viewer");
        Constructor ctor=c.getDeclaredConstructor(L2DMetroPreview.class);ctor.setAccessible(true);
        Object v=ctor.newInstance(new L2DMetroPreview());
        call(v,"load",new Class[0],new Object[0]);set(v,"visible",Boolean.TRUE);set(v,"running",Boolean.TRUE);
        com.MetroBenchmark metro=(com.MetroBenchmark)get(v,"metro");ok(metro!=null && metro.calls()==22,"viewer metro");
        eq((Integer)get(v,"x"),475,"home x");eq((Integer)get(v,"y"),2005,"eye y");eq((Integer)get(v,"z"),-1773,"home z");eq((Integer)get(v,"scaleIndex"),0,"default scale");
        Graphics screen=Image.createImage(240,340).getGraphics();
        int draws=Graphics.drawImageCalls,reads=Image.getRGBCalls;
        screen.stringCalls=0;screen.rectCalls=0;Graphics3D.history.clear();
        paint(v,screen);ok(get(v,"error")==null,"cold paint failed");
        eq(screen.stringCalls,4,"live HUD strings");eq(screen.rectCalls,1,"S100 rects (HUD only)");
        eq(Graphics.drawImageCalls,draws+1,"S100 image copy");eq(Image.getRGBCalls,reads,"no readback");
        ok(((String)get(v,"heading")).startsWith("S100"),"default heading");
        ok(((String)get(v,"counts")).indexOf("T -")>=0,"counts placeholder");
        eq((Integer)get(v,"renderWidth"),240,"S100 width");eq((Integer)get(v,"renderHeight"),340,"S100 height");
        eq(Graphics3D.history.size(),22,"viewer submitted frame");
        int x=(Integer)get(v,"x"),y=(Integer)get(v,"y"),z=(Integer)get(v,"z"),yaw=(Integer)get(v,"yaw");
        key(v,48);key(v,48);screen.stringCalls=0;screen.rectCalls=0;Graphics3D.history.clear();paint(v,screen);
        ok(((String)get(v,"heading")).startsWith("S50"),"S50 heading");
        eq((Integer)get(v,"renderWidth"),120,"S50 width");eq((Integer)get(v,"renderHeight"),170,"S50 height");
        eq(screen.rectCalls,3,"S50 bars + HUD");eq(Graphics3D.history.size(),22,"S50 frame calls");
        key(v,49);paint(v,screen);
        eq((Integer)get(v,"scaleIndex"),0,"key1 BASE scale");ok(((String)get(v,"heading")).startsWith("S100"),"key1 heading");
        eq((Integer)get(v,"x"),x,"toggles moved x");eq((Integer)get(v,"y"),y,"toggles moved y");eq((Integer)get(v,"z"),z,"toggles moved z");eq((Integer)get(v,"yaw"),yaw,"toggles moved yaw");
        set(v,"x",Integer.valueOf(x+1000));set(v,"z",Integer.valueOf(z-1000));set(v,"yaw",Integer.valueOf(90));key(v,53);
        eq((Integer)get(v,"x"),x,"home x");eq((Integer)get(v,"z"),z,"home z");eq((Integer)get(v,"yaw"),0,"home yaw");
        call(v,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(v,screen);eq(Graphics3D.history.size(),0,"hidden render");
        call(v,"showNotify",new Class[0],new Object[0]);ok(((Boolean)get(v,"resetRequested")).booleanValue(),"show reset");
        L2DMetroStats stats=new L2DMetroStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522),"warm published");ok(!stats.record(1000,1000,1020,1021,1022),"anchor published");
        ok(!stats.record(2000,2000,2020,2021,2022),"short window published");ok(stats.record(4000,4000,4020,4021,4022),"window missing");
        eq(stats.fps10,6,"stats FPS");eq(stats.meanNative,20,"stats N");eq(stats.meanCopy,1,"stats C");eq(stats.meanInterval,1500,"stats I");
        call(v,"release",new Class[0],new Object[0]);
    }

    public static void main(String[] args)throws Exception {
        sweep();failures();viewerChecks();
        System.out.println("PASS L2DMetro: "+checks+" checks / "+cases+" indoor poses; stock L2D Node-path contracts, windows and failure recovery. NOT device FPS or rasterization.");
    }
}
