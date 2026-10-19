package code.Rendering;

/** Coarse wall-clock timings, not GPU timestamps. No per-frame arrays/strings.
 * P=before render, N=entire nativeRoom.render (including Java prep + release),
 * C=LCDUI copy, F=paint including HUD, I=paint-start interval incl. scheduling.
 */
final class StaticRoomFrameStats {
    int fps,meanPrepare,meanNative,meanCopy,meanPaint,meanInterval;
    private long since,lastStart,prepare,nativeTime,copy,paint,interval;
    private int frames,intervals;

    void reset(long now) {
        since=now;lastStart=-1;prepare=nativeTime=copy=paint=interval=0;
        frames=intervals=fps=meanPrepare=meanNative=meanCopy=meanPaint=meanInterval=0;
    }
    boolean record(long start,long nativeStart,long nativeEnd,long copyEnd,long end) {
        if(start<since || (lastStart>=0 && start<lastStart) || nativeStart<start || nativeEnd<nativeStart || copyEnd<nativeEnd || end<copyEnd) {
            reset(end);return false;
        }
        if(lastStart>=0){interval+=start-lastStart;intervals++;}
        lastStart=start;prepare+=nativeStart-start;nativeTime+=nativeEnd-nativeStart;
        copy+=copyEnd-nativeEnd;paint+=end-start;frames++;
        long window=end-since;
        if(window<1000)return false;
        fps=(int)(frames*1000L/window);
        meanPrepare=(int)(prepare/frames);meanNative=(int)(nativeTime/frames);
        meanCopy=(int)(copy/frames);meanPaint=(int)(paint/frames);
        meanInterval=intervals==0?0:(int)(interval/intervals);
        since=end;prepare=nativeTime=copy=paint=interval=0;frames=intervals=0;return true;
    }
}
