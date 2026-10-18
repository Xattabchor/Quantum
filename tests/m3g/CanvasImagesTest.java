package code.Rendering;

import java.util.Arrays;
import java.lang.reflect.Field;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Axis-aligned native command reconstruction, NOT GPU rasterization. */
public final class CanvasImagesTest {
    static void check(boolean b,String why) {M3GTest.check(b,why);}
    static Image image(int w,int h) {
        Image source=Image.createImage(w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int alpha=new int[]{0,64,128,255}[(x+y)%4];
            source.pixels[y*w+x]=(alpha<<24)|((x*17&255)<<16)|((y*13&255)<<8)|((x+y)*23&255);
        }
        return source;
    }
    static int blend(int source,int target) {
        int alpha=source>>>24,out=0xff000000;
        for(int shift=0;shift<24;shift+=8)
            out|=((((source>>shift)&255)*alpha+((target>>shift)&255)*(255-alpha))/255)<<shift;
        return out;
    }
    static int[] reference(ScreenImages q,int bg) {
        int[] out=new int[q.width*q.height];Arrays.fill(out,bg);
        for(int i=0;i<q.count;i++) {
            int dx=q.get(i,1),dy=q.get(i,2),w=q.get(i,3),h=q.get(i,4);
            int l=Math.max(dx,q.get(i,8)),r=Math.min(dx+w,q.get(i,10));
            int t=Math.max(dy,q.get(i,9)),b=Math.min(dy+h,q.get(i,11));
            for(int y=t;y<b;y++)for(int x=l;x<r;x++) {
                int c=q.get(i,7)|0xff000000;
                if(q.image(i)!=null) {
                    int sx=q.get(i,5)+(q.get(i,7)==2?w-1-(x-dx):x-dx),sy=q.get(i,6)+y-dy;
                    c=q.image(i).pixels[sy*q.image(i).getWidth()+sx];
                }
                out[y*q.width+x]=blend(c,out[y*q.width+x]);
            }
        }
        return out;
    }
    static int[] nativeCommands(int first,int width,int height,int bg) {
        int[] out=new int[width*height];Arrays.fill(out,bg);
        for(int n=first;n<Graphics3D.history.size();n++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(n);
            check(!d.fog && !d.lit && !d.depthTest && !d.depthWrite && d.viewportWidth==width && d.viewportHeight==height && d.cull==PolygonMode.CULL_NONE,"foreground world state leaked");
            float eye=-d.positions[2]*d.scale;
            int l=Math.round((d.projection[0]*d.positions[0]*d.scale/eye-d.projection[2]+1)*width/2);
            int t=Math.round((1-d.projection[5]*d.positions[1]*d.scale/eye+d.projection[6])*height/2);
            int r=Math.round((d.projection[0]*d.positions[3]*d.scale/eye-d.projection[2]+1)*width/2);
            int b=Math.round((1-d.projection[5]*d.positions[7]*d.scale/eye+d.projection[6])*height/2);
            check(l>=0 && t>=0 && r<=width && b<=height,"native foreground escaped clip: "+l+","+t+","+r+","+b+" eye="+eye+" projection="+Arrays.toString(d.projection));
            for(int y=t;y<b;y++)for(int x=l;x<r;x++) {
                int c=0xff000000|((d.colors[0]&255)<<16)|((d.colors[1]&255)<<8)|(d.colors[2]&255);
                if(d.textured) {
                    check(d.function==Texture2D.FUNC_MODULATE && d.blend==CompositingMode.ALPHA
                            && d.filter==Texture2D.FILTER_NEAREST,"foreground material changed");
                    Image2D image=d.source.image;
                    float u=(d.coords[0]+(x+0.5f-l)/(r-l)*(d.coords[2]-d.coords[0]))*d.uvScale;
                    float v=(d.coords[1]+(y+0.5f-t)/(b-t)*(d.coords[7]-d.coords[1]))*d.uvScale;
                    int sx=Math.min(image.width-1,Math.max(0,(int)(u*image.width)));
                    int sy=Math.min(image.height-1,Math.max(0,(int)(v*image.height)));
                    int p=(sy*image.width+sx)*4;byte[] bytes=image.pixels;
                    c=((bytes[p+3]&255)<<24)|((bytes[p]&255)<<16)|((bytes[p+1]&255)<<8)|(bytes[p+2]&255);
                } else check(d.blend==CompositingMode.REPLACE,"solid rect is not opaque");
                out[y*width+x]=blend(c,out[y*width+x]);
            }
        }
        return out;
    }
    static DirectX7 required() {DirectX7 g=M3GTest.renderer();g.requireM3G();return g;}
    public static void main(String[] args) throws Exception {
        Image source=image(271,233);int[] original=source.pixels.clone();
        for(int anchor:new int[]{0,20,24,36,40,3,17,33})for(boolean mirror:new boolean[]{false,true}) {
            Graphics3D.history.clear();DirectX7 g=required();ScreenImages q=new ScreenImages();q.reset(301,279);
            q.setClip(3,7,293,261);
            q.drawRegion(source,1,2,269,229,mirror?2:0,150,139,anchor);
            q.setColor(0x123456);q.fillRect(-10,10,25,13);q.drawRect(20,30,73,9);
            int[] expected=reference(q,0xff456789);int count=q.count;
            Graphics canvas=Image.createImage(301,279).getGraphics();canvas.setClip(2,4,17,18);
            int first=Graphics3D.history.size(),binds=Graphics3D.binds,reads=source.reads;
            g.renderCanvasImages(canvas,q);
            check(Arrays.equals(expected,nativeCommands(first,301,279,0xff456789)),"native tile/clip/alpha equation differs from source, anchor="+anchor+" mirror="+mirror);
            check(count>0 && q.count==0 && q.image(0)==null,"commands retained Image references");
            check(Graphics3D.binds==binds+1 && Graphics3D.target==null,"foreground target transaction");
            check(canvas.getClipX()==2 && canvas.getClipY()==4 && canvas.getClipWidth()==17 && canvas.getClipHeight()==18,"caller clip not restored");
            check(source.reads>reads && Arrays.equals(original,source.pixels),"source image mutated");g.destroy();
        }
        System.out.println("PASS Canvas foreground: NPOT tiles + original RGBA / anchors / subregions / mirrors / clips / rect outlines / ordered command reconstruction");

        for(int limit:new int[]{64,127}) {
            System.setProperty("m3g.maxTexture",String.valueOf(limit));DirectX7 small=required();
            try {
                ScreenImages tiles=new ScreenImages();Graphics target=Image.createImage(301,279).getGraphics();
                for(int frame=0;frame<2;frame++) {
                    tiles.reset(301,279);tiles.drawRegion(source,0,0,271,233,2,4,7,20);
                    int[] expected=reference(tiles,0xff456789);int first=Graphics3D.history.size();
                    int reads=Image.getRGBCalls,uploads=Graphics3D.uploads;
                    small.renderCanvasImages(target,tiles);
                    check(Arrays.equals(expected,nativeCommands(first,301,279,0xff456789)),"lower POT downsampled foreground");
                    if(frame>0)check(Image.getRGBCalls==reads && Graphics3D.uploads==uploads,"64px tile working set failed to warm");
                    for(int i=first;i<Graphics3D.history.size();i++)check(Graphics3D.history.elementAt(i).source.image.width<=64,"hardware POT limit exceeded");
                }
            } finally {small.destroy();System.clearProperty("m3g.maxTexture");}
        }
        System.out.println("PASS 64/127 hardware texture limits -> exact 64px tiles, no downsampling, zero warm source reads/uploads");

        DirectX7 g=required();ScreenImages q=new ScreenImages();Graphics canvas=Image.createImage(64,64).getGraphics();
        // Tile resources warm once, then even a deliberately failing getRGB must not be called.
        for(int frame=0;frame<4;frame++) {
            q.reset(64,64);q.drawImage(source,0,0,20);
            int reads=Image.getRGBCalls,uploads=Graphics3D.uploads,barriers=g.getM3GCpuBarriers();
            if(frame>0) System.setProperty("readback.fail","true");
            try {g.renderCanvasImages(canvas,q);}finally {System.clearProperty("readback.fail");}
            if(frame>0)check(Image.getRGBCalls==reads && Graphics3D.uploads==uploads,"warm foreground re-read/re-uploaded source");
            check(g.getM3GCpuBarriers()==barriers,"foreground added CPU barrier");
        }
        // Foreground must not replace or materialize the world's pause snapshot.
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();
        g.renderGameFrameImage(null,false,false,null,0,null);g.flush(canvas,0,0);
        Field pending=DirectX7.class.getDeclaredField("frameImage");pending.setAccessible(true);
        Image world=(Image)pending.get(g);int reads=world.reads;
        q.reset(64,64);q.drawImage(source,0,0,20);g.renderCanvasImages(canvas,q);
        check(world.reads==reads && pending.get(g)==world,"foreground touched world readback/pause snapshot");
        g.getDisplay();check(world.reads==reads+1,"pause did not materialize world once");
        int firstWorld=Graphics3D.history.size();Texture tex=M3GTest.texture(0xffabcdef);
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),tex,0);g.renderGameFrameImage(null,false,false,null,0,null);
        Graphics3D.Draw worldDraw=Graphics3D.history.elementAt(Graphics3D.history.size()-1);
        check(Graphics3D.history.size()>firstWorld && worldDraw.projection[0]==2.0f/32 && worldDraw.projection[5]==2.0f/32 && worldDraw.viewportWidth==32 && worldDraw.viewportHeight==32,"world retained foreground camera/viewport");
        g.destroy();
        System.out.println("PASS warm foreground zero getRGB/uploads/CPU barriers / separate Canvas pass / unchanged lazy world snapshot");

        // Bounded cache: 65 sources evict, and releaseRenderer invalidates mutable assets.
        g=required();q.reset(64,64);for(int i=0;i<65;i++)q.drawImage(image(2,2),i%64,0,20);
        g.renderCanvasImages(canvas,q);
        Field renderer=DirectX7.class.getDeclaredField("sceneRenderer");renderer.setAccessible(true);
        Object adapter=renderer.get(g);Field helper=adapter.getClass().getDeclaredField("canvasImages");helper.setAccessible(true);
        Object cache=helper.get(adapter);Field tiles=cache.getClass().getDeclaredField("cache");tiles.setAccessible(true);
        check(((java.util.Vector)tiles.get(cache)).size()==64,"foreground cache unbounded");
        q.reset(64,64);for(int i=0;i<18;i++)q.drawImage(image(128,128),0,0,20);g.renderCanvasImages(canvas,q);
        Field pixels=cache.getClass().getDeclaredField("cachedPixels");pixels.setAccessible(true);
        check(pixels.getInt(cache)==262144 && ((java.util.Vector)tiles.get(cache)).size()==16,"foreground pixel budget not enforced");
        g.releaseRenderer();int uploads=Graphics3D.uploads;
        q.reset(64,64);q.drawImage(source,0,0,20);g.renderCanvasImages(canvas,q);
        check(Graphics3D.uploads>uploads,"native cache release did not invalidate foreground");g.destroy();
        for(String fault:new String[]{"texture.upload.fail","readback.fail","canvas.bind.fail","canvas.viewport.fail","m3g.oom","release.fail","translated","render.fail"}) {
            g=required();q.reset(64,64);q.drawImage(source,0,0,20);canvas=Image.createImage(64,64).getGraphics();canvas.setClip(3,4,50,49);
            if(fault.equals("translated"))canvas.translate(1,2);
            else if(fault.equals("render.fail"))Graphics3D.failAt=Graphics3D.calls+1;
            else System.setProperty(fault,"true");
            boolean failed=false;
            try {g.renderCanvasImages(canvas,q);}catch(IllegalStateException expected){failed=true;}
            finally {System.clearProperty(fault);Graphics3D.failAt=0;}
            check(failed && g.getRendererError()!=null && Graphics3D.target==null && q.count==0,"foreground fault not explicit/clean: "+fault);
            check(canvas.getClipWidth()==50 && canvas.getClipHeight()==49,"fault changed target clip");g.destroy();
        }
        q.reset(10,10);q.drawImage(source,-1000,-1000,20);check(q.count==0,"offscreen image queued");
        q.drawImage(source,Integer.MIN_VALUE,Integer.MIN_VALUE,40);check(q.count==0,"anchor overflow wrapped onto Canvas");
        q.setColor(0x123456);q.drawRect(-1,-1,Integer.MAX_VALUE,Integer.MAX_VALUE);check(q.count==0,"extreme offscreen outline wrapped");
        q.fillRect(-1,-1,Integer.MAX_VALUE,Integer.MAX_VALUE);check(q.count==1 && q.get(0,1)==0 && q.get(0,3)==10,"extreme rectangle clip failed");
        boolean bad=false;try {q.drawRegion(source,0,0,10,10,1,0,0,20);}catch(IllegalArgumentException expected){bad=true;}
        check(bad,"unsupported image transform silently changed");
        System.out.println("PASS bounded foreground cache / explicit invalidation / source read, upload, bind, viewport, OOM, release, render + translation failure / cleanup / unsupported transform");
    }
}
