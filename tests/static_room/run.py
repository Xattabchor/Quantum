#!/usr/bin/env python3
"""Isolated M3G contract doubles. No raster/FPS/device equivalence claims.
The production suite/stubs are not modified to accommodate the new depth path.
"""
import argparse,ast,os,subprocess,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
module=ast.parse((ROOT/'tests/m3g/run.py').read_text())
stubs=ast.literal_eval(next(n.value for n in module.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name)and t.id=='STUBS'for t in n.targets)))
stubs['javax/microedition/lcdui/Graphics.java']=stubs['javax/microedition/lcdui/Graphics.java'].replace('public class Graphics {','public class Graphics {public boolean screenTarget;public void drawString(String s,int x,int y,int a){if(javax.microedition.m3g.Graphics3D.target!=null)throw new AssertionError("HUD with native target bound");}')
stubs['javax/microedition/m3g/VertexBuffer.java']=stubs['javax/microedition/m3g/VertexBuffer.java'].replace('public float scale,uvScale;','public float scale,uvScale;public float[] bias;').replace('positions=a;scale=s;','positions=a;scale=s;bias=b;')
stubs['javax/microedition/m3g/Appearance.java']=stubs['javax/microedition/m3g/Appearance.java'].replace('public class Appearance {','public class Appearance {public CompositingMode getCompositingMode(){return comp;}')
stubs['javax/microedition/m3g/IndexBuffer.java']='package javax.microedition.m3g;public class IndexBuffer {}'
stubs['javax/microedition/m3g/TriangleStripArray.java']=stubs['javax/microedition/m3g/TriangleStripArray.java'].replace('public class TriangleStripArray {','public class TriangleStripArray extends IndexBuffer {')
g=stubs['javax/microedition/m3g/Graphics3D.java']
g=g.replace('public static Camera camera;','public static Camera camera;public static Transform cameraTransform;public static boolean boundDepth;')
g=g.replace('public class Graphics3D {','public class Graphics3D {public static Graphics lastBoundTarget;public void render(VertexBuffer v,IndexBuffer i,Appearance a,Transform t){render(v,(TriangleStripArray)i,a,t);}')
g=g.replace('public int positionCapacity;','public VertexBuffer vertexBuffer;public float[] bias;public int positionCapacity;')
g=g.replace('target=(Graphics)g;binds++;','if(((Graphics)g).screenTarget && Boolean.getBoolean("screen.bind.fail"))throw new IllegalArgumentException("Canvas unsupported");target=(Graphics)g;lastBoundTarget=target;boundDepth=depth;binds++;')
g=g.replace('camera=c;}','camera=c;cameraTransform=t;}')
g=g.replace('!b.colorClear || b.depthClear','!b.colorClear || !b.depthClear || !boundDepth')
g=g.replace('target==null || a.comp.depth','target==null || (a.comp.depthTest && !boundDepth)')
g=g.replace('draw.positionCapacity=v.positions.capacity;','draw.positionCapacity=v.positions.capacity;draw.bias=v.bias==null?null:v.bias.clone();draw.vertexBuffer=v;')
stubs['javax/microedition/m3g/Graphics3D.java']=g
stubs['javax/microedition/m3g/TriangleStripArray.java']=stubs['javax/microedition/m3g/TriangleStripArray.java'].replace('public static int created;', 'public static int created,failAt;').replace('created++;', 'created++;if(created==failAt)throw new OutOfMemoryError("index allocation");')
stubs['javax/microedition/lcdui/Displayable.java']='package javax.microedition.lcdui; public class Displayable {}'
stubs['javax/microedition/lcdui/Canvas.java']='package javax.microedition.lcdui; public abstract class Canvas extends Displayable {public static final int KEY_NUM0=48,KEY_NUM2=50,KEY_NUM4=52,KEY_NUM6=54,KEY_NUM7=55,KEY_NUM8=56,KEY_NUM9=57,KEY_POUND=35; public static int screenWidth=240,screenHeight=340;public int getWidth(){return screenWidth;}public int getHeight(){return screenHeight;}public void setFullScreenMode(boolean b){}public void repaint(){}public void serviceRepaints(){}protected abstract void paint(Graphics g);}'
stubs['javax/microedition/lcdui/Display.java']='package javax.microedition.lcdui;import javax.microedition.midlet.MIDlet;public class Display{public static Display getDisplay(MIDlet m){return new Display();}public void setCurrent(Displayable d){}}'
stubs['javax/microedition/midlet/MIDlet.java']='package javax.microedition.midlet;public abstract class MIDlet{protected abstract void startApp();protected abstract void pauseApp();protected abstract void destroyApp(boolean u);public void notifyDestroyed(){}}'
parser=argparse.ArgumentParser()
parser.add_argument('--jar',type=Path,default=ROOT/'downloads/Quantum_Sym9_DrawCalls.jar')
parser.add_argument('--strips',action='store_true',help='Also validate joined vs original native strips')
parser.add_argument('--present',action='store_true',help='Also validate direct Canvas vs image target viewer')
parser.add_argument('--packaged',action='store_true',help='Test the actual packaged StaticRoom classes, not a recompiled experiment')
args=parser.parse_args()
stubs['javax/microedition/lcdui/Image.java']=stubs['javax/microedition/lcdui/Image.java'].replace('public class Image {', '''public class Image {
 public static java.util.Set<String> resourceReads=new java.util.HashSet<String>();
 public static java.util.Set<String> missingResources=new java.util.HashSet<String>();
 public static Image createImage(String path)throws java.io.IOException {
  resourceReads.add(path);
  java.io.InputStream stream=Image.class.getResourceAsStream(path);
  if(stream==null){missingResources.add(path);throw new java.io.IOException("missing image "+path);}
  java.awt.image.BufferedImage decoded;
  try{decoded=javax.imageio.ImageIO.read(stream);}finally{stream.close();}
  if(decoded==null){missingResources.add(path);throw new java.io.IOException("invalid image "+path);}
  Image result=createImage(decoded.getWidth(),decoded.getHeight());
  result.pixels=decoded.getRGB(0,0,result.w,result.h,null,0,result.w);return result;
 }
''')
java=os.environ.get('JAVA','java');ecj=os.environ.get('ECJ_JAR')
base=args.jar.resolve();api=ROOT/'build/cldc/api.jar'
assert api.exists(),'build API first (scripts/build_cldc.py)'
with tempfile.TemporaryDirectory(prefix='static-room-')as temp:
    temp=Path(temp);sources=[]
    for name,text in stubs.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);sources.append(path)
    # Desktop Object.class is bootstrap-loaded and cannot see MIDlet assets.
    # Re-anchor resource lookup in TEMPORARY dependency copies, not production.
    for relative in ('src/code/Gameplay/Map/Room.java','UtilsText/src/code/utils/StringTools.java'):
        original=ROOT/relative;text=original.read_text()
        old='(new Object()).getClass().getResourceAsStream(file)'
        assert old in text
        path=temp/'anchors'/original.name;path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text.replace(old,'code.Rendering.StaticRoomTest.class.getResourceAsStream(file)'));sources.append(path)
    # Compile the immutable pre-merge source as an independent reference.
    reference=subprocess.check_output(['git','show','ac57d8946257b44b349a85509b9b7b56def852c9:experiments/static-room/src/code/Rendering/StaticRoomMesh.java'],cwd=ROOT,text=True)
    path=temp/'reference'/'StaticRoomMeshReference.java';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(reference.replace('StaticRoomMesh','StaticRoomMeshReference'));sources.append(path)
    if not args.packaged:sources+=list((ROOT/'experiments/static-room/src').rglob('*.java'))
    sources+=list((ROOT/'tests/static_room').glob('*.java'))
    if args.present:
        if not args.packaged:sources+=list((ROOT/'experiments/static-room-present/src').rglob('*.java'))
        sources+=list((ROOT/'tests/static_room_present').glob('*.java'))
    if args.strips:
        if not args.packaged:sources+=list((ROOT/'experiments/static-room-strips/src').rglob('*.java'))
        sources+=list((ROOT/'tests/static_room_strips').glob('*.java'))
    classes=temp/'classes';classes.mkdir()
    compiler=[java,'-jar',ecj,'-8','-proc:none','-nowarn']if ecj else ['javac','-source','8','-target','8']
    subprocess.run(compiler+['-cp',os.pathsep.join(map(str,[base,api])),'-d',str(classes)]+list(map(str,sources)),check=True)
    subprocess.run([java,'-Djava.awt.headless=true','-Dm3g.maxTexture=256','-cp',os.pathsep.join(map(str,[classes,base,api])),
                    'code.Rendering.StaticRoomTest',str(base)],check=True)
    if args.present:
        subprocess.run([java,'-Djava.awt.headless=true','-Dm3g.maxTexture=256','-cp',os.pathsep.join(map(str,[classes,base,api])),
                        'code.Rendering.StaticRoomPresentTest'],check=True)
    if args.strips:
        subprocess.run([java,'-Djava.awt.headless=true','-Dm3g.maxTexture=256','-cp',os.pathsep.join(map(str,[classes,base,api])),
                        'code.Rendering.StaticRoomStripsTest'],check=True)
