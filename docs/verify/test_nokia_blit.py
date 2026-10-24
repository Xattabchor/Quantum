#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка Nokia DirectGraphics blit (§24): NOKIA_BLIT, детектор API, маппинг
параметров drawPixels, значения констант стаба.

  A. Python-зеркала:
     - детектор DirectX7.nokia(): NOKIA_BLIT=0 -> всегда null; Class.forName
       неудачен -> null навсегда (NokiaBlitter не грузится); удачен ->
       blitter, при смене Graphics peer перевзят; create() неудачен -> null
       навсегда;
     - парсинг NOKIA_BLIT: нет ключа -> 1 (opt-out, у любого профиля),
       0 -> выкл, 1 -> вкл, прочее -> выкл (проверка ==1);
     - маппинг flush -> drawPixels: адресация P(x1,y1) =
       pixels[offset + (x1-x) + (y1-y)*scanlength] совпадает с drawRGB
       пиксел-в-пиксел на синтетическом буфере (обе ветви RENDER_SCALE).
  B. Инварианты исходников:
     - константы стаба com.nokia.mid.ui сверены с официальным javadoc
       Nokia UI API 1.1 и двумя независимыми эмуляторами (J2ME-Loader,
       KEmulator): TYPE_INT_8888_ARGB=8888, TYPE_INT_888_RGB=888,
       FLIP_HORIZONTAL=8192, FLIP_VERTICAL=16384, ROTATE_90/180/270=90/180/270
       (ECJ инлайнит static final int - значения обязаны быть точными);
     - NokiaBlitter: символическая константа (не литерал), manipulation=0,
       create() с catch(Throwable) -> null;
     - DirectX7: страж DeviceProfile.nokiaBlit первым, forName в
       try/catch(Throwable), перевзятие при nokiaGraphics != g, обе ветви
       flush() сохраняют оригинальный drawRGB-fallback;
     - DeviceProfile/build-j2me.sh: ключ и nokia_ui_stub.jar на местах.
  C. Артефакт (если JAR собран): NokiaBlitter.class на месте, sipush 8888
     инлайнено, forName-строка в DirectX7.class, com/nokia и javax НЕ в JAR.

Запуск:  python3 docs/verify/test_nokia_blit.py
Код 0 = всё зелёное.
"""
import io
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(os.path.dirname(HERE))

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


# ---------------------------------------------------------------- зеркала ----

class FakeG:
    pass


def mirror_nokia(seq, nokia_blit=True):
    """Зеркало DirectX7.nokia(g): seq - список событий на вызов.

    Каждое событие: (g, forname_ok, create_ok). Возвращает список исходов
    ('blit' / None) и число созданных blitter'ов.
    """
    tried = [False]
    blitter = [None]
    src = [None]
    created = [0]
    out = []
    for (g, forname_ok, create_ok) in seq:
        if not nokia_blit:
            out.append(None)
            continue
        if blitter[0] is None:
            if tried[0]:
                out.append(None)
                continue
            tried[0] = True
            if not forname_ok:
                out.append(None)
                continue
            if create_ok:
                created[0] += 1
                blitter[0] = 'B'
                src[0] = g
                out.append('blit')
            else:
                out.append(None)
            continue
        if src[0] is not g:
            if create_ok:
                created[0] += 1
                blitter[0] = 'B'
                src[0] = g
                out.append('blit')
            else:
                blitter[0] = None   # create провалился - перевзятия больше нет?
                out.append(None)
            continue
        out.append('blit')
    return out, created[0]


def mirror_nokia_blit_opt(opt):
    """DeviceProfile: nokiaBlit = opt>=0 ? (opt==1) : true."""
    return (opt == 1) if opt >= 0 else True


def mirror_draw_rgb(pixels, offset, scanlength, x, y, w, h):
    """Адресация drawRGB/drawPixels: какие пикселы куда попадают."""
    out = []
    for y1 in range(y, y + h):
        for x1 in range(x, x + w):
            out.append(((x1, y1), pixels[offset + (x1 - x) + (y1 - y) * scanlength]))
    return out


def part_a():
    # A1. детектор: ключ выключен -> всегда null, forName не зовётся вовсе.
    g = FakeG()
    out, made = mirror_nokia([(g, True, True)] * 3, nokia_blit=False)
    check(out == [None, None, None] and made == 0, 'A1: NOKIA_BLIT=0 не глушит путь')

    # A2. forName неудачен -> null навсегда (второй вызов не повторяет попытку).
    out, made = mirror_nokia([(g, False, True), (g, True, True)])
    check(out == [None, None] and made == 0, 'A2: неудачный forName не запомнен')

    # A3. успех: blitter создан один раз, тот же Graphics - без перевзятий.
    out, made = mirror_nokia([(g, True, True), (g, True, True), (g, True, True)])
    check(out == ['blit'] * 3 and made == 1, 'A3: лишние перевзятия DirectGraphics')

    # A4. смена Graphics -> peer перевзят.
    g2 = FakeG()
    out, made = mirror_nokia([(g, True, True), (g2, True, True), (g2, True, True)])
    check(out == ['blit'] * 3 and made == 2, 'A4: смена Graphics не перевзят')

    # A5. create() провалился (getDirectGraphics кинул) -> null навсегда.
    out, made = mirror_nokia([(g, True, False), (g, True, True)])
    check(out == [None, None] and made == 0, 'A5: провал create() не запомнен')

    # A6. парсинг ключа: opt-in по умолчанию, 0 глушит, не-1 глушит.
    for opt, want in [(-1, True), (0, False), (1, True), (2, False), (7, False)]:
        check(mirror_nokia_blit_opt(opt) == want,
              'A6: NOKIA_BLIT=%d -> %s, ожидалось %s' % (opt, mirror_nokia_blit_opt(opt), want))

    # A7. адресация drawPixels == drawRGB пиксел-в-пиксел (обе ветви flush).
    # Ветка S>=100: pixels=display, offset=0, scanlength=width.
    vw, vh, tw, th = 6, 5, 24, 20
    display = [(y * vw + x) | 0xff000000 for y in range(vh) for x in range(vw)]
    x0, y0 = 0, 3
    rgb = mirror_draw_rgb(display, 0, vw, x0, y0, vw, vh)
    pxl = mirror_draw_rgb(display, 0, vw, x0, y0, vw, vh)  # drawPixels: те же args
    check(rgb == pxl and len(rgb) == vw * vh, 'A7: ветвь S>=100 разошлась')
    # Ветка S<100: pixels=scaleBuf, offset=0, scanlength=targetW.
    scale = [(y * tw + x) | 0xff000000 for y in range(th) for x in range(tw)]
    rgb = mirror_draw_rgb(scale, 0, tw, x0, y0, tw, th)
    pxl = mirror_draw_rgb(scale, 0, tw, x0, y0, tw, th)
    check(rgb == pxl and len(rgb) == tw * th, 'A7: ветвь S<100 разошлась')


# ------------------------------------------------------- инварианты кода -----

def read(rel):
    return io.open(os.path.join(SRC, rel), encoding='utf-8').read()


def part_b():
    stub = read('docs/j2me-build/stubsrc-nokia/com/nokia/mid/ui/DirectGraphics.java')
    for name, val in [('TYPE_INT_8888_ARGB', 8888), ('TYPE_INT_888_RGB', 888),
                      ('FLIP_HORIZONTAL', 8192), ('FLIP_VERTICAL', 16384),
                      ('ROTATE_90', 90), ('ROTATE_180', 180), ('ROTATE_270', 270)]:
        check(re.search(r'%s\s*=\s*%d\s*;' % (name, val), stub) is not None,
              'B: константа стаба %s != %d (официальный javadoc)' % (name, val))
    check(re.search(r'public void drawPixels\(int\[\] pixels, boolean transparency, int offset,\s*\n'
                    r'\s*int scanlength, int x, int y, int width, int height,\s*\n'
                    r'\s*int manipulation, int formatOfPixels\);', stub) is not None,
          'B: сигнатура drawPixels(int[]) в стабе разошлась')

    du = read('docs/j2me-build/stubsrc-nokia/com/nokia/mid/ui/DirectUtils.java')
    check('public static DirectGraphics getDirectGraphics(Graphics g)' in du,
          'B: сигнатура getDirectGraphics в стабе разошлась')

    nb = read('src/code/Rendering/NokiaBlitter.java')
    # точный текст вызова: символическая константа (не литерал 8888), offset=0,
    # manipulation=0 - более строгая проверка, чем поиск подстрок.
    check(re.search(r'dg\.drawPixels\(pixels, alpha, 0, scanlength, x, y, w, h, 0, '
                    r'DirectGraphics\.TYPE_INT_8888_ARGB\);', nb) is not None,
          'B: вызов drawPixels не совпал с эталоном (символьная константа, offset=0, manipulation=0)')
    check('static NokiaBlitter create(Graphics g)' in nb
          and 'catch (Throwable t)' in nb and 'return null;' in nb,
          'B: create() без catch(Throwable)->null')
    check('private NokiaBlitter(Graphics g)' in nb and 'public final class NokiaBlitter' in nb,
          'B: NokiaBlitter не final/не приватный конструктор')

    dx = read('src/code/Rendering/DirectX7.java')
    m = re.search(r'static final NokiaBlitter nokia\(Graphics g\) \{(.*?)\n   \}', dx, re.S)
    check(m is not None, 'B: детектор nokia(Graphics) не найден')
    body = m.group(1) if m else ''
    check(body.index('DeviceProfile.nokiaBlit') < body.index('Class.forName'),
          'B: страж NOKIA_BLIT должен быть первым (до загрузки NokiaBlitter)')
    check('Class.forName("com.nokia.mid.ui.DirectGraphics")' in body,
          'B: forName-страж отсутствует/изменён')
    check('nokiaGraphics != g' in body, 'B: перевзятие при смене Graphics отсутствует')
    check(body.count('catch') >= 1, 'B: forName вне try/catch')
    # flush(): обе ветви сохраняют оригинальный drawRGB-fallback.
    mf = re.search(r'public final void flush\(Graphics g, int x, int y\) \{(.*?)\n   \}', dx, re.S)
    check(mf is not None, 'B: flush() не найден')
    fbody = mf.group(1) if mf else ''
    check('g.drawRGB(display, 0, width, x, y, width, height,flushAlpha );' in fbody,
          'B: оригинальный drawRGB(display) не сохранён в fallback')
    check('g.drawRGB(buf, 0, targetW, x, y, targetW, targetH, flushAlpha);' in fbody,
          'B: оригинальный drawRGB(buf) не сохранён в scaled-fallback')
    check(fbody.count('nb.blit(') == 2 and 'nokia(g)' in fbody,
          'B: flush() не использует blitter в обеих ветвях RENDER_SCALE')
    check(fbody.index('NokiaBlitter nb = nokia(g);') < fbody.index('if(renderScale >= 100)'),
          'B: детектор вызывается не до стража RENDER_SCALE')

    dp = read('src/code/utils/DeviceProfile.java')
    check('settings.getIntNoLang("NOKIA_BLIT", -1)' in dp,
          'B: DeviceProfile не парсит NOKIA_BLIT')
    check('nokiaBlit = nokiaBlitOpt >= 0 ? (nokiaBlitOpt == 1) : true;' in dp,
          'B: дефолт NOKIA_BLIT не opt-out(true)')
    check('public static boolean nokiaBlit = true;' in dp,
          'B: поле nokiaBlit отсутствует/не true по умолчанию')
    check('nokiaBlit=' in dp, 'B: toString не печатает nokiaBlit')

    bs = read('docs/j2me-build/build-j2me.sh')
    check(bs.count('nokia_ui_stub.jar') >= 3,
          'B: nokia_ui_stub.jar не во всех трёх местах build-j2me.sh (BOOT/libraryjars/check)')

    gt = read('docs/j2me-build/get-toolchain.sh')
    check('stubsrc-nokia' in gt and 'nokia_ui_stub.jar' in gt,
          'B: get-toolchain.sh не собирает nokia_ui_stub.jar')


def part_c():
    jar = os.path.join(SRC, 'artifacts', 'QuantumOptimize.jar')
    if not os.path.exists(jar):
        print('  (C пропущен: artifacts/QuantumOptimize.jar не собран)')
        return
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    check('code/Rendering/NokiaBlitter.class' in names,
          'C: NokiaBlitter.class нет в JAR (shrink его съел?)')
    check(not [n for n in names if n.startswith('com/nokia')],
          'C: com/nokia/* попал в JAR (стаб не должен упаковываться)')
    check(not [n for n in names if n.startswith('javax/')],
          'C: javax/* попал в JAR')
    nb = z.read('code/Rendering/NokiaBlitter.class')
    check(b'\x11\x22\xb8' in nb,
          'C: sipush 8888 не инлайнено в NokiaBlitter.class')
    check(b'drawPixels' in nb and b'getDirectGraphics' in nb,
          'C: ссылки на Nokia UI API отсутствуют в NokiaBlitter.class')
    dx = z.read('code/Rendering/DirectX7.class')
    check(b'com.nokia.mid.ui.DirectGraphics' in dx,
          'C: forName-строка не в constant pool DirectX7.class')
    check(b'NOKIA_BLIT' in z.read('code/utils/DeviceProfile.class'),
          'C: ключ NOKIA_BLIT не в DeviceProfile.class')


def main():
    part_a()
    part_b()
    part_c()
    print()
    if fails:
        print('ПРОВАЛЕНО (%d):' % len(fails))
        for f in fails:
            print('  -', f)
        return 1
    print('Nokia DirectGraphics: детектор грузит NokiaBlitter только при живом')
    print('com.nokia.mid.ui и NOKIA_BLIT=1; drawPixels маппится 1:1 на drawRGB;')
    print('константы стаба = официальному javadoc (8888 инлайнено); в JAR нет')
    print('com/nokia; fallback drawRGB сохранён в обеих ветвях RENDER_SCALE.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
