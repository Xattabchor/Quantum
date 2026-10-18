package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import javax.microedition.m3g.*;

/** Native mesh sky: clipping is CPU geometry work, texturing is entirely M3G. */
final class M3GSkyRenderer {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final javax.microedition.m3g.Camera camera = new javax.microedition.m3g.Camera();
    private final Transform projection = new Transform();
    private final float[] matrix = new float[16];
    private final Appearance appearance = new Appearance();
    private final CompositingMode composite = new CompositingMode();
    private final VertexBuffer buffer = new VertexBuffer();
    private static final int MAX_VERTICES = 192; // 64 ordered triangles, bounded CLDC memory.
    private final VertexArray positions = new VertexArray(MAX_VERTICES,3,2), uv = new VertexArray(MAX_VERTICES,2,2);
    private final TriangleStripArray[] indices = new TriangleStripArray[MAX_VERTICES/3];
    private final short[] packed = new short[MAX_VERTICES*3], coords = new short[MAX_VERTICES*2];
    private int size, batchMode;
    private RawImage batchRaw;
    private float batchUVScale;
    private final SkyGeometry clipper = new SkyGeometry();
    private final RenderObjectBuffer ordered = new RenderObjectBuffer();
    private float scale;
    private int x1,y1,x2,y2;

    M3GSkyRenderer(M3GSceneRenderer owner, Graphics3D graphics) {
        this.owner = owner; this.graphics = graphics;
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        owner.registerPolygonMode(polygon);
        appearance.setPolygonMode(polygon);
        composite.setDepthTestEnable(false); composite.setDepthWriteEnable(false);
        appearance.setCompositingMode(composite);
        buffer.setDefaultColor(0xffffffff);
    }

    boolean draw(DirectX7 g, SkyMesh sky) {
        x1 = Math.max(0,sky.x1); y1 = Math.max(0,sky.y1);
        x2 = Math.min(g.width,sky.x2); y2 = Math.min(g.height,sky.y2);
        if(x2 <= x1 || y2 <= y1) return true;
        if(g.distX <= 0 || g.distY <= 0) return false;
        RenderObject[] polygons = sky.mesh.getPolygons();
        MultyTexture materials = sky.mesh.getTexture();
        // Validate the ENTIRE mesh before the first native draw. Unsupported
        // legacy materials keep their original whole-mesh software behavior.
        for(int i = 0; i < polygons.length; i++) {
            RenderObject p = polygons[i]; Class type = p.getClass();
            if(type != Polygon3V.class && type != Polygon4V.class
                    && type != LightedPolygon3V.class && type != LightedPolygon4V.class
                    && type != ColorLightedPolygon3V.class && type != ColorLightedPolygon4V.class) return false;
            int material = p instanceof Polygon3V ? ((Polygon3V)p).tex : ((Polygon4V)p).tex;
            if(materials == null || materials.textures == null || material < 0 || material >= materials.textures.length) return false;
            Texture t = materials.textures[material];
            if(t == null || !owner.supportsTexture(t.rImg)) return false;
            int mode=DirectX7.m3gDrawMode(t.drawmode);
            if(mode!=0 && mode!=1 && mode!=2 && mode!=4 && mode!=5 && mode!=7 && mode!=10) return false;
        }
        float far = g.distY;
        Vertex[] vertices = sky.mesh.getVertices();
        for(int i = 0; i < vertices.length; i++) {
            vertices[i].transform(sky.matrix);
            far = Math.max(far, g.distY - (float)vertices[i].rz);
        }
        // One scale for the entire sky, NOT per triangle. Shared vertices and
        // canonical clipping intersections then have identical short positions.
        float bound = Math.max(1.0f,Math.max(
                2.0f*Math.max(Math.abs((float)g.centreX),Math.abs((float)g.width-g.centreX))/g.width,
                2.0f*Math.max(Math.abs((float)g.centreY),Math.abs((float)g.height-g.centreY))/g.height));
        scale = far * bound / 30000.0f;
        // Large sky models are not automatically a CPU fallback: their near
        // vertices are usually offscreen. Check the ACTUALLY clipped weights
        // only when the conservative near-plane bound is insufficient.
        if(scale > g.distY / 64.0f && !stable(g,polygons)) return false;
        matrix[0] = matrix[5] = 1;
        matrix[2] = -(2.0f*g.centreX/g.width-1);
        matrix[6] = -(1-2.0f*g.centreY/g.height);
        matrix[10] = matrix[14] = -1; matrix[11] = -0.02f;
        projection.set(matrix); camera.setGeneric(projection);
        try {
            for(int i = 0; i < polygons.length; i++) {
                RenderObject p = polygons[i];
                if(p instanceof Polygon3V) {
                    Polygon3V t = (Polygon3V)p; p.sz = t.a.rz+t.b.rz+t.c.rz;
                } else {
                    Polygon4V q = (Polygon4V)p; p.sz = q.a.rz+q.b.rz+q.c.rz+q.d.rz;
                }
                if(p.ny > 4000 || p.ny < -4000) p.sz += Main.floorOffsetSZ;
                int material = p instanceof Polygon3V ? ((Polygon3V)p).tex : ((Polygon4V)p).tex;
                ordered.addRenderObjectDT(p,materials.textures[material],x1,y1,x2,y2);
            }
            ordered.sort(0,ordered.getSize()-1);
            TMPElement[] items = ordered.getBuffer();
            for(int i = ordered.getSize()-1; i >= 0; i--) {
                RenderObject p = items[i].obj; Texture t = items[i].tex;
                RawImage raw = t.rImg;
                int mode=DirectX7.m3gDrawMode(t.drawmode);
                // Flush BEFORE a different material can mutate a cached texture
                // or cause an upload barrier. Never reorder transparent faces.
                if(batchRaw!=raw || batchMode!=mode) {
                    flush(g);
                    batchRaw=raw; batchMode=mode;
                    boolean alpha = mode == 1 || (mode == 4 && raw.alphaMixing);
                    Texture2D texture = owner.texture(g,raw,mode==2?M3GSceneRenderer.ADD_TEXTURE
                            :alpha ? M3GSceneRenderer.ALPHA_TEXTURE : M3GSceneRenderer.OPAQUE_TEXTURE);
                    texture.setBlending(mode==5?Texture2D.FUNC_BLEND:Texture2D.FUNC_MODULATE);
                    if(mode==5) texture.setBlendColor(DirectX7.getM3GBlendColor());
                    appearance.setTexture(0,texture); appearance.setFog(null);
                    composite.setBlending(mode==2?CompositingMode.ALPHA_ADD
                            :mode==7?CompositingMode.MODULATE:mode==10?CompositingMode.MODULATE_X2
                            :alpha ? CompositingMode.ALPHA : CompositingMode.REPLACE);
                    composite.setAlphaThreshold(alpha ? 0 : owner.textureCutoutThreshold());
                }
                if(p instanceof Polygon3V) {
                    Polygon3V a = (Polygon3V)p;
                    triangle(g,raw,a.a,a.au,a.av,a.b,a.bu,a.bv,a.c,a.cu,a.cv);
                } else {
                    Polygon4V a = (Polygon4V)p;
                    triangle(g,raw,a.a,a.au,a.av,a.b,a.bu,a.bv,a.d,a.du,a.dv);
                    triangle(g,raw,a.b,a.bu,a.bv,a.c,a.cu,a.cv,a.d,a.du,a.dv);
                }
            }
            flush(g);
        } finally {
            // A failed native call is replayed atomically by DirectX7. Discard
            // pending geometry even when failure happened before the last flush.
            size=0; batchRaw=null;
            ordered.resetTex(); ordered.reset();
            // Restore the ordinary camera/viewport even if the next queue entry
            // is a level polygon. No extra framebuffer readback is needed here.
            owner.restoreCamera(g);
        }
        return true;
    }

    private boolean stable(DirectX7 g, RenderObject[] polygons) {
        for(int i = 0; i < polygons.length; i++) {
            if(polygons[i] instanceof Polygon3V) {
                Polygon3V p = (Polygon3V)polygons[i];
                if(!stableTriangle(g,p.a,p.b,p.c)) return false;
            } else {
                Polygon4V p = (Polygon4V)polygons[i];
                if(!stableTriangle(g,p.a,p.b,p.d) || !stableTriangle(g,p.b,p.c,p.d)) return false;
            }
        }
        return true;
    }

    private boolean stableTriangle(DirectX7 g, Vertex a, Vertex b, Vertex c) {
        clipper.clip(g,a,0,0,b,0,0,c,0,0,x1,y1,x2,y2);
        for(int i = 0; i < clipper.count; i++)
            if(clipper.polygon[i*5+2] < scale*64) return false;
        return true;
    }

    private void triangle(DirectX7 g, RawImage raw, Vertex a,int au,int av,
            Vertex b,int bu,int bv,Vertex c,int cu,int cv) {
        if(raw.w < 255) { if(au == 255) au = 256; if(bu == 255) bu = 256; if(cu == 255) cu = 256; }
        if(raw.h < 256) { if(av == 255) av = 256; if(bv == 255) bv = 256; if(cv == 255) cv = 256; }
        clipper.clip(g,a,au,av,b,bu,bv,c,cu,cv,x1,y1,x2,y2);
        float uvScale = Math.max(256,Math.max(Math.max(Math.abs((float)au),Math.abs((float)av)),
                Math.max(Math.max(Math.abs((float)bu),Math.abs((float)bv)),Math.max(Math.abs((float)cu),Math.abs((float)cv))))) / 30000;
        if(clipper.count<3) return;
        // Keep the ORIGINAL per-triangle UV quantization. Only identical scales
        // can share a batch; re-scaling old shorts would drift shared UV edges.
        if(size>0 && batchUVScale!=uvScale) flush(g);
        batchUVScale=uvScale;
        for(int i = 1; i+1 < clipper.count; i++) {
            if(size+3>MAX_VERTICES) flush(g);
            pack(size,0,uvScale); pack(size+1,i,uvScale); pack(size+2,i+1,uvScale);
            size+=3;
        }
    }

    private void flush(DirectX7 g) {
        if(size==0) return;
        int count=size; size=0; // Never retry a partially failed submission.
        positions.set(0,count,packed); uv.set(0,count,coords);
        buffer.setPositions(positions,scale,null);
        buffer.setTexCoords(0,uv,batchUVScale/256,null);
        int slot=count/3-1;
        if(indices[slot]==null) {
            int[] lengths=new int[count/3];
            for(int i=0;i<lengths.length;i++) lengths[i]=3;
            indices[slot]=new TriangleStripArray(0,lengths);
        }
        owner.bind(g);
        graphics.setCamera(camera,null);
        owner.renderNative(g,buffer,indices[slot],appearance);
    }

    private void pack(int slot,int index,float uvScale) {
        float[] v = clipper.polygon; int p = index*5;
        packed[slot*3] = round(v[p]/scale);
        packed[slot*3+1] = round(v[p+1]/scale);
        packed[slot*3+2] = round(-v[p+2]/scale);
        coords[slot*2] = round(v[p+3]/uvScale);
        coords[slot*2+1] = round(v[p+4]/uvScale);
    }

    private static short round(float value) {
        int q = (int)(value + (value < 0 ? -0.5f : 0.5f));
        if(q < -32767 || q > 32767) throw new IllegalArgumentException("sky coordinate overflow");
        return (short)q;
    }
}
