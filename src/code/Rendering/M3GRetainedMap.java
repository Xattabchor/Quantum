package code.Rendering;

import java.util.Hashtable;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.Transform;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.World;

import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.ColorLightedPolygon3V;
import code.Rendering.Meshes.ColorLightedPolygon4V;
import code.Rendering.Meshes.LightedPolygon3V;
import code.Rendering.Meshes.LightedPolygon4V;
import code.Rendering.Meshes.Polygon3V;
import code.Rendering.Meshes.Polygon4V;
import code.Rendering.RenderObject;
import code.Rendering.Vertex;
import code.utils.Main;

/**
 * The kordos location as one retained-mode M3G 1.0 scene graph.
 *
 * Written because the immediate-mode path could not be made fast by tuning it.
 * Eleven rounds of measurement on it established what the frame is NOT: not
 * barriers (B=0), not texture rebuilds (T=0), not the near plane
 * (trisInNearBand=0), and - decisively - not fill. Removing 95% of the lit
 * pass's projected bounding box moved the device's flush by nothing at all
 * (2794 -> 2941 ms with 180 -> 179 lit polys). 16 ms per polygon, ~420 ms per
 * bucket, no dependence on area: the cost is in how the frame is submitted.
 *
 * So this does not tune that path, it replaces the submission model. The world
 * is built ONCE into a scene graph and drawn with a single
 * Graphics3D.render(World) per frame. Everything the immediate path pays per
 * frame - the per-bucket scale loop, VertexArray.set on four arrays,
 * setPositions, a fresh TriangleStripArray, seven binds and seven renders - is
 * either done once at build time or handed to the implementation, which is
 * where frustum culling and clipping belong and where it can be done once
 * instead of every frame.
 *
 * Scope, deliberately: geometry, one texture per material, the baked lightmap
 * in the vertex colours, one perspective camera. No fog, no skybox, no
 * sprites, no weather, no portal recursion - those come across from the main
 * engine afterwards, one at a time, each checked against the FPS this gets on
 * its own.
 */
public final class M3GRetainedMap {

    /** Chunks per axis. Splits the world so the implementation can cull by
     *  bounding box; 4x4 = 16 over a 176094 x 177444 unit level. */
    private static final int GRID = 4;

    private final World world = new World();
    private final Group root = new Group();
    private final Camera camera = new Camera();
    private final Transform camTransform = new Transform();
    private final float[] camMatrix = new float[16];

    private boolean built;
    private int triangles;
    private int drawables;
    private long buildMs;

    public boolean isBuilt() { return built; }
    public int triangles() { return triangles; }
    public int drawables() { return drawables; }
    public long buildMs() { return buildMs; }

    // ------------------------------------------------------------------
    // Build. Runs once; everything below this line is per frame.
    // ------------------------------------------------------------------

    /** One accumulator per (grid cell, texture). */
    private static final class Bin {
        int[] pos = new int[256];
        int posLen;
        int[] uv = new int[256];
        int[] col = new int[256];
        int[] idx = new int[256];
        int idxLen;
        final Hashtable vmap = new Hashtable(); // global vertex -> local vertex
        byte tex;
        MultyTexture mt;
    }

    public void build(DirectX7 g, M3GSceneRenderer owner, Room[] rooms) {
        long t0 = System.currentTimeMillis();
        built = false;
        triangles = 0;
        drawables = 0;

        // Pass 1: bounds, so the grid is over the world that is actually there.
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int total = 0;
        for (int r = 0; r < rooms.length; r++) {
            code.Rendering.Meshes.Mesh m = rooms[r].getMesh();
            if (m == null) continue;
            Vertex[] vs = m.getVertices();
            if (vs == null) continue;
            total += vs.length;
            for (int i = 0; i < vs.length; i++) {
                if (vs[i].x < minX) minX = vs[i].x;
                if (vs[i].x > maxX) maxX = vs[i].x;
                if (vs[i].z < minZ) minZ = vs[i].z;
                if (vs[i].z > maxZ) maxZ = vs[i].z;
            }
        }
        if (total == 0) return;
        int spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);

        // Pass 2: bin every polygon corner.
        Hashtable bins = new Hashtable();
        for (int r = 0; r < rooms.length; r++) {
            code.Rendering.Meshes.Mesh m = rooms[r].getMesh();
            MultyTexture mt = m == null ? null : m.getTexture();
            if (m == null) continue;
            RenderObject[] pols = m.getPolygons();
            if (pols == null) continue;
            for (int p = 0; p < pols.length; p++) {
                RenderObject o = pols[p];
                if (!(o instanceof Polygon3V) && !(o instanceof Polygon4V)) continue;
                int count = o instanceof Polygon3V ? 3 : 4;
                Vertex[] vs = new Vertex[count];
                int[] us = new int[count];
                int[] vv = new int[count];
                if (o instanceof Polygon3V) {
                    Polygon3V q = (Polygon3V) o;
                    vs[0] = q.a; vs[1] = q.b; vs[2] = q.c;
                    us[0] = q.au; us[1] = q.bu; us[2] = q.cu;
                    vv[0] = q.av; vv[1] = q.bv; vv[2] = q.cv;
                } else {
                    Polygon4V q = (Polygon4V) o;
                    vs[0] = q.a; vs[1] = q.b; vs[2] = q.c; vs[3] = q.d;
                    us[0] = q.au; us[1] = q.bu; us[2] = q.cu; us[3] = q.du;
                    vv[0] = q.av; vv[1] = q.bv; vv[2] = q.cv; vv[3] = q.dv;
                }
                int cx = 0, cz = 0;
                for (int i = 0; i < count; i++) { cx += vs[i].x; cz += vs[i].z; }
                cx /= count; cz /= count;
                int gx = clampCell((cx - minX) * GRID / spanX);
                int gz = clampCell((cz - minZ) * GRID / spanZ);
                byte slot = 0;
                if(o instanceof Polygon3V) slot = ((Polygon3V) o).tex;
                else slot = (byte) ((Polygon4V) o).tex;
                Integer key = new Integer((gx * GRID + gz) * 256 + (slot & 255));
                Bin bin = (Bin) bins.get(key);
                if (bin == null) {
                    bin = new Bin(); bin.tex = slot; bin.mt = mt; bins.put(key, bin);
                }
                addPolygon(bin, vs, us, vv, o, count, m);
            }
        }

        // Pass 3: one M3G Mesh per non-empty bin.
        for (java.util.Enumeration e = bins.keys(); e.hasMoreElements();) {
            Integer key = (Integer) e.nextElement();
            Bin bin = (Bin) bins.get(key);
            if (bin.idxLen < 3) continue;
            Mesh node = makeMesh(g, owner, bin);
            if (node == null) continue;
            root.addChild(node);
            drawables++;
        }

        world.addChild(root);
        world.setActiveCamera(camera);
        buildMs = (int) (System.currentTimeMillis() - t0);
        built = drawables > 0;
    }

    private static int clampCell(int v) { return v < 0 ? 0 : (v >= GRID ? GRID - 1 : v); }

    private void addPolygon(Bin bin, Vertex[] vs, int[] us, int[] vv,
            RenderObject o, int count, code.Rendering.Meshes.Mesh mesh) {
        // Fan: quads become (0,1,2) (0,2,3), the same split the quad path uses.
        int[] order = count == 3 ? new int[]{0, 1, 2} : new int[]{0, 1, 2, 0, 2, 3};
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            Vertex v = vs[i];
            Integer gk = new Integer(System.identityHashCode(v));
            Integer local = (Integer) bin.vmap.get(gk);
            if (local == null) {
                local = new Integer(bin.posLen / 3);
                bin.vmap.put(gk, local);
                int n = bin.posLen / 3;
                if ((n + 1) * 3 > bin.pos.length) {
                    int[] np = new int[bin.pos.length * 2];
                    System.arraycopy(bin.pos, 0, np, 0, bin.posLen);
                    bin.pos = np;
                    int[] nu = new int[bin.uv.length * 2];
                    System.arraycopy(bin.uv, 0, nu, 0, n * 2);
                    bin.uv = nu;
                    int[] nc = new int[bin.col.length * 2];
                    System.arraycopy(bin.col, 0, nc, 0, n);
                    bin.col = nc;
                }
                bin.pos[bin.posLen++] = v.x;
                bin.pos[bin.posLen++] = v.y;
                bin.pos[bin.posLen++] = v.z;
                bin.uv[n * 2] = us[i];
                bin.uv[n * 2 + 1] = vv[i];
                bin.col[n] = lightOf(o, i);
            }
            if (bin.idxLen == bin.idx.length) {
                int[] ni = new int[bin.idx.length * 2];
                System.arraycopy(bin.idx, 0, ni, 0, bin.idxLen);
                bin.idx = ni;
            }
            bin.idx[bin.idxLen++] = local.intValue();
        }
        triangles += count == 3 ? 1 : 2;
    }

    /** The baked lightmap value for one corner, exactly as setColors() reads it. */
    private static int lightOf(RenderObject o, int i) {
        int r = 255, gr = 255, b = 255;
        boolean gray = true;
        if (o instanceof LightedPolygon3V) {
            LightedPolygon3V p = (LightedPolygon3V) o;
            r = i == 0 ? p.la : i == 1 ? p.lb : p.lc;
        } else if (o instanceof LightedPolygon4V) {
            LightedPolygon4V p = (LightedPolygon4V) o;
            r = i == 0 ? p.la : i == 1 ? p.lb : i == 2 ? p.lc : p.ld;
        } else if (o instanceof ColorLightedPolygon3V) {
            ColorLightedPolygon3V p = (ColorLightedPolygon3V) o;
            r = i == 0 ? p.ar : i == 1 ? p.br : p.cr;
            gr = i == 0 ? p.ag : i == 1 ? p.bg : p.cg;
            b = i == 0 ? p.ab : i == 1 ? p.bb : p.cb;
            gray = false;
        } else if (o instanceof ColorLightedPolygon4V) {
            ColorLightedPolygon4V p = (ColorLightedPolygon4V) o;
            r = i == 0 ? p.ar : i == 1 ? p.br : i == 2 ? p.cr : p.dr;
            gr = i == 0 ? p.ag : i == 1 ? p.bg : i == 2 ? p.cg : p.dg;
            b = i == 0 ? p.ab : i == 1 ? p.bb : i == 2 ? p.cb : p.db;
            gray = false;
        }
        r = r < 0 ? 0 : (r > 255 ? 255 : r);
        if (gray) gr = b = r;
        else {
            gr = gr < 0 ? 0 : (gr > 255 ? 255 : gr);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);
            if (Main.fogQ == 1) r = gr = b = (r + gr + b) / 3;
        }
        return (r << 16) | (gr << 8) | b;
    }

    private Mesh makeMesh(DirectX7 g, M3GSceneRenderer owner, Bin bin) {
        int verts = bin.posLen / 3;
        int maxAbs = 1;
        for (int i = 0; i < bin.posLen; i++) {
            int a = bin.pos[i] < 0 ? -bin.pos[i] : bin.pos[i];
            if (a > maxAbs) maxAbs = a;
        }
        float scale = maxAbs / 30000f;
        if (scale < 0.001f) scale = 0.001f;

        short[] xyz = new short[bin.posLen];
        for (int i = 0; i < bin.posLen; i++) {
            float f = bin.pos[i] / scale;
            xyz[i] = (short) (int) (f + (f >= 0 ? 0.5f : -0.5f));
        }
        short[] st = new short[verts * 2];
        for (int i = 0; i < verts * 2; i++) st[i] = (short) bin.uv[i];
        byte[] rgba = new byte[verts * 4];
        for (int i = 0; i < verts; i++) {
            int c = bin.col[i];
            rgba[i * 4] = (byte) ((c >> 16) & 255);
            rgba[i * 4 + 1] = (byte) ((c >> 8) & 255);
            rgba[i * 4 + 2] = (byte) (c & 255);
            rgba[i * 4 + 3] = (byte) 255;
        }
        int[] indices = new int[bin.idxLen];
        System.arraycopy(bin.idx, 0, indices, 0, bin.idxLen);
        int[] lengths = new int[bin.idxLen / 3];
        for (int i = 0; i < lengths.length; i++) lengths[i] = 3;

        VertexArray posA = new VertexArray(verts, 3, 2);
        posA.set(0, verts, xyz);
        VertexArray uvA = new VertexArray(verts, 2, 2);
        uvA.set(0, verts, st);
        VertexArray colA = new VertexArray(verts, 4, 1);
        colA.set(0, verts, rgba);

        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(posA, scale, null);
        vb.setTexCoords(0, uvA, 1.0f / 256, null);
        vb.setColors(colA);
        vb.setDefaultColor(0xffffffff);

        Appearance ap = new Appearance();
        PolygonMode pm = new PolygonMode();
        // CULL_NONE: the level's winding is not consistent, and the immediate
        // path ships cull=NONE. Matching it keeps the picture identical; if
        // the fill turns out to be the whole cost, CULL_BACK is the first
        // thing to try, but that is a picture change and has to be judged.
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setShading(PolygonMode.SHADE_SMOOTH);
        pm.setPerspectiveCorrectionEnable(Main.m3gPerspective);
        ap.setPolygonMode(pm);
        CompositingMode cm = new CompositingMode();
        cm.setBlending(CompositingMode.REPLACE);
        cm.setDepthTestEnable(true);
        cm.setDepthWriteEnable(true);
        cm.setAlphaThreshold(0.5f);
        ap.setCompositingMode(cm);

        Texture2D tex = null;
        MultyTexture mt = bin.mt;
        if (mt != null && mt.textures != null && (bin.tex & 255) < mt.textures.length) {
            Texture t = mt.textures[bin.tex & 255];
            if (t != null) {
                RawImage raw = t.mip != null && t.mip.length > 0 ? t.mip[0] : t.rImg;
                if (raw != null && owner.supportsTexture(raw))
                    tex = owner.texture(g, raw, M3GSceneRenderer.OPAQUE_TEXTURE);
            }
        }
        if (tex != null) {
            tex.setBlending(Texture2D.FUNC_MODULATE);
            ap.setTexture(0, tex);
        }

        return new Mesh(vb, new TriangleStripArray(indices, lengths), ap);
    }

    // ------------------------------------------------------------------
    // Frame.
    // ------------------------------------------------------------------

    /** Install the camera. The game's Matrix is world->view after invert(), so
     *  the camera node takes the un-inverted one. */
    public void render(DirectX7 g, M3GSceneRenderer owner) {
        if (!built) return;
        Graphics3D g3d = Graphics3D.getInstance();
        Matrix cam = g.getCamera();
        float fp = (float) Matrix.FP;
        // Row-major in the engine, column-major in M3G.
        camMatrix[0] = cam.m00 / fp; camMatrix[1] = cam.m10 / fp;
        camMatrix[2] = cam.m20 / fp; camMatrix[3] = cam.m30 / fp;
        camMatrix[4] = cam.m01 / fp; camMatrix[5] = cam.m11 / fp;
        camMatrix[6] = cam.m21 / fp; camMatrix[7] = cam.m31 / fp;
        camMatrix[8] = cam.m02 / fp; camMatrix[9] = cam.m12 / fp;
        camMatrix[10] = cam.m22 / fp; camMatrix[11] = cam.m32 / fp;
        camMatrix[12] = cam.m03 / fp; camMatrix[13] = cam.m13 / fp;
        camMatrix[14] = cam.m23 / fp; camMatrix[15] = cam.m33 / fp;
        camTransform.set(camMatrix);
        camera.setTransform(camTransform);
        float fovY = g.fovY > 0 ? g.fovY : 60;
        float near = Math.max(16, g.distX);
        camera.setPerspective(fovY, (float) g.width / (float) g.height,
                near, Main.m3gLitFar);
        g.frameDrawCalls++;
        g3d.render(world);
    }
}
