#!/usr/bin/env python3
"""Native contract doubles, not rasterization or phone FPS measurements."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, default=ROOT / 'experiments/l2d-map/assets/l2d-map')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_StaticRoom_Chunks.jar')
args = parser.parse_args()
reference = ROOT / 'tests/static_room/run.py'
text = reference.read_text()
prefix, separator, _ = text.partition('parser=argparse.ArgumentParser()')
assert separator
ns = {'__file__': str(reference)}
exec(compile(prefix, str(reference), 'exec'), ns)
stubs = ns['stubs']
# Same resource decoding support as the established room suite; no CLI/game
# suite is executed, and production sources/JARs are never re-anchored.
a = text.index("stubs['javax/microedition/lcdui/Image.java']")
b = text.index("java=os.environ", a)
exec(compile(text[a:b], str(reference), 'exec'), ns)
stubs['javax/microedition/lcdui/Font.java'] = 'package javax.microedition.lcdui;public class Font{public static final int FACE_SYSTEM=0,STYLE_PLAIN=0,SIZE_SMALL=8;private static final Font DEFAULT=new Font();public static Font getDefaultFont(){return DEFAULT;}public static Font getFont(int f,int s,int z){return new Font();}public int getHeight(){return 12;}}'
graphics = 'javax/microedition/lcdui/Graphics.java'
stubs[graphics] = stubs[graphics].replace('public class Graphics {', 'public class Graphics {private Font font=Font.getDefaultFont();public Font getFont(){return font;}public void setFont(Font f){font=f;}')
stubs['javax/microedition/lcdui/Canvas.java'] = stubs['javax/microedition/lcdui/Canvas.java'].replace('KEY_NUM0=48,', 'KEY_NUM0=48,KEY_NUM1=49,KEY_NUM3=51,')
java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
api = ROOT / 'build/cldc/api.jar'
assert api.exists(), 'prepare Java ME API jars first'
with tempfile.TemporaryDirectory(prefix='quantum-chunks-') as directory:
    temp = Path(directory)
    sources = []
    for name, text in stubs.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(path)
    for relative in ('src/code/Gameplay/Map/Room.java', 'UtilsText/src/code/utils/StringTools.java'):
        text = (ROOT / relative).read_text()
        old = '(new Object()).getClass().getResourceAsStream(file)'
        assert old in text
        path = temp / 'anchors' / Path(relative).name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text.replace(old, 'code.Rendering.L2DMapExport.class.getResourceAsStream(file)'))
        sources.append(path)
    sources.extend((ROOT / 'tools/l2d-map').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', os.pathsep.join([str(base), str(api)]), '-d', str(classes)] + list(map(str, sources)), check=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true', '-Dm3g.maxTexture=256', '-classpath', os.pathsep.join([str(classes), str(base), str(api)]), 'code.Rendering.L2DMapExport', str(args.output_dir.resolve())], check=True)
