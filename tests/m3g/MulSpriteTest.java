package code.Rendering;

import code.Rendering.Meshes.Sprite;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** MODULATE replacement contracts, not a visual GPU emulator. */
public final class MulSpriteTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static int factor(int pixel,int color) {
        if(pixel==0 || (pixel&255)==69) return 0xffffff;
        int[] masks={0xffffff,0xff0000,0x00ff00,0x0000ff,0xff00ff,0xffff00,0x00ffff};
        int value=pixel&255,out=0;
        for(int shift=0;shift<24;shift+=8) out|=((masks[color]&(255<<shift))!=0?value:255)<<shift;
        return out;
    }
    private static int multiply(int source,int background) {
        int out=background&0xff000000;
        for(int shift=0;shift<24;shift+=8)
            out|=(((source>>>shift)&255)*((background>>>shift)&255)/255)<<shift;
        return out;
    }
    // Independent reference: let original Software clip/mirror/sample an opaque
    // converted bitmap onto white, then multiply the saved framebuffer by it.
    static void reference(DirectX7 cpu,Sprite s) {
        Texture[] textures=s.textures;int frame=s.anim_index;byte mode=s.mode;boolean fog=s.fog;
        Texture t=textures[frame];RawImage raw=t.mip!=null?t.mip[0]:t.rImg;
        int[] converted=new int[raw.img.length],background=cpu.display.clone();
        for(int i=0;i<converted.length;i++) converted[i]=0xff000000|factor(raw.img[i],s.color);
        s.textures=new Texture[]{new Texture(new RawImage(converted,raw.w,raw.h),false,null)};
        s.anim_index=0;s.mode=0;s.fog=false;cpu.clearDisplay(0xffffff);
        try {s.render(cpu,null);}
        finally {s.textures=textures;s.anim_index=frame;s.mode=mode;s.fog=fog;}
        for(int i=0;i<background.length;i++) cpu.display[i]=multiply(cpu.display[i],background[i]);
    }
    private static void submit(DirectX7 g,Sprite s) {
        M3GTest.submit(g,s,null,0);g.render();check(g.isM3GActive(),"MUL disabled M3G: "+g.getRendererError());
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(boolean hw:new boolean[]{false,true}) for(int color=0;color<=6;color++) {
            Main.m3gPerspective=hw;DirectX7 g=M3GTest.renderer();
            for(int pixel:new int[]{0,0xff000000,0xffffffff,0xff808080,0xff123445,0x40123480}) {
                Texture t=M3GTest.texture(pixel);t.drawmode=99;Sprite s=SpriteTest.sprite(t,2);s.color=color;
                int[] source=t.rImg.img.clone();int uploads=Graphics3D.uploads;
                for(boolean fog:new boolean[]{false,true}) for(int range:new int[]{-1,0,1,1000}) {
                    DirectX7.fDist=range;DirectX7.fogc=0xff00ff;s.fog=fog;
                    g.clearDisplay(0x80c0ff);int calls=Graphics3D.calls;submit(g,s);
                    check(Graphics3D.calls==calls+1 && last().blend==CompositingMode.MODULATE && !last().fog,
                            "MUL not single native no-fog MODULATE");
                    check(last().cull==PolygonMode.CULL_NONE && last().perspective==hw,"MUL sprite polygon state");
                    check((g.display[0]&0xffffff)==(multiply(factor(pixel,color),0x80c0ff)&0xffffff),"MUL channel/key/equation");
                    check(t.drawmode==99 && Arrays.equals(t.rImg.img,source),"MUL changed source material/bitmap");
                }
                check(Graphics3D.uploads==uploads+1,"MUL reconverted texture per fog/range change");
            }
            g.destroy();
        }
        Main.m3gPerspective=true;DirectX7.fDist=1000;
        // Geometry/UV clipping and mirroring must be identical to other sprites.
        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff808080);Sprite s=SpriteTest.sprite(t,2);
        s.pos.sx=-8;s.pos.sy=-4;s.size.sx=24;s.size.sy=28;
        for(boolean mx:new boolean[]{false,true}) for(boolean my:new boolean[]{false,true}) {
            s.mirX=mx;s.mirY=my;s.mode=0;submit(g,s);Graphics3D.Draw expected=last();
            s.mode=2;submit(g,s);check(Arrays.equals(last().positions,expected.positions)
                    && Arrays.equals(last().coords,expected.coords),"MUL clip/mirror changed");
        }
        s.textures=new Texture[]{t,M3GTest.texture(0xff202020)};s.anim_index=1;g.clearDisplay(0xffffff);submit(g,s);
        check((g.display[0]&0xffffff)==0x202020,"MUL animation ignored");
        s.anim_index=0;g.destroy();
        // NPOT reduction must convert the sampled blue/key/channel mask, not
        // the wrong source row, and charge/reuse only the reduced upload.
        int[] pixels=new int[70*90];
        for(int i=0;i<pixels.length;i++) pixels[i]=i%4==0?0:i%4==1?0xff000045:0xff000000|(i*7919&0xffffff);
        RawImage raw=new RawImage(pixels,70,90);int[] untouched=pixels.clone();
        g=M3GTest.renderer();s=SpriteTest.sprite(new Texture(raw,false,null),2);s.color=4;submit(g,s);
        Texture2D image=Graphics3D.lastAppearance.tex;
        check(image.image.width==64 && image.image.height==64 && image.wrapS==Texture2D.WRAP_CLAMP,"MUL NPOT reduction/wrapping");
        for(int y=0;y<64;y++) for(int x=0;x<64;x++) {
            int expected=factor(pixels[((2*y+1)*90/128)*70+(2*x+1)*70/128],4),offset=(y*64+x)*4;
            check((image.image.pixels[offset]&255)==(expected>>>16&255)
                    && (image.image.pixels[offset+1]&255)==(expected>>>8&255)
                    && (image.image.pixels[offset+2]&255)==(expected&255)
                    && (image.image.pixels[offset+3]&255)==255,"MUL reduced pixel conversion");
        }
        check(Arrays.equals(pixels,untouched) && raw.w==70 && raw.h==90,"MUL reduction mutated original");
        int uploads=Graphics3D.uploads;submit(g,s);check(Graphics3D.uploads==uploads,"MUL warm upload repeated");g.destroy();
        // Interleave polygon and MUL after warming both cache entries. No CPU barrier.
        g=M3GTest.renderer();t=M3GTest.texture(0xff808080);s=SpriteTest.sprite(t,2);submit(g,s);
        M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();g.beginFrameStats();
        int binds=Graphics3D.binds;uploads=Graphics3D.uploads;
        M3GTest.submit(g,s,null,-300);M3GTest.submit(g,M3GTest.triangle(),t,-200);
        M3GTest.submit(g,SpriteTest.sprite(t,2),null,-100);g.render();
        check(Graphics3D.binds==binds+1 && Graphics3D.uploads==uploads && g.getM3GCpuBarriers()==0
                && g.getM3GDrawCalls()==3,"warm MUL inserted CPU/resource barrier");
        check((g.display[0]&0xffffff)==multiply(0x808080,0x808080),"MUL painter order");
        s.mode=0;submit(g,s);check(last().blend==CompositingMode.REPLACE,"MUL blend leaked");g.destroy();
        // CPU recovery matches new semantics for all masks, mirror directions,
        // clipping and failures, without changing original Software operators.
        for(int color=0;color<=6;color++) for(int failure=0;failure<7;failure++) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            int[] map={0,0xff000045,0xff000000,0xffffffff,0xff202080,0x40808020,0xff808080,0xffabcdef};
            s=SpriteTest.sprite(new Texture(new RawImage(map,4,2),false,null),2);s.color=color;s.fog=true;
            s.pos.sx=-4;s.pos.sy=-2;s.size.sx=28;s.size.sy=30;s.mirX=color%2==0;s.mirY=color%3==0;
            // Different channel mask guarantees a second native packet now
            // that compatible adjacent sprites can share a draw.
            Sprite second=SpriteTest.sprite(s.textures[0],2);second.color=(color+1)%7;
            reference(cpu,s);if(failure==5) reference(cpu,second);
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            if(failure==3) g.flushAlpha=true;
            if(failure==4) {g.setRenderer(DirectX7.SOFTWARE);s.render(g,null);check(!Arrays.equals(g.display,cpu.display),"Software MUL replaced");g.destroy();cpu.destroy();continue;}
            if(failure==5) Graphics3D.failAt=Graphics3D.calls+2;
            M3GTest.submit(g,s,null,0);if(failure==5) M3GTest.submit(g,second,null,1);
            if(failure==6) g.renderSoftware();else g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(Arrays.equals(g.display,cpu.display) && Graphics3D.target==null,"MUL CPU replay mismatch "+color+"/"+failure);
            g.destroy();cpu.destroy();
        }
        System.out.println("PASS native MUL sprite MODULATE / masks0..6 / blue69+zero neutral / clip+mirror+animation / NPOT+cache / warm zero CPU barriers / ordered full-frame recovery / Software unchanged");
    }
}
