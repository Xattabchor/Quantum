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

/** Actual L2D renderer versus frozen Quantum FULL scene, not a full-game port. */
public final class L2DMapPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    static final class Viewer extends Canvas implements Runnable {
        private final L2DMapPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true;
        private volatile Thread worker;
        private StaticRoomBvhMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final L2DMapStats stats=new L2DMapStats();
        private String heading="B L2D IMAGE; warming",timings="N - C - I - ms",counts="T - D - VB -",extra="T4750; VB10032; no culling",stages="FULL scene; near128 far60000";
        private int mode=1;
        private com.MapBenchmark l2d;
        private int turn,move,strafe,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,far,configuredFar,focalX,focalY;
        private volatile String error;
        private long lastFrame;
        Viewer(L2DMapPreview app){this.app=app;setFullScreenMode(true);}
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
            String text=StringTools.getStringFromResource("/level1.txt");
            String begin="[WTEX_BEGIN]",end="[WTEX_END]";int b=text.indexOf(begin),e=text.indexOf(end);
            if(b<0 || e<b)throw new IllegalArgumentException("WTEX block missing");
            String[] lines=GameIni.cutOnStrings(text.substring(b+begin.length(),e),'\n','\n');Vector valid=new Vector();
            for(int i=0;i<lines.length;i++)if(lines[i].trim().length()>0)valid.addElement(lines[i].trim());
            String[] textures=new String[valid.size()];valid.copyInto(textures);
            GameIni config=new GameIni(text.substring(0,b)+text.substring(e+end.length()),true);
            DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);configuredFar=config.getInt("DIST",68500);far=Math.min(60000,configuredFar);DirectX7.drDist=far;
            materials=new MultyTexture(textures);
            float scale=config.getFloat("WORLD_SCALE",1);
            Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,materials);
            if(meshes==null || meshes.length==0 || meshes[0]==null)throw new IllegalArgumentException("world load failed");
            String lightmap=config.get("LIGHTMAP");if(lightmap==null)throw new IllegalArgumentException("baked LIGHTMAP required");
            StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomTexturesPreview.class.getResourceAsStream(lightmap));
            if(meshes.length!=1)throw new IOException("draw-order sidecar requires one room mesh");
            StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomTexturesPreview.class.getResourceAsStream("/maps/kordos/kordos.draworder"));
            int[] start=GameIni.createPos(config.get("START"),',');x=homeX=start[0];y=homeY=start[1]+1503;z=homeZ=start[2];
            resizeTarget(getWidth(),getHeight());
            nativeRoom=new StaticRoomBvhMesh(meshes[0],Main.fogQ,true);
            nativeRoom.setNearPlane(128);nativeRoom.setCullingEnabled(false);l2d=new com.MapBenchmark();resetRequested=true;ready=true;
            // Both modes submit the complete4750 triangles, never compare different masks.
        }

        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;resetRequested=true;
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;
            heading=modeName()+"; warming";
            timings="N - C - I - ms";counts="T - DC - VB -";extra="T4750; VB10032; no culling";stages="FULL scene; near128 far60000";
        }
        private String modeName(){return mode==0?"A Q FULL IMAGE":mode==1?"B L2D IMAGE":"C L2D CANVAS";}
        private static String fps(int value){return (value/10)+"."+(value%10);}
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("1 Q Image; 3 L2D Image; # exit",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading L2D + Quantum map...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(resetRequested)resetStats();
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();
                if(surface==null){surface=Image.createImage(width,height);surfaceGraphics=surface.getGraphics();}
                int frame=0;Texture river=materials.textures[2];for(int f=0;f<river.animMIP.length;f++)if(river.rImg==river.animMIP[f].rImg)frame=f;
                long nativeStart=System.currentTimeMillis();
                if(mode==0)nativeRoom.render(surfaceGraphics,pose,width,height,focalX,focalY,far);
                else l2d.render(mode==2?g:surfaceGraphics,pose,width,height,focalX,focalY,frame);
                long nativeEnd=System.currentTimeMillis();if(mode!=2)g.drawImage(surface,0,0,20);long copyEnd=System.currentTimeMillis();
                g.setColor(0);g.fillRect(0,0,width,90);g.setColor(0xffffff);
                g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);g.drawString(counts,0,30,20);g.drawString(extra,0,45,20);g.drawString(stages,0,60,20);
                g.drawString("0 Q/L2D; * Canvas;5 home",0,75,20);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis())) {
                    heading=modeName()+" FPS "+fps(stats.fps10);
                    stages="FULL scene; near128 far60000";
                    timings="N "+stats.meanNative+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
                    counts="T "+l2d.triangles+" DC "+l2d.calls()+" VB "+l2d.vertices;
                    extra=mode==0?"Q: VB/index API; RGBA":"L2D: Node API; RGB";
                }
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; # exit":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        protected void keyPressed(int key) {
            if((key==KEY_NUM0 || key==KEY_NUM1 || key==KEY_NUM3 || key==42) && ready && !closed){
                mode=key==KEY_NUM1?0:key==KEY_NUM3?1:key==42?2:mode==0?1:0;
                turn=move=strafe=turnRemainder=0;error=null;running=true;resetRequested=true;return;
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
