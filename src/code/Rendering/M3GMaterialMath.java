package code.Rendering;

/** Exact legacy material arithmetic; bounded caches, no per-polygon allocation. */
final class M3GMaterialMath {
    private boolean boundsReady;
    long spanX,spanY;
    private final int[] depths=new int[64],visibility=new int[64];
    private int fadeDistance;
    private int flatDistance,flatDivisor;
    private int fogDistance,fogMove;
    private float far;
    private boolean fogReady;

    void resetBounds() {boundsReady=false;}

    void measure(Vertex[] vertices,int count) {
        if(boundsReady) return;
        int minX=vertices[0].sx,maxX=minX,minY=vertices[0].sy,maxY=minY;
        for(int i=1;i<count;i++) {
            Vertex v=vertices[i];
            minX=Math.min(minX,v.sx);maxX=Math.max(maxX,v.sx);
            minY=Math.min(minY,v.sy);maxY=Math.max(maxY,v.sy);
        }
        spanX=(long)maxX-minX;spanY=(long)maxY-minY;boundsReady=true;
    }

    int visibility(int rz) {
        int distance=DirectX7.fDist;
        if(distance<=1 || rz>=0) return 255;
        if(rz<=-distance) return 0;
        if(fadeDistance!=distance) {
            for(int i=0;i<visibility.length;i++) visibility[i]=0;
            fadeDistance=distance;
        }
        int slot=(rz^(rz>>>4))&63;
        if(visibility[slot]!=0 && depths[slot]==rz) return visibility[slot]-1;
        int value=255-(int)(-(long)rz*255/distance);
        depths[slot]=rz;visibility[slot]=value+1;return value;
    }

    // Legacy HQ fog differs from baked lightmap fading when fDist <= 1.
    int fogVisibility(int rz) {
        if(DirectX7.fDist>1) return visibility(rz);
        return 255-clamp(-(long)rz*255/DirectX7.fDist);
    }

    private int divisor() {
        if(flatDistance!=DirectX7.fDist) {
            flatDistance=DirectX7.fDist;flatDivisor=flatDistance/255;
        }
        return flatDivisor;
    }

    int flatVisibility(int farthest) {
        int divisor=divisor();
        return divisor==0?255:255-clamp(-(long)farthest/divisor);
    }

    int additiveAmount(int farthest) {
        int divisor=divisor();
        return clamp(-(long)farthest/(divisor!=0?divisor:255));
    }

    float fogFar(int move) {
        if(!fogReady || fogDistance!=DirectX7.fDist || fogMove!=move) {
            fogReady=true;
            fogDistance=DirectX7.fDist;fogMove=move;
            far=1.0f+(float)fogDistance/move;
        }
        return far;
    }

    static int shade(int value,int visible) {
        if(value<0) value=0;else if(value>255) value=255;
        int product=value*visible; // Both operands are in 0..255.
        // Exact floor(product/255) for 0..65025, not an approximate reciprocal.
        return (product+1+(product>>>8))>>>8;
    }

    private static int clamp(long value) {return (int)Math.max(0L,Math.min(255L,value));}
}
