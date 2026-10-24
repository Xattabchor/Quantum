#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Регрессия на баг "первый запуск норм, второй вылетает" (RMS настроек).

Причина была в асимметрии: читатель настроек в Main.java потребляет слот
бывшего footsteps (readInt и выбросить), а писатель saveSettingToStore()
после вырезания звука ходьбы этот int не писал. Запись получалась на 4 байта
короче, все поля после sounds читались со сдвигом, мусорное `codes` вело к
new int[огромное] -> OutOfMemoryError (Error не ловится catch(Exception))
-> падение движка на втором запуске.

Тест статически проверяет:
  1) последовательность операций чтения (music..lastLevel) совпадает
     элемент-в-элемент с последовательностью записи - формат RMS симметричен
     и байт-в-байт равен оригинальному (слот footsteps пишется константой);
  2) страж диапазонов на случай сдвинутой/битой записи на месте;
  3) страж на количество keyCodes (защита от OOM-аллокации) на месте.

Запуск:  python3 docs/verify/test_rms_settings.py
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, '..', '..', 'src', 'code', 'utils', 'Main.java')

src = io.open(MAIN, encoding='utf-8').read()


def extract(block_src, start, end):
    i = block_src.index(start)
    j = block_src.index(end, i) + len(end)
    return block_src[i:j]


def ops(block, read=True):
    """Список операций ('int'/'bool') в порядке следования."""
    out = []
    pat = re.compile(r'(readInt|readBoolean|writeInt|writeBoolean)\s*\(')
    for m in pat.finditer(block):
        kind = m.group(1)
        if read and not kind.startswith('read'):
            continue
        if not read and not kind.startswith('write'):
            continue
        out.append('int' if kind.endswith('Int') else 'bool')
    return out


fails = []

# --- 1. Симметрия формата настроек -------------------------------------------
reader = extract(src, 'music = var6.readInt();', 'lastLevel = var6.readInt();')
writer = extract(src, 'dos.writeInt(music);', 'dos.writeInt(lastLevel);')

rops = ops(reader, read=True)
wops = ops(writer, read=False)

# Оригинальный формат: 11 int + 7 bool (music, sounds, [footsteps-слот],
# displaySize, availableLevel, persQ, fogQ, pixelsQ, lang, mouseSpeed,
# lastLevel; frameskip, corpses, blood, mipMapping, debugMode, hideHud,
# resizeWeapons).
expected = ['int', 'int', 'int', 'int', 'int', 'int', 'int', 'int',
            'bool', 'bool', 'bool', 'bool', 'bool',
            'int', 'int', 'bool', 'bool', 'int']

if rops != wops:
    fails.append('читатель и писатель настроек АСИММЕТРИЧНЫ:\n'
                 '  read : %s\n  write: %s' % (rops, wops))
if sorted(rops) != sorted(expected) or rops != wops:
    # порядок важен: сравниваем точную последовательность оригинала,
    # где bool-группа стоит после pixelsQ (см. original Main.java 203-220).
    pass

# Точная последовательность оригинального формата (214a3fc):
orig = ['int', 'int', 'int',            # music, sounds, footsteps-слот
        'int', 'int',                   # displaySize, availableLevel
        'int', 'int', 'int',            # persQ, fogQ, pixelsQ
        'bool', 'bool', 'bool', 'bool', 'bool',  # frameskip..debugMode
        'int', 'int',                   # lang, mouseSpeed
        'bool', 'bool',                 # hideHud, resizeWeapons
        'int']                          # lastLevel
if rops != orig:
    fails.append('последовательность чтения не совпадает с оригинальным '
                 'форматом:\n  есть   : %s\n  оригинал: %s' % (rops, orig))
if wops != orig:
    fails.append('последовательность записи не совпадает с оригинальным '
                 'форматом:\n  есть   : %s\n  оригинал: %s' % (wops, orig))

# --- 2. Страж диапазонов ------------------------------------------------------
if 'corrupted settings record' not in src:
    fails.append('нет стража диапазонов (throw "corrupted settings record")')
for field in ['music', 'sounds', 'displaySize', 'persQ', 'fogQ', 'pixelsQ',
              'lang', 'mouseSpeed', 'lastLevel']:
    if not re.search(r'\b%s\s*<' % field, src):
        fails.append('страж не проверяет поле %s' % field)

# --- 3. Страж количества keyCodes --------------------------------------------
if 'bad keycodes count' not in src:
    fails.append('нет стража codes (throw "bad keycodes count") - '
                 'new int[codes] из мусорного codes снова даст OOM')

# --- Итог ---------------------------------------------------------------------
if fails:
    print('FAIL')
    for f in fails:
        print(' -', f)
    sys.exit(1)

print('RMS настроек: read=%d ops, write=%d ops - симметричны и равны '
      'оригинальному формату (11 int + 7 bool + слот footsteps); '
      'стражи диапазонов и codes на месте.' % (len(rops), len(wops)))
print('OK')
