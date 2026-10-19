#!/usr/bin/env python3
"""Source/packaged native-contract tests. No rasterization or handset FPS claims."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--packaged', action='store_true')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_PolyFill_Test.jar')
args = parser.parse_args()

# Reuse the established depth-capable doubles, not their CLI/build or game tests.
# The prefix only defines/extends the stubs. Keep historical suites unchanged.
reference = ROOT / 'tests/static_room/run.py'
prefix, separator, _ = reference.read_text().partition('parser=argparse.ArgumentParser()')
assert separator, 'static-room stub definitions moved; update this harness explicitly'
namespace = {'__file__': str(reference)}
exec(compile(prefix, str(reference), 'exec'), namespace)
stubs = namespace['stubs']
canvas = 'javax/microedition/lcdui/Canvas.java'
stubs[canvas] = stubs[canvas].replace('KEY_NUM0=48,', 'KEY_NUM0=48,KEY_NUM1=49,KEY_NUM3=51,')
camera = 'javax/microedition/m3g/Camera.java'
stubs[camera] = stubs[camera].replace('t.matrix[14]!=-1 || t.matrix[11]==0', 't.matrix[14]!=0 || t.matrix[15]!=1 || t.matrix[10]!=-2 || t.matrix[11]!=-2')
stubs['javax/microedition/lcdui/Font.java'] = 'package javax.microedition.lcdui;public class Font{public static final int FACE_SYSTEM=0,STYLE_PLAIN=0,SIZE_SMALL=8;private static final Font DEFAULT=new Font();public static Font getDefaultFont(){return DEFAULT;}public static Font getFont(int f,int s,int z){return new Font();}public int getHeight(){return 12;}}'
graphics = 'javax/microedition/lcdui/Graphics.java'
stubs[graphics] = stubs[graphics].replace('public class Graphics {', 'public class Graphics {private Font font=Font.getDefaultFont();public Font getFont(){return font;}public void setFont(Font f){font=f;}')
java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = args.jar.resolve() if args.packaged else ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
with tempfile.TemporaryDirectory(prefix='quantum-poly-fill-') as directory:
    temp = Path(directory)
    sources = []
    for name, text in stubs.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(path)
    if not args.packaged:
        sources.extend((ROOT / 'experiments/poly-fill/src').rglob('*.java'))
    sources.extend((ROOT / 'tests/poly_fill').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', str(base), '-d', str(classes)] + list(map(str, sources)), check=True)
    print('Testing ' + ('PACKAGED CLDC JAR' if args.packaged else 'SOURCE') + '; analytical/contracts only', flush=True)
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-classpath', os.pathsep.join([str(classes), str(base)]), 'code.Rendering.PolyFillTest'], check=True)
