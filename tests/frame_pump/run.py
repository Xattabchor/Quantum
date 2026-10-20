#!/usr/bin/env python3
"""API-double loop/lifecycle checks, not native presentation/performance evidence."""
import argparse
import ast
import os
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--packaged',action='store_true');a=p.parse_args()
# Reuse the immutable previous diagnostic's API doubles without running its CLI.
module=ast.parse((ROOT/'tests/empty_frame/run.py').read_text())
stubs=next(ast.literal_eval(n.value) for n in module.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='STUBS' for t in n.targets))
stubs['javax/microedition/lcdui/Canvas.java']=stubs['javax/microedition/lcdui/Canvas.java'].replace('public int getWidth()', 'public boolean shown;public boolean isShown(){return shown;}public int getWidth()')
stubs['javax/microedition/lcdui/Display.java']='''package javax.microedition.lcdui;
import javax.microedition.midlet.MIDlet;import java.lang.reflect.*;
public class Display{static final Display D=new Display();public Displayable current;
public static Display getDisplay(MIDlet m){return D;}
static void notify(Canvas c,String n){try{Method m=c.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(c);}catch(Exception e){throw new RuntimeException(e);}}
public void setCurrent(Displayable d){if(d==current)return;Displayable old=current;current=d;if(old instanceof Canvas){((Canvas)old).shown=false;notify((Canvas)old,"hideNotify");}if(d instanceof Canvas){((Canvas)d).shown=true;notify((Canvas)d,"showNotify");}}
public void setCurrent(Alert a,Displayable next){setCurrent(a);}}
'''
stubs['javax/microedition/lcdui/Alert.java']='''package javax.microedition.lcdui;public class Alert extends Displayable{public static final int FOREVER=-2;public Alert(String a,String b,Image c,AlertType d){}public void setTimeout(int n){}}'''
stubs['javax/microedition/lcdui/AlertType.java']='''package javax.microedition.lcdui;public class AlertType{public static final AlertType ERROR=new AlertType();}'''
stubs['javax/microedition/lcdui/game/GameCanvas.java']='''package javax.microedition.lcdui.game;
import javax.microedition.lcdui.*;
public class GameCanvas extends Canvas{private final Graphics g=new Graphics(true);public volatile int flushes;public volatile Thread owner;public static volatile boolean fail;
protected GameCanvas(boolean suppress){if(suppress)throw new AssertionError("keys suppressed");}
protected Graphics getGraphics(){return g;}protected void paint(Graphics g){throw new AssertionError("GameCanvas paint loop used");}
public void flushGraphics(){if(fail)throw new IllegalStateException("unsupported flush");if(owner!=null&&owner!=Thread.currentThread())throw new AssertionError("multiple flush threads");owner=Thread.currentThread();flushes++;Thread.yield();}}
'''
java=os.environ.get('JAVA','java');ecj=os.environ.get('ECJ_JAR');jar=ROOT/'downloads/Quantum_FramePump_Test.jar'
with tempfile.TemporaryDirectory(prefix='quantum-frame-pump-') as d:
    temp=Path(d);sources=[]
    for n,s in stubs.items():
        path=temp/n;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(s);sources.append(path)
    if not a.packaged:sources.extend((ROOT/'experiments/frame-pump/src').rglob('*.java'))
    sources.extend((ROOT/'tests/frame_pump').glob('*.java'))
    classes=temp/'classes';classes.mkdir()
    compiler=[java,'-jar',ecj,'-8','-proc:none','-nowarn'] if ecj else ['javac','-source','8','-target','8']
    subprocess.run(compiler+['-encoding','UTF-8','-classpath',str(jar),'-d',str(classes)]+list(map(str,sources)),check=True)
    print('Testing '+('PACKAGED CLDC' if a.packaged else 'SOURCE')+'; NOT handset timings',flush=True)
    subprocess.run([java,'-Xverify:all','-classpath',os.pathsep.join([str(classes),str(jar)]),'code.Diagnostics.FramePumpTest'],check=True,timeout=30)
