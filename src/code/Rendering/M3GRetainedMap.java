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

    /** One quantisation scale for the whole world, not one per chunk.
     *
     *  Per-chunk scaling made the error proportional to the chunk's distance
     *  from the origin: a bin at x=500000 quantised in steps of ~16 world
     *  units while its neighbour quantised in steps of ~0.01, and the seams
     *  between them are not straight. A single scale makes the error uniform
     *  across the level. */
    private float worldScale = 1f;

    /** Which camera path actually ran, and the translation it was given.
     *  Reported on the HUD so a camera that silently fell back to the
     *  translation-only stage is visible rather than inferred. */
    private String camStage = "-";
    private int camPX, camPY, camPZ;

    public String camStage() { return camStage; }
    /** Camera position in world units, scaled down to stay readable. */
    public int camGridX() { return camPX >> 10; }
    public int camGridZ() { return camPZ >> 10; }
    private final Transform camTransform = new Transform();
    private final float[] camMatrix = new float[16];

    private boolean built;
    private int triangles;
    private int drawables;
    private long buildMs;
    /** Set once the retained path has thrown. Reported on the HUD and used to
     *  fall back to the immediate path, so a failure here costs the frame
     *  instead of the game. Without this the clean branch of the stats line
     *  outranked the error branch and swallowed the one fact that mattered. */
    private String failed;
    private String stage = "";

    /** The HUD clips at 30 glyphs, and "java.lang.IllegalArgumentException"
     *  does not fit - which is how a failure got reported as "java.lang.I" and
     *  stopped. Short tags for the ones this path can actually raise. */
    private static String tag(Throwable t) {
        if(t instanceof IllegalArgumentException) return "IAX";
        if(t instanceof IllegalStateException) return "ISX";
        if(t instanceof NullPointerException) return "NPX";
        if(t instanceof IndexOutOfBoundsException) return "IOX";
        if(t instanceof OutOfMemoryError) return "OOM";
        String n = t.getClass().getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }


    /** Turn the 3x3 basis in a column-major 4x4 into a proper rotation.
     *
     *  Fixed-point mul() leaves the columns neither unit length nor mutually
     *  orthogonal, and Transform.set() rejects what it cannot invert. X is
     *  normalised, Y is orthogonalised against X and normalised, Z is rebuilt
     *  as X cross Y so the handedness - and therefore the determinant +1 -
     *  is right by construction rather than by luck. */
    private static void orthonormalise(float[] m) {
        float x0 = m[0], x1 = m[1], x2 = m[2];
        float xl = (float) Math.sqrt(x0 * x0 + x1 * x1 + x2 * x2);
        if(!(xl > 1e-6f)) { x0 = 1f; x1 = 0f; x2 = 0f; xl = 1f; }
        x0 /= xl; x1 /= xl; x2 /= xl;
        float y0 = m[4], y1 = m[5], y2 = m[6];
        float d = y0 * x0 + y1 * x1 + y2 * x2;
        y0 -= d * x0; y1 -= d * x1; y2 -= d * x2;
        float yl = (float) Math.sqrt(y0 * y0 + y1 * y1 + y2 * y2);
        if(!(yl > 1e-6f)) {
            // Y parallel to X: any perpendicular will do.
            y0 = -x1; y1 = x0; y2 = 0f;
            yl = (float) Math.sqrt(y0 * y0 + y1 * y1 + y2 * y2);
            if(!(yl > 1e-6f)) { y0 = 0f; y1 = 1f; y2 = 0f; yl = 1f; }
        }
        y0 /= yl; y1 /= yl; y2 /= yl;
        float z0 = x1 * y2 - x2 * y1;
        float z1 = x2 * y0 - x0 * y2;
        float z2 = x0 * y1 - x1 * y0;
        m[0] = x0; m[1] = x1; m[2] = x2;
        m[4] = y0; m[5] = y1; m[6] = y2;
        m[8] = z0; m[9] = z1; m[10] = z2;
    }

    public String failure() { return failed; }
    public String stage() { return stage; }

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
        built = false;
        failed = null;
        try {
            buildUnsafe(g, owner, rooms);
        } catch(Throwable t) {
            failed = "@b." + stage + " " + tag(t);
            built = false;
        }
    }

    private void buildUnsafe(DirectX7 g, M3GSceneRenderer owner, Room[] rooms) {
        long t0 = System.currentTimeMillis();
        triangles = 0;
        drawables = 0;

        stage = "bounds";
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
        // Largest magnitude in the whole level, so every chunk quantises with
        // the same step. The bounds pass already walks every vertex.
        int worldMaxAbs = Math.max(Math.max(Math.abs(minX), Math.abs(maxX)),
                          Math.max(Math.abs(minZ), Math.abs(maxZ)));
        if (worldMaxAbs < 1) worldMaxAbs = 1;
        worldScale = worldMaxAbs / 30000f;
        if (worldScale < 0.001f) worldScale = 0.001f;

        stage = "bin";
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

        stage = "mesh";
        // Pass 3: one M3G Mesh per non-empty bin.
        for (java.util.Enumeration e = bins.keys(); e.hasMoreElements();) {
            Integer key = (Integer) e.nextElement();
            Bin bin = (Bin) bins.get(key);
            if (bin.idxLen < 3) continue;
            Mesh node = makeMesh(g, owner, bin);
            stage = "mesh@" + (bin.posLen / 3) + "v/" + (bin.idxLen / 3) + "t";
            if (node == null) continue;
            root.addChild(node);
            drawables++;
        }

        world.addChild(root);
        // render(World) needs the active camera to be IN the scene graph, not
        // merely referenced by it - the World is what supplies it with a
        // transform to walk up. Setting it without adding it leaves render()
        // to fail with IllegalStateException on the device, which is exactly
        // what it did.
        world.addChild(camera);
        try {
            world.setActiveCamera(camera);
        } catch(Throwable t) {
            // An implementation that refuses the camera outright is told so
            // at render time instead; the node is in the graph either way.
        }
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
        float scale = worldScale;

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
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(posA, scale, null);
        vb.setTexCoords(0, uvA, 1.0f / 256, null);
        // M3G_CLEAN_LIT off (the default): no baked lightmap, no vertex
        // colours at all, and the texture on REPLACE so it is drawn at full
        // brightness with no light in the scene. Geometry and texture and
        // nothing else - that is the plainest picture there is.
        if(Main.m3gCleanLit == 1) {
            VertexArray colA = new VertexArray(verts, 4, 1);
            colA.set(0, verts, rgba);
            vb.setColors(colA);
        }
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
            // REPLACE without vertex colours puts the texture straight
            // through. MODULATE is only right when the baked lightmap is in
            // the colours; without them everything comes out black.
            tex.setBlending(Main.m3gCleanLit == 1
                    ? Texture2D.FUNC_MODULATE : Texture2D.FUNC_REPLACE);
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
        try {
            renderUnsafe(g, owner);
        } catch(Throwable t) {
            failed = "@" + stage + " " + tag(t);
            built = false;
        }
    }

    private void renderUnsafe(DirectX7 g, M3GSceneRenderer owner) {
        Graphics3D g3d = Graphics3D.getInstance();
        Matrix cam = g.getCamera();
        float fp = (float) Matrix.FP;
        // Row-major in the engine, column-major in M3G:
        //   camMatrix[0..2]  = column 0 = the camera's local X in world
        //   camMatrix[4..6]  = column 1 = local Y
        //   camMatrix[8..10] = column 2 = local Z
        //   camMatrix[12..14] = translation
        //
        // The device rejects this matrix with IAX even with the affine row
        // forced to (0,0,0,1), so what it objects to is the rotation itself.
        // Matrix.mul() only ever writes m00..m23 and does its arithmetic in
        // fixed point, so the basis it hands over is neither normalised nor
        // guaranteed orthogonal - close enough to draw with, not close enough
        // for a Transform that has to be invertible. Gram-Schmidt makes it a
        // proper rotation (det = +1) before the call, and behind that sits a
        // fallback chain so this stage cannot kill the frame whatever it does.
        // The engine's view frame is X = right, Y = DOWN, Z = forward.
        // An M3G camera is X = right, Y = up, Z = backward, and looks down
        // its own -Z. So columns 1 and 2 are both negated: flipping either
        // one alone gives a determinant of -1, which is a mirror and which
        // Transform.set rejects; flipping both keeps det = +1 and puts the
        // level in front of the camera instead of behind it.
        //
        // Verified against the engine's own projection rather than guessed:
        // eight variants (Y and Z flipped or not, camera backed off or not)
        // compared against   sx = centreX + x*distX/(rz + distX)   over five
        // yaw/pitch pairs and five depths. Only this one matches, to under
        // 1.2 px.
        stage = "cTs";
        camMatrix[0] =  cam.m00 / fp; camMatrix[1] =  cam.m10 / fp;
        camMatrix[2] =  cam.m20 / fp; camMatrix[3] =  0f;
        camMatrix[4] = -cam.m01 / fp; camMatrix[5] = -cam.m11 / fp;
        camMatrix[6] = -cam.m21 / fp; camMatrix[7] =  0f;
        camMatrix[8] = -cam.m02 / fp; camMatrix[9] = -cam.m12 / fp;
        camMatrix[10] = -cam.m22 / fp; camMatrix[11] =  0f;

        // The engine divides by (rz + distX), not by rz, so its eye sits
        // distX behind the position it reports and the M3G camera has to be
        // placed there too, or everything near the camera is drawn about 30%
        // too large. Backing off along local +Z is backing off along the
        // view direction once column 2 has been negated above.
        float back = g.distX > 0 ? g.distX : 0f;

        // The translation is NOT in fixed point. Matrix.mul() scales the
        // rotation by FP (16384) but setPosition writes world units raw, and
        // the engine's own transform adds m03/m13/m23 after the >>fp shift:
        //   sx = (x*m00 + y*m01 + z*m02) >> fp  +  m03
        // Dividing it here put the camera 16384x too close to the origin -
        // inside the level, moving 1/16384 as far as it should. Which is what
        // "the camera only moves the raindrops" looked like.
        camMatrix[12] = cam.m03 + back * camMatrix[8];
        camMatrix[13] = cam.m13 + back * camMatrix[9];
        camMatrix[14] = cam.m23 + back * camMatrix[10];
        camMatrix[15] = 1f;
        for(int i = 0; i < 16; i++) {
            float v = camMatrix[i];
            if(v != v || v == Float.POSITIVE_INFINITY || v == Float.NEGATIVE_INFINITY)
                camMatrix[i] = i == 15 ? 1f : 0f;
        }
        orthonormalise(camMatrix);
        boolean set = false;
        try {
            camTransform.set(camMatrix);
            set = true;
        } catch(Throwable t) { set = false; }
        if(!set) {
            // Pure translation: loses the view direction, keeps the level up.
            for(int i = 0; i < 16; i++) camMatrix[i] = 0f;
            camMatrix[0] = camMatrix[5] = camMatrix[10] = camMatrix[15] = 1f;
            camMatrix[12] = cam.m03; camMatrix[13] = cam.m13;
            camMatrix[14] = cam.m23;
            try { camTransform.set(camMatrix); set = true; } catch(Throwable t) { set = false; }
        }
        if(set) {
            stage = "cTn";
            try { camera.setTransform(camTransform); }
            catch(Throwable t) { set = false; }
        }
        if(!set) {
            // Last resort: setTranslation cannot throw. Orientation is lost,
            // which is a visible bug, but the frame survives and the stage
            // name says exactly where it gave up.
            stage = "cTr";
            camera.setTranslation(cam.m03, cam.m13, cam.m23);
        }
        // setPerspective rejects fovy outside (0,180), aspect <= 0 and
        // near >= far, and every one of those is a value we take from the
        // renderer's live state rather than compute.
        stage = "camP";
        float fovY = g.fovY > 0 ? g.fovY : 60;
        if(fovY >= 180) fovY = 60;
        float aspect = g.height > 0 ? (float) g.width / (float) g.height : 0.75f;
        float near = Math.max(16, g.distX);
        float far = Main.m3gLitFar;
        if(far <= near) far = near * 100f;
        camera.setPerspective(fovY, aspect, near, far);
        camStage = stage;
        camPX = cam.m03; camPY = cam.m13; camPZ = cam.m23;
        // This API exposes render(World) only - there is no render(World,
        // Camera) overload to fall back on - so the active camera has to be a
        // child of the World. build() arranges that; if it is ever not, the
        // one thing this can throw is IllegalStateException, and there is
        // nothing to do about it here.
        stage = "render";
        g.frameDrawCalls++;
        g3d.render(world);
    }
}
