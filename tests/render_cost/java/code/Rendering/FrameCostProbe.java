package code.Rendering;

import code.Collision.Height;
import code.Gameplay.LevelLoader;
import code.Gameplay.Map.House;
import code.Gameplay.Map.HouseCreator;
import code.Gameplay.Map.Room;
import code.Gameplay.Map.Skybox;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.Polygon3V;
import code.Rendering.Meshes.Polygon4V;
import code.Rendering.Meshes.Sprite;
import code.utils.GameIni;
import code.utils.Main;
import code.utils.StringTools;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.Graphics3D;

/**
 * Counts native draw calls for one real frame of a real level.
 *
 * A measurement harness, not a renderer: the M3G doubles in tests/render_cost/stubs
 * record every graphics.render() with its state, so a batching change is judged by
 * numbers. No FPS claims, no GPU timings, no production code modified.
 */
public final class FrameCostProbe {

    private static final Map<String, RawImage> IMAGES = new LinkedHashMap<String, RawImage>();
    private static final Map<String, Integer> DRAW_MIX = new LinkedHashMap<String, Integer>();
    private static final Set<String> TEXTURES = new LinkedHashSet<String>();

    private static int frameWorld, frameSprites, frameOptimal, frameTextures;
    private static int overlapFrames, bothFrames;
    private static String worstSplit = "";
    private static int totalDraws, totalFrames, totalTriangles, billboardCalls;
    private static long vcolSum; private static int vcolN, vcolNonWhite;
    // Same vertex-colour census over EVERY geometry draw, lit or packed. The
    // lit-only number moves whenever a poly changes path; this one only moves
    // if the geometry or its colours actually change, so it is the check that
    // a hand-off drew the same thing by a different route.
    private static long allSum; private static int allN, allNonWhite;
    private static int worstDraws, worstTriangles, worstYaw, worstPitch, worstBarriers;

    // ---- projected fill of the lit pass -----------------------------------
    // The device says the lit flush is ~2.8 s of a ~3.8 s frame, with B=0 and
    // T=0 - no barriers, no texture rebuilds. That leaves rasterisation, and
    // rasterisation is priced in SCREENS of filled area (~14 ms each, from the
    // hl2me fill series). So the question "why is the lit pass slow" becomes
    // "how many screens does it cover", which is arithmetic on the vertices it
    // actually submitted - not a guess about which call is expensive.
    //
    // raw   = bounding box of the projected vertices, with w clamped at the
    //         near plane. A lower bound on what a rasteriser without clipping
    //         pays: a vertex walking towards the eye sends the extent to
    //         infinity, and clamping at `near` is the smallest it can get.
    // clip  = the same box intersected with the viewport: the honest fill.
    // raw/clip is the multiple being paid for pixels that never land.
    private static double worstRaw, worstClip, worstDrawArea;
    private static int worstBehind, worstAtEye, worstWide, worstAreaPolys, worstAreaDraws;
    private static String worstAreaView = "";
    private static boolean probeDepth = System.getProperty("probe.depth") != null;

    /** Bbox span, in viewports, above which a lit poly could be handed to the
     *  clipping packed path instead. Area above each rung is the fill a
     *  hand-off at that rung would remove. */
    private static final double[] SPANS = {1, 2, 4, 8, 16, 32};
    private static final double[] overRaw = new double[SPANS.length];
    private static final int[] overTris = new int[SPANS.length];
    private static final int[] worstOverTris = new int[SPANS.length];
    private static final double[] worstOverRaw = new double[SPANS.length];
    private static double offscreenRaw, worstOffscreenRaw;
    private static int queuedObjects;

    private static Sprite[] sprites;
    private static code.Rendering.Texture[] spriteTextures;

    private static InputStream resource(String name) {
        InputStream in = FrameCostProbe.class.getResourceAsStream(name);
        if (in == null) throw new IllegalArgumentException("missing resource: " + name);
        return in;
    }

    private static String text(String name) throws Exception {
        InputStream in = resource(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return new String(out.toByteArray(), "windows-1251").replace("\r", "");
    }

    private static RawImage image(String name) throws Exception {
        RawImage cached = IMAGES.get(name);
        if (cached != null) return cached;
        InputStream in = resource(name);
        BufferedImage decoded = ImageIO.read(in);
        in.close();
        if (decoded == null) throw new IllegalArgumentException("cannot decode " + name);
        int width = decoded.getWidth(), height = decoded.getHeight();
        RawImage raw = new RawImage(decoded.getRGB(0, 0, width, height, null, 0, width), width, height);
        IMAGES.put(name, raw);
        Field table = code.utils.Asset.class.getDeclaredField("table");
        table.setAccessible(true);
        ((Hashtable) table.get(null)).put("RIMG_" + name, raw);
        return raw;
    }

    private static Mesh[] decode(String name, float scale, code.Rendering.MultyTexture texture) throws Exception {
        Method decoder = Room.class.getDeclaredMethod("createFrom3d", String.class, DataInputStream.class,
                Float.TYPE, Float.TYPE, Float.TYPE, code.Rendering.MultyTexture.class);
        decoder.setAccessible(true);
        DataInputStream in = new DataInputStream(resource(name));
        Mesh[] meshes = new Mesh[in.readInt()];
        for (int i = 0; i < meshes.length; i++) {
            meshes[i] = (Mesh) decoder.invoke(null, name, in, scale, scale, scale, texture);
        }
        in.close();
        return meshes;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void warmTextures(DirectX7 g) throws Exception {
        Field field = DirectX7.class.getDeclaredField("sceneRenderer");
        field.setAccessible(true);
        M3GSceneRenderer renderer = (M3GSceneRenderer) field.get(g);
        for (RawImage raw : IMAGES.values()) renderer.texture(g, raw, M3GSceneRenderer.OPAQUE_TEXTURE);
    }

    private static int queued(DirectX7 g) {
        try {
            Field field = DirectX7.class.getDeclaredField("buffer");
            field.setAccessible(true);
            Object buffer = field.get(g);
            Method size = buffer.getClass().getMethod("getSize");
            size.setAccessible(true);
            return ((Integer) size.invoke(buffer)).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** One billboard per icon, the way NPC, prop and weapon sprites queue up. */
    private static void prepareSprites(int count, int distinct) {
        if (count <= 0) return;
        sprites = new Sprite[count];
        spriteTextures = new code.Rendering.Texture[Math.max(1, Math.min(count, distinct))];
        RawImage base = IMAGES.values().iterator().next();
        for (int i = 0; i < spriteTextures.length; i++) {
            int[] pixels = new int[64];
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                pixels[pixel] = base.img[(pixel + i) % base.img.length];
            }
            code.Rendering.Texture texture = new code.Rendering.Texture();
            texture.rImg = new RawImage(pixels, 8, 8);
            spriteTextures[i] = texture;
        }
        for (int i = 0; i < count; i++) {
            Sprite sprite = new Sprite(spriteTextures[0], 24);
            sprite.mode = 3;
            sprite.fog = Boolean.getBoolean("probe.spriteFog");
            sprite.pos = new Vertex();
            sprite.size = new Vertex();
            sprites[i] = sprite;
        }
    }

    private static void queueSprites(DirectX7 g) {
        if (sprites == null) return;
        boolean monotone = Boolean.getBoolean("probe.monotone");
        int textures = spriteTextures.length;
        for (int index = 0; index < sprites.length; index++) {
            Sprite sprite = sprites[index];
            int icon = monotone ? index * textures / sprites.length : index % textures;
            sprite.textures = new code.Rendering.Texture[]{spriteTextures[icon]};
            int x = 4 + (index * 13) % Math.max(1, g.width - 32);
            int y = 4 + (index * 29) % Math.max(1, g.height - 32);
            sprite.pos.sx = x;
            sprite.pos.sy = y;
            sprite.pos.rz = Boolean.getBoolean("probe.sameDepth") ? -1000 : -1000 - index;
            sprite.size.sx = x + 24;
            sprite.size.sy = y + 24;
            g.addRenderObject(sprite, spriteTextures[icon]);
        }
    }

    private static void count(String key) {
        Integer value = DRAW_MIX.get(key);
        DRAW_MIX.put(key, value == null ? 1 : value + 1);
    }

    private static int beyondFar;
    private static int nearPolys;

    /** World polys entirely past the M3G camera far plane draw nothing at all. */
    private static int countBeyondFar(House house, int far) {
        int n = 0;
        nearPolys = 0;
        Room[] rooms = house.getRooms();
        if (rooms == null) return 0;
        for (int i = 0; i < rooms.length; i++) {
            Room room = rooms[i];
            if (room == null) continue;
            Mesh m = room.fullMesh;
            if (m == null) continue;
            RenderObject[] polys = m.getPolygons();
            if (polys == null) continue;
            for (int p = 0; p < polys.length; p++) {
                Object o = polys[p];
                Vertex[] v;
                if (o instanceof Polygon4V) {
                    Polygon4V q = (Polygon4V) o;
                    v = new Vertex[]{q.a, q.b, q.c, q.d};
                } else if (o instanceof Polygon3V) {
                    Polygon3V q = (Polygon3V) o;
                    v = new Vertex[]{q.a, q.b, q.c};
                } else continue;
                boolean all = true;
                for (int k = 0; k < v.length; k++)
                    if (v[k].getViewDepth() > -far) { all = false; break; }
                if (all) n++;
                else nearPolys++;
            }
        }
        return n;
    }

    private static String one(double v) {
        if (v >= 1000) return Long.toString(Math.round(v));
        if (v >= 100) return Double.toString(Math.round(v) / 1.0);
        if (v >= 10) return Double.toString(Math.round(v * 10) / 10.0);
        return Double.toString(Math.round(v * 100) / 100.0);
    }

    /** Screens of projected fill the lit pass asked for, for one frame.
     *
     *  Measured per TRIANGLE, because that is the unit a rasteriser works on,
     *  and driven by the strip indices because the vertex arrays are grown to a
     *  capacity and only partly rewritten each frame - getVertexCount() is the
     *  capacity, not what this frame wrote.
     */
    private static void measureArea(int history, String view) {
        double raw = 0, clip = 0, worst = 0;
        int behindNear = 0, atEye = 0, wide = 0, tris = 0, draws = 0;
        for (int b = 0; b < overRaw.length; b++) { overRaw[b] = 0; overTris[b] = 0; }
        offscreenRaw = 0;
        for (int i = history; i < Graphics3D.history.size(); i++) {
            Graphics3D.Draw d = Graphics3D.history.elementAt(i);
            // Only the lit pass: it is the one that installs a real perspective
            // camera. The packed path feeds an already-screen-space camera.
            if (d.camera == null || d.camera.type != Camera.PERSPECTIVE) continue;
            if (d.positions == null || d.indices == null) continue;
            float[] bias = d.bias == null ? new float[3] : d.bias;
            double f = 1.0 / Math.tan(Math.toRadians(d.camera.fovy) / 2.0);
            double aspect = d.camera.aspect > 0 ? d.camera.aspect : 0.75;
            double fa = f / aspect;
            double W = d.vw > 0 ? d.vw : 240, H = d.vh > 0 ? d.vh : 320;
            float near = d.camera.near > 0 ? d.camera.near : 1;
            draws++;
            int[] idx = d.indices, len = d.lengths;
            int base = 0;
            for (int s = 0; s < len.length; s++) {
                for (int k = 0; k + 2 < len[s]; k++) {
                    int i0 = idx[base + k], i1 = idx[base + k + 1], i2 = idx[base + k + 2];
                    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                    double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                    boolean bad = false, eye = false;
                    for (int t = 0; t < 3; t++) {
                        int vi = t == 0 ? i0 : (t == 1 ? i1 : i2);
                        double x = d.scale * d.positions[vi * 3] + bias[0];
                        double y = d.scale * d.positions[vi * 3 + 1] + bias[1];
                        double z = d.scale * d.positions[vi * 3 + 2] + bias[2];
                        double w = -z;
                        if (w <= 0) { w = near; eye = true; bad = true; }
                        else if (w < near) { w = near; bad = true; }
                        double sx = (fa * x / w * 0.5 + 0.5) * W;
                        double sy = (0.5 - f * y / w * 0.5) * H;
                        if (sx < minX) minX = sx;
                        if (sx > maxX) maxX = sx;
                        if (sy < minY) minY = sy;
                        if (sy > maxY) maxY = sy;
                    }
                    tris++;
                    if (bad) behindNear++;
                    if (eye) atEye++;
                    double rw = Math.max(0, maxX - minX), rh = Math.max(0, maxY - minY);
                    double aRaw = rw * rh / (W * H);
                    double cx1 = Math.max(0, Math.min(W, maxX)), cx0 = Math.max(0, Math.min(W, minX));
                    double cy1 = Math.max(0, Math.min(H, maxY)), cy0 = Math.max(0, Math.min(H, minY));
                    double aClip = Math.max(0, cx1 - cx0) * Math.max(0, cy1 - cy0) / (W * H);
                    raw += aRaw; clip += aClip;
                    if (aRaw > worst) worst = aRaw;
                    if (rw > 4 * W || rh > 4 * H) wide++;
                    if (aClip <= 0) offscreenRaw += aRaw;
                    double span = Math.max(rw / W, rh / H);
                    for (int b = 0; b < SPANS.length; b++) if (span > SPANS[b]) { overRaw[b] += aRaw; overTris[b]++; }
                    if (probeDepth && aRaw > 1)
                        System.out.println("  litTri raw=" + one(aRaw) + " clip=" + one(aClip)
                                + " near=" + one(near) + " eye=" + eye + " band=" + bad
                                + " x=[" + one(minX) + "," + one(maxX) + "]"
                                + " y=[" + one(minY) + "," + one(maxY) + "]"
                                + " vp=" + one(W) + "x" + one(H));
                }
                base += len[s];
            }
        }
        if (System.getProperty("probe.areas") != null)
            System.out.println("  area " + view + " raw=" + one(raw) + " clip=" + one(clip)
                    + " tris=" + tris + " draws=" + draws + " wide=" + wide);
        if (raw > worstRaw) {
            System.arraycopy(overRaw, 0, worstOverRaw, 0, overRaw.length);
            System.arraycopy(overTris, 0, worstOverTris, 0, overTris.length);
            worstOffscreenRaw = offscreenRaw;
            worstRaw = raw; worstClip = clip; worstDrawArea = worst;
            worstBehind = behindNear; worstAtEye = atEye; worstWide = wide;
            worstAreaPolys = tris; worstAreaDraws = draws;
            worstAreaView = view;
        }
    }

    private static void sample(House house, Skybox sky, int[] position, int yaw, int pitch, int fogDist,
            int levelDist, byte drawmode) {
        int draws = 0, triangles = 0, billboards = 0, barriers = 0;
        // One renderer for the whole sample: in the game the M3G renderer and its
        // texture cache live across frames, so per-frame uploads must be measured
        // with the cache warm, not rebuilt by a fresh DirectX7 every frame.
        DirectX7 g = new DirectX7(240, 320);
        g.requireM3G();
        // DirectX7 is a singleton whose constructor resets the per-level statics,
        // so every scene renderer gets the level scalars re-applied here.
        DirectX7.setDrDist(levelDist);
        DirectX7.standartDrawmode = drawmode;
        Matrix rx = new Matrix(), ry = new Matrix(), camera = new Matrix();
        rx.setRotX(pitch);
        ry.setRotY(yaw);
        camera.mul(ry, rx);
        camera.setPosition(position[0], position[1], position[2]);
        g.setCamera(camera);
        for (int frame = 0; frame < 3; frame++) {
            g.beginFrameStats();
            int barriersBefore = g.getM3GCpuBarriers();
            DirectX7.setFogDist(fogDist);
            int history = Graphics3D.history.size();
            beyondFar = countBeyondFar(house, 40000);
            sky.addViewport(0, 0, g.width, g.height);
            sky.draw = true;
            sky.lighting = false;
            queueSprites(g);
            house.render(null, g, House.getParts(house, 0, camera), position[0], position[2]);
            queuedObjects = Math.max(queuedObjects, queued(g));
            g.renderGameFrameImage(null, false, false, house, 0, null);
            if (frame == 0) {
                try {
                    warmTextures(g);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            if (frame == 2) {
                draws = g.getM3GDrawCalls();
                java.util.HashMap<Object,int[]> byTex = new java.util.HashMap<Object,int[]>();
                for (int i = history; i < Graphics3D.history.size(); i++) {
                    Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                    if (!worldOf(d)) continue;
                    int[] v = byTex.get(d.texture);
                    if (v == null) { v = new int[1]; byTex.put(d.texture, v); }
                    v[0] += d.triangles;
                }
                int optimal = 0;
                for (java.util.Iterator<int[]> it = byTex.values().iterator(); it.hasNext();) {
                    int tris = it.next()[0];
                    optimal += Math.max(1, (tris + 127) / 128); // 384 verts = 128 triangles
                }
                frameOptimal = optimal; frameTextures = byTex.size();
                int wDraws = 0, sDraws = 0;
                for (int i = history; i < Graphics3D.history.size(); i++) {
                    Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                    if (worldOf(d)) wDraws++; else if (d.vertexCount <= 4) sDraws++;
                }
                frameWorld = wDraws; frameSprites = sDraws;
                measureArea(history, yaw + "@" + pitch);
                // Did any depth-tested world submission land AFTER a sprite?
                int lastLit = -1, firstSprite = -1;
                for (int i = history; i < Graphics3D.history.size(); i++) {
                    Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                    int k = i - history;
                    if (worldOf(d)) lastLit = k;
                    else if (d.vertexCount <= 4 && firstSprite < 0) firstSprite = k;
                }
                if (firstSprite >= 0 && lastLit >= 0) bothFrames++;
                if (firstSprite >= 0 && lastLit > firstSprite) overlapFrames++;
                billboards = 0;
                for (int i = history; i < Graphics3D.history.size(); i++) {
                    Graphics3D.Draw draw = Graphics3D.history.elementAt(i);
                    triangles += draw.triangles;
                    if (worldOf(draw) && draw.colors != null) {
                        for (int c = 0; c + 3 < draw.colors.length; c += 4) {
                            vcolSum += draw.colors[c] & 255;
                            vcolSum += (draw.colors[c + 1] & 255) * 3;
                            vcolSum += (draw.colors[c + 2] & 255) * 7;
                            vcolN++;
                            if ((draw.colors[c] & 255) != 255) vcolNonWhite++;
                        }
                    }
                    if (draw.colors != null && !(!worldOf(draw) && draw.vertexCount <= 4)) {
                        for (int c = 0; c + 3 < draw.colors.length; c += 4) {
                            allSum += draw.colors[c] & 255;
                            allSum += (draw.colors[c + 1] & 255) * 3;
                            allSum += (draw.colors[c + 2] & 255) * 7;
                            allN++;
                            if ((draw.colors[c] & 255) != 255) allNonWhite++;
                        }
                    }
                    boolean billboard = !worldOf(draw) && draw.vertexCount <= 4;
                    if (billboard) billboards++;

                    TEXTURES.add(draw.texture == null ? "none"
                            : Integer.toString(System.identityHashCode(draw.texture)));
                    count("lit=" + worldOf(draw) + " mat=" + (draw.material != null) + " blend=" + draw.blend
                            + " persp=" + draw.perspective + " billboard=" + billboard);
                }
            }
        }
        g.destroy();
        totalDraws += draws;
        totalFrames++;
        totalTriangles += triangles;
        billboardCalls += billboards;
        if (draws > worstDraws) {
            worstSplit = " (world=" + frameWorld + " sprites=" + frameSprites
                    + " | texture-sorted world would be " + frameOptimal + " calls over " + frameTextures + " textures)";
            worstDraws = draws;
            worstBarriers = Math.max(worstBarriers, barriers);
            worstTriangles = triangles;
            worstYaw = yaw;
            worstPitch = pitch;
        }
        if (System.getProperty("probe.verbose") != null) {
            System.out.println("  yaw=" + yaw + " pitch=" + pitch + " draws=" + draws + " triangles=" + triangles);
        }
    }

    public static void main(String[] args) throws Exception {
        String levelFile = args.length > 0 ? args[0] : "/level41.txt";
        Method cut = LevelLoader.class.getDeclaredMethod("cutLevelFile", String.class);
        cut.setAccessible(true);
        String[] data = (String[]) cut.invoke(null, text(levelFile));
        GameIni level = new GameIni(data[0], false);

        // A/B the screen-extent guard without a rebuild: setting.txt inside the
        // resource JAR is not the copy this harness reads.
        Main.m3gLitMaxSpan = Integer.getInteger("probe.maxSpan", Main.m3gLitMaxSpan);
        Main.pixelsQ = 2;
        Main.persQ = 4;
        // The real game leaves this on, and Asset only BUILDS mip chains when
        // it is on. Forcing it off here made the harness silently measure a
        // build where M3G_LIT_MIP could do nothing at all.
        Main.mipMapping = Integer.getInteger("probe.mipMapping", 1) == 1;
        Main.fogQ = 2;
        Main.floorOffsetSZ = 0;
        Main.m3gWorldLighting = Integer.getInteger("m3g.worldLighting", 1);
        // The real game reads setting.txt through Main.init; the harness must do
        // the same or keys there (M3G_LIT_WORLD9, renderer tuning) are silently
        // ignored and measurements stop matching the shipped behaviour.
        code.utils.GameIni settings = null;
        try {
            String settingsText = code.utils.StringTools.getStringFromResource("/setting.txt");
            if (settingsText != null && settingsText.length() > 0)
                settings = new code.utils.GameIni(settingsText, true);
        } catch (Exception ex) { settings = null; }
        if (settings != null) {
            Main.settings = settings;
            Main.m3gLitWorld9 = settings.getIntNoLang("M3G_LIT_WORLD9", 0);
            Main.m3gLitBaked = settings.getIntNoLang("M3G_LIT_BAKED", 0);
            Main.m3gLazyReadback = settings.getIntNoLang("M3G_LAZY_READBACK", 0) == 1 ? 1 : 0;
            Main.m3gLitNoLights = settings.getIntNoLang("M3G_LIT_NO_LIGHTS", 0) == 1 ? 1 : 0;
            Main.m3gLitAmbient = settings.getIntNoLang("M3G_LIT_AMBIENT", 32);
            Main.m3gLitDir = settings.getIntNoLang("M3G_LIT_DIR", 150);
            Main.m3gLitFar = settings.getIntNoLang("M3G_LIT_FAR", 600000);
            Main.m3gLitDist = settings.getIntNoLang("M3G_LIT_DIST", 0);
            Main.m3gLitMip = settings.getIntNoLang("M3G_LIT_MIP", 0);
            Main.m3gLitFlat = settings.getIntNoLang("M3G_LIT_FLAT", 0);
            Main.m3gLitStatic = settings.getIntNoLang("M3G_LIT_STATIC", 0);
            Main.m3gLitCullNone = settings.getIntNoLang("M3G_LIT_CULL", 1) == 0;
            Main.m3gLitFlipWinding = settings.getIntNoLang("M3G_LIT_FLIP", 0) == 1;
            Main.m3gLitNoVertexColors = settings.getIntNoLang("M3G_LIT_NOVCOL", 0) == 1;
        }
        String w9 = System.getProperty("m3g.litWorld9");
        if (w9 != null) Main.m3gLitWorld9 = Integer.parseInt(w9);
        DirectX7.standartDrawmode = (byte) level.getInt("DMODE", 0);
        DirectX7.setDrDist(level.getInt("DIST", Integer.MAX_VALUE));
        DirectX7.setFogDist(level.getInt("FOGD", 1));

        // LevelLoader.cutLevelFile returns ONE element when the level has no
        // WTEX block, and the game then simply has no texture list. Mirror that
        // instead of indexing a missing element (L41 has no WTEX group).
        String[] textureLines = data.length > 1
                ? StringTools.cutOnStrings(data[1], '\n') : new String[0];
        for (int i = 0; i < textureLines.length; i++) {
            String line = textureLines[i].trim();
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String value = line.substring(equals + 1);
            int comma = value.indexOf(',');
            if (comma >= 0) value = value.substring(0, comma);
            if (value.startsWith("/")) image(value);
        }
        code.Rendering.MultyTexture textures = new code.Rendering.MultyTexture(textureLines);
        Mesh[] meshes = decode(level.get("WORLD_MODEL"), level.getFloat("WORLD_SCALE", 1), textures);

        Room.chunkSize = 5000;
        Room.chunkSizeRender = Integer.getInteger("probe.chunkSize", level.getInt("CHUNK_SIZE", 0));
        if (System.getProperty("probe.lm") != null) {
            int lt = 0, cl = 0, pl = 0;
            for (int i = 0; i < meshes.length; i++) {
                code.Rendering.RenderObject[] objs = meshes[i].getPolygons();
                for (int x = 0; x < objs.length; x++) {
                    if (objs[x] instanceof code.Rendering.Meshes.LightedPolygon3V) lt++;
                    else if (objs[x] instanceof code.Rendering.Meshes.ColorLightedPolygon3V) cl++;
                    else if (objs[x] instanceof code.Rendering.Meshes.Polygon3V) pl++;
                }
            }
            System.out.println("meshTypes: lighted3v=" + lt + " colorLighted=" + cl + " plain3v=" + pl
                    + " dmode=" + DirectX7.standartDrawmode + " lightmap=" + level.get("LIGHTMAP"));
        }
        House house = HouseCreator.create(meshes, false, level.get("LIGHTMAP"), true);
        if (System.getProperty("probe.lm") != null) {
            java.util.TreeMap<Integer,Integer> h = new java.util.TreeMap<Integer,Integer>();
            for (int i = 0; i < meshes.length; i++) {
                code.Rendering.RenderObject[] o2 = meshes[i].getPolygons();
                for (int x = 0; x < o2.length; x++) {
                    if (o2[x] instanceof code.Rendering.Meshes.LightedPolygon3V) {
                        int v = ((code.Rendering.Meshes.LightedPolygon3V) o2[x]).la;
                        Integer n = h.get(v); h.put(v, n == null ? 1 : n + 1);
                    }
                }
            }
            System.out.println("laValues: distinct=" + h.size() + " sample=" + h.headMap(256).toString().substring(0, Math.min(160, h.toString().length())));
        }

        DirectX7.standartDrawmode = 0;
        image(level.get("SKYBOX_TEXTURE"));
        code.Rendering.MultyTexture skyTexture =
                new code.Rendering.MultyTexture(level.get("SKYBOX_TEXTURE"), true);
        Mesh skyMesh = decode(level.get("SKYBOX_MODEL"), 7, skyTexture)[0];
        Skybox sky = new Skybox();
        sky.texture = skyTexture;
        set(sky, "mesh", skyMesh);
        set(sky, "matrix", new Matrix());
        house.setSkybox(sky);
        DirectX7.standartDrawmode = 9;
        // DirectX7 is a singleton and HouseCreator.create() builds a new one,
        // whose resize() resets these statics to their defaults. LevelLoader sets
        // them once and then constructs Scene, so its values survive; a harness
        // that sets them earlier silently measures the defaults instead.
        DirectX7.setDrDist(level.getInt("DIST", Integer.MAX_VALUE));
        DirectX7.standartDrawmode = (byte) level.getInt("DMODE", 0);
        DirectX7.setFogDist(level.getInt("FOGD", 1));

        prepareSprites(Integer.getInteger("probe.sprites", 0),
                Integer.getInteger("probe.spriteTextures", Integer.MAX_VALUE));

        // Materials only take the native Fog path when fDist > 1, and most levels do
        // not set FOGD at all. Measure the fog path the game actually uses.
        int levelFog = level.getInt("FOGD", 1);
        int fogDist = Integer.getInteger("probe.fogDist", levelFog > 1 ? levelFog : 80000);
        int[][] positions = {{0, 0, 0}, {-60000, 0, -60000}};
        Height height = new Height();
        height.getPosition().set(0, 30000, 0);
        house.computeHeight(0, height);
        positions[0][1] = height.getHeight() + 1503;
        height = new Height();
        height.getPosition().set(positions[1][0], 30000, positions[1][2]);
        house.computeHeight(0, height);
        positions[1][1] = height.getHeight() + 1503;

        System.out.println("world polygons=" + meshes[0].getPolygons().length
                + " chunkSize=" + Room.chunkSizeRender
                + " fogDist=" + fogDist
                + " sprites=" + (sprites == null ? 0 : sprites.length));
        for (int[] position : positions) {
            String only = System.getProperty("probe.onlyView");
            for (int pitch : new int[]{0, 30, 60, 85}) {
                for (int yaw = 0; yaw < 360; yaw += 45) {
                    if (only != null && !only.equals(yaw + "@" + pitch)) continue;
                    sample(house, sky, position, yaw, pitch, fogDist,
                            level.getInt("DIST", Integer.MAX_VALUE), (byte) level.getInt("DMODE", 0));
                }
            }
        }

        StringBuilder mix = new StringBuilder();
        for (Map.Entry<String, Integer> entry : DRAW_MIX.entrySet()) {
            if (mix.length() > 0) mix.append(", ");
            mix.append(entry.getKey()).append('x').append(entry.getValue());
        }
        System.out.println("avgDraws=" + (totalDraws / Math.max(1, totalFrames))
                + " worstDraws=" + worstDraws
                + " (yaw=" + worstYaw + " pitch=" + worstPitch + ")");
        System.out.println("beyondFar: " + beyondFar + " polys fully past M3G_LIT_FAR=" + Main.m3gLitFar
                + "; nearPolys=" + nearPolys);
        System.out.println("triangles: avg=" + (totalTriangles / Math.max(1, totalFrames))
                + " worstFrame=" + worstTriangles);
        System.out.println("billboardCalls=" + billboardCalls + " frames=" + totalFrames);
        System.out.println("queuedObjectsPeak=" + queuedObjects);
        System.out.println("distinctTexturesInWorstFrame=" + TEXTURES.size());
        System.out.println("drawMix: " + mix);
        System.out.println("worstSplit: world + sprites = " + worstSplit);
        System.out.println("litFill: draws=" + worstAreaDraws + " tris=" + worstAreaPolys
                + " raw=" + one(worstRaw) + " screens"
                + " clip=" + one(worstClip) + " screens"
                + " ratio=" + (worstClip > 0 ? one(worstRaw / worstClip) : "inf") + "x"
                + " worstDraw=" + one(worstDrawArea) + " screens"
                + " trisInNearBand=" + worstBehind + " trisAtEye=" + worstAtEye
                + " widerThan4x=" + worstWide
                + " (worst view " + worstAreaView + ")");
        {
            StringBuilder sb = new StringBuilder("litFillBySpan: total=" + one(worstRaw) + " screens");
            for (int b = 0; b < SPANS.length; b++)
                sb.append("  >").append(one(SPANS[b])).append("vp=").append(one(worstOverRaw[b]))
                  .append("scr/").append(worstOverTris[b]).append("tri");
            System.out.println(sb.toString());
            System.out.println("litFillOffscreen: " + one(worstOffscreenRaw)
                    + " screens of bbox for triangles that show no pixels at all");
        }
        System.out.println("litFillCost: raw x 14ms/screen = " + one(worstRaw * 14) + " ms"
                + "  clip x 14ms/screen = " + one(worstClip * 14) + " ms"
                + "  (device lit flush: 2794 ms)");
        System.out.println("allColours: sum=" + allSum + " verts=" + allN + " nonWhite=" + allNonWhite
                + "   (lit+packed: moves only if geometry or colours change)");
        System.out.println("cpuBarriers: worst=" + worstBarriers
                + " lazyReadback=" + Main.m3gLazyReadback);
        System.out.println("lights: " + code.Rendering.M3GLitRenderer.lightSig
                + " cull=" + (Main.m3gLitCullNone ? "NONE" : "BACK"));
        System.out.println("litRejects: " + code.Rendering.M3GLitRenderer.statsFail);
        System.out.println("culled: dist=" + code.Rendering.M3GLitRenderer.cullDist
                + " (limit=" + code.Rendering.M3GLitRenderer.lastDistLimit
                + ", levelDIST=" + DirectX7.drDist + ")"
                + " behind=" + code.Rendering.M3GLitRenderer.cullBehind
                + " near=" + code.Rendering.M3GLitRenderer.cullNear
                + " minPx=" + code.Rendering.M3GLitRenderer.cullMinPx);
        System.out.println("lightMode: world9=" + Main.m3gLitWorld9 + " baked=" + Main.m3gLitBaked
                + " ambient=" + Main.m3gLitAmbient + " dir=" + Main.m3gLitDir
                + " maxSpan=" + Main.m3gLitMaxSpan
                + " cullWide=" + code.Rendering.M3GLitRenderer.cullWide
                + " cullOffscreen=" + code.Rendering.M3GLitRenderer.cullOffscreen);
        if (System.getProperty("probe.vcol2") != null) {
            int m0 = 0, m1 = 0; java.util.TreeMap<Integer,Integer> a = new java.util.TreeMap<Integer,Integer>();
            java.util.TreeMap<Integer,Integer> b = new java.util.TreeMap<Integer,Integer>();
            for (int i = 0; i < Graphics3D.history.size(); i++) {
                Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                if (!worldOf(d) || d.colors == null) continue;
                java.util.TreeMap<Integer,Integer> tgt = d.material == null ? a : b;
                for (int c = 0; c + 3 < d.colors.length; c += 4) {
                    Integer key = ((d.colors[c] & 255) << 16) | ((d.colors[c+1] & 255) << 8) | (d.colors[c+2] & 255);
                    Integer n = tgt.get(key); tgt.put(key, n == null ? 1 : n + 1);
                }
                if (d.material == null) m0++; else m1++;
            }
            System.out.println("vcol_by_material: unlitDraws=" + m0 + " litDraws=" + m1);
            System.out.println("  unlit colours: distinct=" + a.size() + " " + (a.size() <= 12 ? a.toString() : a.firstKey() + ".." + a.lastKey()));
            System.out.println("  mat colours:   distinct=" + b.size() + " " + (b.size() <= 12 ? b.toString() : b.firstKey() + ".." + b.lastKey()));
        }
        if (System.getProperty("probe.vcol") != null) {
            java.util.HashMap<Integer,Integer> hist = new java.util.HashMap<Integer,Integer>();
            for (int i = 0; i < Graphics3D.history.size(); i++) {
                Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                if (!worldOf(d) || d.colors == null) continue;
                for (int c = 0; c + 3 < d.colors.length; c += 4) {
                    Integer key = ((d.colors[c] & 255) << 16) | ((d.colors[c+1] & 255) << 8) | (d.colors[c+2] & 255);
                    Integer n = hist.get(key); hist.put(key, n == null ? 1 : n + 1);
                }
            }
            java.util.TreeMap<Integer,Integer> s = new java.util.TreeMap<Integer,Integer>(hist);
            System.out.println("vcolHist: distinct=" + s.size() + " " + (s.size() <= 40 ? s.toString() : s.firstKey() + ".." + s.lastKey()));
        }
        System.out.println("vertexColours: sum=" + vcolSum + " verts=" + vcolN
                + " nonWhite=" + vcolNonWhite);
        System.out.println("worldAfterSprites: " + overlapFrames + " violations in " + bothFrames
                + " frames that have both world and sprites");
        if (System.getProperty("probe.litPos") != null) {
            int n = Graphics3D.history.size();
            int lastSprite = -1, litAfterSprite = 0, totalLit = 0;
            for (int i = 0; i < n; i++) {
                Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                if (!worldOf(d) && d.vertexCount <= 4) lastSprite = i;
            }
            for (int i = 0; i < n; i++) {
                Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                if (worldOf(d)) { totalLit++; if (i > lastSprite) litAfterSprite++; }
            }
            System.out.println("litPos: totalLit=" + totalLit + " paintedAfterLastSprite=" + litAfterSprite);
        }
        if (System.getProperty("probe.order") != null) {
            StringBuilder sb = new StringBuilder("drawOrder: ");
            int n = Graphics3D.history.size();
            for (int i = Math.max(0, n - 24); i < n; i++) {
                Graphics3D.Draw d = Graphics3D.history.elementAt(i);
                sb.append(worldOf(d) ? "LIT" : (d.vertexCount <= 4 ? "spr" : "PK"))
                  .append("(").append(d.triangles).append(") ");
            }
            System.out.println(sb);
        }
        house.destroy();
    }

    /** World geometry = the depth-tested submission. Baked mode 9 draws with a
     * null Material on purpose (that is what disables lighting and preserves
     * the vertex-colour lightmap), so a Material check would miscount it. */
    private static boolean worldOf(Graphics3D.Draw d) {
        return d.depthTest && d.vertexCount > 4;
    }
}
