package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Immediate-mode M3G rasterizer, with CPU barriers for legacy materials. */
public final class M3GSceneRenderer implements SceneRenderer {
    private final Graphics3D graphics;
    private final javax.microedition.m3g.Camera camera = new javax.microedition.m3g.Camera();
    private final Appearance appearance = new Appearance();
    private final CompositingMode composite = new CompositingMode();
    private final Vertex[] vertices = new Vertex[4];
    private final int[] uv = new int[8];
    private final short[] positions = new short[12], coords = new short[8];
    private final byte[] colors = new byte[12];
    private final float[] work = new float[12];
    private final VertexBuffer[] buffers = new VertexBuffer[2];
    private final VertexArray[] posArrays = new VertexArray[2];
    private final VertexArray[] uvArrays = new VertexArray[2];
    private final VertexArray[] colorArrays = new VertexArray[2];
    private final TriangleStripArray[] indices = new TriangleStripArray[2];
    private final Vector textures = new Vector();
    private Image surface;
    private boolean bound;
    private int cachedPixels;
    private final int maxTexture;
    private static final int CACHE_PIXELS = 262144, CACHE_ENTRIES = 16;

    private static final class CachedTexture {
        RawImage image;
        int[] pixels;
        int width, height;
        boolean alpha;
        Texture2D texture;
    }

    public M3GSceneRenderer() {
        graphics = Graphics3D.getInstance();
        Hashtable properties = Graphics3D.getProperties();
        Object limit = properties.get("maxTextureDimension");
        maxTexture = limit instanceof Integer ? ((Integer)limit).intValue() : 256;
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);
        appearance.setPolygonMode(polygon);
        composite.setDepthTestEnable(false);
        composite.setDepthWriteEnable(false);
        appearance.setCompositingMode(composite);
        for(int i = 0; i < 2; i++) {
            int count = i + 3;
            buffers[i] = new VertexBuffer();
            posArrays[i] = new VertexArray(count, 3, 2);
            uvArrays[i] = new VertexArray(count, 2, 2);
            colorArrays[i] = new VertexArray(count, 3, 1);
            buffers[i].setColors(colorArrays[i]);
            buffers[i].setTexCoords(0, uvArrays[i], 1.0f / 256, null);
        }
        indices[0] = new TriangleStripArray(0, new int[]{3});
        // Same diagonal as Polygon4V.render/renderFast: ABD and BCD.
        indices[1] = new TriangleStripArray(new int[]{0, 1, 3, 1, 2, 3}, new int[]{3, 3});
    }

    private void bind(DirectX7 g) {
        if(bound) return;
        if(surface == null || surface.getWidth() != g.width || surface.getHeight() != g.height)
            surface = Image.createImage(g.width, g.height);
        // Copy the CPU background, skybox, and any earlier software segment.
        surface.getGraphics().drawRGB(g.display, 0, g.width, 0, 0, g.width, g.height, false);
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

    public void finish(DirectX7 g) {
        if(!bound) return;
        try { graphics.releaseTarget(); }
        finally { bound = false; }
        surface.getRGB(g.display, 0, g.width, 0, 0, g.width, g.height);
    }

    public void dispose() {
        try {
            if(bound) graphics.releaseTarget();
        } finally {
            bound = false;
            surface = null;
            textures.removeAllElements();
            cachedPixels = 0;
        }
    }

    public boolean draw(DirectX7 g, RenderObject object, Texture texture, boolean fast) {
        if(texture == null) return false;
        int mode = texture.drawmode;
        // Do not silently approximate glass, water, fog, additive/dither modes.
        if(mode != 0 && mode != 4 && mode != 9) return false;
        Class type = object.getClass();
        if(type != Polygon3V.class && type != Polygon4V.class
                && type != LightedPolygon3V.class && type != LightedPolygon4V.class
                && type != ColorLightedPolygon3V.class && type != ColorLightedPolygon4V.class)
            return false;
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
        boolean perspective = !fast && Main.persQ > 0 && (texture.perspectiveCorrection || Main.persQ == 4);
        float scale = ProjectedGeometry.pack(vertices, count, g.centreX, g.centreY,
                g.distY, perspective, positions, work);
        if(scale == 0) return false;
        RawImage raw = selectImage(texture, object, count);
        if(raw == null || !powerOfTwo(raw.w) || !powerOfTwo(raw.h)
                || raw.w > maxTexture || raw.h > maxTexture
                || (long)raw.w * raw.h > CACHE_PIXELS) return false;
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
        setColors(g, object, count, mode == 9 && Main.fogQ > 0,
                fast || (Main.persQ == 0 && !Main.mipMapping));
        // Cache eviction/texture uploads can be illegal while a target is bound
        // on some implementations. Finish before a cache miss, not every polygon.
        Texture2D nativeTexture = findTexture(raw, mode == 4 && raw.alphaMixing);
        if(nativeTexture == null) {
            finish(g);
            nativeTexture = createTexture(raw, mode == 4 && raw.alphaMixing);
        }
        int slot = count - 3;
        posArrays[slot].set(0, count, positions);
        uvArrays[slot].set(0, count, coords);
        colorArrays[slot].set(0, count, colors);
        buffers[slot].setPositions(posArrays[slot], scale, null);
        appearance.setTexture(0, nativeTexture);
        boolean alpha = mode == 4 && raw.alphaMixing;
        composite.setBlending(alpha ? CompositingMode.ALPHA : CompositingMode.REPLACE);
        composite.setAlphaThreshold(alpha ? 0 : 0.5f);
        bind(g);
        graphics.render(buffers[slot], indices[slot], appearance, null);
        return true;
    }

    private void setColors(DirectX7 g, RenderObject object, int count, boolean lit, boolean fast) {
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
                if(!fast && (object instanceof LightedPolygon3V || object instanceof LightedPolygon4V)) {
                    r = object.getLight(vertices[i], r, g);
                    green = b = r;
                } else if(!fast && (object instanceof ColorLightedPolygon3V || object instanceof ColorLightedPolygon4V)) {
                    int[] rgb = object.getLight(vertices[i], r, green, b, g);
                    r = rgb[0]; green = rgb[1]; b = rgb[2];
                }
            }
            colors[i * 3] = (byte)Math.max(0, Math.min(255, r));
            colors[i * 3 + 1] = (byte)Math.max(0, Math.min(255, green));
            colors[i * 3 + 2] = (byte)Math.max(0, Math.min(255, b));
        }
    }

    private RawImage selectImage(Texture texture, RenderObject object, int count) {
        RawImage raw = texture.rImg;
        if(Main.mipMapping && texture.mip != null && texture.mip.length > 0) {
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

    private Texture2D findTexture(RawImage image, boolean alpha) {
        for(int i = 0; i < textures.size(); i++) {
            CachedTexture cached = (CachedTexture)textures.elementAt(i);
            if(cached.image == image && cached.pixels == image.img && cached.width == image.w
                    && cached.height == image.h && cached.alpha == alpha) return cached.texture;
        }
        return null;
    }

    private Texture2D createTexture(RawImage image, boolean alpha) {
        int size = image.w * image.h;
        while(textures.size() > 0 && (textures.size() >= CACHE_ENTRIES || cachedPixels + size > CACHE_PIXELS)) {
            CachedTexture oldest = (CachedTexture)textures.elementAt(0);
            cachedPixels -= oldest.width * oldest.height;
            textures.removeElementAt(0);
        }
        byte[] rgba = new byte[size * 4];
        for(int i = 0; i < size; i++) {
            int pixel = image.img[i];
            rgba[i * 4] = (byte)(pixel >> 16);
            rgba[i * 4 + 1] = (byte)(pixel >> 8);
            rgba[i * 4 + 2] = (byte)pixel;
            rgba[i * 4 + 3] = alpha ? (byte)(pixel >>> 24) : (byte)(pixel == 0 ? 0 : 255);
        }
        CachedTexture cached = new CachedTexture();
        cached.image = image; cached.pixels = image.img; cached.width = image.w; cached.height = image.h;
        cached.alpha = alpha;
        cached.texture = new Texture2D(new Image2D(Image2D.RGBA, image.w, image.h, rgba));
        cached.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        cached.texture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        cached.texture.setBlending(Texture2D.FUNC_MODULATE);
        textures.addElement(cached);
        cachedPixels += size;
        return cached.texture;
    }
}
