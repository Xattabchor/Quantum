package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Ordered M3G polygon packets, with CPU barriers for legacy materials. */
public final class M3GSceneRenderer implements SceneRenderer {
    private final Graphics3D graphics;
    private final javax.microedition.m3g.Camera camera = new javax.microedition.m3g.Camera();
    private final Appearance appearance = new Appearance();
    private final PolygonMode polygon = new PolygonMode();
    private final PolygonMode screenPolygon = new PolygonMode();
    // Fog needs real eye Z even when legacy UV interpolation is affine.
    // This mode deliberately stays affine; the normal modes still follow HW PC.
    private final PolygonMode affineFogPolygon = new PolygonMode();
    private PolygonMode activePolygon;
    private M3GPolygonBatch batch;
    private WorldClipGeometry worldClip;
    private final M3GMaterialMath materialMath=new M3GMaterialMath();
    private final M3GPolygonBatch.State batchState=new M3GPolygonBatch.State();
    private CachedTexture lastTexture;
    // One native Transform/Java array, not a pair of allocations per bind.
    private final Transform worldProjection=new Transform();
    private final float[] worldProjectionData={0,0,0,0,0,0,0,0,0,0,-1,-0.02f,0,0,-1,0};
    private int projectionWidth,projectionHeight,projectionX,projectionY;
    private boolean projectionReady;
    private boolean batchable;
    // Set once bind()/restoreCamera() installs the packed world camera;
    // dirtied by every foreign camera (sky/lit/HUD passes) and by target
    // release. Lets restoreCamera() skip repeated setViewport+setCamera
    // between polygon-batch flushes (saves two JNI calls per flush).
    private boolean cameraInstalled;
    private float buffersScale;
    private static final int[] TRI={0,1,2}, QUAD={0,1,3,1,2,3}, ALT_QUAD={0,1,2,0,2,3},
            ABD={0,1,3}, BCD={1,2,3}, ACD={0,2,3};
    private boolean hardwarePerspective = Main.m3gPerspective;
    private final Vector polygonModes = new Vector();
    private final CompositingMode composite = new CompositingMode();
    private final Fog fog = new Fog();
    private final Vertex[] vertices = new Vertex[4];
    // Weather overlay quads: pooled verts + shared line geometry, reused per entry.
    private final Vertex[] overlayVerts = new Vertex[4];
    private final ScreenLineGeometry overlayGeometry = new ScreenLineGeometry();
    private int overlayVertsReady;
    private final int[] uv = new int[8];
    private final short[] positions = new short[12], coords = new short[8];
    final byte[] colors = new byte[16];
    private M3GLitRenderer litRenderer; // lit pass feeds only drawmode-6 polys
    private final float[] work = new float[12];
    private final VertexBuffer[] buffers = new VertexBuffer[2];
    // These two buffers own stable position arrays; contents change, bindings do not.
    private final float[] positionScales={Float.NaN,Float.NaN};
    private final VertexArray[] posArrays = new VertexArray[2];
    private final VertexArray[] uvArrays = new VertexArray[2];
    private final VertexArray[] colorArrays = new VertexArray[2];
    private final TriangleStripArray[] indices = new TriangleStripArray[2];
    private final TriangleStripArray[] baseQuadTriangles = new TriangleStripArray[2];
    private final TriangleStripArray[] alternateQuadTriangles = new TriangleStripArray[2];
    private final TriangleStripArray baseQuad, alternateQuad;
    static final int OPAQUE_TEXTURE = 0, ALPHA_TEXTURE = 1, ADD_TEXTURE = 3;
    static final int SPRITE_CUTOUT=4, SPRITE_ALPHA=5, SPRITE_ADD=6;
    static final int PANORAMA_TEXTURE=9, SPRITE_MUL=16;
    private final Vector textures = new Vector();
    private Image surface;
    private M3GSkyRenderer skyRenderer;
    private M3GSpriteRenderer spriteRenderer;
    private M3GPanoramaRenderer panoramaRenderer;
    private M3GFrameEffects frameEffects;
    private M3GCanvasImages canvasImages;
    private Background flashBackground;
    private boolean bound;
    private int cachedPixels;
    private final int maxTexture;
    private static final int CACHE_PIXELS = 262144, CACHE_ENTRIES = 16;

    private static final class CachedTexture {
        RawImage image;
        int[] pixels;
        int width, height;
        int kind, cost;
        Texture2D texture;
        int filter=Texture2D.FILTER_NEAREST;
        int levelFilter=Texture2D.FILTER_BASE_LEVEL;
        boolean mipChain;   // texture was uploaded as a mip chain, not a base level
        boolean live;       // already used in the CURRENT frame
        float cutoutThreshold;
    }

    public M3GSceneRenderer() {
        graphics = Graphics3D.getInstance();
        Hashtable properties = Graphics3D.getProperties();
        Object limit = properties.get("maxTextureDimension");
        maxTexture = limit instanceof Integer ? ((Integer)limit).intValue() : 256;
        registerPolygonMode(polygon);
        // ProjectedGeometry flips screen Y: the original front faces become
        // clockwise in M3G. Keep GPU backface rejection for all mesh polygons.
        polygon.setCulling(PolygonMode.CULL_BACK);
        polygon.setWinding(PolygonMode.WINDING_CW);
        registerPolygonMode(screenPolygon);
        screenPolygon.setCulling(PolygonMode.CULL_NONE);
        screenPolygon.setShading(PolygonMode.SHADE_SMOOTH);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);
        affineFogPolygon.setCulling(PolygonMode.CULL_BACK);
        affineFogPolygon.setWinding(PolygonMode.WINDING_CW);
        affineFogPolygon.setShading(PolygonMode.SHADE_SMOOTH);
        affineFogPolygon.setPerspectiveCorrectionEnable(false);
        appearance.setPolygonMode(polygon);
        activePolygon=polygon;
        batchState.polygon=polygon;
        composite.setDepthTestEnable(false);
        composite.setDepthWriteEnable(false);
        appearance.setCompositingMode(composite);
        for(int i = 0; i < 2; i++) {
            int count = i + 3;
            buffers[i] = new VertexBuffer();
            posArrays[i] = new VertexArray(count, 3, 2);
            uvArrays[i] = new VertexArray(count, 2, 2);
            colorArrays[i] = new VertexArray(count, 4, 1);
            buffers[i].setColors(colorArrays[i]);
            buffers[i].setTexCoords(0, uvArrays[i], 1.0f / 256, null);
        }
        indices[0] = new TriangleStripArray(0, new int[]{3});
        // Default diagonal; Polygon4V fog can select ABC/ACD instead.
        indices[1] = new TriangleStripArray(new int[]{0, 1, 3, 1, 2, 3}, new int[]{3, 3});
        baseQuad = indices[1];
        alternateQuad = new TriangleStripArray(new int[]{0, 1, 2, 0, 2, 3}, new int[]{3, 3});
        alternateQuadTriangles[0] = new TriangleStripArray(new int[]{0, 1, 2}, new int[]{3});
        alternateQuadTriangles[1] = new TriangleStripArray(new int[]{0, 2, 3}, new int[]{3});
        baseQuadTriangles[0] = new TriangleStripArray(new int[]{0, 1, 3}, new int[]{3});
        baseQuadTriangles[1] = new TriangleStripArray(new int[]{1, 2, 3}, new int[]{3});
        fog.setMode(Fog.LINEAR);
    }

    // Helpers register once, so a live preference change updates all cached
    // appearances without rebuilding meshes/textures or setting native state per draw.
    void registerPolygonMode(PolygonMode mode) {
        mode.setPerspectiveCorrectionEnable(hardwarePerspective);
        polygonModes.addElement(mode);
    }

    private void setScreenMode(boolean screen) {
        selectPolygonMode(screen?screenPolygon:polygon);
    }

    private void selectPolygonMode(PolygonMode mode) {
        batchState.polygon=mode;
        if(activePolygon!=mode) {appearance.setPolygonMode(mode);activePolygon=mode;}
    }

    // Plain Java state snapshots: no per-polygon native getters. Queued draws
    // upload material/vertex state only when the ordered packet is submitted.
    private void setTexture(Texture2D texture) {
        batchState.texture=texture;
        if(texture==null) {batchState.function=0;batchState.imageFilter=Texture2D.FILTER_NEAREST;
            batchState.levelFilter=Texture2D.FILTER_BASE_LEVEL;}
        if(!batchable) appearance.setTexture(0,texture);
    }
    private void setFog(Fog value) {
        batchState.fogged=value!=null;
        if(!batchable) appearance.setFog(value);
    }
    private void setBlend(int value) {batchState.blend=value;if(!batchable) composite.setBlending(value);}
    private void setThreshold(float value) {batchState.threshold=value;if(!batchable) composite.setAlphaThreshold(value);}
    private void setFunction(Texture2D texture,int value) {
        batchState.function=value;if(!batchable) texture.setBlending(value);
    }


    void bind(DirectX7 g) { bind(g,true); }

    /** For passes that draw INSIDE the current native transaction (sprites,
     *  polygon batch): nothing has been composited into the CPU buffer since the
     *  target was bound, so there is nothing to preserve. bind(g) would copy the
     *  whole framebuffer from int[] into the Image first - measured at ~13 ms per
     *  frame with 200 sprites, because it happens once per bind. */
    void bindInPlace(DirectX7 g) { bind(g,false); }

    // M3G_SCREEN_SCALE: the world pass renders into a smaller viewport
    // surface (area cut), presentation blits centered (no upscale) at finishImage.
    static int targetScale() {return Main.m3gScreenScale;}

    void bind(DirectX7 g,boolean preserve) {
        if(bound) return;
        int scale=targetScale();
        if(scale < 100) preserve=false; // staged/stretch presentation instead
        if(surface == null || surface.getWidth() != g.width*scale/100
                || surface.getHeight() != g.height*scale/100)
            surface = Image.createImage(g.width*scale/100, g.height*scale/100);
        // Copy the CPU background, skybox, and any earlier software segment.
        if(preserve) surface.getGraphics().drawRGB(g.display, 0, g.width, 0, 0, g.width, g.height, false);
        if(!projectionReady || projectionWidth!=g.width || projectionHeight!=g.height
                || projectionX!=g.centreX || projectionY!=g.centreY) {
            worldProjectionData[0]=2.0f/g.width;
            worldProjectionData[2]=-(2.0f*g.centreX/g.width-1);
            worldProjectionData[5]=2.0f/g.height;
            worldProjectionData[6]=-(1-2.0f*g.centreY/g.height);
            worldProjection.set(worldProjectionData);camera.setGeneric(worldProjection);
            projectionWidth=g.width;projectionHeight=g.height;projectionX=g.centreX;projectionY=g.centreY;
            projectionReady=true;
        }
        graphics.bindTarget(surface.getGraphics(), true, 0);
        bound = true;
        // Depth buffer now exists: every (re-)bind resets it so stale depth
        // from the previous native transaction never masks the world.
        if(depthClearBg == null) {
            depthClearBg = new Background();
            depthClearBg.setColorClearEnable(false);
            depthClearBg.setDepthClearEnable(true);
        }
        // Every stage the lit pass reaches is recorded, including the ones that
        // run before any polygon is drawn. Without these the trace reported the
        // stage left over from the previous frame - "render" - for a failure
        // that happened here, in the bind, and the label was a lie.
        M3GLitRenderer.STAGE = "bind";
        graphics.clear(depthClearBg);
        M3GLitRenderer.STAGE = "viewport";
        graphics.setViewport(0, 0, surface.getWidth(), surface.getHeight());
        M3GLitRenderer.STAGE = "bindCam";
        graphics.setCamera(camera, null);
        cameraInstalled = true;
        M3GLitRenderer.STAGE = "resetLights";
        graphics.resetLights();
        if(litRenderer != null) {
            M3GLitRenderer.STAGE = "lights";
            litRenderer.applyLights(g);
        }
    }

    void restoreCamera(DirectX7 g) {
        if(!bound || cameraInstalled) return;
        graphics.setViewport(0,0,g.width,g.height);
        graphics.setCamera(camera,null);
        cameraInstalled = true;
    }

    /** Foreign passes (sky/lit/HUD) replace the camera on the shared target;
     * they must call this so the next world flush re-installs ours. */
    void cameraDirty() { cameraInstalled = false; }

    boolean supportsTexture(RawImage raw) {
        return raw != null && powerOfTwo(raw.w) && powerOfTwo(raw.h)
                && raw.w <= maxTexture && raw.h <= maxTexture
                && (long)raw.w * raw.h <= CACHE_PIXELS;
    }

    static int spriteDimension(int n) {
        if(n<=0 || n>16384) return 0;
        // Keep POT assets unchanged; shrink NPOT copies instead of padding up.
        int result=1;while(result<=n/2) result*=2;return result;
    }

    boolean supportsSpriteTexture(RawImage raw) {
        if(raw==null || raw.img==null) return false;
        int w=spriteDimension(raw.w),h=spriteDimension(raw.h);
        return w>0 && h>0 && w<=maxTexture && h<=maxTexture
                && (long)w*h<=CACHE_PIXELS && (long)raw.w*raw.h<=raw.img.length;
    }

    /** True once the sky has painted every pixel of this frame with an opaque,
     *  depth-free pass. Geometry submitted after that cannot be occluded by
     *  anything that was in the surface before the frame started, so the
     *  full-surface copy that bind(g,true) does before every native pass is
     *  copying pixels nothing can see. Reset by every bind. */
    private boolean surfaceOpaque;
    void markSurfaceOpaque() {surfaceOpaque = true;}
    boolean isSurfaceOpaque() {return surfaceOpaque;}

    private void flushPolygons(DirectX7 g) {if(batch!=null) batch.flush(g);}
    void flushBatch(DirectX7 g) {
        // M3G_LIT_WORLD9 defers the world into texture buckets that ARE depth
        // tested. Emitting them last painted the world over the water, sky and
        // sprites, which stay on the depth-free packed path and therefore lose
        // to terrain regardless of distance. The world is the deepest layer: it
        // goes down first so everything queued after it composites on top.
        //
        // The buckets are emitted AT MOST ONCE per frame. flushBatch runs more
        // than once in a single frame - the world queue flushes through it, and
        // finishImage flushes again before releasing the target - and the
        // buckets are only cleared at the next beginFrame(). Emitting them on
        // every call therefore submitted the same already-drawn geometry a
        // second time, which is what the device refused (the trace showed
        // 'q.scale f2': the second flush of the frame). Submitting a buffer
        // that was already consumed is at best wasted work and at worst a
        // native rejection, and it doubled the cost of the whole world pass.
        if(litBucketsFlushed) { flushPolygons(g); if(spriteRenderer!=null) spriteRenderer.flush(g); return; }
        if(Main.m3gLitWorld9 == 1 && litRenderer != null) litRenderer.flushBuckets(g);
        flushPolygons(g);
        if(spriteRenderer!=null) spriteRenderer.flush(g);
        if(Main.m3gLitWorld9 != 1 && litRenderer != null) litRenderer.flushBuckets(g);
        litBucketsFlushed = true;
    }

    void bindCanvasTarget(javax.microedition.lcdui.Graphics target) {graphics.bindTarget(target,false,0);cameraInstalled=false;}
    void releaseCanvasTarget() {graphics.releaseTarget();cameraInstalled=false;}

    /** A separate target pass AFTER scene presentation, in original HUD order. */
    public void drawCanvasImages(DirectX7 g,ScreenImages images,javax.microedition.lcdui.Graphics target) {
        if(bound) throw new IllegalStateException("foreground while scene target is bound");
        if(canvasImages==null) canvasImages=new M3GCanvasImages(this,graphics,maxTexture);
        canvasImages.draw(g,images,target);
    }

    public void prepareEffects(DirectX7 g,FrameEffects effects) {
        if(frameEffects==null) frameEffects=new M3GFrameEffects(this,graphics,maxTexture);
        frameEffects.prepare(g,effects);
    }

    /** Frame start (native passes): refresh true-3D lit-pass camera/lights. */
    public void beginFrame(DirectX7 g) {
        surfaceOpaque = false; // a new frame: nothing has covered the surface yet
        litBucketsFlushed = false; // a new frame may emit the world again
        for(int i = 0; i < textures.size(); i++)
            ((CachedTexture)textures.elementAt(i)).live = false;
        if(litBroken) {
            // The latch exists so a runtime that genuinely cannot render lit
            // geometry is not hammered every frame, but it must not be forever:
            // on a real device one failure during the first frames (a texture
            // still uploading, a target that just got bound) disabled hardware
            // lighting for the whole session, which from the outside looks
            // exactly like the build ignoring M3G_LIT_WORLD9. Retry on a slow
            // cadence - cheap, and it lets the pass come back on its own.
            if(++litRetryIn < LIT_RETRY_FRAMES) return;
            litRetryIn = 0;
            litBroken = false;
            M3GLitRenderer.statsErr = "";
        }
        if(Main.m3gWorldLighting == 0 && Main.m3gLitWorld9 == 0) return;
        try {
            if(litRenderer == null) litRenderer = new M3GLitRenderer(this, graphics);
            litRenderer.beginFrame(g);
        } catch(Throwable t) {
            // Device/runtime rejected the lit pass: degrade to packed, not crash.
            String m = t.getMessage();
            M3GLitRenderer.statsErr = "@" + M3GLitRenderer.STAGE
                    + (m == null ? "" : " " + m);
            litRenderer = null;
            litBroken = true;
        }
    }

    /** M3G_PROFILE=1: split each frame into passes and print the shares.
     *  Off in every shipped configuration; the hooks are one branch each.
     *  Read live: a cached copy would be fixed at class-load time, which is
     *  before Main parses setting.txt, and would then always read false. */
    static boolean profiling() { return Main.m3gProfile == 1; }

    /** The lit buckets for this frame have been emitted already. Cleared in
     *  beginFrame(), which is the only place a frame actually starts. */
    private boolean litBucketsFlushed;
    private boolean litBroken; // native side rejected the lit pass; retried
    private int litRetryIn;    // frames until the next attempt
    /** ~10 s at 20 FPS: long enough to stop a truly broken runtime from being
     *  hammered, short enough that a transient start-up failure heals while the
     *  player is still on the level. */
    private static final int LIT_RETRY_FRAMES = 200;

    /** Weather rides the shared polygon batch now: nothing native to prepare. */
    public void prepareWeather(DirectX7 g,ScreenLines weather) {
        // kept for SceneRenderer API; polygon weather needs no preallocation
    }

    /** Intermediate native -> CPU/resource transition, not the final readback. */
    public void barrier(DirectX7 g) {
        flushBatch(g);
        if(bound) g.frameCpuBarriers++;
        finish(g);
    }

    /** Complete native base; shared by lightning and certified closed skies. */
    void clearSky(DirectX7 g,int rgb) {
        if(flashBackground==null) {
            flashBackground=new Background();
            flashBackground.setColorClearEnable(true);flashBackground.setDepthClearEnable(false);
        }
        flashBackground.setColor(0xff000000 | rgb);
        bind(g,false);
        graphics.clear(flashBackground);
    }

    /** Re-arm the true-3D pass. The sticky latch exists so a runtime that
     *  really cannot render lit geometry is not hammered every frame, but it
     *  used to be permanent: one failure at level start - a half-initialised
     *  Graphics3D, a texture not yet resident - silently disabled hardware
     *  lighting for the rest of the session, and on screen that is
     *  indistinguishable from "the build ignores M3G_LIT_WORLD9". Called on
     *  level change and by an explicit user retry. */
    boolean isLitBroken() { return litBroken; }

    void reloadLitPass() {
        litBroken = false;
        litRetryIn = 0;
        M3GLitRenderer.statsErr = "";
    }

    void renderNative(DirectX7 g,VertexBuffer vertices,TriangleStripArray indices,Appearance material) {
        g.frameDrawCalls++; // API attempts, including a call that throws.
        graphics.render(vertices,indices,material,null);
    }

    Image2D uploadImage(DirectX7 g,int format,int width,int height,byte[] pixels) {
        Image2D image=new Image2D(format,width,height,pixels);
        g.frameTextureUploads++; // Successful Image2D creation, not cache hits.
        return image;
    }

    public void finish(DirectX7 g) {
        // finishImage also fills g.display directly when the viewport is scaled
        // (centered native-resolution blit with black bars, no upscale).
        // readback=false: this method copies the surface into g.display itself
        // two lines below, so letting finishImage do it first read the very
        // same Image into the very same int[] twice per frame - a 240x320x4
        // copy each time, on the critical path of every frame, for no pixels
        // that differ. finishImage (the image-presentation path) still copies,
        // because there the Image is the product and g.display may never be
        // touched by anyone else.
        Image result=finishImage(g,false);
        cameraInstalled = false;
        if(result!=null && targetScale()>=100) result.getRGB(g.display,0,g.width,0,0,g.width,g.height);
    }

    /** Final image presentation needs a released target, not a Java pixel copy.
     * Scaled viewport (M3G_SCREEN_SCALE<100) is blitted centered on screen at
     * native resolution (no upscale), surrounded by black bars, both into
     * g.display and into a reusable full-size presentation image. */
    public Image finishImage(DirectX7 g) { return finishImage(g, true); }

    /** @param readback copy the finished surface into g.display. The direct
     *  presentation path does that itself and passes false. */
    public Image finishImage(DirectX7 g,boolean readback) {
        flushBatch(g);
        if(!bound) return null;
        try { graphics.releaseTarget(); }
        finally { bound = false; cameraInstalled = false; }
        int scale=targetScale();
        if(scale >= 100) {
            // M3G_LAZY_READBACK: pulling the frame into int[] costs a full
            // framebuffer copy per frame (width*height*4 bytes) and forces the
            // driver to drain before CPU code may touch the result. The finished
            // Image is drawn directly by DirectX7.flushFrame, so the copy is
            // only needed when CPU pixels are actually read (getDisplay:
            // pause snapshot, developer overlays). Default off = old behaviour.
            if(readback && Main.m3gLazyReadback != 1) {
                surface.getRGB(g.display, 0, g.width, 0, 0, g.width, g.height);
            }
            return surface;
        }
        int sw=surface.getWidth(), sh=surface.getHeight();
        if(scaleBuffer == null || scaleBuffer.length != sw*sh) scaleBuffer=new int[sw*sh];
        surface.getRGB(scaleBuffer, 0, sw, 0, 0, sw, sh);
        int gw=g.width, gh=g.height;
        int ox=(gw-sw)>>1, oy=(gh-sh)>>1;
        if(present==null || present.getWidth()!=gw || present.getHeight()!=gh)
            present=Image.createImage(gw, gh);
        Graphics pg=present.getGraphics();
        pg.setColor(0, 0, 0);
        pg.fillRect(0, 0, gw, gh);
        pg.drawRGB(scaleBuffer, 0, sw, ox, oy, sw, sh, false);
        // keep g.display in the same centered state for direct display readers
        int[] dst=g.display;
        for(int i=dst.length-1; i>=0; i--) dst[i]=0xff000000;
        for(int row=0; row<sh; row++) {
            int di=(oy+row)*gw+ox, si=row*sw;
            for(int x=0; x<sw; x++) dst[di+x]=scaleBuffer[si+x];
        }
        return present;
    }

    private int[] scaleBuffer;
    private Image present;
    private Background depthClearBg;

    public void dispose() {
        if(batch!=null) batch.discard();
        batch=null;worldClip=null;
        try {
            if(bound) graphics.releaseTarget();
        } finally {
            bound = false;
            cameraInstalled = false;
            surface = null;projectionReady=false;
            positionScales[0]=positionScales[1]=Float.NaN;
            skyRenderer = null;
            spriteRenderer = null;
            panoramaRenderer = null;
            frameEffects = null;canvasImages=null;
            flashBackground = null;
            polygonModes.removeAllElements();polygonModes.addElement(polygon);polygonModes.addElement(screenPolygon);
            textures.removeAllElements();lastTexture=null;
            cachedPixels = 0;
            // The lit pass no longer owns a texture store of its own: it draws
            // through this cache. Dropping the reference is enough, and the
            // stale buckets must not survive into the next level either.
            litRenderer = null;
            litBroken = false;
        }
    }

    public boolean draw(DirectX7 g, RenderObject object, Texture texture, boolean fast) {
        batchable=false;
        if(object instanceof Sprite) flushPolygons(g);
        else {
            if(spriteRenderer!=null) spriteRenderer.flush(g);
            if(!(object instanceof Polygon3V) && !(object instanceof Polygon4V)) flushPolygons(g);
        }
        if(hardwarePerspective!=Main.m3gPerspective) {
            flushBatch(g);
            hardwarePerspective=Main.m3gPerspective;
            for(int i=0;i<polygonModes.size();i++)
                ((PolygonMode)polygonModes.elementAt(i)).setPerspectiveCorrectionEnable(hardwarePerspective);
        }
        if(object instanceof FrameEffects) {
            if(frameEffects==null) return false;
            return frameEffects.draw(g,(FrameEffects)object);
        }
        if(object instanceof SolidSky) {
            setScreenMode(true);
            SolidSky sky=(SolidSky)object;
            sky.prepare(g);
            float scale=ProjectedGeometry.pack(sky.vertices,4,g.centreX,g.centreY,
                    TexturingPers.move,false,positions,work);
            if(scale==0) return false;
            posArrays[1].set(0,4,positions);
            setPositionScale(1,scale);
            uniformColors(4,sky.color,255);
            colorArrays[1].set(0,4,colors);
            setTexture(null);setFog(null);
            setBlend(CompositingMode.REPLACE);setThreshold(0);
            // Full-frame opaque background; old pixels cannot contribute.
            bind(g,false);restoreCamera(g);
            renderNative(g,buffers[1],baseQuad,appearance);
            return true;
        }
        if(object instanceof SkyFlash) {
            clearSky(g,SkyFlash.COLOR);
            return true;
        }
        if(object instanceof ScreenLines) {
            return drawScreenLines(g,(ScreenLines)object);
        }
        if(object instanceof SkyMesh) {
            if(skyRenderer == null) skyRenderer = new M3GSkyRenderer(this, graphics);
            return skyRenderer.draw(g, (SkyMesh)object);
        }
        if(object instanceof PanoramaSky) {
            if(panoramaRenderer==null) panoramaRenderer=new M3GPanoramaRenderer(this,graphics);
            return panoramaRenderer.draw(g,(PanoramaSky)object);
        }
        if(object instanceof Sprite) {
            if(spriteRenderer==null) spriteRenderer=new M3GSpriteRenderer(this,graphics);
            return spriteRenderer.draw(g,(Sprite)object);
        }
        if(texture == null) return false;
        int mode = DirectX7.m3gDrawMode(texture.drawmode);
        // Modes 7/10 use MODULATE/MODULATE_X2, 11 uses DECAL, 12 uses native exponential Fog.
        // Mode 6 = hardware-lit opt-in (handled above; falls back to plain here).
        if(mode != 0 && mode != 1 && mode != 2 && mode != 3 && mode != 4
                && mode != 5 && mode != 6 && mode != 7 && mode != 8 && mode != 9 && mode != 10 && mode != 11 && mode != 12 && mode != 13) return false;
        boolean exponential=mode==12;
        boolean nativeFog=Main.fogQ>0
                && (mode==3 || mode==6 || (exponential && Mode12Fog.density()>0));
        // Invalid native ranges retain the original CPU material fallback.
        if(nativeFog && !exponential && DirectX7.fDist<=1) return false;
        Class type = object.getClass();
        if(type != Polygon3V.class && type != Polygon4V.class
                && type != LightedPolygon3V.class && type != LightedPolygon4V.class
                && type != ColorLightedPolygon3V.class && type != ColorLightedPolygon4V.class)
            return false;
        // World geometry retains backface culling.
        setScreenMode(false);
        int count;
        if(object instanceof Polygon3V) {
            Polygon3V p = (Polygon3V)object;
            vertices[0] = p.a; vertices[1] = p.b; vertices[2] = p.c;
            uv[0] = p.au; uv[1] = p.av; uv[2] = p.bu; uv[3] = p.bv;
            uv[4] = p.cu; uv[5] = p.cv;
            count = 3;
        } else {
            Polygon4V p = (Polygon4V)object;
            vertices[0] = p.a; vertices[1] = p.b; vertices[2] = p.c; vertices[3] = p.d;
            uv[0] = p.au; uv[1] = p.av; uv[2] = p.bu; uv[3] = p.bv;
            uv[4] = p.cu; uv[5] = p.cv; uv[6] = p.du; uv[7] = p.dv;
            count = 4;
        }
        // Render mode 6 = hardware light opt-in (no longer fog): only such
        // polys enter the true-3D lit pass. When the pass is absent/broken or
        // refuses the poly, mode 6 degrades to a plain textured polygon.
        // A native-side failure disables the pass instead of killing the frame.
        // M3G_LIT_WORLD9=1 also routes drawmode 9 through the same sorted,
        // depth-tested pass. Mode 9 keeps its baked lightmap: setColors replays
        // it for 9 and 13, so only the submission order changes.
        boolean world9 = mode == 9 && Main.m3gLitWorld9 == 1;
        if(mode == 6 || world9) {
            if(litRenderer != null) {
                try {
                    if(litRenderer.draw(g, object, texture, mode, count, vertices, uv)) return true;
                } catch(Throwable t) {
                    // A message-less java.lang.Error from the native m3g core
                    // carries no method name. The stage the pass reached is the
                    // only way to know which call the device rejected.
                    String m = t.getMessage();
                    M3GLitRenderer.statsErr = "@" + M3GLitRenderer.STAGE
                            + (m == null ? "" : " " + m);
                    litRenderer = null;
                    litBroken = true;
                }
            }
            // Fallback to the packed path, but KEEP mode 9. Demoting to 0 is
            // what made a refused world look like "the build ignores the
            // lightmap": setColors() replays the baked lightmap only for modes
            // 9 and 13, so a demoted polygon was painted with uniform white
            // vertex colours - no lightmap, no shading, no fog. Mode 9 already
            // is the original painter path (textured REPLACE, MODULATE, lightmap
            // in the colours), so leaving it alone restores exactly the pre-lit
            // look for that polygon instead of blanking it.
            // Mode 6 keeps its historical demotion to 0: its packed form is a
            // plain textured quad and changing that would change a picture.
            if(!world9) mode = 0;
        }
        materialMath.resetBounds();
        boolean fastPath = fast || (Main.persQ == 0 && !Main.mipMapping);
        boolean alternate = count == 4 && type == Polygon4V.class && !fastPath
                && (mode == 3 || mode == 6 || (exponential && nativeFog)) && Main.fogQ > 0 && alternateDiagonal();
        indices[1] = alternate ? alternateQuad : baseQuad;
        TriangleStripArray[] quadTriangles = alternate ? alternateQuadTriangles : baseQuadTriangles;
        boolean perspective = usePerspective(texture, count, fastPath);
        // TexturingPers interpolates 1 / (move - rz), not 1 / (distY - rz).
        // distY only controls screen projection and changes with the FOV.
        int distance = TexturingPers.move;
        float fogFar=nativeFog && !exponential?materialMath.fogFar(distance):0;
        if(nativeFog && !exponential && !(fogFar>1.0f)) return false;
        if(nativeFog && !perspective) selectPolygonMode(affineFogPolygon);
        float scale = nativeFog
                ? ProjectedGeometry.packFog(vertices,count,g.centreX,g.centreY,distance,positions,work)
                : ProjectedGeometry.pack(vertices,count,g.centreX,g.centreY,
                        distance,mode!=8 && perspective,positions,work);
        boolean clipped=scale==0;
        if(clipped && mode==8) return false;
        buffersScale=scale;
        RawImage raw = selectImage(texture, object, count, fastPath);
        if(mode == 8) {
            // CPU paintFill samples the first UV of EACH triangle, even if its
            // pixel is zero. No texture upload, alpha test, or lightmap applies.
            if(raw == null || raw.img == null || raw.img.length == 0) return false;
            RawImage fillBase = texture.mip != null && texture.mip.length > 0 ? texture.mip[0] : raw;
            if(fillBase == null) return false;
            int first = fillColor(raw, fillBase, 0, perspective);
            int second = count == 4 ? fillColor(raw, fillBase, 1, perspective) : first;
            // Split-color quads stay batchable: both halves share one
            // Appearance (REPLACE, no texture, no fog) and batch.add() copies
            // the arrays per call, so the per-triangle color rewrite below is
            // captured correctly. Unfogged per-triangle scale cancels in
            // projection, so the halves merge into the running world batch
            // instead of costing one graphics.render() each.
            batchable=true;
            int fillSlot = count - 3;
            setFog(null);
            setTexture(null);
            setBlend(CompositingMode.REPLACE);
            setThreshold(0);
            uniformColors(count, first, 255);
            render(g, fillSlot, first == second ? indices[fillSlot] : quadTriangles[0]);
            if(first != second) {
                uniformColors(count, second, 255);
                render(g, fillSlot, quadTriangles[1]);
            }
            return true;
        }
        if(!supportsTexture(raw)) return false;
        RawImage base = texture.mip != null && texture.mip.length > 0 ? texture.mip[0] : raw;
        if(base == null) return false;
        for(int i = 0; i < count; i++) {
            int u = uv[i * 2], v = uv[i * 2 + 1];
            if(u == 255 && base.w < 255) u = 256;
            if(v == 255 && base.h < 256) v = 256;
            if(u < Short.MIN_VALUE || u > Short.MAX_VALUE || v < Short.MIN_VALUE || v > Short.MAX_VALUE)
                return false;
            coords[i * 2] = (short)u;
            coords[i * 2 + 1] = (short)v;
        }
        if(clipped) {
            setColors(g,object,count,mode);
            // Fast path: all vertices inside screen and behind camera — skip
            // WorldClipGeometry plane clipping entirely (6 plane passes +
            // depth split + final pack are unnecessary for fully-visible polys).
            boolean fullyInside = true;
            for(int i = 0; i < count; i++) {
                Vertex v = vertices[i];
                if(v.sx < 0 || v.sx > g.width || v.sy < 0 || v.sy > g.height
                        || v.rz >= 0) {
                    fullyInside = false;
                    break;
                }
            }
            if(!fullyInside) {
                if(worldClip==null) worldClip=new WorldClipGeometry();
                // Retry depth bands for REPLACE/cutout and MODULATE (mode 7).
                // Bands partition each original triangle in order; never blend a
                // whole polygon twice or switch an animated river back to REPLACE.
                if(!worldClip.prepare(g,vertices,count,coords,colors,distance,perspective,nativeFog,alternate,hardwarePerspective,mode==0 || mode==3 || mode==6 || mode==7 || mode==9 || mode==13)) return false;
                if(worldClip.triangles==0) return true; // Completely clipped, no upload/barrier.
            }
        } else {
            setColors(g,object,count,mode);
        }
        // Standard native fog is a single pass for HQ/LQ and affine UVs alike.
        batchable=true;
        int kind = mode == 2 ? ADD_TEXTURE : mode == 1 || mode == 11 || (mode == 4 && raw.alphaMixing) ? ALPHA_TEXTURE : OPAQUE_TEXTURE;
        // Cache eviction/uploads are done outside a bound target.
        Texture2D nativeTexture = texture(g, raw, kind);
        CachedTexture nativeEntry=lastTexture;
        batchState.imageFilter=nativeEntry.filter;
        batchState.levelFilter=nativeEntry.levelFilter;
        batchState.opaque=kind==OPAQUE_TEXTURE && nativeEntry.cutoutThreshold==0;
        int slot = count - 3;
        if(!batchable) posArrays[slot].set(0, count, positions);
        if(!batchable) uvArrays[slot].set(0, count, coords);
        if(!batchable) setPositionScale(slot,scale);
        setFog(null);
        setTexture(nativeTexture);
        // Water UV-scroll on the native (non-lit) path as well: "wat" moves
        // the texture transform instead of flipbook frames (kordos FX).
        if(texture.uvScroll != 0) {
            float o = ((float)(System.currentTimeMillis() % 4000000L) / 1000f
                    * texture.uvScrollSpeed) % 1.0f;
            if(texture.uvScroll == 2) nativeTexture.setTranslation(-o, 0f, 0f);
            else nativeTexture.setTranslation(0f, -o, 0f);
        }
        setFunction(nativeTexture,mode==11?Texture2D.FUNC_DECAL:mode==5?Texture2D.FUNC_BLEND:Texture2D.FUNC_MODULATE);
        batchState.blendColor=mode==5?DirectX7.getM3GBlendColor():0;
        setBlend(mode == 2 ? CompositingMode.ALPHA_ADD
                : mode == 7 ? CompositingMode.MODULATE_X2
                : mode == 10 ? CompositingMode.MODULATE
                : kind == ALPHA_TEXTURE && mode!=11 ? CompositingMode.ALPHA : CompositingMode.REPLACE);
        setThreshold(kind==ALPHA_TEXTURE?0:nativeEntry.cutoutThreshold);

        // Modes 9/13 keep only baked vertex colors, with no distance Fog.
        // Native Fog: linear for 3/6, exponential for 12.
        if(!clipped) setColors(g, object, count, mode);
        if(!batchable) colorArrays[slot].set(0, count, colors);
        if(nativeFog) {
            // Packed eye distance = 1 - rz/move, not raw engine depth.
            batchState.fogMode=exponential?Fog.EXPONENTIAL:Fog.LINEAR;
            batchState.density=exponential?Mode12Fog.density():0;
            batchState.near=exponential?0:1.0f;batchState.far=fogFar;
            batchState.fogColor=exponential?Mode12Fog.color():mode==3?DirectX7.fogc&0xffffff:0;
            setFog(fog);
        }
        if(clipped) {
            for(int i=0;i<worldClip.triangles;i++) {
                System.arraycopy(worldClip.xyz,i*9,positions,0,9);
                System.arraycopy(worldClip.uv,i*6,coords,0,6);
                System.arraycopy(worldClip.rgba,i*12,colors,0,12);
                buffersScale=worldClip.scales[i];render(g,0,indices[0]);
            }
        } else render(g, slot, indices[slot]);
        setFog(null);
        return true;
    }

    private int fillColor(RawImage raw, RawImage base, int vertex, boolean perspective) {
        int u = uv[vertex * 2], v = uv[vertex * 2 + 1];
        if(raw.w != 256) {
            if(u == 255 && base.w < 255) u = 256;
            u = perspective ? (u * raw.w) >> 8 : u * raw.w / 256;
        }
        if(raw.h != 256) {
            if(v == 255 && base.h < 256) v = 256;
            v = perspective ? (v * raw.h) >> 8 : v * raw.h / 256;
        }
        return raw.img[((v << raw.widthBIT) + u) & (raw.img.length - 1)] & 0xffffff;
    }

    /** Screen-space effect quads (rain/snow/lightning) as untextured
     * vertex-colored polygons inside the SHARED polygon batch: same thin
     * four-corner geometry ScreenLineGeometry builds, but merged with the
     * world packets instead of owning a separate native call chain.
     * Constant homogeneous weight (w=1) packs pixel-exact screen offsets;
     * the batch appearance stays depth-off as before. */
    private boolean drawScreenLines(DirectX7 g, ScreenLines lines) {
        if(lines.count == 0) return true;
        int drawn = 0;
        for(int i = 0; i < lines.count; i++) {
            if(!overlayGeometry.build(lines, i, g.width, g.height)) continue;
            if(overlayVertsReady == 0) {
                for(int v = 0; v < 4; v++) overlayVerts[v] = new Vertex();
                overlayVertsReady = 1;
            }
            float[] q = overlayGeometry.xy;
            boolean rect = lines.get(i, 6) == ScreenLines.RECT;
            // Reorder strip pairs to quad perimeter (0->1->2->3) matching
            // baseQuad's {0,1,3,1,2,3} triangulation.
            overlaySet(0, q[0], q[1]);
            overlaySet(1, rect ? q[2] : q[4], rect ? q[3] : q[5]);
            overlaySet(2, q[6], q[7]);
            overlaySet(3, rect ? q[4] : q[2], rect ? q[5] : q[3]);
            float scale = ProjectedGeometry.pack(overlayVerts, 4, g.centreX, g.centreY,
                    TexturingPers.move, false, positions, work);
            if(scale == 0) continue; // pixel offsets do not fit: skip like the old clip
            buffersScale = scale;
            uniformColors(4, lines.get(i, 5), 255);
            batchable = true;
            selectPolygonMode(screenPolygon); // CULL_NONE, like the line stripper
            setFog(null);
            setTexture(null);
            setBlend(CompositingMode.REPLACE);
            setThreshold(0);
            render(g, 1, baseQuad);
            drawn++;
        }
        return drawn > 0; // false -> caller falls back like the old line renderer
    }

    private void overlaySet(int v, float x, float y) {
        Vertex vertex = overlayVerts[v];
        vertex.sx = (int)(x + (x < 0 ? -0.5f : 0.5f));
        vertex.sy = (int)(y + (y < 0 ? -0.5f : 0.5f));
        vertex.rz = 0; // constant homogeneous weight 1: pixel-exact overlay quad
    }

    private void render(DirectX7 g, int slot, TriangleStripArray triangles) {
        if(batchable) {
            if(batch==null) batch=new M3GPolygonBatch(this,graphics);
            int[] order=triangles==baseQuad?QUAD:triangles==alternateQuad?ALT_QUAD
                    :triangles==baseQuadTriangles[0]?ABD:triangles==baseQuadTriangles[1]?BCD
                    :triangles==alternateQuadTriangles[1]?ACD:TRI;
            batch.add(g,slot+3,buffersScale,positions,coords,colors,order,triangles,batchState);
        } else {
            flushBatch(g);bind(g);restoreCamera(g);
            renderNative(g,buffers[slot],triangles,appearance);
        }
    }

    private void setPositionScale(int slot,float scale) {
        if(positionScales[slot]!=scale) {
            buffers[slot].setPositions(posArrays[slot],scale,null);
            positionScales[slot]=scale;
        }
    }

    /** M3G port of the CPU affine triangle painter: draws one textured
     *  triangle through the hardware pipe instead of pixel-bashing g.display.
     *  Coordinates are screen-space (sx/sy), UVs are texel-space (0..255). */
    public void paintM3GTriangle(DirectX7 g, Texture texture,
            Vertex a, int au, int av,
            Vertex b, int bu, int bv,
            Vertex c, int cu, int cv) {
        paintM3GTriangle(g, texture, a, au, av, b, bu, bv, c, cu, cv,
                CompositingMode.REPLACE, Texture2D.FUNC_MODULATE, false);
    }

    /** Same, but with the fill state chosen by the caller. The fill benchmark
     *  needs this: the three-argument-per-vertex version always used REPLACE +
     *  MODULATE, so timing it in a loop measured that one combination fourteen
     *  times and called the samples "modes". */
    public void paintM3GTriangle(DirectX7 g, Texture texture,
            Vertex a, int au, int av,
            Vertex b, int bu, int bv,
            Vertex c, int cu, int cv,
            int blending, int function, boolean fogged) {
        RawImage raw = texture.rImg;
        if(raw == null || raw.img == null || raw.img.length == 0) return;
        if(!powerOfTwo(raw.w) || !powerOfTwo(raw.h)) return;
        if(raw.w > maxTexture || raw.h > maxTexture) return;

        Texture2D nativeTexture = texture(g, raw, OPAQUE_TEXTURE);
        if(nativeTexture == null) return;

        appearance.setTexture(0, nativeTexture);
        nativeTexture.setBlending(function);
        composite.setBlending(blending);
        composite.setAlphaThreshold(0);
        appearance.setCompositingMode(composite);
        appearance.setFog(fogged ? fog : null);

        short[] xyz = positions;
        xyz[0] = (short)a.sx; xyz[1] = (short)a.sy; xyz[2] = 0;
        xyz[3] = (short)b.sx; xyz[4] = (short)b.sy; xyz[5] = 0;
        xyz[6] = (short)c.sx; xyz[7] = (short)c.sy; xyz[8] = 0;

        short[] uv = coords;
        uv[0] = (short)au; uv[1] = (short)av;
        uv[2] = (short)bu; uv[3] = (short)bv;
        uv[4] = (short)cu; uv[5] = (short)cv;

        byte[] rgba = colors;
        for(int i = 0; i < 3; i++) {
            rgba[i * 4] = (byte)255;
            rgba[i * 4 + 1] = (byte)255;
            rgba[i * 4 + 2] = (byte)255;
            rgba[i * 4 + 3] = (byte)255;
        }

        int slot = 0; // count == 3
        posArrays[slot].set(0, 3, xyz);
        uvArrays[slot].set(0, 3, uv);
        colorArrays[slot].set(0, 3, rgba);
        setPositionScale(slot, 1.0f);

        flushBatch(g);
        bind(g);
        restoreCamera(g);
        renderNative(g, buffers[slot], indices[0], appearance);
    }

    void renderSingle(DirectX7 g,int count,float scale,short[] xyz,short[] uv,byte[] rgba,
            TriangleStripArray triangles,Appearance state) {
        int slot=count-3;
        posArrays[slot].set(0,count,xyz);uvArrays[slot].set(0,count,uv);colorArrays[slot].set(0,count,rgba);
        setPositionScale(slot,scale);
        bind(g);restoreCamera(g);renderNative(g,buffers[slot],triangles,state);
    }

    Texture2D texture(DirectX7 g, RawImage raw, int kind) {
        Texture2D result = findTexture(raw, kind);
        if(result == null) {
            barrier(g);
            result = createTexture(g,raw,kind);
        }
        return result;
    }

    private void uniformColors(int count, int rgb, int alpha) {
        for(int i = 0; i < count; i++) {
            colors[i * 4] = (byte)(rgb >> 16);
            colors[i * 4 + 1] = (byte)(rgb >> 8);
            colors[i * 4 + 2] = (byte)rgb;
            colors[i * 4 + 3] = (byte)alpha;
        }
    }

    private static int clamp(int value) {return Math.max(0,Math.min(255,value));}

    // Lit pass (M3GLitRenderer) replays this exact baked-lightmap recipe.
    void setColors(DirectX7 g, RenderObject object, int count, int mode) {
        // M3G_LIT_NOVCOL builds cut static (baked) lighting everywhere:
        // vertex colors are forced white so only hardware lights shade.
        if(Main.m3gLitNoVertexColors) {uniformColors(count,0xffffff,255);return;}
        boolean lit=(mode==9 || mode==13) && Main.fogQ>0;
        // Baked lightmap values only: no muzzle/torch/camera lighting here.
        // Classify the polygon once, not once for every vertex/channel.
        int type=!lit?0:object instanceof LightedPolygon3V?1:object instanceof LightedPolygon4V?2
                :object instanceof ColorLightedPolygon3V?3:object instanceof ColorLightedPolygon4V?4:0;
        if(type==0) {uniformColors(count,0xffffff,255);return;}
        boolean gray=mode==9 || type<=2;
        for(int i=0;i<count;i++) {
            int r,green=0,b=0;
            if(type==1) {
                LightedPolygon3V p=(LightedPolygon3V)object;r=i==0?p.la:i==1?p.lb:p.lc;
            } else if(type==2) {
                LightedPolygon4V p=(LightedPolygon4V)object;r=i==0?p.la:i==1?p.lb:i==2?p.lc:p.ld;
            } else if(type==3) {
                ColorLightedPolygon3V p=(ColorLightedPolygon3V)object;r=i==0?p.ar:i==1?p.br:p.cr;
                if(!gray) {green=i==0?p.ag:i==1?p.bg:p.cg;b=i==0?p.ab:i==1?p.bb:p.cb;}
            } else {
                ColorLightedPolygon4V p=(ColorLightedPolygon4V)object;r=i==0?p.ar:i==1?p.br:i==2?p.cr:p.dr;
                if(!gray) {green=i==0?p.ag:i==1?p.bg:i==2?p.cg:p.dg;b=i==0?p.ab:i==1?p.bb:i==2?p.cb:p.db;}
            }
            r=clamp(r);
            if(gray) green=b=r;
            else {
                green=clamp(green);b=clamp(b);
                // Keep the configured LQ grayscale lightmap; Fog is independent.
                if(Main.fogQ==1) r=green=b=(r+green+b)/3;
            }
            colors[i*4]=(byte)r;colors[i*4+1]=(byte)green;colors[i*4+2]=(byte)b;colors[i*4+3]=(byte)255;
        }
    }

    private boolean usePerspective(Texture texture, int count, boolean fast) {
        if(fast || Main.persQ == 0) return false;
        if(Main.persQ == 4) return true;
        if(!texture.perspectiveCorrection) return false;
        materialMath.measure(vertices,count);
        int xLimit = count == 3 ? (Main.persQ < 3 ? 30 : 15) : (Main.persQ < 3 ? 70 : 50);
        int yLimit = count == 3 ? xLimit : (Main.persQ < 3 ? 100 : 70);
        return materialMath.spanX>xLimit || materialMath.spanY>yLimit;
    }

    private boolean alternateDiagonal() {
        int a = vertices[0].rz, b = vertices[1].rz, c = vertices[2].rz, d = vertices[3].rz;
        // Preserve Polygon4V.min's equal-depth special case as well.
        int farthest = a == b || b == d || b == c || d == c
                ? Math.min(a, Math.min(c, d)) : Math.min(Math.min(a, b), Math.min(c, d));
        return farthest != Math.min(a, c);
    }

    private RawImage selectImage(Texture texture, RenderObject object, int count, boolean fast) {
        RawImage raw = texture.rImg;
        if(!fast && Main.mipMapping && texture.mip != null && texture.mip.length > 0) {
            raw = texture.mip[0];
            materialMath.measure(vertices,count);
            int minU=uv[0],maxU=minU,minV=uv[1],maxV=minV;
            for(int i=1;i<count;i++) {
                minU=Math.min(minU,uv[i*2]);maxU=Math.max(maxU,uv[i*2]);
                minV=Math.min(minV,uv[i*2+1]);maxV=Math.max(maxV,uv[i*2+1]);
            }
            int factor = object.ny > 4000 || object.ny < -4000 || Main.q > 999 ? 2 : 3;
            long screen=(materialMath.spanX+materialMath.spanY)/2*factor;
            for(int level = 1; level < texture.mip.length && level < 3; level++) {
                if(raw == null || texture.mip[level] == null) break;
                long texels = (((long)maxU - minU) * raw.w + ((long)maxV - minV) * raw.h) / 256;
                if(screen >= texels) break;
                raw = texture.mip[level];
            }
        }
        return raw;
    }

    private static boolean powerOfTwo(int n) { return n > 0 && (n & (n - 1)) == 0; }

    /** M3G_LIT_MIP: world textures the lit pass draws may carry a full mip
     * chain, but they must live in ONE cache entry. A parallel store here was
     * the bug: every world texture existed twice (once as a base level for the
     * packed path, once as a chain for the lit path), nothing ever evicted the
     * chains, and they were never counted against CACHE_PIXELS. Sprites stay on
     * base level: the sprite renderer applies its own filtering, and a chain
     * would just be dead memory. */
    private boolean wantsMipChain(int kind) {
        return Main.m3gLitMip == 1 && Main.m3gLitWorld9 == 1
                && kind == OPAQUE_TEXTURE;
    }

    private Texture2D findTexture(RawImage image, int kind) {
        // mipChain is NOT part of the key on purpose. A texture either has a
        // chain or it does not, and that never changes for a given image; if it
        // does not, the base level is the correct fallback. Matching on it made
        // entries that failed to build a chain unmatchable, so they were rebuilt
        // on every single lookup - a cache that silently did nothing.
        CachedTexture last=lastTexture;
        if(last!=null && last.image==image && last.pixels==image.img
                && last.width==image.w && last.height==image.h && last.kind==kind) {last.live=true;return last.texture;}
        for(int i = 0; i < textures.size(); i++) {
            CachedTexture cached = (CachedTexture)textures.elementAt(i);
            if(cached.image == image && cached.pixels == image.img && cached.width == image.w
                    && cached.height == image.h && cached.kind == kind) {cached.live=true;lastTexture=cached;return cached.texture;}
        }
        return null;
    }

    float fogFar(int move) {return materialMath.fogFar(move);}

    // Capture immediately after texture(): a later lookup changes lastTexture.
    float textureCutoutThreshold() {return lastTexture.cutoutThreshold;}
    int textureImageFilter() {return lastTexture.filter;}
    int textureLevelFilter() {return lastTexture.levelFilter;}


    /** Levels below the base come from the loader's chain, rebuilt ONLY when the
     * live base pixel array differs (animated water swaps it). Returns null when
     * no usable chain exists, which keeps the plain base-level upload. */
    private RawImage[] chainFor(RawImage base, int kind) {
        // The loader caches chains per FILE, not per RawImage: find the chain
        // whose level 0 is this very pixel array.
        RawImage[] chain = base.mipChain;
        if(chain == null || chain.length < 2 || chain[0] != base) return null;
        int levels = Math.min(3, chain.length);
        RawImage[] out = new RawImage[levels];
        for(int l = 0; l < levels; l++) {
            if(chain[l] == null || chain[l].w <= 0 || chain[l].h <= 0) return null;
            out[l] = chain[l];
        }
        return out;
    }

    /** Same alpha/blend recipe as the base level, per chain level. No sprite
     * reduction here: chains are only built for non-sprite world textures. */
    private static byte[] convertLevel(RawImage m, int kind, boolean mul) {
        int n = m.w * m.h;
        byte[] out = new byte[n * 4];
        for(int i = 0; i < n; i++) {
            int pixel = m.img[i];
            int rgb = mul ? DirectX7.spriteModulateSource(pixel, kind - SPRITE_MUL)
                    : kind == ADD_TEXTURE ? pixel & 0xfefefe : pixel;
            out[i * 4] = (byte)(rgb >> 16);
            out[i * 4 + 1] = (byte)(rgb >> 8);
            out[i * 4 + 2] = (byte)rgb;
            out[i * 4 + 3] = kind == ALPHA_TEXTURE ? (byte)(pixel >>> 24)
                    : (byte)(mul || kind == SPRITE_ADD || pixel != 0 ? 255 : 0);
        }
        return out;
    }

    private static int[] argbFrom(byte[] rgba, int pixels) {
        int[] out = new int[pixels];
        for(int i = 0; i < pixels; i++) {
            out[i] = ((rgba[i * 4 + 3] & 255) << 24) | ((rgba[i * 4] & 255) << 16)
                    | ((rgba[i * 4 + 1] & 255) << 8) | (rgba[i * 4 + 2] & 255);
        }
        return out;
    }

    private Texture2D createTexture(DirectX7 g,RawImage image,int kind) {
        boolean mul=kind>=SPRITE_MUL && kind<=SPRITE_MUL+6;
        boolean sprite=(kind>=SPRITE_CUTOUT && kind<=SPRITE_ADD) || mul;
        int width=sprite?spriteDimension(image.w):image.w;
        int height=sprite?spriteDimension(image.h):image.h;
        int size = width * height;
        boolean mip = wantsMipChain(kind) && image.w > 1 && image.h > 1
                && powerOfTwo(image.w) && powerOfTwo(image.h);
        // A chain occupies ~4/3 of the base level, not 1x: charge it honestly or
        // the cache under-counts exactly the textures that are the most expensive.
        int cost = mip ? size + size / 3 : size;
        while(textures.size() > 0 && (textures.size() >= CACHE_ENTRIES || cachedPixels + cost > CACHE_PIXELS)) {
            // Prefer the oldest entry that this frame has not touched yet. A
            // mid-frame eviction of a live texture is far worse than a slightly
            // over-budget cache: the next polygon rebinds a NEW native object,
            // which splits batches and uploads the same pixels again.
            int victim = -1;
            for(int i = 0; i < textures.size(); i++) {
                if(!((CachedTexture)textures.elementAt(i)).live) {victim = i; break;}
            }
            if(victim < 0) victim = 0; // everything is live: fall back to oldest
            CachedTexture oldest = (CachedTexture)textures.elementAt(victim);
            cachedPixels -= oldest.cost;
            if(lastTexture==oldest) lastTexture=null;
            textures.removeElementAt(victim);
        }
        byte[] rgba = new byte[size * 4];
        boolean opaque=true;
        for(int i = 0; i < size; i++) {
            // Sprite-only nearest-neighbor reduction, done once per cache miss.
            // Sample texel centers; Fog does not need a separate mask upload.
            // POT images pass through unchanged. Never mutate the original asset.
            int pixel = image.img[sprite?((2*(i/width)+1)*image.h/(2*height))*image.w
                    +(2*(i%width)+1)*image.w/(2*width):i];
            // Legacy additive source discards channel low bits. Hardware cannot
            // mask destination low bits; native ADD may differ by one per channel.
            int rgb = mul?DirectX7.spriteModulateSource(pixel,kind-SPRITE_MUL)
                    :kind == ADD_TEXTURE || kind == SPRITE_ADD ? pixel & 0xfefefe : pixel;
            rgba[i * 4] = (byte)(rgb >> 16);
            rgba[i * 4 + 1] = (byte)(rgb >> 8);
            rgba[i * 4 + 2] = (byte)rgb;
            rgba[i * 4 + 3] = kind==ALPHA_TEXTURE || kind==SPRITE_ALPHA
                    ? (byte)(pixel >>> 24) : (byte)(mul || kind==SPRITE_ADD || kind==PANORAMA_TEXTURE || pixel!=0 ? 255 : 0);
            // Inspect the actual uploaded alpha, including reduced sprite copies,
            // in this existing conversion loop. No extra scan on cache hits.
            if((rgba[i*4+3]&255)!=255) opaque=false;
        }
        CachedTexture cached = new CachedTexture();
        cached.image = image; cached.pixels = image.img; cached.width = image.w; cached.height = image.h;
        cached.kind = kind;cached.cutoutThreshold=opaque?0:0.5f;
        Texture2D built = null;
        if(mip) {
            // Levels come from the mip chain the texture loader already built
            // (texture.mip), skipped and refilled exactly like the base level so
            // cutout and additive semantics stay identical on every level.
            RawImage[] chain = chainFor(image, kind);
            if(chain != null) {
                try {
                    Image[] images = new Image[chain.length];
                    for(int l = 0; l < chain.length; l++) {
                        byte[] lvl = l == 0 ? rgba : convertLevel(chain[l], kind, mul);
                        images[l] = Image.createRGBImage(argbFrom(lvl, chain[l].w * chain[l].h),
                                chain[l].w, chain[l].h, true);
                    }
                    built = new Texture2D(new Image2D(Image2D.RGBA, images));
                    g.frameTextureUploads++;
                    // JSR-184: FILTER_BASE_LEVEL means "use the base level ONLY",
                    // i.e. it switches mipmapping off for that texture. A chain
                    // uploaded with FILTER_BASE_LEVEL is dead weight: the whole
                    // chain is resident and never sampled. NEAREST picks the
                    // nearest level and point-samples inside it - mip filtering
                    // without any bilinear change, which is what this flag means.
                    built.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
                } catch(Exception e) {
                    built = null; // runtime without mip chains: plain upload below
                } catch(Error e) {
                    built = null;
                }
            }
        }
        if(built != null) {
            cached.texture = built;
            cached.mipChain = true;
            cached.levelFilter = Texture2D.FILTER_NEAREST;
            cached.cost = cost;
        } else {
            cached.texture = new Texture2D(uploadImage(g,Image2D.RGBA,width,height,rgba));
            cached.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
            cached.mipChain = false;
            cached.levelFilter = Texture2D.FILTER_BASE_LEVEL;
            cached.cost = size;
        }
        cached.texture.setWrapping(sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT,
                sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT);
        cached.texture.setBlending(Texture2D.FUNC_MODULATE);
        cached.live = true;
        lastTexture=cached;
        textures.addElement(cached);cachedPixels += cached.cost;
        return cached.texture;
    }
}
