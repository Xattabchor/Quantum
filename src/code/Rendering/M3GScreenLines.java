package code.Rendering;

import javax.microedition.m3g.*;

/** 32 independent four-vertex strips per native call, not a draw per raindrop. */
final class M3GScreenLines {
    private static final int SEGMENTS=32, VERTICES=SEGMENTS*4;
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final VertexBuffer buffer=new VertexBuffer();
    private final VertexArray positions=new VertexArray(VERTICES,3,2);
    private final VertexArray colors=new VertexArray(VERTICES,3,1);
    private final short[] packed=new short[VERTICES*3];
    private final byte[] rgb=new byte[VERTICES*3];
    private final TriangleStripArray indices;
    private final Appearance appearance=new Appearance();
    private final ScreenLineGeometry geometry=new ScreenLineGeometry();

    M3GScreenLines(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        int[] verts=new int[VERTICES],lengths=new int[SEGMENTS];
        for(int i=0;i<VERTICES;i++) verts[i]=i;
        for(int i=0;i<SEGMENTS;i++) lengths[i]=4;
        indices=new TriangleStripArray(verts,lengths);
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        owner.registerPolygonMode(polygon);
        appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setBlending(CompositingMode.REPLACE);composite.setAlphaThreshold(0);
        appearance.setCompositingMode(composite);appearance.setFog(null);
        // RGB implies alpha=1 in M3G. No texture, UV, sampling or alpha blending.
        buffer.setColors(colors);
    }

    boolean draw(DirectX7 g,ScreenLines lines) {
        int max=Math.max(g.width,g.height);
        for(int i=0;i<lines.count;i++)
            if(lines.get(i,4)>max*2) return false; // Whole-batch fallback before any drawing.
        int bound=4*max+Math.max(Math.abs(g.centreX),Math.abs(g.centreY))+8;
        int precision=Math.min(16,30000/Math.max(1,bound));
        if(precision<1) return false;
        int size=0;
        for(int i=0;i<lines.count;i++) {
            if(!geometry.build(lines,i,g.width,g.height)) continue;
            int color=lines.get(i,5);
            for(int v=0;v<4;v++) {
                int p=(size*4+v)*3,c=(size*4+v)*3;
                packed[p]=round((geometry.xy[v*2]-g.centreX)*precision);
                packed[p+1]=round((g.centreY-geometry.xy[v*2+1])*precision);
                packed[p+2]=(short)-precision;
                rgb[c]=(byte)(color>>16);rgb[c+1]=(byte)(color>>8);rgb[c+2]=(byte)color;
            }
            if(++size==SEGMENTS) {render(g,precision);size=0;}
        }
        if(size>0) {
            // Fixed-size index buffer: unused strips are degenerate, not bridges
            // between particles and not stale geometry from the previous batch.
            for(int i=size*4;i<VERTICES;i++) {
                packed[i*3]=packed[i*3+1]=0;packed[i*3+2]=(short)-precision;
            }
            render(g,precision);
        }
        return true;
    }
    private void render(DirectX7 g,int precision) {
        positions.set(0,VERTICES,packed);colors.set(0,VERTICES,rgb);
        buffer.setPositions(positions,1.0f/precision,null);
        owner.bind(g);owner.restoreCamera(g);
        owner.renderNative(g,buffer,indices,appearance);
    }
    private static short round(float value) {
        int v=(int)(value+(value<0?-0.5f:0.5f));
        if(v < -32767 || v > 32767) throw new IllegalArgumentException("screen line overflow");
        return (short)v;
    }
}
