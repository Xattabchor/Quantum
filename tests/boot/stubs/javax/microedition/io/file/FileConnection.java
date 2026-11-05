package javax.microedition.io.file;

public interface FileConnection extends javax.microedition.io.InputConnection, javax.microedition.io.OutputConnection {
    boolean exists();
    void create() throws java.io.IOException;
    void delete() throws java.io.IOException;
    void mkdir() throws java.io.IOException;
    long fileSize() throws java.io.IOException;
    java.io.InputStream openInputStream(long start, long len) throws java.io.IOException;
    java.io.OutputStream openOutputStream(long start) throws java.io.IOException;
    void truncate(long byteOffset) throws java.io.IOException;
    void setReadable(boolean r) throws java.io.IOException;
    void setWritable(boolean w) throws java.io.IOException;
    void setHidden(boolean h) throws java.io.IOException;
    java.util.Enumeration list() throws java.io.IOException;
    java.util.Enumeration list(String filter, boolean includeHidden) throws java.io.IOException;
    String getURL();
    String getName();
    String getPath();
    long lastModified();
}
