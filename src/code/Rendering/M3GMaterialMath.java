package code.Rendering;

/** Reusable material bounds and native Fog endpoints, no per-polygon allocation. */
final class M3GMaterialMath {
    private boolean boundsReady;
    long spanX,spanY;
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

    float fogFar(int move) {
        if(!fogReady || fogDistance!=DirectX7.fDist || fogMove!=move) {
            fogReady=true;
            fogDistance=DirectX7.fDist;fogMove=move;
            far=1.0f+(float)fogDistance/move;
        }
        return far;
    }

}
