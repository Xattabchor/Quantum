#!/usr/bin/env python3
"""Native contract doubles for river upscale-delivery diagnostic; not phone FPS/raster claims."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--packaged', action='store_true')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_RiverMap_Upscale.jar')
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

# Real-API packaged classes bind getWidth/getHeight to Displayable (inherited);
# source-mode doubles redeclare them on Canvas. Reflection-inherited members keep both.
displayable = 'javax/microedition/lcdui/Displayable.java'
assert stubs[displayable] == 'package javax.microedition.lcdui; public class Displayable {}'
stubs[displayable] = 'package javax.microedition.lcdui; public class Displayable {public int getWidth(){return Canvas.screenWidth;}public int getHeight(){return Canvas.screenHeight;}}'
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
assert 'drawRGBCalls++;\n  blit(p,o,s,x,y,w,h,a);' in stubs[graphics]
stubs[graphics] = stubs[graphics].replace(
    'drawRGBCalls++;\n  blit(p,o,s,x,y,w,h,a);',
    'drawRGBCalls++;rgbX=x;rgbY=y;rgbW=w;rgbH=h;rgbA=a;\n  blit(p,o,s,x,y,w,h,a);')
stubs[graphics] = stubs[graphics].replace(
    'public static int drawRGBCalls,drawImageCalls;',
    'public static int drawRGBCalls,drawImageCalls;public int rgbX,rgbY,rgbW,rgbH;public boolean rgbA;')
stubs['javax/microedition/lcdui/Canvas.java'] = stubs['javax/microedition/lcdui/Canvas.java'].replace(
    'KEY_NUM0=48,', 'KEY_NUM0=48,KEY_NUM1=49,KEY_NUM3=51,')
appearance = 'javax/microedition/m3g/Appearance.java'
stubs[appearance] = stubs[appearance].replace(
    'public class Appearance {', 'public class Appearance {public PolygonMode getPolygonMode(){return polygon;}')

polygon_mode = 'javax/microedition/m3g/PolygonMode.java'
assert 'SHADE_SMOOTH=165' in stubs[polygon_mode]
stubs[polygon_mode] = stubs[polygon_mode].replace('SHADE_SMOOTH=165', 'SHADE_FLAT=164,SHADE_SMOOTH=165')
assert 'public void setShading(int n){}' in stubs[polygon_mode]
stubs[polygon_mode] = stubs[polygon_mode].replace(
    'public void setShading(int n){}',
    'public int shading=SHADE_SMOOTH; public void setShading(int n){shading=n;}')
texture2d = 'javax/microedition/m3g/Texture2D.java'
assert 'public int function,blendColor,filter,wrapS,wrapT;' in stubs[texture2d]
stubs[texture2d] = stubs[texture2d].replace(
    'public int function,blendColor,filter,wrapS,wrapT;',
    'public int function,blendColor,filter,levelFilter=FILTER_BASE_LEVEL,wrapS,wrapT;')
assert 'public void setFiltering(int a,int b){filter=b;}' in stubs[texture2d]
stubs[texture2d] = stubs[texture2d].replace(
    'public void setFiltering(int a,int b){filter=b;}',
    'public void setFiltering(int a,int b){levelFilter=a;filter=b;}')
g3d = 'javax/microedition/m3g/Graphics3D.java'
assert 'textureAlpha,filter,colorComponents' in stubs[g3d]
stubs[g3d] = stubs[g3d].replace(
    'textureAlpha,filter,colorComponents',
    'textureAlpha,filter,levelFilter,shading,colorComponents')
assert 'draw.filter=a.tex==null?0:a.tex.filter;' in stubs[g3d]
stubs[g3d] = stubs[g3d].replace(
    'draw.filter=a.tex==null?0:a.tex.filter;',
    'draw.filter=a.tex==null?0:a.tex.filter;draw.levelFilter=a.tex==null?0:a.tex.levelFilter;draw.shading=a.polygon==null?PolygonMode.SHADE_SMOOTH:a.polygon.shading;')
java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = args.jar.resolve() if args.packaged else ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
api = ROOT / 'build/cldc/api.jar'
assert api.exists(), 'prepare Java ME API jars first'
with tempfile.TemporaryDirectory(prefix='quantum-river-upscale-') as directory:
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
        path.write_text(source_text.replace(old, 'code.Rendering.RiverUpscaleTest.class.getResourceAsStream(file)'))
        sources.append(path)
    if not args.packaged:
        sources.extend((ROOT / 'experiments/river-pixel/src').rglob('*.java'))
        sources.extend((ROOT / 'experiments/river-upscale/src').rglob('*.java'))
    sources.extend((ROOT / 'tests/river_upscale').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', os.pathsep.join([str(base), str(api)]), '-d', str(classes)] + list(map(str, sources)), check=True)
    print('Testing ' + ('PACKAGED CLDC JAR' if args.packaged else 'SOURCE') + '; upscale/native contracts, NOT phone performance', flush=True)
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true', '-Dm3g.maxTexture=256', '-classpath', os.pathsep.join([str(classes), str(base), str(api)]), 'code.Rendering.RiverUpscaleTest'], check=True)
