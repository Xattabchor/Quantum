#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка TITLE_IMAGE (§25): своя картинка-фон титульного экрана.

  A. Python-зеркала:
     - конструктор: ключа нет -> background = None; ключ есть -> загрузка
       через ImageResize-контракт (файл битый/нет -> None, без исключений);
     - paint(): background=None -> ветвь чёрного fillRect бит-в-бит прежняя;
       background=Image -> drawImage(0,0,0); белый текст рисуется ПОСЛЕ фона
       в обеих ветвях (порядок операций сохранён);
     - onRightSoftKey(): background обнуляется ДО создания Menu (heap).
  B. Инварианты исходников:
     - TitleScreen.java: getNoLang("TITLE_IMAGE"), createImage(titleImage, w, h),
       ветвление paint с прежним fillRect в else, drawImage-якорь 0
       (хаус-стаиль Menu), background=null первым делом в onRightSoftKey,
       импорты ImageResize/Image;
     - ImageResize.createImage(String,int,int): внутренний catch Exception ->
       return null (контракт, на который опирается TitleScreen);
     - DeviceProfile TITLE_IMAGE НЕ разбирает (контентный ключ).
  C. Артефакт (если JAR собран): строка TITLE_IMAGE в TitleScreen.class.

Запуск:  python3 docs/verify/test_title_image.py
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

def mirror_ctor(title_image_key, file_ok):
    """Зеркало конструктора TitleScreen (в части TITLE_IMAGE)."""
    background = None
    if title_image_key is not None:
        # ImageResize.createImage: catch(Exception) -> null
        background = 'IMG' if file_ok else None
    return background


def mirror_paint(background):
    """Зеркало paint(): возвращает порядок операций."""
    ops = []
    if background is not None:
        ops.append(('drawImage', background, 0, 0, 0))
    else:
        ops.append(('setColor', 0))
        ops.append(('fillRect', 0, 0, 'w', 'h'))
    ops.append(('setColor', 0xffffff))
    ops.append(('text.paint',))
    return ops


def mirror_exit(background):
    """Зеркало onRightSoftKey(): порядок освобождения и создания Menu."""
    ops = []
    background = None
    ops.append(('release', background))
    ops.append(('menu',))
    return ops


def part_a():
    # A1. ключа нет -> None, paint бит-в-бит прежний (чёрный фон).
    check(mirror_ctor(None, True) is None, 'A1: без ключа background != None')
    ops = mirror_paint(None)
    check(ops[0] == ('setColor', 0) and ops[1] == ('fillRect', 0, 0, 'w', 'h'),
          'A1: ветвь без картинки не прежняя')

    # A2. ключ есть, файл хороший -> картинка; paint через drawImage(0,0,0).
    check(mirror_ctor('/title.png', True) == 'IMG', 'A2: картинка не загружена')
    ops = mirror_paint('IMG')
    check(ops[0] == ('drawImage', 'IMG', 0, 0, 0), 'A2: drawImage не (0,0,0)')

    # A3. ключ есть, файл битый/нет -> None (контракт ImageResize), чёрный фон.
    check(mirror_ctor('/missing.png', False) is None,
          'A3: битый файл не откатился в None')
    check(mirror_paint(None)[0] == ('setColor', 0), 'A3: откат не на чёрный фон')

    # A4. белый текст рисуется ПОСЛЕ фона в обеих ветвях.
    for bg in (None, 'IMG'):
        ops = mirror_paint(bg)
        check(ops[-2] == ('setColor', 0xffffff) and ops[-1] == ('text.paint',),
              'A4: порядок текста нарушен (bg=%s)' % bg)

    # A5. выход: background освобождён ДО создания Menu.
    ops = mirror_exit('IMG')
    check(ops[0][0] == 'release' and ops[0][1] is None and ops[1][0] == 'menu',
          'A5: освобождение не до Menu')


# ------------------------------------------------------- инварианты кода -----

def read(rel):
    return io.open(os.path.join(SRC, rel), encoding='utf-8').read()


def part_b():
    ts = read('src/code/HUD/TitleScreen.java')
    check('import code.utils.ImageResize;' in ts
          and 'import javax.microedition.lcdui.Image;' in ts,
          'B: импорты ImageResize/Image отсутствуют')
    check(re.search(r'^\s*Image background;', ts, re.M) is not None,
          'B: поле Image background отсутствует')
    check('Main.settings.getNoLang("TITLE_IMAGE")' in ts,
          'B: ключ читается не getNoLang("TITLE_IMAGE")')
    check('if(titleImage != null) background = ImageResize.createImage(titleImage, w, h);' in ts,
          'B: загрузка картинки не по эталону (null-guard + w,h)')
    m = re.search(r'protected final void paint\(Graphics g\) \{(.*?)\n    \}', ts, re.S)
    check(m is not None, 'B: paint() не найден')
    body = m.group(1) if m else ''
    check(re.search(r'if\(background != null\) \{\s*g\.drawImage\(background, 0, 0, 0\);\s*'
                    r'\} else \{\s*g\.setColor\(0\);\s*g\.fillRect\(0,0,w,h\);\s*\}',
                    body) is not None,
          'B: ветвление paint() разошлось (drawImage(0,0,0) / прежний fillRect)')
    check(body.index('fillRect') < body.index('g.setColor(0xffffff)'),
          'B: белый текст рисуется до фона')
    mo = re.search(r'protected final void onRightSoftKey\(\) \{(.*?)\n    \}', ts, re.S)
    check(mo is not None, 'B: onRightSoftKey() не найден')
    obody = mo.group(1).strip() if mo else ''
    check(obody.startswith('background = null;'),
          'B: onRightSoftKey не начинает с background = null')
    check(obody.index('background = null') < obody.index('new Menu'),
          'B: освобождение не до new Menu')

    ir = read('src/code/utils/ImageResize.java')
    m = re.search(r'public static Image createImage\(String file, int w, int h\) \{(.*?)\n    \}',
                  ir, re.S)
    check(m is not None, 'B: ImageResize.createImage(String,int,int) не найден')
    ibody = m.group(1) if m else ''
    check('catch (Exception' in ibody and 'return null;' in ibody,
          'B: контракт null у ImageResize.createImage нарушен')

    dp = read('src/code/utils/DeviceProfile.java')
    check('TITLE_IMAGE' not in dp,
          'B: TITLE_IMAGE не должен разбираться в DeviceProfile (контентный ключ)')


def part_c():
    jar = os.path.join(SRC, 'artifacts', 'QuantumOptimize.jar')
    if not os.path.exists(jar):
        print('  (C пропущен: artifacts/QuantumOptimize.jar не собран)')
        return
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    check('code/HUD/TitleScreen.class' in names, 'C: TitleScreen.class нет в JAR')
    check(b'TITLE_IMAGE' in z.read('code/HUD/TitleScreen.class'),
          'C: строка TITLE_IMAGE не в constant pool TitleScreen.class')


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
    print('TITLE_IMAGE: без ключа титульный экран бит-в-бит прежний (чёрный);')
    print('с ключом - картинка на весь экран, битый файл откатывается в чёрный')
    print('без исключений; heap освобождается до Menu.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
