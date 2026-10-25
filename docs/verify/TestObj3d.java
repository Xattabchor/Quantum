import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import tools.objconv.Obj3d;

/**
 * Проверка кодека Obj3d (3D <-> OBJ) без MIDP: компилируется против CLDC,
 * выполняется на обычной JRE. Печатает машиночитаемые строки для
 * docs/verify/test_objconv.py:
 *   OK <кейс> / FAIL <кейс> <детали>
 *   STATS <кейс> m=<меши> v=<вершины> f=<грани>
 *   HEX <кейс> <байты в hex>
 *   ERR <кейс> caught=<0|1>
 *   FRAC fails=<n>
 *   UV fails=<n>
 */
public class TestObj3d {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        synth1();
        uvSweep();
        fracSweep();
        handObj();
        clamp();
        errIdx();
        errEmpty();
        errNegCount();
        errTrunc();
        System.out.println(failures == 0 ? "ALL OK" : "TOTAL FAILS " + failures);
    }

    // ------------------------------------------------ синтетика как в selfTest

    static void synth1() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeInt(2);
        d.writeShort(4);
        d.writeShort(0); d.writeShort(0); d.writeShort(0);
        d.writeShort(100); d.writeShort(0); d.writeShort(0);
        d.writeShort(100); d.writeShort(50); d.writeShort(0);
        d.writeShort(0); d.writeShort(50); d.writeShort(0);
        d.writeShort(1);
        d.writeShort(Obj3d.MAT_MARKER); d.writeShort(3);
        d.writeShort(0); d.writeShort(1); d.writeShort(2);
        d.writeByte(0); d.writeByte(0); d.writeByte(255); d.writeByte(0);
        d.writeByte(255); d.writeByte(255);
        d.writeShort(1);
        d.writeShort(0); d.writeShort(1); d.writeShort(2); d.writeShort(3);
        d.writeByte(128); d.writeByte(64); d.writeByte(32); d.writeByte(16);
        d.writeByte(8); d.writeByte(4); d.writeByte(255); d.writeByte(255);
        d.writeShort(3);
        d.writeShort(-10); d.writeShort(-20); d.writeShort(-30);
        d.writeShort(32767); d.writeShort(-32768); d.writeShort(1);
        d.writeShort(7); d.writeShort(8); d.writeShort(9);
        d.writeShort(1);
        // без маркера: mat0 - стартовое значение блока (избыточный маркер
        // round-trip бы нормализовал, а тест требует байт-в-байт)
        d.writeShort(0); d.writeShort(1); d.writeShort(2);
        d.writeByte(1); d.writeByte(2); d.writeByte(3); d.writeByte(4);
        d.writeByte(5); d.writeByte(6);
        d.writeShort(0);
        d.flush();
        byte[] data = bos.toByteArray();

        int[] st1 = new int[3];
        String obj = Obj3d.toObj(data, st1);
        int[] st2 = new int[3];
        byte[] back = Obj3d.fromObj(obj, st2);
        System.out.println("STATS synth1 m=" + st1[0] + " v=" + st1[1] + " f=" + st1[2]);
        boolean statsOk = st2[0] == st1[0] && st2[1] == st1[1] && st2[2] == st1[2]
                && st1[0] == 2 && st1[1] == 7 && st1[2] == 3;
        report("synth1", statsOk && equals(data, back),
                "stats=" + statsOk + " bytesEq=" + equals(data, back));
        System.out.println("HEX synth1 " + hex(back));
        // второй round-trip из восстановленных байт - OBJ-текст должен совпасть
        String obj2 = Obj3d.toObj(back);
        report("synth1-obj", obj.equals(obj2), "obj text differs");
    }

    // ------------------------------------------------ UV-байты 0..255

    static void uvSweep() {
        int fails = 0;
        for(int b = 0; b < 256; b++) {
            try {
                byte[] data = tinyMesh(b, 255 - b);
                byte[] back = Obj3d.fromObj(Obj3d.toObj(data));
                if(!equals(data, back)) fails++;
            } catch (Throwable t) {
                fails++;
            }
        }
        System.out.println("UV fails=" + fails);
        report("uv", fails == 0, fails + " байт не прошли round-trip");
    }

    /** Меш: 3 вершины, 1 треугольник, все UV = (u,v) на каждый угол. */
    static byte[] tinyMesh(int u, int v) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        d.writeInt(1);
        d.writeShort(3);
        d.writeShort(0); d.writeShort(0); d.writeShort(0);
        d.writeShort(1); d.writeShort(0); d.writeShort(0);
        d.writeShort(0); d.writeShort(1); d.writeShort(0);
        d.writeShort(1);
        d.writeShort(0); d.writeShort(1); d.writeShort(2);
        for(int i = 0; i < 3; i++) {
            d.writeByte(u);
            d.writeByte(v);
        }
        d.writeShort(0);
        d.flush();
        return bos.toByteArray();
    }

    // ------------------------------------------------ точность frac()

    static void fracSweep() {
        int fails = 0;
        for(int b = 0; b < 256; b++) {
            float f = Float.parseFloat(Obj3d.frac(b));
            int back = (int) (f * 255.0f + 0.5f);
            if(back != b) fails++;
        }
        System.out.println("FRAC fails=" + fails);
        report("frac", fails == 0, fails + " значений frac неточны");
    }

    // ------------------------------------------------ рукописный Blender-OBJ

    static void handObj() {
        String obj =
            "# blender export\r\n"
          + "mtllib cube.mtl\n"
          + "o Cube\n"
          + "g Cube\n"
          + "v 0 0 0\n"
          + "v 10 0 0\n"
          + "v 10 20 0\n"
          + "v 0 20 0\n"
          + "v 5 5 30\n"
          + "vn 0 0 1\n"
          + "usemtl Material.001\n"
          + "s off\n"
          + "f 1//1 2//1 3//1 4//1\r\n"
          + "g empty\n"
          + "o Second\n"
          + "v -5 -5 -5\n"
          + "v 32767 0 0\n"
          + "v 0 0.4 -0.6\n"
          + "vt 0.5 0.5\n"
          + "vt 1.0 0.0\n"
          + "vt 0 1\n"
          + "vt 0.25 0.75\n"
          + "vt 0.75 0.25\n"
          + "usemtl mat7\n"
          + "f -1 -2 -3\n"
          + "f 5/1 6/2 7/3 1/4 2/5\n";
        int[] st = new int[3];
        byte[] data = Obj3d.fromObj(obj, st);
        System.out.println("STATS handobj m=" + st[0] + " v=" + st[1] + " f=" + st[2]);
        System.out.println("HEX handobj " + hex(data));
        report("handobj", st[0] == 2 && st[1] == 10 && st[2] == 5,
                "ожидалось m=2 v=10 f=5, получено m=" + st[0] + " v=" + st[1]
                        + " f=" + st[2]);
        // round-trip обратно в OBJ и снова в байты - стабильность
        byte[] again = Obj3d.fromObj(Obj3d.toObj(data));
        report("handobj-rt", equals(data, again), "round-trip байт не совпал");
    }

    // ------------------------------------------------ округление/обрезка координат

    static void clamp() {
        String obj = "v 99999.6 -40000 0.6\nv 1 2 3\nv 4 5 6\nf 1 2 3\n";
        byte[] data = Obj3d.fromObj(obj);
        System.out.println("HEX clamp " + hex(data));
        // ожидается вершина (32767, -32768, 1)
        boolean ok = data.length >= 4 + 2 + 6
                && get16(data, 6) == 32767 && get16(data, 8) == -32768
                && get16(data, 10) == 1;
        report("clamp", ok, "координаты не округлились/не обрезались");
    }

    // ------------------------------------------------ ошибки

    static void errIdx() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeInt(1);
            d.writeShort(2);
            d.writeShort(0); d.writeShort(0); d.writeShort(0);
            d.writeShort(1); d.writeShort(1); d.writeShort(1);
            d.writeShort(1);
            d.writeShort(0); d.writeShort(1); d.writeShort(5);   // 5 - вне 0..1
            for(int i = 0; i < 6; i++) d.writeByte(0);
            d.writeShort(0);
            d.flush();
            Obj3d.toObj(bos.toByteArray());
            report("err-idx", false, "исключения не было");
            System.out.println("ERR err-idx caught=0");
        } catch (IllegalArgumentException e) {
            report("err-idx", true, "");
            System.out.println("ERR err-idx caught=1");
        } catch (Exception e) {
            report("err-idx", false, "не то исключение: " + e);
            System.out.println("ERR err-idx caught=0");
        }
    }

    static void errEmpty() {
        try {
            Obj3d.fromObj("v 1 2 3\n# нет граней\n");
            report("err-empty", false, "исключения не было");
            System.out.println("ERR err-empty caught=0");
        } catch (IllegalArgumentException e) {
            report("err-empty", true, "");
            System.out.println("ERR err-empty caught=1");
        }
    }

    static void errNegCount() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeInt(1);
            d.writeShort(-5);
            d.flush();
            Obj3d.toObj(bos.toByteArray());
            report("err-neg", false, "исключения не было");
            System.out.println("ERR err-neg caught=0");
        } catch (IllegalArgumentException e) {
            report("err-neg", true, "");
            System.out.println("ERR err-neg caught=1");
        } catch (Exception e) {
            report("err-neg", false, "не то исключение: " + e);
            System.out.println("ERR err-neg caught=0");
        }
    }

    static void errTrunc() {
        try {
            Obj3d.toObj(new byte[]{0, 0, 0, 2});   // meshCount=2, данных нет
            report("err-trunc", false, "исключения не было");
            System.out.println("ERR err-trunc caught=0");
        } catch (IllegalArgumentException e) {
            report("err-trunc", true, "");
            System.out.println("ERR err-trunc caught=1");
        }
    }

    // ------------------------------------------------ хелперы

    static void report(String name, boolean ok, String detail) {
        if(ok) {
            System.out.println("OK " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + " " + detail);
        }
    }

    static boolean equals(byte[] a, byte[] b) {
        if(a.length != b.length) return false;
        for(int i = 0; i < a.length; i++) {
            if(a[i] != b[i]) return false;
        }
        return true;
    }

    static String hex(byte[] a) {
        StringBuffer sb = new StringBuffer();
        for(int i = 0; i < a.length; i++) {
            int v = a[i] & 0xFF;
            if(v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    static int get16(byte[] a, int off) {
        return (short) (((a[off] & 0xFF) << 8) | (a[off + 1] & 0xFF));
    }
}
