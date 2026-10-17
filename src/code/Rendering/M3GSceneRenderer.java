package code.Rendering;

import code.Rendering.Meshes.*;
import code.Gameplay.Map.LightMapper;
import code.Math.MathUtils;
import code.utils.Main;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Ordered M3G polygon packets, with CPU barriers for legacy materials. */
public final class M3GSceneRenderer implements SceneRenderer {
    private final Graphics3D graphics;
    private final javax.microedition.m3g.Camera camera = new javax.microedition.m3g.Camera();
    private final Appearance appearance = new Appearance();
    private final PolygonMode polygon = new PolygonMode();
    private final PolygonMode screenPolygon = new PolygonMode();
    private boolean screenMode;
    private M3GPolygonBatch batch;
    private final M3GPolygonBatch.State batchState=new M3GPolygonBatch.State();
    private CachedTexture lastTexture;
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
    private final byte[] colors = new byte[16];
    private final float[] work = new float[12];
    private final VertexBuffer[] buffers = new VertexBuffer[2];
    private final VertexArray[] posArrays = new VertexArray[2];
    private final VertexArray[] uvArrays = new VertexArray[2];
    private final VertexArray[] colorArrays = new VertexArray[2];
    private final TriangleStripArray[] indices = new TriangleStripArray[2];
    private final TriangleStripArray[] baseQuadTriangles = new TriangleStripArray[2];
    private final TriangleStripArray[] alternateQuadTriangles = new TriangleStripArray[2];
    private final TriangleStripArray baseQuad, alternateQuad;
    static final int OPAQUE_TEXTURE = 0, ALPHA_TEXTURE = 1, FOG_MASK = 2, ADD_TEXTURE = 3;
    static final int SPRITE_CUTOUT=4, SPRITE_ALPHA=5, SPRITE_ADD=6,
            SPRITE_MASK=7, SPRITE_ALPHA_MASK=8;
    static final int PANORAMA_TEXTURE=9;
    private final Vector textures = new Vector();
    private Image surface;
    private M3GSkyRenderer skyRenderer;
    private M3GScreenLines screenLines;
    private M3GSpriteRenderer spriteRenderer;
    private M3GPanoramaRenderer panoramaRenderer;
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
        appearance.setPolygonMode(polygon);
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
        batchState.polygon=screen?screenPolygon:polygon;
        if(screenMode!=screen) {
            appearance.setPolygonMode(screen?screenPolygon:polygon);
            screenMode=screen;
        }
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

    void bind(DirectX7 g,boolean preserve) {
        if(bound) return;
        if(surface == null || surface.getWidth() != g.width || surface.getHeight() != g.height)
            surface = Image.createImage(g.width, g.height);
        // Copy the CPU background, skybox, and any earlier software segment.
        if(preserve) surface.getGraphics().drawRGB(g.display, 0, g.width, 0, 0, g.width, g.height, false);
        Transform projection = new Transform();
        projection.set(new float[]{
            2.0f / g.width, 0, -(2.0f * g.centreX / g.width - 1), 0,
            0, 2.0f / g.height, -(1 - 2.0f * g.centreY / g.height), 0,
            0, 0, -1, -0.02f,
            0, 0, -1, 0
        });
        camera.setGeneric(projection);
        graphics.bindTarget(surface.getGraphics(), false, 0);
        bound = true;
        graphics.setViewport(0, 0, g.width, g.height);
        graphics.setCamera(camera, null);
        graphics.resetLights();
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

    private void flushBatch(DirectX7 g) {if(batch!=null) batch.flush(g);}

    public void finish(DirectX7 g) {
        flushBatch(g);
        if(!bound) return;
        try { graphics.releaseTarget(); }
        finally { bound = false; }
        surface.getRGB(g.display, 0, g.width, 0, 0, g.width, g.height);
    }

    public void dispose() {
        if(batch!=null) batch.discard();
        batch=null;
        try {
            if(bound) graphics.releaseTarget();
        } finally {
            bound = false;
            surface = null;
            skyRenderer = null;
            screenLines = null;
            spriteRenderer = null;
            panoramaRenderer = null;
            flashBackground = null;
            polygonModes.removeAllElements();polygonModes.addElement(polygon);polygonModes.addElement(screenPolygon);
            textures.removeAllElements();lastTexture=null;
            cachedPixels = 0;
        }
    }

    public boolean draw(DirectX7 g, RenderObject object, Texture texture, boolean fast) {
        batchable=false;
        if(!(object instanceof Polygon3V) && !(object instanceof Polygon4V)) flushBatch(g);
        if(hardwarePerspective!=Main.m3gPerspective) {
            flushBatch(g);
            hardwarePerspective=Main.m3gPerspective;
            for(int i=0;i<polygonModes.size();i++)
                ((PolygonMode)polygonModes.elementAt(i)).setPerspectiveCorrectionEnable(hardwarePerspective);
        }
        if(object instanceof SolidSky) {
            setScreenMode(true);
            SolidSky sky=(SolidSky)object;
            sky.prepare(g);
            float scale=ProjectedGeometry.pack(sky.vertices,4,g.centreX,g.centreY,
                    TexturingPers.move,false,positions,work);
            if(scale==0) return false;
            posArrays[1].set(0,4,positions);
            buffers[1].setPositions(posArrays[1],scale,null);
            uniformColors(4,sky.color,255);
            colorArrays[1].set(0,4,colors);
            setTexture(null);setFog(null);
            setBlend(CompositingMode.REPLACE);setThreshold(0);
            // Full-frame opaque background; old pixels cannot contribute.
            bind(g,false);restoreCamera(g);
            graphics.render(buffers[1],baseQuad,appearance,null);
            return true;
        }
        if(object instanceof SkyFlash) {
            if(flashBackground == null) {
                flashBackground = new Background();
                flashBackground.setColor(0xff000000 | SkyFlash.COLOR);
                flashBackground.setColorClearEnable(true);
                flashBackground.setDepthClearEnable(false);
            }
            // The clear overwrites the complete target; don't upload old pixels.
            bind(g,false);
            graphics.clear(flashBackground);
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
        MuzzleGlow glow = object instanceof MuzzleGlow ? (MuzzleGlow)object : null;
        if(glow != null) {
            if(glow.alpha <= 0) return true;
            object=glow.quad;texture=glow.texture;fast=true;
        }
        if(texture == null) return false;
        int mode = DirectX7.m3gDrawMode(texture.drawmode);
        // Mode 7 is an untextured tint; modes 10/11/12 are aliases of 0.
        if(mode != 0 && mode != 1 && mode != 2 && mode != 3 && mode != 4
                && mode != 5 && mode != 6 && mode != 7 && mode != 8 && mode != 9 && mode != 13) return false;
        // The original paths disagree on these degenerate distance values.
        // Preserve their behavior instead of passing invalid ranges to M3G Fog.
        if((mode == 3 || mode == 6) && Main.fogQ > 0 && DirectX7.fDist <= 1) return false;
        Class type = object.getClass();
        if(type != Polygon3V.class && type != Polygon4V.class
                && type != LightedPolygon3V.class && type != LightedPolygon4V.class
                && type != ColorLightedPolygon3V.class && type != ColorLightedPolygon4V.class)
            return false;
        // Screen-space muzzle glow shares buffers/material state, not mesh culling.
        setScreenMode(glow!=null);
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
        boolean fastPath = fast || (Main.persQ == 0 && !Main.mipMapping);
        boolean alternate = count == 4 && type == Polygon4V.class && !fastPath
                && (mode == 3 || mode == 6) && Main.fogQ > 0 && alternateDiagonal();
        indices[1] = alternate ? alternateQuad : baseQuad;
        TriangleStripArray[] quadTriangles = alternate ? alternateQuadTriangles : baseQuadTriangles;
        boolean perspective = mode != 7 && usePerspective(texture, count, fastPath);
        // TexturingPers interpolates 1 / (move - rz), not 1 / (distY - rz).
        // distY only controls screen projection and changes with the FOV.
        int distance = TexturingPers.move;
        float scale = ProjectedGeometry.pack(vertices, count, g.centreX, g.centreY,
                distance, mode != 8 && perspective, positions, work);
        if(scale == 0) return false;
        buffersScale=scale;
        if(mode == 7) {
            batchable=true;
            // Cheapest glass approximation: no source pixels, UV, mip or upload.
            // Uniform cool tint, alpha 32/255. Original Software refraction stays.
            int slot = count - 3;
            setFog(null);
            setTexture(null);
            setBlend(CompositingMode.ALPHA);
            setThreshold(0);
            uniformColors(count, 0xb0beca, 32);
            render(g, slot, indices[slot]);
            return true;
        }
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
            if(!batchable) buffers[fillSlot].setPositions(posArrays[fillSlot], scale, null);
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
        boolean highQuality = Main.fogQ == 2 && !Main.forceLQFog;
        // Affine mode 6 historically ignores forceLQFog, unlike its perspective path.
        if(mode == 6 && !perspective) highQuality = Main.fogQ == 2;
        float fogFar = 1.0f + (float)DirectX7.fDist / distance;
        boolean nativeFog = (mode == 3 || mode == 6) && Main.fogQ > 0 && highQuality
                && perspective && fogFar > 1.0f
                && (mode != 6 || !LightMapper.cameraVectorLight);
        // Multi-pass/flat per-triangle legacy fog retains its immediate order.
        batchable=glow==null && ((mode!=3 && mode!=6) || Main.fogQ==0 || nativeFog);
        if(!batchable) flushBatch(g);
        int kind = mode == 2 ? ADD_TEXTURE : mode == 4 && raw.alphaMixing ? ALPHA_TEXTURE : OPAQUE_TEXTURE;
        // Cache eviction/uploads are done outside a bound target. A white alpha
        // mask is needed only for the colored fog's legacy two-pass variant.
        Texture2D nativeTexture = texture(g, raw, kind);
        CachedTexture nativeEntry=lastTexture;
        batchState.imageFilter=nativeEntry.filter;
        Texture2D mask = mode == 3 && Main.fogQ > 0 && !nativeFog ? texture(g, raw, FOG_MASK) : null;
        int slot = count - 3;
        if(!batchable) posArrays[slot].set(0, count, positions);
        if(!batchable) uvArrays[slot].set(0, count, coords);
        if(!batchable) buffers[slot].setPositions(posArrays[slot], scale, null);
        setFog(null);
        setTexture(nativeTexture);
        setFunction(nativeTexture,Texture2D.FUNC_MODULATE);
        setBlend((glow != null || mode == 2) ? CompositingMode.ALPHA_ADD
                : kind == ALPHA_TEXTURE ? CompositingMode.ALPHA : CompositingMode.REPLACE);
        setThreshold(kind == ALPHA_TEXTURE ? 0 : 0.5f);

        if(glow != null) {
            // Warm radial RGBA texture * fading vertex alpha, additively blended.
            nativeTexture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_LINEAR);
            nativeEntry.filter=Texture2D.FILTER_LINEAR;
            uniformColors(count,0xffffff,glow.alpha);
            if(!batchable) colorArrays[slot].set(0,count,colors);
            render(g,slot,indices[slot]);
        } else if(mode == 5) {
            // Original mode 5: one normal/lightdir coefficient, not a native
            // Light or baked lightmap. Perspective fogQ=0 bypasses shading;
            // affine historically shades even with fogQ=0.
            int light = MathUtils.calcLight(object.nx, object.ny, object.nz,
                    DirectX7.lightdirx, DirectX7.lightdiry, DirectX7.lightdirz);
            int shade = perspective && Main.fogQ == 0 ? 255 : (light * 255 + 128) >> 8;
            uniformColors(count, (shade << 16) | (shade << 8) | shade, 255);
            if(!batchable) colorArrays[slot].set(0, count, colors);
            render(g, slot, indices[slot]);
        } else if(mode == 1 && Main.fogQ > 0) {
            // Legacy ADD fog is saturation of texture + constant fog RGB, not
            // conventional linear fog. Texture ADD performs that operation in M3G.
            uniformColors(count, additiveFog(count), 255);
            if(!batchable) colorArrays[slot].set(0, count, colors);
            setFunction(nativeTexture,Texture2D.FUNC_ADD);
            render(g, slot, indices[slot]);
        } else if((mode == 3 || mode == 6) && Main.fogQ > 0) {
            if(nativeFog) {
                uniformColors(count, 0xffffff, 255);
                if(!batchable) colorArrays[slot].set(0, count, colors);
                // Packed eye distance = 1 - rz/move, NOT the original -rz.
                // Mapping both endpoints cancels the engine's projection offset.
                batchState.near=1.0f;batchState.far=fogFar;
                batchState.fogColor=mode==3?DirectX7.fogc&0xffffff:0;
                setFog(fog);
                render(g, slot, indices[slot]);
                setFog(null);
            } else if(!highQuality && count == 4) {
                // Flat LQ fog follows the same chosen diagonal as the CPU path.
                legacyFog(g, object, slot, count, quadTriangles[0], mode, perspective,
                        false, Math.min(vertices[0].rz, Math.min(vertices[1].rz, vertices[alternate ? 2 : 3].rz)), nativeTexture, mask);
                legacyFog(g, object, slot, count, quadTriangles[1], mode, perspective,
                        false, Math.min(vertices[alternate ? 0 : 1].rz, Math.min(vertices[2].rz, vertices[3].rz)), nativeTexture, mask);
            } else {
                int farthest = vertices[0].rz;
                for(int i = 1; i < count; i++) farthest = Math.min(farthest, vertices[i].rz);
                legacyFog(g, object, slot, count, indices[slot], mode, perspective,
                        highQuality, farthest, nativeTexture, mask);
            }
        } else {
            setColors(g, object, count, mode);
            if(!batchable) colorArrays[slot].set(0, count, colors);
            render(g, slot, indices[slot]);
        }
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
            graphics.render(buffers[slot], triangles, appearance, null);
        }
    }

    void renderSingle(DirectX7 g,int count,float scale,short[] xyz,short[] uv,byte[] rgba,
            TriangleStripArray triangles,Appearance state) {
        int slot=count-3;
        posArrays[slot].set(0,count,xyz);uvArrays[slot].set(0,count,uv);colorArrays[slot].set(0,count,rgba);
        buffers[slot].setPositions(posArrays[slot],scale,null);
        bind(g);restoreCamera(g);graphics.render(buffers[slot],triangles,state,null);
    }

    Texture2D texture(DirectX7 g, RawImage raw, int kind) {
        Texture2D result = findTexture(raw, kind);
        if(result == null) {
            finish(g);
            result = createTexture(raw, kind);
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

    private static int clamp(long value) { return (int)Math.max(0L, Math.min(255L, value)); }

    private int additiveFog(int count) {
        int farthest = vertices[0].rz;
        for(int i = 1; i < count; i++) farthest = Math.min(farthest, vertices[i].rz);
        int divisor = DirectX7.fDist / 255;
        int amount = clamp(-(long)farthest / (divisor != 0 ? divisor : 255));
        int shade = 255 - amount;
        int r = Math.max(0, ((DirectX7.fogc >> 16) & 255) - shade);
        int green = Math.max(0, ((DirectX7.fogc >> 8) & 255) - shade);
        int b = Math.max(0, (DirectX7.fogc & 255) - shade);
        return ((r << 16) | (green << 8) | b) & 0xfefefe;
    }

    private int visibility(DirectX7 g, RenderObject object, Vertex vertex,
            int mode, boolean perspective, boolean highQuality, int farthest) {
        int result;
        if(highQuality) result = 255 - clamp(-(long)vertex.rz * 255 / DirectX7.fDist);
        else {
            int divisor = DirectX7.fDist / 255;
            // When the legacy integer divisor vanishes its LQ fog amount stays 0.
            result = 255 - (divisor == 0 ? 0 : clamp(-(long)farthest / divisor));
        }
        if(mode == 6 && highQuality && perspective && LightMapper.cameraVectorLight) {
            result = result * MathUtils.calcLight(object.nx, object.ny, object.nz,
                    vertex.x - g.camera.m03, vertex.y - g.camera.m13, vertex.z - g.camera.m23) / 255;
        }
        return clamp(result);
    }

    private void legacyFog(DirectX7 g, RenderObject object, int slot, int count,
            TriangleStripArray triangles, int mode, boolean perspective, boolean highQuality,
            int farthest, Texture2D nativeTexture, Texture2D mask) {
        // First pass: texture * visibility. M3G interpolates and modulates; no
        // texture pixels are recolored on the CPU, including in the affine path.
        appearance.setTexture(0, nativeTexture);
        composite.setBlending(CompositingMode.REPLACE);
        composite.setAlphaThreshold(0.5f);
        for(int i = 0; i < count; i++) {
            int visible = visibility(g, object, vertices[i], mode, perspective, highQuality, farthest);
            colors[i * 4] = colors[i * 4 + 1] = colors[i * 4 + 2] = (byte)visible;
            colors[i * 4 + 3] = (byte)255;
        }
        colorArrays[slot].set(0, count, colors);
        render(g, slot, triangles);
        if(mode == 3) {
            // Add C_fog * (1 - visibility) in M3G. The alpha mask prevents fog
            // from filling the original texture's transparent holes. Using ALPHA
            // here would attenuate the already darkened first pass a second time.
            for(int i = 0; i < count; i++) {
                colors[i * 4 + 3] = (byte)(255 - (colors[i * 4] & 255));
                colors[i * 4] = (byte)(DirectX7.fogc >> 16);
                colors[i * 4 + 1] = (byte)(DirectX7.fogc >> 8);
                colors[i * 4 + 2] = (byte)DirectX7.fogc;
            }
            colorArrays[slot].set(0, count, colors);
            appearance.setTexture(0, mask);
            composite.setBlending(CompositingMode.ALPHA_ADD);
            composite.setAlphaThreshold(0);
            render(g, slot, triangles);
            appearance.setTexture(0, nativeTexture);
            composite.setBlending(CompositingMode.REPLACE);
            composite.setAlphaThreshold(0.5f);
        }
    }

    private void setColors(DirectX7 g, RenderObject object, int count, int mode) {
        boolean lit = (mode == 9 || mode == 13) && Main.fogQ > 0;
        for(int i = 0; i < count; i++) {
            int r = 255, green = 255, b = 255;
            if(lit) {
                if(object instanceof LightedPolygon3V) {
                    LightedPolygon3V p = (LightedPolygon3V)object;
                    r = i == 0 ? p.la : i == 1 ? p.lb : p.lc;
                    green = b = r;
                } else if(object instanceof LightedPolygon4V) {
                    LightedPolygon4V p = (LightedPolygon4V)object;
                    r = i == 0 ? p.la : i == 1 ? p.lb : i == 2 ? p.lc : p.ld;
                    green = b = r;
                } else if(object instanceof ColorLightedPolygon3V) {
                    ColorLightedPolygon3V p = (ColorLightedPolygon3V)object;
                    r = i == 0 ? p.ar : i == 1 ? p.br : p.cr;
                    green = i == 0 ? p.ag : i == 1 ? p.bg : p.cg;
                    b = i == 0 ? p.ab : i == 1 ? p.bb : p.cb;
                } else if(object instanceof ColorLightedPolygon4V) {
                    ColorLightedPolygon4V p = (ColorLightedPolygon4V)object;
                    r = i == 0 ? p.ar : i == 1 ? p.br : i == 2 ? p.cr : p.dr;
                    green = i == 0 ? p.ag : i == 1 ? p.bg : i == 2 ? p.cg : p.dg;
                    b = i == 0 ? p.ab : i == 1 ? p.bb : i == 2 ? p.cb : p.db;
                }
                // Baked lightmap data only. No getLight(), camera-vector light,
                // flashlight or muzzle flash is evaluated for native vertex RGB.
                boolean baked = object instanceof LightedPolygon3V || object instanceof LightedPolygon4V
                        || object instanceof ColorLightedPolygon3V || object instanceof ColorLightedPolygon4V;
                if(baked) {
                    int visible = g.lightmapColor(255, vertices[i].rz);
                    r = clamp(r) * visible / 255;
                    green = clamp(green) * visible / 255;
                    b = clamp(b) * visible / 255;
                }
                if(mode == 9) green = b = r;
                else if(Main.fogQ == 1) r = green = b = (r + green + b) / 3;
            }
            colors[i * 4] = (byte)Math.max(0, Math.min(255, r));
            colors[i * 4 + 1] = (byte)Math.max(0, Math.min(255, green));
            colors[i * 4 + 2] = (byte)Math.max(0, Math.min(255, b));
            colors[i * 4 + 3] = (byte)255;
        }
    }

    private boolean usePerspective(Texture texture, int count, boolean fast) {
        if(fast || Main.persQ == 0) return false;
        if(Main.persQ == 4) return true;
        if(!texture.perspectiveCorrection) return false;
        int minX = vertices[0].sx, maxX = minX, minY = vertices[0].sy, maxY = minY;
        for(int i = 1; i < count; i++) {
            minX = Math.min(minX, vertices[i].sx); maxX = Math.max(maxX, vertices[i].sx);
            minY = Math.min(minY, vertices[i].sy); maxY = Math.max(maxY, vertices[i].sy);
        }
        int xLimit = count == 3 ? (Main.persQ < 3 ? 30 : 15) : (Main.persQ < 3 ? 70 : 50);
        int yLimit = count == 3 ? xLimit : (Main.persQ < 3 ? 100 : 70);
        return (long)maxX - minX > xLimit || (long)maxY - minY > yLimit;
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
            int minX = vertices[0].sx, maxX = minX, minY = vertices[0].sy, maxY = minY;
            int minU = uv[0], maxU = minU, minV = uv[1], maxV = minV;
            for(int i = 1; i < count; i++) {
                minX = Math.min(minX, vertices[i].sx); maxX = Math.max(maxX, vertices[i].sx);
                minY = Math.min(minY, vertices[i].sy); maxY = Math.max(maxY, vertices[i].sy);
                minU = Math.min(minU, uv[i * 2]); maxU = Math.max(maxU, uv[i * 2]);
                minV = Math.min(minV, uv[i * 2 + 1]); maxV = Math.max(maxV, uv[i * 2 + 1]);
            }
            int factor = object.ny > 4000 || object.ny < -4000 || Main.q > 999 ? 2 : 3;
            for(int level = 1; level < texture.mip.length && level < 3; level++) {
                if(raw == null || texture.mip[level] == null) break;
                long screen = ((long)maxX - minX + (long)maxY - minY) / 2 * factor;
                long texels = (((long)maxU - minU) * raw.w + ((long)maxV - minV) * raw.h) / 256;
                if(screen >= texels) break;
                raw = texture.mip[level];
            }
        }
        return raw;
    }

    private static boolean powerOfTwo(int n) { return n > 0 && (n & (n - 1)) == 0; }

    private Texture2D findTexture(RawImage image, int kind) {
        for(int i = 0; i < textures.size(); i++) {
            CachedTexture cached = (CachedTexture)textures.elementAt(i);
            if(cached.image == image && cached.pixels == image.img && cached.width == image.w
                    && cached.height == image.h && cached.kind == kind) {lastTexture=cached;return cached.texture;}
        }
        return null;
    }

    private Texture2D createTexture(RawImage image, int kind) {
        boolean sprite=kind>=SPRITE_CUTOUT && kind<=SPRITE_ALPHA_MASK;
        int width=sprite?spriteDimension(image.w):image.w;
        int height=sprite?spriteDimension(image.h):image.h;
        int size = width * height;
        while(textures.size() > 0 && (textures.size() >= CACHE_ENTRIES || cachedPixels + size > CACHE_PIXELS)) {
            CachedTexture oldest = (CachedTexture)textures.elementAt(0);
            cachedPixels -= oldest.cost;
            textures.removeElementAt(0);
        }
        byte[] rgba = new byte[size * 4];
        for(int i = 0; i < size; i++) {
            // Sprite-only nearest-neighbor reduction, done once per cache miss.
            // Sample texel centers; color and fog masks use the identical source.
            // POT images pass through unchanged. Never mutate the original asset.
            int pixel = image.img[sprite?((2*(i/width)+1)*image.h/(2*height))*image.w
                    +(2*(i%width)+1)*image.w/(2*width):i];
            // Legacy additive source discards channel low bits. Hardware cannot
            // mask destination low bits; native ADD may differ by one per channel.
            int rgb = kind == ADD_TEXTURE || kind == SPRITE_ADD ? pixel & 0xfefefe : pixel;
            boolean mask=kind==FOG_MASK || kind==SPRITE_MASK || kind==SPRITE_ALPHA_MASK;
            rgba[i * 4] = mask ? (byte)255 : (byte)(rgb >> 16);
            rgba[i * 4 + 1] = mask ? (byte)255 : (byte)(rgb >> 8);
            rgba[i * 4 + 2] = mask ? (byte)255 : (byte)rgb;
            rgba[i * 4 + 3] = kind==ALPHA_TEXTURE || kind==SPRITE_ALPHA || kind==SPRITE_ALPHA_MASK
                    ? (byte)(pixel >>> 24) : (byte)(kind==SPRITE_ADD || kind==PANORAMA_TEXTURE || pixel!=0 ? 255 : 0);
        }
        CachedTexture cached = new CachedTexture();
        cached.image = image; cached.pixels = image.img; cached.width = image.w; cached.height = image.h;
        cached.kind = kind;cached.cost=size;
        cached.texture = new Texture2D(new Image2D(Image2D.RGBA, width, height, rgba));
        cached.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        cached.texture.setWrapping(sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT,
                sprite?Texture2D.WRAP_CLAMP:Texture2D.WRAP_REPEAT);
        cached.texture.setBlending(Texture2D.FUNC_MODULATE);
        textures.addElement(cached);lastTexture=cached;
        cachedPixels += size;
        return cached.texture;
    }
}
