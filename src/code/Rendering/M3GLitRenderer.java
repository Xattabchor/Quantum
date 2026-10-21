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

    public M3GLitRenderer(M3GSceneRenderer owner, Graphics3D graphics) {
        this.owner = owner;
        this.graphics = graphics;

        // Same shading/culling policy as the packed world appearance, but
        // with a Material: with lights enabled Material multiplies per the
        // JSR-184 lighting equation (specular off = cheaper per-vertex math).
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
        float near = Main.m3gLitNear, far = Main.m3gLitFar;
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
        // 1/(kc + kq*d^2): kc floor keeps close surfaces below clipping,
        // kq gives 50% at the configured half-distance.
        omni.setAttenuation(0.4f, 0f, 0.6f / (half * half));
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
        graphics.addLight(ambient, null);
        graphics.addLight(sun, sunTransform);
        float pulse = 0f;
        if(g.shootIntensity > 0 && g.shootLength > 0)
            pulse = (float) g.shootIntensity / (float) g.shootLength;
        else if(g.flashlightEnabled) pulse = 1f;
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
        long px = vertices[0].getViewX(), py = vertices[0].getViewY(),
                pz = vertices[0].getViewDepth();
        if(nx * px + ny * py + nz * pz > 0) { nx = -nx; ny = -ny; nz = -nz; }
        double len = Math.sqrt((double) (nx * nx + ny * ny + nz * nz));
        int bxN, byN, bzN;
        if(len > 0) {
            bxN = (int) (nx * 126.0 / len);
            byN = (int) (ny * 126.0 / len);
            bzN = (int) (nz * 126.0 / len);
        } else { bxN = 0; byN = 0; bzN = 126; }
        for(int i = 0; i < count; i++) {
            nrm[i * 3] = (byte) bxN;
            nrm[i * 3 + 1] = (byte) byN;
            nrm[i * 3 + 2] = (byte) bzN;
        }
        normalArray.set(0, count, nrm);

        RawImage base = texture.mip != null && texture.mip.length > 0
                ? texture.mip[0] : raw;
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

        if(Main.m3gLitNoVertexColors) {
            for(int i = 0; i < count * 4; i++) rgba[i] = (byte) 255;
        } else {
            owner.setColors(g, object, count, mode);
            System.arraycopy(owner.colors, 0, rgba, 0, count * 4);
        }
        colorArray.set(0, count, rgba);

        int kind = mode == 2 ? M3GSceneRenderer.ADD_TEXTURE
                : mode == 1 || mode == 11 || (mode == 4 && raw.alphaMixing)
                ? M3GSceneRenderer.ALPHA_TEXTURE : M3GSceneRenderer.OPAQUE_TEXTURE;
        Texture2D tex = owner.texture(g, raw, kind);
        if(lastTex != tex) {
            appearance.setTexture(0, tex);
            lastTex = tex;
        }
        tex.setBlending(mode == 11 ? Texture2D.FUNC_DECAL
                : mode == 5 ? Texture2D.FUNC_BLEND : Texture2D.FUNC_MODULATE);

        float threshold = kind == M3GSceneRenderer.ALPHA_TEXTURE
                ? 0 : owner.textureCutoutThreshold();
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
