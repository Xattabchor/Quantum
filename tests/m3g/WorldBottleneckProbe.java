package code.Rendering;

import code.Gameplay.Map.Room;
import code.Rendering.Meshes.Mesh;
import code.Math.Matrix;
import code.utils.IniFile;
import code.utils.Main;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Diagnostic counts on real world geometry. No GPU timings or FPS claims.
 * Intentionally submits ALL renderable map rooms, without House portals/chunks/NPCs.
 * Source instrumentation is applied only to temporary test copies by run.py.
 */
public final class WorldBottleneckProbe {
    static boolean active;
    static int textureSplits,stateSplits,scaleSplits,capacitySplits,scaleOnlySplits,fogShared,fogLegacy;
    static int clipAttempts,clipRecovered,clipTriangles,clipDiscards;
    public static boolean clipResult(WorldClipGeometry geometry,boolean success) {
        if(active) {
            clipAttempts++;
            if(success) {clipRecovered++;clipTriangles+=geometry.triangles;if(geometry.triangles==0) clipDiscards++;}
        }
        return success;
    }
    public static float fogResult(boolean shared,float scale) {
        if(active) {if(shared) fogShared++;else fogLegacy++;}
        return scale;
    }
    static final Map<String,Integer> rejects=new TreeMap<String,Integer>();
    static final Map<String,Integer> packRejects=new TreeMap<String,Integer>();
    public static float packReject(String reason) {
        if(active) {Integer n=packRejects.get(reason);packRejects.put(reason,n==null?1:n+1);}
        return 0;
    }
    public static void split(boolean texture,boolean state,boolean scale,boolean capacity) {
        if(!active) return;
        if(scale && !texture && !state && !capacity) scaleOnlySplits++;
        if(texture) textureSplits++;if(state) stateSplits++;if(scale) scaleSplits++;if(capacity) capacitySplits++;
    }
    public static boolean reject(String reason) {
        if(active) {Integer n=rejects.get(reason);rejects.put(reason,n==null?1:n+1);}
        return false;
    }
    private static InputStream resource(String path) {
        InputStream stream=WorldBottleneckProbe.class.getResourceAsStream(path);
        if(stream==null) throw new IllegalArgumentException("Missing probe resource: "+path);
        return stream;
    }
    private static IniFile ini(String path) throws Exception {
        try(InputStream in=resource(path)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1) bytes.write(b,0,n);
            return new IniFile(new String(bytes.toByteArray(),"windows-1251"),false);
        }
    }
    private static Mesh[] meshes(String path,float scale,MultyTexture texture) throws Exception {
        Method decoder=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,
            Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);decoder.setAccessible(true);
        try(DataInputStream in=new DataInputStream(resource(path))) {
            Mesh[] result=new Mesh[in.readInt()];
            for(int i=0;i<result.length;i++) result[i]=(Mesh)decoder.invoke(null,path,in,scale,scale,scale,texture);
            M3GTest.check(in.available()==0,"unconsumed mesh data");
            // HouseCreator uses the last mesh as PORTAL definitions, not world
            // surfaces. Never render that metadata as geometry in the probe.
            return result.length>1?Arrays.copyOf(result,result.length-1):result;
        }
    }
    private static final Field QUEUE;
    static {
        try {QUEUE=DirectX7.class.getDeclaredField("buffer");QUEUE.setAccessible(true);}
        catch(Exception e) {throw new RuntimeException(e);}
    }
    private static int submit(DirectX7 g,Mesh[] meshes) throws Exception {
        for(Mesh mesh:meshes) {
            g.transformAndProjectVertices(mesh,g.getInvCamera());
            g.addMesh(mesh,0,0,g.width,g.height);mesh.applySz();
        }
        return ((RenderObjectBuffer)QUEUE.get(g)).getSize();
    }
    private static void run(String name,boolean fogOff) throws Exception {
        IniFile level=ini("/"+name),settings=ini("/setting.txt");
        String textureName=level.get("WORLD_TEXTURE");
        M3GTest.check(textureName.endsWith(".png") && textureName.indexOf(',')<0,"probe needs single PNG atlas");
        BufferedImage img;
        try(InputStream in=resource(textureName)) {img=ImageIO.read(in);}
        int w=img.getWidth(),h=img.getHeight();
        Main.pixelsQ=2;Main.persQ=4;Main.mipMapping=false;Main.fogQ=2;
        Main.floorOffsetSZ=level.getInt("FLOOR_OFFSETSZ",0);
        DirectX7.standartDrawmode=(byte)level.getInt("DMODE",0);
        DirectX7.setDrDist(level.getInt("DIST",Integer.MAX_VALUE));
        DirectX7.setFogDist(level.getInt("FOGD",1));
        Texture texture=new Texture(new RawImage(img.getRGB(0,0,w,h,null,0,w),w,h),true,null);
        if(fogOff) texture.drawmode=0; // Counterfactual test only, never a config/source edit.
        Mesh[] meshes=meshes(level.get("WORLD_MODEL"),level.getFloat("WORLD_SCALE",settings.getFloat("WORLD_SCALE",1)),new MultyTexture(texture));
        int vertices=0,polygons=0;
        // Match LevelLoader's default load-time vertex deduplication, otherwise
        // CPU transform counts would exaggerate the normal game's workload.
        if(level.getInt("OPTIMIZE_LEVEL_MODEL",settings.getInt("OPTIMIZE_LEVEL_MODEL",1))!=0)
            for(Mesh mesh:meshes) mesh.optimize();
        for(Mesh mesh:meshes) {vertices+=mesh.getVertices().length;polygons+=mesh.getPolygons().length;}
        String[] start=level.get("START").split("[,;]");
        int px=Integer.parseInt(start[0].trim()),py=Integer.parseInt(start[1].trim())+1503,pz=Integer.parseInt(start[2].trim());
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        System.out.println("PROBE map="+name+" meshes="+meshes.length+" vertices="+vertices+" polygons="+polygons+" atlas="+w+"x"+h+" mode="+texture.drawmode+" experiment="+(fogOff?"fog-off":"original"));
        for(int pitch:new int[]{0,60}) for(int yaw:new int[]{0,90,180,270}) {
            Matrix camera=new Matrix(),rx=new Matrix(),ry=new Matrix();rx.setRotX(pitch);ry.setRotY(yaw);camera.mul(ry,rx);camera.setPosition(px,py,pz);g.setCamera(camera);
            for(int frame=0;frame<2;frame++) {
                textureSplits=stateSplits=scaleSplits=capacitySplits=scaleOnlySplits=fogShared=fogLegacy=0;rejects.clear();packRejects.clear();
                active=frame==1;g.beginFrameStats();
                int rgb=Image.getRGBCalls,copies=Graphics.drawRGBCalls;
                M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();
                int queued=submit(g,meshes);
                g.renderGameFrameImage(null,false,false,null,0,null);
                M3GTest.check(g.isM3GActive(),"probe backend failed: "+g.getRendererError());
                if(active) System.out.println("PROBE frame="+name+" experiment="+(fogOff?"fog-off":"original")+" yaw="+yaw+" pitch="+pitch+" transformed="+vertices+" queued="+queued
                    +" D="+g.getM3GDrawCalls()+" B="+g.getM3GCpuBarriers()+" T="+g.getM3GTextureUploads()
                    +" readbacks="+(Image.getRGBCalls-rgb)+" uploads="+(Graphics.drawRGBCalls-copies)
                    +" splitTexture="+textureSplits+" splitState="+stateSplits+" splitFogScale="+scaleSplits+" splitCapacity="+capacitySplits+" fogShared="+fogShared+" fogLegacy="+fogLegacy+" rejects="+rejects+" packRejects="+packRejects);
            }
        }
        active=false;g.destroy();
    }
    public static void main(String[] args) throws Exception {
        for(String name:new String[]{"level19.txt","level27.txt","level40.txt"}) run(name,false);
        run("level40.txt",true);
        System.out.println("PASS world submission probe / production mesh decoder+transform+culling+sort+adapter / counts only, no FPS");
    }
}
