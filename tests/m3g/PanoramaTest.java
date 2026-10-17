package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Geometry/state/failure contracts; fake M3G does not rasterize the panorama. */
public final class PanoramaTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Skybox sky(Texture t,float scale,float rx,float ry,int crop) {
        Skybox s=new Skybox(t,rx,ry,crop,scale,0.15f,0x102030,0x908070);s.skyboxAlways=true;return s;
    }
    private static PanoramaSky packet(Skybox sky) throws Exception {
        Field f=Skybox.class.getDeclaredField("nativePanorama");f.setAccessible(true);return (PanoramaSky)f.get(sky);
    }
    private static float x(Graphics3D.Draw d,int i,DirectX7 g) {return g.centreX+d.positions[i*3]/-(float)d.positions[i*3+2];}
    private static float y(Graphics3D.Draw d,int i,DirectX7 g) {return g.centreY-d.positions[i*3+1]/-(float)d.positions[i*3+2];}
    private static void rectangle(Graphics3D.Draw d,DirectX7 g,int x1,int y1,int x2,int y2) {
        check(x(d,0,g)==x1 && y(d,0,g)==y1 && x(d,2,g)==x2 && y(d,2,g)==y2,"panorama viewport/rect geometry");
        check(d.blend==CompositingMode.REPLACE && !d.fog,"panorama inherited blend/fog");
        for(int i=3;i<16;i+=4) check((d.colors[i]&255)==255,"panorama vertex alpha");
    }
    private static void uv(Graphics3D.Draw d,PanoramaSky p,RawImage raw,int top,int bottom) {
        float ua=d.coords[0]*d.uvScale,ub=d.coords[2]*d.uvScale;
        float va=d.coords[1]*d.uvScale,vb=d.coords[5]*d.uvScale;
        for(int x=p.x1;x<p.x2;x++) {
            double actual=ua+(ub-ua)*(x+0.5-p.x1)/(p.x2-p.x1);
            double expected=((long)p.uStart+(long)p.du*x)/(raw.w*4096.0);
            double difference=actual-expected;difference-=Math.rint(difference);
            check(Math.abs(difference)*raw.w<0.14,"yaw/FOV/repeatX/fixed-point U mismatch");
        }
        for(int y=top;y<bottom;y++) {
            double actual=va+(vb-va)*(y+0.5-top)/(bottom-top);
            double expected=(long)p.dv*(y-(long)p.startY)/(raw.h*4096.0);
            double difference=actual-expected;difference-=Math.rint(difference);
            check(Math.abs(difference)*raw.h<0.14,"pitch/horizon/repeatY/fixed-point V mismatch");
        }
    }
    public static void main(String[] args) throws Exception {
        int[] pixels=new int[128*64];for(int i=0;i<pixels.length;i++) pixels[i]=0x80000000|i*31;
        RawImage raw=new RawImage(pixels,128,64);Texture texture=new Texture(raw,false,null);texture.drawmode=99;
        int[] original=pixels.clone();
        for(int[] size:new int[][]{{32,32},{240,320},{320,240}}) {
            DirectX7 g=new DirectX7(size[0],size[1]);g.setRenderer(DirectX7.M3G);
            Skybox sky=sky(texture,0.3f,2,1,-91);int uploads=Graphics3D.uploads;
            for(int fov:new int[]{40,74,100}) for(int yaw:new int[]{-1080,-30,0,359,360,1080}) for(int pitch:new int[]{-80,0,80}) {
                sky.rotateX=pitch;sky.rotateY=yaw;g.updateFov(fov);g.clearDisplay(0xabcdef);
                int[] before=g.display.clone();int first=Graphics3D.history.size(),copies=Graphics.drawRGBCalls;
                sky.render(null,g);PanoramaSky p=packet(sky);check(Arrays.equals(before,g.display),"native panorama still ran CPU pixel sampler");
                g.render();check(g.isM3GActive(),"panorama backend disabled: "+g.getRendererError());
                int top=Math.max(p.y1,Math.min(p.y2,p.startY)),bottom=Math.max(p.y1,Math.min(p.y2,p.endY)),index=first;
                if(top>p.y1) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(index++);rectangle(d,g,p.x1,p.y1,p.x2,top);
                    check(!d.textured && (d.colors[0]&255)==0x10,"top sky color");
                }
                if(bottom>top) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(index++);rectangle(d,g,p.x1,top,p.x2,bottom);
                    check(d.textured && d.textureAlpha==255,"panorama incorrectly uses source alpha");uv(d,p,raw,top,bottom);
                }
                if(p.y2>bottom) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(index++);rectangle(d,g,p.x1,bottom,p.x2,p.y2);
                    check(!d.textured && (d.colors[0]&255)==0x90,"ground sky color");
                }
                check(index==Graphics3D.history.size() && index-first<=3,"panorama draw count");
                check(Graphics.drawRGBCalls==copies,"full opaque panorama uploaded old CPU frame");
                check(Graphics3D.uploads<=uploads+1,"panorama reuploaded texture during rotation");
                check(texture.drawmode==99 && texture.rImg==raw && Arrays.equals(raw.img,original),"panorama modified original texture");
            }
            sky.rotateX=0;sky.rotateY=0;sky.render(null,g);g.render();
            Texture2D nativeImage=Graphics3D.lastAppearance.tex;
            // The last draw can be an untextured ground band, so inspect cache
            // through another full-height panorama frame.
            Skybox full=sky(texture,1,2,1,-91);full.render(null,g);g.render();nativeImage=Graphics3D.lastAppearance.tex;
            check(nativeImage.image.width==128 && nativeImage.image.height==64,"panorama resolution changed");
            check(nativeImage.wrapS==Texture2D.WRAP_REPEAT && nativeImage.wrapT==Texture2D.WRAP_REPEAT,"panorama not repeating");
            for(int i=3;i<nativeImage.image.pixels.length;i+=4) check((nativeImage.image.pixels[i]&255)==255,"panorama texel alpha not opaque");
            M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff654321),0);g.render();check(g.display[0]==0xff654321,"panorama occluded world pass");
            int clears=Graphics3D.clears;sky.lighting=true;sky.render(null,g);g.render();check(Graphics3D.clears==clears+1,"panorama lost cheap sky-only storm");
            sky.render(null,g);g.render();check(Graphics3D.history.lastElement().blend==CompositingMode.REPLACE,"panorama did not resume after storm");
            g.resize(40,24);sky.render(null,g);g.render();check(g.isM3GActive(),"panorama resize");g.destroy();sky.destroy();
        }
        System.out.println("PASS panorama mode2 native band+top+ground / yaw+pitch+FOV+aspect / fixed-point UVs / original resolution+opaque RGB / cache / world+storm+resize");

        for(float rx:new float[]{-2,0,0.5f,8,40}) for(float ry:new float[]{-2,0,0.5f,4,12}) {
            DirectX7 g=M3GTest.renderer();Skybox sky=sky(texture,0.6f,rx,ry,-20);sky.skyboxAlways=false;
            sky.x1=3;sky.y1=4;sky.x2=28;sky.y2=29;sky.rotateX=15;sky.rotateY=-540;
            int copies=Graphics.drawRGBCalls,first=Graphics3D.history.size();sky.render(null,g);PanoramaSky p=packet(sky);g.render();
            check(Graphics.drawRGBCalls==copies+1,"partial panorama didn't preserve outside portal/crop");
            int top=Math.max(p.y1,Math.min(p.y2,p.startY)),bottom=Math.max(p.y1,Math.min(p.y2,p.endY));
            for(int i=first;i<Graphics3D.history.size();i++) {
                Graphics3D.Draw d=Graphics3D.history.elementAt(i);
                check(x(d,0,g)==3 && x(d,2,g)==28 && y(d,0,g)>=4 && y(d,2,g)<=p.y2,"portal/crop bounds lost");
                if(d.textured) uv(d,p,raw,top,bottom);
            }
            g.destroy();
        }
        System.out.println("PASS panorama partial portal / lowestDegree+horizonOffset / positive+negative+zero repeat / preserve old frame");

        for(int failure=0;failure<8;failure++) {
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(failure==7?0:0x123456);
            Skybox sky=sky(texture,0.2f,2,1,-91),reference=sky(texture,0.2f,2,1,-91);
            reference.render(null,cpu);
            if(failure<3) Graphics3D.failAt=Graphics3D.calls+failure+1;
            if(failure==3) System.setProperty("readback.fail","true");
            if(failure==4) System.setProperty("m3g.oom","true");
            if(failure==5) g.flushAlpha=true;
            if(failure==6) g.setRenderer(DirectX7.SOFTWARE);
            if(failure==7) DeveloperMenu.renderPolygonsOverwrite=true;
            sky.render(null,g);g.render();Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");DeveloperMenu.renderPolygonsOverwrite=false;
            check(Arrays.equals(cpu.display,g.display),"panorama CPU fallback/replay mismatch "+failure);
            check(Graphics3D.target==null,"panorama failure leaked target");cpu.destroy();g.destroy();
        }
        for(int width:new int[]{70,512}) {
            int[] data=new int[width*4];Arrays.fill(data,0xff8090a0);Texture unsupported=new Texture(new RawImage(data,width,4),false,null);
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Skybox sky=sky(unsupported,1,2,1,-91),reference=sky(unsupported,1,2,1,-91);reference.render(null,cpu);
            int calls=Graphics3D.calls;sky.render(null,g);g.render();
            check(Graphics3D.calls==calls && Arrays.equals(cpu.display,g.display),"unsupported panorama was reduced/dropped instead of exact CPU fallback");g.destroy();cpu.destroy();
        }
        DirectX7 g=M3GTest.renderer();Texture black=M3GTest.texture(0);Skybox sky=sky(black,1,2,1,-91);sky.render(null,g);g.render();
        check((g.display[0]&0xffffff)==0 && Graphics3D.history.lastElement().textureAlpha==255,"black panorama turned transparent");
        g.clearDisplay(0x123456);M3GTest.submit(g,M3GTest.triangle(),black,0);g.render();
        check((g.display[0]&0xffffff)==0x123456,"panorama alpha kind contaminated polygon cache");g.destroy();
        g=M3GTest.renderer();Texture animated=M3GTest.texture(0xffff0000);sky=sky(animated,1,2,1,-91);
        sky.render(null,g);g.render();int uploads=Graphics3D.uploads;
        animated.mip=new RawImage[]{M3GTest.texture(0xff0000ff).rImg};
        sky.render(null,g);g.render();check((g.display[0]&0xffffff)==0xff0000 && Graphics3D.uploads==uploads,
                "panorama incorrectly selected sprite base mip");
        animated.rImg=M3GTest.texture(0xff00ff00).rImg;sky.render(null,g);g.render();
        check((g.display[0]&0xffffff)==0x00ff00 && Graphics3D.uploads==uploads+1,"panorama didn't follow current RawImage");
        animated.rImg.img=animated.rImg.img.clone();sky.render(null,g);g.render();
        check(Graphics3D.uploads==uploads+2,"panorama pixel replacement reused stale cache");
        g.releaseRenderer();sky.render(null,g);g.render();check(Graphics3D.uploads==uploads+3,"panorama release/cache lifecycle");
        g.destroy();
        // Unrepresentable repeat ranges must reject the whole batch before any
        // fill rect is submitted, then replay the exact old sampler.
        g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        sky=sky(texture,0.2f,10000,1,-91);Skybox reference=sky(texture,0.2f,10000,1,-91);
        reference.render(null,cpu);int calls=Graphics3D.calls;sky.render(null,g);g.render();
        check(Graphics3D.calls==calls && Arrays.equals(cpu.display,g.display),"extreme UV range drew partial native sky");g.destroy();cpu.destroy();
        System.out.println("PASS panorama current image+pixel replacement+release cache / extreme UV whole-batch fallback");
        System.out.println("PASS panorama first+middle+last draw/readback/OOM replay / Software+alpha+debug / NPOT+oversize exact fallback / opaque black+cache isolation");
    }
}
