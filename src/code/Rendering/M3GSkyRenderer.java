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
    private final VertexArray positions = new VertexArray(3,3,2), uv = new VertexArray(3,2,2);
    private final TriangleStripArray indices = new TriangleStripArray(0,new int[]{3});
    private final short[] packed = new short[9], coords = new short[6];
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
            if(t == null || (DirectX7.m3gDrawMode(t.drawmode) != 0 && t.drawmode != 4) || !owner.supportsTexture(t.rImg)) return false;
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
                boolean alpha = t.drawmode == 4 && raw.alphaMixing;
                // Shared bounded texture cache; animation and array replacement
                // have the same invalidation semantics as level materials.
                Texture2D texture = owner.texture(g,raw,alpha ? M3GSceneRenderer.ALPHA_TEXTURE : M3GSceneRenderer.OPAQUE_TEXTURE);
                texture.setBlending(Texture2D.FUNC_MODULATE);
                appearance.setTexture(0,texture); appearance.setFog(null);
                composite.setBlending(alpha ? CompositingMode.ALPHA : CompositingMode.REPLACE);
                composite.setAlphaThreshold(alpha ? 0 : 0.5f);
                if(p instanceof Polygon3V) {
                    Polygon3V a = (Polygon3V)p;
                    triangle(g,raw,a.a,a.au,a.av,a.b,a.bu,a.bv,a.c,a.cu,a.cv);
                } else {
                    Polygon4V a = (Polygon4V)p;
                    triangle(g,raw,a.a,a.au,a.av,a.b,a.bu,a.bv,a.d,a.du,a.dv);
                    triangle(g,raw,a.b,a.bu,a.bv,a.c,a.cu,a.cv,a.d,a.du,a.dv);
                }
            }
        } finally {
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
        for(int i = 1; i+1 < clipper.count; i++) {
            pack(0,0,uvScale); pack(1,i,uvScale); pack(2,i+1,uvScale);
            positions.set(0,3,packed); uv.set(0,3,coords);
            buffer.setPositions(positions,scale,null);
            buffer.setTexCoords(0,uv,uvScale/256,null);
            owner.bind(g);
            graphics.setCamera(camera,null);
            graphics.render(buffer,indices,appearance,null);
        }
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
