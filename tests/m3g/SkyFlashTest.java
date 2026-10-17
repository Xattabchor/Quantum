package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.HUD.DeveloperMenu;
import code.utils.Main;
import code.utils.WeatherGenerator;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.Graphics3D;

/** Solid storm sky, never a world overlay or lightning bolt. No GPU screenshots. */
public final class SkyFlashTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static WeatherGenerator weather() {
        return new WeatherGenerator(32,32,0,0,0,0,0,true,null,1,1,16,16,1,1);
    }
    private static void flash(DirectX7 g,Skybox sky) {sky.lighting=true;sky.render(null,g);g.render();}
    private static void solid(DirectX7 g) {
        for(int p:g.display) check((p&0xffffff)==SkyFlash.COLOR,"not a solid light-gray sky");
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(boolean gradient:new boolean[]{false,true}) {
            DirectX7 g=M3GTest.renderer();Skybox sky=new Skybox(0x112233);sky.skyboxAlways=true;
            if(gradient) sky.skyLighting=M3GTest.texture(0xff123456);
            int calls=Graphics3D.calls,clears=Graphics3D.clears,uploads=Graphics3D.uploads;
            int copies=Graphics.drawRGBCalls;
            flash(g,sky);solid(g);
            check(g.isM3GActive() && Graphics3D.clears==clears+1,"storm didn't use native clear");
            check(Graphics3D.calls==calls && Graphics3D.uploads==uploads,"storm drew geometry/bolts or uploaded texture");
            check(Graphics.drawRGBCalls==copies,"sky clear unnecessarily copied old CPU pixels to target");
            check(!sky.lighting && Graphics3D.target==null,"flash state or target leaked");
            flash(g,sky);check(Graphics3D.clears==clears+2,"repeated sky flash lost native clear");
            // World geometry follows and occludes the sky, never gets whitened.
            M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff654321),0);g.render();
            check(g.display[0]==0xff654321 && (g.display[1]&0xffffff)==SkyFlash.COLOR,"sky flash covered the world");
            int[] before=g.display.clone();int n=Graphics3D.calls,c=Graphics3D.clears;
            weather().lightingEffect(g,-50);sky.drawLighting(g);g.render();
            check(Arrays.equals(before,g.display) && Graphics3D.calls==n && Graphics3D.clears==c,"world flash/bolt survived");
            // Normal sky resumes immediately when the 135ms caller no longer sets lighting.
            sky.render(null,g);g.render();for(int p:g.display) check(p==0x112233,"normal sky did not resume");
            g.resize(40,24);flash(g,sky);solid(g);g.destroy();
        }
        System.out.println("PASS solid sky native clear / no texture+draw+old-pixel upload / no bolts+world glow / occlusion / reset+resize");

        for(int failure=0;failure<5;failure++) {
            DirectX7 g=M3GTest.renderer();Skybox sky=new Skybox(0x112233);
            if(failure==0) System.setProperty("clear.fail","true");
            if(failure==1) System.setProperty("readback.fail","true");
            if(failure==2) System.setProperty("m3g.oom","true");
            if(failure==3) g.flushAlpha=true;
            if(failure==4) DeveloperMenu.renderPolygonsOverwrite=true;
            flash(g,sky);System.clearProperty("clear.fail");System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            DeveloperMenu.renderPolygonsOverwrite=false;solid(g);
            check(Graphics3D.target==null,"sky clear failure leaked target");
            int[] before=g.display.clone();weather().lightingEffect(g,-50);sky.drawLighting(g);g.render();
            check(Arrays.equals(before,g.display),"fallback revived expensive storm effects");g.destroy();
        }
        System.out.println("PASS solid sky fallback / clear+readback+OOM / alpha+debug / no bolt or world flash on failure");

        // Original Software still uses its configured gradient, bolts and world light.
        DirectX7 cpu=new DirectX7(240,320);Skybox sky=new Skybox(0x112233);
        sky.skyboxAlways=true;sky.skyLighting=M3GTest.texture(0xff224466);flash(cpu,sky);
        boolean colored=false;for(int p:cpu.display) if((p&0xffffff)!=SkyFlash.COLOR) colored=true;
        check(colored,"Software lost configured storm sky");
        boolean bolt=false;
        for(int seed=1;seed<100 && !bolt;seed++) {
            cpu.clearDisplay(0x123456);sky.lastLighting=seed;sky.drawLighting(cpu);
            for(int p:cpu.display) if(p!=0x123456) {bolt=true;break;}
        }
        check(bolt,"Software lost lightning bolts");cpu.destroy();
        cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);int[] before=cpu.display.clone();weather().lightingEffect(cpu,-50);
        check(!Arrays.equals(before,cpu.display),"Software world light was disabled");cpu.destroy();
        System.out.println("PASS unchanged Software gradient sky / lightning bolts / world lighting");
    }
}
