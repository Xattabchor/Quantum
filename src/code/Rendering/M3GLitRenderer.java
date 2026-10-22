package code.Rendering;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Transform;

import code.Rendering.Meshes.Polygon3V;
import code.Rendering.Meshes.Polygon4V;
import code.utils.Main;
import java.util.Vector;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Image2D;

/**
 * True-3D hardware-lit world pass for the M3G backend (M3G_LIT).
 *
 * The regular M3G path blits polygons already projected by the software
 * camera ("packed" screen-space), so M3G lights can never apply there.
 * This pass instead submits the SAME world polygons in camera/view space
 * (Vertex.recordView data captured during projection) through a real
 * perspective Camera, and adds hardware lights:
 *   ambient + directional ("room sun") + an OMNI muzzle-flash light
 *   driven by DirectX7.shootIntensity/shootLength (Weapon.fire feed,
 *   the same pulse the legacy CPU Mode5WeaponLight consumed).
 *
 * Normals are per-polygon geodetic normals computed from the view-space
 * vertex data (works for static kordos map and animated meshes alike) and
 * auto-oriented toward the camera so winding never matters. Vertex colors
 * (baked lightmap, see setColors) are kept: M3G modulates them with the
 * lit material color; Main.m3gLitNoVertexColors probes the opposite mode.
 *
 * Everything not in (Polygon3V|Polygon4V textured) keeps the legacy path
 * (sky, sprites, weather lines, HUD). Disabled by default; enabled only
 * via the M3G_LIT setting (kordos-full build). Other builds keep the old
 * packed path untouched.
 */
public final class M3GLitRenderer {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;

    private final Camera camera = new Camera();
    private final Appearance appearance = new Appearance();
    private final CompositingMode compositing = new CompositingMode();
    private final PolygonMode polygon = new PolygonMode();
    private final Material material = new Material();
    private final Background depthClear = new Background();

    private final Light ambient = new Light();
    private final Light sun = new Light();
    private final Light omni = new Light();
    private final Transform sunTransform = new Transform();
    private final Transform omniTransform = new Transform();
    // OpenSM3D-proven immediate-mode style: pass an explicit identity matrix
    // instead of null to addLight/setCamera — some runtimes reject null.
    private final Transform identity = new Transform();

    private final VertexBuffer buffer = new VertexBuffer();
    private final VertexArray posArray = new VertexArray(4, 3, 2);
    private final VertexArray uvArray = new VertexArray(4, 2, 2);
    private final VertexArray colorArray = new VertexArray(4, 4, 1);
    private final VertexArray normalArray = new VertexArray(4, 3, 1);
    private final TriangleStripArray triStrip =
            new TriangleStripArray(new int[]{0, 1, 2}, new int[]{3});
    private final TriangleStripArray triStripRev =
            new TriangleStripArray(new int[]{0, 2, 1}, new int[]{3});
    private final TriangleStripArray quadStrip =
            new TriangleStripArray(new int[]{0, 1, 3, 1, 2, 3}, new int[]{3, 3});
    private final TriangleStripArray quadStripRev =
            new TriangleStripArray(new int[]{0, 3, 1, 1, 3, 2}, new int[]{3, 3});

    private final short[] xyz = new short[12];
    private final short[] uv = new short[8];
    private final byte[] rgba = new byte[16];
    private final byte[] nrm = new byte[12];

    private float posScale = -1f;
    private Texture2D lastTex;
    private boolean depthCleared;
    private long frameId;
    private long selfTestStart;

    // HW-mip texture stores (M3G_LIT_MIP=1): full Image2D mip chain per
    // source Texture; filters {NEAREST, NEAREST} = NEAREST_MIPMAP_NEAREST,
    // so no CPU-side mip picking or software interpolation is left.
    private final Vector litTextures = new Vector();

    private static final class LitTexture {
        Texture source;
        int kind;
        int[] img0;
        Texture2D texture;
    }

    // Deferred per-signature buckets (FPS): the whole frame's lit polys go out
    // as ONE native draw per (texture, blend kind, texfunc) — D counter drops
    // from ~260 per-poly calls to ~15 bucket calls. Opaque buckets flush
    // first, then the blended ones; depth buffer resolves interleavings.
    private final Vector buckets = new Vector();

    private static final class LitBucket {
        Texture2D tex; int kind; int func;
        int verts, indexLen, stripCount;
        int[] iv = new int[0];
        short[] uva = new short[0];
        byte[] cola = new byte[0];
        byte[] nrma = new byte[0];
        int[] indices = new int[0];
        int[] strips = new int[0];
        VertexBuffer vb;
        VertexArray posA, uvA, colA, nrmA;
        short[] xyz = new short[0];
        float posScale = -1f;
    }

    private static int[] grow(int[] a, int n) {
        if(a.length >= n) return a;
        int c = a.length * 2; if(c < n) c = n; if(c < 96) c = 96;
        int[] b = new int[c]; System.arraycopy(a, 0, b, 0, a.length); return b;
    }

    private static short[] growS(short[] a, int n) {
        if(a.length >= n) return a;
        int c = a.length * 2; if(c < n) c = n; if(c < 96) c = 96;
        short[] b = new short[c]; System.arraycopy(a, 0, b, 0, a.length); return b;
    }

    private static byte[] growB(byte[] a, int n) {
        if(a.length >= n) return a;
        int c = a.length * 2; if(c < n) c = n; if(c < 96) c = 96;
        byte[] b = new byte[c]; System.arraycopy(a, 0, b, 0, a.length); return b;
    }

    private static int[] trim(int[] a, int n) {
        int[] b = new int[n]; System.arraycopy(a, 0, b, 0, n); return b;
    }

    private Texture2D litTexture(DirectX7 g, Texture texture, RawImage raw, int kind) {
        boolean hwMip = Main.m3gLitMip == 1
                && texture.mip != null && texture.mip.length > 0 && texture.mip[0] != null;
        if(!hwMip) return owner.texture(g, raw, kind);
        int[] live0 = texture.mip[0].img;
        for(int i = 0; i < litTextures.size(); i++) {
            LitTexture lt = (LitTexture) litTextures.elementAt(i);
            // Animated sources (river water) swap pixel arrays in place:
            // react to identity changes by rebuilding the chain.
            if(lt.source == texture && lt.kind == kind && lt.img0 == live0)
                return lt.texture;
        }
        try {
            int levels = Math.min(3, texture.mip.length);
            Image[] chain = new Image[levels];
            for(int l = 0; l < levels; l++) {
                RawImage m = texture.mip[l];
                int size = m.w * m.h;
                int[] px = new int[size];
                boolean alpha = kind == M3GSceneRenderer.ALPHA_TEXTURE;
                for(int p = 0; p < size; p++) {
                    int rgb = m.img[p];
                    int a = alpha ? (rgb >>> 24) : (rgb != 0 ? 255 : 0);
                    px[p] = (a << 24) | (rgb & 0xffffff);
                }
                chain[l] = Image.createRGBImage(px, m.w, m.h, true);
            }
            Texture2D tex = new Texture2D(new Image2D(Image2D.RGBA, chain));
            tex.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
            tex.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
            LitTexture lt = new LitTexture();
            lt.source = texture; lt.kind = kind; lt.texture = tex;
            lt.img0 = live0;
            litTextures.addElement(lt);
            g.frameTextureUploads++;
            return tex;
        } catch(Exception e) {
            return owner.texture(g, raw, kind); // device without mip chains
        } catch(Error e) {
            return owner.texture(g, raw, kind);
        }
    }

    public M3GLitRenderer(M3GSceneRenderer owner, Graphics3D graphics) {
        this.owner = owner;
        this.graphics = graphics;

        // Same shading/culling policy as the packed world appearance, but
        // with a Material: with lights enabled Material multiplies per the
        // JSR-184 lighting equation (specular off = cheaper per-vertex math).
        // Winding CW is not a guess: the packed path judges fronts CW in NDC
        // (= CCW in engine screen-y-down coords), and the recordView view
        // space is y-up, which flips the winding exactly once again, so lit
        // fronts arrive here as CW as well.
        polygon.setCulling(PolygonMode.CULL_BACK);
        polygon.setWinding(PolygonMode.WINDING_CW);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);
        owner.registerPolygonMode(polygon);
        material.setColor(Material.DIFFUSE, 0xffffffff);
        // JSR-184 default material AMBIENT is dark grey (0x333333-ish): it
        // multiplies the ambient light down to ~nothing. Open it fully.
        material.setColor(Material.AMBIENT, 0xffffffff);
        material.setColor(Material.SPECULAR, 0xff000000); // specular off
        appearance.setPolygonMode(polygon);
        appearance.setMaterial(material);
        appearance.setCompositingMode(compositing);
        compositing.setAlphaThreshold(0);

        depthClear.setColorClearEnable(false);
        depthClear.setDepthClearEnable(true);

        ambient.setMode(Light.AMBIENT);
        ambient.setColor(0xffffff);
        sun.setMode(Light.DIRECTIONAL);
        sun.setColor(0xffffff);
        omni.setMode(Light.OMNI);
        omni.setColor(0xffffff);
        // Down-forward-right in view space (view: +X right, +Y up, -Z front).
        sunTransform.postRotate(30f, 1f, 0f, 0f);
        sunTransform.postRotate(-35f, 0f, 1f, 0f);

        buffer.setNormals(normalArray);
        buffer.setColors(colorArray);
        buffer.setTexCoords(0, uvArray, 1.0f / 256, null);

        // M3G_LIT_DEBUG=1: unlit white-silhouette probe (no Material = no
        // lighting per JSR-184, no texture, CULL_NONE). Separates "geometry
        // never reaches the screen" from "geometry is textured/shaded black".
        debugPolygon.setCulling(PolygonMode.CULL_NONE);
        debugPolygon.setShading(PolygonMode.SHADE_SMOOTH);
        debugComposite.setBlending(CompositingMode.REPLACE);
        debugComposite.setDepthTestEnable(true);
        debugComposite.setDepthWriteEnable(true);
        debugComposite.setAlphaThreshold(0);
        debugAppearance.setPolygonMode(debugPolygon);
        debugAppearance.setCompositingMode(debugComposite);
    }

    private final Appearance debugAppearance = new Appearance();
    private final PolygonMode debugPolygon = new PolygonMode();
    private final CompositingMode debugComposite = new CompositingMode();

    /** Frame-unique state: real perspective camera + light parameters. */
    // OpenSM3D-proven path: setPerspective with the engine's own FOV fields
    // (their m3g runtime renders retained/perspective cameras reliably;
    // fully custom generic matrices stay for the proven packed path only).

    // On-device diagnostics (debug stats line): polygons consumed by the lit
    // pass vs rejected per frame, and the last rejection reason.
    // reason: 1=exotic mode, 2=no/null texture or unsupported, 3=no view
    // record, 4=uv out of 16-bit range, 0=none.
    public static int statsLit, statsFail, statsReason;
    public static String statsErr = ""; // last fatal lit-pass failure (nullable text)
    private int frameLit, frameFail, frameReason;

    public static void resetLitStats() {
        statsLit = 0; statsFail = 0; statsReason = 0;
    }

    public void beginFrame(DirectX7 g) {
        frameId++;
        depthCleared = false;
        if(frameLit > statsLit) statsLit = frameLit;
        if(frameFail > statsFail) { statsFail = frameFail; statsReason = frameReason; }
        frameLit = 0; frameFail = 0; frameReason = 0;
        for(int i = 0; i < buckets.size(); i++) {
            LitBucket bk = (LitBucket) buckets.elementAt(i);
            bk.verts = 0; bk.indexLen = 0; bk.stripCount = 0;
        }
        float near = Main.m3gLitNear > 0 ? Main.m3gLitNear : Math.max(16, g.distX);
        float far = Main.m3gLitFar;
        camera.setPerspective(g.fovY > 0 ? g.fovY : 60,
                (float) g.width / (float) g.height, near, far);
        polygon.setCulling(Main.m3gLitCullNone
                ? PolygonMode.CULL_NONE : PolygonMode.CULL_BACK);
        polygon.setWinding(Main.m3gLitFlipWinding
                ? PolygonMode.WINDING_CCW : PolygonMode.WINDING_CW);
        ambient.setIntensity(Main.m3gLitAmbient / 255f);
        sun.setIntensity(Main.m3gLitDir / 255f);

        float half = g.distX * Main.m3gOmniHalfDistX / 100f;
        if(half < 1f) half = 1f;
        // 1/(kc + kq*d^2): low constant floor (~6.7x peak) so the flash is
        // visible well before fine-tuning, kq gives 50% at half-distance.
        omni.setAttenuation(0.15f, 0f, 0.85f / (half * half));
        omniTransform.setIdentity();
        omniTransform.postTranslate(g.distX * Main.m3gOmniOffsetX / 100f,
                g.distY * Main.m3gOmniOffsetY / 100f,
                g.distX * Main.m3gOmniOffsetZ / 100f);
    }

    /** Re-added after every target bind (bind resets the light list). */
    void applyLights(DirectX7 g) {
        if(!depthCleared) {
            graphics.clear(depthClear);
            depthCleared = true;
        }
        float pulse = 0f;
        if(g.shootIntensity > 0)
            pulse = g.shootLength > 0
                    ? (float) g.shootIntensity / (float) g.shootLength : 1f;
        else if(g.flashlightEnabled) pulse = 1f;
        // Spawn self-test (M3G_LIT_SELFTEST>0): full flash for the first
        // seconds, weapon-independent - proves the whole light path on hw.
        if(Main.m3gLitSelfTest > 0) {
            if(selfTestStart == 0) selfTestStart = System.currentTimeMillis();
            if(System.currentTimeMillis() - selfTestStart
                    < Main.m3gLitSelfTest * 1000L) pulse = 1f;
            else Main.m3gLitSelfTest = 0; // once per level load
        }
        // Ambient pulse isolates the muzzle event on hw: global light CANNOT
        // be invisible, so if the ambient flash shows but the omni does not,
        // the device's OMNI implementation (not the code path) is at fault.
        ambient.setIntensity(Math.min(1f,
                Main.m3gLitAmbient / 255f + Main.m3gLitAmbPulse / 255f * pulse));
        graphics.addLight(ambient, identity);
        if(Main.m3gLitDir > 0) graphics.addLight(sun, sunTransform);
        if(Main.m3gWorldLighting == 1 && pulse > 0f) {
            float flash = Main.m3gOmniBase / 255f + Main.m3gOmniFlash / 255f * pulse;
            omni.setIntensity(flash * 2.2f);
            graphics.addLight(omni, omniTransform);
        }
    }

    /**
     * View-space submission of one world/mesh polygon with hardware lights.
     * Returns false when the legacy packed path must handle it (unsupported
     * texture/mode), true when the polygon was consumed (drawn or culled).
     */
    public boolean draw(DirectX7 g, RenderObject object, Texture texture,
            int mode, int count, Vertex[] vertices, int[] uvIn) {
        // Keep exotic fill/fog modes on their certified CPU-equivalent path.
        if(mode == 8 || mode == 10 || mode == 12) { frameFail++; frameReason = 1; return false; }
        RawImage raw = texture.rImg;
        if(texture.mip != null && texture.mip.length > 0) raw = texture.mip[0];
        if(raw == null || !owner.supportsTexture(raw)) { frameFail++; frameReason = 2; return false; }

        int maxAbs = 1;
        boolean behind = true;
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            if(v.getViewDepth() == Integer.MAX_VALUE) { frameFail++; frameReason = 3; return false; } // no view record
            int x = v.getViewX(), y = v.getViewY(), z = v.getViewDepth();
            if(z < 0) behind = false;
            maxAbs = Math.max(maxAbs, Math.max(Math.abs(x),
                    Math.max(Math.abs(y), Math.abs(z))));
        }
        if(behind) return true; // entirely behind the eye plane

        float scale = maxAbs / 30000f;
        if(scale < 0.001f) scale = 0.001f;
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            float x = v.getViewX() / scale, y = v.getViewY() / scale,
                    z = v.getViewDepth() / scale;
            xyz[i * 3] = (short) (int) (x + (x >= 0 ? 0.5f : -0.5f));
            xyz[i * 3 + 1] = (short) (int) (y + (y >= 0 ? 0.5f : -0.5f));
            xyz[i * 3 + 2] = (short) (int) (z + (z >= 0 ? 0.5f : -0.5f));
        }
        posArray.set(0, count, xyz);
        if(posScale != scale) {
            buffer.setPositions(posArray, scale, null);
            posScale = scale;
        }

        if(Main.m3gLitDebug == 1) {
            // White-silhouette probe: skip normals/colors/uv/lights entirely.
            for(int i = 0; i < count * 4; i++) rgba[i] = (byte) 255;
            colorArray.set(0, count, rgba);
            owner.flushBatch(g);
            owner.bind(g);
            graphics.setCamera(camera, identity);
            owner.renderNative(g, buffer, count == 3 ? triStrip : quadStrip, debugAppearance);
            frameLit++;
            return true;
        }

        // Flats: cross((b-a),(c-a)) in view space, auto-oriented toward the eye,
        // replicated for every corner (smooth shading interpolates to flat).
        long ax = (long) vertices[1].getViewX() - vertices[0].getViewX();
        long ay = (long) vertices[1].getViewY() - vertices[0].getViewY();
        long az = (long) vertices[1].getViewDepth() - vertices[0].getViewDepth();
        long bx = (long) vertices[2].getViewX() - vertices[0].getViewX();
        long by = (long) vertices[2].getViewY() - vertices[0].getViewY();
        long bz = (long) vertices[2].getViewDepth() - vertices[0].getViewDepth();
        long nx = ay * bz - az * by;
        long ny = az * bx - ax * bz;
        long nz = ax * by - ay * bx;
        if(count == 4) {
            // Quads with (near-)collinear first three vertices produce a
            // degenerate normal: rate the other fan triangle (0,2,3) too and
            // keep the larger-magnitude geometric normal.
            long cx = bx, cy = by, cz = bz;
            long dx = (long) vertices[3].getViewX() - vertices[0].getViewX();
            long dy = (long) vertices[3].getViewY() - vertices[0].getViewY();
            long dz = (long) vertices[3].getViewDepth() - vertices[0].getViewDepth();
            long mx = cy * dz - cz * dy;
            long my = cz * dx - cx * dz;
            long mz = cx * dy - cy * dx;
            if(mx * mx + my * my + mz * mz > nx * nx + ny * ny + nz * nz) {
                nx = mx; ny = my; nz = mz;
            }
        }
        long px = vertices[0].getViewX(), py = vertices[0].getViewY(),
                pz = vertices[0].getViewDepth();
        if(nx * px + ny * py + nz * pz > 0) { nx = -nx; ny = -ny; nz = -nz; }
        double len = Math.sqrt((double) (nx * nx + ny * ny + nz * nz));
        int bxN, byN, bzN;
        if(len > 0) {
            bxN = (int) (nx * 126.0 / len);
            byN = (int) (ny * 126.0 / len);
            bzN = (int) (nz * 126.0 / len);
        } else {
            // Degenerate polygon: point at the eye instead of a hard axis.
            double pl = Math.sqrt((double) (px * px + py * py + pz * pz));
            if(pl > 0) {
                bxN = (int) (-px * 126.0 / pl);
                byN = (int) (-py * 126.0 / pl);
                bzN = (int) (-pz * 126.0 / pl);
            } else { bxN = 0; byN = 0; bzN = 126; }
        }
        for(int i = 0; i < count; i++) {
            nrm[i * 3] = (byte) bxN;
            nrm[i * 3 + 1] = (byte) byN;
            nrm[i * 3 + 2] = (byte) bzN;
        }
        normalArray.set(0, count, nrm);
        boolean normalViz = Main.m3gLitNormalViz;

        RawImage base = texture.mip != null && texture.mip.length > 0
                && Main.m3gLitMip == 0 ? texture.mip[0] : raw;
        if(base == null) return false;
        for(int i = 0; i < count; i++) {
            int u = uvIn[i * 2], v = uvIn[i * 2 + 1];
            if(u == 255 && base.w < 255) u = 256;
            if(v == 255 && base.h < 256) v = 256;
            if(u < Short.MIN_VALUE || u > Short.MAX_VALUE
                    || v < Short.MIN_VALUE || v > Short.MAX_VALUE) { frameFail++; frameReason = 4; return false; }
            uv[i * 2] = (short) u;
            uv[i * 2 + 1] = (short) v;
        }
        uvArray.set(0, count, uv);

        if(normalViz) {
            // M3G_LIT_NORMALVIZ: paint vertices by the geometric normal
            // (n/2+0.5 mapping), multiplied into the texture by MODULATE.
            int rv = (bxN >> 1) + 127, gv = (byN >> 1) + 127, bv = (bzN >> 1) + 127;
            for(int i = 0; i < count; i++) {
                rgba[i * 4] = (byte) rv;
                rgba[i * 4 + 1] = (byte) gv;
                rgba[i * 4 + 2] = (byte) bv;
                rgba[i * 4 + 3] = (byte) 255;
            }
        } else if(Main.m3gLitNoVertexColors) {
            for(int i = 0; i < count * 4; i++) rgba[i] = (byte) 255;
        } else {
            owner.setColors(g, object, count, mode);
            System.arraycopy(owner.colors, 0, rgba, 0, count * 4);
        }
        colorArray.set(0, count, rgba);

        int kind = mode == 2 ? M3GSceneRenderer.ADD_TEXTURE
                : mode == 1 || mode == 11 || (mode == 4 && raw.alphaMixing)
                ? M3GSceneRenderer.ALPHA_TEXTURE : M3GSceneRenderer.OPAQUE_TEXTURE;
        Texture2D tex = litTexture(g, texture, raw, kind);
        int func = mode == 11 ? Texture2D.FUNC_DECAL
                : mode == 5 ? Texture2D.FUNC_BLEND : Texture2D.FUNC_MODULATE;

        // Deferred batching: append into the (tex, kind, func) bucket; the
        // whole frame is emitted by flushBuckets() at the end of the native
        // transaction. Winding no longer matters (CULL_NONE).
        LitBucket bk = null;
        for(int i = 0; i < buckets.size(); i++) {
            LitBucket c = (LitBucket) buckets.elementAt(i);
            if(c.tex == tex && c.kind == kind && c.func == func) { bk = c; break; }
        }
        if(bk == null) {
            bk = new LitBucket();
            bk.tex = tex; bk.kind = kind; bk.func = func;
            buckets.addElement(bk);
        }
        int vbase = bk.verts;
        bk.iv = grow(bk.iv, (vbase + count) * 3);
        bk.uva = growS(bk.uva, (vbase + count) * 2);
        bk.cola = growB(bk.cola, (vbase + count) * 4);
        bk.nrma = growB(bk.nrma, (vbase + count) * 3);
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            bk.iv[(vbase + i) * 3] = v.getViewX();
            bk.iv[(vbase + i) * 3 + 1] = v.getViewY();
            bk.iv[(vbase + i) * 3 + 2] = v.getViewDepth();
            bk.uva[(vbase + i) * 2] = uv[i * 2];
            bk.uva[(vbase + i) * 2 + 1] = uv[i * 2 + 1];
            bk.cola[(vbase + i) * 4] = rgba[i * 4];
            bk.cola[(vbase + i) * 4 + 1] = rgba[i * 4 + 1];
            bk.cola[(vbase + i) * 4 + 2] = rgba[i * 4 + 2];
            bk.cola[(vbase + i) * 4 + 3] = rgba[i * 4 + 3];
            bk.nrma[(vbase + i) * 3] = (byte) bxN;
            bk.nrma[(vbase + i) * 3 + 1] = (byte) byN;
            bk.nrma[(vbase + i) * 3 + 2] = (byte) bzN;
        }
        bk.verts += count;
        if(count == 3) {
            bk.indices = grow(bk.indices, bk.indexLen + 3);
            bk.indices[bk.indexLen++] = vbase;
            bk.indices[bk.indexLen++] = vbase + 1;
            bk.indices[bk.indexLen++] = vbase + 2;
            bk.strips = grow(bk.strips, bk.stripCount + 1);
            bk.strips[bk.stripCount++] = 3;
        } else {
            bk.indices = grow(bk.indices, bk.indexLen + 6);
            bk.indices[bk.indexLen++] = vbase;
            bk.indices[bk.indexLen++] = vbase + 1;
            bk.indices[bk.indexLen++] = vbase + 3;
            bk.indices[bk.indexLen++] = vbase + 1;
            bk.indices[bk.indexLen++] = vbase + 2;
            bk.indices[bk.indexLen++] = vbase + 3;
            bk.strips = grow(bk.strips, bk.stripCount + 2);
            bk.strips[bk.stripCount++] = 3;
            bk.strips[bk.stripCount++] = 3;
        }
        frameLit++;
        return true;
    }

    /** Deferred draw: opaque buckets first, then blended buckets in creation
     * order. Called from M3GSceneRenderer.flushBatch (end of transaction). */
    void flushBuckets(DirectX7 g) {
        int n = buckets.size();
        if(n == 0) return;
        for(int phase = 0; phase < 2; phase++) {
            for(int bi = 0; bi < n; bi++) {
                LitBucket bk = (LitBucket) buckets.elementAt(bi);
                boolean opaque = bk.kind == M3GSceneRenderer.OPAQUE_TEXTURE;
                if((phase == 0) != opaque) continue;
                flushBucket(g, bk);
            }
        }
    }

    private void flushBucket(DirectX7 g, LitBucket bk) {
        if(bk.verts == 0) return;
        int maxAbs = 1;
        for(int i = 0; i < bk.verts * 3; i++) {
            int c = bk.iv[i]; if(c < 0) c = -c;
            if(c > maxAbs) maxAbs = c;
        }
        float scale = maxAbs / 30000f;
        if(scale < 0.001f) scale = 0.001f;
        bk.xyz = growS(bk.xyz, bk.verts * 3);
        for(int i = 0; i < bk.verts * 3; i++) {
            float f = bk.iv[i] / scale;
            bk.xyz[i] = (short) (int) (f + (f >= 0 ? 0.5f : -0.5f));
        }
        if(bk.vb == null || bk.posA.getVertexCount() < bk.verts) {
            int cap = 8; while(cap < bk.verts) cap *= 2;
            VertexBuffer vb = new VertexBuffer();
            bk.posA = new VertexArray(cap, 3, 2);
            bk.uvA = new VertexArray(cap, 2, 2);
            bk.colA = new VertexArray(cap, 4, 1);
            bk.nrmA = new VertexArray(cap, 3, 1);
            vb.setNormals(bk.nrmA);
            vb.setColors(bk.colA);
            vb.setTexCoords(0, bk.uvA, 1.0f / 256, null);
            bk.vb = vb;
            bk.posScale = -1f;
        }
        bk.posA.set(0, bk.verts, bk.xyz);
        bk.uvA.set(0, bk.verts, bk.uva);
        bk.colA.set(0, bk.verts, bk.cola);
        bk.nrmA.set(0, bk.verts, bk.nrma);
        if(bk.posScale != scale) {
            bk.vb.setPositions(bk.posA, scale, null);
            bk.posScale = scale;
        }
        // Fresh strip object per flush: index sequences vary frame to frame.
        TriangleStripArray strip = new TriangleStripArray(
                trim(bk.indices, bk.indexLen), trim(bk.strips, bk.stripCount));
        float threshold = bk.kind == M3GSceneRenderer.ALPHA_TEXTURE ? 0f : 0.5f;
        if(bk.kind == M3GSceneRenderer.OPAQUE_TEXTURE) {
            compositing.setBlending(CompositingMode.REPLACE);
            compositing.setDepthWriteEnable(true);
        } else if(bk.kind == M3GSceneRenderer.ADD_TEXTURE) {
            compositing.setBlending(CompositingMode.ALPHA_ADD);
            compositing.setDepthWriteEnable(false);
        } else {
            compositing.setBlending(CompositingMode.ALPHA);
            compositing.setDepthWriteEnable(false);
        }
        compositing.setAlphaThreshold(threshold);
        compositing.setDepthTestEnable(true);
        bk.tex.setBlending(bk.func);
        appearance.setTexture(0, bk.tex);
        owner.bind(g);
        graphics.setCamera(camera, identity);
        owner.renderNative(g, bk.vb, strip, appearance);
    }
}
