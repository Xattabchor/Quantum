package javax.microedition.m3g;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.Image;

/** Recording M3G double for draw-call accounting. Never renders pixels. */
public class Graphics3D {

    public static final int ANTIALIAS = 2, DITHER = 4, TRUE_COLOR = 8, OVERWRITE = 16;

    /** One native submission, as the renderer actually asked for it. */
    public static final class Draw {
        public Texture2D texture;
        public PolygonMode polygon;
        public int blend, function, blendColor, filter, levelFilter, threshold;
        public int vertexCount, indexCount, triangles;
        public float fogNear, fogFar, fogDensity, scale;
        public int fogColor, fogMode;
        public boolean fog;
        public Material material;
        /** Depth-tested world submission: the lit/sorted pass, Material or not. */
        public boolean depthTest;
        public Transform transform;
        public short[] positions;
        public byte[] colors;
        public boolean perspective;
        public String key() {
            return (texture == null ? "-" : Integer.toString(System.identityHashCode(texture.image)))
                    + "/" + blend + "/" + function + "/" + blendColor + "/" + filter
                    + (fog ? "/f" + fogColor + "/" + fogMode + "/" + fogNear + "/" + fogFar + "/" + fogDensity : "/nf")
                    + "/" + (polygon == null ? "-" : polygon.cull + ":" + polygon.winding + ":" + polygon.perspective)
                    + (material == null ? "" : "/lit");
        }
    }

    public static final Vector<Draw> history = new Vector<Draw>();
    public static int calls, binds, releases, clears;
    public static Graphics3D target;      // non-null while a render target is bound
    public static Object boundObject;
    public static Camera camera;
    /** The Image whose pixels the current target writes into, if any. */
    public static javax.microedition.lcdui.Image surfaceImage;
    public static int viewportW = 240, viewportH = 320;
    public static float depthNear, depthFar;
    public static final Vector<Light> lights = new Vector<Light>();

    private static final Graphics3D INSTANCE = new Graphics3D();

    public static Graphics3D getInstance() { return INSTANCE; }

    public static Hashtable getProperties() {
        Hashtable p = new Hashtable();
        p.put("maxTextureDimension", Integer.getInteger("m3g.maxTexture", 256));
        return p;
    }

    public void bindTarget(Object target) { bindTarget(target, true, 0); }

    public void bindTarget(Object target, boolean depth, int hints) {
        if (Graphics3D.target != null) throw new AssertionError("target leak");
        if (Boolean.getBoolean("canvas.bind.fail")) throw new IllegalStateException("bind");
        Graphics3D.target = this; boundObject = target; binds++;
        if (target instanceof javax.microedition.lcdui.Graphics) {
            surfaceImage = ((javax.microedition.lcdui.Graphics) target).image;
        } else surfaceImage = null;
    }

    public void releaseTarget() {
        if (boundObject == null) throw new AssertionError("double release");
        boundObject = null; target = null; surfaceImage = null; releases++;
        if (Boolean.getBoolean("release.fail")) throw new IllegalStateException("release");
    }

    public void setViewport(int x, int y, int w, int h) {
        if (Boolean.getBoolean("canvas.viewport.fail")) throw new IllegalStateException("viewport");
        viewportW = w; viewportH = h;
    }

    public void clear(Background background) { clears++; }

    public void setCamera(Camera camera, Transform transform) { this.camera = camera; }

    public int addLight(Light light, Transform transform) { lights.addElement(light); return lights.size() - 1; }

    public void setLight(int index, Light light, Transform transform) {
        if (index >= lights.size()) throw new IndexOutOfBoundsException("setLight");
        lights.setElementAt(light, index);
    }

    public void resetLights() { lights.removeAllElements(); }

    public int getLightCount() { return lights.size(); }

    public void setDepthRange(float near, float far) { depthNear = near; depthFar = far; }

    public float getDepthRangeNear() { return depthNear; }

    public float getDepthRangeFar() { return depthFar; }

    public Camera getCamera(Transform transform) { return camera; }

    public Object getTarget() { return boundObject; }

    public int getViewportWidth() { return viewportW; }

    public int getViewportHeight() { return viewportH; }

    public boolean isDepthBufferEnabled() { return false; }

    public void render(VertexBuffer vertices, IndexBuffer indices, Appearance appearance, Transform transform) {
        if (boundObject == null) throw new AssertionError("draw without target");
        if (vertices == null || indices == null) throw new NullPointerException("draw args");
        calls++;
        Draw d = new Draw();
        d.vertexCount = vertices.getVertexCount();
        d.indexCount = indices.getIndexCount();
        d.triangles = d.indexCount - 2 * strips(indices);
        d.positions = vertices.positions == null ? null : vertices.positions.shorts;
        d.colors = vertices.colors == null ? null : vertices.colors.bytes;
        d.scale = vertices.positionScale[0];
        if (appearance != null) {
            d.polygon = appearance.getPolygonMode();
            d.perspective = d.polygon == null || d.polygon.perspective;
            CompositingMode composite = appearance.getCompositingMode();
            if (composite != null) { d.blend = composite.getBlending(); d.threshold = (int) composite.getAlphaThreshold();
                d.depthTest = composite.isDepthTestEnabled(); }
            Fog fog = appearance.getFog();
            if (fog != null) {
                d.fog = true; d.fogColor = fog.getColor(); d.fogMode = fog.getMode();
                d.fogNear = fog.getNearDistance(); d.fogFar = fog.getFarDistance(); d.fogDensity = fog.getDensity();
            }
            d.material = appearance.getMaterial();
            d.texture = appearance.getTexture(0);
            if (d.texture != null) {
                d.function = d.texture.getBlending(); d.filter = d.texture.getImageFilter();
                d.levelFilter = d.texture.getLevelFilter(); d.blendColor = d.texture.getBlendColor();
            }
        }
        d.transform = transform;
        history.addElement(d);
    }

    private static int strips(IndexBuffer indices) { return indices instanceof TriangleStripArray ? ((TriangleStripArray) indices).lengths.length : 1; }

    public void render(VertexBuffer vertices, IndexBuffer indices, Appearance appearance, Transform transform, int scope) {
        render(vertices, indices, appearance, transform);
    }
}
