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

/** River/village map: BVH/GRID selection plus FULL/MIN/OFF diagnostic HUD. */
public final class RiverMapBvhHudPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    private static final class Viewer extends Canvas implements Runnable {
        private static final int HUD_FULL=0,HUD_MIN=1,HUD_OFF=2;
        private final RiverMapBvhHudPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true,bvh=true;
        private volatile Thread worker;
        private StaticRoomBvhMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final StaticRoomPerspectiveStats stats=new StaticRoomPerspectiveStats();
        private String heading="BVH FULL; warming",timings="N - C - I - ms",counts="T - DC - S -",extra="V - CUT - U -";
        private int hudMode=HUD_FULL;
        private boolean showResult,hasResult;
        private int turn,move,strafe,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,far,configuredFar,focalX,focalY;
        private volatile String error;
        private long lastFrame;
        Viewer(RiverMapBvhHudPreview app){this.app=app;setFullScreenMode(true);}
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
            nativeRoom=new StaticRoomBvhMesh(meshes[0],Main.fogQ,true);
            nativeRoom.setCullingEnabled(true);nativeRoom.setNearPlane(128);nativeRoom.setBvhEnabled(bvh);resetRequested=true;ready=true;
            // Same river map/materials in all modes; selection and HUD amount are the variables.
        }

        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;resetRequested=true;
        }
        private String hudName(){return hudMode==HUD_FULL?"FULL":hudMode==HUD_MIN?"MIN":"OFF";}
        private String selectionName(boolean useBvh){return useBvh?"BVH":"GRID";}
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;hasResult=false;
            heading=selectionName(bvh)+" "+hudName()+"; warming";
            timings="N - C - I - ms";counts="T - DC - S -";extra="V - CUT - U -";
        }
        private static String fps(int value){return (value/10)+"."+(value%10);}
        private void updateResult(boolean useBvh) {
            heading=selectionName(useBvh)+" "+hudName()+" FPS "+fps(stats.fps10)+(nativeRoom.isCullingFallbackActive()?" MEM":"");
            timings="N "+stats.meanNative+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
            counts="T "+nativeRoom.submittedTriangles+" DC "+nativeRoom.draws+" S "+nativeRoom.submittedCorners;
            extra="V "+nativeRoom.visibilityTests+" CUT "+nativeRoom.culledCells+" U "+nativeRoom.maskUploads;
            hasResult=true;
        }
        private void drawFullHud(Graphics g,String lastLine) {
            g.setColor(0);g.fillRect(0,0,width,90);g.setColor(0xffffff);
            g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);g.drawString(counts,0,30,20);g.drawString(extra,0,45,20);
            g.drawString("7 BVH;9 GRID;5 home",0,60,20);g.drawString(lastLine,0,75,20);
        }
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("# exit; restart to retry",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading river BVH HUD...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(showResult){
                    if(surface!=null)g.drawImage(surface,0,0,20);else {g.setColor(0);g.fillRect(0,0,width,height);}
                    drawFullHud(g,hasResult?"* resume;1/3/0 HUD":"NO RESULT YET; * resume");
                    return;
                }
                if(resetRequested)resetStats();
                boolean useBvh=bvh;
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();nativeRoom.setBvhEnabled(useBvh);
                if(surface==null){surface=Image.createImage(width,height);surfaceGraphics=surface.getGraphics();}
                long nativeStart=System.currentTimeMillis();nativeRoom.render(surfaceGraphics,pose,width,height,focalX,focalY,far);long nativeEnd=System.currentTimeMillis();
                g.drawImage(surface,0,0,20);long copyEnd=System.currentTimeMillis();
                if(hudMode==HUD_FULL)drawFullHud(g,"1F;3M;0OFF;*result");
                else if(hudMode==HUD_MIN){g.setColor(0);g.fillRect(0,0,width,30);g.setColor(0xffffff);g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);}
                // HUD_OFF deliberately draws no panel or text after the map copy.
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis()))updateResult(useBvh);
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; # exit":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        private void freezeMovement(){turn=move=strafe=turnRemainder=0;}
        protected void keyPressed(int key) {
            if(key==42 && ready && !closed && error==null){ // '*': reveal/freeze the OFF result, then resume with a fresh window.
                if(showResult){showResult=false;freezeMovement();resetRequested=true;}
                else if(hudMode==HUD_OFF){showResult=true;freezeMovement();}
                return;
            }
            if((key==KEY_NUM0 || key==KEY_NUM1 || key==KEY_NUM3) && ready && !closed && error==null){
                hudMode=key==KEY_NUM1?HUD_FULL:key==KEY_NUM3?HUD_MIN:HUD_OFF;
                showResult=false;freezeMovement();resetRequested=true;return;
            }
            if((key==KEY_NUM7 || key==KEY_NUM9) && ready && !closed && error==null){
                // 7/9 are BVH/GRID in this combined diagnostic, not strafe.
                bvh=key==KEY_NUM7;showResult=false;freezeMovement();resetRequested=true;return;
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
