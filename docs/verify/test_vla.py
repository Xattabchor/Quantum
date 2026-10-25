#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка кодека VlaCodec (vla 13 цвет <-> 9 серый) и сборки VlaConverter:
 1. TestVla (CLDC-код на JRE) - синтетика, round-trip, ошибки
 2. сборка VlaConverter тем же пайплайном что на устройстве (-dontoptimize, check-jar)
"""
import os, re, subprocess, sys
HERE=os.path.dirname(os.path.abspath(__file__)); SRC=os.path.dirname(os.path.dirname(HERE))
T=os.environ.get('T', os.path.expanduser('~/tools/j2me')); JAVA=os.environ.get('JAVA','java')
WORK=os.path.join('/tmp','vlaconvtest'); ECJ=['org.eclipse.jdt.internal.compiler.batch.Main']
BOOT=os.pathsep.join([os.path.join(T,'lib',n) for n in ('cldc_1.1.jar','midp_2.0.jar','jsr184_1.1.jar','jsr075_stub.jar')])
def run(cmd,**kw):
    r=subprocess.run(cmd,capture_output=True,text=True,**kw)
    if r.returncode!=0:
        print(r.stdout[-4000:]); print(r.stderr[-4000:]); raise SystemExit('код %d: %s'%(r.returncode,' '.join(cmd[:6])))
    return r.stdout
def main():
    os.makedirs(os.path.join(WORK,'codec'),exist_ok=True)
    print('[1/2] компиляция и прогон TestVla (CLDC на JRE)')
    run([JAVA,'-cp',os.path.join(T,'lib','ecj.jar')]+ECJ+['-source','1.4','-target','1.4','-encoding','UTF-8','-bootclasspath',BOOT,'-nowarn','-d',os.path.join(WORK,'codec'),os.path.join(SRC,'tools/vlaconv/src/tools/vlaconv/VlaCodec.java'),os.path.join(HERE,'TestVla.java')])
    out=run([JAVA,'-cp',os.path.join(T,'lib','cldc_1.1.jar')+os.pathsep+os.path.join(WORK,'codec'),'TestVla'])
    fails=[]
    for l in out.splitlines(): print('   ',l)
    if 'ALL OK' not in out: fails.append('TestVla не ALL OK')
    for tag in ('gray-rt','gray-color-size','color-gray','cross-gray-already','cross-gray-color-size','cross-gray-color-rt','rnd-rt','err-trunc','err-extra'):
        if 'OK '+tag not in out: fails.append('TestVla кейс '+tag+' не OK')
    print('[2/2] сборка VlaConverter (ECJ->ProGuard -dontoptimize -> check-jar)')
    build_sh=os.path.join(SRC,'docs/j2me-build/build-vlaconv.sh')
    out2=run(['bash',build_sh],env={**os.environ,'T':T,'SRC':SRC,'JAVA':JAVA})
    for l in out2.splitlines()[-8:]: print('   ',l)
    if 'классов после preverify: 4' not in out2: fails.append('VlaConverter классов !=6')
    if 'Проверка пройдена' not in out2: fails.append('VlaConverter check-jar не прошёл')
    jar=os.path.join(SRC,'artifacts','VlaConverter.jar')
    if not os.path.exists(jar) or os.path.getsize(jar)<5000: fails.append('VlaConverter.jar не собран')
    print()
    if fails:
        print('ПРОВАЛЕНО (%d):'%len(fails))
        for f in fails: print('  -',f)
        return 1
    print('VLA: цвет<->серый (R+G+B)/3 точный, сжатый формат цветного учтён, round-trip серого стабилен, сборка VlaConverter check-jar зелёный.')
    return 0
if __name__=='__main__': sys.exit(main())
