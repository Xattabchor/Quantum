package code.Rendering;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Diagnostic-build-only wall-clock profiler. Never part of a normal build.
 * Exclusive nested categories: child time is not charged to its parent.
 * Millisecond clock, one-second averages; NOT a GPU hardware timer.
 */
public final class FrameProfile {
    public static final int OTHER=0, PREP=1, SUBMIT=2, API=3, READ=4,
        UPLOAD=5, BACKUP=6, PRESENT=7, HUD=8, LOGIC=9, WAIT=10, CPU=11, VERTEX=12, SORT=13, UPDATE=14, INTERACT=15, PACK=16;
    private static final int COUNT=17;
    private static final long[] frame=new long[COUNT],sum=new long[COUNT];
    private static final int[] stack=new int[16];
    private static int stage,depth,frames,level;
    private static long tick,started,ended,previousStart,window,intervalSum,gapSum;
    private static boolean active,valid;
    private static final String[] lines={"PF: warming up", "", "", "", "", ""};

    private static final String[] names={"SETUP","PREP","SUBMIT","M3G-API","READBACK",
        "UPLOAD","BACKUP","PRESENT","HUD","LOGIC","SLEEP","CPU-RASTER","VERTEX",
        "SORT","UPDATE","INTERACT","PACK"};

    private static long now() {return System.currentTimeMillis();}
    private static void charge(long time) {
        if(active) {
            if(time>=tick) frame[stage]+=time-tick;
            else valid=false;
        }
        tick=time;
    }
    public static void reset() {
        active=false;depth=0;window=previousStart=ended=0;
        frames=0;intervalSum=gapSum=0;
        for(int i=0;i<COUNT;i++) {frame[i]=0;sum[i]=0;}
        lines[0]="PF: warming up";
        for(int i=1;i<lines.length;i++) lines[i]="";
    }
    public static void begin(int levelNumber) {
        long time=now();
        boolean reset=active || window==0 || time<previousStart ||
            level!=levelNumber;
        if(reset) {
            for(int i=0;i<COUNT;i++) sum[i]=0;
            frames=0;intervalSum=gapSum=0;window=time;
            previousStart=ended=0;
            lines[0]="PF L"+levelNumber+": warming up";
            for(int i=1;i<lines.length;i++) lines[i]="";
        }
        // Publish a completed frame only when its NEXT paint begins. This pairs
        // category times with the same start-to-start interval: I = F + G.
        if(previousStart!=0 && ended>=previousStart && time>=ended) {
            for(int i=0;i<COUNT;i++) sum[i]+=frame[i];
            frames++;intervalSum+=time-previousStart;gapSum+=time-ended;
        }
        level=levelNumber;
        if(frames>0 && time-window>=1000) publish(time);
        for(int i=0;i<COUNT;i++) frame[i]=0;
        stage=OTHER;depth=0;tick=started=time;active=valid=true;
    }
    public static void push(int next) {
        if(!active) return;
        if(depth>=stack.length) throw new IllegalStateException("profile stack overflow");
        charge(now());stack[depth++]=stage;stage=next;
    }
    public static void pop() {
        if(!active) return;
        if(depth==0) throw new IllegalStateException("profile stack underflow");
        charge(now());stage=stack[--depth];
    }
    private static String average(long total,int count) {
        if(count==0) return "-";
        long value=total*10/count;
        return Long.toString(value/10)+"."+Long.toString(value%10);
    }
    private static String value(int category) {return average(sum[category],frames);}
    public static void end() {
        if(!active) return;
        long time=now();charge(time);active=false;
        if(depth!=0) throw new IllegalStateException("unbalanced profile stages");
        if(valid) {ended=time;previousStart=started;}
        else {ended=previousStart=0;}
    }
    private static void publish(long time) {
        long total=0;for(int i=0;i<COUNT;i++) total+=sum[i];
        lines[0]="PF L"+level+" n"+frames+" I"+average(intervalSum,frames)+" F"+average(total,frames)+" G"+average(gapSum,frames);
        lines[1]="P"+value(PREP)+" V"+value(VERTEX)+" M"+value(PACK)+" S"+value(SUBMIT)+" Z"+value(SORT);
        lines[2]="A"+value(API)+" R"+value(READ)+" U"+value(UPLOAD)+" K"+value(BACKUP);
        lines[3]="X"+value(PRESENT)+" C"+value(CPU)+" H"+value(HUD)+" O"+value(OTHER);
        lines[4]="L"+value(LOGIC)+" E"+value(UPDATE)+" Q"+value(INTERACT)+" W"+value(WAIT);
        int top=0;for(int i=1;i<COUNT;i++) if(sum[i]>sum[top]) top=i;
        lines[5]=gapSum>sum[top]?"TOP OUTSIDE "+average(gapSum,frames)+"ms":
            "TOP "+names[top]+" "+value(top)+"ms";
        for(int i=0;i<COUNT;i++) sum[i]=0;
        frames=0;intervalSum=gapSum=0;window=time;
    }
    public static void draw(Graphics g,int y,int width) {
        Font old=g.getFont();int color=g.getColor();
        int x=g.getClipX(),cy=g.getClipY(),w=g.getClipWidth(),h=g.getClipHeight();
        try {
            Font small=Font.getFont(Font.FACE_SYSTEM,Font.STYLE_PLAIN,Font.SIZE_SMALL);
            int line=small.getHeight();g.setFont(small);
            g.setClip(0,y,width,line*lines.length);g.setColor(0);g.fillRect(0,y,width,line*lines.length);g.setColor(0xffffff);
            for(int i=0;i<lines.length;i++) g.drawString(lines[i],1,y+i*line,Graphics.TOP|Graphics.LEFT);
        } finally {g.setFont(old);g.setColor(color);g.setClip(x,cy,w,h);}
    }
}
