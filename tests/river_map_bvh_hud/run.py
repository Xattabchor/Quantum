#!/usr/bin/env python3
"""Native contract doubles for river BVH+HUD diagnostic; not phone FPS/raster claims."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--packaged', action='store_true')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_RiverMap_BvhHud.jar')
args = parser.parse_args()

reference = ROOT / 'tests/static_room/run.py'
text = reference.read_text()
prefix, separator, _ = text.partition('parser=argparse.ArgumentParser()')
assert separator
ns = {'__file__': str(reference)}
exec(compile(prefix, str(reference), 'exec'), ns)
stubs = ns['stubs']
# Same resource decoding support as the established room suite.
a = text.index("stubs['javax/microedition/lcdui/Image.java']")
b = text.index('java=os.environ', a)
exec(compile(text[a:b], str(reference), 'exec'), ns)

stubs['javax/microedition/lcdui/Font.java'] = 'package javax.microedition.lcdui;public class Font{public static final int FACE_SYSTEM=0,STYLE_PLAIN=0,SIZE_SMALL=8;private static final Font DEFAULT=new Font();public static Font getDefaultFont(){return DEFAULT;}public static Font getFont(int f,int s,int z){return new Font();}public int getHeight(){return 12;}}'
graphics = 'javax/microedition/lcdui/Graphics.java'
stubs[graphics] = stubs[graphics].replace(
    'public class Graphics {',
    'public class Graphics {private Font font=Font.getDefaultFont();public Font getFont(){return font;}public void setFont(Font f){font=f;}')
old_draw_string = 'public boolean screenTarget;public void drawString(String s,int x,int y,int a){if(javax.microedition.m3g.Graphics3D.target!=null)throw new AssertionError("HUD with native target bound");}'
assert old_draw_string in stubs[graphics]
stubs[graphics] = stubs[graphics].replace(
    old_draw_string,
    'public boolean screenTarget;public int stringCalls;public String lastString;public void drawString(String s,int x,int y,int a){stringCalls++;lastString=s;if(javax.microedition.m3g.Graphics3D.target!=null)throw new AssertionError("HUD with native target bound");}')
stubs['javax/microedition/lcdui/Canvas.java'] = stubs['javax/microedition/lcdui/Canvas.java'].replace(
    'KEY_NUM0=48,', 'KEY_NUM0=48,KEY_NUM1=49,KEY_NUM3=51,')
appearance = 'javax/microedition/m3g/Appearance.java'
stubs[appearance] = stubs[appearance].replace(
    'public class Appearance {', 'public class Appearance {public PolygonMode getPolygonMode(){return polygon;}')

java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = args.jar.resolve() if args.packaged else ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
api = ROOT / 'build/cldc/api.jar'
assert api.exists(), 'prepare Java ME API jars first'
with tempfile.TemporaryDirectory(prefix='quantum-river-bvh-hud-') as directory:
    temp = Path(directory)
    sources = []
    for name, stub_text in stubs.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(stub_text)
        sources.append(path)
    for relative in ('src/code/Gameplay/Map/Room.java', 'UtilsText/src/code/utils/StringTools.java'):
        source_text = (ROOT / relative).read_text()
        old = '(new Object()).getClass().getResourceAsStream(file)'
        assert old in source_text
        path = temp / 'anchors' / Path(relative).name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source_text.replace(old, 'code.Rendering.RiverMapBvhHudTest.class.getResourceAsStream(file)'))
        sources.append(path)
    if not args.packaged:
        sources.extend((ROOT / 'experiments/river-map-bvh-hud/src').rglob('*.java'))
    sources.extend((ROOT / 'tests/river_map_bvh_hud').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', os.pathsep.join([str(base), str(api)]), '-d', str(classes)] + list(map(str, sources)), check=True)
    print('Testing ' + ('PACKAGED CLDC JAR' if args.packaged else 'SOURCE') + '; BVH/HUD/native contracts, NOT phone performance', flush=True)
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true', '-Dm3g.maxTexture=256', '-classpath', os.pathsep.join([str(classes), str(base), str(api)]), 'code.Rendering.RiverMapBvhHudTest'], check=True)
