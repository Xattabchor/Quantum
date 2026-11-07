package code.Rendering;

import code.HUD.DeveloperMenu;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.MeshImage;
import code.Math.Matrix;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public final class DirectX7 {

   public static final int M3G = 1;
   private SceneRenderer sceneRenderer;
   private boolean rendererFailed, nativePass;
   private boolean m3gRequired;
   private boolean staticLightmapPass, expFogPass;
   private String rendererError;
   private FrameEffects frameEffects;
   private Image frameImage;
   private int[] frameImageBuffer;
   private boolean imagePresentationFailed;
   int frameDrawCalls,frameCpuBarriers,frameTextureUploads;
   /** Wall-clock cost of the last M3G frame, in milliseconds. Read by the
    *  stats line; zero until the first frame completes. */
   private int frameMs;
   private int peakDraws,peakBarriers,peakUploads;
   /** Slowest M3G frame inside the current stats window, in milliseconds.
    *  Peak, not average: the frame the player stumbles on is the worst one,
    *  and an average over a 250 ms window at 1 FPS is a single frame anyway. */
   private int peakFrameMs;
   private long statsNext;
   private String statsText;
   private boolean statsNative;

   /** Live instance for diagnostics/benchmark access from HUD menus. */
   public static DirectX7 current;

   /** Render-mode benchmark (lazily created). Null until first run. */
   public SpeedTest speedTest = null;

   /** Call once before sky/world preparation, NOT once per native segment. */
   public void beginFrameStats() {frameDrawCalls=frameCpuBarriers=frameTextureUploads=0;}
   public int getM3GDrawCalls() {return frameDrawCalls;}
   public int getM3GCpuBarriers() {return frameCpuBarriers;}
   public int getM3GTextureUploads() {return frameTextureUploads;}

   /** Cached HUD, per-frame peaks over a 250ms display window. No string per draw. */
   public String getM3GStatsText(long now) {
       peakDraws=Math.max(peakDraws,frameDrawCalls);
       peakBarriers=Math.max(peakBarriers,frameCpuBarriers);
       peakUploads=Math.max(peakUploads,frameTextureUploads);
       peakFrameMs=Math.max(peakFrameMs,frameMs);
       boolean active=isM3GActive();
       if(statsText==null || now>=statsNext || now<statsNext-250 || active!=statsNative) {
           // THE LINE IS 30 CHARACTERS WIDE. The HUD font is 8 px per glyph on
           // a 240 px screen and the screen edge clips the rest silently, which
           // is why the earlier version - marker appended to an already full
           // line - appeared to print nothing at all. Everything here is ordered
           // by what a dead lit pass needs first, and every part is trimmed to
           // the space that is actually left.
           final int HUD_CHARS = 30;
           // "M3G " / "CPU " cost four glyphs of a thirty-glyph line and were
           // the first thing to push the useful half off the edge. The marker
           // now names the renderer itself: LIT / LIT-off / LIT-DEAD when M3G
           // is driving, CPU / CPU-off / CPU-DEAD when it is not. Same two
           // facts, four characters cheaper - which is what buys back the
           // lit-poly count and lets the profile tail survive the clip.
           String marker = code.utils.Main.m3gLitWorld9 != 1
                   ? (active ? "LIT-off" : "CPU-off")
                   : litBrokenFlag() ? (active ? "LIT-DEAD" : "CPU-DEAD")
                   : (active ? "LIT" : "CPU");
           // A probe that rescued the draw names itself: that is the useful
           // fact, and it is short enough to survive the 30-char trim.
           String err = M3GLitRenderer.DIAG.length() > 0
                   ? M3GLitRenderer.DIAG : M3GLitRenderer.statsErr;
           StringBuffer b = new StringBuffer(40);
           b.append(marker);
           if(code.utils.Main.m3gLitSplit == 1) {
               // Stage split of the lit flush: u = Java-side per-bucket prep
               // (scale loop + VertexArray.set + setPositions), s = building
               // the TriangleStripArray, b = bind/setCamera/setFog, r = the
               // graphics.render() call. Sums to the flush total the normal
               // line reports.
               b.append(" u").append(M3GLitRenderer.statsPrepMs)
                .append(" s").append(M3GLitRenderer.statsStripMs)
                .append(" b").append(M3GLitRenderer.statsBindMs)
                .append(" r").append(M3GLitRenderer.statsRenderMs);
           } else if(err.length() > 0) {
               // A failure message is worth more than the counters: it names the
               // runtime call that threw. Give it the rest of the line.
               b.append(' ');
               int room = HUD_CHARS - b.length();
               b.append(err.length() <= room ? err : err.substring(0, Math.max(0, room)));
           } else {
               // POSITIONAL, not letter-coded. The previous form labelled the
               // two millisecond readings F and T and came back transcribed
               // twice with them the wrong way round - a diagnostic that can
               // be read two ways costs more than it saves. The order is
               // always: whole frame / lit flush, then the worst single
               // bucket. Draw calls are gone: they were the least informative
               // number here and the line was already at the 30-glyph clip.
               b.append(' ').append(peakFrameMs).append('/')
                .append(M3GLitRenderer.statsLitMs)
                .append(" b").append(M3GLitRenderer.statsLitBucketMs);
               // Everything below is optional and buys its glyphs: with four
               // digit millisecond readings the core is already 18 characters,
               // and a fixed layout pushed the profile tail - the part that
               // names the expensive pass - off the edge on exactly the frames
               // that were slow enough to make it interesting.
               // D/B/T is the documented convention for this very HUD
               // (docs/m3g.md): D = Graphics3D.render calls, B = intermediate
               // native->CPU barriers on a bound target, T = native Image2D
               // created on a cache miss. A healthy frame reads B=0 and T=0
               // with D ~20-60, so B and T are ALARMS and only spend glyphs
               // when they fire, while D is context and always worth two.
               // Reworking the line dropped all three, which threw away the
               // one counter that names a per-frame readback (B) and the one
               // that names a texture rebuild (T) - the two failures this
               // port is documented to fall into.
               if(b.length() + 4 <= HUD_CHARS) b.append(" D").append(peakDraws);
               if(peakBarriers > 0 && b.length() + 4 <= HUD_CHARS)
                   b.append(" B").append(peakBarriers);
               if(peakUploads > 0 && b.length() + 4 <= HUD_CHARS)
                   b.append(" T").append(peakUploads);
               if(M3GLitRenderer.statsLit > 0 && b.length() + 6 <= HUD_CHARS)
                   b.append(" l").append(M3GLitRenderer.statsLit);
               if(M3GLitRenderer.statsFail > 0 && b.length() + 8 <= HUD_CHARS)
                   b.append(" f").append(M3GLitRenderer.statsFail)
                    .append(" r").append(M3GLitRenderer.statsReason);
           }
           String line = b.toString();
           statsText = line.length() <= HUD_CHARS ? line : line.substring(0, HUD_CHARS);
           if(M3GSceneRenderer.profiling()) {
               // Give the pass breakdown whatever is left rather than a
               // fixed slice: a clipped tail that still names the worst pass
               // beats a complete tail that never reaches the screen.
               String tail = FrameProfile.hudLineCompact();
               int room = HUD_CHARS - statsText.length();
               if(room > 0)
                   statsText += tail.length() <= room ? tail : tail.substring(0, room);
           }
           if(speedTest != null && speedTest.ranOk) {
               statsText += " | " + speedTest.resultsLine();
           }
           peakDraws=peakBarriers=peakUploads=peakFrameMs=0;statsNext=now+250;statsNative=active;
           M3GLitRenderer.resetLitStats();
       }
       return statsText;
   }

   public int width, height;
   public int[] display;
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

current = this;

camera = new Matrix();
invCamera = new Matrix();
finalCamera = new Matrix();
buffer = new RenderObjectBuffer();
resize(width,height);
lx=ly=lz=0;
}
   
public void resize(int width, int height) {
resetQueues();
discardFrameImage();
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
discardFrameImage();
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

   /** Synchronize an image-only frame before exposing writable CPU pixels. */
   public final int[] getDisplay() {
      if(m3gRequired && rendererFailed) throw requiredFailure(rendererError);
      try {syncFrameImage();}
      catch(RuntimeException ex) {
          if(m3gRequired) throw requiredFailure(ex.toString());
          throw ex;
      } catch(OutOfMemoryError ex) {
          if(m3gRequired) throw requiredFailure("not enough memory for M3G snapshot");
          throw ex;
      }
      return this.display;
   }

   private void discardFrameImage() {frameImage=null;frameImageBuffer=null;}

   private void syncFrameImage() {
       if(frameImage==null) return;
       if(frameImageBuffer==display) {
           // Leave the image pending if getRGB throws: never expose stale pixels.
           frameCpuBarriers++;
           frameImage.getRGB(display,0,width,0,0,width,height);
       }
       discardFrameImage();
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


   /** Gameplay migration latch: the scene transaction is M3G-only. There is no
    * CPU rasterizer in this build at all - the flag simply turns on strict checks. */
   public void requireM3G() {
       m3gRequired=true;
   }

   public boolean isM3GRequired() {return m3gRequired;}

   /** Only an explicit user retry (or resize) clears a required backend failure. */
   public void retryM3G() {
       if(!m3gRequired) throw new IllegalStateException("M3G is not required");
       resetQueues();
       discardFrameImage();
       releaseRenderer();
       rendererFailed=false;
       rendererError=null;
       clearDisplay(0); // Never reuse partial failure pixels as an open-sky base.
   }

   private IllegalStateException requiredFailure(String message) {
       if(!rendererFailed || rendererError==null) rendererError=message;
       rendererFailed=true;
       // A partially submitted image must never be shown or read back as a frame.
       discardFrameImage();
       nativePass=false;
       if(sceneRenderer!=null) {
           try {sceneRenderer.dispose();}
           catch(RuntimeException ex) { }
           catch(NoClassDefFoundError ex) { }
           catch(OutOfMemoryError ex) { }
       }
       sceneRenderer=null;
       return new IllegalStateException("M3G required: "+rendererError);
   }

   private void checkRequiredState(boolean allowM3G) {
       if(rendererFailed) throw requiredFailure(rendererError);
       if(!allowM3G) throw requiredFailure("CPU rasterization disabled");
   }

   private void ensureNativeRenderer() throws Exception {
       if(sceneRenderer==null) sceneRenderer=(SceneRenderer)
               Class.forName("code.Rendering.M3GSceneRenderer").newInstance();
   }

   /** True when the true-3D pass gave up; the stats line says so out loud
    *  instead of showing a healthy build with a blank world. */
   private boolean litBrokenFlag() {
       return sceneRenderer instanceof M3GSceneRenderer
               && ((M3GSceneRenderer) sceneRenderer).isLitBroken();
   }

   public int getRenderer() { return M3G; }
   public String getRendererError() { return rendererError; }
   public boolean isM3GActive() { return sceneRenderer != null && !rendererFailed; }

   /** Exposes the live M3GSceneRenderer for benchmark/diagnostic paths that
    * need to submit raw textured triangles through the hardware pipe. */
   public SceneRenderer getSceneRenderer() { return sceneRenderer; }

   /** Run the M3G render-mode benchmark (all modes 0..13) and store results
    * in {@link #speedTest}. Call from a native render pass. */
   public void runSpeedTest(int iterations) {
       if(speedTest == null) speedTest = new SpeedTest();
       speedTest.runBenchmark(this, iterations);
   }

   /** Also call after modifying a texture's public pixel array in place. */
   /** Called when the world is replaced (level load/restart). The sticky
    *  lit-pass latch must not survive into the next level: a runtime failure
    *  while level 1 was starting used to leave hardware lighting dead for the
    *  whole session, which looks exactly like the feature being ignored. */
   public void reloadLitPass() {
       if(sceneRenderer instanceof M3GSceneRenderer)
           ((M3GSceneRenderer) sceneRenderer).reloadLitPass();
   }

   public void releaseRenderer() {
       syncFrameImage();
       imagePresentationFailed=false;
       nativePass = false;
       if(sceneRenderer != null) {
           try { sceneRenderer.dispose(); }
           catch(RuntimeException ex) { }
           catch(NoClassDefFoundError ex) { }
       }
       sceneRenderer = null;
       frameEffects = null;
   }

   /** Native material numbers: 11 DECAL, 12 EXP fog. */
   public static byte m3gDrawMode(byte mode) {
       return mode;
   }

   /** CPU equivalent of native MODULATE; source alpha is handled as cutout. */
   static int modulateColor(int source, int background) {
       return (background & 0xff000000)
               | ((((source >>> 16) & 255) * ((background >>> 16) & 255) / 255) << 16)
               | ((((source >>> 8) & 255) * ((background >>> 8) & 255) / 255) << 8)
               | ((source & 255) * (background & 255) / 255);
   }

   /** CPU equivalent of MODULATE_X2, including saturation of bright channels. */
   static int modulateColorX2(int source, int background) {
       return (background & 0xff000000)
               | (Math.min(255, ((source >>> 16) & 255) * ((background >>> 16) & 255) * 2 / 255) << 16)
               | (Math.min(255, ((source >>> 8) & 255) * ((background >>> 8) & 255) * 2 / 255) << 8)
               | Math.min(255, (source & 255) * (background & 255) * 2 / 255);
   }

   /** MUL assets store brightness in blue; 69 is their legacy neutral key. */
   static int spriteModulateSource(int pixel, int color) {
       int value=pixel&255;
       if(pixel==0 || value==69) return 0xffffff;
       int mask=0xffffff;
       switch(color) {
           case 1: mask=0xff0000;break;
           case 2: mask=0x00ff00;break;
           case 3: mask=0x0000ff;break;
           case 4: mask=0xff00ff;break;
           case 5: mask=0xffff00;break;
           case 6: mask=0x00ffff;break;
       }
       return ((value*0x010101)&mask) | (0xffffff^mask);
   }

   /** Selected-M3G sprite recovery, without reviving the legacy saturating MUL. */
   public static int modulateSpritePixel(int pixel, int background, int color) {
       return modulateColor(spriteModulateSource(pixel,color),background);
   }

   /** API-free recovery of mode 5 ambient 25% + directional 75%.
    * The CPU samplers use /256; return 64..256 so full diffuse remains full RGB. */
   public static int mode5Shade(int nx,int ny,int nz) {
       double normal=Math.sqrt((double)nx*nx+(double)ny*ny+(double)nz*nz);
       double light=Math.sqrt((double)lightdirx*lightdirx+(double)lightdiry*lightdiry+(double)lightdirz*lightdirz);
       double dot=0;
       if(normal>0 && light>0) dot=((double)nx*lightdirx+(double)ny*lightdiry+(double)nz*lightdirz)/(normal*light);
       dot=Math.max(0,Math.min(1,dot));
       return 64+(int)(192*dot+0.5);
   }

   /** Shared dispatch, including the polygons of animated MeshImage objects. */
   public void drawRenderObject(RenderObject object, Texture texture, boolean fast) {
       if(nativePass) {
           // The exact engine MeshImage wrapper only animates/projects/queues
           // polygons; it never paints display. Let its children decide whether
           // a CPU barrier is needed, instead of round-tripping every NPC/mesh.
           if(object instanceof MeshImage && object.getClass()==MeshImage.class
                   && ((MeshImage)object).getMesh()!=null
                   && ((MeshImage)object).getMesh().getClass()==Mesh.class) {
               if(fast) object.renderFast(this,texture);else object.render(this,texture);
               return;
           }
           if(sceneRenderer.draw(this, object, texture, fast)) return;
           throw requiredFailure("unsupported object "+object.getClass().getName()
                   +" material="+(texture==null?"none":String.valueOf(texture.drawmode)));
       }
       throw requiredFailure("draw outside native frame: "+object.getClass().getName());
   }

   public boolean isExpFogPass() {return expFogPass;}
   public boolean isStaticLightmapPass() { return staticLightmapPass; }

   /** Selected-M3G baked brightness only: no fog, distance or dynamic light.
    * Keep rz in the signature for compatibility with existing CPU callers. */
   public int lightmapColor(int value, int rz) {
       return Math.max(0,Math.min(255,value));
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
       // The background queue holds the sky, the main queue the world and the
       // sprites. Splitting here is what separates "sky" from "world" in the
       // profile; without the split every geometry pass lands in one bucket.
       if(backgroundPending) {
           if(M3GSceneRenderer.profiling()) FrameProfile.mark(FrameProfile.SKY);
           drawQueue(backgroundBuffer);
           if(M3GSceneRenderer.profiling()) {
               FrameProfile.close(FrameProfile.SKY);
               FrameProfile.mark(FrameProfile.WORLD);
           }
       }
       drawQueue(buffer);
   }

   public final void render() {
       render(true,null,null,false);
   }

   /** Game frame as an LCDUI Image; pixels stay lazy until getDisplay(). */
   public boolean renderGameFrameImage(byte[] vignette,boolean dead,boolean damaged,
           code.Gameplay.Map.House house,int part,ScreenLines weather) {
       return render(true,prepareFrameEffects(vignette,dead,damaged),weather,!imagePresentationFailed);
   }

   /** Only a known complete FIRST background makes previous pixels irrelevant.
    * Sky meshes need an explicit closed/opaque/viewport certificate and base;
    * a portal crop or world geometry alone never makes this assumption. */
   private boolean opaqueFrameBackground() {
       if(!backgroundPending || backgroundBuffer.getSize()==0) return false;
       RenderObject object=backgroundBuffer.getBuffer()[backgroundBuffer.getSize()-1].obj;
       if(object instanceof SolidSky || object instanceof SkyFlash) return true;
       if(object instanceof code.Rendering.Meshes.SkyMesh)
           return ((code.Rendering.Meshes.SkyMesh)object).clearsBackground(this);
       if(object instanceof PanoramaSky) {
           PanoramaSky sky=(PanoramaSky)object;
           return sky.x1==0 && sky.y1==0 && sky.x2==width && sky.y2==height && sky.startY<=sky.endY;
       }
       return false;
   }

   private FrameEffects prepareFrameEffects(byte[] vignette,boolean dead,boolean damaged) {
       if(frameEffects==null) frameEffects=new FrameEffects();
       frameEffects.set(vignette,dead,damaged);
       return frameEffects.active()?frameEffects:null;
   }

   private boolean render(boolean allowM3G,FrameEffects effects,ScreenLines weather,boolean imageOutput) {
       return renderRequired(allowM3G,effects,weather,imageOutput);
   }

   /** Required gameplay transaction: no backup, CPU dispatch, or whole-frame
    * replay. CPU resource barriers/readback for partial frames remain allowed. */
   private boolean renderRequired(boolean allowM3G,FrameEffects effects,ScreenLines weather,boolean imageOutput) {
       long frameStartMs = System.currentTimeMillis();
       try {
           checkRequiredState(allowM3G);
           lx=camera.m03;ly=camera.m13;lz=camera.m23;
           buffer.sort(0,buffer.getSize()-1);
           if(backgroundPending) backgroundBuffer.sort(0,backgroundBuffer.getSize()-1);
           if(imageOutput && opaqueFrameBackground()) discardFrameImage();
           else syncFrameImage();
           ensureNativeRenderer();
           final boolean prof = M3GSceneRenderer.profiling();
           if(prof) FrameProfile.frameStart();
           if(effects!=null) sceneRenderer.prepareEffects(this,effects);
           if(weather!=null) sceneRenderer.prepareWeather(this,weather);
           if(prof) {FrameProfile.close(FrameProfile.SETUP);FrameProfile.mark(FrameProfile.WORLD);}
           // Refresh the true-3D lit-pass camera/lights (mode-6 polys) — without
           // this the hardware-lit pass never exists in the required path.
           if(sceneRenderer instanceof M3GSceneRenderer)
               ((M3GSceneRenderer)sceneRenderer).beginFrame(this);
           nativePass=true;
           if(prof) {FrameProfile.close(FrameProfile.WORLD);FrameProfile.mark(FrameProfile.SPRITES);}
           drawBuffer();
           if(prof) {FrameProfile.close(FrameProfile.SPRITES);FrameProfile.mark(FrameProfile.EFFECTS);}
           if(effects!=null) drawRenderObject(effects,null,false);
           if(prof) {FrameProfile.close(FrameProfile.EFFECTS);FrameProfile.mark(FrameProfile.WEATHER);}
           if(weather!=null && weather.count>0 && !sceneRenderer.draw(this,weather,null,false))
               throw requiredFailure("unsupported native weather");
           if(prof) {FrameProfile.close(FrameProfile.WEATHER);FrameProfile.mark(FrameProfile.FINISH);}
           if(imageOutput) {
               frameImage=sceneRenderer.finishImage(this);
               frameImageBuffer=frameImage==null?null:display;
           } else sceneRenderer.finish(this);
           if(prof) {FrameProfile.close(FrameProfile.FINISH);FrameProfile.frameEnd(0);}
           return weather!=null;
       } catch(Exception ex) {
           throw requiredFailure(ex.toString());
       } catch(NoClassDefFoundError ex) {
           throw requiredFailure(ex.toString());
       } catch(OutOfMemoryError ex) {
           throw requiredFailure("not enough memory for M3G");
       } finally {
           // Measured after resetQueues(): it is part of the frame the player
           // waits for, and a cost that survives the frame is exactly the kind
           // of thing this number exists to expose. Two clock reads per frame
           // are nothing next to even a healthy 40 ms frame.
           frameMs=(int)(System.currentTimeMillis()-frameStartMs);
           nativePass=false;
           resetQueues();
       }
   }

   private boolean renderRequiredOverlay(RenderObject effect) {
       try {
           checkRequiredState(true);
           if(nativePass) throw requiredFailure("nested native overlay");
           syncFrameImage();
           ensureNativeRenderer();
           if(!sceneRenderer.draw(this,effect,null,false))
               throw requiredFailure("unsupported overlay "+effect.getClass().getName());
           sceneRenderer.finish(this);
           return true;
       } catch(Exception ex) {
           throw requiredFailure(ex.toString());
       } catch(NoClassDefFoundError ex) {
           throw requiredFailure(ex.toString());
       } catch(OutOfMemoryError ex) {
           throw requiredFailure("not enough memory for M3G overlay");
       }
   }

   public boolean canRenderM3G() { return !rendererFailed; }

   /** Post-effect overlay before LCDUI flush. False means use original LCDUI
    * weather; this method never consumes/replays the scene's object queue. */
   public boolean renderScreenLines(ScreenLines lines) {
       checkRequiredState(true);
       if(nativePass) throw requiredFailure("nested native weather");
       if(!canRenderM3G()) return false;
       if(lines.count == 0) return true;
       return renderRequiredOverlay(lines);
   }

   /** Native overlay; no CPU replay. Screenshots keep their own copy path. */
   public boolean renderOverlay(RenderObject effect) {
       return renderRequiredOverlay(effect);
   }

   /** Canvas-only foreground: never materialize/replay the world Image. */
   public void renderCanvasImages(Graphics target,ScreenImages images) {
       try {
           if(!m3gRequired) throw new IllegalStateException("foreground requires M3G scene policy");
           checkRequiredState(true);
           if(nativePass) throw requiredFailure("foreground inside world transaction");
           if(images.count==0) return;
           ensureNativeRenderer();
           sceneRenderer.drawCanvasImages(this,images,target);
       } catch(Exception ex) {
           throw requiredFailure(ex.toString());
       } catch(NoClassDefFoundError ex) {
           throw requiredFailure(ex.toString());
       } catch(OutOfMemoryError ex) {
           throw requiredFailure("not enough memory for M3G foreground");
       } finally {
           images.clear();
       }
   }

   /** New level-local RGB is reset to black on missing/invalid input. */
   public static void configureM3GBlend(String value) {Mode5Blend.configure(value);}
   public static int getM3GBlendColor() {return Mode5Blend.color();}
   public static void configureM3GExpFog(String color,String density) {Mode12Fog.configure(color,density);}
   public static int getM3GExpFogColor() {return Mode12Fog.color();}
   public static float getM3GExpFogDensity() {return Mode12Fog.density();}

   /** Compatibility hooks: mode 5 no longer has lights or a shot pulse. */
   public Mode5WeaponLight getMode5WeaponLight() {return null;}
   public void startWeaponLight() { }
   public void startMuzzleGlow() { }

   /** Retained for callers of the removed fake screen-space illumination. */
   public boolean renderMuzzleGlow(code.Gameplay.Map.House house,int part) {return false;}

   public final void drawLine(int x1, int y1, int x2, int y2, int fat, int col) {
       if(m3gRequired) throw requiredFailure("CPU line rasterization disabled");
       if(frameImage!=null) syncFrameImage();
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
       try {flushFrame(g,x,y);}
       catch(RuntimeException ex) {
           if(m3gRequired) throw requiredFailure(ex.toString());
           throw ex;
       } catch(OutOfMemoryError ex) {
           if(m3gRequired) throw requiredFailure("not enough memory for M3G presentation");
           throw ex;
       }
   }

   private void flushFrame(Graphics g,int x,int y) {
       if(m3gRequired && rendererFailed) throw requiredFailure(rendererError);
       if(frameImage!=null && frameImageBuffer==display && !imagePresentationFailed) {
           try {g.drawImage(frameImage,x,y,Graphics.TOP|Graphics.LEFT);return;}
           catch(RuntimeException ex) {imagePresentationFailed=true;}
           catch(OutOfMemoryError ex) {imagePresentationFailed=true;}
           // Image blitting unsupported on this device: retain M3G rasterization
           // but fall back to the original eager readback/drawRGB presentation.
       }
       syncFrameImage();
       if(DeveloperMenu.renderPolygonsOverwrite) for(int i=display.length-1;i>=0;i--) {
           int c=display[i];
           if(c==0) display[i]=0xffffff;
           else if(c==1) display[i]=0x00ff00;
           else if(c==2) display[i]=0xffff00;
           else if(c==3) display[i]=0xff8800;
           else if(c==4) display[i]=0xff0000;
           else if(c==5) display[i]=0x880000;
       }
g.drawRGB(display, 0, width, x, y, width, height, false);
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
ver.recordView(sx,-sy,rz);
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

ver.recordView(sx,-sy,rz);
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
   discardFrameImage(); // A complete CPU fill needs no previous pixels.

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
