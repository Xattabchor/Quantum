package code.Rendering;

/** Three-second whole-path windows; coarse API wall time, NOT GPU timestamps.
 * Warmup and a first completed-frame anchor exclude cold/switch/pause costs.
 */
final class L2DMapStats {
    int fps10,meanPrepare,meanNative,meanCopy,meanPaint,meanInterval,frames;
    long warmUntil,start=-1,last=-1,seen=-1,prepare,nativeTime,copy,paint,interval;
    void reset(long now){warmUntil=now+1000;clear();fps10=meanPrepare=meanNative=meanCopy=meanPaint=meanInterval=0;}
    private void clear(){start=last=seen=-1;prepare=nativeTime=copy=paint=interval=0;frames=0;}
    boolean record(long frame,long nativeStart,long nativeEnd,long copyEnd,long end){return recordWindow(frame,nativeStart,nativeEnd,copyEnd,end);}
    boolean recordWindow(long frame,long nativeStart,long nativeEnd,long copyEnd,long end) {
        if(frame<seen || nativeStart<frame || nativeEnd<nativeStart || copyEnd<nativeEnd || end<copyEnd){reset(end);return false;}
        seen=end;if(frame<warmUntil)return false;
        if(start<0){start=end;last=frame;return false;}
        interval+=frame-last;last=frame;prepare+=nativeStart-frame;nativeTime+=nativeEnd-nativeStart;copy+=copyEnd-nativeEnd;paint+=end-frame;frames++;
        long elapsed=end-start;if(elapsed<3000 || frames<2)return false;
        fps10=(int)(frames*10000L/elapsed);meanPrepare=(int)(prepare/frames);meanNative=(int)(nativeTime/frames);meanCopy=(int)(copy/frames);
        meanPaint=(int)(paint/frames);meanInterval=(int)(interval/frames);clear();warmUntil=end;seen=end;return true;
    }
}
