package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Native state and lighting-equation contracts, NOT a GPU rasterizer. */
public final class NativeLightingTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    static RenderObject polygon(int kind) {
        Vertex a=M3GTest.vertex(2,2,-100),b=M3GTest.vertex(25,2,-200),
                c=M3GTest.vertex(2,25,-300),d=M3GTest.vertex(25,25,-200);
        if(kind==0) return new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==1) return new Polygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==2) return new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==3) return new LightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==4) return new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        return new ColorLightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
    }
    private static Texture texture() {Texture t=M3GTest.texture(0xffdf9153);t.drawmode=5;return t;}
    private static void submit(DirectX7 g,RenderObject p,Texture t) {M3GTest.submit(g,p,t,0);g.render();}
    private static void direction(int[] d) {DirectX7.lightdirx=(short)d[0];DirectX7.lightdiry=(short)d[1];DirectX7.lightdirz=(short)d[2];}
    private static void near(int actual,int expected,int tolerance,String why) {
        for(int shift=0;shift<24;shift+=8) check(Math.abs(((actual>>shift)&255)-((expected>>shift)&255))<=tolerance,why);
    }
    static void contracts() {
        int[][] dirs={{0,0,4096},{0,0,-4096},{4096,0,0},{2048,0,4096},{0,0,0},
                {0,4096,0},{0,-4096,0},{32767,-32768,12345}};
        int[][] normals={{0,0,4096},{1000,2000,-3000},{0,0,0},{Integer.MIN_VALUE,1,Integer.MAX_VALUE}};
        for(int kind=0;kind<6;kind++) {
            DirectX7 g=M3GTest.renderer();Texture t=texture();RenderObject p=polygon(kind);
            for(int pers:new int[]{0,4}) for(int quality:new int[]{0,1,2}) for(int[] dir:dirs) for(int[] normal:normals) {
                Main.persQ=pers;Main.fogQ=quality;direction(dir);
                p.nx=normal[0];p.ny=normal[1];p.nz=normal[2];submit(g,p,t);
                check(g.isM3GActive(),"native lighting unexpectedly fell back: "+g.getRendererError());
                Graphics3D.Draw draw=last();
                check(draw.blend==CompositingMode.REPLACE && !draw.fog && draw.lit && draw.lights==2,"mode5 native material/lights");
                check(draw.ambientIntensity==0.25f && draw.directionalIntensity==(dir[0]==0&&dir[1]==0&&dir[2]==0?0:0.75f),"mode5 intensities");
                check(draw.diffuse==0xffffffff && (draw.ambient&0xffffff)==0xffffff && !draw.tracking
                        && draw.emissive==0 && draw.specular==0,"mode5 material alpha/tracking/specular");
                double packedLength=Math.sqrt((double)draw.normals[0]*draw.normals[0]+(double)draw.normals[1]*draw.normals[1]+(double)draw.normals[2]*draw.normals[2]);
                check(Math.abs(packedLength-32767)<1,"zero/non-unit normal submitted to driver");
                for(byte color:draw.colors) check((color&255)==255,"CPU light still baked into colors");
                for(int v=1;v<draw.normals.length/3;v++) for(int c=0;c<3;c++)
                    check(draw.normals[v*3+c]==draw.normals[c],"flat normal changed across vertices");
                for(int col=0;col<3;col++) for(int other=0;other<3;other++) {
                    double dot=0;for(int row=0;row<3;row++) dot+=draw.lightMatrix[row*4+col]*draw.lightMatrix[row*4+other];
                    check(Math.abs(dot-(col==other?1:0))<0.00001,"light basis not orthonormal");
                }
                double nl=Math.sqrt((double)p.nx*p.nx+(double)p.ny*p.ny+(double)p.nz*p.nz);
                double dl=Math.sqrt((double)dir[0]*dir[0]+(double)dir[1]*dir[1]+(double)dir[2]*dir[2]);
                double dot=nl==0||dl==0?0:Math.max(0,((double)p.nx*dir[0]+(double)p.ny*dir[1]+(double)p.nz*dir[2])/(nl*dl));
                double shade=0.25+0.75*dot;int expected=0;
                for(int shift=0;shift<24;shift+=8) expected|=(int)(((0xdf9153>>shift)&255)*shade+0.5)<<shift;
                near(g.display[0],expected,1,"Lambert orientation/ambient equation");
                check(Math.abs(DirectX7.mode5Shade(p.nx,p.ny,p.nz)/256.0-shade)<1.0/256,"CPU shade differs from native");
                int baseline=g.display[0];g.shootIntensity=999;g.flashlightEnabled=true;
                LightMapper.cameraVectorLight=true;g.camera.m03+=500;submit(g,p,t);
                near(g.display[0],baseline,0,"mode5 reacted to shot/torch/camera");
            }
            t.drawmode=0;submit(g,p,t);check(!last().lit && last().lights==0,"mode5 light/material leaked to ordinary material");g.destroy();
        }
        System.out.println("PASS native mode5 ambient+directional / 6 polygon types / flat normals / 25% ambient / no fogQ bypass / zero+extreme vectors / light orientation / no camera/torch/shot");
    }
    public static void main(String[] args) {
        Main.mipMapping=false;
        // A pending packet must retain its own direction even if lightdir changes.
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer r=new M3GSceneRenderer();Texture t=texture();
        RenderObject p=polygon(0);p.nx=p.ny=0;p.nz=4096;int start=Graphics3D.history.size();
        direction(new int[]{0,0,4096});check(r.draw(g,p,t,false),"direction packet A");
        direction(new int[]{0,0,-4096});check(r.draw(g,p,t,false),"direction packet B");r.finish(g);
        check(Graphics3D.history.size()==start+2,"direction change did not split packet");
        check(Graphics3D.history.get(start).lightMatrix[10]==1 && last().lightMatrix[10]==-1,"pending packet used newer lightdir");
        r.dispose();g.destroy();
        // Alternate lit/unlit packets within one bind, including world modes,
        // then immediate sky/sprite/post/weather adapters must remove the lights.
        g=M3GTest.renderer();DirectX7.fDist=1000;
        int[] modes={5,0,5,1,5,2,5,7,5,9,5,10,5,13};Texture[] materials=new Texture[modes.length];
        for(int i=0;i<modes.length;i++) {materials[i]=texture();materials[i].drawmode=(byte)modes[i];submit(g,polygon(0),materials[i]);}
        start=Graphics3D.history.size();int binds=Graphics3D.binds;
        for(int i=0;i<materials.length;i++) M3GTest.submit(g,polygon(0),materials[i],-1000+i);
        g.render();check(g.isM3GActive() && Graphics3D.history.size()==start+modes.length && Graphics3D.binds==binds+1,"lit/unlit packets reordered/merged or split the bind");
        for(int i=start;i<Graphics3D.history.size();i++) {
            Graphics3D.Draw d=Graphics3D.history.get(i);check(d.lit?d.lights==2:d.lights==0,"mixed mode light leakage");
        }
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.render();check(!last().lit && last().lights==0,"sky light leaked");
        Texture spriteTex=M3GTest.texture(0xff8090a0);M3GTest.submit(g,SpriteTest.sprite(spriteTex,2),null,0);g.render();
        check(!last().lit && last().lights==0,"sprite light leaked");g.destroy();
        g=M3GTest.renderer();g.startMuzzleGlow();start=Graphics3D.history.size();
        ScreenLines weather=new ScreenLines();weather.rect(2,2,1,1,0x80a0c0);
        M3GTest.submit(g,polygon(0),t,0);
        check(g.renderGameFrame(new byte[32*32],false,true,MuzzleGlowTest.wall(1000),0,weather),"lit combined frame failed");
        check(Graphics3D.history.get(start).lit && Graphics3D.history.size()==start+3,"lit/post/weather packets missing or fake flash remains");
        for(int i=start+1;i<Graphics3D.history.size();i++) check(!Graphics3D.history.get(i).lit && Graphics3D.history.get(i).lights==0,"light leaked to post/weather");
        g.destroy();
        // No Fog/distance-blackout, including the API-free perspective fallback.
        for(int pers:new int[]{0,4}) for(int[] dir:new int[][]{{0,0,4096},{0,0,-4096},{2048,0,4096},{0,0,0}}) {
            direction(dir);Main.persQ=pers;Main.fogQ=2;
            DirectX7 cpu=M3GTest.renderer();DirectX7.fDist=50;
            RenderObject face=polygon(0);face.nx=face.ny=0;face.nz=4096;
            M3GTest.submit(cpu,face,t,0);cpu.renderSoftware();int expected=0,shade=DirectX7.mode5Shade(0,0,4096);
            for(int shift=0;shift<24;shift+=8) expected|=((((0xdf9153>>shift)&255)*shade)>>8)<<shift;
            near(cpu.display[3*32+3],expected,0,"mode5 CPU ambient/front/zero/far equation");cpu.destroy();
        }
        // Native setup, draw and readback failures replay the selected-M3G
        // ambient equation, not the original Software's black back-facing light.
        direction(new int[]{0,0,-4096});
        for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) for(int quality:new int[]{0,2})
            for(String failure:new String[]{"material.fail","normals.fail","lights.fail","lights.second.fail","draw","readback.fail"}) {
                Main.persQ=pers;Main.fogQ=quality;
                DirectX7 cpu=M3GTest.renderer(),actual=M3GTest.renderer();
                RenderObject a=polygon(kind),b=polygon(kind);a.nx=b.nx=0;a.ny=b.ny=0;a.nz=b.nz=4096;
                Texture ordinary=M3GTest.texture(0xff8090a0);
                M3GTest.submit(cpu,polygon(0),ordinary,-100);M3GTest.submit(cpu,a,t,0);cpu.renderSoftware();
                M3GTest.submit(actual,polygon(0),ordinary,-100);M3GTest.submit(actual,b,t,0);
                if(failure.equals("draw")) Graphics3D.failAt=Graphics3D.calls+2;else System.setProperty(failure,"true");
                actual.render();Graphics3D.failAt=0;System.clearProperty(failure);
                check(!actual.isM3GActive() && Graphics3D.target==null && Graphics3D.lights.size()==0,"native light failure did not release/disable");
                check(Arrays.equals(cpu.display,actual.display),"ambient equation lost in frame recovery: "+failure);
                // CPU first inside pixel is ambient-only, not black or unlit.
                near(cpu.display[3*32+3],0x372414,1,"CPU ambient floor/fogQ/UV path");
                DirectX7 software=new DirectX7(32,32);RenderObject original=polygon(kind);original.nz=4096;original.nx=original.ny=0;
                M3GTest.submit(software,original,t,0);software.render();
                int legacy=pers>0&&quality==0?0xdf9153:0;
                near(software.display[3*32+3],legacy,0,"original Software was changed");
                software.destroy();actual.destroy();cpu.destroy();
            }
        System.out.println("PASS native mode5 pending lightdir snapshot / lit-unlit isolation / setup+normal+light+draw+readback atomic recovery / ambient preserved / original Software unchanged");
    }
}
