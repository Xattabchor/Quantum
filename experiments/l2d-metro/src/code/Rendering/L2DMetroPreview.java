package code.Rendering;

import code.Math.Matrix;
import java.io.IOException;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** Indoor probe: upstream L2D_M3G metro level (level1: metro.3d2 + metro_1.bmp,
 * WORLD_SCALE 7500) rendered by the stock L2D Renderer.addMesh(Node) path,
 * world-only, with the same HUD timing stand as the other diags.
 * Complementary to the river/village probes: tiny geometry (1640 tris,
 * 22 draws), walls everywhere, zero sky/river. NOT a game port, NOT an
 * LCD-FPS claim: Java API wall-clock windows only. */
public final class L2DMetroPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    static final class Viewer extends Canvas implements Runnable {
        private static final int[] SCALES={100,75,50,25};
        private final L2DMetroPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true;
        private volatile Thread worker;
        private com.MetroBenchmark metro;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final L2DMetroStats stats=new L2DMetroStats();
        private String heading="S100; warming",timings="N - C - I - ms",counts="T - DC - VB -",extra="metro.3d2; near128 far60000";
        private int scaleIndex;
        private boolean scaleDirty=true;
        private int turn,move,strafe,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,renderWidth,renderHeight,focalX,focalY,renderFocalX,renderFocalY;
        private volatile String error;
        private long lastFrame;
        Viewer(L2DMetroPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;resetRequested=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;resetRequested=true;}
        protected void hideNotify(){visible=false;resetRequested=true;}
        protected void showNotify(){visible=true;resetRequested=true;}
        void close(){closed=true;stop();if(worker==null)release();}
        private void release(){surface=null;surfaceGraphics=null;}
        public void run(){
            try {
                if(!ready)load();
                while(!closed){if(running && visible){repaint();serviceRepaints();}Thread.sleep(running && visible && error==null?1:100);}
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory during load":ex.toString();repaint();}
            finally{release();synchronized(this){worker=null;}}
        }
        private void load() throws IOException {
            metro=new com.MetroBenchmark();
            x=homeX=475;y=homeY=com.MetroBenchmark.EYE_HEIGHT;z=homeZ=-1773; // level1 START + L2D character eye height
            resizeTarget(getWidth(),getHeight());
            scaleDirty=true;resetRequested=true;ready=true;
        }
        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;
            scaleDirty=true;resetRequested=true;
        }
        private void updateRenderSize() {
            int percent=SCALES[scaleIndex];
            renderWidth=Math.max(1,width*percent/100);renderHeight=Math.max(1,height*percent/100);
            renderFocalX=Math.max(1,focalX*renderWidth/width);renderFocalY=Math.max(1,focalY*renderHeight/height);
            surface=null;surfaceGraphics=null;
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;
            heading="S"+SCALES[scaleIndex]+"; warming";timings="N - C - I - ms";
        }
        private static String fps(int value){return (value/10)+"."+(value%10);}
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("1 S100; # exit",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading metro...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(scaleDirty){updateRenderSize();scaleDirty=false;}
                if(resetRequested)resetStats();
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);
                if(surface==null){surface=Image.createImage(renderWidth,renderHeight);surfaceGraphics=surface.getGraphics();}
                long nativeStart=System.currentTimeMillis();metro.render(surfaceGraphics,pose,renderWidth,renderHeight,renderFocalX,renderFocalY);long nativeEnd=System.currentTimeMillis();
                if(renderWidth<width || renderHeight<height) {
                    g.setColor(0);
                    if(renderWidth<width)g.fillRect(renderWidth,0,width-renderWidth,height);
                    if(renderHeight<height)g.fillRect(0,renderHeight,renderWidth,height-renderHeight);
                }
                g.drawImage(surface,0,0,20);long copyEnd=System.currentTimeMillis();
                g.setColor(0);g.fillRect(0,0,width,60);g.setColor(0xffffff);
                g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);g.drawString(counts,0,30,20);g.drawString(extra,0,45,20);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis())) {
                    heading="S"+SCALES[scaleIndex]+" FPS "+fps(stats.fps10);
                    timings="N "+stats.meanNative+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
                    counts="T "+metro.triangles+" DC "+metro.calls()+" VB "+metro.vertices;
                }
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; # exit":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        private void freezeMovement(){turn=move=strafe=turnRemainder=0;}
        private void changed(){freezeMovement();error=null;running=true;resetRequested=true;}
        protected void keyPressed(int key) {
            if(key==KEY_NUM1 && ready && !closed){scaleIndex=0;scaleDirty=true;changed();return;}
            if(key==KEY_NUM0 && ready && !closed){scaleIndex=(scaleIndex+1)%SCALES.length;scaleDirty=true;changed();return;}
            if(key==KEY_NUM5 && ready){x=homeX;y=homeY;z=homeZ;yaw=0;changed();return;}
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
            else if(key==KEY_NUM7)strafe=-1;else if(key==KEY_NUM9)strafe=1;
        }
        protected void keyReleased(int key){if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;if(key==KEY_NUM2 || key==KEY_NUM8)move=0;if(key==KEY_NUM7 || key==KEY_NUM9)strafe=0;}
    }
}
