package javax.microedition.io.file;

import java.io.IOException;
import java.util.Enumeration;

import javax.microedition.io.StreamConnection;

/**
 * Compile-only заглушка JSR-75 (FileConnection API) для сборки конвертера
 * QCT без настоящего JSR-75 в тулчейне. На аппарате класс приходит из
 * прошивки; в конвертерский JAR заглушка НЕ упаковывается (иначе она бы
 * заместила системный интерфейс и сломала linkage).
 * Сигнатуры - по спецификации JSR-75 (FileConnection 1.0).
 */
public interface FileConnection extends StreamConnection {

    boolean isOpen();

    String getName();

    String getPath();

    long fileSize() throws IOException;

    long directorySize(boolean includeSubDirs) throws IOException;

    long totalSize();

    long availableSize();

    long usedSize();

    boolean exists() throws IOException;

    boolean isDirectory() throws IOException;

    boolean isHidden();

    void setHidden(boolean hidden) throws IOException;

    boolean canRead();

    boolean canWrite();

    void setReadable(boolean readable);

    void setWritable(boolean writable);

    long lastModified();

    Enumeration list() throws IOException;

    Enumeration list(String filter, boolean includeHidden)
            throws IOException;

    void create() throws IOException;

    void mkdir() throws IOException;

    void mkdirs() throws IOException;

    void delete() throws IOException;

    void rename(String newPath) throws IOException;

    void truncate(long byteOffset) throws IOException;

    void setFileConnection(String fileName) throws IOException;
}
