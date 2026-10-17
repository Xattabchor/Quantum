package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** M3G-only mode3 replacement; original gradient resources remain usable. */
public final class GradientSkyTest {
    private static final int COLOR=0x123456;
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Texture texture() {
        return new Texture(new RawImage(new int[]{0xff123456,0xff7090b0,0xff90b0d0,0xffe0f0ff},1,4),true,null);
    }
    private static Skybox gradient(Texture t) {Skybox s=new Skybox(t,-91,0.2f,0);s.skyboxAlways=true;return s;}
    private static void submit(Skybox s,DirectX7 g) {s.render(null,g);g.render();}
    public static void main(String[] args) throws Exception {
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;
        for(int[] size:new int[][]{{32,32},{240,320},{320,240}}) {
            DirectX7 g=new DirectX7(size[0],size[1]);g.setRenderer(DirectX7.M3G);g.clearDisplay(0xabcdef);
            Texture t=texture();int[] original=t.rImg.img.clone();Skybox sky=gradient(t);
            for(int fov:new int[]{40,74,100}) for(int pitch:new int[]{-90,0,90}) {
                g.updateFov(fov);sky.rotateX=pitch;sky.rotateY=125;
                int calls=Graphics3D.calls,uploads=Graphics3D.uploads,copies=Graphics.drawRGBCalls;
                int[] before=g.display.clone();sky.render(null,g);
                check(Arrays.equals(before,g.display),"mode3 still painted CPU gradient during queue submission");
                g.render();Graphics3D.Draw d=Graphics3D.history.lastElement();
                check(g.isM3GActive() && Graphics3D.calls==calls+1 && !d.textured && !d.fog
                        && d.blend==CompositingMode.REPLACE,"mode3 replacement not one untextured solid quad");
                check(Graphics3D.uploads==uploads && Graphics.drawRGBCalls==copies,"replacement uploaded gradient or old framebuffer");
                for(int v=0;v<4;v++) check((d.colors[v*4]&255)==0x12 && (d.colors[v*4+1]&255)==0x34
                        && (d.colors[v*4+2]&255)==0x56 && (d.colors[v*4+3]&255)==255,"flat source color changed");
            }
            check(Arrays.equals(original,t.rImg.img) && t.rImg.w==1 && t.rImg.h==4,"gradient resource changed");
            // Cheap one-texel lookup follows a changed source without uploading it.
            t.rImg.img[0]=0xff204060;submit(sky,g);check((g.display[0]&0xffffff)==0x204060,"changed gradient base color not reflected");
            sky.lighting=true;int clears=Graphics3D.clears;submit(sky,g);
            check(Graphics3D.clears==clears+1 && (g.display[0]&0xffffff)==SkyFlash.COLOR,"storm sky flash changed");
            submit(sky,g);check((g.display[0]&0xffffff)==0x204060,"flat sky didn't resume after storm");
            g.resize(40,24);submit(sky,g);check(g.isM3GActive(),"replacement resize failed");sky.destroy();g.destroy();
        }
        System.out.println("PASS M3G gradient -> one solid vertex-color quad / source first RGB / no CPU gradient+texture upload / FOV+pitch+aspect / resource unchanged / storm+resize");

        DirectX7 g=M3GTest.renderer();Skybox empty=new Skybox(texture(),-91,1,0);int calls=Graphics3D.calls;
        submit(empty,g);check(Graphics3D.calls==calls,"empty viewport generated a solid sky");g.destroy();
        for(int failure=0;failure<5;failure++) {
            g=M3GTest.renderer();Skybox sky=gradient(texture());
            if(failure==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            if(failure==3) g.flushAlpha=true;
            if(failure==4) DeveloperMenu.renderPolygonsOverwrite=true;
            submit(sky,g);Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");DeveloperMenu.renderPolygonsOverwrite=false;
            for(int pixel:g.display) check(pixel==COLOR,"M3G fallback revived gradient");
            check(Graphics3D.target==null,"replacement leaked target");g.destroy();
        }
        g=M3GTest.renderer();Skybox sky=gradient(texture());Texture world=M3GTest.texture(0xffaabbcc);
        // Warm texture before checking the one shared sky/world transaction.
        M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
        int binds=Graphics3D.binds,reads=Image.getRGBCalls;
        sky.render(null,g);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
        check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1 && g.display[0]==0xffaabbcc,"replacement split shared pass or covered world");g.destroy();
        System.out.println("PASS empty viewport / native+readback+OOM+alpha+debug fallback stays flat / warm shared sky-world transaction");

        // Same Skybox instance must regain its original gradient in Software.
        g=M3GTest.renderer();sky=gradient(texture());submit(sky,g);g.setRenderer(DirectX7.SOFTWARE);g.clearDisplay(0xabcdef);calls=Graphics3D.calls;submit(sky,g);
        int[] software=g.display.clone();boolean different=false;for(int pixel:software) if(pixel!=software[0]) different=true;
        check(different && Graphics3D.calls==calls,"Software gradient removed or sent to native");
        DirectX7 reference=new DirectX7(32,32);reference.clearDisplay(0xabcdef);Skybox expected=gradient(texture());expected.addViewport(0,0,32,32);
        java.lang.reflect.Method original=Skybox.class.getDeclaredMethod("renderGradient",DirectX7.class,Texture.class,int.class,float.class,float.class);
        original.setAccessible(true);original.invoke(expected,reference,texture(),-91,0.2f,0f);
        check(Arrays.equals(software,reference.display),"Software no longer matches original gradient painter");
        g.setRenderer(DirectX7.M3G);submit(sky,g);check(!Graphics3D.history.lastElement().textured && (g.display[0]&0xffffff)==COLOR,"switch back to M3G revived gradient");
        reference.destroy();g.destroy();
        System.out.println("PASS same resource+Skybox Software gradient unchanged / exact original painter / renderer switching back to flat M3G");
    }
}
