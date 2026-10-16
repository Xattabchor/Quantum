#!/usr/bin/env python3
"""Contract tests with instrumented M3G doubles, NOT a visual M3G emulator.
First build via scripts/build_cldc.py; then run with JAVA and ECJ_JAR or JAVAC.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
APP = ROOT / "build/cldc/Quantum-unverified.jar"
API = ROOT / "build/cldc/api.jar"
JAVA = os.environ.get("JAVA", "java")

# Mesh skies use a transactional native submission, not forced CPU rasterization.
house = (ROOT / "src/code/Gameplay/Map/House.java").read_text()
assert "g3d.renderSoftware();" not in house
skybox = (ROOT / "src/code/Gameplay/Map/Skybox.java").read_text()
assert "g3d.addRenderObjectDT(nativeMesh, null);" in skybox

# Only the API subset under test. These sources never enter the MIDlet.
STUBS = {
"javax/microedition/media/Manager.java": """package javax.microedition.media;
public final class Manager {public static String[] getSupportedContentTypes(String p){return new String[0];}}
""",
"javax/microedition/lcdui/Image.java": '''package javax.microedition.lcdui;
public class Image {
 public int[] pixels; int w,h;
 public static Image createImage(int w,int h) { Image i=new Image(); i.w=w;i.h=h;i.pixels=new int[w*h];return i; }
 public int getWidth(){return w;} public int getHeight(){return h;}
 public Graphics getGraphics(){return new Graphics(this);}
 public void getRGB(int[] p,int o,int s,int x,int y,int w,int h){
  if(Boolean.getBoolean("readback.fail"))throw new IllegalStateException("readback");
  for(int j=0;j<h;j++)System.arraycopy(pixels,(y+j)*this.w+x,p,o+j*s,w);
 }
}''',
"javax/microedition/lcdui/Graphics.java": '''package javax.microedition.lcdui;
public class Graphics {
 public Image image; Graphics(Image i){image=i;}
 public void drawRGB(int[] p,int o,int s,int x,int y,int w,int h,boolean a){
  for(int j=0;j<h;j++)System.arraycopy(p,o+j*s,image.pixels,(y+j)*image.w+x,w);
 }
}''',
"javax/microedition/m3g/Transform.java": '''package javax.microedition.m3g;
public class Transform { public float[] matrix; public void set(float[] m){matrix=m;} }''',
"javax/microedition/m3g/Camera.java": '''package javax.microedition.m3g;
public class Camera {public Transform projection;public void setGeneric(Transform t){projection=t;
 if(t.matrix[14]!=-1 || t.matrix[11]==0)throw new AssertionError("projection");
}}''',
"javax/microedition/m3g/CompositingMode.java": '''package javax.microedition.m3g;
public class CompositingMode {
 public static final int ALPHA=64,ALPHA_ADD=65,REPLACE=68;
 public boolean depth; public int blend; public float threshold;
 public void setDepthTestEnable(boolean b){depth=b;} public void setDepthWriteEnable(boolean b){}
 public void setBlending(int b){blend=b;} public void setAlphaThreshold(float f){threshold=f;}
}''',
"javax/microedition/m3g/PolygonMode.java": '''package javax.microedition.m3g;
public class PolygonMode {public static final int CULL_NONE=162,SHADE_SMOOTH=165;
 public void setCulling(int n){} public void setPerspectiveCorrectionEnable(boolean b){} public void setShading(int n){} }''',
"javax/microedition/m3g/Appearance.java": '''package javax.microedition.m3g;
public class Appearance {public Texture2D tex;public CompositingMode comp;public Fog fog;
 public void setFog(Fog f){fog=f;}
 public void setPolygonMode(PolygonMode p){} public void setCompositingMode(CompositingMode c){comp=c;}
 public void setTexture(int n,Texture2D t){tex=t;} }''',
"javax/microedition/m3g/Fog.java": '''package javax.microedition.m3g;
public class Fog {public static final int LINEAR=81;public int color;public float near,far;
 public void setMode(int m){if(m!=LINEAR)throw new AssertionError("fog mode");}
 public void setColor(int c){color=c;}
 public void setLinear(float n,float f){if(f<=n)throw new IllegalArgumentException("fog range");near=n;far=f;}
}''',
"javax/microedition/m3g/VertexArray.java": '''package javax.microedition.m3g;
public class VertexArray {public short[] shorts;public byte[] bytes;int components;
 public VertexArray(int count,int components,int size){this.components=components;}
 public void set(int start,int count,short[] p){shorts=new short[count*components];System.arraycopy(p,0,shorts,0,shorts.length);}
 public void set(int start,int count,byte[] p){bytes=new byte[count*components];System.arraycopy(p,0,bytes,0,bytes.length);}
}''',
"javax/microedition/m3g/VertexBuffer.java": '''package javax.microedition.m3g;
public class VertexBuffer {public VertexArray colors,coords,positions;public float scale,uvScale;
 public void setColors(VertexArray a){colors=a;}
 public void setDefaultColor(int c){colors=new VertexArray(3,4,1);colors.bytes=new byte[12];java.util.Arrays.fill(colors.bytes,(byte)255);}
 public void setTexCoords(int n,VertexArray a,float s,float[] b){coords=a;uvScale=s;}
 public void setPositions(VertexArray a,float s,float[] b){positions=a;scale=s;}
}''',
"javax/microedition/m3g/TriangleStripArray.java": '''package javax.microedition.m3g;
public class TriangleStripArray {public int[] indices;
 public TriangleStripArray(int start,int[] lengths){indices=new int[]{0,1,2};}
 public TriangleStripArray(int[] indices,int[] lengths){this.indices=indices;}
}''',
"javax/microedition/m3g/Image2D.java": '''package javax.microedition.m3g;
public class Image2D {public static final int RGBA=100; public byte[] pixels;
 public Image2D(int f,int w,int h,byte[] p){pixels=p;Graphics3D.uploads++;} }''',
"javax/microedition/m3g/Texture2D.java": '''package javax.microedition.m3g;
public class Texture2D {public static final int FILTER_BASE_LEVEL=208,FILTER_NEAREST=210,WRAP_REPEAT=241,FUNC_MODULATE=227,FUNC_ADD=224;
 public Image2D image;public int function;public Texture2D(Image2D i){image=i;}
 public void setFiltering(int a,int b){} public void setWrapping(int a,int b){} public void setBlending(int n){function=n;} }''',
"javax/microedition/m3g/Graphics3D.java": '''package javax.microedition.m3g;
import java.util.Hashtable;
import javax.microedition.lcdui.Graphics;
public class Graphics3D {
 private static final Graphics3D INSTANCE=new Graphics3D();
 public static int calls,uploads,binds,releases,failAt,before;
 public static VertexBuffer lastVertices; public static TriangleStripArray lastIndices;public static Appearance lastAppearance;
 public static Graphics target; public static Camera camera;
 public static class Draw {
  public int blend,function,fogColor,textureAlpha;public float near,far;public boolean fog;
  public byte[] colors;public int[] indices; public short[] positions,coords;public float scale,uvScale;public float[] projection;
 }
 public static java.util.Vector<Draw> history=new java.util.Vector<Draw>();
 public static Graphics3D getInstance(){return INSTANCE;}
 public static Hashtable getProperties(){Hashtable p=new Hashtable();p.put("maxTextureDimension",Integer.valueOf(256));return p;}
 public void bindTarget(Object g,boolean depth,int hints){
  if(target!=null)throw new AssertionError("target leak");target=(Graphics)g;binds++;
 }
 public void releaseTarget(){if(target==null)throw new AssertionError("double release");target=null;releases++;}
 public void setViewport(int x,int y,int w,int h){} public void setCamera(Camera c,Transform t){camera=c;} public void resetLights(){}
 public void render(VertexBuffer v,TriangleStripArray i,Appearance a,Transform t){
  if(target==null || a.comp.depth)throw new AssertionError("target/depth");
  if(Boolean.getBoolean("m3g.oom"))throw new OutOfMemoryError("native memory");
  before=target.image.pixels[0];calls++;lastVertices=v;lastIndices=i;lastAppearance=a;
  byte[] p=a.tex.image.pixels;byte[] c=v.colors.bytes;
  Draw draw=new Draw();draw.blend=a.comp.blend;draw.function=a.tex.function;
  draw.colors=c.clone();draw.indices=i.indices.clone();draw.textureAlpha=p[3]&255;
  draw.positions=v.positions.shorts.clone();draw.coords=v.coords.shorts.clone();draw.scale=v.scale;draw.uvScale=v.uvScale;
  draw.projection=camera.projection.matrix.clone();
  draw.fog=a.fog!=null;
  if(draw.fog){draw.fogColor=a.fog.color;draw.near=a.fog.near;draw.far=a.fog.far;}
  history.add(draw);
  // Sample only the first vertex/texel to test blending equations. This is NOT
  // triangle rasterization or a visual substitute for a JSR-184 implementation.
  int alpha=(p[3]&255)*(c[3]&255)/255;
  if(alpha/255.0f>=a.comp.threshold){
   int out=0xff000000;
   for(int k=0;k<3;k++){
    int src=a.tex.function==Texture2D.FUNC_ADD?Math.min(255,(p[k]&255)+(c[k]&255)):(p[k]&255)*(c[k]&255)/255;
    if(draw.fog){float z=-v.positions.shorts[2]*v.scale;
     float f=Math.max(0,Math.min(1,(z-draw.near)/(draw.far-draw.near)));
     src=Math.round(src*(1-f)+((draw.fogColor>>(16-k*8))&255)*f);
    }
    int dst=(before>>(16-k*8))&255;
    int value=a.comp.blend==CompositingMode.ALPHA_ADD?Math.min(255,dst+src*alpha/255)
      :a.comp.blend==CompositingMode.ALPHA?(src*alpha+dst*(255-alpha))/255:src;
    out|=value<<(16-k*8);
   }
   target.image.pixels[0]=out;
  }
  if(calls==failAt)throw new IllegalStateException("native render failure");
 }
}'''
}

with tempfile.TemporaryDirectory(prefix="quantum-m3g-") as temp:
    temp = Path(temp)
    classes = temp / "classes"
    classes.mkdir()
    sources = []
    for name, content in STUBS.items():
        path = temp / "stubs" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
    sources += sorted(TEST.glob("*.java"))
    # Compile the current adapter against the doubles, not copies of its logic.
    sources += [ROOT / "src/code/Rendering" / name for name in
                ("M3GSceneRenderer.java", "ProjectedGeometry.java", "M3GSkyRenderer.java", "SkyGeometry.java")]
    if os.environ.get("ECJ_JAR"):
        compiler = [JAVA, "-jar", os.environ["ECJ_JAR"], "-8", "-proc:none", "-nowarn"]
    else:
        compiler = [os.environ.get("JAVAC", "javac"), "-source", "8", "-target", "8"]
    subprocess.run(compiler + ["-classpath", os.pathsep.join(map(str, (APP, API))),
                              "-d", str(classes)] + list(map(str, sources)), check=True)
    classpath = os.pathsep.join(map(str, (classes, APP, API)))
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.M3GTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FogTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.GeometryTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SkyTest"], check=True)
    # Remove the optional API for a real class-loading fallback test.
    shutil.rmtree(classes / "javax/microedition/m3g")
    no_m3g_api = temp / "api-without-m3g.jar"
    with zipfile.ZipFile(API) as src, zipfile.ZipFile(no_m3g_api, "w") as dst:
        for name in src.namelist():
            if not name.startswith("javax/microedition/m3g/"):
                dst.writestr(name, src.read(name))
    subprocess.run([JAVA, "-cp", os.pathsep.join(map(str, (classes, APP, no_m3g_api))),
                    "code.Rendering.MissingM3GTest"], check=True)
