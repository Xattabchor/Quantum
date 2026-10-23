import java.util.Random;

import code.utils.QctEncoder;

/**
 * Проверка энкодера QCT: round-trip encode->decode и статистика ошибок.
 * Компилируется против CLDC, выполняется на обычной JRE.
 * Печатает машиночитаемые строки для docs/verify/test_qct.py:
 *   RT <имя> maxerr=<n> alphaok=<0|1> nullok=<0|1>
 *   HEX <имя> <байты в hex>
 */
public class TestQctEncode {

    static int[] src0, src1, src2, src3;

    public static void main(String[] args) {
        src0 = new int[32 * 32];
        src1 = new int[32 * 32];
        src2 = new int[64 * 64];
        src3 = new int[32 * 32];
        int[] pal = new int[]{0xff102030, 0xff406080, 0xffa0b0c0, 0xffe0f0ff};
        for(int y = 0; y < 32; y++) {
            for(int x = 0; x < 32; x++) {
                int b = ((y / 2) * 16 + (x / 2)) & 3;      // ровно 4 цвета на блок
                src0[x + y * 32] = pal[b];
                int dx = x - 16, dy = y - 16;
                boolean inside = dx * dx + dy * dy < 100;
                src1[x + y * 32] = inside ? pal[(x / 2 + y / 2) & 3] : 0x00000000;
                int a = (x * 8 + y * 4) & 0xff;
                src3[x + y * 32] = (a << 24) | pal[(x / 4) & 3];
            }
        }
        for(int y = 0; y < 64; y++) {
            for(int x = 0; x < 64; x++) {
                src2[x + y * 64] = 0xff000000 | ((x * 4) << 16) | ((y * 4) << 8)
                        | (((x + y) * 2) & 0xff);
            }
        }

        check("t0", src0, 32, 32, 4, 0, true);
        check("t1", src1, 32, 32, 4, 0, true);
        check("t2", src2, 64, 64, 64, 1, false);   // палитра 64: ошибка мала
        check("t2b", src2, 64, 64, 16, 1, false);  // палитра 16: стресс, отчёт
        check("t3", src3, 32, 32, 4, 0, true);

        byte[] odd = QctEncoder.encode(src0, 33, 32, 4, 0);
        System.out.println("RT odd maxerr=-1 alphaok=1 nullok="
                + (odd == null ? 1 : 0));
    }

    static void check(String name, int[] src, int w, int h, int cc, int q,
                      boolean expectExact) {
        byte[] qct = QctEncoder.encode(src, w, h, cc, q);
        if(qct == null) {
            System.out.println("RT " + name + " maxerr=-1 alphaok=0 nullok=0");
            return;
        }
        int[] back;
        int maxerr = 0;
        int alphaok = 1;
        try {
            back = QctEncoder.decode(qct);
        } catch (Exception e) {
            System.out.println("RT " + name + " maxerr=-1 alphaok=0 nullok=0");
            return;
        }
        for(int i = 0; i < src.length; i++) {
            int sa = (src[i] >>> 24) & 0xff;
            int ba = (back[i] >>> 24) & 0xff;
            if(sa == 0) {
                if(ba != 0) alphaok = 0;
                continue;
            }
            if(sa != ba) alphaok = 0;
            int d = Math.abs(((src[i] >> 16) & 0xff) - ((back[i] >> 16) & 0xff));
            if(d > maxerr) maxerr = d;
            d = Math.abs(((src[i] >> 8) & 0xff) - ((back[i] >> 8) & 0xff));
            if(d > maxerr) maxerr = d;
            d = Math.abs((src[i] & 0xff) - (back[i] & 0xff));
            if(d > maxerr) maxerr = d;
        }
        System.out.println("RT " + name + " maxerr=" + maxerr + " alphaok="
                + alphaok + " nullok=1 exact=" + (expectExact ? 1 : 0));
        StringBuffer hex = new StringBuffer(qct.length * 2);
        for(int i = 0; i < qct.length; i++) {
            int v = qct[i] & 0xff;
            hex.append("0123456789abcdef".charAt(v >> 4));
            hex.append("0123456789abcdef".charAt(v & 15));
        }
        System.out.println("HEX " + name + " " + hex.toString());
    }
}
