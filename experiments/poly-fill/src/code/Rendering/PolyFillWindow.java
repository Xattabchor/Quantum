package code.Rendering;

/** Coarse wall-clock end-to-end throughput, NOT GPU timestamps.
 * Warmup/build/paused intervals are excluded. Results allocate once per window.
 */
final class PolyFillWindow {
    static final int WARM_MS=1000,MEASURE_MS=3000;
    long warmUntil,start=-1,last=-1,seen=-1,nativeMs,copyMs,intervalMs;
    int frames;
    void reset(long now){warmUntil=now+WARM_MS;clear();}
    void repeat(long now){warmUntil=now;clear();}
    private void clear(){start=last=seen=-1;nativeMs=copyMs=intervalMs=0;frames=0;}
    Result record(long frame,long nativeStart,long nativeEnd,long copied,long end,PolyFillMesh mesh,int width,int height) {
        if(frame>nativeStart || nativeStart>nativeEnd || nativeEnd>copied || copied>end || (seen>=0 && frame<seen)) {reset(end);return null;}
        seen=end;if(frame<warmUntil)return null;
        // First post-warm frame anchors the window but is NOT counted. Each
        // later completed frame then contributes a full presentation interval.
        if(start<0){start=end;last=frame;return null;}
        intervalMs+=frame-last;last=frame;
        nativeMs+=nativeEnd-nativeStart;copyMs+=copied-nativeEnd;frames++;
        long elapsed=end-start;if(elapsed<MEASURE_MS || frames<2)return null;
        return new Result(mesh,width,height,frames,elapsed,nativeMs,copyMs,intervalMs);
    }
    static final class Result {
        final boolean fill;
        final int level,width,height,frames,fps10,nativeMean,copyMean,intervalMean,triangles,vertices,layers;
        final long elapsed,trianglesPerSecond,nominalPixelsPerSecond;
        Result(PolyFillMesh mesh,int width,int height,int frames,long elapsed,long nativeMs,long copyMs,long intervalMs) {
            fill=mesh.fill;level=mesh.level;this.width=width;this.height=height;this.frames=frames;this.elapsed=elapsed;
            triangles=mesh.triangles;vertices=mesh.vertexCount;layers=mesh.layers;
            fps10=(int)(frames*10000L/elapsed);nativeMean=(int)(nativeMs/frames);copyMean=(int)(copyMs/frames);
            intervalMean=frames==0?0:(int)(intervalMs/frames);
            trianglesPerSecond=(long)triangles*frames*1000L/elapsed;
            nominalPixelsPerSecond=(long)width*height*layers*frames*1000L/elapsed;
        }
    }
}
