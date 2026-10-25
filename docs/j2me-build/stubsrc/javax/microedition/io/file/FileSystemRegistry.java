package javax.microedition.io.file;

import java.util.Enumeration;

/**
 * Compile-only заглушка JSR-75 (FileSystemRegistry) - парная к
 * FileConnection.java. На аппарате класс приходит из прошивки; в JAR
 * конвертера заглушка НЕ упаковывается.
 * Сигнатура - по спецификации JSR-75 (FileConnection 1.0):
 * listRoots() возвращает Enumeration строк вида "c:/" (имена корней
 * без префикса file:///).
 */
public class FileSystemRegistry {

    public static Enumeration listRoots() {
        return null;
    }

    // addRootListener/removeRootListener намеренно не включены:
    // конвертер их не использует, а прошивки без них обходятся.
}
