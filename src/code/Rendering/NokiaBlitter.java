package code.Rendering;

import com.nokia.mid.ui.DirectGraphics;
import com.nokia.mid.ui.DirectUtils;
import javax.microedition.lcdui.Graphics;

/**
 * Blitter кадра через Nokia DirectGraphics (§24). На аппаратах с
 * com.nokia.mid.ui (Nokia S60/S40, вкл. E65) drawPixels() выводит кадр
 * нативным путём - как правило быстрее g.drawRGB(int[]).
 *
 * Класс загружается ТОЛЬКО после успешного
 * Class.forName("com.nokia.mid.ui.DirectGraphics") в DirectX7.nokia():
 * на аппаратах без Nokia UI он не загружается вовсе, и flush() остаётся на
 * прежнем drawRGB (NoClassDefFoundError исключён). NOKIA_BLIT=0 отключает
 * путь даже на Nokia.
 *
 * Семантика drawPixels совпадает с drawRGB: адресация
 * pixels[offset + (x1-x) + (y1-y)*scanlength], transparency=false
 * игнорирует альфу (непрозрачный blit, аналог processAlpha=false),
 * формат TYPE_INT_8888_ARGB (=8888) - те же 0xAARRGGBB int, что в display.
 * manipulation=0 - без поворотов/отражений (константы FLIP_NONE в API нет).
 */
public final class NokiaBlitter {

    private final DirectGraphics dg;

    private NokiaBlitter(Graphics g) {
        dg = DirectUtils.getDirectGraphics(g);
    }

    /**
     * Blitter для данного Graphics; null, если API не инициализировался
     * (вызывающий откатывается на g.drawRGB).
     */
    static NokiaBlitter create(Graphics g) {
        try {
            return new NokiaBlitter(g);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Эквивалент g.drawRGB(pixels, 0, scanlength, x, y, w, h, alpha). */
    final void blit(int[] pixels, int scanlength, int x, int y, int w, int h, boolean alpha) {
        dg.drawPixels(pixels, alpha, 0, scanlength, x, y, w, h, 0, DirectGraphics.TYPE_INT_8888_ARGB);
    }
}
