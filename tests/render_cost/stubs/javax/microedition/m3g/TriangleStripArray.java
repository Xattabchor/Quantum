package javax.microedition.m3g;
public class TriangleStripArray extends IndexBuffer {
    public int[] indices, lengths;
    public TriangleStripArray(int first, int[] lengths) {
        super(total(lengths));
        this.lengths = lengths.clone();
        this.indices = new int[total(lengths)];
        for (int i = 0; i < indices.length; i++) indices[i] = first + i;
    }
    public TriangleStripArray(int[] indices, int[] lengths) {
        super(total(lengths));
        if (indices.length != total(lengths)) throw new IllegalArgumentException("index count");
        this.lengths = lengths.clone();
        this.indices = indices.clone();
    }
    private static int total(int[] lengths) { int t = 0; for (int i = 0; i < lengths.length; i++) { if (lengths[i] < 3) throw new IllegalArgumentException("strip too short"); t += lengths[i]; } return t; }
    public void getIndices(int[] out) { System.arraycopy(indices, 0, out, 0, indices.length); }
}
