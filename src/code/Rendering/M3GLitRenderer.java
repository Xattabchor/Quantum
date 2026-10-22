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

    private final VertexBuffer buffer = new VertexBuffer();
    private final VertexArray posArray = new VertexArray(4, 3, 2);
    private final VertexArray uvArray = new VertexArray(4, 2, 2);
    private final VertexArray colorArray = new VertexArray(4, 4, 1);
    private final VertexArray normalArray = new VertexArray(4, 3, 1);
    private final TriangleStripArray triStrip =
            new TriangleStripArray(new int[]{0, 1, 2}, new int[]{3});
    private final TriangleStripArray quadStrip =
            new TriangleStripArray(new int[]{0, 1, 3, 1, 2, 3}, new int[]{3, 3});

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
    }

    /** Frame-unique state: real perspective camera + light parameters. */
    // Generic projection = classic pinhole with the engine focal lengths
    // (distX/distY): x_clip = x*fx/z_neg. Avoids CLDC-missing Math.atan.
    private final Transform cameraTransform = new Transform();
    private final float[] projection = {0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, -1, 0};

    public void beginFrame(DirectX7 g) {
        frameId++;
        depthCleared = false;
        // near<1 = auto: 1*focal span. far/near=3000 keeps the 16-bit depth
        // useful across the map instead of collapsing the far field into a
        // few dozen steps (near=16 was ratio 37500 and distant z-fighting).
        float near = Main.m3gLitNear > 0 ? Main.m3gLitNear : Math.max(16, g.distX);
        float far = Main.m3gLitFar;
        projection[0] = (float) g.distX / g.centreX;
        projection[5] = (float) g.distY / g.centreY;
        // OpenGL-style RHCS: z_clip = z*(f+n)/(n-f) + 2fn/(n-f), w = -z.
        projection[10] = (far + near) / (near - far);
        projection[14] = 2f * far * near / (near - far);
        cameraTransform.set(projection);
        camera.setGeneric(cameraTransform);
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
        graphics.addLight(ambient, null);
        graphics.addLight(sun, sunTransform);
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
        if(mode == 8 || mode == 10 || mode == 12) return false;
        RawImage raw = texture.rImg;
        if(texture.mip != null && texture.mip.length > 0) raw = texture.mip[0];
        if(raw == null || !owner.supportsTexture(raw)) return false;

        int maxAbs = 1;
        boolean behind = true;
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            if(v.getViewDepth() == Integer.MAX_VALUE) return false; // no view record: fall back
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
                    || v < Short.MIN_VALUE || v > Short.MAX_VALUE) return false;
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
        if(lastTex != tex) {
            appearance.setTexture(0, tex);
            lastTex = tex;
        }
        tex.setBlending(mode == 11 ? Texture2D.FUNC_DECAL
                : mode == 5 ? Texture2D.FUNC_BLEND : Texture2D.FUNC_MODULATE);

        // Alpha threshold: cutout punch for non-alpha kinds (owner cache and
        // hw-mip chains share this semantics), plain alpha blend elsewhere.
        float threshold = kind == M3GSceneRenderer.ALPHA_TEXTURE ? 0f : 0.5f;
        if(kind == M3GSceneRenderer.OPAQUE_TEXTURE) {
            compositing.setBlending(CompositingMode.REPLACE);
            compositing.setDepthWriteEnable(true);
        } else if(kind == M3GSceneRenderer.ADD_TEXTURE) {
            compositing.setBlending(CompositingMode.ALPHA_ADD);
            compositing.setDepthWriteEnable(false);
        } else {
            compositing.setBlending(CompositingMode.ALPHA);
            compositing.setDepthWriteEnable(false);
        }
        compositing.setAlphaThreshold(threshold);
        compositing.setDepthTestEnable(true);

        owner.flushBatch(g);
        graphics.setCamera(camera, null);
        owner.renderNative(g, buffer, count == 3 ? triStrip : quadStrip, appearance);
        return true;
    }
}
