package code.Rendering;

import code.Rendering.Meshes.Sprite;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import java.util.Arrays;
import java.lang.reflect.Field;
import javax.microedition.m3g.*;

/** Real queue/adapter contracts. The fake API samples one texel, not a rasterizer. */
public final class SpriteTest {
    private static void check(boolean ok,String why) {M3GTest.check(ok,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    static Sprite sprite(Texture t,int mode) {
        Sprite s=new Sprite(t,5);s.mode=(byte)mode;
        s.pos.sx=0;s.pos.sy=0;s.pos.rz=-400;s.size.sx=32;s.size.sy=32;s.size.rz=-400;
        return s;
    }
    private static void submit(DirectX7 g,Sprite s) {
        M3GTest.submit(g,s,null,0);g.render();
        check(g.isM3GActive(),"sprite disabled native backend: "+g.getRendererError());
    }
    private static void near(int a,int b,int tolerance,String why) {
        for(int shift=0;shift<=16;shift+=8)
            check(Math.abs(((a>>shift)&255)-((b>>shift)&255))<=tolerance,why+" "+Integer.toHexString(a)+" / "+Integer.toHexString(b));
    }
    private static Object field(Object o,String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;DirectX7.standartDrawmode=0;DirectX7.fDist=1000;DirectX7.fogc=0x507090;
        for(int mode:new int[]{0,1,3}) for(int pixel:new int[]{0,0xff000000,0xff4181f1,0x804181f1}) {
            Texture t=M3GTest.texture(pixel);t.drawmode=99;Sprite s=sprite(t,mode);
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);
            for(boolean fog:new boolean[]{false,true}) for(int fogMode:new int[]{0,6}) for(int distance:new int[]{0,1000}) {
                DirectX7.standartDrawmode=(byte)fogMode;DirectX7.fDist=distance;s.fog=fog;
                cpu.clearDisplay(0x123457);s.render(cpu,null);g.clearDisplay(0x123457);
                int calls=Graphics3D.calls;submit(g,s);
                check(Graphics3D.calls>calls,"sprite still rendered on CPU");
                near(cpu.display[0],g.display[0],2,"sprite blend/fog equation mode "+mode);
                check(last().textured && !last().fog,"sprite relies on depth fog instead of flat legacy distance");
                check(t.drawmode==99,"sprite changed shared texture material");
            }
            g.destroy();cpu.destroy();
        }
        DirectX7.standartDrawmode=0;DirectX7.fDist=1000;
        System.out.println("PASS native sprite cutout+ADD+alpha / black+holes / flat colored+black fog / zero distance / shared material unchanged");

        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xffc08040);Sprite s=sprite(t,0);
        g.centreX=11;g.centreY=13;s.pos.sx=-8;s.pos.sy=-4;s.size.sx=24;s.size.sy=28;
        for(boolean mx:new boolean[]{false,true}) for(boolean my:new boolean[]{false,true}) {
            s.mirX=mx;s.mirY=my;submit(g,s);Graphics3D.Draw d=last();
            int[] xs={0,24,24,0},ys={0,0,28,28};
            float[] us=mx?new float[]{0.75f,0,0,0.75f}:new float[]{0.25f,1,1,0.25f};
            float[] vs=my?new float[]{0.875f,0.875f,0,0}:new float[]{0.125f,0.125f,1,1};
            for(int i=0;i<4;i++) {
                check(Math.abs(d.positions[i*3]*d.scale+g.centreX-xs[i])<0.01,"sprite projected X/crop");
                check(Math.abs(g.centreY-d.positions[i*3+1]*d.scale-ys[i])<0.01,"sprite projected Y/crop");
                check(Math.abs(d.coords[i*2]*d.uvScale-us[i])<0.001 && Math.abs(d.coords[i*2+1]*d.uvScale-vs[i])<0.001,"clipped/mirrored UVs");
            }
        }
        s.pos.sx=Integer.MIN_VALUE+1;s.pos.sy=-1000000;s.size.sx=Integer.MAX_VALUE;s.size.sy=1000000;submit(g,s);
        int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
        s.pos.sx=40;s.size.sx=48;submit(g,s);check(Graphics3D.calls==calls && Graphics3D.uploads==uploads,"offscreen sprite uploaded/drew");
        s.pos.sx=0;s.size.sx=0;submit(g,s);check(Graphics3D.calls==calls,"zero-area sprite drew");g.destroy();
        System.out.println("PASS sprite framebuffer-local projection / clip+mirror UVs / huge near rectangle / offscreen+degenerate");

        int[] pixels=new int[15];for(int i=0;i<15;i++) pixels[i]=0xff000000|i*0x010203;
        RawImage raw=new RawImage(pixels,3,5);int[] untouched=pixels.clone();t=new Texture(raw,true,null);s=sprite(t,0);g=M3GTest.renderer();submit(g,s);
        Texture2D image=Graphics3D.lastAppearance.tex;
        check(image.image.width==2 && image.image.height==4,"NPOT sprite wasn't reduced to lower POT");
        check(image.wrapS==Texture2D.WRAP_CLAMP && image.wrapT==Texture2D.WRAP_CLAMP,"sprite resampling repeats");
        check(last().coords[2]==4096 && last().coords[5]==4096,"reduced sprite UVs do not cover full image");
        for(int y=0;y<4;y++) for(int x=0;x<2;x++) {
            int pixel=pixels[((2*y+1)*5/8)*3+(2*x+1)*3/4],i=(x+y*2)*4;
            check((image.image.pixels[i]&255)==((pixel>>16)&255) && (image.image.pixels[i+2]&255)==(pixel&255),"NPOT center sampling/source stride");
        }
        check(Arrays.equals(pixels,untouched) && raw.w==3 && raw.h==5,"native sprite changed original asset");
        Object backend=field(g,"sceneRenderer");check(((Integer)field(backend,"cachedPixels")).intValue()==8,"cache must charge reduced dimensions");
        uploads=Graphics3D.uploads;for(int i=0;i<4;i++) submit(g,s);check(Graphics3D.uploads==uploads,"sprite texture uploaded every frame");
        raw.img=raw.img.clone();submit(g,s);check(Graphics3D.uploads==uploads+1,"replaced pixels reused stale texture");
        s.textures=new Texture[]{t,M3GTest.texture(0xff00ff00)};s.anim_index=1;submit(g,s);check((g.display[0]&0xffffff)==0x00ff00,"sprite animation frame ignored");
        s.anim_index=0;uploads=Graphics3D.uploads;submit(g,s);check(Graphics3D.uploads==uploads,"animation texture wasn't cached");
        RawImage base=new RawImage(new int[]{0xffff0000,0xffff0000,0xffff0000,0xffff0000},2,2);
        t.mip=new RawImage[]{base,new RawImage(new int[]{0xff0000ff},1,1)};submit(g,s);
        check((g.display[0]&0xffffff)==0xff0000 && t.rImg==raw,"sprite selected polygon mip or mutated rImg");g.destroy();
        System.out.println("PASS NPOT nearest reduction / original assets unchanged / reduced cache accounting / upload reuse / pixel replacement / animation / base mip");

        for(int[] dims:new int[][]{{1,1,1,1},{2,2,2,2},{32,64,32,64},{64,64,64,64},
                {128,128,128,128},{70,90,64,64},{127,65,64,64},{256,256,256,256},{257,1,256,1}}) {
            int[] data=new int[dims[0]*dims[1]];Arrays.fill(data,0xffabcdef);
            g=M3GTest.renderer();s=sprite(new Texture(new RawImage(data,dims[0],dims[1]),false,null),0);submit(g,s);
            image=Graphics3D.lastAppearance.tex;
            check(image.image.width==dims[2] && image.image.height==dims[3],"lower POT policy: "+dims[0]+"x"+dims[1]);
            check(Math.abs(last().positions[6]*last().scale+g.centreX-32)<0.01
                    && Math.abs(g.centreY-last().positions[7]*last().scale-32)<0.01,"resampling changed screen size");
            g.destroy();
        }
        for(int mode:new int[]{0,1,3}) for(boolean fog:new boolean[]{false,true}) {
            int[] data=new int[70*90];
            for(int i=0;i<data.length;i++) data[i]=((i%3==0?0:i%3==1?128:255)<<24)|((i*7919)&0xffffff);
            raw=new RawImage(data,70,90);untouched=data.clone();g=M3GTest.renderer();s=sprite(new Texture(raw,false,null),mode);s.fog=fog;submit(g,s);
            image=Graphics3D.lastAppearance.tex;check(image.image.width==64 && image.image.height==64,"70x90 must become 64x64");
            boolean mask=fog && mode!=1;
            for(int y=0;y<64;y++) for(int x=0;x<64;x++) {
                int src=data[((2*y+1)*90/128)*70+(2*x+1)*70/128],i=(y*64+x)*4;
                int expectedAlpha=mode==1?255:mode==3?src>>>24:src==0?0:255;
                int rgb=mask?0xffffff:mode==1?src&0xfefefe:src;
                check((image.image.pixels[i]&255)==((rgb>>16)&255) && (image.image.pixels[i+1]&255)==((rgb>>8)&255)
                        && (image.image.pixels[i+2]&255)==(rgb&255) && (image.image.pixels[i+3]&255)==expectedAlpha,
                        "reduced cutout/ADD/alpha/fog mask mismatch");
            }
            check(Arrays.equals(data,untouched) && raw.w==70 && raw.h==90,"reduction modified source image");
            uploads=Graphics3D.uploads;submit(g,s);check(Graphics3D.uploads==uploads,"reduction repeated on cache hit");g.destroy();
        }
        System.out.println("PASS 70x90 -> 64x64 / POT assets unchanged / original screen size / cutout+ADD+alpha+fog sample alignment / cached reduction");

        // Warm the two material-specific cache entries, then interleave sprites
        // and polygons without a CPU barrier or an additional target transaction.
        g=M3GTest.renderer();t=M3GTest.texture(0xffff0000);s=sprite(t,0);submit(g,s);
        M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
        int binds=Graphics3D.binds;uploads=Graphics3D.uploads;
        M3GTest.submit(g,s,null,-300);M3GTest.submit(g,M3GTest.triangle(),t,-200);M3GTest.submit(g,s,null,-100);g.render();
        check(Graphics3D.binds==binds+1 && Graphics3D.uploads==uploads,"warm sprites force CPU/readback barriers");
        M3GTest.submit(g,s,null,-300);M3GTest.submit(g,new M3GTest.Marker(true),null,-200);
        M3GTest.submit(g,sprite(M3GTest.texture(0xff0000ff),3),null,-100);g.render();
        check((g.display[0]&0xffffff)==0x0000ff && Graphics3D.before==0xff00ff00,"sprite painter/CPU barrier ordering changed");
        s.mode=1;s.fog=true;submit(g,s);s.mode=3;submit(g,s);s.mode=0;s.fog=false;submit(g,s);
        check(last().blend==CompositingMode.REPLACE && last().function==Texture2D.FUNC_MODULATE && last().uvScale==1.0f/4096,"sprite blend/UV state leak");
        M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
        check(last().uvScale==1.0f/256 && last().blend==CompositingMode.REPLACE && !last().fog,"sprite state contaminated polygons");g.destroy();
        System.out.println("PASS sprite/polygon single warm transaction / painter+CPU barrier / isolated blend+fog+UV state");

        // MUL/color variants are deliberately exact software fallbacks.
        for(int color=0;color<=6;color++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            s=sprite(M3GTest.texture(0xff505050),2);s.color=color;calls=Graphics3D.calls;
            s.render(cpu,null);submit(g,s);
            check(Graphics3D.calls==calls && Arrays.equals(cpu.display,g.display),"MUL/color CPU fallback changed");g.destroy();cpu.destroy();
        }
        for(int failure=0;failure<6;failure++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            s=sprite(M3GTest.texture(0x804181f1),3);s.fog=true;s.render(cpu,null);
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            if(failure==3) g.flushAlpha=true;
            if(failure==4) DeveloperMenu.renderPolygonsOverwrite=true;
            if(failure==5) Graphics3D.failAt=Graphics3D.calls+2; // Second fog pass.
            if(failure==4) {cpu.clearDisplay(0);s.render(cpu,null);}
            M3GTest.submit(g,s,null,0);g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");DeveloperMenu.renderPolygonsOverwrite=false;
            check(Arrays.equals(cpu.display,g.display) && Graphics3D.target==null,"sprite failure wasn't completely replayed "+failure);
            g.destroy();cpu.destroy();
        }
        g=M3GTest.renderer();int[] big=new int[513];Arrays.fill(big,0xffabcdef);s=sprite(new Texture(new RawImage(big,513,1),false,null),0);
        calls=Graphics3D.calls;submit(g,s);check(Graphics3D.calls==calls && (g.display[0]&0xffffff)==0xabcdef,"oversize sprite CPU fallback");g.destroy();
        // Reduced dimensions, rather than raw area, govern eviction.
        // Five 511x511 sources become five 256x256 uploads: only four fit.
        g=M3GTest.renderer();
        for(int i=0;i<5;i++) {
            int[] data=new int[511*511];Arrays.fill(data,0xff203040+i);
            submit(g,sprite(new Texture(new RawImage(data,511,511),false,null),0));
        }
        backend=field(g,"sceneRenderer");
        check(((Integer)field(backend,"cachedPixels")).intValue()==262144 && ((java.util.Vector)field(backend,"textures")).size()==4,
                "reduced sprite cache didn't evict at pixel budget");g.destroy();
        for(int mode:new int[]{0,1,3}) {
            g=new DirectX7(32,32);DirectX7 cpu=new DirectX7(32,32);g.clearDisplay(0x123456);cpu.clearDisplay(0x123456);
            s=sprite(M3GTest.texture(0x804181f1),mode);s.fog=true;s.mirX=true;s.mirY=true;
            calls=Graphics3D.calls;s.render(cpu,null);M3GTest.submit(g,s,null,0);g.render();
            check(Graphics3D.calls==calls && Arrays.equals(cpu.display,g.display),"Software sprite rendering changed");g.destroy();cpu.destroy();
        }
        g=M3GTest.renderer();s=sprite(M3GTest.texture(0xff90a0b0),0);submit(g,s);
        uploads=Graphics3D.uploads;g.resize(40,48);s.size.sx=40;s.size.sy=48;submit(g,s);
        check(Graphics3D.uploads==uploads+1 && g.display.length==1920 && Graphics3D.target==null,"sprite resize/cache lifecycle");
        g.setRenderer(DirectX7.SOFTWARE);calls=Graphics3D.calls;M3GTest.submit(g,s,null,0);g.render();
        check(Graphics3D.calls==calls,"sprite renderer selection ignored");
        g.setRenderer(DirectX7.M3G);submit(g,s);check(Graphics3D.uploads==uploads+2,"sprite mode switch reused disposed state");g.destroy();
        System.out.println("PASS reduced cache budget+eviction / unchanged original Software sprites / resize+renderer lifecycle");
        System.out.println("PASS exact MUL/color+oversize fallback / draw+fog-pass+readback+OOM rollback / alpha+debug safety");
    }
}
