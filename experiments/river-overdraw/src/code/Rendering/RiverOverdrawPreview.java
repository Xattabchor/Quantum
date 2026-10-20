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

/** River-map overdraw A/B: large buffers, compact order, front-to-back, depth prepass. */
public final class RiverOverdrawPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    private static final class Viewer extends Canvas implements Runnable {
        private static final int MODE_LARGE=0,MODE_ORIG=1,MODE_F2B=2,MODE_ZPRE=3;
        private final RiverOverdrawPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true;
        private volatile Thread worker;
        private RiverOverdrawMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final RiverOverdrawStats stats=new RiverOverdrawStats();
        private String heading="C F2B; warming",timings="N - R - I - ms",fullTimings="N - C - I - ms",counts="T - DC - VB -",extra="S - V - CUT -",stages="J/B/K/R/E -";
        private int mode=MODE_F2B;
        private boolean showResult,hasResult;
        private int turn,move,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,far,configuredFar,focalX,focalY;
        private volatile String error;
        private long lastFrame;
        Viewer(RiverOverdrawPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;resetRequested=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;resetRequested=true;}
        protected void hideNotify(){visible=false;resetRequested=true;}
        protected void showNotify(){visible=true;resetRequested=true;}
        void close(){closed=true;stop();if(worker==null)release();}
        private void release(){if(nativeRoom!=null)nativeRoom.dispose();nativeRoom=null;surface=null;surfaceGraphics=null;}
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
            nativeRoom=new RiverOverdrawMesh(meshes[0],Main.fogQ,true);
            nativeRoom.setCullingEnabled(true);nativeRoom.setNearPlane(128);nativeRoom.setBvhEnabled(false);
            if(!nativeRoom.isCompactAvailable())mode=MODE_LARGE;
            applyMode();resetRequested=true;ready=true;
            // Same GRID-selected faces in every mode; only buffer/order/pass structure changes.
        }

        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;resetRequested=true;
        }
        private String modeName(){return mode==MODE_LARGE?"A LARGE":mode==MODE_ORIG?"C ORIG":mode==MODE_F2B?"C F2B":"C ZPRE";}
        private void applyMode() {
            if(nativeRoom==null)return;
            boolean compact=mode!=MODE_LARGE && nativeRoom.isCompactAvailable();
            if(mode!=MODE_LARGE && !compact)mode=MODE_LARGE;
            nativeRoom.setCompactEnabled(compact);
            nativeRoom.setFrontToBackEnabled(mode>=MODE_F2B);
            nativeRoom.setDepthPrepassEnabled(mode==MODE_ZPRE);
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;hasResult=false;
            heading=modeName()+"; warming";
            timings="N - R - I - ms";fullTimings="N - C - I - ms";counts="T - DC - VB -";extra="S - V - CUT -";stages="J/B/K/R/E -";
        }
        private static String fps(int value){return (value/10)+"."+(value%10);}
        private void updateResult() {
            heading=modeName()+" FPS "+fps(stats.fps10);
            timings="N "+stats.meanNative+" R "+stats.meanR+" I "+stats.meanInterval+" ms";
            fullTimings="N "+stats.meanNative+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
            stages="J/B/K/R/E "+stats.meanJ+"/"+stats.meanB+"/"+stats.meanK+"/"+stats.meanR+"/"+stats.meanE;
            counts="T "+nativeRoom.submittedTriangles+" DC "+nativeRoom.draws+" VB "+nativeRoom.submittedBufferVertices;
            extra="S "+nativeRoom.submittedCorners+" V "+nativeRoom.visibilityTests+" CUT "+nativeRoom.culledCells+" P "+nativeRoom.sortedPackets;
            hasResult=true;
        }
        private void drawLiveHud(Graphics g) {
            g.setColor(0);g.fillRect(0,0,width,30);g.setColor(0xffffff);
            g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);
        }
        private void drawFullHud(Graphics g,String lastLine) {
            g.setColor(0);g.fillRect(0,0,width,90);g.setColor(0xffffff);
            g.drawString(heading,0,0,20);g.drawString(fullTimings,0,15,20);g.drawString(counts,0,30,20);g.drawString(extra,0,45,20);g.drawString(stages,0,60,20);
            g.drawString(lastLine,0,75,20);
        }
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("1 A LARGE; # exit",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading overdraw test...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(showResult){
                    if(surface!=null)g.drawImage(surface,0,0,20);else {g.setColor(0);g.fillRect(0,0,width,height);}
                    drawFullHud(g,hasResult?"* resume;0 mode;5 home":"NO RESULT YET; * resume");
                    return;
                }
                if(resetRequested)resetStats();
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)(pose.m02*(long)move*elapsed*10/Matrix.FP);
                z+=(int)(pose.m22*(long)move*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();applyMode();
                if(surface==null){surface=Image.createImage(width,height);surfaceGraphics=surface.getGraphics();}
                long nativeStart=System.currentTimeMillis();nativeRoom.render(surfaceGraphics,pose,width,height,focalX,focalY,far);long nativeEnd=System.currentTimeMillis();
                g.drawImage(surface,0,0,20);long copyEnd=System.currentTimeMillis();
                drawLiveHud(g);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis(),nativeRoom))updateResult();
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; 1 selects A":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        private void freezeMovement(){turn=move=turnRemainder=0;}
        private void selectMode(int next) {
            mode=next;
            if(nativeRoom!=null && mode!=MODE_LARGE && !nativeRoom.isCompactAvailable())mode=MODE_LARGE;
            showResult=false;freezeMovement();error=null;running=true;resetRequested=true;
        }
        protected void keyPressed(int key) {
            if(key==42 && ready && !closed && error==null){ // '*': freeze/show full counters, then resume fresh.
                if(showResult){showResult=false;freezeMovement();resetRequested=true;}
                else {showResult=true;freezeMovement();}
                return;
            }
            if((key==KEY_NUM0 || key==KEY_NUM1 || key==KEY_NUM3 || key==KEY_NUM7 || key==KEY_NUM9) && ready && !closed){
                int next=key==KEY_NUM1?MODE_LARGE:key==KEY_NUM3?MODE_ORIG:key==KEY_NUM7?MODE_F2B:key==KEY_NUM9?MODE_ZPRE:(mode+1)%4;
                selectMode(next);return;
            }
            if(key==KEY_NUM5 && ready){showResult=false;x=homeX;y=homeY;z=homeZ;yaw=0;freezeMovement();resetRequested=true;return;}
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(showResult)return;
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
        }
        protected void keyReleased(int key){if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;if(key==KEY_NUM2 || key==KEY_NUM8)move=0;}
    }
}
