package tools.vlaconv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Кодек lightmap.vla: конвертация между DMODE=13 (цвет, ColorLightedPolygon)
 * и DMODE=9 (серый, LightedPolygon) на оригинальном формате (сохранён).
 *
 * Формат vla - сырой поток без заголовков, порядок полигонов = порядок
 * в Mesh[] как в HouseCreator/Room.createFrom3d: для каждой меши сначала
 * все 3V-полигоны, затем все 4V. Тип 3V/4V и их количество берётся из
 * соответствующего .3d (int meshCount, per mesh: short vCount + vCount*6,
 * short p3Count + p3Count записей, short p4Count + p4Count записей).
 *
 * 9 (серый):  3V: 3 байта la,lb,lc (каждый как (byte)(v-128)), 4V: 4 байта.
 * 13 (цвет):  3V: 3 байта ar,br,cr + boolean colored + если true 6 байт ag,ab,bg,bb,cg,cb
 *             4V: 4 байта ar,br,cr,dr + boolean + если true 8 байт
 *   Серый в модели 13 сжимается: если ar==ag==ab && ... то colored==false
 *   и пишутся только 3/4 байта. Конвертер учитывает это.
 *
 * Серый = (R+G+B)/3 - как в конструкторе LightedPolygon(ColorLightedPolygon)
 * и в TexturingPers при fogQ==1. При обратном 9->13 пишем сжатый цветной
 * с r==g==b==gray (colored==false), что рендерится идентично.
 *
 * Только byte[]/DataInputStream - без MIDP, годен и на аппарате и на JRE-тестах.
 */
public final class VlaCodec {

    private VlaCodec() {}

    private static IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m);
    }

    // --- парсинг .3d: количества 3V/4V на мешь ---

    /**
     * @param meshBytes содержимое .3d как в Room.loadMeshes (int meshCount big-endian)
     * @return int[meshCount][2] где [i][0]=p3Count, [i][1]=p4Count
     */
    public static int[][] parseMeshCounts(byte[] meshBytes) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(meshBytes));
        try {
            int meshCount = in.readInt();
            if (meshCount < 1 || meshCount > 4096) throw err("meshCount " + meshCount + " вне 1..4096");
            int[][] out = new int[meshCount][2];
            for (int m = 0; m < meshCount; m++) {
                int vCount = in.readShort();
                if (vCount < 0) throw err("mesh" + m + " vCount<0");
                // skip vertices
                for (int i = 0; i < vCount; i++) {
                    in.readShort(); in.readShort(); in.readShort();
                }
                int p3 = in.readShort();
                if (p3 < 0) throw err("mesh" + m + " p3Count<0");
                for (int i = 0; i < p3; i++) {
                    int va = in.readShort();
                    if (va == -32768) { // маркер материала
                        in.readShort(); // mat
                        va = in.readShort();
                    }
                    in.readShort(); in.readShort(); // vb, vc
                    for (int k = 0; k < 6; k++) in.readByte(); // uv 6
                }
                int p4 = in.readShort();
                if (p4 < 0) throw err("mesh" + m + " p4Count<0");
                for (int i = 0; i < p4; i++) {
                    int va = in.readShort();
                    if (va == -32768) {
                        in.readShort();
                        va = in.readShort();
                    }
                    in.readShort(); in.readShort(); in.readShort(); // vb,vc,vd
                    for (int k = 0; k < 8; k++) in.readByte(); // uv 8
                }
                out[m][0] = p3;
                out[m][1] = p4;
            }
            return out;
        } catch (IOException e) {
            throw err("обрыв .3d: " + e);
        }
    }

    // --- 13 (цвет) -> 9 (серый) ---

    public static byte[] colorToGray(byte[] colorVla, byte[] meshBytes) {
        int[][] counts = parseMeshCounts(meshBytes);
        return colorToGray(colorVla, counts);
    }

    public static byte[] colorToGray(byte[] colorVla, int[][] counts) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(colorVla));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            for (int m = 0; m < counts.length; m++) {
                int p3 = counts[m][0];
                for (int i = 0; i < p3; i++) {
                    int ar = in.readByte() + 128;
                    int br = in.readByte() + 128;
                    int cr = in.readByte() + 128;
                    boolean colored = in.readBoolean();
                    int ag, ab, bg, bb, cg, cb;
                    if (colored) {
                        ag = in.readByte() + 128;
                        ab = in.readByte() + 128;
                        bg = in.readByte() + 128;
                        bb = in.readByte() + 128;
                        cg = in.readByte() + 128;
                        cb = in.readByte() + 128;
                    } else {
                        ag = ab = ar;
                        bg = bb = br;
                        cg = cb = cr;
                    }
                    int la = (ar + ag + ab) / 3;
                    int lb = (br + bg + bb) / 3;
                    int lc = (cr + cg + cb) / 3;
                    out.writeByte(la - 128);
                    out.writeByte(lb - 128);
                    out.writeByte(lc - 128);
                }
                int p4 = counts[m][1];
                for (int i = 0; i < p4; i++) {
                    int ar = in.readByte() + 128;
                    int br = in.readByte() + 128;
                    int cr = in.readByte() + 128;
                    int dr = in.readByte() + 128;
                    boolean colored = in.readBoolean();
                    int ag, ab, bg, bb, cg, cb, dg, db;
                    if (colored) {
                        ag = in.readByte() + 128;
                        ab = in.readByte() + 128;
                        bg = in.readByte() + 128;
                        bb = in.readByte() + 128;
                        cg = in.readByte() + 128;
                        cb = in.readByte() + 128;
                        dg = in.readByte() + 128;
                        db = in.readByte() + 128;
                    } else {
                        ag = ab = ar;
                        bg = bb = br;
                        cg = cb = cr;
                        dg = db = dr;
                    }
                    int la = (ar + ag + ab) / 3;
                    int lb = (br + bg + bb) / 3;
                    int lc = (cr + cg + cb) / 3;
                    int ld = (dr + dg + db) / 3;
                    out.writeByte(la - 128);
                    out.writeByte(lb - 128);
                    out.writeByte(lc - 128);
                    out.writeByte(ld - 128);
                }
            }
            if (in.available() > 0) throw err("лишние " + in.available() + " байт в конце цветного vla");
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw err("обрыв цветного vla (mesh " + counts.length + "): " + e);
        }
    }

    // --- 9 (серый) -> 13 (цвет, сжатый: r==g==b) ---

    public static byte[] grayToColor(byte[] grayVla, byte[] meshBytes) {
        int[][] counts = parseMeshCounts(meshBytes);
        return grayToColor(grayVla, counts);
    }

    public static byte[] grayToColor(byte[] grayVla, int[][] counts) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(grayVla));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            for (int m = 0; m < counts.length; m++) {
                int p3 = counts[m][0];
                for (int i = 0; i < p3; i++) {
                    int la = in.readByte() + 128;
                    int lb = in.readByte() + 128;
                    int lc = in.readByte() + 128;
                    // пишем как несжатый серый в цветном формате: r==g==b => colored==false
                    out.writeByte(la - 128);
                    out.writeByte(lb - 128);
                    out.writeByte(lc - 128);
                    out.writeBoolean(false);
                }
                int p4 = counts[m][1];
                for (int i = 0; i < p4; i++) {
                    int la = in.readByte() + 128;
                    int lb = in.readByte() + 128;
                    int lc = in.readByte() + 128;
                    int ld = in.readByte() + 128;
                    out.writeByte(la - 128);
                    out.writeByte(lb - 128);
                    out.writeByte(lc - 128);
                    out.writeByte(ld - 128);
                    out.writeBoolean(false);
                }
            }
            if (in.available() > 0) throw err("лишние " + in.available() + " байт в конце серого vla");
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw err("обрыв серого vla: " + e);
        }
    }

    // --- хелпер для тестов: собрать синтетический .3d ---
    public static byte[] buildMeshBytes(int[][] counts) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(counts.length);
            for (int m = 0; m < counts.length; m++) {
                int p3 = counts[m][0], p4 = counts[m][1];
                // 3 вершины-заглушки чтобы .3d валиден
                out.writeShort(3);
                out.writeShort(0); out.writeShort(0); out.writeShort(0);
                out.writeShort(100); out.writeShort(0); out.writeShort(0);
                out.writeShort(0); out.writeShort(100); out.writeShort(0);
                out.writeShort(p3);
                for (int i = 0; i < p3; i++) {
                    out.writeShort(0); out.writeShort(1); out.writeShort(2);
                    for (int k = 0; k < 6; k++) out.writeByte(0);
                }
                out.writeShort(p4);
                for (int i = 0; i < p4; i++) {
                    // для 4V нужно 4 вершины - добавим четвёртую в мешь с p4>0
                    // но для теста достаточно и 3 вершин с повторным индексом 0
                    out.writeShort(0); out.writeShort(1); out.writeShort(2); out.writeShort(0);
                    for (int k = 0; k < 8; k++) out.writeByte(0);
                }
            }
            out.flush();
            // патч: если есть p4, вершинов должно быть 4 - перепишем через counts
            // проще пересобрать с 4 вершинами для мешей с p4>0
            boolean need4 = false;
            for (int i = 0; i < counts.length; i++) if (counts[i][1] > 0) need4 = true;
            if (need4) {
                // не критично для парсера counts - он не валидирует индексы
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw err("buildMeshBytes: " + e);
        }
    }
}
