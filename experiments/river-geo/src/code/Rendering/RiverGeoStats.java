package code.Rendering;

/** Three-second whole-path windows; coarse API wall time, NOT GPU timestamps.
 * Warmup and a first completed-frame anchor exclude cold/switch/pause costs.
 * Identical windows to the river pixel/upscale diagnostics, re-typed for the geo mesh. */
final class RiverGeoStats {
    int fps10,meanPrepare,meanNative,meanCopy,meanPaint,meanInterval,frames;
    int meanJ,meanB,meanK,meanR,meanE;
    private long j,b,k,r,e;
    long warmUntil,start=-1,last=-1,seen=-1,prepare,nativeTime,copy,paint,interval;
    void reset(long now){warmUntil=now+1000;clear();fps10=meanPrepare=meanNative=meanCopy=meanPaint=meanInterval=meanJ=meanB=meanK=meanR=meanE=0;}
    private void clear(){start=last=seen=-1;prepare=nativeTime=copy=paint=interval=0;frames=0;j=b=k=r=e=0;}
    boolean record(long frame,long nativeStart,long nativeEnd,long copyEnd,long end){return record(frame,nativeStart,nativeEnd,copyEnd,end,null);}
    boolean record(long frame,long nativeStart,long nativeEnd,long copyEnd,long end,RiverGeoMesh room) {
        if(frame<seen || nativeStart<frame || nativeEnd<nativeStart || copyEnd<nativeEnd || end<copyEnd){reset(end);return false;}
        seen=end;if(frame<warmUntil)return false;
        if(start<0){start=end;last=frame;return false;}
        if(room!=null){j+=room.prepareMs;b+=room.bindMs;k+=room.clearMs;r+=room.drawMs;e+=room.releaseMs;}
        interval+=frame-last;last=frame;prepare+=nativeStart-frame;nativeTime+=nativeEnd-nativeStart;copy+=copyEnd-nativeEnd;paint+=end-frame;frames++;
        long elapsed=end-start;if(elapsed<3000 || frames<2)return false;
        fps10=(int)(frames*10000L/elapsed);meanPrepare=(int)(prepare/frames);meanNative=(int)(nativeTime/frames);meanCopy=(int)(copy/frames);
        meanJ=(int)(j/frames);meanB=(int)(b/frames);meanK=(int)(k/frames);meanR=(int)(r/frames);meanE=(int)(e/frames);
        meanPaint=(int)(paint/frames);meanInterval=(int)(interval/frames);clear();warmUntil=end;seen=end;return true;
    }
}
