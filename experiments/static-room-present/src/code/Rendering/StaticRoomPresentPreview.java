package code.Rendering;

import code.Gameplay.Map.Room;
import code.Rendering.Meshes.LightedPolygon3V;
import code.Rendering.Meshes.LightedPolygon4V;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.IOException;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.GameIni;
import code.utils.Main;
import code.utils.StringTools;
import java.util.Vector;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** Direct Canvas vs offscreen Image target experiment. Both modes cull identically. */
public final class StaticRoomPresentPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}

    static int loadLightmap(Mesh[] meshes,InputStream input)throws IOException {
        if(input==null)throw new IOException("lightmap missing");
        DataInputStream in=new DataInputStream(input);int values=0;
        try {
            for(int m=0;m<meshes.length;m++) {
                RenderObject[] faces=meshes[m].getPolygons();
                for(int f=0;f<faces.length;f++) {
                    RenderObject face=faces[f];
                    if(face instanceof LightedPolygon3V){LightedPolygon3V p=(LightedPolygon3V)face;p.la=in.readByte()+128;p.lb=in.readByte()+128;p.lc=in.readByte()+128;values+=3;}
                    else if(face instanceof LightedPolygon4V){LightedPolygon4V p=(LightedPolygon4V)face;p.la=in.readByte()+128;p.lb=in.readByte()+128;p.lc=in.readByte()+128;p.ld=in.readByte()+128;values+=4;}
                    else throw new IOException("preview requires mode9 lighted geometry");
                }
            }
            if(in.read()!=-1)throw new IOException("trailing lightmap bytes");
            return values;
        } finally {in.close();}
    }

    private static final class Viewer extends Canvas implements Runnable {
        private final StaticRoomPresentPreview app;
        private volatile boolean running,closed;
        private Thread worker;
        private StaticRoomMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;
        private Graphics surfaceGraphics;
        private final StaticRoomFrameStats stats=new StaticRoomFrameStats();
        private String heading="A direct; warming",timings="N - C - P - ms",counts="F - I - T -";
        private volatile boolean direct=true,ready;
        private volatile int turn,move,strafe;
        private int x,y,z,yaw,width,height,far,focalX,focalY;
        private String error;
        private long lastFrame;
        private int turnRemainder;

        Viewer(StaticRoomPresentPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;}
        void close(){closed=true;stop();}
        public void run(){
            try {
                if(!ready)load();
                stats.reset(System.currentTimeMillis());
                while(!closed){if(running){repaint();serviceRepaints();}Thread.sleep(running?1:100);}
            }catch(Throwable ex){error=ex.toString();repaint();}
            finally{if(nativeRoom!=null)nativeRoom.dispose();surface=null;surfaceGraphics=null;synchronized(this){worker=null;} }
        }
        private void load() throws IOException {
            Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);
            Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
            String text=StringTools.getStringFromResource("/level1.txt");
            String begin="[WTEX_BEGIN]",end="[WTEX_END]";int b=text.indexOf(begin),e=text.indexOf(end);
            if(b<0 || e<b)throw new IllegalArgumentException("WTEX block missing");
            String[] lines=GameIni.cutOnStrings(text.substring(b+begin.length(),e),'\n','\n');Vector valid=new Vector();
            for(int i=0;i<lines.length;i++)if(lines[i].trim().length()>0)valid.addElement(lines[i].trim());
            String[] textures=new String[valid.size()];valid.copyInto(textures);
            GameIni config=new GameIni(text.substring(0,b)+text.substring(e+end.length()),true);
            DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);far=config.getInt("DIST",68500);DirectX7.drDist=far;
            materials=new MultyTexture(textures);
            float scale=config.getFloat("WORLD_SCALE",1);
            Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,materials);
            if(meshes==null || meshes.length==0 || meshes[0]==null)throw new IllegalArgumentException("world load failed");
            String lightmap=config.get("LIGHTMAP");if(lightmap==null)throw new IllegalArgumentException("baked LIGHTMAP required");
            loadLightmap(meshes,StaticRoomPresentPreview.class.getResourceAsStream(lightmap));
            int[] start=GameIni.createPos(config.get("START"),',');x=start[0];y=start[1]+1503;z=start[2];
            resizeTarget(getWidth(),getHeight());
            nativeRoom=new StaticRoomMesh(meshes[0],Main.fogQ,true);
            nativeRoom.setCullingEnabled(true);resetStats();ready=true;
            // No legacy DirectX7 framebuffer, retained source Mesh, or offscreen
            // Image in the direct path. Keep only native cache + materials.
        }
        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            // Preserve the published DirectX7.updateFov(74) integer rounding.
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);
            focalX=focalX*height/width;
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;
            heading=direct?"A direct; warming":"B image; warming";
            timings="N - C - P - ms";counts="F - I - T -";
        }
        protected void paint(Graphics g) {
            if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed; 0 switches target",0,0,20);g.drawString(error,0,20,20);return;}
            if(!ready){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Loading static room...",0,0,20);return;}
            int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();
                // Snapshot the toggle once: key events cannot mix two target
                // modes inside a frame. Both modes use the SAME culled cache.
                boolean useDirect=direct;
                Image frameImage=null;Graphics frameGraphics=g;
                if(useDirect){surface=null;surfaceGraphics=null;}
                else {
                    if(surface==null){surface=Image.createImage(width,height);surfaceGraphics=surface.getGraphics();}
                    frameImage=surface;frameGraphics=surfaceGraphics;
                }
                g.translate(-tx,-ty);g.setClip(0,0,width,height);
                long nativeStart=System.currentTimeMillis();
                nativeRoom.render(frameGraphics,pose,width,height,focalX,focalY,far);
                long nativeEnd=System.currentTimeMillis();
                if(!useDirect)g.drawImage(frameImage,0,0,20);
                long copyEnd=System.currentTimeMillis();
                g.setColor(0);g.fillRect(0,0,width,60);g.setColor(0xffffff);
                // Strings are replaced only once per reporting window, not on
                // every frame. No claim that API wall time equals GPU time.
                g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);g.drawString(counts,0,30,20);
                g.drawString("0 target; 4/6 turn; 2/8 move; 7/9 strafe",0,45,20);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis())) {
                    heading=(useDirect?"A direct":"B image")+" FPS "+stats.fps+" D "+nativeRoom.draws+(nativeRoom.isCullingFallbackActive()?" MEM->whole":"");
                    timings="N "+stats.meanNative+" C "+stats.meanCopy+" P "+stats.meanPrepare+" ms";
                    counts="F "+stats.meanPaint+" I "+stats.meanInterval+" T "+nativeRoom.submittedTriangles;
                }
            }catch(Throwable ex){error=ex.toString();running=false;repaint();}
            finally{g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        protected void keyPressed(int key) {
            if(key==KEY_NUM0){
                direct=!direct;
                resetStats();
                // Explicit native target retry, not an automatic CPU fallback.
                // If Canvas binding is unsupported, 0 lets the user choose B.
                if(ready && !closed){error=null;running=true;}
                return;
            }
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
            else if(key==KEY_NUM7)strafe=-1;else if(key==KEY_NUM9)strafe=1;
        }
        protected void keyReleased(int key) {
            if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;
            if(key==KEY_NUM2 || key==KEY_NUM8)move=0;
            if(key==KEY_NUM7 || key==KEY_NUM9)strafe=0;
        }
    }
}
