package code.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Hashtable;

/**
 * Энкодер/декодер текстурного формата QCT - того самого, который движок уже
 * умеет читать (RawImage.qctToRawImage), но который раньше нечем было
 * создавать: TextureCompressor.compress() писал другое представление и был
 * закомментирован.
 *
 * Формат (DXT1-подобный, но с ГЛОБАЛЬНОЙ палитрой и блоками 2x2):
 *   'Q','C','T'
 *   u16 headerSize  - размер оставшейся шапки в байтах (7 + 1 при alpha>=1)
 *   u8  version     (1)
 *   u16 w, u16 h    - размеры; оба обязаны быть чётными (блоки 2x2)
 *   u8  ccLog       - цветов палитры = 1 &lt;&lt; (ccLog+1), то есть 2..256
 *   u8  alphaType   - 0: без альфы; 1: бинарная альфа; 2: полутона альфы run'ами
 *   [u8 lastColorReplace] - только при alphaType&gt;=1 (зарезервировано, пишем 0)
 *   палитра: colorCount * (u8 r, u8 g, u8 b), альфа при чтении forcing 0xff
 *   блоки 2x2 (строки сверху вниз): u8 c0id, u8 c3id, u8 ids
 *       c0id &gt; c3id  - режим альфы блока: id2 = полностью прозрачный,
 *                      id1 = смесь c0/c3 50/50;
 *       c0id &lt;= c3id -Opaque-режим: id1 = 2/3 c0 + 1/3 c3, id2 = наоборот;
 *       ids: 2 бита на пиксель, порядок (0,0),(1,0),(0,1),(1,1) - биты 0,2,4,6
 *   [поток альфы при alphaType==2] - пары (u8 skip, u8 run) и run байт альфы,
 *       обходящие непрозрачные пиксели в порядке индексов, как в загрузчике.
 *
 * Итого 3 байта на блок = 0.75 байта на пиксель плюс палитра: примерно в 5 раз
 * компактнее int[] и заметно компактнее PNG-декодирования по памяти на
 * загрузке (не нужен промежуточный Image).
 *
 * Только CLDC 1.1: целочисленная арифметика, никаких float/double, чтобы
 * конвертер можно было гонять прямо на ARM9-аппарате.
 */
public final class QctEncoder {

    private QctEncoder() {
    }

    /** Максимум уникальных цветов, которые участвуют в построении палитры. */
    private static final int MAX_UNIQUE = 2048;

    /**
     * Пакует текстуру в QCT.
     *
     * @param rgb        пиксели 0xAARRGGBB, построчно, длина w*h
     * @param colorCount размер палитры: 2, 4, 8, 16, 32, 64, 128 или 256
     * @param quality    0 - быстрый подбор эндпоинтов, 1 - расширенный перебор
     * @return байты файла QCT или null, если размеры нечётные (&lt;2 либо не
     *         кратны 2): загрузчик движка читает только блоки 2x2 целиком.
     */
    public static byte[] encode(int[] rgb, int w, int h, int colorCount,
                                int quality) {
        if(rgb == null || w < 2 || h < 2 || (w & 1) != 0 || (h & 1) != 0) {
            return null;
        }
        int cc = 4;
        for(int t = 2; t <= 256; t <<= 1) {
            if(t == colorCount) {
                cc = t;
                break;
            }
        }
        int ccLog = 0;
        while((1 << (ccLog + 1)) < cc) ccLog++;

        // --- тип альфы ---
        int alphaType = 0;
        for(int i = 0; i < rgb.length; i++) {
            int a = (rgb[i] >>> 24) & 0xff;
            if(a == 0) {
                if(alphaType < 1) alphaType = 1;
            } else if(a != 255) {
                alphaType = 2;
                break;
            }
        }

        int[] palette = buildPalette(rgb, cc);

        // ближайший индекс палитры для каждого уникального цвета (кэш)
        Hashtable nearest = new Hashtable();

        byte[] blocks = new byte[(w >> 1) * (h >> 1) * 3];
        int bp = 0;
        for(int y = 0; y < h; y += 2) {
            for(int x = 0; x < w; x += 2) {
                int p0 = rgb[x + y * w];
                int p1 = rgb[x + 1 + y * w];
                int p2 = rgb[x + (y + 1) * w];
                int p3 = rgb[x + 1 + (y + 1) * w];
                boolean t0 = ((p0 >>> 24) & 0xff) == 0;
                boolean t1 = ((p1 >>> 24) & 0xff) == 0;
                boolean t2 = ((p2 >>> 24) & 0xff) == 0;
                boolean t3 = ((p3 >>> 24) & 0xff) == 0;

                if(t0 && t1 && t2 && t3) {
                    blocks[bp] = 1; blocks[bp + 1] = 0;
                    blocks[bp + 2] = (byte) 0xAA;      // все четыре - id2
                    bp += 3;
                    continue;
                }

                int[] px = new int[]{p0, p1, p2, p3};
                boolean[] tr = new boolean[]{t0, t1, t2, t3};
                int[] idx = new int[4];
                for(int i = 0; i < 4; i++) {
                    idx[i] = tr[i] ? 0 : nearest(palette, nearest, px[i]);
                }
                // кандидаты в эндпоинты: ближайшие индексы + экстремумы яркости
                int lo = -1, hi = -1, lmin = Integer.MAX_VALUE, lmax = -1;
                for(int i = 0; i < 4; i++) {
                    if(tr[i]) continue;
                    int l = lum(px[i]);
                    if(l < lmin) { lmin = l; lo = idx[i]; }
                    if(l > lmax) { lmax = l; hi = idx[i]; }
                }
                int[] cand = quality > 0
                        ? new int[]{idx[0], idx[1], idx[2], idx[3], lo, hi}
                        : new int[]{idx[0], idx[1], idx[2], idx[3]};
                int cn = 0;
                for(int i = 0; i < cand.length; i++) {          // дедупликация
                    if(cand[i] < 0) continue;
                    boolean dup = false;
                    for(int j = 0; j < cn; j++) if(cand[j] == cand[i]) dup = true;
                    if(!dup) cand[cn++] = cand[i];
                }

                boolean blockAlpha = t0 || t1 || t2 || t3;
                int bestErr = Integer.MAX_VALUE, bestA = 0, bestB = 0;
                int bestIds = 0;
                for(int i = 0; i < cn; i++) {
                    for(int j = 0; j < cn; j++) {
                        int a = cand[i], b = cand[j];
                        if(blockAlpha ? (a <= b) : (a > b)) continue;
                        int[] tmp = blockColors(palette, a, b, blockAlpha);
                        int err = 0, ids = 0;
                        for(int k = 0; k < 4; k++) {
                            int id;
                            if(tr[k]) {
                                id = 2;
                            } else {
                                id = bestId(tmp, px[k], blockAlpha);
                                err += dist2(px[k], tmp[id]);
                            }
                            ids |= id << (k * 2);
                        }
                        if(err < bestErr) {
                            bestErr = err; bestA = a; bestB = b; bestIds = ids;
                        }
                    }
                }
                if(blockAlpha && bestErr == Integer.MAX_VALUE) {
                    // все кандидаты совпали: строим пару a>b любой ценой,
                    // иначе блок не получит режим альфы и прозрачные пиксели
                    // станут видимыми
                    int v = cand[0];
                    bestA = v;
                    bestB = (v + 1) % palette.length;
                    if(bestA <= bestB) {
                        bestA = (v + 1) % palette.length;
                        bestB = v;
                    }
                    if(bestA <= bestB) {
                        bestA = palette.length - 1;
                        bestB = 0;
                    }
                    int[] tmp = blockColors(palette, bestA, bestB, true);
                    bestIds = 0;
                    for(int k = 0; k < 4; k++) {
                        int id = tr[k] ? 2 : bestId(tmp, px[k], true);
                        bestIds |= id << (k * 2);
                    }
                }
                blocks[bp] = (byte) bestA;
                blocks[bp + 1] = (byte) bestB;
                blocks[bp + 2] = (byte) bestIds;
                bp += 3;
            }
        }

        // --- сборка файла ---
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte('Q'); out.writeByte('C'); out.writeByte('T');
            out.writeShort(7 + (alphaType >= 1 ? 1 : 0));
            out.writeByte(1);                       // version
            out.writeShort(w);
            out.writeShort(h);
            out.writeByte(ccLog);
            out.writeByte(alphaType);
            if(alphaType >= 1) out.writeByte(0);    // lastColorReplace
            for(int i = 0; i < palette.length; i++) {
                out.writeByte((palette[i] >> 16) & 0xff);
                out.writeByte((palette[i] >> 8) & 0xff);
                out.writeByte(palette[i] & 0xff);
            }
            out.write(blocks, 0, bp);
            if(alphaType == 2) writeAlphaStream(out, rgb);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Распаковка QCT в int[] - побитово та же логика, что и в
     * RawImage.qctToRawImage (нужна конвертеру для самопроверки и тестам).
     */
    public static int[] decode(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(
                new ByteArrayInputStream(data));
        dis.skip(3);
        int headerSize = dis.readUnsignedShort();
        dis.readUnsignedByte();
        int w = dis.readUnsignedShort();
        int h = dis.readUnsignedShort();
        int colorCount = 1 << (dis.readUnsignedByte() + 1);
        int alphaType = dis.readUnsignedByte();
        headerSize -= 7;
        if(alphaType >= 1) {
            dis.readUnsignedByte();
            headerSize--;
        }
        if(headerSize > 0) dis.skip(headerSize);

        int[] colors = new int[colorCount];
        for(int i = 0; i < colorCount; i++) {
            colors[i] = 0xff000000 | (dis.readUnsignedByte() << 16)
                    | (dis.readUnsignedByte() << 8) | dis.readUnsignedByte();
        }
        int[] img = new int[w * h];
        int[] tmp = new int[4];
        for(int y = 0; y < h / 2; y++) {
            int yp = y * w << 1;
            for(int x = 0; x < w / 2; x++) {
                int xp = yp + (x << 1);
                int c0id = dis.readUnsignedByte();
                int c3id = dis.readUnsignedByte();
                boolean hasAlpha = c0id > c3id;
                tmp[0] = colors[c0id];
                tmp[3] = colors[c3id];
                int rb0 = tmp[0] & 0xff00ff;
                int rb3 = tmp[3] & 0xff00ff;
                int g0 = tmp[0] & 0x00ff00;
                int g3 = tmp[3] & 0x00ff00;
                if(hasAlpha) {
                    tmp[2] = 0;
                    tmp[1] = ((((rb0 + rb3) & 0x1fe01de)
                            | ((g0 + g3) & 0x001fe00)) >> 1) | 0xff000000;
                } else {
                    tmp[1] = ((((rb0 * 171 + rb3 * 85) & 0xff00ff00)
                            | ((g0 * 171 + g3 * 85) & 0x00ff0000)) >> 8)
                            | 0xff000000;
                    tmp[2] = ((((rb0 * 85 + rb3 * 171) & 0xff00ff00)
                            | ((g0 * 85 + g3 * 171) & 0x00ff0000)) >> 8)
                            | 0xff000000;
                }
                int ids = dis.readByte();
                img[xp] = tmp[ids & 3];
                img[xp + 1] = tmp[(ids >>> 2) & 3];
                img[xp + w] = tmp[(ids >>> 4) & 3];
                img[xp + w + 1] = tmp[(ids >>> 6) & 3];
            }
        }
        if(alphaType == 2) {
            for(int i = 0; i < img.length; i++) {
                if((img[i] >>> 24) == 0) continue;
                int solid = dis.readUnsignedByte();
                int transparent = dis.readUnsignedByte();
                i += solid;
                if(transparent == 0) {
                    i--;
                    continue;
                }
                for(; i < img.length; i++) {
                    if((img[i] >>> 24) == 0) continue;
                    img[i] = (dis.readUnsignedByte() << 24)
                            | (img[i] & 0xffffff);
                    if(--transparent == 0) break;
                }
            }
        }
        return img;
    }

    // ------------------------------------------------------------------
    // внутренности
    // ------------------------------------------------------------------

    /** Палитра: уникальные цвета, если влезают, иначе целочисленный k-means. */
    private static int[] buildPalette(int[] rgb, int cc) {
        // Прореживание равномерное по индексу (а не "первые N пикселов"),
        // иначе на градиентах палитра покрывала бы только угол изображения.
        int step = (rgb.length + MAX_UNIQUE - 1) / MAX_UNIQUE;
        if(step < 1) step = 1;
        Hashtable counts = new Hashtable();
        int uniq = 0;
        for(int i = 0; i < rgb.length; i += step) {
            if(((rgb[i] >>> 24) & 0xff) == 0) continue;
            Integer key = new Integer(rgb[i] & 0xffffff);
            Integer c = (Integer) counts.get(key);
            if(c == null) {
                if(uniq >= MAX_UNIQUE) continue;
                uniq++;
                counts.put(key, new Integer(1));
            } else {
                counts.put(key, new Integer(c.intValue() + 1));
            }
        }
        int[] cols = new int[uniq];
        int[] wgt = new int[uniq];
        int n = 0;
        for(int i = 0; i < rgb.length && n < uniq; i += step) {
            if(((rgb[i] >>> 24) & 0xff) == 0) continue;
            int key = rgb[i] & 0xffffff;
            Integer c = (Integer) counts.get(new Integer(key));
            if(c == null) continue;
            counts.remove(new Integer(key));
            cols[n] = key;
            wgt[n] = c.intValue();
            n++;
        }
        if(uniq == 0) return new int[cc];                  // полностью прозрачная
        sortPairs(cols, wgt);
        int[] pal = new int[cc];
        if(uniq <= cc) {
            for(int i = 0; i < cc; i++) pal[i] = cols[Math.min(i, uniq - 1)];
            return pal;
        }
        for(int i = 0; i < cc; i++) {                       // равномерная инициализация
            pal[i] = cols[i * uniq / cc];
        }
        int[] sr = new int[cc], sg = new int[cc], sb = new int[cc], sw = new int[cc];
        int[] own = new int[uniq];
        for(int it = 0; it < 6; it++) {
            for(int i = 0; i < cc; i++) sr[i] = sg[i] = sb[i] = sw[i] = 0;
            for(int i = 0; i < uniq; i++) {
                int b = 0, bd = Integer.MAX_VALUE;
                for(int k = 0; k < cc; k++) {
                    int d = dist2(cols[i] | 0xff000000, pal[k] | 0xff000000);
                    if(d < bd) { bd = d; b = k; }
                }
                own[i] = b;
                sr[b] += ((cols[i] >> 16) & 0xff) * wgt[i];
                sg[b] += ((cols[i] >> 8) & 0xff) * wgt[i];
                sb[b] += (cols[i] & 0xff) * wgt[i];
                sw[b] += wgt[i];
            }
            for(int k = 0; k < cc; k++) {
                if(sw[k] > 0) {
                    pal[k] = ((sr[k] / sw[k]) << 16) | ((sg[k] / sw[k]) << 8)
                            | (sb[k] / sw[k]);
                }
            }
        }
        return pal;
    }

    /** Сортировка цвета вместе с весом (простая вставками: палитра мала). */
    private static void sortPairs(int[] a, int[] w) {
        for(int end = a.length - 1; end > 0; end--) {
            for(int i = 1; i <= end; i++) {
                if(a[i - 1] > a[i]) {
                    int t = a[i - 1]; a[i - 1] = a[i]; a[i] = t;
                    t = w[i - 1]; w[i - 1] = w[i]; w[i] = t;
                }
            }
        }
    }

    private static int nearest(int[] palette, Hashtable cache, int color) {
        Integer key = new Integer(color & 0xffffff);
        Integer hit = (Integer) cache.get(key);
        if(hit != null) return hit.intValue();
        int best = 0, bd = Integer.MAX_VALUE;
        int c = color | 0xff000000;
        for(int i = 0; i < palette.length; i++) {
            int d = dist2(c, palette[i] | 0xff000000);
            if(d < bd) { bd = d; best = i; }
        }
        cache.put(key, new Integer(best));
        return best;
    }

    /** Временные цвета блока - формулы загрузчика, чтобы оценка была честной. */
    private static int[] blockColors(int[] palette, int a, int b,
                                      boolean alpha) {
        int[] tmp = new int[4];
        tmp[0] = palette[a];
        tmp[3] = palette[b];
        int rb0 = tmp[0] & 0xff00ff, rb3 = tmp[3] & 0xff00ff;
        int g0 = tmp[0] & 0x00ff00, g3 = tmp[3] & 0x00ff00;
        if(alpha) {
            tmp[2] = 0;
            tmp[1] = ((((rb0 + rb3) & 0x1fe01de)
                    | ((g0 + g3) & 0x001fe00)) >> 1) | 0xff000000;
        } else {
            tmp[1] = ((((rb0 * 171 + rb3 * 85) & 0xff00ff00)
                    | ((g0 * 171 + g3 * 85) & 0x00ff0000)) >> 8) | 0xff000000;
            tmp[2] = ((((rb0 * 85 + rb3 * 171) & 0xff00ff00)
                    | ((g0 * 85 + g3 * 171) & 0x00ff0000)) >> 8) | 0xff000000;
        }
        return tmp;
    }

    private static int bestId(int[] tmp, int px, boolean alpha) {
        int best = 0, bd = Integer.MAX_VALUE;
        for(int id = 0; id < 4; id++) {
            if(alpha && id == 2) continue;
            int d = dist2(px, tmp[id]);
            if(d < bd) { bd = d; best = id; }
        }
        return best;
    }

    private static int dist2(int a, int b) {
        int r = ((a >> 16) & 0xff) - ((b >> 16) & 0xff);
        int g = ((a >> 8) & 0xff) - ((b >> 8) & 0xff);
        int bl = (a & 0xff) - (b & 0xff);
        return r * r + g * g + bl * bl;
    }

    private static int lum(int c) {
        return (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150
                + (c & 0xff) * 29) >> 8;
    }

    /**
     * Поток полутонов альфы: машина индексов повторена с загрузчика один в
     * один, поэтому пары (skip, run) всегда согласованы с чтением.
     */
    private static void writeAlphaStream(DataOutputStream out, int[] rgb)
            throws IOException {
        int len = rgb.length;
        int i = 0;
        while(i < len) {
            if(((rgb[i] >>> 24) & 0xff) == 0) {
                i++;
                continue;
            }
            int a = (rgb[i] >>> 24) & 0xff;
            if(a == 255) {
                // скольким сплошным пикселам пройти до первого полупрозрачного
                int s = 0;
                int k = i;
                while(s < 255 && k < len) {
                    int ka = (rgb[k] >>> 24) & 0xff;
                    if(ka == 0) { k++; s++; continue; }   // прозрачные проскакивают бесплатно
                    if(ka != 255) break;
                    s++;
                    k++;
                }
                // k указывает на полупрозрачный непрозрачный пиксель или за конец
                if(k < len && ((rgb[k] >>> 24) & 0xff) != 0
                        && ((rgb[k] >>> 24) & 0xff) != 255) {
                    int t = 0;
                    int q = k;
                    while(t < 255 && q < len) {
                        int qa = (rgb[q] >>> 24) & 0xff;
                        if(qa == 0) { q++; continue; }
                        if(qa == 255) break;
                        t++;
                        q++;
                    }
                    out.writeByte(s);
                    out.writeByte(t);
                    for(int z = k; z < q; z++) {
                        if(((rgb[z] >>> 24) & 0xff) == 0) continue;
                        out.writeByte((rgb[z] >>> 24) & 0xff);
                    }
                    i = q;
                } else {
                    out.writeByte(s);
                    out.writeByte(0);
                    i = i + s;
                }
            } else {
                int t = 0;
                int q = i;
                while(t < 255 && q < len) {
                    int qa = (rgb[q] >>> 24) & 0xff;
                    if(qa == 0) { q++; continue; }
                    if(qa == 255) break;
                    t++;
                    q++;
                }
                out.writeByte(0);
                out.writeByte(t);
                for(int z = i; z < q; z++) {
                    if(((rgb[z] >>> 24) & 0xff) == 0) continue;
                    out.writeByte((rgb[z] >>> 24) & 0xff);
                }
                i = q;
            }
        }
    }
}
