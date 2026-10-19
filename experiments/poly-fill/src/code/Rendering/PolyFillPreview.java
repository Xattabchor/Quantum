package code.Rendering;

import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;
import javax.microedition.m3g.Texture2D;

/** Standalone Quantum native-path microbenchmark, not a game map or GPU spec. */
public final class PolyFillPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}

    static String decimal(long value,long unit){return (value/unit)+"."+((value%unit)*10/unit);}

    static String hundredths(long value,long unit){long part=(value%unit)*100/unit;return (value/unit)+"."+(part<10?"0":"")+part;}

    private static final class Viewer extends Canvas implements Runnable {
        private final PolyFillPreview app;
        private final Font font=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        private volatile boolean running,closed,visible,resetRequested=true,showResults;
        private volatile String error;
        private volatile Thread worker;
        private Texture2D texture;
        private PolyFillMesh work;
        private Image surface;
        private Graphics target;
        private int width,height,areaHeight;
        private boolean fill,automatic,dirty=true;
        private int level=3,polyLevel=3,fillLevel,autoSlot,page;
        private final PolyFillWindow window=new PolyFillWindow();
        private final PolyFillWindow.Result[] results=new PolyFillWindow.Result[PolyFillMesh.total()];
        private final String[] failures=new String[PolyFillMesh.total()];
        private PolyFillWindow.Result latest;
        private long nextHud;
        private String heading="Quantum POLY / FILL",timing="FPS - N - C - I -",rate="5 = full auto sweep",phase="Warming";

        Viewer(PolyFillPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;resetRequested=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;resetRequested=true;}
        protected void hideNotify(){visible=false;resetRequested=true;}
        protected void showNotify(){visible=true;resetRequested=true;}
        void close(){closed=true;running=false;if(worker==null)release();}
        private void release(){if(work!=null)work.dispose();work=null;texture=null;surface=null;target=null;}
        public void run() {
            try {
                while(!closed){if(running && visible){repaint();serviceRepaints();}Thread.sleep(running && visible && !showResults && error==null?1:100);}
            }catch(InterruptedException ex){closed=true;}
            finally{release();synchronized(this){worker=null;}}
        }
        private void clearResults(){for(int i=0;i<results.length;i++){results[i]=null;failures[i]=null;}page=0;}
        private void select(boolean useFill,int index) {
            fill=useFill;level=Math.max(0,Math.min(PolyFillMesh.levels(fill)-1,index));
            if(fill)fillLevel=level;else polyLevel=level;
            dirty=work==null || work.fill!=fill || work.level!=level;latest=null;error=null;showResults=false;resetRequested=true;nextHud=0;
        }
        private void selectAuto(int index){autoSlot=index;select(index>=PolyFillMesh.GRIDS.length,index>=PolyFillMesh.GRIDS.length?index-PolyFillMesh.GRIDS.length:index);}
        private void startAuto(){clearResults();automatic=true;selectAuto(0);}
        private void advance() {
            if(autoSlot+1<PolyFillMesh.total())selectAuto(autoSlot+1);
            else {automatic=false;showResults=true;page=0;}
        }
        private void viewport() {
            int w=getWidth(),h=getHeight();
            if(w==width && h==height)return;
            if(w<1 || h<=90)throw new IllegalArgumentException("screen too small");
            boolean changed=width!=0;width=w;height=h;areaHeight=h-90;
            surface=null;target=null;surface=Image.createImage(width,areaHeight);target=surface.getGraphics();
            if(changed){clearResults();automatic=false;showResults=false;latest=null;}
            resetRequested=true;nextHud=0;
        }
        private void prepare() {
            if(texture==null)texture=PolyFillMesh.checker();
            if(dirty) {
                // Drop the previous native buffers before allocating a larger
                // workload. This build/allocation is OUTSIDE measured frames.
                if(work!=null)work.dispose();work=null;
                work=new PolyFillMesh(fill,level,texture);dirty=false;resetRequested=true;
            }
            if(resetRequested){window.reset(System.currentTimeMillis());resetRequested=false;nextHud=0;}
        }
        private void hud(Graphics g,long now) {
            if(now>=nextHud) {
                heading=fill?"FILL x"+work.layers+" T"+work.triangles+" Z OFF":"POLY T"+work.triangles+" V"+work.vertexCount;
                timing=latest==null?"FPS - N - C - I -":"FPS "+decimal(latest.fps10,10)+" N "+latest.nativeMean+" C "+latest.copyMean+" I "+latest.intervalMean;
                rate=latest==null?"1 draw; opaque 64x64 nearest":fill?hundredths(latest.nominalPixelsPerSecond,1000000)+" Mpix/s nominal":decimal(latest.trianglesPerSecond,1000)+" Ktri/s";
                if(now<window.warmUntil)phase="Warm "+decimal(window.warmUntil-now,1000)+"s";
                else phase="Measure "+decimal(window.start<0?0:now-window.start,1000)+"/3s";
                if(automatic)phase+=" auto "+(autoSlot+1)+"/"+PolyFillMesh.total();
                nextHud=now+500;
            }
            g.setColor(0);g.fillRect(0,0,width,60);g.fillRect(0,height-30,width,30);g.setColor(0xffffff);
            g.drawString(heading,0,0,20);g.drawString(timing,0,15,20);g.drawString(rate,0,30,20);g.drawString(phase,0,45,20);
            g.drawString("1 poly 3 fill 2+/8- 0 reset",0,height-30,20);g.drawString("5 auto 7 results # exit",0,height-15,20);
        }
        private void table(Graphics g) {
            g.setColor(0);g.fillRect(0,0,width,height);g.setColor(0xffffff);
            g.drawString("RESULTS "+width+"x"+areaHeight+" nominal",0,0,20);
            int rows=Math.max(1,(height-60)/15),pages=(PolyFillMesh.total()+rows-1)/rows;page=Math.max(0,Math.min(page,pages-1));
            g.drawString("P:tri Ktri/s  F:layers Mpix/s",0,15,20);
            for(int row=0;row<rows;row++) {
                int slot=page*rows+row;if(slot>=results.length)break;
                boolean isFill=slot>=PolyFillMesh.GRIDS.length;int index=isFill?slot-PolyFillMesh.GRIDS.length:slot;
                int count=isFill?PolyFillMesh.LAYERS[index]:2*PolyFillMesh.GRIDS[index]*PolyFillMesh.GRIDS[index];
                String line=(isFill?"F ":"P ")+count+" ";PolyFillWindow.Result r=results[slot];
                if(r!=null)line+=decimal(r.fps10,10)+"fps "+(isFill?hundredths(r.nominalPixelsPerSecond,1000000):decimal(r.trianglesPerSecond,1000))+(isFill?"M":"K")+" N"+r.nativeMean;
                else line+=failures[slot]==null?"not measured":failures[slot];
                g.drawString(line,0,30+row*15,20);
            }
            g.drawString("4/6 page 7 back 5 rerun # exit",0,height-15,20);
        }
        private void failure(Throwable ex) {
            int slot=PolyFillMesh.slot(fill,level);results[slot]=null;failures[slot]=ex instanceof OutOfMemoryError?"OOM":"ERR";
            error=ex instanceof OutOfMemoryError?"Out of memory":ex.toString();
            if(work!=null)work.dispose();work=null;dirty=true;
            if(automatic){error=null;System.gc();advance();}
        }
        protected void paint(Graphics g) {
            if(closed)return;
            if(!running || !visible){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Paused",0,0,20);return;}
            Font savedFont=g.getFont();
            int tx=g.getTranslateX(),ty=g.getTranslateY(),cx=g.getClipX(),cy=g.getClipY(),cw=g.getClipWidth(),ch=g.getClipHeight();
            try {
                g.setFont(font);g.translate(-tx,-ty);g.setClip(0,0,getWidth(),getHeight());viewport();
                if(showResults){table(g);return;}
                if(error!=null) {
                    g.setColor(0);g.fillRect(0,0,width,height);g.setColor(0xffffff);g.drawString("Failed: "+error,0,0,20);
                    g.drawString("8 lower; 1 poly; 3 fill",0,20,20);g.drawString("5 auto / 7 results / # exit",0,40,20);return;
                }
                prepare();long frame=System.currentTimeMillis(),nativeStart=System.currentTimeMillis();
                work.render(target,width,areaHeight);long nativeEnd=System.currentTimeMillis();
                g.drawImage(surface,0,60,20);long copied=System.currentTimeMillis();hud(g,copied);
                PolyFillWindow.Result completed=window.record(frame,nativeStart,nativeEnd,copied,System.currentTimeMillis(),work,width,areaHeight);
                if(completed!=null) {
                    int slot=PolyFillMesh.slot(fill,level);results[slot]=completed;failures[slot]=null;latest=completed;nextHud=0;
                    System.out.println("POLY_FILL_RESULT mode="+(fill?"FILL":"POLY")+" level="+level+" size="+width+"x"+areaHeight+" T="+completed.triangles+" V="+completed.vertices+" layers="+completed.layers+" frames="+completed.frames+" ms="+completed.elapsed+" fps10="+completed.fps10+" N="+completed.nativeMean+" C="+completed.copyMean+" I="+completed.intervalMean+" tri/s="+completed.trianglesPerSecond+" nominalPix/s="+completed.nominalPixelsPerSecond);
                    if(automatic)advance();else window.repeat(System.currentTimeMillis());
                }
            }catch(Throwable ex){failure(ex);}
            finally{g.setFont(savedFont);g.translate(tx-g.getTranslateX(),ty-g.getTranslateY());g.setClip(cx,cy,cw,ch);}
        }
        protected void keyPressed(int key) {
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM5){if(automatic){automatic=false;showResults=true;}else startAuto();return;}
            if(key==KEY_NUM7){automatic=false;showResults=!showResults;resetRequested=true;return;}
            if(showResults && (key==KEY_NUM4 || key==KEY_NUM6)){page+=key==KEY_NUM4?-1:1;return;}
            if(key==KEY_NUM1){automatic=false;select(false,polyLevel);}
            else if(key==KEY_NUM3){automatic=false;select(true,fillLevel);}
            else if(key==KEY_NUM2 || key==KEY_NUM8){automatic=false;select(fill,level+(key==KEY_NUM2?1:-1));}
            else if(key==KEY_NUM0){automatic=false;error=null;showResults=false;resetRequested=true;latest=null;nextHud=0;}
        }
    }
}
