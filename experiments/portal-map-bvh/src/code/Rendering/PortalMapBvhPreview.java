package code.Rendering;

import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.GameIni;
import code.utils.Main;
import code.utils.StringTools;
import java.util.Vector;
import java.io.IOException;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** New user-map portal + BVH ON/OFF comparison, not a production renderer replacement. */
public final class PortalMapBvhPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    private static final class Viewer extends Canvas implements Runnable {
        private final PortalMapBvhPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true;
        private volatile Thread worker;
        private PortalMapBvhMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final PortalMapBvhStats stats=new PortalMapBvhStats();
        private String heading="BVH ON F40000; warming",timings="N - C - I - ms",counts="T - D - VB -",extra="Rooms -/15 at - P -",stages="J/B/K/R/E -";
        private boolean bvh=true;
        private int turn,move,strafe,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,far,configuredFar,focalX,focalY;
        private volatile String error;
        private long lastFrame;
        Viewer(PortalMapBvhPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;resetRequested=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;resetRequested=true;}
        protected void hideNotify(){visible=false;resetRequested=true;}
        protected void showNotify(){visible=true;resetRequested=true;}
        void close(){closed=true;stop();if(worker==null)release();}
        private void release(){if(nativeRoom!=null)nativeRoom.dispose();surface=null;surfaceGraphics=null;}
        public void run(){
            try {
                if(!ready)load();
                while(!closed){if(running && visible){repaint();serviceRepaints();}Thread.sleep(running && visible && error==null?1:100);}
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory during load":ex.toString();repaint();}
            finally{release();synchronized(this){worker=null;}}
        }
        private void load() throws IOException {
            Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);
            Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
            GameIni config=PortalMapData.config();
            Mesh[] meshes=PortalMapBvhData.load(config);materials=meshes[0].getTexture();
            configuredFar=config.getInt("DIST",40000);far=configuredFar;DirectX7.drDist=far;
            int[] start=GameIni.createPos(config.get("START"),',');x=homeX=start[0];y=homeY=start[1]+1503;z=homeZ=start[2];
            resizeTarget(getWidth(),getHeight());
            nativeRoom=new PortalMapBvhMesh(meshes,Main.fogQ,true);nativeRoom.setNearPlane(128);nativeRoom.setPortalsEnabled(true);nativeRoom.setBvhEnabled(bvh);resetRequested=true;ready=true;
            // World-only diagnostic: original RGB light, sky/weather/assets retained
            // in the archive, but no sky/weather/gameplay work in either mode.

        }

        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;resetRequested=true;
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;
            heading=(bvh?"BVH ON":"BVH OFF")+" F"+far+"; warming";
            timings="N - C - I - ms";counts="T - DC - VB -";extra="Rooms -/15 at - P -";stages="J/B/K/R/E -";
        }
        private static String fps(int value){return (value/10)+"."+(value%10);}
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("# exit; restart to retry",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading portal map...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(resetRequested)resetStats();
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();nativeRoom.setPortalsEnabled(true);nativeRoom.setBvhEnabled(bvh);
                if(surface==null){surface=Image.createImage(width,height);surfaceGraphics=surface.getGraphics();}
                long nativeStart=System.currentTimeMillis();nativeRoom.render(surfaceGraphics,pose,width,height,focalX,focalY,far);long nativeEnd=System.currentTimeMillis();
                if(nativeRoom.memoryFallback && !heading.startsWith("BVH FALLBACK"))heading="BVH FALLBACK F"+far+"; warming";
                g.drawImage(surface,0,0,20);long copyEnd=System.currentTimeMillis();
                g.setColor(0);g.fillRect(0,0,width,90);g.setColor(0xffffff);
                g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);g.drawString(counts,0,30,20);g.drawString(extra,0,45,20);g.drawString(stages,0,60,20);
                g.drawString("U "+nativeRoom.maskUploads+" 0bvh;*far;5home",0,75,20);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis(),nativeRoom)) {
                    heading=(bvh?"BVH ON":"BVH OFF")+" F"+far+" FPS "+fps(stats.fps10);
                    stages="J/B/K/R/E "+stats.meanJ+"/"+stats.meanB+"/"+stats.meanK+"/"+stats.meanR+"/"+stats.meanE;
                    timings="N "+stats.meanNative+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
                    counts="T "+nativeRoom.submittedTriangles+" DC "+nativeRoom.draws+" VB "+nativeRoom.submittedBufferVertices;
                    extra=nativeRoom.currentRoom<0?"OUT -> ALL15":("Rooms "+nativeRoom.selectedRooms+"/15 at "+nativeRoom.currentRoom+" P "+nativeRoom.portalTests+(nativeRoom.bvhCached?" C":""));
                    if(nativeRoom.memoryFallback)heading="BVH FALLBACK F"+far+" FPS "+fps(stats.fps10);
                }
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; # exit":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        protected void keyPressed(int key) {
            if(key==42 && ready && !closed && error==null){far=far==40000?30000:far==30000?20000:40000;turn=move=strafe=turnRemainder=0;resetRequested=true;return;}
            if((key==KEY_NUM0 || key==KEY_NUM1 || key==KEY_NUM3) && ready && !closed && error==null){
                bvh=key==KEY_NUM1?true:key==KEY_NUM3?false:!bvh;
                turn=move=strafe=turnRemainder=0;resetRequested=true;return;
            }
            if(key==KEY_NUM5 && ready){x=homeX;y=homeY;z=homeZ;yaw=turn=move=strafe=turnRemainder=0;resetRequested=true;return;}
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
            else if(key==KEY_NUM7)strafe=-1;else if(key==KEY_NUM9)strafe=1;
        }
        protected void keyReleased(int key){if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;if(key==KEY_NUM2 || key==KEY_NUM8)move=0;if(key==KEY_NUM7 || key==KEY_NUM9)strafe=0;}
    }
}
