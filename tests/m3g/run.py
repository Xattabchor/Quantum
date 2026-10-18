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
LEVEL41_PROBE = bool(os.environ.get("QUANTUM_LEVEL41_PROBE"))
LEVEL12_PROBE = bool(os.environ.get("QUANTUM_LEVEL12_PROBE"))
LEVEL13_PROBE = bool(os.environ.get("QUANTUM_LEVEL13_PROBE"))
STLKR_ALL_PROBE = bool(os.environ.get("QUANTUM_STLKR_LEVELS_PROBE"))
STLKR_PROBE = LEVEL12_PROBE or LEVEL13_PROBE or STLKR_ALL_PROBE
if sum((LEVEL41_PROBE, LEVEL12_PROBE, LEVEL13_PROBE, STLKR_ALL_PROBE)) > 1:
    raise ValueError("Run level41, level12, level13 and all-Stlkr probes separately")
WORLD_REASONS = bool(os.environ.get("QUANTUM_WORLD_PROBE")) or LEVEL41_PROBE or STLKR_PROBE

# Mesh skies use a transactional native submission, not forced CPU rasterization.
house = (ROOT / "src/code/Gameplay/Map/House.java").read_text()
assert "g3d.renderSoftware();" not in house
sky_stage=house[house.index("private final void renderSkybox("):house.index("public final int render(",house.index("private final void renderSkybox("))]
assert "g3d.renderBackground();" in sky_stage and "g3d.render();" not in sky_stage
skybox = (ROOT / "src/code/Gameplay/Map/Skybox.java").read_text()
assert "g3d.addRenderObjectDT(nativeMesh, null);" in skybox

# Weather is composited after image effects but before flush/HUD; the original
# LCDUI paint remains the fallback and is skipped on native success.
game = (ROOT / "src/code/Gameplay/GameScreen.java").read_text()
# BLOOM remains a level/settings key, but only the requested Software backend
# runs it. Do not clear the flag: switching back must restore the level choice.
assert "if(bloom && g3d.getRenderer()==DirectX7.SOFTWARE) fastbloomScr(g3d);" in game
assert game.count("fastbloomScr(g3d);")==1
loader = (ROOT / "src/code/Gameplay/LevelLoader.java").read_text()
assert 'GameScreen.bloom = getBoolean("BLOOM", lvl, setting, false);' in loader

assert "wg.paintM3G(g3d)" not in game and "g3d.renderMuzzleGlow(" not in game
assert game.index("wg.prepareM3G(g3d)") < game.index("scene.flush(g, 0, screenY)")
assert "nativeWeather=g3d.renderGameFrameImage(vignette,playerDied,player.isDamaged()," in game
assert "wg.paintAfterScene(g, screenY, g3d.getRenderer(), nativeWeather);" in game
assert game.index("scene.flush(g, 0, screenY)") < game.index("wg.paintAfterScene(") < game.index("player.arsenal.drawWeapon(")
assert game.index("g3d.renderGameFrameImage(") < game.index("player.arsenal.drawWeapon(")
frame_source=(ROOT / "src/code/Rendering/DirectX7.java").read_text()
assert "scene.getG3D().getDisplay(), musTime" in game  # pause must synchronize lazy native pixels
assert not (ROOT / "src/code/Rendering/MuzzleGlow.java").exists()
assert "prepareMuzzleGlow" not in frame_source and "new MuzzleGlow" not in frame_source
assert game.index("g3d.render();") < game.index("wg.lightingEffect(") < game.index("if(vignette != null &&")
assert "if(lighting && !player.character.underRoof && wg != null && g3d.getRenderer() != DirectX7.M3G)" in game
assert "System.currentTimeMillis() - wg.lastLighting) < 135" in game
assert game.index("wg.setRenderer(Main.renderer)") < game.index("wg.moveY((int) -yRot")
weapon = (ROOT / "src/code/Gameplay/Weapon.java").read_text()
assert "if(shootLight) {" in weapon and "g3d.startWeaponLight();" not in weapon
assert 'DirectX7.configureM3GBlend(lvl.get("M3G_BLEND_COLOR"));' in loader
assert 'DirectX7.configureM3GExpFog(lvl.get("M3G_EXP_FOG_COLOR"),lvl.get("M3G_EXP_FOG_DENSITY"));' in loader

# Successful native material/sprite fog must not reintroduce CPU fading/masks.
scene_source=(ROOT / "src/code/Rendering/M3GSceneRenderer.java").read_text()
sprite_source=(ROOT / "src/code/Rendering/M3GSpriteRenderer.java").read_text()
assert "MuzzleGlow" not in scene_source and "muzzleTexture" not in scene_source
assert not (ROOT / "src/code/Rendering/M3GMode5Lighting.java").exists()
assert "Mode5WeaponLight.pack(" not in scene_source and "mode5Lighting" not in scene_source
assert "new Mode5WeaponLight" not in frame_source
assert "rayCastScreenEffect(" not in frame_source+scene_source
for forbidden in ("legacyFog(", "additiveFog(", "lightmapColor(", "FOG_MASK", "SPRITE_MASK", "SPRITE_ALPHA_MASK"):
    assert forbidden not in scene_source+sprite_source

# Selected-M3G effects replace CPU postprocess even after native failure.
assert "g3d.renderGameFrameImage(vignette,playerDied,player.isDamaged()," in game
assert "if(vignette != null && g3d.getRenderer()==DirectX7.SOFTWARE)" in game
assert "if(doubleBright && g3d.getRenderer()==DirectX7.SOFTWARE)" in game
assert "if(g3d.getRenderer()==DirectX7.SOFTWARE) {\n                if(playerDied) deathScr" in game

# All native render/image creation sites route through one counted API boundary.
rendering=ROOT / "src/code/Rendering"
for adapter in rendering.glob("M3G*.java"):
    text=adapter.read_text()
    if adapter.name!="M3GSceneRenderer.java":
        assert "graphics.render(" not in text and "new Image2D(" not in text, adapter
assert scene_source.count("graphics.render(")==1 and scene_source.count("new Image2D(")==1
assert game.index("g3d.beginFrameStats();") < game.index("scene.render(g,")
assert "g3d.getRenderer()==DirectX7.M3G && DeveloperMenu.showFps" in game
assert "g3d.getM3GStatsText(System.currentTimeMillis())" in game

# Only the API subset under test. These sources never enter the MIDlet.
STUBS = {
"javax/microedition/media/Manager.java": """package javax.microedition.media;
public final class Manager {public static String[] getSupportedContentTypes(String p){return new String[0];}}
""",
"javax/microedition/lcdui/Image.java": '''package javax.microedition.lcdui;
public class Image {
 public static int getRGBCalls,creates;
 public int[] pixels; int w,h;
 public static Image createImage(int w,int h) { creates++; Image i=new Image(); i.w=w;i.h=h;i.pixels=new int[w*h];return i; }
 public int getWidth(){return w;} public int getHeight(){return h;}
 public Graphics getGraphics(){return new Graphics(this);}
 public void getRGB(int[] p,int o,int s,int x,int y,int w,int h){
  getRGBCalls++;
  if(Boolean.getBoolean("readback.fail"))throw new IllegalStateException("readback");
  for(int j=0;j<h;j++)System.arraycopy(pixels,(y+j)*this.w+x,p,o+j*s,w);
 }
}''',
"javax/microedition/lcdui/Graphics.java": '''package javax.microedition.lcdui;
public class Graphics {
 public static final int TOP=16,LEFT=4;
 public Image image; public static int drawRGBCalls,drawImageCalls;
 private int tx,ty,cx,cy,cw,ch;
 Graphics(Image i){image=i;cw=i.w;ch=i.h;}
 public void translate(int x,int y){tx+=x;ty+=y;}
 public void setClip(int x,int y,int w,int h){cx=x+tx;cy=y+ty;cw=w;ch=h;}
 public void drawImage(Image src,int x,int y,int anchor){
  drawImageCalls++;
  if(Boolean.getBoolean("image.blit.fail"))throw new IllegalArgumentException("blit");
  if(Boolean.getBoolean("image.blit.oom"))throw new OutOfMemoryError("blit memory");
  if(anchor!=(TOP|LEFT))throw new AssertionError("anchor");
  blit(src.pixels,0,src.w,x,y,src.w,src.h,false);
 }
 private void blit(int[] p,int o,int s,int x,int y,int w,int h,boolean alpha){
  x+=tx;y+=ty;
  for(int j=0;j<h;j++)for(int k=0;k<w;k++){
   int dx=x+k,dy=y+j;
   if(dx<0 || dy<0 || dx>=image.w || dy>=image.h || dx<cx || dy<cy || dx>=cx+cw || dy>=cy+ch)continue;
   int c=p[o+j*s+k];
   if(alpha && c>>>24==0)continue;
   image.pixels[dy*image.w+dx]=c;
  }
 }
 public int color,lineCalls,rectCalls,lastX,lastY,lastX2,lastY2,colorCalls;
 public java.util.Vector<int[]> lineHistory=new java.util.Vector<int[]>();
 public void setColor(int c){color=c;colorCalls++;}
 public void drawLine(int x,int y,int x2,int y2){lineCalls++;lastX=x;lastY=y;lastX2=x2;lastY2=y2;lineHistory.add(new int[]{x,y,x2,y2,color});}
 public void fillRect(int x,int y,int w,int h){rectCalls++;lastX=x;lastY=y;lastX2=x+w;lastY2=y+h;}
 public void drawRGB(int[] p,int o,int s,int x,int y,int w,int h,boolean a){
  drawRGBCalls++;
  blit(p,o,s,x,y,w,h,a);
 }
}''',
"javax/microedition/m3g/Background.java": '''package javax.microedition.m3g;
public class Background {
 public int color; public boolean colorClear,depthClear;
 public void setColor(int c){color=c;} public void setColorClearEnable(boolean b){colorClear=b;}
 public void setDepthClearEnable(boolean b){depthClear=b;}
}''',
"javax/microedition/m3g/Transform.java": '''package javax.microedition.m3g;
public class Transform { public static int created; public float[] matrix; public Transform(){created++;} public void set(float[] m){matrix=m.clone();} }''',
"javax/microedition/m3g/Camera.java": '''package javax.microedition.m3g;
public class Camera {public static int generics; public Transform projection;public void setGeneric(Transform t){generics++;projection=t;
 if(t.matrix[14]!=-1 || t.matrix[11]==0)throw new AssertionError("projection");
}}''',
"javax/microedition/m3g/CompositingMode.java": '''package javax.microedition.m3g;
public class CompositingMode {
 public static final int ALPHA=64,ALPHA_ADD=65,MODULATE=66,MODULATE_X2=67,REPLACE=68;
 public boolean depth; public int blend; public float threshold;
 public void setDepthTestEnable(boolean b){depth=b;} public void setDepthWriteEnable(boolean b){}
 public void setBlending(int b){blend=b;} public void setAlphaThreshold(float f){threshold=f;}
}''',
"javax/microedition/m3g/PolygonMode.java": '''package javax.microedition.m3g;
public class PolygonMode {public static final int CULL_BACK=160,CULL_FRONT=161,CULL_NONE=162,SHADE_SMOOTH=165,WINDING_CCW=168,WINDING_CW=169;
 public boolean perspective=true;public int cull=CULL_BACK,winding=WINDING_CCW;public static int perspectiveCalls;
 public void setCulling(int n){cull=n;} public void setWinding(int n){winding=n;} public void setPerspectiveCorrectionEnable(boolean b){perspective=b;perspectiveCalls++;} public void setShading(int n){} }''',
"javax/microedition/m3g/Appearance.java": '''package javax.microedition.m3g;
public class Appearance {public Texture2D tex;public CompositingMode comp;public Fog fog;public PolygonMode polygon;public Material material;
 public void setMaterial(Material m){material=m;}
 public void setFog(Fog f){fog=f;}
 public void setPolygonMode(PolygonMode p){polygon=p;} public void setCompositingMode(CompositingMode c){comp=c;}
 public void setTexture(int n,Texture2D t){tex=t;} }''',
"javax/microedition/m3g/Light.java": '''package javax.microedition.m3g;
public class Light {
 public static final int AMBIENT=128,DIRECTIONAL=129,OMNI=130;public int mode,color;public float intensity=1,constant=1,linear,quadratic;
 public void setAttenuation(float c,float l,float q){constant=c;linear=l;quadratic=q;}
 public void setMode(int m){mode=m;}public void setColor(int c){color=c;}public void setIntensity(float i){intensity=i;}
}''',
"javax/microedition/m3g/Material.java": '''package javax.microedition.m3g;
public class Material {
 public static final int AMBIENT=1024,DIFFUSE=2048,EMISSIVE=4096,SPECULAR=8192;
 public int ambient=0xff333333,diffuse=0xffcccccc,emissive,specular;public boolean tracking;
 public Material(){if(Boolean.getBoolean("material.fail"))throw new IllegalStateException("material");}
 public void setColor(int target,int rgb){if((target&AMBIENT)!=0)ambient=rgb;if((target&DIFFUSE)!=0)diffuse=rgb;
  if((target&EMISSIVE)!=0)emissive=rgb;if((target&SPECULAR)!=0)specular=rgb;}
 public void setShininess(float s){}public void setVertexColorTrackingEnable(boolean b){tracking=b;}
}''',
"javax/microedition/m3g/Fog.java": '''package javax.microedition.m3g;
public class Fog {public static final int EXPONENTIAL=80,LINEAR=81;public int color,mode=LINEAR;public float near,far,density=1;
 public void setMode(int m){if(m!=LINEAR && m!=EXPONENTIAL)throw new AssertionError("fog mode");if(m==EXPONENTIAL && Boolean.getBoolean("exp.unsupported"))throw new IllegalArgumentException("EXP unavailable");mode=m;}
 public void setDensity(float d){if(!(d>=0) || Float.isInfinite(d))throw new IllegalArgumentException("fog density");density=d;}
 public void setColor(int c){color=c;}
 public void setLinear(float n,float f){if(f<=n)throw new IllegalArgumentException("fog range");near=n;far=f;}
}''',
"javax/microedition/m3g/VertexArray.java": '''package javax.microedition.m3g;
public class VertexArray {public static int created,sets;public short[] shorts;public byte[] bytes;int components;
 public VertexArray(int count,int components,int size){this.components=components;created++;}
 public void set(int start,int count,short[] p){sets++;shorts=new short[count*components];System.arraycopy(p,0,shorts,0,shorts.length);}
 public void set(int start,int count,byte[] p){sets++;bytes=new byte[count*components];System.arraycopy(p,0,bytes,0,bytes.length);}
}''',
"javax/microedition/m3g/VertexBuffer.java": '''package javax.microedition.m3g;
public class VertexBuffer {public VertexArray colors,coords,positions,normals;public float scale,uvScale;
 public void setColors(VertexArray a){colors=a;}
 public void setNormals(VertexArray a){if(a!=null && Boolean.getBoolean("normals.fail"))throw new IllegalStateException("normals");normals=a;}
 public static int defaultColorCalls;
 public void setDefaultColor(int c){defaultColorCalls++;colors=new VertexArray(4,4,1);colors.bytes=new byte[16];
  for(int i=0;i<4;i++) {colors.bytes[i*4]=(byte)(c>>16);colors.bytes[i*4+1]=(byte)(c>>8);colors.bytes[i*4+2]=(byte)c;colors.bytes[i*4+3]=(byte)(c>>>24);}}
 public void setTexCoords(int n,VertexArray a,float s,float[] b){coords=a;uvScale=s;}
 public void setPositions(VertexArray a,float s,float[] b){positions=a;scale=s;}
}''',
"javax/microedition/m3g/TriangleStripArray.java": '''package javax.microedition.m3g;
public class TriangleStripArray {public static int created;public int[] indices,lengths;
 public TriangleStripArray(int start,int[] lengths){if(Boolean.getBoolean("weather.indices.fail"))throw new OutOfMemoryError("weather indices");created++;this.lengths=lengths;int count=0;for(int n:lengths) count+=n;indices=new int[count];for(int n=0;n<count;n++) indices[n]=start+n;}
 public TriangleStripArray(int[] indices,int[] lengths){created++;this.indices=indices;this.lengths=lengths;}
}''',
"javax/microedition/m3g/Image2D.java": '''package javax.microedition.m3g;
public class Image2D {public static final int RGBA=100,LUMINANCE=97; public byte[] pixels,encoded;public int width,height,format;
 public Image2D(int f,int w,int h,byte[] p){
  if(Graphics3D.target!=null)throw new AssertionError("texture upload with bound target");
  if(f==RGBA && Boolean.getBoolean("texture.upload.fail"))throw new OutOfMemoryError("texture memory");
  if(f==LUMINANCE && Boolean.getBoolean("effects.upload.fail"))throw new OutOfMemoryError("effect mask memory");
  encoded=p;format=f;width=w;height=h;Graphics3D.uploads++;
  if(f==LUMINANCE){pixels=new byte[w*h*4];for(int i=0;i<w*h;i++){pixels[i*4]=pixels[i*4+1]=pixels[i*4+2]=p[i];pixels[i*4+3]=(byte)255;}}
  else pixels=p;
 } }''',
"javax/microedition/m3g/Texture2D.java": '''package javax.microedition.m3g;
public class Texture2D {public static final int FILTER_BASE_LEVEL=208,FILTER_NEAREST=210,FILTER_LINEAR=209,WRAP_CLAMP=240,WRAP_REPEAT=241,FUNC_MODULATE=227,FUNC_ADD=224,FUNC_BLEND=225,FUNC_DECAL=226;
 public Image2D image;public int function,blendColor,filter,wrapS,wrapT;public Texture2D(Image2D i){image=i;}
 public void setFiltering(int a,int b){filter=b;} public void setWrapping(int a,int b){wrapS=a;wrapT=b;} public void setBlending(int n){function=n;} public void setBlendColor(int rgb){blendColor=rgb&0xffffff;} }''',
"javax/microedition/m3g/Graphics3D.java": '''package javax.microedition.m3g;
import java.util.Hashtable;
import javax.microedition.lcdui.Graphics;
public class Graphics3D {
 private static final Graphics3D INSTANCE=new Graphics3D();
 public static int calls,uploads,binds,releases,failAt,before,clears;
 public static VertexBuffer lastVertices; public static TriangleStripArray lastIndices;public static Appearance lastAppearance;
 public static Graphics target; public static Camera camera;
 public static class Draw {
  public Texture2D source;public int blend,function,blendColor,fogColor,fogMode,textureAlpha,filter,colorComponents,cull,winding,sample;public boolean textured,perspective;public float near,far,density,threshold;public boolean fog;
  public byte[] colors;public int[] indices,lengths; public short[] positions,coords,normals;public boolean lit,tracking;public int lights,diffuse,ambient,emissive,specular;public float ambientIntensity,directionalIntensity,omniIntensity,omniQuadratic;public float[] omniPosition;public float[] lightMatrix;public float scale,uvScale;public float[] projection;
 }
 public static java.util.Vector<Draw> history=new java.util.Vector<Draw>();
 public static Graphics3D getInstance(){return INSTANCE;}
 public static Hashtable getProperties(){Hashtable p=new Hashtable();p.put("maxTextureDimension",Integer.valueOf(256));return p;}
 public void bindTarget(Object g,boolean depth,int hints){
  if(target!=null)throw new AssertionError("target leak");target=(Graphics)g;binds++;
 }
 public void releaseTarget(){if(target==null)throw new AssertionError("double release");target=null;releases++;if(Boolean.getBoolean("release.fail"))throw new IllegalStateException("release");}
 public void setViewport(int x,int y,int w,int h){} public void setCamera(Camera c,Transform t){camera=c;} public void resetLights(){lights.removeAllElements();lightTransforms.removeAllElements();}
 public static java.util.Vector<Light> lights=new java.util.Vector<Light>();
 public static java.util.Vector<float[]> lightTransforms=new java.util.Vector<float[]>();
 public int addLight(Light l,Transform t){if(Boolean.getBoolean("lights.fail") || (lights.size()==1 && Boolean.getBoolean("lights.second.fail")) || (l.mode==Light.OMNI && Boolean.getBoolean("omni.fail")))throw new IllegalStateException("light");
  lights.add(l);lightTransforms.add(t==null?null:t.matrix.clone());return lights.size()-1;}
 public void setLight(int n,Light l,Transform t){lights.set(n,l);lightTransforms.set(n,t==null?null:t.matrix.clone());}
 public void clear(Background b){
  if(target==null || !b.colorClear || b.depthClear)throw new AssertionError("sky clear state");
  if(Boolean.getBoolean("m3g.oom"))throw new OutOfMemoryError("native clear memory");
  clears++;java.util.Arrays.fill(target.image.pixels,b.color);
  if(Boolean.getBoolean("clear.fail"))throw new IllegalStateException("native clear failure");
 }
 public void render(VertexBuffer v,TriangleStripArray i,Appearance a,Transform t){
  if(target==null || a.comp.depth)throw new AssertionError("target/depth");
  if(Boolean.getBoolean("m3g.oom"))throw new OutOfMemoryError("native memory");
  before=target.image.pixels[0];calls++;lastVertices=v;lastIndices=i;lastAppearance=a;
  byte[] p=a.tex==null?new byte[]{-1,-1,-1,-1}:a.tex.image.pixels;byte[] c=v.colors.bytes;
  if(a.tex!=null && Boolean.getBoolean("m3g.sampleCenter")) {
   int offset=(a.tex.image.width/2+a.tex.image.height/2*a.tex.image.width)*4;
   p=new byte[]{p[offset],p[offset+1],p[offset+2],p[offset+3]};
  }
  Draw draw=new Draw();draw.source=a.tex;draw.blend=a.comp.blend;draw.function=a.tex==null?Texture2D.FUNC_MODULATE:a.tex.function;draw.filter=a.tex==null?0:a.tex.filter;
  draw.blendColor=a.tex==null?0:a.tex.blendColor;
  draw.sample=((p[0]&255)<<24)|((p[1]&255)<<16)|((p[2]&255)<<8)|(p[3]&255);draw.threshold=a.comp.threshold;
  draw.colorComponents=v.colors.components;draw.textured=a.tex!=null;draw.perspective=a.polygon==null || a.polygon.perspective;
  draw.cull=a.polygon==null?PolygonMode.CULL_BACK:a.polygon.cull;draw.winding=a.polygon==null?PolygonMode.WINDING_CCW:a.polygon.winding;
  draw.colors=c.clone();draw.indices=i.indices.clone();draw.lengths=i.lengths;draw.textureAlpha=p[3]&255;
  draw.positions=v.positions.shorts.clone();draw.coords=v.coords==null || v.coords.shorts==null?null:v.coords.shorts.clone();draw.scale=v.scale;draw.uvScale=v.uvScale;
  draw.lights=lights.size();draw.lit=a.material!=null;
  draw.normals=v.normals==null?null:v.normals.shorts.clone();
  double lightingScale=1;
  if(draw.lit){
   if(v.normals==null)throw new AssertionError("lit draw without normals");
   draw.tracking=a.material.tracking;draw.diffuse=a.material.diffuse;draw.ambient=a.material.ambient;
   draw.emissive=a.material.emissive;draw.specular=a.material.specular;
   lightingScale=0;
   for(int n=0;n<lights.size();n++){
    Light l=lights.get(n);
    if(l.mode==Light.AMBIENT){draw.ambientIntensity+=l.intensity;lightingScale+=l.intensity;}
    else if(l.mode==Light.DIRECTIONAL){
     draw.directionalIntensity+=l.intensity;draw.lightMatrix=lightTransforms.get(n).clone();
     double dot=(draw.normals[0]*draw.lightMatrix[2]+draw.normals[1]*draw.lightMatrix[6]+draw.normals[2]*draw.lightMatrix[10])/32767.0;
     lightingScale+=l.intensity*Math.max(0,Math.min(1,dot));
    } else if(l.mode==Light.OMNI) {
     draw.omniIntensity=l.intensity;draw.omniQuadratic=l.quadratic;
     float[] m=lightTransforms.get(n);draw.omniPosition=new float[]{m[3],m[7],m[11]};
     double dx=m[3]-draw.positions[0]*draw.scale,dy=m[7]-draw.positions[1]*draw.scale,dz=m[11]-draw.positions[2]*draw.scale;
     double d2=dx*dx+dy*dy+dz*dz,d=Math.sqrt(d2);
     double dot=d==0?0:Math.max(0,(draw.normals[0]*dx+draw.normals[1]*dy+draw.normals[2]*dz)/(32767*d));
     lightingScale+=l.intensity*dot/(l.constant+l.linear*d+l.quadratic*d2);
    } else throw new AssertionError("unexpected positional light");
   }
  }
  draw.projection=camera.projection.matrix.clone();
  draw.fog=a.fog!=null;
  if(draw.fog){draw.fogMode=a.fog.mode;draw.density=a.fog.density;draw.fogColor=a.fog.color;draw.near=a.fog.near;draw.far=a.fog.far;}
  history.add(draw);
  // Sample only the first vertex/texel to test blending equations. This is NOT
  // triangle rasterization or a visual substitute for a JSR-184 implementation.
  int alpha=(p[3]&255)*(v.colors.components==3?255:c[3]&255)/255;
  if(draw.function==Texture2D.FUNC_DECAL) alpha=v.colors.components==3?255:c[3]&255;
  if(alpha/255.0f>=a.comp.threshold){
   int out=0xff000000;
   for(int k=0;k<3;k++){
    int src=draw.function==Texture2D.FUNC_ADD?Math.min(255,(p[k]&255)+(c[k]&255)):(p[k]&255)*(c[k]&255)/255;
    if(draw.function==Texture2D.FUNC_BLEND) src=((c[k]&255)*(255-(p[k]&255))+((draw.blendColor>>(16-k*8))&255)*(p[k]&255))/255;
    if(draw.function==Texture2D.FUNC_DECAL) src=((c[k]&255)*(255-(p[3]&255))+(p[k]&255)*(p[3]&255))/255;
    if(draw.lit) src=Math.min(255,(int)((p[k]&255)*lightingScale+0.5));
    if(draw.fog){float z=-v.positions.shorts[2]*v.scale;
     float f=draw.fogMode==Fog.EXPONENTIAL?(float)(1-Math.exp(-draw.density*z)):Math.max(0,Math.min(1,(z-draw.near)/(draw.far-draw.near)));
     src=Math.round(src*(1-f)+((draw.fogColor>>(16-k*8))&255)*f);
    }
    int dst=(before>>(16-k*8))&255;
    int value=a.comp.blend==CompositingMode.MODULATE_X2?Math.min(255,2*src*dst/255)
      :a.comp.blend==CompositingMode.MODULATE?src*dst/255
      :a.comp.blend==CompositingMode.ALPHA_ADD?Math.min(255,dst+src*alpha/255)
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
                ("Vertex.java", "Mode11Decal.java", "Mode12Fog.java", "TexturingAffine.java", "TexturingPers.java", "TexturingFloors.java", "M3GSceneRenderer.java", "M3GFrameEffects.java", "M3GMaterialMath.java", "M3GPolygonBatch.java", "ProjectedGeometry.java", "WorldClipGeometry.java", "M3GSkyRenderer.java", "SkyGeometry.java", "SkyCoverage.java", "M3GScreenLines.java", "ScreenLineGeometry.java", "M3GSpriteRenderer.java", "M3GPanoramaRenderer.java")]
    # Validate on-device frame-profile copies against the same API contracts.
    # Requires a prior build_cldc.py --profile-frame; not a timing benchmark.
    if os.environ.get("QUANTUM_FRAME_PROFILE"):
        if WORLD_REASONS:
            raise ValueError("Run frame timing and world-reason instrumentation separately")
        with zipfile.ZipFile(APP) as app:
            if "code/Rendering/FrameProfile.class" not in app.namelist():
                raise ValueError("Build with --profile-frame first")
        names = {"M3GSceneRenderer.java", "ProjectedGeometry.java"}
        sources = [ROOT / "build/cldc/frame-profile-src/code/Rendering" / p.name
                   if p.name in names else p for p in sources]
        sources.append(ROOT / "tests/frame_profile/FrameProfileSuiteRunner.java")
    # Optional diagnostics instrument TEMPORARY source copies only. Production
    # sources, published classes and rendering decisions remain untouched.
    if WORLD_REASONS:
        if LEVEL41_PROBE or STLKR_PROBE:
            sources.append(ROOT / "src/code/Rendering/DirectX7.java")
        if LEVEL41_PROBE or STLKR_ALL_PROBE:
            sources.append(ROOT / "src/code/Gameplay/Map/LightMapper.java")
        instrumented = []
        for source in sources:
            if source.name == "M3GPolygonBatch.java":
                content = source.read_text()
                marker = "(size==0?firstOrder.length:size)+order.length>MAX_VERTICES)) flush(g);"
                assert content.count(marker) == 1
                content = content.replace(marker, """(size==0?firstOrder.length:size)+order.length>MAX_VERTICES)) {
                    WorldBottleneckProbe.split(texture!=tex,
                        polygon!=poly || this.blend!=blend || this.function!=function
                        || blendColor!=source.blendColor || levelFilter!=level || imageFilter!=filter
                        || this.threshold!=threshold || this.fogged!=fogged || fogColor!=color
                        || this.near!=near || this.far!=far || this.fogMode!=fogMode || this.density!=density,
                        fogged && this.scale!=scale,
                        (size==0?firstOrder.length:size)+order.length>MAX_VERTICES);
                    flush(g);
                }""")
            elif source.name == "M3GSceneRenderer.java":
                content = source.read_text()
                start = content.index("    public boolean draw(")
                end = content.index("    private int fillColor(", start)
                before = content[:start]
                lines = content[start:end].splitlines(keepends=True)
                for i, line in enumerate(lines):
                    reason = "geometry-clip" if "worldClip.prepare" in line else "geometry-pack" if "if(scale == 0)" in line else "native-line-%d" % (len(before.splitlines()) + i + 1)
                    lines[i] = line.replace("return false;", 'return WorldBottleneckProbe.reject("%s");' % reason)
                content = before + "".join(lines) + content[end:]
                if STLKR_PROBE:
                    marker = "textures.addElement(cached);cachedPixels += size;"
                    assert content.count(marker) == 1
                    content = content.replace(marker, marker + "\n        Level13Probe.upload(cached.texture,image,kind);")
                marker = "worldClip.prepare(g,vertices,count,coords,colors,distance,perspective,nativeFog,alternate,hardwarePerspective)"
                assert content.count(marker) == 1
                content = content.replace(marker, "WorldBottleneckProbe.clipResult(worldClip," + marker + ")")
            elif source.name == "ProjectedGeometry.java":
                lines = source.read_text().splitlines(keepends=True)
                for i, line in enumerate(lines):
                    reason = ("behind-camera" if "v.rz" in line else
                              "weight-precision" if "if(q <= 0" in line else
                              "position-overflow" if "if(x < -32767" in line else
                              "screen-range" if "if(x>32767" in line else "pack-line-%d" % (i + 1))
                    lines[i] = line.replace("return 0;", 'return WorldBottleneckProbe.packReject("%s");' % reason)
                content = "".join(lines)
                assert content.count("if(valid) return scale;") == 1
                content = content.replace("if(valid) return scale;", "if(valid) return WorldBottleneckProbe.fogResult(true,scale);")
                marker = "return pack(vertices,count,cx,cy,distance,true,positions,work);"
                assert content.count(marker) == 1
                content = content.replace(marker, "return WorldBottleneckProbe.fogResult(false,pack(vertices,count,cx,cy,distance,true,positions,work));")
            elif (LEVEL41_PROBE or STLKR_PROBE) and source.name == "DirectX7.java":
                content = source.read_text()
                marker = "public void transformAndProjectVertices(Mesh ms, Matrix matrix) {"
                assert content.count(marker) == 1
                probe = "Level13Probe" if STLKR_PROBE else "Level41Probe"
                content = content.replace(marker, marker + "\nif(code.Rendering."+probe+".counting) code.Rendering."+probe+".projected+=ms.getVertices().length;")
            elif (LEVEL41_PROBE or STLKR_ALL_PROBE) and source.name == "LightMapper.java":
                content = source.read_text()
                marker = "new Object().getClass().getResourceAsStream(lightdataFile)"
                assert content.count(marker) == 1
                probe = "StlkrLevelsProbe" if STLKR_ALL_PROBE else "Level41Probe"
                content = content.replace(marker, "code.Rendering."+probe+".class.getResourceAsStream(lightdataFile)")
            else:
                instrumented.append(source)
                continue
            path = temp / "probe-sources" / source.name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            instrumented.append(path)
        sources = instrumented
    # Disable only saturated-Fog folding in temporary sources, for a baseline.
    if os.environ.get("QUANTUM_FOG_SOLID_REFERENCE"):
        reference_sources = []
        for source in sources:
            if source.name == "M3GPolygonBatch.java":
                content = source.read_text()
                marker = "&& source.opaque && opaque"
                assert content.count(marker) == 1
                path = temp / "fog-solid-reference" / source.name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content.replace(marker,"&& false && source.opaque && opaque"))
                reference_sources.append(path)
            else:
                reference_sources.append(source)
        sources = reference_sources
    # Test-only merge-disabled reference for exact ordered stream comparison.
    # Never add a runtime/level option or modify the production source in place.
    if os.environ.get("QUANTUM_FOG_REBASE_REFERENCE"):
        reference_sources = []
        for source in sources:
            if source.name == "M3GPolygonBatch.java":
                content = source.read_text()
                marker = "&& rebase(scale,positions,count)"
                assert content.count(marker) == 1
                path = temp / "fog-rebase-reference" / source.name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content.replace(marker, "&& false"))
                reference_sources.append(path)
            else:
                reference_sources.append(source)
        sources = reference_sources
    # Diagnostic reference only: flush adjacent sprites before each dispatch.
    # Native Fog/texture conversion remain enabled; never alter release source.
    if os.environ.get("QUANTUM_SPRITE_BATCH_REFERENCE"):
        reference_sources = []
        for source in sources:
            if source.name == "M3GSceneRenderer.java":
                content = source.read_text()
                marker = "if(object instanceof Sprite) flushPolygons(g);"
                assert content.count(marker) == 1
                path = temp / "sprite-batch-reference" / source.name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content.replace(marker, "if(object instanceof Sprite) {flushPolygons(g); if(spriteRenderer!=null) spriteRenderer.flush(g);}"))
                reference_sources.append(path)
            else:
                reference_sources.append(source)
        sources = reference_sources
    if os.environ.get("ECJ_JAR"):
        compiler = [JAVA, "-jar", os.environ["ECJ_JAR"], "-8", "-proc:none", "-nowarn"]
    else:
        compiler = [os.environ.get("JAVAC", "javac"), "-source", "8", "-target", "8"]
    subprocess.run(compiler + ["-classpath", os.pathsep.join(map(str, (APP, API))),
                              "-d", str(classes)] + list(map(str, sources)), check=True)
    resource_jar = os.environ.get("QUANTUM_RESOURCE_JAR")
    paths = [classes, APP, API]
    if resource_jar:
        resource_jar = Path(resource_jar).resolve()
        with zipfile.ZipFile(resource_jar) as assets:
            if any(n.lower().endswith(".class") for n in assets.namelist()):
                raise ValueError("QUANTUM_RESOURCE_JAR must contain assets only; run scripts/game_resources.py")
        # Assets take precedence even if the latest APP was built with a
        # different game archive; the verified resource JAR contains no code.
        paths.insert(1, resource_jar)
    classpath = os.pathsep.join(map(str, paths))
    if resource_jar:
        subprocess.run([JAVA, "-Djava.awt.headless=true", "-cp", classpath,
                        "code.Rendering.GameResourcesTest", str(resource_jar)], check=True)
    if os.environ.get("QUANTUM_WORLD_PROBE"):
        if not resource_jar:
            raise ValueError("QUANTUM_WORLD_PROBE requires QUANTUM_RESOURCE_JAR")
        subprocess.run([JAVA, "-Djava.awt.headless=true", "-cp", classpath,
                        "code.Rendering.WorldBottleneckProbe"], check=True)
    if LEVEL41_PROBE:
        if not resource_jar:
            raise ValueError("QUANTUM_LEVEL41_PROBE requires QUANTUM_RESOURCE_JAR")
        subprocess.run([JAVA, "-Djava.awt.headless=true", "-cp", classpath,
                        "code.Rendering.Level41Probe"], check=True)
    if STLKR_ALL_PROBE:
        if not resource_jar:
            raise ValueError("All-Stlkr probe requires QUANTUM_RESOURCE_JAR")
        subprocess.run([JAVA, "-Djava.awt.headless=true", "-cp", classpath,
                        "code.Rendering.StlkrLevelsProbe", str(resource_jar)], check=True)
    if LEVEL12_PROBE or LEVEL13_PROBE:
        if not resource_jar:
            raise ValueError("Stlkr level probe requires QUANTUM_RESOURCE_JAR")
        subprocess.run([JAVA, "-Djava.awt.headless=true",
                        "-Dlevel.probe=" + ("12" if LEVEL12_PROBE else "13"),
                        "-Dlevel13.fogOff=" + ("true" if os.environ.get("QUANTUM_LEVEL12_FOG_OFF" if LEVEL12_PROBE else "QUANTUM_LEVEL13_FOG_OFF") else "false"),
                        "-cp", classpath, "code.Rendering.Level13Probe", str(resource_jar)], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.ModeOptimizationAuditTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.M3GTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SpriteTest"], check=True)
    if not os.environ.get("QUANTUM_SPRITE_BATCH_REFERENCE"):
        subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SpriteBatchTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.MulSpriteTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.PerspectiveToggleTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.BackfaceTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.BatchTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.OpaqueTextureTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.MaterialPrepTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FogTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.CpuOptimizationTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FrameEffectsTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FrameTransactionTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.ImagePresentationTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SkyBackgroundTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.WorldClipTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.Arm9PreparationTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.MaterialModesTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.NativePrepTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.NativeLightingTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.Mode5BlendTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.WeaponOmniTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.GlassTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.ModulateX2Test"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.AlphaModeTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SkyFlashTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SolidSkyTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.GradientSkyTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.PanoramaTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.CombinedPassTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.DitherDisabledTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.Mode11DecalTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.Mode12FogTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.GeometryTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FogPackingTest"], check=True)
    if not os.environ.get("QUANTUM_FOG_REBASE_REFERENCE"):
        subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FogRebaseTest"], check=True)
    if not os.environ.get("QUANTUM_FOG_SOLID_REFERENCE"):
        subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FogSolidTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.RenderStatsTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SkyTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.SkyBatchTest", str(TEST / "sky-stream-golden.txt")], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.LinesTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.CheapRainTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.RainSimulationTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.StaticLightTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.BakedNoFogTest"], check=True)
    subprocess.run([JAVA, "-cp", classpath, "code.Rendering.MuzzleGlowTest"], check=True)
    if os.environ.get("QUANTUM_FRAME_PROFILE"):
        for test in ("FrameTransactionTest", "ImagePresentationTest", "CombinedPassTest", "Mode5BlendTest", "WorldClipTest", "Mode11DecalTest", "Mode12FogTest", "Arm9PreparationTest", "FogRebaseTest", "FogSolidTest", "SpriteBatchTest"):
            if test == "FogRebaseTest" and os.environ.get("QUANTUM_FOG_REBASE_REFERENCE"):
                continue
            if test == "SpriteBatchTest" and os.environ.get("QUANTUM_SPRITE_BATCH_REFERENCE"):
                continue
            subprocess.run([JAVA, "-cp", classpath, "code.Rendering.FrameProfileSuiteRunner",
                            "code.Rendering." + test], check=True)
    # Remove the optional API for a real class-loading fallback test.
    shutil.rmtree(classes / "javax/microedition/m3g")
    no_m3g_api = temp / "api-without-m3g.jar"
    with zipfile.ZipFile(API) as src, zipfile.ZipFile(no_m3g_api, "w") as dst:
        for name in src.namelist():
            if not name.startswith("javax/microedition/m3g/"):
                dst.writestr(name, src.read(name))
    for options in ([], ["hardware-perspective-off"]):
        subprocess.run([JAVA, "-cp", os.pathsep.join(map(str, (classes, APP, no_m3g_api))),
                        "code.Rendering.MissingM3GTest"] + options, check=True)
