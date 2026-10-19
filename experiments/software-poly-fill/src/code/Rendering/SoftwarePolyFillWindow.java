package code.Rendering;

/** Coarse wall-clock end-to-end throughput, software pipeline, NOT a GPU timer.
 * Warmup/build/paused intervals are excluded. Results allocate once per window.
 */
final class SoftwarePolyFillWindow {
    static final int WARM_MS=1000,MEASURE_MS=3000;
    long warmUntil,start=-1,last=-1,seen=-1,renderMs,copyMs,intervalMs;
    int frames;
    void reset(long now){warmUntil=now+WARM_MS;clear();}
    void repeat(long now){warmUntil=now;clear();}
    private void clear(){start=last=seen=-1;renderMs=copyMs=intervalMs=0;frames=0;}
    Result record(long frame,long renderStart,long renderEnd,long copied,long end,SoftwarePolyFillMesh mesh,int width,int height) {
        if(frame>renderStart || renderStart>renderEnd || renderEnd>copied || copied>end || (seen>=0 && frame<seen)) {reset(end);return null;}
        seen=end;if(frame<warmUntil)return null;
        // First post-warm frame anchors the window but is NOT counted. Each
        // later completed frame then contributes a full presentation interval.
        if(start<0){start=end;last=frame;return null;}
        intervalMs+=frame-last;last=frame;
        renderMs+=renderEnd-renderStart;copyMs+=copied-renderEnd;frames++;
        long elapsed=end-start;if(elapsed<MEASURE_MS || frames<2)return null;
        return new Result(mesh,width,height,frames,elapsed,renderMs,copyMs,intervalMs);
    }
    static final class Result {
        final boolean fill;
        final int level,width,height,frames,fps10,renderMean,copyMean,intervalMean,triangles,vertices,layers;
        final long elapsed,trianglesPerSecond,nominalPixelsPerSecond;
        Result(SoftwarePolyFillMesh mesh,int width,int height,int frames,long elapsed,long renderMs,long copyMs,long intervalMs) {
            fill=mesh.fill;level=mesh.level;this.width=width;this.height=height;this.frames=frames;this.elapsed=elapsed;
            triangles=mesh.triangles;vertices=mesh.vertexCount;layers=mesh.layers;
            fps10=(int)(frames*10000L/elapsed);renderMean=(int)(renderMs/frames);copyMean=(int)(copyMs/frames);
            intervalMean=frames==0?0:(int)(intervalMs/frames);
            trianglesPerSecond=(long)triangles*frames*1000L/elapsed;
            nominalPixelsPerSecond=(long)width*height*layers*frames*1000L/elapsed;
        }
    }
}
