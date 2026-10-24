#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка формата QCT end-to-end:

  1. TestQctEncode (чистый CLDC-код, выполняется на JRE) пакует тестовые
     текстуры и печатает round-trip статистику + байты в hex;
  2. скрипт раскладывает эти байты по файлам t*.qct;
  3. TestQctLoad читает их НАСТОЯЩИМ загрузчиком движка
     (RawImage.createRawImage -> qctToRawImage, ресурс из classpath) и
     сверяет с QctEncoder.decode() побитово.

Запуск:  python3 docs/verify/test_qct.py
Переменные окружения: T (тулчейн), JAVA (путь к java). Код возврата 0 = всё
зелёное.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(os.path.dirname(HERE))
T = os.environ.get('T', os.path.expanduser('~/tools/j2me'))
JAVA = os.environ.get('JAVA', 'java')
WORK = os.path.join('/tmp', 'qcttest')
ECJ = ['org.eclipse.jdt.internal.compiler.batch.Main']
BOOT = os.pathsep.join([os.path.join(T, 'lib', n) for n in
                        ('cldc_1.1.jar', 'midp_2.0.jar', 'jsr184_1.1.jar',
                         'jsr075_stub.jar', 'nokia_ui_stub.jar')])


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        raise SystemExit('команда завершилась с кодом %d: %s'
                         % (r.returncode, ' '.join(cmd[:6])))
    return r.stdout


def main():
    for d in ('enc', 'load', 'res', 'classes', 'stub'):
        os.makedirs(os.path.join(WORK, d), exist_ok=True)

    print('[1/4] компиляция и прогон TestQctEncode (CLDC-код на JRE)')
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'enc'),
           os.path.join(SRC, 'src', 'code', 'utils', 'QctEncoder.java'),
           os.path.join(HERE, 'TestQctEncode.java')])
    out = run([JAVA, '-cp', os.path.join(T, 'lib', 'cldc_1.1.jar')
               + os.pathsep + os.path.join(WORK, 'enc'), 'TestQctEncode'])

    fails = []
    rt = {}
    for line in out.splitlines():
        if line.startswith('RT '):
            m = re.match(r'RT (\S+) maxerr=(-?\d+) alphaok=(\d) nullok=(\d)'
                         r'(?: exact=(\d))?', line)
            rt[m.group(1)] = m
            print('   ', line)
    for name, expect_exact in (('t0', True), ('t1', True), ('t3', False)):
        m = rt.get(name)
        if not m:
            fails.append('%s: нет строки RT' % name)
            continue
        if m.group(4) != '1':
            fails.append('%s: encode вернул null' % name)
        if m.group(3) != '1':
            fails.append('%s: альфа не совпала после round-trip' % name)
        if expect_exact and m.group(2) != '0':
            fails.append('%s: ожидался точный round-trip, maxerr=%s'
                         % (name, m.group(2)))
    if rt.get('t2') and not (0 <= int(rt['t2'].group(2)) <= 48):
        fails.append('t2/cc64: градиент ухудшился сильнее порога: maxerr=%s'
                     % rt['t2'].group(2))
    if rt.get('t2b') and not (0 <= int(rt['t2b'].group(2)) <= 170):
        fails.append('t2b/cc16: градиент ухудшился сильнее порога: maxerr=%s'
                     % rt['t2b'].group(2))
    if rt.get('odd') and rt['odd'].group(4) != '1':
        fails.append('нечётный размер не отклонён энкодером')

    print('[2/4] раскладка байтов по файлам t*.qct')
    n = 0
    for line in out.splitlines():
        if line.startswith('HEX '):
            _, name, hexs = line.split()
            with open(os.path.join(WORK, 'res', name + '.qct'), 'wb') as f:
                f.write(bytes.fromhex(hexs))
            n += 1
    print('    файлов: %d' % n)
    if n < 5:
        fails.append('мало HEX-строк: %d' % n)

    print('[3/4] компиляция движка и TestQctLoad')
    srcs = os.path.join(WORK, 'srcs.txt')
    with open(srcs, 'w') as f:
        for root in ('src', 'Utils/src', 'UtilsFPS/src', 'UtilsText/src'):
            for dirpath, _, files in os.walk(os.path.join(SRC, root)):
                for fn in files:
                    if fn.endswith('.java'):
                        f.write(os.path.join(dirpath, fn) + '\n')
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'classes'), '@' + srcs])
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'stub'),
           os.path.join(HERE, 'stub', 'code', 'utils', 'Main.java')])
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-classpath', os.path.join(WORK, 'classes'),
           '-d', os.path.join(WORK, 'load'),
           os.path.join(HERE, 'TestQctLoad.java')])

    print('[4/4] загрузчик движка против QctEncoder.decode')
    # движок ищет ресурсы идиомой (new Object()).getClass()
    # .getResourceAsStream() - на JRE это bootstrap-загрузчик, поэтому
    # каталог с t*.qct добавляем и в bootclasspath
    out = run([JAVA, '-Xbootclasspath/a:' + os.path.join(WORK, 'res'),
               '-cp', os.pathsep.join([
        os.path.join(T, 'lib', 'cldc_1.1.jar'),
        os.path.join(T, 'lib', 'midp_2.0.jar'),
        os.path.join(WORK, 'stub'),      # заглушка Main раньше настоящей
        os.path.join(WORK, 'classes'),
        os.path.join(WORK, 'load'),
        os.path.join(WORK, 'res')]), 'TestQctLoad'])
    for line in out.splitlines():
        print('   ', line)
    if 'ALL LOAD OK' not in out:
        fails.append('загрузчик движка разошёлся с decode()')

    print()
    if fails:
        print('ПРОВАЛЕНО (%d):' % len(fails))
        for f in fails:
            print('  -', f)
        return 1
    print('QCT: round-trip точный на палитрных/бинарных текстурах, альфа '
          'сохраняется, загрузчик движка совпадает с decode() побитово.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
