package code.Rendering;

import code.HUD.DeveloperMenu;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Actual effect backend/data/transfers and CPU replay, not GPU rasterization. */
public final class FrameEffectsTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static DirectX7 renderer() {return M3GTest.renderer();}
    private static void world(DirectX7 g,Texture texture) {M3GTest.submit(g,M3GTest.triangle(),texture,0);}
    private static byte[] mask(int w,int h,int d) {byte[] mask=new byte[w*h];Arrays.fill(mask,(byte)(d-128));return mask;}
    private static void near(int a,int b,int tolerance) {
        for(int shift=0;shift<24;shift+=8) check(Math.abs(((a>>shift)&255)-((b>>shift)&255))<=tolerance,"effect equation mismatch");
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        // Cold mask is prepared BEFORE the sky/world, not uploaded mid-transaction.
        DirectX7 g=renderer();Texture t=M3GTest.texture(0xffa0c0e0);byte[] mask=mask(32,32,128);
        int binds=Graphics3D.binds,reads=javax.microedition.lcdui.Image.getRGBCalls;
        int calls=Graphics3D.calls;
        world(g,t);g.renderWithEffects(mask,true,true);
        check(Graphics3D.calls==calls+2,"all three effects must share ONE quad");
        check(Graphics3D.binds==binds+1 && javax.microedition.lcdui.Image.getRGBCalls==reads+1,"cold effects added frame transfers");
        check(last().blend==CompositingMode.MODULATE && last().cull==PolygonMode.CULL_NONE && !last().fog && last().threshold==0,"effect state");
        check(Graphics3D.lastAppearance.tex.image.format==Image2D.LUMINANCE,"RGBA mask wastes memory");
        check(Graphics3D.lastAppearance.tex.image.encoded.length<=4096,"unbounded mask memory");
        check((last().colors[0]&255)==128 && (last().colors[1]&255)==32,"death+damage not composed in vertex tint");
        FrameEffects fx=new FrameEffects();fx.set(mask,true,true);
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0xffa0c0e0);fx.render(cpu,null);
        near(g.display[0],cpu.display[0],2);
        System.out.println("PASS one MODULATE quad for vignette+death+damage / LUMINANCE <=4KiB / cold world+effects single bind+readback / combined tint equation");

        int uploads=Graphics3D.uploads,sets=VertexArray.sets,arrays=VertexArray.created;
        int setters=VertexBuffer.defaultColorCalls;
        for(int frame=0;frame<8;frame++) {
            binds=Graphics3D.binds;reads=javax.microedition.lcdui.Image.getRGBCalls;
            // Effects-only frame still works, doesn't require queued geometry.
            g.clearDisplay(0xffa0c0e0);g.renderWithEffects(mask,true,true);
            check(Graphics3D.binds==binds+1 && javax.microedition.lcdui.Image.getRGBCalls==reads+1,"effects-only transfer count");
        }
        check(Graphics3D.uploads==uploads && VertexArray.sets==sets && VertexArray.created==arrays
                && VertexBuffer.defaultColorCalls==setters,"warm effects upload/rebuild geometry/colors/mask");
        for(boolean dead:new boolean[]{false,true}) for(boolean hurt:new boolean[]{false,true}) {
            g.clearDisplay(0xffa0c0e0);calls=Graphics3D.calls;g.renderWithEffects(mask,dead,hurt);
            fx.set(mask,dead,hurt);cpu.clearDisplay(0xffa0c0e0);fx.render(cpu,null);
            near(g.display[0],cpu.display[0],2);check(Graphics3D.calls==calls+1,"effect state adds passes");
            check(Graphics3D.uploads==uploads,"tint change reuploads mask");
        }
        g.renderWithEffects(null,true,false);g.renderWithEffects(mask,false,false);
        check(Graphics3D.uploads==uploads,"temporarily disabled vignette lost warm mask");
        calls=Graphics3D.calls;g.renderWithEffects(null,false,false);
        check(Graphics3D.calls==calls,"inactive effects draw");
        calls=Graphics3D.calls;g.renderWithEffects(new byte[0],false,false);
        check(Graphics3D.calls==calls,"empty vignette causes no-op draw");
        // Existing sky/world transaction must stay shared, with the effects last.
        binds=Graphics3D.binds;reads=javax.microedition.lcdui.Image.getRGBCalls;calls=Graphics3D.calls;
        M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();world(g,t);
        g.renderWithEffects(mask,false,false);
        check(Graphics3D.binds==binds+1 && javax.microedition.lcdui.Image.getRGBCalls==reads+1 && Graphics3D.calls==calls+3,"effects split warm sky/world transaction");
        check(last().blend==CompositingMode.MODULATE,"effect not last after sky+world");
        world(g,t);g.render();check(last().blend==CompositingMode.REPLACE && !last().fog,"effects leaked into next frame world state");
        System.out.println("PASS warm effects zero mask/geometry/color uploads / no native buffers per frame / tint changes no mask uploads / disabled mask reuse / inactive skip");

        byte[] varied=new byte[32*32];for(int i=0;i<varied.length;i++) varied[i]=(byte)(i%256-128);
        byte[] copy=varied.clone();g.renderWithEffects(varied,false,false);
        Image2D image=Graphics3D.lastAppearance.tex.image;
        check(image.width==32 && image.height==32,"small mask resampled unnecessarily");
        fx.set(varied,false,false);
        for(int i=0;i<varied.length;i++) check((image.encoded[i]&255)==((fx.factor(i,varied.length)*255+128)>>8),"mask source/unrolled-pair conversion");
        check(Arrays.equals(varied,copy),"source vignette changed");
        g.resize(240,320);mask=mask(240,320,255);g.renderWithEffects(mask,false,false);
        image=Graphics3D.lastAppearance.tex.image;
        check(image.width==64 && image.height==64 && image.encoded.length==4096,"large mask not bounded 64x64");
        Graphics3D.Draw d=last();
        check((double)d.positions[0]/-d.positions[2]==-g.centreX && (double)d.positions[6]/-d.positions[8]==g.width-g.centreX,"effect coverage X");
        g.centreX=19;g.centreY=21;g.renderWithEffects(mask,false,false);
        check((double)last().positions[0]/-last().positions[2]==-19,"effect cached stale camera center");
        g.releaseRenderer();uploads=Graphics3D.uploads;g.renderWithEffects(mask,false,false);
        check(Graphics3D.uploads==uploads+1,"release retained native mask");g.destroy();cpu.destroy();
        System.out.println("PASS original paired vignette coefficients / source immutable / size+center invalidation / bounded downsample / resize+release lifecycle");

        for(int failure=0;failure<6;failure++) {
            g=renderer();cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);mask=mask(32,32,128);t=M3GTest.texture(0xffa0c0e0);
            world(cpu,t);cpu.render();fx.set(mask,true,true);fx.render(cpu,null);
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+2; // Effect after successful scene draw.
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            if(failure==3) g.flushAlpha=true;
            if(failure==4) {DeveloperMenu.renderPolygonsOverwrite=true;cpu.clearDisplay(0);world(cpu,t);cpu.render();fx.render(cpu,null);}
            if(failure==5) System.setProperty("effects.upload.fail","true");
            world(g,t);g.renderWithEffects(mask,true,true);
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");System.clearProperty("effects.upload.fail");DeveloperMenu.renderPolygonsOverwrite=false;
            check(Arrays.equals(cpu.display,g.display),"failure didn't restore + replay scene and effects exactly once "+failure);
            check(Graphics3D.target==null,"effect failure leaked target");
            g.destroy();cpu.destroy();
        }
        g=new DirectX7(32,32);g.clearDisplay(0xffa0c0e0);int[] untouched=g.display.clone();
        g.renderWithEffects(mask,true,true);check(Arrays.equals(untouched,g.display),"Software applied M3G replacements");g.destroy();
        Main.m3gPerspective=true;
        System.out.println("PASS effect draw/readback/OOM full-frame replay once / alpha+debug CPU effects / Software not changed");
    }
}
