package java.lang;

/**
 * Compile-time stub for CLDC 1.1's java.lang.StringBuilder.
 *
 * The vipaoL cldc11.jar API collection predates JSR-139 and ships only
 * StringBuffer. Real CLDC 1.1 devices (Nokia S40 etc.) include
 * StringBuilder, and JDK8 javac emits StringBuilder-based string
 * concatenation for -source 1.5. This stub is never packaged into the
 * game jar; it only lets javac resolve the class against the API
 * bootclasspath. Method bodies are irrelevant (API stub).
 */
public class StringBuilder {

    public StringBuilder() { }

    public StringBuilder(int capacity) { }

    public StringBuilder(String value) { }

    public StringBuilder append(Object value) { return this; }

    public StringBuilder append(String value) { return this; }

    public StringBuilder append(StringBuffer value) { return this; }

    public StringBuilder append(char[] value) { return this; }

    public StringBuilder append(boolean value) { return this; }

    public StringBuilder append(char value) { return this; }

    public StringBuilder append(int value) { return this; }

    public StringBuilder append(long value) { return this; }

    public StringBuilder append(float value) { return this; }

    public StringBuilder append(double value) { return this; }

    public int length() { return 0; }

    public String toString() { return ""; }
}
