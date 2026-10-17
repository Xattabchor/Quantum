package code.Rendering;

import code.Rendering.Meshes.*;
import code.Math.Matrix;
import code.Gameplay.Map.LightMapper;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;

/** Uploaded-alpha metadata and native state contracts, not GPU screenshots. */
public final class OpaqueTextureTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static DirectX7 renderer() {DirectX7.fDist=1000;return M3GTest.renderer();}
    private static Texture texture(boolean hole) {
        int[] pixels={0xff123456,0xff000000,0x00123456,hole?0:0x80123456};
        RawImage raw=new RawImage(pixels.clone(),2,2);raw.img=pixels;raw.alphaMixing=false;
        return new Texture(raw,true,null);
    }
    private static void world(DirectX7 g,Texture t,float threshold) {
        M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
        check(g.isM3GActive() && last().threshold==threshold,"wrong world alpha-test state mode "+t.drawmode);
    }
    private static Sprite sprite(Texture t,int mode) {
        Sprite s=new Sprite(t,5);s.mode=(byte)mode;
        s.pos.sx=0;s.pos.sy=0;s.pos.rz=-100;s.size.sx=32;s.size.sy=32;return s;
    }
    private static void sprite(DirectX7 g,Sprite s,float threshold) {
        int start=Graphics3D.history.size();M3GTest.submit(g,s,null,0);g.render();
        check(g.isM3GActive() && Graphics3D.history.elementAt(start).threshold==threshold,"wrong sprite alpha-test state");
    }
    private static SkyMesh sky(Texture t) {
        Vertex a=new Vertex(-40,40,-100),b=new Vertex(40,40,-100),c=new Vertex(-40,-40,-200);
        Polygon3V p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture mt=new MultyTexture();mt.textures=new Texture[]{t};
        SkyMesh sky=new SkyMesh(new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p},mt));sky.set(new Matrix(),0,0,32,32);return sky;
    }
    public static void main(String[] args) {
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;Main.forceLQFog=false;
        for(boolean correction:new boolean[]{true,false}) for(int mode:new int[]{0,1,2,4,5,9,10,11,12,13}) {
            Main.m3gPerspective=correction;DirectX7 g=renderer();
            Texture opaque=texture(false),cutout=texture(true);opaque.drawmode=cutout.drawmode=(byte)mode;
            int[] original=opaque.rImg.img.clone();
            world(g,opaque,0);world(g,cutout,0.5f);
            check(Graphics3D.lastAppearance.tex.image.pixels[15]==0,"hole alpha changed");
            world(g,opaque,0);check(Arrays.equals(original,opaque.rImg.img),"original texture changed");g.destroy();
        }
        // Native Fog and legacy colored/black fog use the same first-pass metadata;
        // the additive mask pass must retain its original zero threshold.
        LightMapper.cameraVectorLight=false;
        for(int mode:new int[]{3,6}) for(int quality:new int[]{0,1,2}) for(int perspective:new int[]{0,4}) for(boolean hole:new boolean[]{false,true}) {
            Main.persQ=perspective;Main.fogQ=quality;DirectX7 g=renderer();Texture t=texture(hole);t.drawmode=(byte)mode;
            int start=Graphics3D.history.size();M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();
            check(g.isM3GActive() && Graphics3D.history.elementAt(start).threshold==(hole?0.5f:0),"fog first-pass threshold");
            for(int i=start+1;i<Graphics3D.history.size();i++) check(Graphics3D.history.elementAt(i).threshold==0,"fog mask threshold changed");g.destroy();
        }
        Main.persQ=4;Main.fogQ=2;
        System.out.println("PASS opaque world alpha-test OFF / one zero texel keeps cutout ON / opaque black+legacy nonzero RGB / material aliases+lighting+native/legacy fog / PC ON+OFF");

        DirectX7 g=renderer();Texture opaque=texture(false),cutout=texture(true);
        for(int mode:new int[]{0,3}) {
            Sprite full=sprite(opaque,mode),holes=sprite(cutout,mode);sprite(g,full,0);sprite(g,holes,0.5f);
            full.fog=holes.fog=true;sprite(g,full,0);sprite(g,holes,0.5f);
        }
        Texture blended=M3GTest.texture(0x80123456);blended.rImg.alphaMixing=true;blended.drawmode=4;
        world(g,blended,0);check(last().blend==CompositingMode.ALPHA && last().textureAlpha==128,"world blending replaced by cutout");
        sprite(g,sprite(blended,3),0);check(last().blend==CompositingMode.ALPHA,"sprite blending changed");
        sprite(g,sprite(cutout,1),0);check(last().blend==CompositingMode.ALPHA_ADD,"ADD sprite changed");
        for(Texture t:new Texture[]{opaque,cutout,blended}) {
            int start=Graphics3D.history.size();M3GTest.submit(g,sky(t),null,0);g.render();
            check(Graphics3D.history.elementAt(start).threshold==(t==cutout?0.5f:0),"model sky alpha-test state");
        }
        // Vertex alpha matters too: do not relax the old threshold of a custom
        // non-alpha muzzle texture merely because its texels are all opaque.
        MuzzleGlow glow=new MuzzleGlow();glow.texture=opaque;glow.alpha=100;
        check(g.renderOverlay(glow) && last().threshold==0.5f,"opaque texture with fading vertex alpha lost threshold");g.destroy();
        System.out.println("PASS sprites+fog+model sky / ALPHA and ADD unchanged / fading vertex-alpha effect retains original behavior");

        g=renderer();int[] pixels=new int[15];Arrays.fill(pixels,0xff123456);pixels[7]=0;
        RawImage raw=new RawImage(pixels,3,5);raw.alphaMixing=false;Texture npot=new Texture(raw,true,null);
        sprite(g,sprite(npot,0),0); // Source row 2 isn't sampled by the existing 3x5 -> 2x4 reduction.
        check(pixels[7]==0 && raw.w==3 && raw.h==5,"NPOT source modified");
        pixels=pixels.clone();pixels[14]=0;raw.img=pixels;sprite(g,sprite(npot,0),0.5f);g.destroy();
        System.out.println("PASS metadata follows actual reduced sprite upload / surviving hole retains test / original NPOT pixels+dimensions unchanged");

        g=renderer();Texture t=texture(false);world(g,t,0);int uploads=Graphics3D.uploads;
        for(int i=0;i<16;i++) world(g,t,0);check(Graphics3D.uploads==uploads,"warm opaque texture reuploaded");
        t.rImg.img[3]=0;world(g,t,0);check(Graphics3D.uploads==uploads,"in-place edit unexpectedly rescanned/reuploaded cached texture");
        g.releaseRenderer();world(g,t,0.5f);t.rImg.img=new int[]{-1,-1,-1,-1};world(g,t,0);
        t.rImg.img=new int[]{-1,0,-1,-1};world(g,t,0.5f);
        Sprite animated=sprite(texture(false),0);animated.setTextures(new Texture[]{texture(false),texture(true)});
        animated.anim_index=0;sprite(g,animated,0);animated.anim_index=1;sprite(g,animated,0.5f);animated.anim_index=0;sprite(g,animated,0);
        g.destroy();
        System.out.println("PASS warm cache / existing in-place edit+release contract / array replacement+texture animation update cached alpha metadata");

        // Opaque packets still merge; changing opacity/material retains ordering.
        g=renderer();opaque=texture(false);cutout=texture(true);
        int start=Graphics3D.history.size();
        for(int i=0;i<100;i++) M3GTest.submit(g,M3GTest.triangle(),opaque,i);g.render();
        check(Graphics3D.history.size()==start+2,"opaque batch draw reduction lost");
        for(int i=start;i<Graphics3D.history.size();i++) check(Graphics3D.history.elementAt(i).threshold==0,"opaque batch still alpha-tests");
        start=Graphics3D.history.size();
        for(int i=0;i<6;i++) M3GTest.submit(g,M3GTest.triangle(),i<2||i>=4?opaque:cutout,i);g.render();
        check(Graphics3D.history.size()==start+3 && Graphics3D.history.elementAt(start).threshold==0
                && Graphics3D.history.elementAt(start+1).threshold==0.5f && last().threshold==0,"batch opacity transitions lost order");g.destroy();
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);M3GTest.submit(cpu,M3GTest.triangle(),opaque,0);cpu.render();
        g=renderer();Graphics3D.failAt=Graphics3D.calls+1;M3GTest.submit(g,M3GTest.triangle(),opaque,0);g.render();Graphics3D.failAt=0;
        check(!g.isM3GActive() && Arrays.equals(cpu.display,g.display),"opaque path broke CPU replay");g.destroy();cpu.destroy();
        System.out.println("PASS opaque 100 triangles -> 2 draws / opaque-cutout-opaque state boundaries / original CPU rollback");
    }
}
