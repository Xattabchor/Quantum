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
    private float buffersScale;
    private static final int[] TRI={0,1,2}, QUAD={0,1,3,1,2,3}, ALT_QUAD={0,1,2,0,2,3},
            ABD={0,1,3}, BCD={1,2,3}, ACD={0,2,3};
    private boolean hardwarePerspective = Main.m3gPerspective;
    private final Vector polygonModes = new Vector();
    private final CompositingMode composite = new CompositingMode();
    private final Fog fog = new Fog();
    private final Vertex[] vertices = new Vertex[4];
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
    private M3GScreenLines screenLines;
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
        if(texture==null) {batchState.function=0;batchState.imageFilter=Texture2D.FILTER_NEAREST;}
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
        graphics.clear(depthClearBg);
        graphics.setViewport(0, 0, surface.getWidth(), surface.getHeight());
        graphics.setCamera(camera, null);
        graphics.resetLights();
        if(litRenderer != null) litRenderer.applyLights(g);
    }

    void restoreCamera(DirectX7 g) {
        if(!bound) return;
        graphics.setViewport(0,0,g.width,g.height);
        graphics.setCamera(camera,null);
    }

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

    private void flushPolygons(DirectX7 g) {if(batch!=null) batch.flush(g);}
    void flushBatch(DirectX7 g) {
        flushPolygons(g);
        if(spriteRenderer!=null) spriteRenderer.flush(g);
        if(litRenderer!=null) litRenderer.flushBuckets(g); // deferred lit buckets
    }

    void bindCanvasTarget(javax.microedition.lcdui.Graphics target) {graphics.bindTarget(target,false,0);}
    void releaseCanvasTarget() {graphics.releaseTarget();}

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
        if(Main.m3gWorldLighting == 0 || litBroken) return;
        try {
            if(litRenderer == null) litRenderer = new M3GLitRenderer(this, graphics);
            litRenderer.beginFrame(g);
        } catch(Throwable t) {
            // Device/runtime rejected the lit pass: degrade to packed, not crash.
            M3GLitRenderer.statsErr = t.toString();
            litRenderer = null;
            litBroken = true;
        }
    }

    private boolean litBroken; // sticky: native side rejected the lit pass

    /** Prepare native weather before binding the shared scene target. */
    public void prepareWeather(DirectX7 g,ScreenLines weather) {
        if(weather!=null && weather.count>0 && screenLines==null)
            screenLines=new M3GScreenLines(this,graphics);
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
        Image result=finishImage(g);
        if(result!=null && targetScale()>=100) result.getRGB(g.display,0,g.width,0,0,g.width,g.height);
    }

    /** Final image presentation needs a released target, not a Java pixel copy.
     * Scaled viewport (M3G_SCREEN_SCALE<100) is blitted centered on screen at
     * native resolution (no upscale), surrounded by black bars, both into
     * g.display and into a reusable full-size presentation image. */
    public Image finishImage(DirectX7 g) {
        flushBatch(g);
        if(!bound) return null;
        try { graphics.releaseTarget(); }
        finally { bound = false; }
        int scale=targetScale();
        if(scale >= 100) return surface;
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
            surface = null;projectionReady=false;
            positionScales[0]=positionScales[1]=Float.NaN;
            skyRenderer = null;
            screenLines = null;
            spriteRenderer = null;
            panoramaRenderer = null;
            frameEffects = null;canvasImages=null;
            flashBackground = null;
            polygonModes.removeAllElements();polygonModes.addElement(polygon);polygonModes.addElement(screenPolygon);
            textures.removeAllElements();lastTexture=null;
            cachedPixels = 0;
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
            if(screenLines == null) screenLines = new M3GScreenLines(this,graphics);
            return screenLines.draw(g,(ScreenLines)object);
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
        // Mode 1 (legacy CPU fog-tint texturing) is dead weight on M3G:
        // repurposed as flat fill, handled by the mode-8 solid-fill path.
        if(mode == 1) mode = 8;
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
        if(mode == 6) {
            if(litRenderer != null) {
                try {
                    if(litRenderer.draw(g, object, texture, mode, count, vertices, uv)) return true;
                } catch(Throwable t) {
                    M3GLitRenderer.statsErr = t.toString();
                    litRenderer = null;
                    litBroken = true;
                }
            }
            mode = 0;
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
            batchable=first==second;
            if(!batchable) flushBatch(g);
            int fillSlot = count - 3;
            if(!batchable) posArrays[fillSlot].set(0, count, positions);
            if(!batchable) setPositionScale(fillSlot,scale);
            setFog(null);
            setTexture(null);
            setBlend(CompositingMode.REPLACE);
            setThreshold(0);
            uniformColors(count, first, 255);
            if(!batchable) colorArrays[fillSlot].set(0, count, colors);
            render(g, fillSlot, first == second ? indices[fillSlot] : quadTriangles[0]);
            if(first != second) {
                uniformColors(count, second, 255);
                if(!batchable) colorArrays[fillSlot].set(0, count, colors);
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
            if(worldClip==null) worldClip=new WorldClipGeometry();
            // Retry depth bands for REPLACE/cutout and MODULATE (mode 7).
            // Bands partition each original triangle in order; never blend a
            // whole polygon twice or switch an animated river back to REPLACE.
            if(!worldClip.prepare(g,vertices,count,coords,colors,distance,perspective,nativeFog,alternate,hardwarePerspective,mode==0 || mode==3 || mode==6 || mode==7 || mode==9 || mode==13)) return false;
            if(worldClip.triangles==0) return true; // Completely clipped, no upload/barrier.
        }
        // Standard native fog is a single pass for HQ/LQ and affine UVs alike.
        batchable=true;
        int kind = mode == 2 ? ADD_TEXTURE : mode == 11 || mode == 4 ? ALPHA_TEXTURE : OPAQUE_TEXTURE;
        // Cache eviction/uploads are done outside a bound target.
        Texture2D nativeTexture = texture(g, raw, kind);
        CachedTexture nativeEntry=lastTexture;
        batchState.imageFilter=nativeEntry.filter;
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
                : mode == 10 ? CompositingMode.MODULATE_X2
                : mode == 7 ? CompositingMode.MODULATE
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

    private Texture2D findTexture(RawImage image, int kind) {
        CachedTexture last=lastTexture;
        if(last!=null && last.image==image && last.pixels==image.img
                && last.width==image.w && last.height==image.h && last.kind==kind) return last.texture;
        for(int i = 0; i < textures.size(); i++) {
            CachedTexture cached = (CachedTexture)textures.elementAt(i);
            if(cached.image == image && cached.pixels == image.img && cached.width == image.w
                    && cached.height == image.h && cached.kind == kind) {lastTexture=cached;return cached.texture;}
        }
        return null;
    }

    float fogFar(int move) {return materialMath.fogFar(move);}

    // Capture immediately after texture(): a later lookup changes lastTexture.
    float textureCutoutThreshold() {return lastTexture.cutoutThreshold;}
    int textureImageFilter() {return lastTexture.filter;}

    private Texture2D createTexture(DirectX7 g,RawImage image,int kind) {
        boolean mul=kind>=SPRITE_MUL && kind<=SPRITE_MUL+6;
        boolean sprite=(kind>=SPRITE_CUTOUT && kind<=SPRITE_ADD) || mul;
        int width=sprite?spriteDimension(image.w):image.w;
        int height=sprite?spriteDimension(image.h):image.h;
        int size = width * height;
        while(textures.size() > 0 && (textures.size() >= CACHE_ENTRIES || cachedPixels + size > CACHE_PIXELS)) {
            CachedTexture oldest = (CachedTexture)textures.elementAt(0);
            cachedPixels -= oldest.cost;
            if(lastTexture==oldest) lastTexture=null;
            textures.removeElementAt(0);
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
        cached.kind = kind;cached.cost=size;
        cached.cutoutThreshold=opaque?0:0.5f;
        cached.texture = new Texture2D(uploadImage(g,Image2D.RGBA,width,height,rgba));
        cached.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        cached.texture.setWrapping(sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT,
                sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT);
        cached.texture.setBlending(Texture2D.FUNC_MODULATE);
        lastTexture=cached;
        textures.addElement(cached);cachedPixels += size;
        return cached.texture;
    }
}
