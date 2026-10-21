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

/** Upscale A/B on the river map: same reduced viewport render as the pixel diagnostic,
 * plus delivery either 1:1 with black bars (DIR) or nearest-expanded to full screen (UP).
 * The goal is to price the Java upscale copy against the fill saved by the smaller viewport. */
public final class RiverUpscalePreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    private static final class Viewer extends Canvas implements Runnable {
        private static final int[] SCALES={100,75,50,25};
        private final RiverUpscalePreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,ready,resetRequested=true;
        private volatile Thread worker;
        private RiverPixelMesh nativeRoom;
        private MultyTexture materials;
        private final Matrix pose=new Matrix();
        private Image surface;private Graphics surfaceGraphics;
        private final RiverPixelStats stats=new RiverPixelStats();
        private String heading="S100 DIR TEX SM B; warming",timings="N - R - C - I - ms";
        private int scaleIndex;
        private boolean upscale,textured=true,flat,mip,pixelDirty=true,scaleDirty=true;
        private int turn,move,turnRemainder;
        private int x,y,z,yaw,homeX,homeY,homeZ,width,height,renderWidth,renderHeight,far,configuredFar,focalX,focalY,renderFocalX,renderFocalY;
        private int[] srcBuf,dstBuf;
        private volatile String error;
        private long lastFrame;
        Viewer(RiverUpscalePreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;resetRequested=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;resetRequested=true;}
        protected void hideNotify(){visible=false;resetRequested=true;}
        protected void showNotify(){visible=true;resetRequested=true;}
        void close(){closed=true;stop();if(worker==null)release();}
        private void release(){if(nativeRoom!=null)nativeRoom.dispose();nativeRoom=null;surface=null;surfaceGraphics=null;srcBuf=dstBuf=null;}
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
            nativeRoom=new RiverPixelMesh(meshes[0],Main.fogQ,true);
            nativeRoom.setCullingEnabled(true);nativeRoom.setNearPlane(128);nativeRoom.setPerspectiveEnabled(true);
            pixelDirty=true;resetRequested=true;ready=true;
            // Same GRID-selected faces and same pixel path as the pixel diagnostic; only delivery changes.
        }

        private void resizeTarget(int w,int h) {
            if(w<=0 || h<=0)throw new IllegalArgumentException("viewport");
            width=w;height=h;surface=null;surfaceGraphics=null;dstBuf=null;
            double tangent=Math.tan(74*Math.PI/360);
            focalX=(int)((width/2)/tangent);focalY=(int)((height/2)/tangent);focalX=focalX*height/width;
            scaleDirty=true;resetRequested=true;
        }
        private void updateRenderSize() {
            int percent=SCALES[scaleIndex];
            renderWidth=Math.max(1,width*percent/100);renderHeight=Math.max(1,height*percent/100);
            renderFocalX=Math.max(1,focalX*renderWidth/width);renderFocalY=Math.max(1,focalY*renderHeight/height);
            surface=null;surfaceGraphics=null;srcBuf=null;
        }
        private String modeName(){return "S"+SCALES[scaleIndex]+" "+(upscale?"UP":"DIR")+" "+(textured?"TEX":"1X1")+" "+(flat?"FL":"SM")+" "+(mip?"M":"B");}
        private void applyPixelState() {
            if(!pixelDirty || nativeRoom==null)return;
            nativeRoom.setTexturedEnabled(textured);nativeRoom.setFlatShadingEnabled(flat);nativeRoom.setMipmapsEnabled(mip);pixelDirty=false;
        }
        private void resetStats() {
            stats.reset(System.currentTimeMillis());lastFrame=0;resetRequested=false;
            heading=modeName()+"; warming";timings="N - R - C - I - ms";
        }
        private static String fps(int value){return (value/10)+"."+(value%10);}
        /** Nearest-neighbour expand: dst(x,y) = src(floor(x*sw/dw), floor(y*sh/dh)).
         * Rows that map to the already expanded source row are duplicated arraycopy-cheap. */
        static void upscaleNN(int[] src,int sw,int sh,int[] dst,int dw,int dh) {
            if(sw<1 || sh<1 || sw>dw || sh>dh)throw new IllegalArgumentException("upscale size");
            if(src.length<sw*sh || dst.length<dw*dh)throw new IllegalArgumentException("upscale buffer");
            int fx=(sw<<16)/dw,fy=(sh<<16)/dh;
            int pos=0,lastSy=-1,rowStart=0;
            for(int y=0;y<dh;y++) {
                int sy=(y*fy)>>16;
                if(sy==lastSy) {
                    System.arraycopy(dst,rowStart,dst,pos,dw);
                } else {
                    rowStart=pos;int srow=sy*sw,acc=0;
                    for(int x=0;x<dw;x++){dst[pos+x]=src[srow+(acc>>16)];acc+=fx;}
                    lastSy=sy;
                }
                pos+=dw;
            }
        }
        protected void paint(Graphics g) {
            if(closed)return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());
                if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);g.drawString("1 BASE; # exit",0,20,20);return;}
                if(!ready || !running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString(!ready?"Loading upscale test...":"Paused",0,0,20);return;}
                long time=System.currentTimeMillis();
                if(width!=getWidth() || height!=getHeight())resizeTarget(getWidth(),getHeight());
                if(scaleDirty){updateRenderSize();scaleDirty=false;}
                if(resetRequested)resetStats();
                int elapsed=lastFrame==0?0:(int)Math.max(0,Math.min(50,time-lastFrame));lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;pose.setRotY(yaw);
                x+=(int)(pose.m02*(long)move*elapsed*10/Matrix.FP);
                z+=(int)(pose.m22*(long)move*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();applyPixelState();
                if(surface==null){surface=Image.createImage(renderWidth,renderHeight);surfaceGraphics=surface.getGraphics();}
                long nativeStart=System.currentTimeMillis();nativeRoom.render(surfaceGraphics,pose,renderWidth,renderHeight,renderFocalX,renderFocalY,far);long nativeEnd=System.currentTimeMillis();
                if(!upscale || (renderWidth==width && renderHeight==height)) {
                    if(renderWidth<width || renderHeight<height) {
                        g.setColor(0);
                        if(renderWidth<width)g.fillRect(renderWidth,0,width-renderWidth,height);
                        if(renderHeight<height)g.fillRect(0,renderHeight,renderWidth,height-renderHeight);
                    }
                    g.drawImage(surface,0,0,20);
                } else {
                    if(srcBuf==null || srcBuf.length<renderWidth*renderHeight)srcBuf=new int[renderWidth*renderHeight];
                    if(dstBuf==null || dstBuf.length<width*height)dstBuf=new int[width*height];
                    surface.getRGB(srcBuf,0,renderWidth,0,0,renderWidth,renderHeight);
                    upscaleNN(srcBuf,renderWidth,renderHeight,dstBuf,width,height);
                    g.drawRGB(dstBuf,0,width,0,0,width,height,false);
                }
                long copyEnd=System.currentTimeMillis();
                g.setColor(0);g.fillRect(0,0,width,30);g.setColor(0xffffff);
                g.drawString(heading,0,0,20);g.drawString(timings,0,15,20);
                if(stats.record(time,nativeStart,nativeEnd,copyEnd,System.currentTimeMillis(),nativeRoom)) {
                    heading=modeName()+" FPS "+fps(stats.fps10);
                    timings="N "+stats.meanNative+" R "+stats.meanR+" C "+stats.meanCopy+" I "+stats.meanInterval+" ms";
                }
            }catch(Throwable ex){error=ex instanceof OutOfMemoryError?"Out of memory; 1 selects BASE":ex.toString();running=false;repaint();}
            finally{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        private void freezeMovement(){turn=move=turnRemainder=0;}
        private void changed(){freezeMovement();error=null;running=true;resetRequested=true;}
        protected void keyPressed(int key) {
            if(key==KEY_NUM1 && ready && !closed){scaleIndex=0;upscale=false;textured=true;flat=false;mip=false;scaleDirty=pixelDirty=true;changed();return;}
            if(key==KEY_NUM0 && ready && !closed){scaleIndex=(scaleIndex+1)%SCALES.length;scaleDirty=true;changed();return;}
            if(key==KEY_NUM3 && ready && !closed){upscale=!upscale;changed();return;}
            if(key==KEY_NUM7 && ready && !closed){textured=!textured;pixelDirty=true;changed();return;}
            if(key==KEY_NUM9 && ready && !closed){flat=!flat;pixelDirty=true;changed();return;}
            if(key==42 && ready && !closed){mip=!mip;pixelDirty=true;changed();return;} // '*': base level <-> nearest mip level.
            if(key==KEY_NUM5 && ready){x=homeX;y=homeY;z=homeZ;yaw=0;changed();return;}
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
        }
        protected void keyReleased(int key){if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;if(key==KEY_NUM2 || key==KEY_NUM8)move=0;}
    }
}
