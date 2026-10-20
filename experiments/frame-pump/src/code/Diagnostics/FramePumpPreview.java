package code.Diagnostics;

import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.midlet.MIDlet;

/** Separate S0 presentation experiment. No M3G, maps, or game changes. */
public final class FramePumpPreview extends MIDlet implements Runnable {
    private static final String[] NAMES={"WORKER CANVAS","SELF CANVAS","GAME FLUSH"};
    private final Object gate=new Object(),frame=new Object();
    private final FramePumpStats stats=new FramePumpStats();
    private final long[] ops=new long[FramePumpStats.COUNT];
    private final String[] lines=new String[9];
    private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
    private volatile boolean running,visible,closed;
    private volatile int mode,version;
    private volatile Thread worker;
    private boolean full=true;
    private int measuredVersion=-1,width,height,serial;
    private String failure;
    private PaintView canvas;
    private FlushView game;

    protected void startApp(){
        synchronized(frame){
            if(closed)return;
            if(canvas==null)canvas=new PaintView(this);
            running=true;version++;
            if(worker==null){worker=new Thread(this);worker.start();}
        }
        display();wake();
    }
    protected void pauseApp(){suspend();}
    protected void destroyApp(boolean unconditional){close();}
    private void suspend(){synchronized(frame){running=false;version++;}wake();}
    private void close(){synchronized(frame){closed=true;running=false;version++;}wake();}
    private void wake(){synchronized(gate){gate.notifyAll();}}
    private void display(){
        Canvas target;
        synchronized(frame){
            if(mode==2 && game==null)game=new FlushView(this);
            target=mode==2?(Canvas)game:canvas;
            // Actual show/hide callbacks establish visibility after a switch.
            visible=false;
        }
        Display.getDisplay(this).setCurrent(target);
        // When the same Canvas remains current, there need not be showNotify.
        synchronized(frame){visible=target.isShown();}
        kick();
    }
    private void kick(){
        synchronized(frame){if(active() && mode==1)canvas.request();}
        wake();
    }
    private boolean active(){return running && visible && !closed;}
    private void visibility(Canvas source,boolean shown){
        synchronized(frame){
            if(source!=(mode==2?(Canvas)game:canvas))return;
            visible=shown;version++;
            if(!shown && canvas!=null)canvas.pending=false;
        }
        kick();
    }
    private void key(int key){
        if(key==35){close();notifyDestroyed();return;}
        synchronized(frame){
            int next=mode;
            if(key==48)next=(mode+1)%3;
            else if(key==49)next=0;
            else if(key==51)next=1;
            else if(key==53)next=2;
            else if(key==55)full=!full;
            else return;
            if(failure!=null)running=true;
            mode=next;version++;failure=null;
            if(canvas!=null)canvas.pending=false;
        }
        display();
    }
    public void run(){
        try{
            while(!closed){
                synchronized(gate){
                    while(!closed && (!active() || mode==1 || (mode==0 && canvas.pending)))gate.wait();
                }
                if(closed)break;
                synchronized(frame){
                    if(!active())continue;
                    if(mode==0)canvas.request();
                    else if(mode==2)flushFrame();
                }
            }
        }catch(Throwable ex){
            synchronized(frame){failure=ex.toString();running=false;}
            if(canvas!=null)canvas.repaint();
        }finally{worker=null;}
    }
    private static String tenth(int value){return value<0?"-":value/10+"."+value%10;}
    private void text(boolean result){
        lines[0]=mode+" "+NAMES[mode]+(result?" FPS "+tenth(stats.fps10):" warming");
        lines[1]=result?"I/P/G "+tenth(stats.meanI10)+"/"+tenth(stats.meanP10)+"/"+tenth(stats.meanG10):"I/P/G -/-/- ms";
        lines[2]=result?"F/U/H/Q "+tenth(stats.mean10[0])+"/"+(mode==2?tenth(stats.mean10[4]):"-")+"/"+tenth(stats.mean10[6])+"/"+tenth(stats.mean10[7]):"F/U/H/Q -/-/-/-";
        lines[3]=result?"I "+stats.minI+".."+stats.maxI+" P "+stats.minP+".."+stats.maxP:"1s warm + 3s window";
        lines[4]="S0 NO sleep; H "+(full?"FULL":"MIN");
        lines[5]="1 worker;3 self;5 flush";
        lines[6]="0 next;7 HUD;# exit";
        lines[7]=mode==2?"P includes flush; Q n/a":"P ends before paint return";
        lines[8]="Wall time, NOT LCD flips";
    }
    private void prepare(long start,int w,int h){
        if(measuredVersion!=version || width!=w || height!=h){
            width=w;height=h;measuredVersion=version;stats.reset(start);text(false);
        }
        for(int i=0;i<ops.length;i++)ops[i]=0;
        ops[7]=-1;
    }
    private void draw(Graphics g){
        long begin=System.currentTimeMillis();
        g.setColor(0x203040);g.fillRect(0,0,width,height);
        ops[0]=System.currentTimeMillis()-begin;begin=System.currentTimeMillis();
        g.setFont(font);int lh=font.getHeight()+2;
        g.setColor(0);g.fillRect(0,0,width,Math.min(height,(full?lines.length:2)*lh));g.setColor(0xffffff);
        if(failure!=null){g.drawString("FAILED: "+failure,0,0,20);}
        else for(int i=0;i<(full?lines.length:2);i++)g.drawString(lines[i],0,i*lh,20);
        g.setColor((serial++&1)==0?0xffffff:0x203040);g.fillRect(Math.max(0,width-4),Math.max(0,height-4),4,4);
        ops[6]=System.currentTimeMillis()-begin;
    }
    private void completed(long start,long end){if(failure==null && stats.record(start,end,ops))text(true);}
    private void paintFrame(Graphics g){
        synchronized(frame){
            long start=System.currentTimeMillis(),q=canvas.pending?start-canvas.requestedAt:-1;
            canvas.pending=false;
            if(mode==2 || !active())return;
            Font saved=g.getFont();int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try{
                prepare(start,canvas.getWidth(),canvas.getHeight());ops[7]=q;
                g.translate(-tx,-ty);g.setClip(0,0,width,height);draw(g);
            }finally{
                g.setFont(saved);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);
            }
            completed(start,System.currentTimeMillis());
            // SELF: request directly here, no worker completion handshake.
            if(mode==1 && active())canvas.request();
        }
        if(mode==0)wake();
    }
    private void flushFrame(){
        long start=System.currentTimeMillis();
        prepare(start,game.getWidth(),game.getHeight());
        try{
            Graphics g=game.buffer();g.translate(-g.getTranslateX(),-g.getTranslateY());g.setClip(0,0,width,height);
            draw(g);long before=System.currentTimeMillis();game.flushGraphics();ops[4]=System.currentTimeMillis()-before;
            completed(start,System.currentTimeMillis());
        }catch(Throwable ex){
            failure=ex.toString();running=false;
            Alert alert=new Alert("Flush FAILED",failure,null,AlertType.ERROR);
            alert.setTimeout(Alert.FOREVER);Display.getDisplay(this).setCurrent(alert,game);
            // Do not silently substitute Canvas or count failed flushes as FPS.
        }
    }
    private static final class PaintView extends Canvas {
        private final FramePumpPreview app;
        volatile boolean pending;
        long requestedAt;
        PaintView(FramePumpPreview a){app=a;setFullScreenMode(true);}
        void request(){if(!pending){pending=true;requestedAt=System.currentTimeMillis();repaint();}}
        protected void paint(Graphics g){app.paintFrame(g);}
        protected void showNotify(){app.visibility(this,true);}
        protected void hideNotify(){app.visibility(this,false);}
        protected void sizeChanged(int w,int h){synchronized(app.frame){app.version++;}app.kick();}
        protected void keyPressed(int k){app.key(k);}
    }
    private static final class FlushView extends GameCanvas {
        private final FramePumpPreview app;
        FlushView(FramePumpPreview a){super(false);app=a;setFullScreenMode(true);}
        Graphics buffer(){return getGraphics();}
        protected void showNotify(){app.visibility(this,true);}
        protected void hideNotify(){app.visibility(this,false);}
        protected void sizeChanged(int w,int h){synchronized(app.frame){app.version++;}app.wake();}
        protected void keyPressed(int k){app.key(k);}
    }
}
