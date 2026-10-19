package code.Rendering;

import java.lang.reflect.*;
import java.util.*;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Contract doubles, NOT a Canvas rasterizer, GPU timer or handset benchmark. */
public final class StaticRoomPresentTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Field field(Object object,String name)throws Exception {
        Field result=object.getClass().getDeclaredField(name);result.setAccessible(true);return result;
    }
    static Object get(Object object,String name)throws Exception{return field(object,name).get(object);}
    static void set(Object object,String name,Object value)throws Exception{field(object,name).set(object,value);}
    static void call(Object object,String name)throws Exception {
        Method method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(object);
    }
    static void key(Object object)throws Exception {
        Method method=object.getClass().getDeclaredMethod("keyPressed",Integer.TYPE);method.setAccessible(true);method.invoke(object,48);
    }
    static void paint(Object object,Graphics graphics)throws Exception {
        Method method=object.getClass().getDeclaredMethod("paint",Graphics.class);method.setAccessible(true);method.invoke(object,graphics);
    }
    static Object viewer(boolean present)throws Exception {
        Class<?> type=Class.forName("code.Rendering."+(present?"StaticRoomPresentPreview":"StaticRoomPreview")+"$Viewer");
        Class<?> app=present?StaticRoomPresentPreview.class:StaticRoomPreview.class;
        Constructor<?> constructor=type.getDeclaredConstructor(app);constructor.setAccessible(true);
        Object result=constructor.newInstance(app.newInstance());call(result,"load");return result;
    }
    static String renderWitness(Object viewer,Graphics screen)throws Exception {
        Graphics3D.history.clear();paint(viewer,screen);
        check(get(viewer,"error")==null,"paint failed: "+get(viewer,"error"));
        check(Graphics3D.target==null,"native target leaked");return StaticRoomTest.witness();
    }
    static void stats() {
        StaticRoomFrameStats stats=new StaticRoomFrameStats();stats.reset(1000);
        for(int frame=0;frame<10;frame++) {
            long start=1000+100*frame;
            boolean published=stats.record(start,start+2,start+82,start+92,start+100);
            check(published==(frame==9),"stats must publish only at >=1s");
        }
        check(stats.fps==10 && stats.meanPrepare==2 && stats.meanNative==80 && stats.meanCopy==10 && stats.meanPaint==100 && stats.meanInterval==100,"exclusive durations/intervals wrong");
        stats.reset(1000);
        for(int frame=0;frame<7;frame++) {
            long start=1000+150*frame;stats.record(start,start+2,start+92,start+92,start+100);
        }
        check(stats.fps==7 && stats.meanNative==90 && stats.meanCopy==0 && stats.meanInterval==150 && stats.meanPaint==100,"scheduling gap confused with native/copy time");
        check(!stats.record(500,501,502,503,504),"backwards clock published");
        check(stats.fps==0 && stats.meanNative==0,"clock reset retained old sample");
        check(!stats.record(505,504,508,508,509),"negative duration accepted");
        System.out.println("PASS synthetic 1s wall-clock aggregation / P N C F I / scheduling gap / switch reset / backwards clock; NOT device timings");
    }
    static void targets()throws Exception {
        ((Hashtable)getStatic(code.utils.Asset.class,"table")).clear();
        Image.resourceReads.clear();Image.missingResources.clear();
        Object next=viewer(true);StaticRoomMesh room=(StaticRoomMesh)get(next,"nativeRoom");
        check(Image.resourceReads.size()==6 && Image.missingResources.isEmpty(),"new viewer missing cold resources");
        check(get(next,"surface")==null && get(next,"surfaceGraphics")==null,"direct load allocated offscreen surface");
        for(Field f:next.getClass().getDeclaredFields()) {
            check(f.getType()!=DirectX7.class && f.getType()!=code.Rendering.Meshes.Mesh.class,"retained legacy framebuffer/source mesh");
        }
        Graphics screen=Image.createImage(240,340).getGraphics();screen.screenTarget=true;
        int images=Image.creates,copies=Graphics.drawImageCalls;
        renderWitness(next,screen);renderWitness(next,screen);
        check(Graphics3D.lastBoundTarget==screen && Image.creates==images && copies==Graphics.drawImageCalls,"direct target allocated/copied image");
        key(next);check(!((Boolean)get(next,"direct")),"0 did not select image");renderWitness(next,screen);
        Object surface=get(next,"surface"),surfaceGraphics=get(next,"surfaceGraphics");
        check(surface!=null && Graphics3D.lastBoundTarget==surfaceGraphics && surfaceGraphics!=screen && Image.creates==images+1 && Graphics.drawImageCalls==copies+1,"B image target/copy incorrect");
        renderWitness(next,screen);check(get(next,"surface")==surface && get(next,"surfaceGraphics")==surfaceGraphics && Image.creates==images+1,"B reallocated Image/Graphics");
        key(next);renderWitness(next,screen);check(get(next,"surface")==null && get(next,"surfaceGraphics")==null,"A retained image buffer");
        check(Graphics3D.lastBoundTarget==screen,"A did not restore screen target");
        // Full-screen coordinates during M3G; caller translation/clip restored.
        screen.translate(7,-3);screen.setClip(4,5,91,80);
        int[] before={screen.getTranslateX(),screen.getTranslateY(),screen.getClipX(),screen.getClipY(),screen.getClipWidth(),screen.getClipHeight()};
        renderWitness(next,screen);
        int[] after={screen.getTranslateX(),screen.getTranslateY(),screen.getClipX(),screen.getClipY(),screen.getClipWidth(),screen.getClipHeight()};
        check(Arrays.equals(before,after),"caller graphics state changed");
        System.setProperty("screen.bind.fail","true");paint(next,screen);System.clearProperty("screen.bind.fail");
        check(get(next,"error")!=null && Graphics3D.target==null,"unsupported direct binding hidden/leaked");
        key(next);check(get(next,"error")==null,"0 cannot recover from unsupported direct Canvas");
        renderWitness(next,screen);check(Graphics3D.lastBoundTarget!=screen,"error recovery was not explicit native Image");
        // Also recover from a draw failure after binding; always release target.
        key(next);Graphics3D.failAt=Graphics3D.calls+1;paint(next,screen);Graphics3D.failAt=0;
        check(get(next,"error")!=null && Graphics3D.target==null,"native draw failure leaked target");
        key(next);renderWitness(next,screen);
        int oldWidth=Canvas.screenWidth,oldHeight=Canvas.screenHeight;
        Canvas.screenWidth=320;Canvas.screenHeight=241;
        Graphics resized=Image.createImage(320,241).getGraphics();resized.screenTarget=true;
        renderWitness(next,resized);Image resizedSurface=(Image)get(next,"surface");
        check(resizedSurface.getWidth()==320 && resizedSurface.getHeight()==241,"resized B surface stale");
        Canvas.screenWidth=oldWidth;Canvas.screenHeight=oldHeight;
        room.dispose();
        System.out.println("PASS cold direct/Image/A targets / lazy reused B Image+Graphics / zero A image copies / explicit unsupported-Canvas recovery / draw-error release / clip+translation restore / resize / no legacy framebuffer or source Mesh retention");
    }
    static Object getStatic(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
    static void witnesses()throws Exception {
        Object old=viewer(false),next=viewer(true);
        Texture oldRiver=((MultyTexture)get(old,"materials")).textures[4];
        Texture nextRiver=((MultyTexture)get(next,"materials")).textures[4];
        RawImage[] oldFrames=new RawImage[4],nextFrames=new RawImage[4];
        for(int i=0;i<4;i++){oldFrames[i]=oldRiver.animMIP[i].rImg;nextFrames[i]=nextRiver.animMIP[i].rImg;}
        // Freeze the animation clock per witness; still exercise each of its
        // four actual prepared frames. No renderer/test fixture re-upload.
        oldRiver.animation_speed=nextRiver.animation_speed=Float.MIN_VALUE;
        Graphics screen=Image.createImage(240,340).getGraphics();screen.screenTarget=true;
        int samples=0;
        for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            set(old,"yaw",yaw);set(next,"yaw",yaw);
            oldRiver.rImg=oldFrames[frame];nextRiver.rImg=nextFrames[frame];
            String baseline=renderWitness(old,screen);
            set(next,"direct",true);String direct=renderWitness(next,screen);
            check(baseline.equals(direct),"changed culled geometry/camera/state/river frame vs old viewer");
            set(next,"direct",false);String image=renderWitness(next,screen);
            check(direct.equals(image),"A/B changed scene, not just target");samples++;
        }
        // Match focal lengths against the actual pinned engine, not only its
        // source, including odd sizes and landscape integer rounding.
        Method resize=next.getClass().getDeclaredMethod("resizeTarget",Integer.TYPE,Integer.TYPE);resize.setAccessible(true);
        for(int[] size:new int[][]{{240,340},{240,320},{176,208},{320,240},{241,321},{320,241},{101,99}}) {
            resize.invoke(next,size[0],size[1]);DirectX7 reference=new DirectX7(size[0],size[1]);
            check(((Integer)get(next,"focalX"))==reference.distX && ((Integer)get(next,"focalY"))==reference.distY,"FOV rounding changed");reference.destroy();
        }
        ((StaticRoomMesh)get(old,"nativeRoom")).dispose();((DirectX7)get(old,"legacy")).destroy();
        ((StaticRoomMesh)get(next,"nativeRoom")).dispose();
        System.out.println("PASS "+samples+" old-cull vs direct vs image ordered scene/camera/state/texture witnesses / all 4 river frames / 7 viewport focal pairs vs published engine; NOT pixel equivalence");
    }
    public static void main(String[] args)throws Exception{stats();targets();witnesses();System.out.println("NOT a handset, GPU timestamp, Canvas pixel or FPS benchmark");}
}
