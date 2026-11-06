package javax.microedition.m3g;
public class VertexBuffer {
    public int count;
    public float[] positionBias = new float[]{0, 0, 0}, positionScale = new float[]{1, 1, 1};
    public VertexArray positions, colors, normals;
    public VertexArray[] texCoords = new VertexArray[8];
    public float[] texBias = new float[8], texScale = new float[8];
    public void setPositions(VertexArray a, float scale, float[] bias) {
        if (a.getComponentCount() != 3) throw new IllegalArgumentException("positions need 3 components");
        positions = a; count = a.getVertexCount();
        if (scale != 0f || bias != null) {
            positionScale = new float[]{scale, scale, scale};
            if (bias != null) positionBias = bias.clone();
        }
    }
    public void setTexCoords(int index, VertexArray a, float scale, float[] bias) {
        if (index < 0 || index > 7 || a.getComponentCount() != 2) throw new IllegalArgumentException("texcoords");
        texCoords[index] = a; texScale[index] = scale;
        if (bias != null) texBias[index] = bias[index < bias.length ? index : 0];
    }
    public void setColors(VertexArray a) {
        int n = a.getComponentCount();
        if (n != 3 && n != 4) throw new IllegalArgumentException("colors need 3 or 4 components");
        colors = a;
    }
    public void setNormals(VertexArray a) {
        if (a.getComponentCount() != 3) throw new IllegalArgumentException("normals need 3 components");
        normals = a;
    }
    public int getVertexCount() { return count; }
    public VertexArray getPositions(float[] bias) { if (bias != null && bias.length >= 3) System.arraycopy(positionBias, 0, bias, 0, 3); return positions; }
    public VertexArray getTexCoords(int index, float[] bias) { if (bias != null && bias.length > 0) bias[0] = texBias[index]; return texCoords[index]; }
    public VertexArray getNormals() { return normals; }
    public VertexArray getColors() { return colors; }
    public void setDefaultColor(int argb) {}
    public int getDefaultColor() { return 0xffffffff; }
}
