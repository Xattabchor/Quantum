package code.Rendering;

public final class MissingM3GTest {
    public static void main(String[] args) {
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
        System.out.println("PASS missing JSR-184 API at runtime / sky / weather overlay");
    }
}
