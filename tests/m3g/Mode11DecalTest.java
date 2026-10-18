package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** DECAL is texture-over-vertex color, NOT texture-over-framebuffer ALPHA. */
public final class Mode11DecalTest {
    private static void check(boolean value,String why) {M3GTest.check(value,why);}
    private static Texture texture(int argb) {Texture t=M3GTest.texture(argb);t.drawmode=11;return t;}
    private static void submit(DirectX7 g,RenderObject p,Texture t) {M3GTest.submit(g,p,t,0);g.render();}
    public static void main(String[] args) {
        Main.mipMapping=false;Main.forceLQFog=false;Main.fogQ=2;
        for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) for(int color:new int[]{0xff204080,0x80204080,0}) {
            Main.persQ=pers;DirectX7 g=M3GTest.renderer();Texture t=texture(color);
            int[] original=t.rImg.img.clone();DirectX7.fDist=DirectX7.waterDistance=0;
            g.clearDisplay(0x123456);submit(g,DitherDisabledTest.polygon(kind),t);
            Graphics3D.Draw draw=Graphics3D.history.lastElement();
            check(g.isM3GActive() && draw.function==Texture2D.FUNC_DECAL,"11 did not select native DECAL");
            check(draw.blend==CompositingMode.REPLACE && !draw.fog && !draw.lit && draw.threshold==0,"DECAL inherited framebuffer ALPHA/Fog/light/cutout");
            check(draw.textureAlpha==(color>>>24),"DECAL discarded source alpha");
            check(g.display[0]==Mode11Decal.pixel(color),"DECAL mixed against framebuffer instead of white vertex color");
            check(t.drawmode==11 && Arrays.equals(original,t.rImg.img),"DECAL mutated original material/pixels");
            int uploads=Graphics3D.uploads;submit(g,DitherDisabledTest.polygon(kind),t);
            check(Graphics3D.uploads==uploads,"warm DECAL reuploaded texture");
            t.drawmode=0;submit(g,DitherDisabledTest.polygon(kind),t);
            check(Graphics3D.history.lastElement().function==Texture2D.FUNC_MODULATE,"DECAL leaked into ordinary material");g.destroy();
        }
        System.out.println("PASS mode11 native DECAL / six polygon types / affine+perspective / opaque+fractional+zero alpha / white base not framebuffer / state reset / warm cache / original assets");

        for(int pers:new int[]{0,4}) for(int color:new int[]{0xff204080,0x80204080,0}) for(int scenario=0;scenario<4;scenario++) {
            Main.persQ=pers;DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);Texture t=texture(color);
            Texture expected=M3GTest.texture(Mode11Decal.pixel(color));RenderObject p=DitherDisabledTest.polygon(0),ref=DitherDisabledTest.polygon(0);
            if(scenario==0) {((Polygon3V)p).a.rz=((Polygon3V)ref).a.rz=1;}
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            g.clearDisplay(0x123456);cpu.clearDisplay(0x123456);submit(cpu,ref,expected);
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            submit(g,p,t);Graphics3D.failAt=0;System.clearProperty("readback.fail");
            check(Arrays.equals(g.display,cpu.display),"DECAL CPU fallback/full replay mismatch");
            check(t.drawmode==11,"DECAL replay modified source mode");g.destroy();cpu.destroy();
        }
        System.out.println("PASS mode11 geometry+flushAlpha CPU fallback / native+readback full replay / affine+perspective / zero distances / original Software isolation");

        Mode11Decal cache=new Mode11Decal();Texture source=texture(0x80204080);
        source.mip=new RawImage[]{source.rImg,M3GTest.texture(0x40808080).rImg,M3GTest.texture(0).rImg};
        Texture first=cache.texture(source);RawImage image=first.rImg,mip=first.mip[1];
        check(cache.texture(source).rImg==image && cache.texture(source).mip[1]==mip,"CPU recovery allocates copies on a cache hit");
        source.rImg.img=new int[]{0xffaabbcc,0xffaabbcc,0xffaabbcc,0xffaabbcc};
        check(cache.texture(source).rImg!=image && cache.texture(source).rImg.img[0]==0xffaabbcc,"CPU recovery missed pixel-array replacement");
        source.rImg=M3GTest.texture(0).rImg;
        check(cache.texture(source).rImg.img[0]==0xffffffff,"CPU recovery missed animation/image replacement");
        System.out.println("PASS mode11 bounded CPU recovery cache / mip copies / array+image replacement / no JSR-184 dependency");

        Main.persQ=4;Main.fogQ=2;
        DirectX7 g=M3GTest.renderer();SkyMesh sky=SkyTest.cube();sky.set(new Matrix(),0,0,32,32);
        for(Texture t:sky.mesh.getTexture().textures) {t.drawmode=11;t.rImg=M3GTest.texture(0x80204080).rImg;}
        int start=Graphics3D.history.size();submit(g,sky,null);
        check(g.isM3GActive() && Graphics3D.history.size()>start && g.display[0]==Mode11Decal.pixel(0x80204080),"DECAL sky fell back or lost alpha");
        for(int i=start;i<Graphics3D.history.size();i++) check(Graphics3D.history.elementAt(i).function==Texture2D.FUNC_DECAL,"sky not DECAL");
        for(int frame=0;frame<2;frame++) {
            sky.set(new Matrix(),0,0,32,32);g.beginFrameStats();
            M3GTest.submit(g,sky,null,0);g.renderBackground();g.renderGameFrameImage(null,false,false,null,0,null);
            check(g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0,"certified DECAL sky lost warm B0/T0");
        }
        Texture shared=texture(0x80204080);Texture ordinary=new Texture(shared.rImg,true,null);ordinary.drawmode=0;
        for(int frame=0;frame<2;frame++) {
            g.beginFrameStats();for(int i=0;i<4;i++) M3GTest.submit(g,M3GTest.triangle(),i%2==0?shared:ordinary,i);
            start=Graphics3D.history.size();g.render();
            check(Graphics3D.history.size()==start+4,"DECAL/ordinary material boundary merged/reordered");
            if(frame==1) check(g.getM3GTextureUploads()==0,"alternating material warm uploads");
        }
        Texture alpha=new Texture(shared.rImg,true,null);alpha.drawmode=1;
        for(int frame=0;frame<2;frame++) {
            g.beginFrameStats();start=Graphics3D.history.size();
            for(int i=0;i<4;i++) M3GTest.submit(g,M3GTest.triangle(),i%2==0?shared:alpha,i);
            g.render();check(Graphics3D.history.size()==start+4,"shared alpha-cache function boundary merged");
            for(int i=start;i<Graphics3D.history.size();i++) {
                Graphics3D.Draw d=Graphics3D.history.elementAt(i);
                check(d.function==(d.blend==CompositingMode.ALPHA?Texture2D.FUNC_MODULATE:Texture2D.FUNC_DECAL),"shared native texture mutated pending packet function");
            }
            if(frame==1) check(g.getM3GTextureUploads()==0,"shared alpha-cache warm uploads");
        }
        g.destroy();System.out.println("PASS mode11 native model sky / DECAL-MODULATE shared-source boundaries / painter order / warm uploads");
    }
}
