#!/usr/bin/env python3
"""Standalone deterministic API doubles; no rasterization/device speed claims."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--packaged',action='store_true');a=p.parse_args()
STUBS={
'javax/microedition/midlet/MIDlet.java':'''package javax.microedition.midlet;public abstract class MIDlet{public boolean destroyed;protected abstract void startApp();protected abstract void pauseApp();protected abstract void destroyApp(boolean b);public void notifyDestroyed(){destroyed=true;}}''',
'javax/microedition/lcdui/Displayable.java':'''package javax.microedition.lcdui;public class Displayable{}''',
'javax/microedition/lcdui/Display.java':'''package javax.microedition.lcdui;import javax.microedition.midlet.MIDlet;public class Display{static final Display D=new Display();public static Display getDisplay(MIDlet m){return D;}public void setCurrent(Displayable d){}}''',
'javax/microedition/lcdui/Font.java':'''package javax.microedition.lcdui;public class Font{public static final int FACE_SYSTEM=0,STYLE_PLAIN=0,SIZE_SMALL=8;public static Font getFont(int f,int s,int z){return new Font();}public int getHeight(){return 12;}}''',
'javax/microedition/lcdui/Graphics.java':'''package javax.microedition.lcdui;
public class Graphics{public final boolean screen;public int fills,strings,copies;public int x,y,cx,cy,cw=240,ch=320;public Font font=new Font();
public Graphics(boolean b){screen=b;}public Font getFont(){return font;}public void setFont(Font f){font=f;}public int getTranslateX(){return x;}public int getTranslateY(){return y;}public int getClipX(){return cx;}public int getClipY(){return cy;}public int getClipWidth(){return cw;}public int getClipHeight(){return ch;}public void translate(int a,int b){x+=a;y+=b;}public void setClip(int a,int b,int c,int d){cx=a;cy=b;cw=c;ch=d;}public void setColor(int c){}
private void free(){if(javax.microedition.m3g.Graphics3D.target!=null)throw new AssertionError("MIDP with M3G target bound");}
public void fillRect(int a,int b,int c,int d){free();fills++;}public void drawString(String s,int a,int b,int anchor){free();strings++;}
public void drawImage(Image i,int a,int b,int anchor){free();if(Boolean.getBoolean("copy.fail"))throw new OutOfMemoryError("copy failure");copies++;}}
''',
'javax/microedition/lcdui/Image.java':'''package javax.microedition.lcdui;public class Image{public static int created;private final Graphics g=new Graphics(false);public static Image createImage(int w,int h){if(w<1||h<1)throw new IllegalArgumentException();created++;return new Image();}public Graphics getGraphics(){return g;}}''',
'javax/microedition/lcdui/Canvas.java':'''package javax.microedition.lcdui;
public abstract class Canvas extends Displayable{
public static final int KEY_NUM0=48,KEY_NUM1=49,KEY_NUM2=50,KEY_NUM3=51,KEY_NUM5=53,KEY_NUM7=55,KEY_NUM9=57,KEY_POUND=35;
public static int width=240,height=320;public static volatile boolean auto;
public volatile int requests,services,completed;private boolean queued,active;private Thread event;
public int getWidth(){return width;}public int getHeight(){return height;}public void setFullScreenMode(boolean b){}protected abstract void paint(Graphics g);
public synchronized void repaint(){if(!auto)return;requests++;queued=true;
 if(event==null){event=new Thread(new Runnable(){public void run(){while(true){synchronized(Canvas.this){try{while(!queued)Canvas.this.wait();}catch(InterruptedException e){return;}queued=false;active=true;}
 try{paint(new Graphics(true));}finally{synchronized(Canvas.this){active=false;completed++;Canvas.this.notifyAll();}}}}},"MIDP event double");event.setDaemon(true);event.start();}notifyAll();}
public synchronized void serviceRepaints(){services++;if(!auto)return;try{while(queued||active)wait();}catch(InterruptedException e){throw new RuntimeException(e);}}
public synchronized void awaitPaints(int n,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(completed<n&&System.currentTimeMillis()<end)wait(Math.max(1,end-System.currentTimeMillis()));if(completed<n)throw new AssertionError("paint deadlock "+completed+"/"+n);}
}''',
'javax/microedition/m3g/Transform.java':'''package javax.microedition.m3g;public class Transform{public float[] m=new float[16];public Transform(){m[0]=m[5]=m[10]=m[15]=1;}public void set(float[] f){m=f.clone();}}''',
'javax/microedition/m3g/Camera.java':'''package javax.microedition.m3g;public class Camera{public void setGeneric(Transform t){}}''',
'javax/microedition/m3g/Background.java':'''package javax.microedition.m3g;public class Background{public boolean color,depth;public int rgb;public void setColor(int c){rgb=c;}public void setColorClearEnable(boolean b){color=b;}public void setDepthClearEnable(boolean b){depth=b;}}''',
'javax/microedition/m3g/Graphics3D.java':'''package javax.microedition.m3g;import java.util.*;import javax.microedition.lcdui.Graphics;
public class Graphics3D{private static final Graphics3D G=new Graphics3D();public static Graphics target;public static int instances,binds,releases,clears;public static boolean boundDepth;public static final List<String> trace=new ArrayList<String>();
public static Graphics3D getInstance(){instances++;return G;}private static void fail(String name){if(Boolean.getBoolean(name))throw new IllegalArgumentException(name);}
public void bindTarget(Object o,boolean depth,int hints){fail("bind.fail");Graphics g=(Graphics)o;if(g.screen)fail("direct.fail");if(target!=null)throw new AssertionError("nested bind");target=g;boundDepth=depth;binds++;trace.add("bind:"+depth+":"+g.screen);}
public void setViewport(int x,int y,int w,int h){fail("viewport.fail");trace.add("viewport");}public void setCamera(Camera c,Transform t){fail("camera.fail");trace.add("camera");}public void resetLights(){fail("lights.fail");trace.add("lights");}
public void clear(Background b){fail("clear.fail");if(target==null||b.depth&&!boundDepth)throw new AssertionError("clear target/depth");if(b.rgb!=0x203040)throw new AssertionError("background");clears++;trace.add("clear:"+b.color+":"+b.depth);}
public void releaseTarget(){if(target==null)throw new AssertionError("release without bind");target=null;releases++;trace.add("release");fail("release.fail");}
}'''
}
java=os.environ.get('JAVA','java');ecj=os.environ.get('ECJ_JAR');jar=ROOT/'downloads/Quantum_EmptyFrame_Test.jar'
with tempfile.TemporaryDirectory(prefix='quantum-empty-frame-') as d:
    temp=Path(d);sources=[]
    for n,s in STUBS.items():
        path=temp/n;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(s);sources.append(path)
    if not a.packaged:sources.extend((ROOT/'experiments/empty-frame/src').rglob('*.java'))
    sources.extend((ROOT/'tests/empty_frame').glob('*.java'));classes=temp/'classes';classes.mkdir()
    compiler=[java,'-jar',ecj,'-8','-proc:none','-nowarn'] if ecj else ['javac','-source','8','-target','8']
    subprocess.run(compiler+['-encoding','UTF-8','-classpath',str(jar),'-d',str(classes)]+list(map(str,sources)),check=True)
    print('Testing '+('PACKAGED CLDC' if a.packaged else 'SOURCE')+'; NOT handset timings',flush=True)
    subprocess.run([java,'-Xverify:all','-classpath',os.pathsep.join([str(classes),str(jar)]),'code.Diagnostics.EmptyFrameTest'],check=True,timeout=30)
