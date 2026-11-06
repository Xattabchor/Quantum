package code.Rendering;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Fog;
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
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

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
 * via drawmode 6 per texture (kordos-full build). Other builds keep the old
 * packed path untouched.
 */
public final class M3GLitRenderer {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;

    private final Camera camera = new Camera();
    private final Appearance appearance = new Appearance();
    /** Baked mode 9: same as {@link #appearance} but with a NULL Material, so
     * JSR-184 disables lighting and the vertex colours (the baked lightmap) are
     * used verbatim instead of being recomputed from a material. */
    private final Appearance bakedAppearance = new Appearance();
    private final CompositingMode compositing = new CompositingMode();
    private final PolygonMode polygon = new PolygonMode();
    private final Material material = new Material();
    private final Background depthClear = new Background();

    private final Light neutralAmbient = new Light();
    private final Light ambient = new Light();
    private final Light sun = new Light();
    private final Light omni = new Light();
    private final Transform sunTransform = new Transform();
    private final Transform omniTransform = new Transform();
    // OpenSM3D-proven immediate-mode style: pass an explicit identity matrix
    // instead of null to addLight/setCamera — some runtimes reject null.
    private final Transform identity = new Transform();

    private final VertexBuffer buffer = new VertexBuffer();
    // Sized for the widest polygon seen so far; recreated when a wider one
    // arrives. A VertexArray cannot be resized, only replaced.
    private VertexArray posArray = new VertexArray(8, 3, 2);
    private VertexArray uvArray = new VertexArray(8, 2, 2);
    private VertexArray colorArray = new VertexArray(8, 4, 1);
    private int immediateCapacity = 8;

    /** Make the probe buffers hold at least `count` vertices. */
    private void reserveImmediate(int count) {
        if(count <= immediateCapacity) return;
        int cap = immediateCapacity;
        while(cap < count) cap *= 2;
        posArray = new VertexArray(cap, 3, 2);
        uvArray = new VertexArray(cap, 2, 2);
        colorArray = new VertexArray(cap, 4, 1);
        normalArray = new VertexArray(cap, 3, 1);
        // The VertexBuffer keeps references to the old arrays: re-install.
        buffer.setNormals(normalArray);
        buffer.setColors(colorArray);
        buffer.setTexCoords(0, uvArray, 1.0f / 256, null);
        posScale = -1f; // positions must be re-attached to the new array
        immediateCapacity = cap;
    }
    // Fixed at 4 corners like the other probe arrays; it has to follow the
    // polygon width or normalArray.set() overruns and the runtime rejects
    // the buffer ("byte values too short", 294 bytes for 648 needed).
    private VertexArray normalArray = new VertexArray(8, 3, 1);
    private final TriangleStripArray triStrip =
            new TriangleStripArray(new int[]{0, 1, 2}, new int[]{3});
    private final TriangleStripArray triStripRev =
            new TriangleStripArray(new int[]{0, 2, 1}, new int[]{3});
    private final TriangleStripArray quadStrip =
            new TriangleStripArray(new int[]{0, 1, 3, 1, 2, 3}, new int[]{3, 3});
    private final TriangleStripArray quadStripRev =
            new TriangleStripArray(new int[]{0, 3, 1, 1, 3, 2}, new int[]{3, 3});

    // Immediate-path scratch (white probe). These used to be fixed at four
    // corners while the VertexArrays below were fixed at four vertices, so any
    // polygon with more than four vertices wrote past BOTH: the short[] write
    // threw ArrayIndexOutOfBounds and the native set() threw
    // "byte values too short" from a runtime that validates it. Both now grow.
    private short[] xyz = new short[12];
    private short[] uv = new short[8];
    private byte[] rgba = new byte[16];
    private byte[] nrm = new byte[12];

    private float posScale = -1f;
    private Texture2D lastTex;
    private boolean depthCleared;
    private long frameId;
    private long selfTestStart;

    // Deferred per-signature buckets (FPS): the whole frame's lit polys go out
    // as ONE native draw per (texture, blend kind, texfunc) — D counter drops
    // from ~260 per-poly calls to ~15 bucket calls. Opaque buckets flush
    // first, then the blended ones; depth buffer resolves interleavings.
    private final Vector buckets = new Vector();

    /** Vertices per render() call. Same ceiling the packed world path uses
     *  (M3GPolygonBatch.MAX_VERTICES), chosen there as the point where extra
     *  capacity stops paying. It is also a hard limit on some runtimes: a
     *  handset that accepts every vertex array can still reject the whole
     *  buffer with a message-less java.lang.Error from render() when the batch
     *  is simply too large. A world bucket can hold thousands of vertices, so
     *  it is submitted in slices of this size. */
    private static final int MAX_BATCH_VERTICES = 384;

    private static final class LitBucket {
        Texture2D tex; int kind; int func; boolean fogged;
        boolean unlit;   // baked mode 9: null Material, lighting off
        int levelFilter; // NEAREST for a mip chain, BASE_LEVEL otherwise
        int scrollDir; float scrollSpeed; // water UV-scroll (0 = static tex)
        int verts, indexLen, stripCount, maxAbs = 1;
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

    /** Textures for the lit pass. They come from the SHARED cache, so a world
     * texture exists exactly once: with M3G_LIT_MIP=1 the scene renderer uploads
     * that single entry as a mip chain and hands back the same object here.
     *
     * This used to keep its own parallel store (`litTextures`) and build a
     * second, chain-only copy of every world texture. Nothing ever evicted those
     * copies and they were never counted against the cache budget, so with
     * HW mip on the world's textures lived twice: over the texture budget,
     * thrashing the rasteriser, ~4x slower. The store is gone; so is the leak. */
    private Texture2D litTexture(DirectX7 g, Texture texture, RawImage raw, int kind) {
        return owner.texture(g, raw, kind);
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
        // M3G_LIT_NO_LIGHTS: mode 9 carries the whole baked lightmap in the
        // vertex colours, so the material must contribute nothing of its own.
        // EMISSIVE is the only JSR-184 term that is never multiplied by light:
        //   result = emissive + ambient*materialAmbient + sum(diffuse*materialDiffuse)
        // With no lights in the scene the last two terms are zero, and emissive
        // (tracking the per-vertex colour) is exactly the lightmap. That is
        // strictly more faithful than the white-ambient trick of
        // M3G_LIT_BAKED, which relies on the runtime folding ambient to 1.0.
        boolean noLights = Main.m3gLitNoLights == 1 && Main.m3gLitWorld9 == 1;
        if(noLights) {
            material.setColor(Material.EMISSIVE, 0xffffffff);
            material.setColor(Material.AMBIENT, 0x00000000);
            material.setColor(Material.DIFFUSE, 0x00000000);
        } else {
            material.setColor(Material.EMISSIVE, 0x00000000);
            material.setColor(Material.DIFFUSE, 0xffffffff);
            // JSR-184 default material AMBIENT is dark grey (0x333333-ish): it
            // multiplies the ambient light down to ~nothing. Open it fully.
            material.setColor(Material.AMBIENT, 0xffffffff);
        }
        material.setColor(Material.SPECULAR, 0xff000000); // specular off
        appearance.setPolygonMode(polygon);
        appearance.setMaterial(material);
        appearance.setCompositingMode(compositing);
        // Baked mode 9: NO Material at all. Per JSR-184 "lighting is disabled
        // for a submesh if it has a null Material, and enabled otherwise. If
        // lighting is disabled, the final vertex colour is taken from the
        // associated VertexBuffer as such." That is exactly the baked lightmap.
        //
        // With a Material present, lighting is on and the colour is COMPUTED
        // from the material and the lights instead. Vertex colour tracking is
        // the only hook that feeds the buffer colours back into that equation,
        // it is off by default in the API and this code never enabled it - so
        // every vertex came out as the material's ambient term: flat white
        // world, and a full per-vertex lighting equation per frame on top.
        bakedAppearance.setPolygonMode(polygon);
        bakedAppearance.setCompositingMode(compositing);
        // deliberately no setMaterial(): a null Material disables lighting
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
    public static int cullDist, cullBehind, cullMinPx; // why polys were dropped
    public static int lastDistLimit, cullMode;
    /** Signature of the lights actually handed to the engine this frame. */
    public static String lightSig = "";
    public static int statsDynDist;     // live dyn-cull distance (M3G_LIT_DYN=1)
    public static String statsErr = ""; // last fatal lit-pass failure (nullable text)
    /** Last JSR-184 call the lit pass reached. A message-less java.lang.Error
     *  from the native core names no method, so the stage is the only way to
     *  tell setPositions from render() on a handset. */
    public static String STAGE = "?";
    private int frameLit, frameFail, frameReason;
    private int frameMaterial;      // draws this frame that went out WITH a Material
    private boolean lastFrameHadMaterial = true;

    public static void resetLitStats() {
        statsLit = 0; statsFail = 0; statsReason = 0;
        // The cull counters are reported next to the lit counters (and by the
        // measurement probe), so they have to follow the same lifetime. They
        // were never cleared anywhere, so they only ever accumulated from
        // startup and any reading of them described the whole session rather
        // than the window it was printed in.
        cullDist = 0; cullBehind = 0; cullMinPx = 0;
    }

    public void beginFrame(DirectX7 g) {
        frameId++;
        // Per-frame stage tracing starts here. See beginBatch(): the label was
        // previously only maintained by flushBucket, so any failure earlier in
        // the frame was reported under the previous frame's last stage.
        STAGE = "begin";
        depthCleared = false;
        flashActive = false;
        // Sweep untouched cache entries every ~10s (deleted polys / level
        // transitions can't leak forever).
        if((frameId % 600) == 599 && staticAttr.size() > 0) {
            for(Enumeration e = staticAttr.keys(); e.hasMoreElements();) {
                Object k = e.nextElement();
                if(frameId - ((Attr) staticAttr.get(k)).touched > 600)
                    staticAttr.remove(k);
            }
        }
        if(frameLit > statsLit) statsLit = frameLit;
        if(frameFail > statsFail) { statsFail = frameFail; statsReason = frameReason; }
        lastFrameHadMaterial = frameMaterial > 0;
        frameMaterial = 0;
        frameLit = 0; frameFail = 0; frameReason = 0;
        for(int i = 0; i < buckets.size(); i++) {
            LitBucket bk = (LitBucket) buckets.elementAt(i);
            bk.verts = 0; bk.indexLen = 0; bk.stripCount = 0; bk.maxAbs = 1;
        }
        // Dynamic distance cull (M3G_LIT_DYN=1): track the frame-time moving
        // average and shrink/relax the cutoff toward the target FPS.
        if(Main.m3gLitDyn == 1) {
            int cap = Main.m3gLitDist > 0 ? Main.m3gLitDist : (int) Main.m3gLitFar;
            if(cap > (int) Main.m3gLitFar) cap = (int) Main.m3gLitFar;
            if(dynDist <= 0 || dynDist > cap) dynDist = cap;
            int minD = cap < 20000 ? cap : 20000;
            long now = System.currentTimeMillis();
            if(lastFrameTime != 0) {
                float dt = now - lastFrameTime;
                if(dt > 250) dt = 250; // pauses/hiccups must not sink the average
                avgDt = avgDt == 0 ? dt : avgDt * 0.85f + dt * 0.15f;
                float target = 1000f / Math.max(5, Main.m3gLitFps);
                if(avgDt > target * 1.1f) {
                    dynDist = (int) (dynDist * 0.92f);
                    if(dynDist < minD) dynDist = minD;
                } else if(avgDt < target * 0.92f) {
                    dynDist = (int) (dynDist * 1.06f) + 1500;
                    if(dynDist > cap) dynDist = cap;
                }
            }
            lastFrameTime = now;
            statsDynDist = dynDist;
        } else statsDynDist = 0;
        // CPU modes 3/6 fog: linear fade to fogc from the eye (cpu: rz/fDist).
        frameFogOn = Main.fogQ > 0 && DirectX7.fDist > 1;
        if(frameFogOn) {
            fog.setMode(Fog.LINEAR);
            fog.setColor(DirectX7.fogc & 0xffffff);
            fog.setLinear(1.0f, Math.max(2f, DirectX7.fDist));
        }
        float near = Main.m3gLitNear > 0 ? Main.m3gLitNear : Math.max(16, g.distX);
        float far = Main.m3gLitFar;
        STAGE = "persp";
        camera.setPerspective(g.fovY > 0 ? g.fovY : 60,
                (float) g.width / (float) g.height, near, far);
        cullMode = Main.m3gLitCullNone ? PolygonMode.CULL_NONE : PolygonMode.CULL_BACK;
        polygon.setCulling(cullMode);
        // Flat shading reads dramatically cheaper on software m3g rasterizers;
        // normals are replicated per corner anyway, so the look is identical.
        polygon.setShading(Main.m3gLitFlat == 1
                ? PolygonMode.SHADE_FLAT : PolygonMode.SHADE_SMOOTH);
        polygon.setWinding(Main.m3gLitFlipWinding
                ? PolygonMode.WINDING_CCW : PolygonMode.WINDING_CW);
        ambient.setIntensity(Main.m3gLitAmbient / 255f);
        ambient.setColor(Main.m3gLitAmbCol & 0xffffff);
        sun.setIntensity(Main.m3gLitDir / 255f);
        sun.setColor(Main.m3gLitDirCol & 0xffffff);
        omni.setColor(Main.m3gOmniCol & 0xffffff);

        float half = g.distX * Main.m3gOmniHalfDistX / 100f;
        if(half < 1f) half = 1f;
        // 1/(kc + kq*d^2): low constant floor (~6.7x peak) so the flash is
        // visible well before fine-tuning, kq gives 50% at half-distance.
        omni.setAttenuation(0.15f, 0f, 0.85f / (half * half));
        STAGE = "omni";
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
        // M3G_LIT_BAKED=1: the world already carries its lightmap in the vertex
        // colours (setColors replays it for mode 9/13), so the lit material must
        // not multiply anything else into it. The JSR-184 equation is
        //   emissive + ambient*materialAmbient + sum(diffuse*materialDiffuse),
        // so a white AMBIENT light with a fully-open material AMBIENT is the only
        // combination that leaves the baked colour untouched. Directional, omni
        // and the muzzle pulse stay out; they are lighting, not the lightmap.
        // A live muzzle flash is real lighting and always plays, baked or not.
        // The omni is the weapon flash of the true-3D pass: it belongs to the
        // pass, not to a separate switch. A frame where nothing went out with a
        // Material draws no lit polygons at all (every drawmode-6 poly is either
        // absent or was degraded to the packed path), so handing the engine a
        // third lamp there is pure per-vertex cost for nothing. In practice that
        // makes the lamp automatic for level 6 geometry: it lights exactly the
        // frames that contain drawmode-6 polys and stays out of every other one.
        boolean flashWanted = pulse > 0f && Main.m3gWorldLighting == 1
                && (lastFrameHadMaterial || frameMaterial > 0);
        // M3G_LIT_NO_LIGHTS: submit nothing at all. The material EMISSIVE carries
        // the baked lightmap, so the scene needs no light node - not even the
        // neutral white ambient. Dropping it removes one light submission per
        // bind and the per-vertex ambient term the runtime would still compute.
        if(Main.m3gLitNoLights == 1 && Main.m3gLitWorld9 == 1) {
            lightSig = "none(emissive)";
            return;
        }
        // Mode 9 submissions carry a null Material, and JSR-184 only consults
        // the lights when a Material is present - so when a frame draws nothing
        // but baked mode 9 there is no reason to hand over a single light.
        // Mode 6 polys still go out WITH a Material and do need them, hence the
        // previous-frame test rather than an unconditional skip.
        if(Main.m3gLitWorld9 == 1 && !lastFrameHadMaterial) {
            lightSig = "none(mode9 has null Material)";
            return;
        }
        boolean baked = Main.m3gLitBaked == 1 && Main.m3gLitWorld9 == 1 && !flashWanted;
        if(baked) {
            neutralAmbient.setMode(Light.AMBIENT);
            neutralAmbient.setColor(0xffffff);
            neutralAmbient.setIntensity(1f);
            graphics.addLight(neutralAmbient, identity);
            lightSig = "neutralAmbient(i=1.0,c=ffffff)";
        } else {
            ambient.setIntensity(Math.min(1f,
                    Main.m3gLitAmbient / 255f + Main.m3gLitAmbPulse / 255f * pulse));
            graphics.addLight(ambient, identity);
            if(Main.m3gLitDir > 0) graphics.addLight(sun, sunTransform);
            lightSig = "ambient(i=" + Math.min(1f, Main.m3gLitAmbient / 255f + Main.m3gLitAmbPulse / 255f * pulse)
                    + ") sun=" + (Main.m3gLitDir > 0);
        }
        if(flashWanted && !baked) {
            flashActive = true; // cache bypass while dynamic light is live
            float flash = Main.m3gOmniBase / 255f + Main.m3gOmniFlash / 255f * pulse;
            omni.setIntensity(flash * 2.2f);
            graphics.addLight(omni, omniTransform);
            lightSig = lightSig + " +omni";
        }
    }

    /**
     * View-space submission of one world/mesh polygon with hardware lights.
     * Returns false when the legacy packed path must handle it (unsupported
     * texture/mode), true when the polygon was consumed (drawn or culled).
     */
    public boolean draw(DirectX7 g, RenderObject object, Texture texture,
            int mode, int count, Vertex[] vertices, int[] uvIn) {
        // Keep exotic fill/fog and framebuffer-blend modes (7=X2, 8/10, 12) on the
        // certified scene-renderer path - the lit pass only composites REPLACE/ALPHA/ADD.
        STAGE = "draw";
        if(mode == 7 || mode == 8 || mode == 10 || mode == 12) { frameFail++; frameReason = 1; return false; }
        // Mode 9 = baked monochrome lightmap in the vertex colours. Submit it
        // with NO Material so JSR-184 disables lighting and takes the vertex
        // colour as-is; any Material turns the colour into a computed value and
        // throws the lightmap away. M3G_LIT_NOVCOL is the opposite probe: it
        // blanks the colours and asks the material+lights to shade instead.
        boolean unlit = mode == 9 && !Main.m3gLitNoVertexColors
                && Main.m3gLitNoLights == 0;
        RawImage raw = texture.rImg;
        // Water UV-scroll: pin the base frame (or the sole static texture) and
        // scroll its UV transform natively — one resident texture.
        if(texture.uvScroll != 0) {
            if(texture.animMIP != null) raw = texture.animMIP[0].rImg;
        }
        else if(texture.mip != null && texture.mip.length > 0) raw = texture.mip[0];
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
        if(behind) {cullBehind++; return true;} // entirely behind the eye plane
        // Distance cull: drop polys entirely beyond the cutoff.
        // Consumed-without-draw keeps the counter honest: L shows survivors.
        // Start from the level's own limit so the lit pass can never draw less
        // world than the packed path it replaces (DirectX7.drDist is the level's
        // DIST). A tighter, explicitly configured rung may only ever narrow it.
        //
        // This used to read
        //     int distLimit = Main.m3gLitDyn == 1 ? dynDist : Main.m3gLitDist;
        //     distLimit = DirectX7.drDist;
        // where the second assignment discarded the first on every pass, so
        // M3G_LIT_DIST and M3G_LIT_DYN were parsed from setting.txt and then
        // silently had no effect at all.
        int distLimit = DirectX7.drDist;
        if(Main.m3gLitDyn == 1 && dynDist > 0) {
            if(distLimit <= 0 || dynDist < distLimit) distLimit = dynDist;
        } else if(Main.m3gLitDist > 0
                && (distLimit <= 0 || Main.m3gLitDist < distLimit)) {
            distLimit = Main.m3gLitDist;
        }
        // Flash-time cap (M3G_OMNI_CULL): two live lamps double per-vertex
        // lighting cost on software m3g; while the omni is on, don't feed it
        // geometry it can't visibly light anyway.
        if(flashActive && Main.m3gOmniCull > 0
                && (distLimit <= 0 || distLimit > Main.m3gOmniCull))
            distLimit = Main.m3gOmniCull;
        lastDistLimit = distLimit;
        if(distLimit > 0) {
            boolean beyond = true;
            for(int i = 0; i < count; i++)
                if(vertices[i].getViewDepth() > -distLimit) { beyond = false; break; }
            if(beyond) {cullDist++; return true;}
        }
        // Subpixel cull (M3G_LIT_MINPX): a poly whose whole screen bbox fits
        // into MINPX x MINPX pixels costs a full light/shade round for a dot's
        // worth of pixels — drop it before any bucket work. Guarded: ignored
        // while any vertex sits closer than one screen step (projected coords
        // of near-clipped polys are unreliable).
        if(Main.m3gLitMinPx > 0) {
            boolean cull = true;
            int minX = vertices[0].sx, maxX = minX, minY = vertices[0].sy, maxY = minY;
            for(int i = 0; i < count; i++) {
                Vertex v = vertices[i];
                if(v.getViewDepth() > -g.distX) { cull = false; break; } // near guard
                int sx = v.sx, sy = v.sy;
                if(sx < minX) minX = sx; else if(sx > maxX) maxX = sx;
                if(sy < minY) minY = sy; else if(sy > maxY) maxY = sy;
            }
            if(cull && maxX - minX < Main.m3gLitMinPx
                    && maxY - minY < Main.m3gLitMinPx) {cullMinPx++; return true;}
        }

        // Static attribute cache (M3G_LIT_STATIC): world polys never change
        // texture/uv/colors/normals — only view positions move per frame.
        // Skipped while the omni flash is live so its baked colors can't
        // freeze mid-update, and in debug shapes entirely.
        Attr at = null;
        boolean useStatic = Main.m3gLitStatic == 1 && Main.m3gLitDebug == 0
                && !Main.m3gLitNormalViz && !flashActive;
        if(useStatic) {
            Vertex v0 = vertices[0];
            at = (Attr) staticAttr.get(object);
            if(at != null && (at.tex != texture || at.mode != mode
                    || at.count != count || at.x != v0.x || at.y != v0.y
                    || at.z != v0.z || at.fogged != (frameFogOn && mode == 3))) at = null;
        }

        // Immediate-path buffer is only consumed by the white probe below —
        // batched rendering never touches posArray.
        if(Main.m3gLitDebug == 1) {
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
        STAGE = "arrPos";
        posArray.set(0, count, xyz);
        if(posScale != scale) {
            buffer.setPositions(posArray, scale, null);
            posScale = scale;
        }
        }

        if(Main.m3gLitDebug == 1) {
            // White-silhouette probe: skip normals/colors/uv/lights entirely.
            for(int i = 0; i < count * 4; i++) rgba[i] = (byte) 255;
            STAGE = "arrCol";
        colorArray.set(0, count, rgba);
            owner.flushBatch(g);
            owner.bind(g);
            graphics.setCamera(camera, identity);
            owner.cameraDirty(); // replaced the world camera on the shared target
            owner.renderNative(g, buffer, count == 3 ? triStrip : quadStrip, debugAppearance);
            frameLit++;
            return true;
        }

        // Flats: cross((b-a),(c-a)) in view space, auto-oriented toward the eye,
        // replicated for every corner (smooth shading interpolates to flat).
        // Skipped entirely on static-cache hits — nrm/uv/rgba come from Attr.
        // Also skipped in baked mode: with no Material JSR-184 disables lighting
        // and then never reads the normals, so the cross product, the two sqrts
        // and the normalise are pure waste on every world polygon.
        // Normalviz needs the values after the block, unlit skips it entirely.
        // Every scratch buffer below is indexed by corner count, and the
        // VertexArrays are fixed-size once created, so grow them to this
        // polygon BEFORE anything is written. They used to be fixed at four
        // corners: a wider polygon overran the short[] and then handed the
        // runtime a too-short vertex array ("byte values too short").
        reserveImmediate(count);
        xyz = growS(xyz, count * 3);
        uv = growS(uv, count * 2);
        rgba = growB(rgba, count * 4);
        nrm = growB(nrm, count * 3);
        int bxN = 0, byN = 0, bzN = 126;
        if(at == null && !unlit) {
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
        STAGE = "arrNrm";
        normalArray.set(0, count, nrm);
        }
        boolean normalViz = Main.m3gLitNormalViz && !unlit;

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
        STAGE = "arrUV";
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
            STAGE = "setColors";
            owner.setColors(g, object, count, mode);
            System.arraycopy(owner.colors, 0, rgba, 0, count * 4);
        }
        colorArray.set(0, count, rgba);

        int kind, func, level = Texture2D.FILTER_BASE_LEVEL; Texture2D tex; boolean foggy;
        short[] uvs; byte[] cols, nrms;
        if(at != null) {
            kind = at.kind; func = at.func; foggy = at.fogged; level = at.level;
            uvs = at.uvc; cols = at.colc; nrms = at.nrmc;
            at.touched = frameId;
            // Animated textures (river): CPU-side updateAnimation() swaps
            // rImg between cached animMIP frames; the static cache must not
            // pin frame 0 — rebind the native copy of the CURRENT frame
            // (find-style lookup: each frame uploads once, binds are free).
            tex = ((Texture) at.tex).animMIP != null
                    ? litTexture(g, (Texture) at.tex, raw, kind) : at.t2d;
        } else {
            kind = mode == 2 ? M3GSceneRenderer.ADD_TEXTURE
                    : mode == 1 || mode == 11 || (mode == 4 && raw.alphaMixing)
                    ? M3GSceneRenderer.ALPHA_TEXTURE : M3GSceneRenderer.OPAQUE_TEXTURE;
            tex = litTexture(g, texture, raw, kind);
            func = mode == 11 ? Texture2D.FUNC_DECAL
                    : mode == 5 ? Texture2D.FUNC_BLEND : Texture2D.FUNC_MODULATE;
            // Mode 6 rides the M3G rails with LIGHT only (ambient + omni) — the
            // CPU pass's gouraud/camera-light look is the hardware lights' job;
            // no native Fog. Mode 3 keeps the linear Fog (its only CPU meaning).
            foggy = frameFogOn && mode == 3;
            uvs = uv; cols = rgba; nrms = nrm;
            level = owner.textureLevelFilter();
            if(useStatic) {
                at = new Attr();
                at.tex = texture; at.t2d = tex; at.mode = mode; at.count = count;
                at.x = vertices[0].x; at.y = vertices[0].y; at.z = vertices[0].z;
                at.kind = kind; at.func = func; at.fogged = foggy; at.level = level;
                at.uvc = new short[count * 2];
                System.arraycopy(uv, 0, at.uvc, 0, count * 2);
                at.colc = new byte[count * 4];
                System.arraycopy(rgba, 0, at.colc, 0, count * 4);
                at.nrmc = new byte[count * 3];
                System.arraycopy(nrm, 0, at.nrmc, 0, count * 3);
                at.touched = frameId;
                staticAttr.put(object, at);
            }
        }

        // Deferred batching: append into the (tex, kind, func) bucket; the
        // whole frame is emitted by flushBuckets() at the end of the native
        // transaction. Winding no longer matters (CULL_NONE).
        // A bucket is capped at MAX_BATCH_VERTICES. Without the cap a whole
        // world submesh lands in one render() call - thousands of vertices and
        // well over a thousand strips - and a handset that accepts each vertex
        // array separately can still reject that buffer outright with a
        // message-less java.lang.Error (error 136 on an E65). The packed world
        // path submits the same geometry 384 vertices at a time and works on
        // that device; this pass now uses the same ceiling. Filling the current
        // bucket only while the polygon still fits keeps the draw count close
        // to the cap's worth of calls instead of one call per polygon.
        LitBucket bk = null;
        for(int i = 0; i < buckets.size(); i++) {
            LitBucket c = (LitBucket) buckets.elementAt(i);
            if(c.tex == tex && c.kind == kind && c.func == func && c.fogged == foggy
                    && c.unlit == unlit && c.levelFilter == level
                    && c.verts + count <= MAX_BATCH_VERTICES) { bk = c; break; }
        }
        if(bk == null) {
            bk = new LitBucket();
            bk.tex = tex; bk.kind = kind; bk.func = func; bk.fogged = foggy;
            bk.unlit = unlit;
            bk.levelFilter = level;
            bk.scrollDir = texture.uvScroll; bk.scrollSpeed = texture.uvScrollSpeed;
            buckets.addElement(bk);
        }
        int vbase = bk.verts;
        bk.iv = grow(bk.iv, (vbase + count) * 3);
        bk.uva = growS(bk.uva, (vbase + count) * 2);
        bk.cola = growB(bk.cola, (vbase + count) * 4);
        if(!unlit) bk.nrma = growB(bk.nrma, (vbase + count) * 3);
        int mxq = bk.maxAbs;
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            int x = v.getViewX(), y = v.getViewY(), z = v.getViewDepth();
            bk.iv[(vbase + i) * 3] = x;
            bk.iv[(vbase + i) * 3 + 1] = y;
            bk.iv[(vbase + i) * 3 + 2] = z;
            if(x < 0) x = -x; if(y < 0) y = -y; if(z < 0) z = -z;
            if(x > mxq) mxq = x;
            if(y > mxq) mxq = y;
            if(z > mxq) mxq = z;
        }
        bk.maxAbs = mxq;
        // Bulk copies instead of per-field stores — nothing else to compute here.
        System.arraycopy(uvs, 0, bk.uva, vbase * 2, count * 2);
        System.arraycopy(cols, 0, bk.cola, vbase * 4, count * 4);
        if(!unlit) System.arraycopy(nrms, 0, bk.nrma, vbase * 3, count * 3);
        bk.verts += count;
        if(!unlit) frameMaterial++;
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
        // Absolute-time water offset: no drift, one call per frame even with
        // several scroll buckets (river + future water textures).
        float offV = (float) (System.currentTimeMillis() % 4000000L) / 1000f;
        for(int phase = 0; phase < 2; phase++) {
            for(int bi = 0; bi < n; bi++) {
                LitBucket bk = (LitBucket) buckets.elementAt(bi);
                boolean opaque = bk.kind == M3GSceneRenderer.OPAQUE_TEXTURE;
                if((phase == 0) != opaque) continue;
                // Water scroll: skew this bucket's texture transform. The
                // object is river-only in practice, so leaving the offset on
                // it across frames is fine (re-set every flush anyway).
                if(bk.scrollDir != 0) {
                    float o = offV * bk.scrollSpeed % 1.0f;
                    if(bk.scrollDir == 2) bk.tex.setTranslation(-o, 0f, 0f);
                    else bk.tex.setTranslation(0f, -o, 0f);
                }
                flushBucket(g, bk);
            }
        }
    }

    /** Dynamic-cull state (per renderer): live distance + frame-time average. */
    private int dynDist;
    private long lastFrameTime;
    private float avgDt;

    /** M3G_LIT_STATIC=1: per-poly attribute cache — world polys keep texture,
     * uv, colors and normals forever; only view positions move per frame. */
    private final Hashtable staticAttr = new Hashtable();
    private boolean flashActive; // omni live this frame (cache bypassed)

    private static final class Attr {
        Object tex; Texture2D t2d;
        int mode, kind, func, count, x, y, z, level;
        boolean fogged;
        short[] uvc; byte[] colc; byte[] nrmc;
        long touched;
    }

    /** Native Fog node for CPU modes 3/6; refreshed once per frame. */
    private final Fog fog = new Fog();
    private boolean frameFogOn;

    private void flushBucket(DirectX7 g, LitBucket bk) {
        if(bk.verts == 0) return;
        // maxAbs was accumulated at append time: one float op total here.
        float scale = bk.maxAbs / 30000f;
        if(scale < 0.001f) scale = 0.001f;
        float inv = 1f / scale; // multiply beats divide per coordinate
        bk.xyz = growS(bk.xyz, bk.verts * 3);
        for(int i = 0; i < bk.verts * 3; i++) {
            float f = bk.iv[i] * inv;
            bk.xyz[i] = (short) (int) (f + (f >= 0 ? 0.5f : -0.5f));
        }
        if(bk.vb == null || bk.posA.getVertexCount() < bk.verts) {
            int cap = 8; while(cap < bk.verts) cap *= 2;
            VertexBuffer vb = new VertexBuffer();
            bk.posA = new VertexArray(cap, 3, 2);
            bk.uvA = new VertexArray(cap, 2, 2);
            bk.colA = new VertexArray(cap, 4, 1);
            // Normals are installed per bucket only when the bucket is LIT.
            // The packed world path - the one that works on the handset - never
            // calls setNormals at all, and an unlit bucket has no Material, so
            // JSR-184 disables lighting and never reads a normal. Shipping a
            // normals stream anyway costs a third vertex array and, on a
            // validating runtime, can reject the whole buffer from render()
            // with a message-less java.lang.Error.
            bk.nrmA = new VertexArray(cap, 3, 1);
            if(!bk.unlit) vb.setNormals(bk.nrmA);
            vb.setColors(bk.colA);
            vb.setTexCoords(0, bk.uvA, 1.0f / 256, null);
            bk.vb = vb;
            bk.posScale = -1f;
        }
        bk.posA.set(0, bk.verts, bk.xyz);
        bk.uvA.set(0, bk.verts, bk.uva);
        bk.colA.set(0, bk.verts, bk.cola);
        // Unlit buckets carry no normals stream at all (see the buffer setup):
        // with no Material the runtime must not read one, and the packed path
        // that works on the handset never supplies one either. Only the lit
        // bucket uploads normals.
        if(!bk.unlit) {
            if(bk.nrma.length < bk.verts * 3)
                bk.nrma = growB(bk.nrma, bk.verts * 3);
            STAGE = "nrmSet";
            bk.nrmA.set(0, bk.verts, bk.nrma);
        }
        if(bk.posScale != scale) {
            STAGE = "setPos";
            bk.vb.setPositions(bk.posA, scale, null);
            bk.posScale = scale;
        }
        // Fresh strip object per flush: index sequences vary frame to frame.
        STAGE = "strip";
        TriangleStripArray strip = new TriangleStripArray(
                trim(bk.indices, bk.indexLen), trim(bk.strips, bk.stripCount));
        float threshold = bk.kind == M3GSceneRenderer.ALPHA_TEXTURE ? 0f : 0.5f;
        if(bk.kind == M3GSceneRenderer.OPAQUE_TEXTURE) {
            STAGE = "comp";
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
        STAGE = "texBlend";
        bk.tex.setBlending(bk.func);
        // Filtering is not re-applied here: the shared cache entry already
        // carries it (FILTER_NEAREST level for a mip chain, FILTER_BASE_LEVEL
        // otherwise) and never changes it. Re-asserting it per flush buys
        // nothing and one runtime raises M3G_INVALID_VALUE on it, which becomes
        // a message-less IllegalArgumentException that kills the frame.
        // Unlit buckets go out with a NULL Material: JSR-184 then disables
        // lighting and uses the vertex colours verbatim (the baked lightmap),
        // instead of computing a colour from Material x lights.
        Appearance ap = bk.unlit ? bakedAppearance : appearance;
        STAGE = "setTex";
        ap.setTexture(0, bk.tex);
        // bind(g) copies the entire framebuffer into the surface before drawing.
        // When the sky already covered every pixel with an opaque pass, those
        // pixels are gone: no later geometry can expose them. Measured on the
        // harness as 98 full-screen copies over 192 frames - all of them from
        // here, and all of them copying a frame that the sky had already
        // replaced. bindInPlace still resets the depth buffer, which is what
        // the world actually depends on.
        STAGE = "bind";
        if(owner.isSurfaceOpaque()) owner.bindInPlace(g);
        else owner.bind(g);
        STAGE = "camera";
        graphics.setCamera(camera, identity);
        owner.cameraDirty(); // replaced the world camera on the shared target
        STAGE = "setFog";
        ap.setFog(bk.fogged ? fog : null);
        STAGE = "render";
        // The native core may refuse this submission. Rather than guess which
        // ingredient it objects to, bisect the differences from the packed path
        // that draws this same world on this same device, and report the first
        // form that is still fatal. Each probe is one throwaway render(); a
        // healthy frame never reaches here, so this costs nothing in normal use.
        try {
            owner.renderNative(g, bk.vb, strip, ap);
        } catch(Throwable t) {
            Appearance works = diagnose(g, bk, strip, ap);
            // Nothing we tried helped. Rethrow as an Error so the caller's
            // catch(Throwable) still sees a failure it can report: a plain
            // throw of the caught Throwable is not allowed here, because this
            // method does not declare it.
            if(works == null) throw new Error();
            owner.renderNative(g, bk.vb, strip, works);
        }
    }

    /** Which ingredient makes render() throw, narrowed on the device.
     *  Returns a short tag for the stats line. */
    private Appearance diagnose(DirectX7 g, LitBucket bk, TriangleStripArray strip,
                            Appearance ap) {
        // Probe A: identical call again. Distinguishes "this buffer is rejected"
        // from "the first render() after bind() is rejected".
        try {
            owner.renderNative(g, bk.vb, strip, ap);
            DIAG = "rep"; return ap;   // the repeat succeeded: transient
        } catch(Throwable t) { /* still refused */ }
        // Probe B: the painter's appearance, rebuilt from scratch - no Material,
        // polygon mode and compositing re-created. Removes a poisoned shared
        // Appearance object (the lit pass mutates one across the whole frame).
        try {
            Appearance fresh = new Appearance();
            fresh.setPolygonMode(polygon);
            fresh.setCompositingMode(compositing);
            fresh.setFog(bk.fogged ? fog : null);
            fresh.setTexture(0, bk.tex);
            owner.renderNative(g, bk.vb, strip, fresh);
            DIAG = "freshAp"; return fresh;
        } catch(Throwable t) { /* still refused */ }
        // Probe C: no texture. The painter always has one, so this only tells us
        // whether the sampler is implicated at all.
        try {
            Appearance noTex = new Appearance();
            noTex.setPolygonMode(polygon);
            noTex.setCompositingMode(compositing);
            owner.renderNative(g, bk.vb, strip, noTex);
            DIAG = "noTex"; return noTex;
        } catch(Throwable t) { /* still refused */ }
        // Probe D: the smallest possible submission from this same buffer - one
        // triangle, sequential indices. If even that dies, the problem is not
        // this geometry's size or index layout.
        try {
            TriangleStripArray one = new TriangleStripArray(0, new int[]{3});
            owner.renderNative(g, bk.vb, one, ap);
            DIAG = "1tri"; return ap;
        } catch(Throwable t) { /* still refused */ }
        DIAG = "all-fail"; return null;
    }

    /** Last diagnose() result, surfaced through statsErr. */
    public static String DIAG = "";
    /** A rebuilt Appearance was accepted where the shared one was not. */
    static boolean freshAppearance;
}
