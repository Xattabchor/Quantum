package javax.microedition.m3g;

/** Recording VertexArray: keeps a full-capacity copy so partial set() works. */
public class VertexArray {

    public static final int BYTE = 1, SHORT = 2;

    public final int components, type, count;
    public short[] shorts;
    public byte[] bytes;

    public VertexArray(int count, int components, int type) {
        if (count <= 0 || components <= 0) throw new IllegalArgumentException("vertex array");
        this.count = count; this.components = components; this.type = type;
    }

    private void check(int first, int length) {
        if (first < 0 || length < 0 || first + length > count) throw new IndexOutOfBoundsException("VertexArray " + first + "+" + length + ">" + count);
    }

    public void set(int first, int length, short[] values) {
        check(first, length);
        if (values == null || values.length < length * components) throw new IllegalArgumentException("short values too short");
        if (shorts == null) shorts = new short[count * components];
        System.arraycopy(values, 0, shorts, first * components, length * components);
    }

    public void set(int first, int length, byte[] values) {
        check(first, length);
        if (values == null || values.length < length * components) throw new IllegalArgumentException("byte values too short");
        if (bytes == null) bytes = new byte[count * components];
        System.arraycopy(values, 0, bytes, first * components, length * components);
    }

    public void get(int first, int length, short[] out) { check(first, length); System.arraycopy(shorts, first * components, out, 0, length * components); }

    public void get(int first, int length, byte[] out) { check(first, length); System.arraycopy(bytes, first * components, out, 0, length * components); }

    public int getComponentCount() { return components; }

    public int getComponentType() { return type; }

    public int getVertexCount() { return count; }
}
