#!/usr/bin/env python3
"""Native contract doubles, not rasterization or phone FPS measurements."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--packaged', action='store_true')
parser.add_argument('--jar', type=Path, default=ROOT / 'downloads/Quantum_L2D_Map.jar')
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
stubs['javax/microedition/m3g/Node.java'] = 'package javax.microedition.m3g;public class Node {}'
stubs['javax/microedition/m3g/Mesh.java'] = 'package javax.microedition.m3g;public class Mesh extends Node {public VertexBuffer vertices;public IndexBuffer[] indices;public Appearance[] appearances;public Mesh(VertexBuffer v,IndexBuffer i,Appearance a){this(v,new IndexBuffer[]{i},new Appearance[]{a});}public Mesh(VertexBuffer v,IndexBuffer[] i,Appearance[] a){vertices=v;indices=i;appearances=a;}public VertexBuffer getVertexBuffer(){return vertices;}public Appearance getAppearance(int i){return appearances[i];}public int getSubmeshCount(){return indices.length;}public IndexBuffer getIndexBuffer(int i){return indices[i];}}'
stubs['javax/microedition/m3g/Group.java'] = 'package javax.microedition.m3g;public class Group extends Node {public void addChild(Node n){}public void removeChild(Node n){}public int getChildCount(){return 0;}public void setScale(float x,float y,float z){}public void setTranslation(float x,float y,float z){}public void setTransform(Transform t){}}'
stubs['javax/microedition/m3g/SkinnedMesh.java'] = 'package javax.microedition.m3g;public class SkinnedMesh extends Mesh {public SkinnedMesh(VertexBuffer v,IndexBuffer[] i,Appearance[] a,Group g){super(v,i,a);}public void addTransform(Node n,int w,int f,int c){}}'
stubs['javax/microedition/m3g/Transform.java'] = stubs['javax/microedition/m3g/Transform.java'].replace('public void set(float[] m)', 'public void set(Transform t){set(t.matrix);}public void invert(){}public void setIdentity(){matrix=new float[16];matrix[0]=matrix[5]=matrix[10]=matrix[15]=1;}public void postTranslate(float x,float y,float z){}public void postRotate(float a,float x,float y,float z){}public void postScale(float x,float y,float z){}public void set(float[] m)')
stubs['javax/microedition/m3g/Appearance.java'] = stubs['javax/microedition/m3g/Appearance.java'].replace('public class Appearance {','public class Appearance {public PolygonMode getPolygonMode(){return polygon;}')
stubs['javax/microedition/m3g/Graphics3D.java'] = stubs['javax/microedition/m3g/Graphics3D.java'].replace('public class Graphics3D {','public class Graphics3D {public static final int OVERWRITE=16;public static int nodeCalls;public void render(Node n,Transform t){nodeCalls++;if(t!=null)throw new AssertionError("unexpected object transform");Mesh m=(Mesh)n;for(int i=0;i<m.indices.length;i++)render(m.vertices,m.indices[i],m.appearances[i],null);}')
java = os.environ.get('JAVA', 'java')
ecj = os.environ.get('ECJ_JAR')
base = args.jar.resolve() if args.packaged else ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
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
        path.write_text(text.replace(old, 'code.Rendering.L2DMapTest.class.getResourceAsStream(file)'))
        sources.append(path)
    if not args.packaged:
        sources.extend((ROOT / 'experiments/l2d-map/src').rglob('*.java'))
    sources.extend((ROOT / 'tests/l2d_map').glob('*.java'))
    classes = temp / 'classes'
    classes.mkdir()
    compiler = [java, '-jar', ecj, '-8', '-proc:none', '-nowarn'] if ecj else ['javac', '-source', '8', '-target', '8']
    engine=temp / 'engine'
    engine.mkdir()
    if not args.packaged:
        subprocess.run(compiler + ['-encoding','UTF-8','-classpath',str(api),'-d',str(engine)] + list(map(str,(ROOT / 'experiments/l2d-map/upstream/src').rglob('*.java'))),check=True)
    subprocess.run(compiler + ['-encoding', 'UTF-8', '-classpath', os.pathsep.join([str(base), str(engine), str(api)]), '-d', str(classes)] + list(map(str, sources)), check=True)
    print('Testing ' + ('PACKAGED CLDC JAR' if args.packaged else 'SOURCE') + '; geometry/state contracts, NOT phone performance', flush=True)
    subprocess.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true', '-Dm3g.maxTexture=256', '-classpath', os.pathsep.join([str(classes), str(base), str(engine), str(ROOT / 'experiments/l2d-map/assets'), str(api)]), 'code.Rendering.L2DMapTest'], check=True)
