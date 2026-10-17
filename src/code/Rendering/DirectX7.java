package code.Rendering;

import code.HUD.DeveloperMenu;
import code.Rendering.Meshes.Mesh;
import code.Math.Matrix;
import javax.microedition.lcdui.Graphics;

public final class DirectX7 {

   public static final int SOFTWARE = 0, M3G = 1;
   private int rendererMode = SOFTWARE;
   private SceneRenderer sceneRenderer;
   private boolean rendererFailed, nativePass;
   private boolean staticLightmapPass;
   private int[] frameBackup;
   private String rendererError;
   private MuzzleGlow muzzleGlow;
   private FrameEffects frameEffects;

   public int width, height;
   public int[] display;
   public boolean flushAlpha = false;
   public Matrix camera, invCamera, finalCamera;
   public int centreX, centreY;
   public int distX, distY;
   public int fovX, fovY;
   
   public static int fDist = 1;
   public static int drDist = 30000000;
   public static int waterDistance = 10000;
   public static byte standartDrawmode = 0;
   public static int fogc = 0x5500ff;
   public static short lightdirx = 0;
   public static short lightdiry = 0;
   public static short lightdirz = 0;
   
   private final RenderObjectBuffer buffer;
   private RenderObjectBuffer backgroundBuffer;
   private boolean backgroundPending;
   public int lx,ly,lz = 0;
   public int shootIntensity = 0;
   public int shootLength = 0;
   public static boolean useAutoWMove = true; //wtf is this
   public boolean flashlightEnabled = false;

   public DirectX7(int width, int height) {

this.width = width;
this.height = height;

camera = new Matrix();
invCamera = new Matrix();
finalCamera = new Matrix();
buffer = new RenderObjectBuffer();
resize(width,height);
lx=ly=lz=0;
}
   
public void resize(int width, int height) {
resetQueues();
releaseRenderer();
rendererFailed = false;
rendererError = null;
this.width = width;
this.height = height;

display = new int[width * height];

centreX = width / 2;
centreY = height / 2;

updateFov(74);
}

public final void updateFov(int fov) {
distX = (int) (centreX/Math.tan(fov*Math.PI/360));
distY = (int) (centreY/Math.tan(fov*Math.PI/360));
distX = distX*height/width;

fovY=fov;
fovX=fov*width/height;
}
   public final void destroy() {
resetQueues();
releaseRenderer();
backgroundBuffer = null;

DirectX7.fDist = 1;
DirectX7.drDist = 30000000;
DirectX7.standartDrawmode = 0;
DirectX7.fogc = 0x5500ff;
camera = invCamera = finalCamera = null;
this.buffer.resetTex();
   }


   public final int getWidth() {
      return this.width;
   }

   public final int getHeight() {
      return this.height;
   }

   public final int[] getDisplay() {
      return this.display;
   }

   public final void setCamera(Matrix matrix) {
      invCamera.set(matrix);
      camera.set(matrix);
      invCamera.invert();
   }

   public final Matrix getInvCamera() {
      return invCamera;
   }

   public final Matrix getCamera() {
      return camera;
   }

   public final Matrix computeFinalMatrix(Matrix matrix) {
      finalCamera.mul(invCamera, matrix);
      return finalCamera;
   }

    public final void addRenderObject(RenderObject obj) {
        addRenderObject(obj, 0, 0, this.width, this.height);
    }

    public final void addRenderObject(RenderObject obj, int x1, int y1, int x2, int y2) {
        buffer.addRenderObject(obj, x1, y1, x2, y2);
    }

    public final void addRenderObject(RenderObject obj, Texture tex) {
        buffer.addRenderObject(obj, tex, 0, 0, this.width, this.height);

    }

    public final void addRenderObjectDT(RenderObject obj, Texture tex) {
        buffer.addRenderObjectDT(obj, tex, 0, 0, this.width, this.height);
    }

    public final void addMesh(Mesh mesh, int x1, int y1, int x2, int y2) {
        buffer.addRenderObjects(mesh.getPolygons(), mesh.getTexture(), x1, y1, x2, y2);
    }

    public final void addMesh(Mesh mesh, int x1, int y1, int x2, int y2, MultyTexture tex) {
        buffer.addRenderObjects(mesh.getPolygons(), tex, x1, y1, x2, y2);
    }


   /** Changing the requested mode also permits a fresh attempt after a failure. */
   public void setRenderer(int mode) {
       if(mode != SOFTWARE && mode != M3G) throw new IllegalArgumentException("renderer");
       if(mode == rendererMode) return;
       releaseRenderer();
       rendererMode = mode;
       rendererFailed = false;
       rendererError = null;
   }

   public int getRenderer() { return rendererMode; }
   public String getRendererError() { return rendererError; }
   public boolean isM3GActive() { return sceneRenderer != null && !rendererFailed; }

   /** Also call after modifying a texture's public pixel array in place. */
   public void releaseRenderer() {
       nativePass = false;
       if(sceneRenderer != null) {
           try { sceneRenderer.dispose(); }
           catch(RuntimeException ex) { }
           catch(NoClassDefFoundError ex) { }
       }
       sceneRenderer = null;
       frameBackup = null;
       muzzleGlow = null;
       frameEffects = null;
   }

   private void failRenderer(String message) {
       releaseRenderer();
       rendererFailed = true;
       rendererError = message;
       System.out.println("M3G disabled: " + message);
   }

   /** M3G deliberately disables legacy dithering materials, treating them as 0. */
   public static byte m3gDrawMode(byte mode) {
       return mode >= 10 && mode <= 12 ? (byte)0 : mode;
   }

   /** Shared dispatch, including the polygons of animated MeshImage objects. */
   public void drawRenderObject(RenderObject object, Texture texture, boolean fast) {
       if(nativePass) {
           if(sceneRenderer.draw(this, object, texture, fast)) return;
           // CPU materials/unsupported sprites must see all prior M3G pixels.
           sceneRenderer.finish(this);
       }
       byte originalMode = texture == null ? 0 : texture.drawmode;
       boolean alias = rendererMode == M3G && texture != null && originalMode >= 10 && originalMode <= 12;
       if(alias) texture.drawmode = 0;
       boolean previous = staticLightmapPass;
       staticLightmapPass = rendererMode == M3G && texture != null
               && (texture.drawmode == 9 || texture.drawmode == 13);
       try {
           if(fast) object.renderFast(this, texture);
           else object.render(this, texture);
       } finally {
           staticLightmapPass = previous;
           if(alias) texture.drawmode = originalMode;
       }
   }

   public boolean isStaticLightmapPass() { return staticLightmapPass; }

   /** Static baked brightness plus distance fade; no camera or weapon inputs. */
   public int lightmapColor(int value, int rz) {
       value = Math.max(0,Math.min(255,value));
       if(fDist <= 1) return value;
       int fade = (int)Math.max(0L,Math.min(255L,-(long)rz*255/fDist));
       return value*(255-fade)/255;
   }

   /** Keep sky as an ordered prefix, never mixed into world depth sorting.
    * Software/debug/alpha retain the original immediate sky pass. */
   public void renderBackground() {
       if(!canRenderM3G() || backgroundPending) {render();return;}
       if(buffer.getSize()==0) return; // CPU gradient already painted its pixels.
       TMPElement[] items=buffer.getBuffer();
       int count=buffer.getSize();
       try {
           if(backgroundBuffer==null) backgroundBuffer=new RenderObjectBuffer();
           for(int i=0;i<count;i++)
               backgroundBuffer.addRenderObjectDT(items[i].obj,items[i].tex,0,0,width,height);
       } catch(OutOfMemoryError ex) {
           clearBackground();
           render();return; // Original immediate pass if deferral cannot allocate.
       }
       // Do not retain a duplicate sky/texture in the now-empty world pool.
       // Clear only after the entire copy succeeds, so OOM can render the original.
       for(int i=0;i<count;i++) {items[i].obj=null;items[i].tex=null;}
       buffer.reset();
       backgroundPending=true;
   }

   private void clearBackground() {
       backgroundPending=false;
       if(backgroundBuffer==null) return;
       TMPElement[] items=backgroundBuffer.getBuffer();
       for(int i=0;i<backgroundBuffer.getSize();i++) {items[i].obj=null;items[i].tex=null;}
       backgroundBuffer.reset();
   }

   private void resetQueues() {buffer.reset();clearBackground();}

   private void drawQueue(RenderObjectBuffer queue) {
       TMPElement[] elements = queue.getBuffer();
       for(int i = queue.getSize() - 1; i >= 0; i--)
           drawRenderObject(elements[i].obj, elements[i].tex, false);
   }

   private void drawBuffer() {
       if(backgroundPending) drawQueue(backgroundBuffer);
       drawQueue(buffer);
   }

   public final void render() {
       render(true);
   }

   /** Original rasterizer for a complete pass, without changing backend/cache. */
   public final void renderSoftware() {
       render(false);
   }

   /** Game frame effects run after world geometry but BEFORE its final readback.
    * Software callers retain their original GameScreen postprocessing. */
   public void renderWithEffects(byte[] vignette, boolean dead, boolean damaged) {
       if(rendererMode!=M3G) {render();return;}
       if(frameEffects==null) frameEffects=new FrameEffects();
       frameEffects.set(vignette,dead,damaged);
       render(true,frameEffects.active()?frameEffects:null);
   }

   private void render(boolean allowM3G) {render(allowM3G,null);}

   private void render(boolean allowM3G,FrameEffects effects) {
       lx = camera.m03;
       ly = camera.m13;
       lz = camera.m23;
       buffer.sort(0, buffer.getSize() - 1);
       if(backgroundPending) backgroundBuffer.sort(0,backgroundBuffer.getSize()-1);
       if(DeveloperMenu.renderPolygonsOverwrite) clearDisplay(0);
       // Mutable LCDUI Images do not preserve framebuffer alpha. Keep these
       // paths entirely on the original rasterizer rather than change semantics.
       boolean useM3G = allowM3G && rendererMode == M3G && !rendererFailed && !flushAlpha
               && !DeveloperMenu.renderPolygonsOverwrite && (buffer.getSize()>0 || backgroundPending || effects!=null);
       try {
           if(useM3G) {
               boolean saved = false;
               try {
                   if(sceneRenderer == null) sceneRenderer = (SceneRenderer)
                           Class.forName("code.Rendering.M3GSceneRenderer").newInstance();
                   if(frameBackup == null || frameBackup.length != display.length)
                       frameBackup = new int[display.length];
                   System.arraycopy(display, 0, frameBackup, 0, display.length);
                   saved = true;
                   if(effects!=null) sceneRenderer.prepareEffects(this,effects);
                   nativePass = true;
                   drawBuffer();
                   if(effects!=null) drawRenderObject(effects,null,false);
                   sceneRenderer.finish(this);
                   return;
               } catch(Exception ex) {
                   // Includes missing optional class and unsupported native targets.
                   rendererError = ex.toString();
               } catch(NoClassDefFoundError ex) {
                   rendererError = ex.toString();
               } catch(OutOfMemoryError ex) {
                   rendererError = "not enough memory for M3G";
               } finally {
                   nativePass = false;
               }
               // A failed batch can include both CPU and M3G objects. Restore
               // the pre-pass pixels and replay the complete sorted queue once.
               if(saved) System.arraycopy(frameBackup, 0, display, 0, display.length);
               failRenderer(rendererError);
           }
           drawBuffer();
           if(effects!=null) effects.render(this,null);
       } finally {
           nativePass = false;
           resetQueues();
       }
   }

   public boolean canRenderM3G() {
       return rendererMode == M3G && !rendererFailed && !flushAlpha
               && !DeveloperMenu.renderPolygonsOverwrite;
   }

   /** Post-effect overlay before LCDUI flush. False means use original LCDUI
    * weather; this method never consumes/replays the scene's object queue. */
   public boolean renderScreenLines(ScreenLines lines) {
       if(!canRenderM3G() || nativePass) return false;
       if(lines.count == 0) return true;
       return renderOverlay(lines);
   }

   /** Best-effort native overlay; no CPU replay and no scene-queue consumption. */
   public boolean renderOverlay(RenderObject effect) {
       if(!canRenderM3G() || nativePass) return false;
       boolean saved = false;
       try {
           if(sceneRenderer == null) sceneRenderer = (SceneRenderer)
                   Class.forName("code.Rendering.M3GSceneRenderer").newInstance();
           if(frameBackup == null || frameBackup.length != display.length)
               frameBackup = new int[display.length];
           System.arraycopy(display,0,frameBackup,0,display.length);
           saved = true;
           boolean drawn = sceneRenderer.draw(this,effect,null,false);
           sceneRenderer.finish(this);
           if(!drawn) System.arraycopy(frameBackup,0,display,0,display.length);
           return drawn;
       } catch(Exception ex) {
           rendererError = ex.toString();
       } catch(NoClassDefFoundError ex) {
           rendererError = ex.toString();
       } catch(OutOfMemoryError ex) {
           rendererError = "not enough memory for M3G effects";
       }
       if(saved) System.arraycopy(frameBackup,0,display,0,display.length);
       failRenderer(rendererError);
       return false;
   }

   public void startMuzzleGlow() {
       if(!canRenderM3G()) return;
       try {
           if(muzzleGlow == null) muzzleGlow = new MuzzleGlow();
           muzzleGlow.start(System.currentTimeMillis());
       } catch(OutOfMemoryError ex) { muzzleGlow = null; }
   }

   public boolean renderMuzzleGlow(code.Gameplay.Map.House house,int part) {
       if(!canRenderM3G() || muzzleGlow == null) return false;
       try {
           return muzzleGlow.prepare(this,house,part,System.currentTimeMillis()) && renderOverlay(muzzleGlow);
       } catch(Exception ex) {
           // A cosmetic ray/texture failure must not change combat or the frame.
           return false;
       } catch(OutOfMemoryError ex) { return false; }
   }

   public final void drawLine(int x1, int y1, int x2, int y2, int fat, int col) {
       int tmp;
       if(y1>y2) {tmp=y1; y1=y2; y2=tmp;tmp=x1; x1=x2; x2=tmp;}
       
       if( (x1>width && x2>width) || y1>height || (x2<0 && x1<0) || y2<0) return;
       int ySize = y2 - y1;
       int halfFat = fat/2;
       if(fat==1) halfFat = 0;
       
       int y=y1;
       if(y<0) y=0;
       int yEnd = y2;
       if(yEnd>height) yEnd = height;
       
       int perc,x,xx1,xx2;
       for(;y<yEnd;y++) {
           perc = y-y1;
           x = (x1*(ySize-perc) + x2*perc) / ySize;
           
           tmp = y*width;
           xx1 = x-halfFat;
           xx2 = xx1 + fat;
           if(xx1>width || xx2<0) continue;
           if(xx1<0) xx1=0;
           if(xx2>=width) xx2=width-1;
           xx1+=tmp; xx2+=tmp;
           
           while(xx1<xx2) {
               display[xx1] = col; xx1++;
           }
       }
       
       
   }

   public final void flush(Graphics g, int x, int y) {
       if(DeveloperMenu.renderPolygonsOverwrite) for(int i=display.length-1;i>=0;i--) {
           int c=display[i];
           if(c==0) display[i]=0xffffff;
           else if(c==1) display[i]=0x00ff00;
           else if(c==2) display[i]=0xffff00;
           else if(c==3) display[i]=0xff8800;
           else if(c==4) display[i]=0xff0000;
           else if(c==5) display[i]=0x880000;
       }
g.drawRGB(display, 0, width, x, y, width, height, flushAlpha);
   }


    public static void transform(Mesh ms, Matrix m) {
Vertex[] vers=ms.getVertices();

        final int m00 = m.m00 >> 2;
        final int m01 = m.m01 >> 2;
        final int m02 = m.m02 >> 2;
        final int m03 = m.m03;

        final int m10 = m.m10 >> 2;
        final int m11 = m.m11 >> 2;
        final int m12 = m.m12 >> 2;
        final int m13 = m.m13;

        final int m20 = m.m20 >> 2;
        final int m21 = m.m21 >> 2;
        final int m22 = m.m22 >> 2;
        final int m23 = m.m23;

        final int fp =  Matrix.fp-2;

        Vertex ver;
        int x, y, z;
        for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;

            ver.sx = (x * m00 >> fp) + (y * m01 >> fp) + (z * m02 >> fp) + m03;
            ver.sy = (x * m10 >> fp) + (y * m11 >> fp) + (z * m12 >> fp) + m13;
            ver.rz = (x * m20 >> fp) + (y * m21 >> fp) + (z * m22 >> fp) + m23;
        }



}
    
    public static void transform(Vertex[] vers, Matrix m) {

        if(m!=null) {
        final int m00 = m.m00 >> 2;
        final int m01 = m.m01 >> 2;
        final int m02 = m.m02 >> 2;
        final int m03 = m.m03;

        final int m10 = m.m10 >> 2;
        final int m11 = m.m11 >> 2;
        final int m12 = m.m12 >> 2;
        final int m13 = m.m13;

        final int m20 = m.m20 >> 2;
        final int m21 = m.m21 >> 2;
        final int m22 = m.m22 >> 2;
        final int m23 = m.m23;

        final int fp =  Matrix.fp-2;

        Vertex ver;
        int x, y, z;
        for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;

            ver.sx = (x * m00 >> fp) + (y * m01 >> fp) + (z * m02 >> fp) + m03;
            ver.sy = (x * m10 >> fp) + (y * m11 >> fp) + (z * m12 >> fp) + m13;
            ver.rz = (x * m20 >> fp) + (y * m21 >> fp) + (z * m22 >> fp) + m23;
        }
        } else {
            Vertex ver;
            for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];

            ver.sx = ver.x;
            ver.sy = ver.y;
            ver.rz = ver.z;
            } 
            
        }



}
    
    public void project(Vertex[] vertices,int rz) {
        Vertex ver = null;
        int sx, sy;
        for(int i=vertices.length-1; i>=0; i--) {
            ver = vertices[i];
            sx = ver.sx;
            sy = -ver.sy;

            if(rz <= 0) {
                sx = sx * distX / (rz + distX) ;
                sy = sy * distY / (rz + distY) ;
            } 
            
            ver.sx = (short)(sx+centreX);
            ver.sy = (short)(sy+centreY);
        }
    }

public static void returnMesh(Mesh ms) {
Vertex[] vers=ms.getVertices();

        Vertex ver;
        for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];
            ver.x=ver.sx;
            ver.y=ver.sy;
            ver.z=ver.rz;
        }
}


public static void transformSave(Object ms, Matrix m) {
Vertex[] vers;
if(ms instanceof Mesh) vers = ((Mesh)ms).getVertices();
else if(ms instanceof Vertex[]) vers = (Vertex[]) ms;
else return;

        final int m00 = m.m00 >> 2;
        final int m01 = m.m01 >> 2;
        final int m02 = m.m02 >> 2;
        final int m03 = m.m03;

        final int m10 = m.m10 >> 2;
        final int m11 = m.m11 >> 2;
        final int m12 = m.m12 >> 2;
        final int m13 = m.m13;

        final int m20 = m.m20 >> 2;
        final int m21 = m.m21 >> 2;
        final int m22 = m.m22 >> 2;
        final int m23 = m.m23;

        final int fp =  Matrix.fp-2;

        Vertex ver;
        int x, y, z;
        for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;
            ver.sx=x;
            ver.sy=y;
            ver.rz=z;
            ver.x = (x * m00 >> fp) + (y * m01 >> fp) + (z * m02 >> fp) + m03;
            ver.y = (x * m10 >> fp) + (y * m11 >> fp) + (z * m12 >> fp) + m13;
            ver.z = (x * m20 >> fp) + (y * m21 >> fp) + (z * m22 >> fp) + m23;
        }



}

    public static void transformReturn(Object ms) {
        Vertex[] vers;
        if (ms instanceof Mesh) {
            vers = ((Mesh) ms).getVertices();
        } else if(ms instanceof Vertex[]) {
            vers = (Vertex[]) ms;
        } else return;

        Vertex ver;
        int x, y, z;
        for(int i=vers.length-1; i>=0; i--) {
            ver = vers[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;
            ver.sx = x;
            ver.sy = y;
            ver.rz = z;
            ver.x = ver.sx;
            ver.y = ver.sy;
            ver.z = ver.rz;
        }

    }


    public void transformAndProjectVertices(Mesh ms, Matrix matrix) {
        Vertex[] vertices=ms.getVertices();


        final int m00 = matrix.m00 >> 2;
        final int m01 = matrix.m01 >> 2;
        final int m02 = matrix.m02 >> 2;
        final int m03 = matrix.m03;

        final int m10 = matrix.m10 >> 2;
        final int m11 = matrix.m11 >> 2;
        final int m12 = matrix.m12 >> 2;
        final int m13 = matrix.m13;

        final int m20 = matrix.m20 >> 2;
        final int m21 = matrix.m21 >> 2;
        final int m22 = matrix.m22 >> 2;
        final int m23 = matrix.m23;

        final int fp =  Matrix.fp-2;

        Vertex ver;
        int x, y, z;
        int sx, sy, rz;

        for(int i=vertices.length-1; i>=0; i--) {
            ver = vertices[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;

            sx = m03;
if(m00!=0) sx+= (x * m00 >> fp);
if(m01!=0) sx+= (y * m01 >> fp);
if(m02!=0) sx+= (z * m02 >> fp);

            sy = -m13;
if(m10!=0) sy-= (x * m10 >> fp);
if(m11!=0) sy-= (y * m11 >> fp);
if(m12!=0) sy-= (z * m12 >> fp);

rz = m23;
if(m20!=0) rz+= (x * m20 >> fp);
if(m21!=0) rz+= (y * m21 >> fp);
if(m22!=0) rz+= (z * m22 >> fp);

//int rz2=MathUtils.pLength(sx*100/centreX,sy*100/centreY,0);
if(rz<0) {
sx = sx *distX /(-rz/*+rz2*/ + distX) ;
sy = sy *distY /(-rz/*+rz2*/ + distY) ;
} /*else {
sx = sx *distX /(1+rz2 + distX) ;
sy = sy *distY /(1+rz2 + distY) ;
}*/

ver.sx = sx+centreX;//(this.height-sy)*this.width/this.height;
ver.sy = sy+centreY;//sx*this.height/this.width;
ver.rz = rz;

/*
//if(rz<0) {
sx = sx *3/distX;//*distX /(-rz + distX) ;
sy = sy *3/distY;//*distY /(-rz + distY) ;
//}

ver.sx = sx+centreX;//(this.height-sy)*this.width/this.height;
ver.sy = sy+centreY;//sx*this.height/this.width;
ver.rz = rz;
*/
        }

    



}


    public void transformAndProjectVertices(Vertex[] vertices, Matrix matrix) {


        final int m00 = matrix.m00 >> 2;
        final int m01 = matrix.m01 >> 2;
        final int m02 = matrix.m02 >> 2;
        final int m03 = matrix.m03;

        final int m10 = matrix.m10 >> 2;
        final int m11 = matrix.m11 >> 2;
        final int m12 = matrix.m12 >> 2;
        final int m13 = matrix.m13;

        final int m20 = matrix.m20 >> 2;
        final int m21 = matrix.m21 >> 2;
        final int m22 = matrix.m22 >> 2;
        final int m23 = matrix.m23;

        final int fp =  Matrix.fp-2;

        Vertex ver;
        int x, y, z;
        int sx, sy, rz;

        
        for(int i=vertices.length-1; i>=0; i--) {
            ver = vertices[i];
            x = ver.x;
            y = ver.y;
            z = ver.z;

            sx = m03;
if(m00!=0) sx+= (x * m00 >> fp);
if(m01!=0) sx+= (y * m01 >> fp);
if(m02!=0) sx+= (z * m02 >> fp);

            sy = -m13;
if(m10!=0) sy-= (x * m10 >> fp);
if(m11!=0) sy-= (y * m11 >> fp);
if(m12!=0) sy-= (z * m12 >> fp);

            rz = m23;
if(m20!=0) rz+= (x * m20 >> fp);
if(m21!=0) rz+= (y * m21 >> fp);
if(m22!=0) rz+= (z * m22 >> fp);

if(rz<0) {
sx = sx *distX /(-rz + distX) ;
sy = sy *distY /(-rz + distY) ;
}

ver.sx = sx+centreX;//(this.height-sy)*this.width/this.height;
ver.sy = sy+centreY;//sx*this.height/this.width;
ver.rz = rz;
       }

    



}

public static void setFogDist(int d)
{
fDist=d;
}

public static void setDrDist(int d)
{
drDist=d;
}


public final void clearDisplay(int col) {

   int length = display.length;
   int i=0;
   //if(Main.s60Optimization==true) {
   for(; length-i>5;i+=5) {
      display[i] = col;
      display[i+1] = col;
      display[i+2] = col;
      display[i+3] = col;
      display[i+4] = col;
   }
   //}

   for(; i<length;i++) {
      display[i] = col;
   }


}


}
