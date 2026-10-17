package code.Rendering;

import code.Gameplay.Map.House;
import code.Gameplay.Map.Room;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.Polygon4V;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Ray/pulse/overlay contracts; no GPU rasterization or real-game asset test. */
public final class MuzzleGlowTest {
    private static void check(boolean ok,String message) { M3GTest.check(ok,message); }
    private static House wall(int distance) {
        Vertex a=new Vertex(-3000,3000,-distance),b=new Vertex(3000,3000,-distance),
                c=new Vertex(3000,-3000,-distance),d=new Vertex(-3000,-3000,-distance);
        Polygon4V p=new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        MultyTexture t=new MultyTexture();t.textures=new Texture[]{M3GTest.texture(0xffffffff)};
        Room.chunkSize=Room.chunkSizeRender=0;
        Room room=new Room(new Mesh(new Vertex[]{a,b,c,d},new RenderObject[]{p},t),0);
        return new House(new Room[]{room},new Room[][]{new Room[0]});
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 g=new DirectX7(64,64);g.setRenderer(DirectX7.M3G);
        House near=wall(1000),far=wall(6000),behind=wall(-1000);
        java.lang.reflect.Field visibleField=House.class.getDeclaredField("roomsToRender");visibleField.setAccessible(true);
        java.util.Vector visible=(java.util.Vector)visibleField.get(near);
        int visibleCount=visible.size();
        MuzzleGlow glow=new MuzzleGlow();
        check(!glow.prepare(g,near,0,1000),"glow without a shot");
        glow.start(1000);
        check(!glow.prepare(g,null,0,1000) && !glow.prepare(g,near,-1,1000)
                && !glow.prepare(g,near,1,1000),"invalid room/house should skip cosmetic effect");
        check(!glow.prepare(g,far,0,1000),"far wall lit");
        check(!glow.prepare(g,behind,0,1000),"sky/empty forward ray lit");
        check(glow.prepare(g,near,0,1000),"near wall not detected by actual House raycast");
        check(visible.size()==visibleCount,"cosmetic ray changed visible-room list / portal debug");
        int peak=glow.alpha;
        check(peak>0 && peak<255,"unbounded glow intensity");
        check(glow.texture.rImg.w==32 && glow.texture.rImg.h==32 && glow.texture.rImg.alphaMixing,"procedural radial RGBA missing");
        int[] radial=glow.texture.rImg.img;
        check(radial[0]==0 && radial[31]==0 && radial[radial.length-1]==0,"blob has opaque square corners");
        check((radial[16+16*32]>>>24)>200 && (radial[16+16*32]&0xffffff)==0xffd080,"warm radial center");
        Texture original=glow.texture;
        check(glow.prepare(g,near,0,1060) && glow.alpha<peak && glow.texture==original,"time fade recreated texture");
        check(!glow.prepare(g,near,0,1120) && glow.alpha==0,"flash did not expire at 120ms");
        glow.start(2000);check(glow.prepare(g,near,0,2000) && glow.alpha==peak,"automatic-fire retrigger");
        g.camera.m03=10000;
        check(!glow.prepare(g,near,0,2001),"cached ray ignored camera movement into empty space");
        g.camera.m03=0;glow.start(3000);check(glow.prepare(g,near,0,3000),"pose reset");
        check(!glow.prepare(g,near,0,2999),"backward clock keeps stale pulse");
        System.out.println("PASS real surface ray gate / range / pose cache invalidation / procedural radial alpha / timed pulse");

        glow.start(4000);check(glow.prepare(g,near,0,4000),"prepare native flash");
        System.setProperty("m3g.sampleCenter","true");
        g.clearDisplay(0xff102030);int calls=Graphics3D.calls,uploads=Graphics3D.uploads;
        check(g.renderOverlay(glow) && Graphics3D.calls==calls+1,"blob is not one native quad draw");
        Graphics3D.Draw draw=Graphics3D.history.lastElement();
        check(draw.blend==CompositingMode.ALPHA_ADD && !draw.fog && draw.filter==Texture2D.FILTER_LINEAR,"blob is not soft additive / fog-free");
        for(int i=0;i<4;i++) check((draw.colors[i*4+3]&255)==glow.alpha,"fading vertex alpha");
        check(draw.indices.length==6,"blob must be exactly two triangles");
        int result=g.display[0];
        check(((result>>16)&255)>0x10 && ((result>>8)&255)>0x20 && (result&255)>0x30,"additive sample did not brighten framebuffer");
        check(g.shootIntensity==0 && !g.flashlightEnabled,"glow changed dynamic-light flags");
        glow.prepare(g,near,0,4060);g.renderOverlay(glow);
        check(Graphics3D.uploads==uploads+1,"pulse rebuilt/reuploaded texture every frame");
        System.clearProperty("m3g.sampleCenter");
        Texture alpha=M3GTest.texture(0x80ffffff);alpha.drawmode=4;
        M3GTest.submit(g,M3GTest.triangle(),alpha,0);g.render();
        check(Graphics3D.history.lastElement().blend==CompositingMode.ALPHA,"additive blending leaked into ordinary alpha material");
        M3GTest.Marker pending=new M3GTest.Marker(false);M3GTest.submit(g,pending,null,0);
        g.renderOverlay(glow);check(pending.calls==0,"glow consumed world queue");g.render();check(pending.calls==1,"world queue lost");
        check(Graphics3D.target==null,"glow target leak");g.destroy();
        System.out.println("PASS native additive quad / bilinear radial / brightness equation / texture reuse / state+queue isolation");

        // The actual DirectX7 trigger remains M3G-only and clears on resize/backend change.
        g=new DirectX7(64,64);calls=Graphics3D.calls;g.startMuzzleGlow();
        check(!g.renderMuzzleGlow(near,0) && Graphics3D.calls==calls,"Software grew a new glow effect");
        g.setRenderer(DirectX7.M3G);g.startMuzzleGlow();
        check(g.renderMuzzleGlow(near,0),"real trigger did not arm pulse");
        g.resize(64,64);check(!g.renderMuzzleGlow(near,0),"resize retained old muzzle flash");
        g.startMuzzleGlow();g.flushAlpha=true;check(!g.renderMuzzleGlow(near,0),"ARGB framebuffer used RGB overlay");
        g.flushAlpha=false;g.setRenderer(DirectX7.SOFTWARE);check(!g.renderMuzzleGlow(near,0),"backend switch retained flash");g.destroy();

        g=M3GTest.renderer();glow.start(5000);check(glow.prepare(g,near,0,5000),"prepare failure case");
        int[] before=g.display.clone();Graphics3D.failAt=Graphics3D.calls+1;
        check(!g.renderOverlay(glow),"native flash failure reported success");Graphics3D.failAt=0;
        check(Arrays.equals(before,g.display) && Graphics3D.target==null && !g.isM3GActive(),"failed blob damaged frame");g.destroy();
        g=M3GTest.renderer();before=g.display.clone();System.setProperty("readback.fail","true");
        check(!g.renderOverlay(glow),"flash readback failure ignored");System.clearProperty("readback.fail");
        check(Arrays.equals(before,g.display) && Graphics3D.target==null,"readback failed to restore frame");g.destroy();
        near.destroy();far.destroy();behind.destroy();
        System.out.println("PASS M3G-only trigger / resize+alpha / cosmetic failure rollback / original scene retained");
    }
}
