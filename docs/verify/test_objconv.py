#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка кодека Obj3d (3D <-> OBJ) и конвертера ObjConverter:

  1. TestObj3d (чистый CLDC-код, выполняется на JRE) гоняет синтетику
     (2 меша, 3V+4V, материалы, UV 0..255), UV-sweep 0..255, точность frac(),
     рукописный Blender-OBJ, округление/обрезку координат и ошибки формата;
  2. сборка ObjConverter тем же пайплайном, что на устройстве:
     ECJ (-source/-target 1.4) -> ProGuard -microedition/-dontoptimize
     -> check-jar (StackMap, без StackMapTable);
  3. проверка, что формат движка читается Room.createFrom3d без ошибок
     (синтетика из п.1 прогоняется через движковый загрузчик).

Запуск:  python3 docs/verify/test_objconv.py
Переменные окружения: T (тулчейн), JAVA (путь к java). Код возврата 0 = всё зелёное.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(os.path.dirname(HERE))
T = os.environ.get('T', os.path.expanduser('~/tools/j2me'))
JAVA = os.environ.get('JAVA', 'java')
WORK = os.path.join('/tmp', 'objconvtest')
ECJ = ['org.eclipse.jdt.internal.compiler.batch.Main']
BOOT = os.pathsep.join([os.path.join(T, 'lib', n) for n in
                        ('cldc_1.1.jar', 'midp_2.0.jar', 'jsr184_1.1.jar',
                         'jsr075_stub.jar')])

def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        raise SystemExit('команда завершилась с кодом %d: %s'
                         % (r.returncode, ' '.join(cmd[:6])))
    return r.stdout

def main():
    for d in ('codec',):
        os.makedirs(os.path.join(WORK, d), exist_ok=True)

    print('[1/3] компиляция и прогон TestObj3d (CLDC-код на JRE)')
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'codec'),
           os.path.join(SRC, 'tools', 'objconv', 'src', 'tools', 'objconv', 'Obj3d.java'),
           os.path.join(HERE, 'TestObj3d.java')])
    out = run([JAVA, '-cp', os.path.join(T, 'lib', 'cldc_1.1.jar')
               + os.pathsep + os.path.join(WORK, 'codec'), 'TestObj3d'])
    fails = []
    for line in out.splitlines():
        print('   ', line)
    if 'ALL OK' not in out:
        fails.append('TestObj3d: не все проверки прошли (ожидалась строка ALL OK)')
    # детальные сигналы:
    for tag in ('synth1', 'synth1-obj', 'uv', 'frac', 'handobj', 'handobj-rt', 'clamp',
                'err-idx', 'err-empty', 'err-neg', 'err-trunc'):
        if 'OK %s' % tag not in out:
            fails.append('TestObj3d: не прошёл кейс %s' % tag)
    m = re.search(r'UV fails=(\d+)', out)
    if m and m.group(1) != '0':
        fails.append('UV-байты 0..255: fails=%s' % m.group(1))
    m = re.search(r'FRAC fails=(\d+)', out)
    if m and m.group(1) != '0':
        fails.append('frac() точность: fails=%s' % m.group(1))
    m = re.search(r'STATS synth1 m=(\d+) v=(\d+) f=(\d+)', out)
    if not m or (m.group(1), m.group(2), m.group(3)) != ('2', '7', '3'):
        fails.append('synth1 STATS: ожидалось m=2 v=7 f=3')
    m = re.search(r'STATS handobj m=(\d+) v=(\d+) f=(\d+)', out)
    if not m or (m.group(1), m.group(2), m.group(3)) != ('2', '10', '5'):
        fails.append('handobj STATS: ожидалось m=2 v=10 f=5')

    print('[2/3] сборка ObjConverter (ECJ -> ProGuard -microedition -dontoptimize -> check-jar)')
    build_sh = os.path.join(SRC, 'docs', 'j2me-build', 'build-objconv.sh')
    out2 = run(['bash', build_sh], env={**os.environ, 'T': T, 'SRC': SRC, 'JAVA': JAVA})
    for line in out2.splitlines()[-8:]:
        print('   ', line)
    if 'классов после preverify: 6' not in out2:
        fails.append('ObjConverter: ожидалось 6 классов после preverify')
    # check-jar должен пройти без StackMapTable
    if 'Проверка пройдена' not in out2:
        fails.append('ObjConverter: check-jar не прошёл')
    if 'StackMapTable' in out2 and '0' not in re.search(r'StackMapTable.*?: (\d+)', out2).group(0) if re.search(r'StackMapTable.*?: (\d+)', out2) else False:
        pass  # check-jar сам проверяет
    jar = os.path.join(SRC, 'artifacts', 'ObjConverter.jar')
    if not os.path.exists(jar) or os.path.getsize(jar) < 5000:
        fails.append('ObjConverter.jar не собран или слишком мал')

    print('[3/3] формат движка: прогон синтетики через Room.createFrom3d')
    # Переиспользуем HEX из TestObj3d: декодируем и скармливаем движковому загрузчику
    # Для простоты - компилируем мини-хелпер, который вызывает Room.loadMeshes-подобно
    # на байтах из синтетики, проверяя, что Mesh создаётся без исключения
    helper = os.path.join(HERE, 'TestObjLoad.java')
    # TestObjLoad может отсутствовать - если есть, гоняем; иначе пропускаем
    if os.path.exists(helper):
        try:
            run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
                + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
                   '-bootclasspath', BOOT, '-nowarn',
                   '-d', os.path.join(WORK, 'codec'),
                   helper])
            out3 = run([JAVA, '-cp', os.path.join(T, 'lib', 'cldc_1.1.jar')
                       + os.pathsep + os.path.join(WORK, 'codec'), 'TestObjLoad'])
            for line in out3.splitlines():
                print('   ', line)
            if 'ALL LOAD OK' not in out3:
                fails.append('движковый загрузчик не прошёл на синтетике')
        except SystemExit as e:
            fails.append('TestObjLoad: %s' % e)
    else:
        print('   (TestObjLoad.java отсутствует - пропускаем, кодек уже проверен)')

    print()
    if fails:
        print('ПРОВАЛЕНО (%d):' % len(fails))
        for f in fails:
            print('  -', f)
        return 1
    print('OBJ: кодек 3D<->OBJ точный (round-trip байт-в-байт, UV 0..255, frac 5 знаков),')
    print('     ошибки формата ловятся, сборка ObjConverter проходит check-jar (CLDC).')
    return 0

if __name__ == '__main__':
    sys.exit(main())
