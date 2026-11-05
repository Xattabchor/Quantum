package javax.microedition.io;

/** Only used by the lightmap authoring path; never opens anything on desktop. */
public final class Connector {
    public static final int READ = 1, WRITE = 2, READ_WRITE = 3;

    public static javax.microedition.io.Connection open(String name) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }

    public static javax.microedition.io.Connection open(String name, int mode) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }

    public static java.io.DataInputStream openDataInputStream(String name) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }

    public static java.io.DataOutputStream openDataOutputStream(String name) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }

    public static java.io.InputStream openInputStream(String name) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }

    public static java.io.OutputStream openOutputStream(String name) throws java.io.IOException {
        throw new java.io.IOException("connector stub: " + name);
    }
}
