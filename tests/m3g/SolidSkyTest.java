package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Solid mode1 background contracts; fake M3G does not rasterize full images. */
public final class SolidSkyTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static void submit(Skybox sky,DirectX7 g) {sky.render(null,g);g.render();}
    public static void main(String[] args) {
        Main.mipMapping=false;
        for(int[] size:new int[][]{{32,32},{240,320},{320,240}}) for(int color:new int[]{0,0xffffff,0x123456,0x80aabbcc}) {
            DirectX7 g=new DirectX7(size[0],size[1]);g.setRenderer(DirectX7.M3G);
            Skybox sky=new Skybox(color);sky.addViewport(2,3,10,12);
            int uploads=Graphics3D.uploads;
            for(int fov:new int[]{40,74,100}) for(int quality:new int[]{0,2}) {
                g.updateFov(fov);Main.fogQ=quality;Main.persQ=quality==0?0:4;
                int calls=Graphics3D.calls,clears=Graphics3D.clears,copies=Graphics.drawRGBCalls;
                submit(sky,g);Graphics3D.Draw d=Graphics3D.history.lastElement();
                check(g.isM3GActive() && Graphics3D.calls==calls+1 && Graphics3D.clears==clears,"solid sky not one vertex-color draw");
                check(!d.textured && !d.fog && d.blend==CompositingMode.REPLACE,"solid sky uses texture/fog/blending");
                check(Graphics3D.uploads==uploads && Graphics.drawRGBCalls==copies,"solid sky uploaded bitmap/old framebuffer");
                for(int v=0;v<4;v++) {
                    check((d.colors[v*4]&255)==((color>>16)&255) && (d.colors[v*4+1]&255)==((color>>8)&255)
                            && (d.colors[v*4+2]&255)==(color&255) && (d.colors[v*4+3]&255)==255,"solid sky vertex color/alpha");
                    float w=-d.positions[v*3+2];
                    float x=g.centreX+d.positions[v*3]/w,y=g.centreY-d.positions[v*3+1]/w;
                    check(x==(v==0 || v==3?0:g.width) && y==(v<2?0:g.height),"solid sky no longer covers complete framebuffer");
                }
                check((g.display[0]&0xffffff)==(color&0xffffff),"solid sky sample color");
            }
            M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff654321),0);g.render();
            check(g.display[0]==0xff654321,"background occluded the level");
            // Existing cheap storm clear remains separate from the mode1 quad.
            sky.lighting=true;int clears=Graphics3D.clears;submit(sky,g);
            check(Graphics3D.clears==clears+1,"solid sky broke storm clear");submit(sky,g);
            check((g.display[0]&0xffffff)==(color&0xffffff),"original sky color didn't resume after storm");
            g.resize(40,24);submit(sky,g);check(g.isM3GActive(),"solid sky resize");sky.destroy();g.destroy();
        }
        System.out.println("PASS solid sky mode1 vertex colors / full-frame geometry / FOV+aspect / no texture+old-frame upload / opaque black / storm+resize");

        for(int fallback=0;fallback<5;fallback++) {
            DirectX7 g=M3GTest.renderer();Skybox sky=new Skybox(0x123456);
            if(fallback==0) Graphics3D.failAt=Graphics3D.calls+1;
            if(fallback==1) System.setProperty("readback.fail","true");
            if(fallback==2) System.setProperty("m3g.oom","true");
            if(fallback==3) g.flushAlpha=true;
            if(fallback==4) DeveloperMenu.renderPolygonsOverwrite=true;
            submit(sky,g);Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            DeveloperMenu.renderPolygonsOverwrite=false;
            for(int pixel:g.display) check(pixel==0x123456,"solid sky original CPU fallback");
            check(Graphics3D.target==null,"solid sky target leak");g.destroy();
        }
        DirectX7 cpu=new DirectX7(32,32);int calls=Graphics3D.calls;submit(new Skybox(0xabcdef),cpu);
        for(int pixel:cpu.display) check(pixel==0xabcdef,"Software solid sky changed");
        check(Graphics3D.calls==calls,"Software solid sky entered M3G");cpu.destroy();
        // Other 2D modes retain the original CPU renderers.
        DirectX7 g=M3GTest.renderer();Texture image=M3GTest.texture(0xff123456);
        Skybox gradient=new Skybox(image,-91,1,0);gradient.skyboxAlways=true;calls=Graphics3D.calls;
        submit(gradient,g);check(Graphics3D.calls==calls && !g.isM3GActive(),"gradient sky changed");
        Skybox panorama=new Skybox(image,1,1,-91,1,0,0,0);panorama.skyboxAlways=true;
        submit(panorama,g);check(Graphics3D.calls==calls && !g.isM3GActive(),"panorama sky changed");g.destroy();
        System.out.println("PASS solid sky draw/readback/OOM/alpha/debug fallback / unchanged Software+gradient+panorama");
    }
}
