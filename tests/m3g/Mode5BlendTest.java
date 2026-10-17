package code.Rendering;

import code.HUD.DeveloperMenu;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Configuration + native state + independent CPU sampling references, not FPS. */
public final class Mode5BlendTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static int expected(int source,int constant) {
        int result=0xff000000;
        for(int shift=0;shift<24;shift+=8) {
            int t=(source>>>shift)&255,k=(constant>>>shift)&255;
            result|=((255*(255-t)+k*t)/255)<<shift;
        }
        return result;
    }
    private static String rgb(int color) {return ((color>>>16)&255)+","+((color>>>8)&255)+","+(color&255);}
    private static Texture texture(int color) {Texture t=M3GTest.texture(color);t.drawmode=5;return t;}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static void submit(DirectX7 g,RenderObject p,Texture t) {M3GTest.submit(g,p,t,0);g.render();}

    static void nativeContracts() {
        Main.mipMapping=false;
        for(int constant:new int[]{0,0x4080ff,0xff8040,0xffffff}) {
            DirectX7.configureM3GBlend(rgb(constant));
            for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) for(int fog:new int[]{0,1,2}) {
                Main.persQ=pers;Main.fogQ=fog;DirectX7 g=M3GTest.renderer();
                RenderObject p=NativeLightingTest.polygon(kind);p.nx=Integer.MIN_VALUE;p.ny=0;p.nz=Integer.MAX_VALUE;
                Texture t=texture(0x80df9153);submit(g,p,t);
                Graphics3D.Draw d=last();
                check(g.isM3GActive() && d.function==Texture2D.FUNC_BLEND && d.blendColor==constant,"mode5 not native FUNC_BLEND/config color");
                check(d.blend==CompositingMode.REPLACE && !d.fog && !d.lit && d.lights==0 && d.normals==null,"mode5 still has Material/Fog/lights/normals");
                for(byte c:d.colors) check((c&255)==255,"BLEND primary color not white");
                check(g.display[0]==expected(0x80df9153,constant),"native sample equation");
                g.startWeaponLight();g.startMuzzleGlow();g.shootIntensity=999;g.flashlightEnabled=true;
                DirectX7.lightdirx=0;DirectX7.lightdiry=-4096;DirectX7.lightdirz=0;
                int baseline=g.display[0];submit(g,p,t);
                check(g.display[0]==baseline && g.getMode5WeaponLight()==null && !last().lit && last().lights==0,"shot/torch/lightdir changed BLEND");
                t.drawmode=0;submit(g,p,t);
                check(last().function==Texture2D.FUNC_MODULATE && g.display[0]==0xffdf9153,"BLEND state leaked to mode0/shared texture");
                g.destroy();
            }
        }
        DirectX7.configureM3GBlend(null);
        System.out.println("PASS mode5 FUNC_BLEND / level RGB / six polygon types / fogQ+persQ / no Material+normals+lights+Fog+OMNI / primary white / no mode0 state leak");
    }

    private static void cpuReferences() {
        for(int constant:new int[]{0,0xffffff,0x4080ff,0xff8040,0x123456}) {
            DirectX7.configureM3GBlend(rgb(constant));
            for(int pers:new int[]{0,4}) for(int fog:new int[]{0,1,2}) for(int opt:new int[]{Main.s60Optimization}) {
                Main.persQ=pers;Main.fogQ=fog;Main.mipMapping=false;
                for(int kind=0;kind<6;kind++) {
                    int[] pixels={0,0xff000000,0xff112233,0x80402080};
                    Texture t=new Texture(new RawImage(pixels.clone(),2,2),true,null);t.drawmode=5;
                    Texture reference=new Texture(new RawImage(pixels.clone(),2,2),true,null);reference.drawmode=0;
                    for(int i=0;i<reference.rImg.img.length;i++) if(pixels[i]!=0) reference.rImg.img[i]=expected(pixels[i],constant);
                    DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
                    RenderObject p=NativeLightingTest.polygon(kind),q=NativeLightingTest.polygon(kind);
                    // The reference mode0 sampler preserves original UV/mip/cutout rounding.
                    M3GTest.submit(g,p,t,0);g.renderSoftware();M3GTest.submit(cpu,q,reference,0);cpu.renderSoftware();
                    check(Arrays.equals(g.display,cpu.display),"CPU BLEND UV/clip/cutout/reference pers="+pers+" fog="+fog+" opt="+opt+" kind="+kind);
                    check(t.drawmode==5 && Arrays.equals(pixels,t.rImg.img),"CPU modified original texture/mode");
                    g.destroy();cpu.destroy();
                }
            }
        }
        DirectX7.configureM3GBlend(null);
        System.out.println("PASS allocation-free CPU BLEND vs independently transformed mode0 texture / affine+perspective / six polygon classes / fogQ / production scanline unroll / cutout+alpha / source pixels unchanged");
    }

    public static void main(String[] args) {
        for(String invalid:new String[]{null,"","1,2","1,2,3,4","-1,0,0","0,256,0","0,0,-1","x,0,0","2147483648,0,0","#4080FF"}) {
            DirectX7.configureM3GBlend("255,128,64");check(DirectX7.getM3GBlendColor()==0xff8040,"valid color");
            DirectX7.configureM3GBlend(invalid);check(DirectX7.getM3GBlendColor()==0,"missing/invalid level color inherited previous level");
        }
        DirectX7.configureM3GBlend(" 64 , 128 , 255 ");check(DirectX7.getM3GBlendColor()==0x4080ff,"RGB whitespace");
        for(int t=0;t<256;t++) for(int k=0;k<256;k++) {
            int src=0xff000000|t*0x010101,col=k*0x010101;
            check(Mode5Blend.pixel(src,col)==expected(src,col),"exhaustive blend byte equation");
        }
        System.out.println("PASS strict RGB config / whitespace / absent+invalid key resets black on new level / exhaustive 65536 texture+constant byte pairs");
        cpuReferences();
        for(int pers:new int[]{0,4}) for(int fog:new int[]{0,1,2}) {
            Main.persQ=pers;Main.fogQ=fog;int[] baseline=null;
            for(String color:new String[]{null,"255,255,255","64,128,255"}) {
                DirectX7.configureM3GBlend(color);DirectX7 software=new DirectX7(32,32);software.clearDisplay(0x123456);
                RenderObject face=NativeLightingTest.polygon(0);face.nx=0;face.ny=0;face.nz=4096;
                M3GTest.submit(software,face,texture(0xff8090a0),0);software.render();
                if(baseline==null) baseline=software.display.clone();
                else check(Arrays.equals(baseline,software.display),"level BLEND color changed original Software mode5");
                software.destroy();
            }
        }
        System.out.println("PASS original Software mode5 ignores M3G_BLEND_COLOR / affine+perspective / fog quality bypass unchanged");
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        // State must be captured at submission time, not read when a packet flushes.
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer r=new M3GSceneRenderer();Texture t=texture(0xff8090a0);
        RenderObject p=M3GTest.triangle();int start=Graphics3D.history.size();
        DirectX7.configureM3GBlend("255,0,0");check(r.draw(g,p,t,false),"packet red");
        DirectX7.configureM3GBlend("0,0,255");check(r.draw(g,p,t,false),"packet blue");r.finish(g);
        check(Graphics3D.history.size()==start+2 && Graphics3D.history.get(start).blendColor==0xff0000 && last().blendColor==255,"packet used newer level color");
        r.dispose();g.destroy();
        g=M3GTest.renderer();submit(g,p,t);int uploads=Graphics3D.uploads;
        DirectX7.configureM3GBlend("255,128,64");g.beginFrameStats();start=Graphics3D.calls;
        for(int i=0;i<100;i++) M3GTest.submit(g,M3GTest.triangle(),t,i);g.render();
        check(Graphics3D.calls==start+2 && Graphics3D.uploads==uploads && g.getM3GCpuBarriers()==0,"new color reuploaded texture or broke 100-triangle batching");g.destroy();
        g=M3GTest.renderer();start=Graphics3D.history.size();
        M3GTest.submit(g,ModeOptimizationAuditTest.sky(5),null,0);g.render();
        check(g.isM3GActive() && Graphics3D.history.size()>start && last().function==Texture2D.FUNC_BLEND
                && last().blendColor==0xff8040 && !last().lit,"mode5 mesh sky not native BLEND");g.destroy();
        System.out.println("PASS pending BLEND colors frozen / color change splits packet without reupload / warm 100 triangles -> 2 draws / B0+T0");

        for(int pers:new int[]{0,4}) for(int failure=0;failure<4;failure++) {
            Main.persQ=pers;DirectX7.configureM3GBlend("64,128,255");
            g=M3GTest.renderer();DirectX7 cpu=M3GTest.renderer();
            M3GTest.Marker marker=new M3GTest.Marker(false),refMarker=new M3GTest.Marker(false);
            M3GTest.submit(g,M3GTest.triangle(),t,-300);M3GTest.submit(g,marker,null,-200);M3GTest.submit(g,M3GTest.triangle(),t,-100);
            M3GTest.submit(cpu,M3GTest.triangle(),t,-300);M3GTest.submit(cpu,refMarker,null,-200);M3GTest.submit(cpu,M3GTest.triangle(),t,-100);cpu.renderSoftware();
            if(failure<2) Graphics3D.failAt=Graphics3D.calls+failure+1;
            if(failure==2) System.setProperty("readback.fail","true");
            if(failure==3) System.setProperty("m3g.oom","true");
            g.render();Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(!g.isM3GActive() && Graphics3D.target==null && Arrays.equals(g.display,cpu.display),"BLEND mixed full CPU replay failure="+failure);
            g.destroy();cpu.destroy();
        }
        // Native-only image presentation must still have no final readback.
        g=M3GTest.renderer();submit(g,p,t);int reads=Image.getRGBCalls;
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();M3GTest.submit(g,p,t,0);
        g.renderGameFrameImage(null,false,false,null,0,null);g.flush(Image.createImage(32,32).getGraphics(),0,0);
        check(Image.getRGBCalls==reads && !last().lit && last().function==Texture2D.FUNC_BLEND,"BLEND lost direct Image presentation");g.destroy();
        DirectX7.configureM3GBlend(null);Main.persQ=4;
        System.out.println("PASS BLEND mixed native/CPU first+second draw/readback/OOM rollback / no final getRGB in Image path");
    }
}
