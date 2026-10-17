package code.Rendering;

public final class MissingM3GTest {
    public static void main(String[] args) {
        code.utils.Main.m3gPerspective=args.length==0;
        DirectX7 g = M3GTest.renderer();
        M3GTest.submit(g, M3GTest.triangle(), M3GTest.texture(0xffffffff), 0);
        g.render();
        M3GTest.check(!g.isM3GActive(), "Missing JSR-184 did not fall back");
        M3GTest.check(g.getRendererError() != null, "Missing JSR-184 not diagnosed");
        g.destroy();
        // SkyMesh must also remain loadable and replayable without any JSR-184.
        g=M3GTest.renderer();
        code.Rendering.Meshes.Polygon3V p=M3GTest.triangle();
        MultyTexture textures=new MultyTexture();textures.textures=new Texture[]{M3GTest.texture(0xffffffff)};
        code.Rendering.Meshes.Mesh mesh=new code.Rendering.Meshes.Mesh(new Vertex[]{p.a,p.b,p.c},new RenderObject[]{p},textures);
        code.Rendering.Meshes.SkyMesh sky=new code.Rendering.Meshes.SkyMesh(mesh);
        sky.set(new code.Math.Matrix(),0,0,32,32);M3GTest.submit(g,sky,null,0);g.render();
        M3GTest.check(!g.isM3GActive() && g.getRendererError()!=null,"Sky requires optional API to fall back");
        g.destroy();
        g=M3GTest.renderer();ScreenLines lines=new ScreenLines();lines.line(2,2,4,20,1,0xffffff,false);
        M3GTest.check(!g.renderScreenLines(lines) && g.getRendererError()!=null,"weather overlay needs optional API to fall back");
        g.destroy();
        g=M3GTest.renderer();code.utils.Main.persQ=4;code.utils.Main.fogQ=2;code.utils.Main.mipMapping=false;
        code.Rendering.Meshes.ColorLightedPolygon3V baked=new code.Rendering.Meshes.ColorLightedPolygon3V(
                p.a,p.b,p.c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        Texture bakedTexture=M3GTest.texture(0xffffffff);bakedTexture.drawmode=13;
        g.shootIntensity=100;g.shootLength=0;M3GTest.submit(g,baked,bakedTexture,0);g.render();
        M3GTest.check(!g.isM3GActive() && !g.isStaticLightmapPass(),"missing API lost static fallback/scope");g.destroy();
        g=M3GTest.renderer();MuzzleGlow glow=new MuzzleGlow();glow.alpha=100;
        glow.texture=M3GTest.texture(0x80ffffff);int background=g.display[0];
        M3GTest.check(!g.renderOverlay(glow) && g.display[0]==background && g.getRendererError()!=null,
                "missing API flash must skip without damaging the frame");g.destroy();
        for(int mode:new int[]{2,5,7,8}) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture t=M3GTest.texture(0xffd08040);t.drawmode=(byte)mode;
            Texture referenceTexture=M3GTest.texture(0xffd08040);referenceTexture.drawmode=(byte)mode;
            if(mode==7) GlassTest.reference(cpu,M3GTest.triangle(),referenceTexture);
            else {M3GTest.submit(cpu,M3GTest.triangle(),referenceTexture,0);cpu.render();}
            M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
            M3GTest.check(t.drawmode==mode,"missing API leaked material alias");
            M3GTest.check(!g.isM3GActive() && java.util.Arrays.equals(cpu.display,g.display),"missing API mode "+mode);
            g.destroy();cpu.destroy();
        }
        for(int mode:new int[]{10,11,12}) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            Texture t=M3GTest.texture(0xffa1b2c3);t.drawmode=(byte)mode;
            M3GTest.submit(cpu,M3GTest.triangle(),M3GTest.texture(0xffa1b2c3),0);cpu.render();
            M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
            M3GTest.check(!g.isM3GActive() && java.util.Arrays.equals(cpu.display,g.display) && t.drawmode==mode,
                    "missing API revived dithering or changed material "+mode);
            g.destroy();cpu.destroy();
        }
        g=M3GTest.renderer();DirectX7 flashCPU=new DirectX7(32,32);flashCPU.clearDisplay(0x123456);
        code.utils.WeatherGenerator flashWeather=new code.utils.WeatherGenerator(32,32,0,0,0,0,0,true,null,1,1,16,16,1,1);
        int[] untouched=g.display.clone();flashWeather.lightingEffect(g,-10);
        M3GTest.check(java.util.Arrays.equals(g.display,untouched),"missing API re-enabled world flash");
        code.Gameplay.Map.Skybox flashingSky=new code.Gameplay.Map.Skybox(0x112233);flashingSky.lighting=true;
        flashingSky.render(null,g);g.render();
        for(int pixel:g.display) M3GTest.check((pixel&0xffffff)==SkyFlash.COLOR,"missing API lost solid sky flash");
        M3GTest.check(!g.isM3GActive(),"missing API sky clear didn't fall back");
        g.destroy();flashCPU.destroy();
        g=M3GTest.renderer();code.Gameplay.Map.Skybox solidSky=new code.Gameplay.Map.Skybox(0x345678);
        solidSky.render(null,g);g.render();
        for(int pixel:g.display) M3GTest.check(pixel==0x345678,"missing API lost solid mode1 sky");
        M3GTest.check(!g.isM3GActive(),"solid sky requires optional API to fall back");g.destroy();
        g=M3GTest.renderer();code.utils.WeatherGenerator rainOnly=new code.utils.WeatherGenerator(32,32,0,0,0,0,0,false,null,1,1,16,16,1,1);
        rainOnly.coords=new float[]{10,5};rainOnly.info=new int[]{1,0};
        M3GTest.check(!rainOnly.paintM3G(g) && g.getRendererError()!=null,"native rain must report missing optional API");
        javax.microedition.lcdui.Graphics rainLCD=javax.microedition.lcdui.Image.createImage(32,64).getGraphics();
        rainOnly.paintAfterScene(rainLCD,13,g.getRenderer(),false);
        M3GTest.check(rainLCD.lineCalls==1 && g.getRendererError()!=null,"missing API lost cheap LCDUI rain");g.destroy();
        for(int mode:new int[]{0,1,3}) {
            g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            int[] pixels=new int[15];java.util.Arrays.fill(pixels,0x80d08040);
            Texture tex=new Texture(new RawImage(pixels,3,5),false,null);
            code.Rendering.Meshes.Sprite sprite=new code.Rendering.Meshes.Sprite(tex,5);
            sprite.mode=(byte)mode;sprite.fog=true;sprite.mirX=true;
            sprite.pos.sx=0;sprite.pos.sy=0;sprite.pos.rz=-400;sprite.size.sx=32;sprite.size.sy=32;
            M3GTest.submit(cpu,sprite,null,0);cpu.render();M3GTest.submit(g,sprite,null,0);g.render();
            M3GTest.check(!g.isM3GActive() && g.getRendererError()!=null && java.util.Arrays.equals(cpu.display,g.display),
                    "missing API failed to replay sprite mode "+mode);
            cpu.destroy();g.destroy();
        }
        g=M3GTest.renderer();DirectX7 panoCPU=new DirectX7(32,32);panoCPU.clearDisplay(0x123456);
        Texture panoTexture=M3GTest.texture(0x80123456);
        code.Gameplay.Map.Skybox panorama=new code.Gameplay.Map.Skybox(panoTexture,2,1,-91,0.2f,0,0x334455,0x667788);
        code.Gameplay.Map.Skybox reference=new code.Gameplay.Map.Skybox(panoTexture,2,1,-91,0.2f,0,0x334455,0x667788);
        panorama.skyboxAlways=reference.skyboxAlways=true;panorama.rotateY=reference.rotateY=-540;
        reference.render(null,panoCPU);panorama.render(null,g);g.render();
        M3GTest.check(!g.isM3GActive() && g.getRendererError()!=null && java.util.Arrays.equals(panoCPU.display,g.display),
                "panorama wrapper requires optional API or replay changed sampling");panoCPU.destroy();g.destroy();
        for(boolean pano:new boolean[]{false,true}) {
            g=M3GTest.renderer();DirectX7 combinedCPU=new DirectX7(32,32);combinedCPU.setRenderer(DirectX7.M3G);combinedCPU.clearDisplay(0x123456);
            code.Gameplay.Map.Skybox bg=pano?new code.Gameplay.Map.Skybox(panoTexture,2,1,-91,0.2f,0,0x334455,0x667788):new code.Gameplay.Map.Skybox(0x123456);
            bg.skyboxAlways=true;bg.render(null,combinedCPU);combinedCPU.renderBackground();
            Texture world=M3GTest.texture(0xffc08040);M3GTest.submit(combinedCPU,M3GTest.triangle(),world,Integer.MIN_VALUE);combinedCPU.renderSoftware();
            bg.render(null,g);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,Integer.MIN_VALUE);g.render();
            M3GTest.check(!g.isM3GActive() && g.getRendererError()!=null && java.util.Arrays.equals(combinedCPU.display,g.display),
                    "missing API lost combined background/world replay");
            int[] once=g.display.clone();g.render();M3GTest.check(java.util.Arrays.equals(once,g.display),"missing API retained stale sky prefix");
            combinedCPU.destroy();g.destroy();
        }
        g=M3GTest.renderer();
        Texture gradientTexture=new Texture(new RawImage(new int[]{0xff123456,0xff90b0d0},1,2),true,null);
        code.Gameplay.Map.Skybox gradient=new code.Gameplay.Map.Skybox(gradientTexture,-91,1,0);gradient.skyboxAlways=true;
        gradient.render(null,g);g.render();
        for(int pixel:g.display) M3GTest.check(pixel==0x123456,"missing API revived gradient instead of flat replacement");
        M3GTest.check(!g.isM3GActive() && g.getRendererError()!=null,"gradient replacement requires optional API to fall back");g.destroy();
        g=M3GTest.renderer();DirectX7 effectsCPU=new DirectX7(32,32);effectsCPU.clearDisplay(0x123456);
        byte[] vignette=new byte[32*32]; // Coefficient 128.
        Texture worldTexture=M3GTest.texture(0xffa0c0e0);
        M3GTest.submit(effectsCPU,M3GTest.triangle(),worldTexture,0);effectsCPU.render();
        FrameEffects effects=new FrameEffects();effects.set(vignette,true,true);effects.render(effectsCPU,null);
        M3GTest.submit(g,M3GTest.triangle(),worldTexture,0);g.renderWithEffects(vignette,true,true);
        M3GTest.check(!g.isM3GActive() && java.util.Arrays.equals(effectsCPU.display,g.display),"missing API lost combined frame effects or ran them twice");
        g.destroy();effectsCPU.destroy();
        g=M3GTest.renderer();effectsCPU=new DirectX7(32,32);effectsCPU.clearDisplay(0x123456);
        M3GTest.submit(effectsCPU,M3GTest.triangle(),worldTexture,0);effectsCPU.render();effects.render(effectsCPU,null);
        ScreenLines tail=new ScreenLines();tail.rect(2,3,1,1,0xffffff);
        g.startMuzzleGlow();M3GTest.submit(g,M3GTest.triangle(),worldTexture,0);
        M3GTest.check(!g.renderGameFrame(vignette,true,true,null,0,tail),"missing API reported native weather success");
        M3GTest.check(!g.isM3GActive() && java.util.Arrays.equals(effectsCPU.display,g.display),"missing API combined tail corrupted scene replay");
        M3GTest.check(g.getM3GDrawCalls()==0 && g.getM3GTextureUploads()==0
                && g.getM3GStatsText(1000).startsWith("CPU "),"stats/HUD requires missing optional API");
        g.destroy();effectsCPU.destroy();
        System.out.println("PASS missing JSR-184 API at runtime / sky / weather overlay / sprite+panorama class safety / combined queue replay / HW perspective="+code.utils.Main.m3gPerspective);
    }
}
