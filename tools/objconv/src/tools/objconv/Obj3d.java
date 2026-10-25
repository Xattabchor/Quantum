package tools.objconv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Кодек бинарного формата мешей Quantum ("3d", читается движком в
 * Room.loadMeshes/createFrom3d) &lt;-&gt; Wavefront OBJ. Чистая логика:
 * только byte[]/String, без MIDP - годится и для аппарата, и для JRE-тестов.
 *
 * Формат "3d" (big-endian), по нему же см. docs/ARM9.md §26:
 *   int32  meshCount                 (несколько мешей = кадры Morphing)
 *   меш:
 *     int16 vertexCount
 *       vertexCount × (int16 x, int16 y, int16 z)
 *     int16 poly3Count
 *       poly3Count ×: [маркер: int16 -32768, int16 mat,]
 *                     int16 va, vb, vc, byte au,av,bu,bv,cu,cv
 *     int16 poly4Count
 *       poly4Count ×: [маркер...] int16 va,vb,vc,vd,
 *                     byte au,av,bu,bv,cu,cv,du,dv
 *   Маркер материала стоит ПЕРЕД полигоном, у которого сменился mat;
 *   mat действует до следующего маркера (движок кладёт его в polygon.tex).
 *
 * OBJ-соглашения конвертера (стандартный OBJ, совместим с Blender):
 *   - каждый меш - группа "o meshN"; вершины "v x y z" (целые, как в файле);
 *   - UV движка ПОУГЛОВЫЕ (byte 0..255 на угол полигона), поэтому каждый
 *     угол получает собственную "vt u v" (0..1, 5 знаков - байт恢复 точный);
 *   - "f a/ta b/tb c/tc" - порядок вершин КАК В ФАЙЛЕ (движок сам
 *     переворачивает winding при загрузке);
 *   - материал: "usemtl matN" (импорт понимает любые имена: берутся первые
 *     цифры, нет цифр - 0);
 *   - импорт: несколько мешей = несколько "o"/"g" с гранями между ними;
 *     вершины меша = уникальные использованные его гранями в порядке
 *     первого обращения; грани &gt;4 углов - веерная триангуляция;
 *     отрицательные индексы (Blender) поддерживаются; vn игнорируются
 *     (движок считает нормали сам - Morphing.createNormals).
 *
 * Ограничения формата: координаты и счётчики - int16 (вершины/полигоны
 * меша ≤ 32767, координаты округляются и обрезаются в ±32767).
 */
public final class Obj3d {

    /** Маркер смены материала в формате 3d (int16 перед va). */
    public static final int MAT_MARKER = -32768;

    private Obj3d() {
    }

    private static IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg);
    }

    // ------------------------------------------------------------ 3D -> OBJ

    public static String toObj(byte[] data) {
        return toObj(data, null);
    }

    /**
     * 3D-файл -> текст OBJ.
     *
     * @param stats необязательный int[3]: [meshCount, вершин всего, граней всего]
     */
    public static String toObj(byte[] data, int[] stats) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        StringBuffer sb = new StringBuffer();
        try {
            int meshCount = in.readInt();
            if(meshCount < 1 || meshCount > 4096) {
                throw err("mesh count " + meshCount + " вне диапазона 1..4096");
            }
            sb.append("# Quantum 3D mesh, converted by ObjConverter\n");
            sb.append("# meshes: ").append(meshCount).append('\n');
            sb.append("# faces are in file order (engine reverses winding)\n");
            int gvt = 0;          // глобальный счётчик vt (OBJ 1-based)
            int gBase = 0;        // глобальный индекс первой вершины меша
            int totalV = 0, totalF = 0;
            for(int m = 0; m < meshCount; m++) {
                sb.append("o mesh").append(m).append('\n');
                int vCount = in.readShort();
                if(vCount < 0) throw err("mesh" + m + ": отрицательное число вершин");
                for(int i = 0; i < vCount; i++) {
                    sb.append("v ").append(in.readShort()).append(' ')
                      .append(in.readShort()).append(' ')
                      .append(in.readShort()).append('\n');
                }
                totalV += vCount;

                // движок сбрасывает mat=0 в начале КАЖДОГО блока (3V и 4V),
                // поэтому usemtl печатается и у первой грани блока (включая mat0)
                int lastMat = 0;
                int em3 = -1;
                int p3 = in.readShort();
                if(p3 < 0) throw err("mesh" + m + ": отрицательное число 3V");
                for(int i = 0; i < p3; i++) {
                    int mat = -1;
                    int va = in.readShort();
                    if(va == MAT_MARKER) {
                        mat = in.readShort();
                        va = in.readShort();
                    }
                    int vb = in.readShort();
                    int vc = in.readShort();
                    checkIdx("mesh" + m + " 3V#" + i, va, vCount);
                    checkIdx("mesh" + m + " 3V#" + i, vb, vCount);
                    checkIdx("mesh" + m + " 3V#" + i, vc, vCount);
                    int au = in.readByte() & 0xFF, av = in.readByte() & 0xFF;
                    int bu = in.readByte() & 0xFF, bv = in.readByte() & 0xFF;
                    int cu = in.readByte() & 0xFF, cv = in.readByte() & 0xFF;
                    int curEff = mat >= 0 ? mat : lastMat;
                    if(em3 != curEff) {
                        sb.append("usemtl mat").append(curEff).append('\n');
                        em3 = curEff;
                    }
                    lastMat = curEff;
                    sb.append("vt ").append(frac(au)).append(' ').append(frac(av)).append('\n');
                    sb.append("vt ").append(frac(bu)).append(' ').append(frac(bv)).append('\n');
                    sb.append("vt ").append(frac(cu)).append(' ').append(frac(cv)).append('\n');
                    gvt += 3;
                    sb.append("f ").append(gBase + va + 1).append('/').append(gvt - 2)
                      .append(' ').append(gBase + vb + 1).append('/').append(gvt - 1)
                      .append(' ').append(gBase + vc + 1).append('/').append(gvt)
                      .append('\n');
                    totalF++;
                }

                lastMat = 0;   // движок: mat = 0 перед 4V-блоком
                int em4 = -1;
                int p4 = in.readShort();
                if(p4 < 0) throw err("mesh" + m + ": отрицательное число 4V");
                for(int i = 0; i < p4; i++) {
                    int mat = -1;
                    int va = in.readShort();
                    if(va == MAT_MARKER) {
                        mat = in.readShort();
                        va = in.readShort();
                    }
                    int vb = in.readShort();
                    int vc = in.readShort();
                    int vd = in.readShort();
                    checkIdx("mesh" + m + " 4V#" + i, va, vCount);
                    checkIdx("mesh" + m + " 4V#" + i, vb, vCount);
                    checkIdx("mesh" + m + " 4V#" + i, vc, vCount);
                    checkIdx("mesh" + m + " 4V#" + i, vd, vCount);
                    int au = in.readByte() & 0xFF, av = in.readByte() & 0xFF;
                    int bu = in.readByte() & 0xFF, bv = in.readByte() & 0xFF;
                    int cu = in.readByte() & 0xFF, cv = in.readByte() & 0xFF;
                    int du = in.readByte() & 0xFF, dv = in.readByte() & 0xFF;
                    int curEff = mat >= 0 ? mat : lastMat;
                    if(em4 != curEff) {
                        sb.append("usemtl mat").append(curEff).append('\n');
                        em4 = curEff;
                    }
                    lastMat = curEff;
                    sb.append("vt ").append(frac(au)).append(' ').append(frac(av)).append('\n');
                    sb.append("vt ").append(frac(bu)).append(' ').append(frac(bv)).append('\n');
                    sb.append("vt ").append(frac(cu)).append(' ').append(frac(cv)).append('\n');
                    sb.append("vt ").append(frac(du)).append(' ').append(frac(dv)).append('\n');
                    gvt += 4;
                    sb.append("f ").append(gBase + va + 1).append('/').append(gvt - 3)
                      .append(' ').append(gBase + vb + 1).append('/').append(gvt - 2)
                      .append(' ').append(gBase + vc + 1).append('/').append(gvt - 1)
                      .append(' ').append(gBase + vd + 1).append('/').append(gvt)
                      .append('\n');
                    totalF++;
                }
                gBase += vCount;
            }
            if(stats != null && stats.length >= 3) {
                stats[0] = meshCount;
                stats[1] = totalV;
                stats[2] = totalF;
            }
            return sb.toString();
        } catch (IOException e) {
            throw err("3D-данные обрываются: " + e);
        }
    }

    private static void checkIdx(String where, int idx, int vCount) {
        if(idx < 0 || idx >= vCount) {
            throw err(where + ": индекс вершины " + idx + " вне 0.." + (vCount - 1));
        }
    }

    /** byte 0..255 -> "0.xxxxx"/"1.0": round(значение*255) возвращает тот же байт. */
    public static String frac(int b) {
        if(b <= 0) return "0.0";
        if(b >= 255) return "1.0";
        int n = (b * 100000 + 127) / 255;
        String s = Integer.toString(n);
        while(s.length() < 5) s = "0" + s;
        return "0." + s;
    }

    // ------------------------------------------------------------ OBJ -> 3D

    /** Грань: локальные индексы вершин + побайтовые UV углов. */
    private static final class Face {
        int mat;
        int[] lv;     // локальные индексы вершин (порядок как в "f")
        byte[] uv;    // 2*lv.length байт (u,v на угол)
    }

    private static final class MeshAcc {
        final Hashtable map = new Hashtable();   // Integer globalV -> Integer local
        final Vector verts = new Vector();       // int[3]
        final Vector f3 = new Vector();          // Face
        final Vector f4 = new Vector();          // Face
        boolean hasFaces() {
            return !f3.isEmpty() || !f4.isEmpty();
        }
    }

    public static byte[] fromObj(String obj) {
        return fromObj(obj, null);
    }

    /**
     * Текст OBJ -> байты формата 3d.
     *
     * @param stats необязательный int[3]: [meshCount, вершин всего, граней всего]
     */
    public static byte[] fromObj(String obj, int[] stats) {
        Vector vx = new Vector();     // int[3] - глобальные вершины
        Vector vt = new Vector();     // byte[2] - глобальные UV
        Vector meshes = new Vector(); // MeshAcc
        MeshAcc cur = null;
        int curMat = 0;
        int lineNo = 0;
        int pos = 0;
        int len = obj.length();
        while(pos <= len) {
            int nl = obj.indexOf('\n', pos);
            String line = nl < 0 ? obj.substring(pos) : obj.substring(pos, nl);
            pos = nl < 0 ? len + 1 : nl + 1;
            lineNo++;
            String[] t = tokens(line);
            if(t.length == 0 || t[0].charAt(0) == '#') continue;
            String cmd = t[0].toLowerCase();
            try {
                if(cmd.equals("v")) {
                    if(t.length < 4) throw err("строка " + lineNo + ": v требует x y z");
                    vx.addElement(new int[]{
                            clamp16(parseInt16(parseFloat(t[1], lineNo))),
                            clamp16(parseInt16(parseFloat(t[2], lineNo))),
                            clamp16(parseInt16(parseFloat(t[3], lineNo)))});
                } else if(cmd.equals("vt")) {
                    if(t.length < 2) throw err("строка " + lineNo + ": vt требует u");
                    byte u = uvByte(parseFloat(t[1], lineNo));
                    byte v = t.length >= 3 ? uvByte(parseFloat(t[2], lineNo)) : (byte) 0;
                    vt.addElement(new byte[]{u, v});
                } else if(cmd.equals("o") || cmd.equals("g")) {
                    if(cur == null || cur.hasFaces()) {
                        cur = new MeshAcc();
                        meshes.addElement(cur);
                    }
                } else if(cmd.equals("usemtl")) {
                    curMat = parseMat(t.length >= 2 ? t[1] : "", lineNo);
                } else if(cmd.equals("f")) {
                    if(t.length < 4) throw err("строка " + lineNo + ": f требует >= 3 углов");
                    if(cur == null) {
                        cur = new MeshAcc();
                        meshes.addElement(cur);
                    }
                    addFace(cur, t, vx, vt, curMat, lineNo);
                }
                // vn/vp/s/mtllib/... - игнорируются (движок считает нормали сам)
            } catch (NumberFormatException e) {
                throw err("строка " + lineNo + ": " + e);
            }
        }
        // меши без граней (пустые o/g-группы) отбрасываем
        Vector keep = new Vector();
        for(int i = 0; i < meshes.size(); i++) {
            MeshAcc ma = (MeshAcc) meshes.elementAt(i);
            if(ma.hasFaces()) keep.addElement(ma);
        }
        if(keep.isEmpty()) throw err("в OBJ нет ни одной грани (f)");

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(keep.size());
            int totalV = 0, totalF = 0;
            for(int m = 0; m < keep.size(); m++) {
                MeshAcc ma = (MeshAcc) keep.elementAt(m);
                if(ma.verts.size() > 32767) {
                    throw err("mesh" + m + ": " + ma.verts.size() + " вершин > 32767");
                }
                out.writeShort(ma.verts.size());
                for(int i = 0; i < ma.verts.size(); i++) {
                    int[] v = (int[]) ma.verts.elementAt(i);
                    out.writeShort(v[0]);
                    out.writeShort(v[1]);
                    out.writeShort(v[2]);
                }
                totalV += ma.verts.size();
                totalF += writeFaces(out, ma.f3);
                totalF += writeFaces(out, ma.f4);
            }
            out.flush();
            if(stats != null && stats.length >= 3) {
                stats[0] = keep.size();
                stats[1] = totalV;
                stats[2] = totalF;
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw err("сборка 3D не удалась: " + e);
        }
    }

    private static int writeFaces(DataOutputStream out, Vector faces) throws IOException {
        if(faces.size() > 32767) throw err(faces.size() + " полигонов > 32767");
        out.writeShort(faces.size());
        int lastMat = 0;
        for(int i = 0; i < faces.size(); i++) {
            Face f = (Face) faces.elementAt(i);
            if(f.mat != lastMat) {
                out.writeShort(MAT_MARKER);
                out.writeShort(f.mat);
                lastMat = f.mat;
            }
            for(int k = 0; k < f.lv.length; k++) out.writeShort(f.lv[k]);
            for(int k = 0; k < f.uv.length; k++) out.writeByte(f.uv[k]);
        }
        return faces.size();
    }

    private static void addFace(MeshAcc ma, String[] t, Vector vx, Vector vt,
            int mat, int lineNo) {
        int n = t.length - 1;
        int[] gv = new int[n];
        int[] tv = new int[n];
        for(int i = 0; i < n; i++) {
            String tok = t[i + 1];
            int slash = tok.indexOf('/');
            String vs = slash < 0 ? tok : tok.substring(0, slash);
            String ts = "";
            if(slash >= 0) {
                int slash2 = tok.indexOf('/', slash + 1);
                ts = slash2 < 0 ? tok.substring(slash + 1)
                                : tok.substring(slash + 1, slash2);
            }
            gv[i] = resolveIdx(parseInt(vs, lineNo), vx.size(), "вершина", lineNo);
            tv[i] = ts.length() == 0 ? -1
                    : resolveIdx(parseInt(ts, lineNo), vt.size(), "vt", lineNo);
        }
        if(n == 3) {
            ma.f3.addElement(buildFace(ma, gv, tv, mat, vx, vt));
        } else if(n == 4) {
            ma.f4.addElement(buildFace(ma, gv, tv, mat, vx, vt));
        } else {
            // веерная триангуляция: (0,1,2), (0,2,3), ...
            for(int k = 1; k + 1 < n; k++) {
                int[] g3 = new int[]{gv[0], gv[k], gv[k + 1]};
                int[] t3 = new int[]{tv[0], tv[k], tv[k + 1]};
                ma.f3.addElement(buildFace(ma, g3, t3, mat, vx, vt));
            }
        }
    }

    private static Face buildFace(MeshAcc ma, int[] gv, int[] tv, int mat,
            Vector vx, Vector vt) {
        Face f = new Face();
        f.mat = mat;
        f.lv = new int[gv.length];
        f.uv = new byte[gv.length * 2];
        for(int i = 0; i < gv.length; i++) {
            Integer key = new Integer(gv[i]);
            Integer local = (Integer) ma.map.get(key);
            if(local == null) {
                local = new Integer(ma.verts.size());
                ma.map.put(key, local);
                ma.verts.addElement(vx.elementAt(gv[i] - 1));
            }
            f.lv[i] = local.intValue();
            if(tv[i] >= 0) {
                byte[] uv = (byte[]) vt.elementAt(tv[i] - 1);
                f.uv[i * 2] = uv[0];
                f.uv[i * 2 + 1] = uv[1];
            }
        }
        return f;
    }

    private static int resolveIdx(int idx, int size, String what, int lineNo) {
        if(idx == 0) throw err("строка " + lineNo + ": индекс " + what + " = 0");
        int r = idx < 0 ? size + idx + 1 : idx;   // отрицательные = от конца
        if(r < 1 || r > size) {
            throw err("строка " + lineNo + ": индекс " + what + " " + idx
                    + " вне 1.." + size);
        }
        return r;
    }

    /** "mat12"/"12"/"01_-_Default" -> 12/12/1; нет цифр -> 0. */
    static int parseMat(String name, int lineNo) {
        int i = 0;
        while(i < name.length() && !isDigit(name.charAt(i))) i++;
        if(i == name.length()) return 0;
        int j = i;
        while(j < name.length() && isDigit(name.charAt(j))) j++;
        int v = parseInt(name.substring(i, j), lineNo);
        return clamp16(v);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static float parseFloat(String s, int lineNo) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            throw err("строка " + lineNo + ": не число: \"" + s + "\"");
        }
    }

    private static int parseInt(String s, int lineNo) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw err("строка " + lineNo + ": не целое: \"" + s + "\"");
        }
    }

    private static int parseInt16(float f) {
        // round к ближайшему целому (как (int)(x+0.5) для положительных)
        return f >= 0 ? (int) (f + 0.5f) : (int) (f - 0.5f);
    }

    private static int clamp16(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    private static byte uvByte(float f) {
        if(f != f) f = 0;                    // NaN
        if(f < 0) f = 0;
        if(f > 1) f = 1;
        return (byte) (int) (f * 255.0f + 0.5f);
    }

    private static String[] tokens(String line) {
        Vector v = new Vector();
        int i = 0, n = line.length();
        while(i < n) {
            char c = line.charAt(i);
            if(c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            int j = i;
            while(j < n) {
                char d = line.charAt(j);
                if(d == ' ' || d == '\t' || d == '\r' || d == '\f') break;
                j++;
            }
            v.addElement(line.substring(i, j));
            i = j;
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
