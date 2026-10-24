#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка RENDER_SCALE (§22): масштаб внутреннего рендера с nearest-апскейлом.

  A. Python-зеркала кода DirectX7/DeviceProfile/WeatherGenerator/LevelLoader:
     - resize(): view = target*S/100 (минимум 1); S=100 -> view == target
       бит-в-бит (страж в flush(), отложенный scaleBuf не создаётся);
     - парсинг DeviceProfile: нет ключа -> 100 у любого профиля,
       значения обрезаются в 25..100;
     - colMap/rowMap: неубывающие, без выхода за границы view-буфера,
       каждый исходный пиксел выбирается хотя бы раз (сюръективность);
     - scaledDisplay(): содержимое == display[rowMap[ty]*viewW+colMap[tx]]
       для всех (tx,ty), длина == targetW*targetH;
     - screenY леттербокса не зависит от S (только target-размеры);
     - lightingEffect (молния): старые индексы (страйд screenWidth, строки
       до screenHeight) при S<100 вылезали бы за view-буфер; новые
       (g3d.width/height) остаются в границах; при S=100 horizonY совпадает
       со старой формулой бит-в-бит;
     - vignette getRGB: страйд img.getWidth() (view) в границах массива;
       старый страйд width (target) при S<100 дал бы OOB;
     - getScaledDisplay(): длина == targetW*targetH (страйд PauseScreen).
  B. Инварианты исходников (grep): страж renderScale>=100 перед оригинальным
     drawRGB; fastbloom/vignette/death/damage остаются на view-буфере;
     PauseScreen получает getScaledDisplay(); WeatherGenerator создаётся
     с экранными размерами (частицы), lightingEffect - на g3d-размерах.

Запуск:  python3 docs/verify/test_render_scale.py
Код 0 = всё зелёное.
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(os.path.dirname(HERE))

fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)


# ---------------------------------------------------------------- зеркала ----

def mirror_resize(targetW, targetH, s):
    """DirectX7.resize(): возвращает (viewW, viewH)."""
    if s < 100:
        w = targetW * s // 100
        h = targetH * s // 100
        if w < 1:
            w = 1
        if h < 1:
            h = 1
        return w, h
    return targetW, targetH


def mirror_profile_scale(opt):
    """DeviceProfile: opt = значение ключа или -1 (нет ключа)."""
    rs = opt if opt >= 0 else 100
    if rs < 25:
        rs = 25
    if rs > 100:
        rs = 100
    return rs


def mirror_maps(viewW, viewH, targetW, targetH):
    colMap = [tx * viewW // targetW for tx in range(targetW)]
    rowMap = [ty * viewH // targetH for ty in range(targetH)]
    return colMap, rowMap


def mirror_scaled(display, viewW, viewH, targetW, targetH, colMap, rowMap):
    scaleBuf = [0] * (targetW * targetH)
    for ty in range(targetH):
        srow = rowMap[ty] * viewW
        drow = ty * targetW
        for tx in range(targetW):
            scaleBuf[drow + tx] = display[srow + colMap[tx]]
    return scaleBuf


def part_a():
    targets = [(240, 320), (176, 208), (128, 128), (320, 240), (360, 640)]
    scales = [25, 33, 50, 66, 75, 99, 100]

    # A1. resize(): геометрия + бит-в-бит при S=100.
    for (tw, th) in targets:
        for s in scales:
            vw, vh = mirror_resize(tw, th, s)
            if s >= 100:
                check((vw, vh) == (tw, th),
                      'A1: S=%d view != target (%dx%d)' % (s, tw, th))
            else:
                check(vw == max(1, tw * s // 100) and vh == max(1, th * s // 100),
                      'A1: S=%d view=%dx%d ожидалось %dx%d'
                      % (s, vw, vh, tw * s // 100, th * s // 100))
                check(vw <= tw and vh <= th, 'A1: view больше target при S=%d' % s)
    # экстремально маленькая цель не даёт нулевого буфера
    check(mirror_resize(3, 3, 25) == (1, 1), 'A1: минимум 1x1 нарушен')

    # A2. DeviceProfile: opt-in и клампаны.
    check(mirror_profile_scale(-1) == 100, 'A2: нет ключа -> != 100')
    for opt, want in [(0, 25), (24, 25), (25, 25), (50, 50), (99, 99),
                      (100, 100), (101, 100), (999, 100)]:
        check(mirror_profile_scale(opt) == want,
              'A2: RENDER_SCALE=%d -> %d, ожидалось %d'
              % (opt, mirror_profile_scale(opt), want))

    # A3/A4. карты, границы, сюръективность, содержимое scaleBuf.
    for (tw, th) in targets:
        for s in scales:
            vw, vh = mirror_resize(tw, th, s)
            colMap, rowMap = mirror_maps(vw, vh, tw, th)
            check(len(colMap) == tw and len(rowMap) == th, 'A3: длина карт')
            check(all(colMap[i] <= colMap[i + 1] for i in range(tw - 1))
                  and all(rowMap[i] <= rowMap[i + 1] for i in range(th - 1)),
                  'A3: карты неубывающие (%dx%d S=%d)' % (tw, th, s))
            check(colMap[0] == 0 and rowMap[0] == 0, 'A3: карты начинаются с 0')
            check(colMap[-1] < vw and rowMap[-1] < vh,
                  'A3: OOB в картах (%dx%d S=%d)' % (tw, th, s))
            check(len(set(colMap)) == vw and len(set(rowMap)) == vh,
                  'A3: не каждый исходный пиксел выбран (%dx%d S=%d)' % (tw, th, s))
            # A4: содержимое - уникальный "цвет" на пиксел view-буфера.
            display = [(y * vw + x) | 0xff000000 for y in range(vh) for x in range(vw)]
            buf = mirror_scaled(display, vw, vh, tw, th, colMap, rowMap)
            check(len(buf) == tw * th, 'A4: длина scaleBuf != targetW*targetH')
            ok = True
            for ty in range(th):
                for tx in range(tw):
                    want = display[rowMap[ty] * vw + colMap[tx]]
                    if buf[ty * tw + tx] != want:
                        ok = False
                        break
                if not ok:
                    break
            check(ok, 'A4: scaleBuf содержимое (%dx%d S=%d)' % (tw, th, s))
            # A5: getScaledDisplay всегда совпадает по страйду с target.
            check(len(buf) == tw * th, 'A5: страйд PauseScreen не сходится')

    # A6. screenY леттербокса зависит только от target (не от S).
    physH = 320
    ys = set()
    for s in scales:
        tw, th = 240, physH * 100 // 100  # target не зависит от renderScale
        ys.add((physH - th) // 2)
    check(len(ys) == 1, 'A6: screenY зависит от S')

    # A7. lightingEffect: старый код при S<100 вылезал за буфер, новый - нет.
    tw, th = 240, 320
    for s in [50, 75]:
        vw, vh = mirror_resize(tw, th, s)
        viewLen = vw * vh
        oldMax = (th - 1) * tw + tw - 1     # y*screenWidth + (screenWidth-1)
        newMax = (vh - 1) * vw + vw - 1     # y*g3d.width + (g3d.width-1)
        check(oldMax >= viewLen,
              'A7: старый OOB-сценарий не воспроизведён при S=%d' % s)
        check(newMax < viewLen, 'A7: новый индекс вне буфера при S=%d' % s)
    # при S=100 формула horizonY совпадает бит-в-бит (g3d.height==screenHeight).
    horizonLine, fovY, rotateX = -5, 100, 30.0
    for (tw, th) in [(240, 320), (176, 208)]:
        vw, vh = mirror_resize(tw, th, 100)
        old = int(th / 2 - horizonLine * th / fovY + rotateX * th / fovY)
        new = int(vh / 2 - horizonLine * vh / fovY + rotateX * vh / fovY)
        check(old == new, 'A7: horizonY при S=100 разошёлся')

    # A8. vignette getRGB: страйд img.getWidth() (view) в границах.
    for s in [50, 100]:
        tw, th = 240, 320
        vw, vh = mirror_resize(tw, th, s)
        arrLen = vw * vh
        newMax = (vh - 1) * vw + vw - 1
        check(newMax < arrLen, 'A8: новый страйд getRGB вне массива при S=%d' % s)
        if s < 100:
            oldMax = (vh - 1) * tw + vw - 1   # старый страйд = width (target)
            check(oldMax >= arrLen,
                  'A8: старый OOB-сценарий не воспроизведён при S=%d' % s)


# ------------------------------------------------------- инварианты кода -----

def read(rel):
    return io.open(os.path.join(SRC, rel), encoding='utf-8').read()


def part_b():
    dx = read('src/code/Rendering/DirectX7.java')
    # страж S>=100 идёт ПЕРЕД оригинальным drawRGB(display, ... width ...);
    # с этапа 24 внутри обеих ветвей стоит NokiaBlitter с drawRGB-fallback.
    m = re.search(r'if\(renderScale >= 100\) \{\s*'
                  r'if\(nb != null\) \{\s*'
                  r'nb\.blit\(display, width, x, y, width, height, flushAlpha\);\s*'
                  r'\} else \{\s*'
                  r'g\.drawRGB\(display, 0, width, x, y, width, height,\s*flushAlpha \);\s*'
                  r'\}\s*'
                  r'return;\s*\}', dx)
    check(m is not None, 'B: страж renderScale>=100 с оригинальным drawRGB не найден')
    check(re.search(r'int\[\] buf = scaledDisplay\(\);\s*'
                    r'if\(nb != null\) \{\s*'
                    r'nb\.blit\(buf, targetW, x, y, targetW, targetH, flushAlpha\);\s*'
                    r'\} else \{\s*'
                    r'g\.drawRGB\(buf, 0, targetW, x, y, targetW, targetH, flushAlpha\);\s*'
                    r'\}', dx) is not None,
          'B: scaled-ветвь flush() не найдена')
    check('scaleBuf = null;' in dx and 'colMap = null;' in dx and 'rowMap = null;' in dx,
          'B: resize() не инвалидирует отложенные буферы')
    check('renderScale = DeviceProfile.renderScale;' in dx,
          'B: resize() не читает DeviceProfile.renderScale')
    check('getScaledDisplay' in dx and 'getTargetWidth' in dx and 'getTargetHeight' in dx,
          'B: геттеры target/getScaledDisplay отсутствуют')

    gs = read('src/code/Gameplay/GameScreen.java')
    check('int screenY = (height - g3d.getTargetHeight()) / 2;' in gs,
          'B: screenY не на target-высоте')
    check('new PauseScreen(main, this, scene.getG3D().getScaledDisplay(), musTime)' in gs,
          'B: PauseScreen не через getScaledDisplay()')
    check('applyVignette(g3d.getDisplay())' in gs,
          'B: vignette ушёл с view-буфера')
    # fastbloom остаётся на view-размерах (не трогать): :659 (2) + :664 (4)
    # + :671 (4) = 10 ссылок; больше g3d.getWidth/Height в GameScreen нет.
    fb = gs.count('g3d.getWidth()') + gs.count('g3d.getHeight()')
    check(fb == 10, 'B: fastbloom-сайты g3d.getWidth/Height: %d, ожидалось 10' % fb)
    check('scene.getG3D().getTargetWidth()' in gs and 'scene.getG3D().getTargetHeight()' in gs,
          'B: Player/createScene-сайты не на target')

    dp = read('src/code/utils/DeviceProfile.java')
    check('settings.getIntNoLang("RENDER_SCALE", -1)' in dp,
          'B: DeviceProfile не парсит RENDER_SCALE')
    check('renderScale = renderScaleOpt >= 0 ? renderScaleOpt : 100;' in dp,
          'B: дефолт RENDER_SCALE не 100 (opt-in)')
    check('if(renderScale < 25) renderScale = 25;' in dp
          and 'if(renderScale > 100) renderScale = 100;' in dp,
          'B: клампаны 25..100 отсутствуют')

    ll = read('src/code/Gameplay/LevelLoader.java')
    check('new WeatherGenerator(GameScreen.width, GameScreen.height * Main.getDisplaySize() / 100' in ll,
          'B: WeatherGenerator больше не создаётся с экранными размерами (частицы)')
    check('img.getRGB(vignette, 0, img.getWidth(), 0, 0, img.getWidth(), img.getHeight());' in ll,
          'B: страйд getRGB виньетки не исправлен на img.getWidth()')
    check('scene.g3d.getTargetWidth()' in ll and 'scene.g3d.getTargetHeight()' in ll,
          'B: фон-скейлинг LevelLoader не на target')

    wg = read('src/code/utils/WeatherGenerator.java')
    m = re.search(r'public final void lightingEffect\(.*?\n    \}', wg, re.S)
    check(m is not None, 'B: lightingEffect не найден')
    # выкидываем комментарии: в закомментированном animationHorizonLine-блоке
    # screenHeight остаётся легально.
    body = re.sub(r'/\*.*?\*/', '', m.group(0), flags=re.S) if m else ''
    body = re.sub(r'//[^\n]*', '', body)
    check('screenWidth' not in body and 'screenHeight' not in body,
          'B: lightingEffect всё ещё на screen-размерах (OOB при S<100)')
    check('g3d.height' in body and 'g3d.width' in body,
          'B: lightingEffect не переведён на g3d-размеры')

    ds = read('src/code/Gameplay/DialogScreen.java')
    check('g3d.getTargetWidth() - 20' in ds and 'getHeight() / 2 - g3d.getTargetHeight() / 2' in ds,
          'B: DialogScreen не на target-размерах')


def main():
    part_a()
    part_b()
    print()
    if fails:
        print('ПРОВАЛЕНО (%d):' % len(fails))
        for f in fails:
            print('  -', f)
        return 1
    print('RENDER_SCALE: resize/карты/апскейл зеркально точны, S=100 бит-в-бит')
    print('(страж в flush, отложенный scaleBuf); lightingEffect и vignette-страйд')
    print('в границах view-буфера; HUD/пауза/диалоги на target-размерах.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
