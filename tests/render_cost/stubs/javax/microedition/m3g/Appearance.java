package javax.microedition.m3g;
public class Appearance {
    public PolygonMode polygon;
    public CompositingMode composite;
    public Fog fog;
    public Material material;
    public Texture2D[] textures = new Texture2D[8];
    public int layer;
    public void setCompositingMode(CompositingMode m) { composite = m; }
    public CompositingMode getCompositingMode() { return composite; }
    public void setFog(Fog f) { fog = f; }
    public Fog getFog() { return fog; }
    public void setPolygonMode(PolygonMode m) { polygon = m; }
    public PolygonMode getPolygonMode() { return polygon; }
    public void setLayer(int l) { layer = l; }
    public int getLayer() { return layer; }
    public void setMaterial(Material m) { material = m; }
    public Material getMaterial() { return material; }
    public void setTexture(int index, Texture2D t) { textures[index] = t; }
    public Texture2D getTexture(int index) { return textures[index]; }
}
