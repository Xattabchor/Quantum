package javax.microedition.m3g;
public abstract class IndexBuffer {
    private final int count;
    protected IndexBuffer(int count) { this.count = count; }
    public int getIndexCount() { return count; }
    public abstract void getIndices(int[] out);
    protected static int max(int[] v) { int m = 0; for (int i = 0; i < v.length; i++) if (v[i] > m) m = v[i]; return m; }
}
