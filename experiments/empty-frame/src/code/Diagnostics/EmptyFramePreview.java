package code.Diagnostics;

import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** Standalone presentation-path diagnostic; not a renderer/game replacement. */
public final class EmptyFramePreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}
    private static final class Viewer extends Canvas implements Runnable {
        private static final String[] NAMES={"CANVAS FILL","IMAGE COPY","IMAGE FILL+COPY","M3G BIND+COPY","M3G RGB+COPY","M3G RGBZ+COPY","M3G NO-Z+COPY","M3G DIRECT RGBZ","CANVAS IDLE"};
        private final EmptyFramePreview app;
        private final Object pump=new Object();
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private final EmptyFrameStats stats=new EmptyFrameStats();
        private final long[] ops=new long[EmptyFrameStats.COUNT];
        private final String[] failures=new String[NAMES.length];
        private final String[] lines=new String[10];
        private volatile boolean running,visible,closed,resetRequested=true,forceService=true,fullHud=true,sleepEnabled=true;
        private volatile Thread worker;
        private volatile int mode,controlVersion;
        private int statsVersion=-1;
        private boolean pending;
        private long requestedAt,requestId;
        private volatile long lastService,lastSleep;
        private volatile String workerFailure;
        private Image image;private Graphics imageGraphics;private EmptyFrameNative nativeApi;
        private int width,height,serial,imageAllocations,nativeAllocations;
        Viewer(EmptyFramePreview app){this.app=app;setFullScreenMode(true);warmText();}
        synchronized void start(){synchronized(pump){running=true;resetLater();pump.notifyAll();}if(worker==null && !closed){worker=new Thread(this);worker.start();}}
        void stop(){synchronized(pump){running=false;pending=false;resetLater();pump.notifyAll();}}
        protected void hideNotify(){synchronized(pump){visible=false;pending=false;resetLater();pump.notifyAll();}}
        protected void showNotify(){synchronized(pump){visible=true;resetLater();pump.notifyAll();}}
        void close(){synchronized(pump){closed=true;running=false;pending=false;pump.notifyAll();}if(worker==null)release();}
        private void release(){image=null;imageGraphics=null;nativeApi=null;}
        public void run(){
            try{
                while(true){
                    synchronized(pump){while(!closed && (!running || !visible || pending))pump.wait();if(closed)break;pending=true;requestId++;requestedAt=System.currentTimeMillis();}
                    // Never hold pump while calling serviceRepaints: paint() must
                    // be able to notify completion on another MIDP event thread.
                    repaint();long before=System.currentTimeMillis();
                    if(forceService)serviceRepaints();lastService=System.currentTimeMillis()-before;
                    synchronized(pump){while(!closed && running && visible && pending)pump.wait();}
                    long sleepStart=System.currentTimeMillis();if(!closed && sleepEnabled)Thread.sleep(1);lastSleep=System.currentTimeMillis()-sleepStart;
                }
            }catch(Throwable ex){workerFailure=ex.toString();repaint();}
            finally{release();synchronized(this){worker=null;}}
        }
        private void resetLater(){synchronized(pump){controlVersion++;resetRequested=true;}}
        private void warmText(){
            lines[0]=mode+" "+NAMES[mode]+" warming";lines[1]="I/P/G -/-/- ms "+(forceService?"s":"q")+(sleepEnabled?"1":"0");lines[2]="F/C/H/Q -/-/-/-";
            lines[3]="B/S/K/E -/-/-/-";lines[4]="I min/max -/-; P -/-";lines[5]="NO MAP T0 DC0; 1s+3s";
            lines[6]="PUMP "+(forceService?"SYNC":"QUEUED")+" H "+(fullHud?"FULL":"MIN")+" S"+(sleepEnabled?"1":"0");
            lines[7]="0/2 mode;* pump;7 HUD;9 S";lines[8]="1canvas;3image;5RGBZ;#exit";lines[9]="API wall time; not GPU time";
        }
        private static String fps(int v){return v/10+"."+v%10;}
        private static String time(int tenths){return tenths<0?"-":fps(tenths);}
        private void resultText(){
            lines[0]=mode+" "+NAMES[mode]+" FPS "+fps(stats.fps10);
            lines[1]="I/P/G "+time(stats.meanI10)+"/"+time(stats.meanP10)+"/"+time(stats.meanG10)+" ms "+(forceService?"s":"q")+(sleepEnabled?"1":"0");
            lines[2]="F/C/H/Q "+time(stats.mean10[0])+"/"+time(stats.mean10[5])+"/"+time(stats.mean10[6])+"/"+time(stats.mean10[7]);
            lines[3]="B/S/K/E "+time(stats.mean10[1])+"/"+time(stats.mean10[2])+"/"+time(stats.mean10[3])+"/"+time(stats.mean10[4]);
            lines[4]="I "+stats.minI+".."+stats.maxI+"; P "+stats.minP+".."+stats.maxP;
            lines[5]="T0 DC0; svc/sl(last) "+lastService+"/"+lastSleep;
            lines[6]="PUMP "+(forceService?"SYNC":"QUEUED")+" H "+(fullHud?"FULL":"MIN")+" S"+(sleepEnabled?"1":"0");
        }
        private void ensureImage(){if(image==null){image=Image.createImage(width,height);imageGraphics=image.getGraphics();imageGraphics.setColor(0x203040);imageGraphics.fillRect(0,0,width,height);imageAllocations++;}}
        private void work(Graphics g,int selected){
            long start;
            if(selected==0){start=System.currentTimeMillis();g.setColor(0x203040);g.fillRect(0,0,width,height);ops[0]=System.currentTimeMillis()-start;}
            else if(selected>=1 && selected<=7){
                if(selected!=7)ensureImage();
                if(selected==2){start=System.currentTimeMillis();imageGraphics.setColor(0x203040);imageGraphics.fillRect(0,0,width,height);ops[0]=System.currentTimeMillis()-start;}
                if(selected>=3){if(nativeApi==null){nativeApi=new EmptyFrameNative();nativeAllocations++;}nativeApi.frame(selected==7?g:imageGraphics,width,height,selected,ops);}
                if(selected!=7){start=System.currentTimeMillis();g.drawImage(image,0,0,20);ops[5]=System.currentTimeMillis()-start;}
            }
        }
        protected void paint(Graphics g){
            long start=System.currentTimeMillis(),queue=-1,ticket=-1;
            synchronized(pump){if(pending){queue=start-requestedAt;ticket=requestId;}}
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            boolean measured=false;int selected=mode,version=controlVersion;
            try{
                if(closed)return;
                g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());g.setFont(font);
                if(width!=getWidth() || height!=getHeight()){width=getWidth();height=getHeight();image=null;imageGraphics=null;resetLater();version=controlVersion;}
                if(resetRequested || statsVersion!=version){stats.reset(start);warmText();statsVersion=version;resetRequested=false;g.setColor(0x203040);g.fillRect(0,0,width,height);}
                if(!running || !visible){g.setColor(0xffffff);g.drawString("Paused",0,0,20);return;}
                for(int i=0;i<ops.length;i++)ops[i]=0;ops[7]=queue;
                if(workerFailure!=null){g.setColor(0xffffff);g.drawString("Pump failed; # exit",0,0,20);g.drawString(workerFailure,0,15,20);return;}
                if(failures[selected]==null){
                    try{work(g,selected);measured=true;}
                    catch(Throwable ex){failures[selected]=ex.toString();stats.reset(System.currentTimeMillis());}
                }
                long hud=System.currentTimeMillis();int lineHeight=font.getHeight()+2;
                g.setColor(0);g.fillRect(0,0,width,Math.min(height,(fullHud?10:2)*lineHeight));g.setColor(0xffffff);
                if(failures[selected]!=null){g.drawString(selected+" UNSUPPORTED / FAILED",0,0,20);g.drawString(failures[selected],0,lineHeight,20);g.drawString("0 next;2 prev;# exit",0,2*lineHeight,20);}
                else if(fullHud){for(int i=0;i<lines.length;i++)g.drawString(lines[i],0,i*lineHeight,20);}
                else{g.drawString(lines[0],0,0,20);g.drawString(lines[1],0,lineHeight,20);}
                // Same tiny changing marker in every mode, including IDLE/MIN.
                g.setColor((serial++&1)==0?0xffffff:0x203040);g.fillRect(Math.max(0,width-4),Math.max(0,height-4),4,4);
                ops[6]=System.currentTimeMillis()-hud;
            }finally{
                try{g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
                finally{
                    try{if(measured && !closed && running && visible && !resetRequested && controlVersion==version && mode==selected && stats.record(start,System.currentTimeMillis(),ops))resultText();}
                    finally{synchronized(pump){if(pending && requestId==ticket)pending=false;pump.notifyAll();}}
                }
            }
        }
        private void select(int value){mode=(value+NAMES.length)%NAMES.length;resetLater();}
        protected void keyPressed(int key){
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM0)select(mode+1);else if(key==KEY_NUM2)select(mode-1);
            else if(key==KEY_NUM1)select(0);else if(key==KEY_NUM3)select(1);else if(key==KEY_NUM5)select(5);
            else if(key==42){forceService=!forceService;resetLater();}
            else if(key==KEY_NUM7){fullHud=!fullHud;resetLater();}
            else if(key==KEY_NUM9){sleepEnabled=!sleepEnabled;resetLater();}
        }
    }
}
