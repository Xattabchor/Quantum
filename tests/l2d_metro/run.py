#!/usr/bin/env python3
"""Native contract doubles for indoor metro probe; not phone FPS/raster claims."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--packaged', action='store_true')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_L2D_Metro.jar')
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
stubs['javax/microedition/lcdui/Canvas.java'] = stubs['javax/microedition/lcdui/Canvas.java'].replace(
    'KEY_NUM0=48,', 'KEY_NUM0=48,KEY_NUM1=49,KEY_NUM3=51,')
# Paletted BMP path used by the stock upstream BMPLoader (metro_1.bmp is 8bpp).
image2d = 'javax/microedition/m3g/Image2D.java'
stubs[image2d] = stubs[image2d].replace(
    'RGBA=100,LUMINANCE=97;', 'RGBA=100,LUMINANCE=97,RGB=99; public int getWidth(){return width;} public int getHeight(){return height;} public int getFormat(){return format;}')
stub_ctor = 'public Image2D(int f,int w,int h,byte[] p){'
assert stub_ctor in stubs[image2d]
stubs[image2d] = stubs[image2d].replace(
    stub_ctor,
    'public Image2D(int f,int w,int h,byte[] p,byte[] palette){format=f;width=w;height=h;Graphics3D.uploads++;encoded=p;pixels=new byte[w*h*4];for(int i=0;i<w*h;i++){int idx=p[i]&255,i3=idx*3;pixels[i*4]=palette[i3];pixels[i*4+1]=palette[i3+1];pixels[i*4+2]=palette[i3+2];pixels[i*4+3]=(byte)255;}}\n '
    + stub_ctor)
# L2D engine Nodes.
stubs['javax/microedition/m3g/Node.java'] = 'package javax.microedition.m3g;public class Node {}'
stubs['javax/microedition/m3g/Mesh.java'] = 'package javax.microedition.m3g;public class Mesh extends Node {public VertexBuffer vertices;public IndexBuffer[] indices;public Appearance[] appearances;public Mesh(VertexBuffer v,IndexBuffer i,Appearance a){this(v,new IndexBuffer[]{i},new Appearance[]{a});}public Mesh(VertexBuffer v,IndexBuffer[] i,Appearance[] a){vertices=v;indices=i;appearances=a;}public VertexBuffer getVertexBuffer(){return vertices;}public Appearance getAppearance(int i){return appearances[i];}public int getSubmeshCount(){return indices.length;}public IndexBuffer getIndexBuffer(int i){return indices[i];}}'
stubs['javax/microedition/m3g/Group.java'] = 'package javax.microedition.m3g;public class Group extends Node {public void addChild(Node n){}public void removeChild(Node n){}public int getChildCount(){return 0;}public void setScale(float x,float y,float z){}public void setTranslation(float x,float y,float z){}public void setTransform(Transform t){}}'
stubs['javax/microedition/m3g/SkinnedMesh.java'] = 'package javax.microedition.m3g;public class SkinnedMesh extends Mesh {public SkinnedMesh(VertexBuffer v,IndexBuffer[] i,Appearance[] a,Group g){super(v,i,a);}public void addTransform(Node n,int w,int f,int c){}}'
stubs['javax/microedition/m3g/Transform.java'] = stubs['javax/microedition/m3g/Transform.java'].replace('public void set(float[] m)', 'public void set(Transform t){set(t.matrix);}public void invert(){}public void setIdentity(){matrix=new float[16];matrix[0]=matrix[5]=matrix[10]=matrix[15]=1;}public void postTranslate(float x,float y,float z){}public void postRotate(float a,float x,float y,float z){}public void postScale(float x,float y,float z){}public void set(float[] m)')
stubs['javax/microedition/m3g/Appearance.java'] = stubs['javax/microedition/m3g/Appearance.java'].replace('public class Appearance {','public class Appearance {public PolygonMode getPolygonMode(){return polygon;}')
stubs['javax/microedition/m3g/Graphics3D.java'] = stubs['javax/microedition/m3g/Graphics3D.java'].replace('public class Graphics3D {','public class Graphics3D {public static final int OVERWRITE=16;public static int nodeCalls;public static short[] byteToShort(byte[] b){short[] s=new short[b.length];for(int i2=0;i2<b.length;i2++)s[i2]=b[i2];return s;}public void render(Node n,Transform t){nodeCalls++;if(t!=null)throw new AssertionError("unexpected object transform");Mesh m=(Mesh)n;for(int i=0;i<m.indices.length;i++)render(m.vertices,m.indices[i],m.appearances[i],null);}')
# Stock 3D2 loader stores UV/normal attributes as byte VertexArrays; expand for the double.
g3d = 'javax/microedition/m3g/Graphics3D.java'
assert 'draw.normals=v.normals==null?null:v.normals.shorts.clone();' in stubs[g3d]
stubs[g3d] = stubs[g3d].replace(
    'draw.normals=v.normals==null?null:v.normals.shorts.clone();',
    'draw.normals=v.normals==null?null:(v.normals.shorts!=null?v.normals.shorts.clone():byteToShort(v.normals.bytes));')
stubs[g3d] = stubs[g3d].replace(
    'draw.coords=v.coords==null || v.coords.shorts==null?null:v.coords.shorts.clone();',
    'draw.coords=v.coords==null?null:(v.coords.shorts!=null?v.coords.shorts.clone():byteToShort(v.coords.bytes));')

java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = args.jar.resolve() if args.packaged else ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
api = ROOT / 'build/cldc/api.jar'
assert api.exists(), 'prepare Java ME API jars first'
with tempfile.TemporaryDirectory(prefix='quantum-l2d-metro-') as directory:
    temp = Path(directory)
    sources = []
    for name, stub_text in stubs.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(stub_text)
        sources.append(path)
    if not args.packaged:
        sources.extend((ROOT / 'experiments/l2d-metro/src').rglob('*.java'))
    sources.extend((ROOT / 'tests/l2d_metro').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    engine = temp / 'engine'
    engine.mkdir()
    if not args.packaged:
        subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', str(api), '-d', str(engine)] + list(map(str, (ROOT / 'experiments/l2d-map/upstream/src').rglob('*.java'))), check=True)
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', os.pathsep.join([str(base), str(engine), str(api)]), '-d', str(classes)] + list(map(str, sources)), check=True)
    print('Testing ' + ('PACKAGED CLDC JAR' if args.packaged else 'SOURCE') + '; geometry/state contracts, NOT phone performance', flush=True)
    assets = ROOT / 'experiments/l2d-metro/assets'
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true', '-Dm3g.maxTexture=256',
                    '-classpath', os.pathsep.join([str(classes), str(base), str(engine), str(assets), str(api)]),
                    'code.Rendering.L2DMetroTest'], check=True)
