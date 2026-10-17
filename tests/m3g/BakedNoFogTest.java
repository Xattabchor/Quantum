package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Baked 9/13: remove distance attenuation, preserve existing color quality. */
public final class BakedNoFogTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static RenderObject face(int kind,int depth) {
        RenderObject p=NativeLightingTest.polygon(kind);
        if(p instanceof Polygon3V) {Polygon3V q=(Polygon3V)p;q.a.rz=q.b.rz=q.c.rz=-depth;}
        else {Polygon4V q=(Polygon4V)p;q.a.rz=q.b.rz=q.c.rz=q.d.rz=-depth;}
        if(p instanceof LightedPolygon3V) {LightedPolygon3V q=(LightedPolygon3V)p;q.la=q.lb=q.lc=64;}
        else if(p instanceof LightedPolygon4V) {LightedPolygon4V q=(LightedPolygon4V)p;q.la=q.lb=q.lc=q.ld=64;}
        else if(p instanceof ColorLightedPolygon3V) {
            ColorLightedPolygon3V q=(ColorLightedPolygon3V)p;q.ar=q.br=q.cr=64;q.ag=q.bg=q.cg=128;q.ab=q.bb=q.cb=224;
        } else if(p instanceof ColorLightedPolygon4V) {
            ColorLightedPolygon4V q=(ColorLightedPolygon4V)p;q.ar=q.br=q.cr=q.dr=64;q.ag=q.bg=q.cg=q.dg=128;q.ab=q.bb=q.cb=q.db=224;
        }
        return p;
    }
    private static Texture texture(int mode) {Texture t=M3GTest.texture(0xffffffff);t.drawmode=(byte)mode;return t;}
    private static int rgb(int kind,int mode,int quality) {
        if(kind<2 || quality==0) return 0xffffff;
        if(kind<4 || mode==9) return 0x404040;
        if(quality==1) return 0x8a8a8a; // (64+128+224)/3
        return 0x4080e0;
    }
    public static void main(String[] args) {
        Main.mipMapping=false;LightMapper.cameraVectorLight=false;
        for(int kind=0;kind<6;kind++) for(int mode:new int[]{9,13}) for(int pers:new int[]{0,4})
            for(int quality:new int[]{0,1,2}) {
                Main.persQ=pers;Main.fogQ=quality;DirectX7 g=M3GTest.renderer();Texture t=texture(mode);
                int uploads=Graphics3D.uploads;byte[] original=null;
                for(int distance:new int[]{Integer.MIN_VALUE,-1,0,1,100,1000,Integer.MAX_VALUE}) for(int depth:new int[]{0,100,2000,10000}) {
                    DirectX7.fDist=distance;int calls=Graphics3D.calls;
                    M3GTest.submit(g,face(kind,depth),t,0);g.render();
                    check(g.isM3GActive() && Graphics3D.calls==calls+1,"baked material depends on Fog range/depth");
                    Graphics3D.Draw d=Graphics3D.history.lastElement();
                    check(!d.fog && !d.lit && d.lights==0,"Fog or dynamic lighting left on baked material");
                    if(original==null) original=d.colors.clone();
                    check(Arrays.equals(original,d.colors),"distance faded baked vertex color");
                    int expected=rgb(kind,mode,quality);
                    for(int i=0;i<d.colors.length/4;i++) for(int c=0;c<3;c++)
                        check((d.colors[i*4+c]&255)==((expected>>(16-c*8))&255),"baked color/quality changed");
                    check((g.display[0]&0xffffff)==expected,"native texture * baked color not preserved");
                }
                check(Graphics3D.uploads==uploads+1,"Fog/range changes reupload baked texture");
                g.startMuzzleGlow();g.shootIntensity=1000;g.shootLength=0;g.flashlightEnabled=true;LightMapper.cameraVectorLight=true;
                M3GTest.submit(g,face(kind,1000),t,0);g.render();
                check(Arrays.equals(original,Graphics3D.history.lastElement().colors),"weapon/torch changed baked colors");
                g.destroy();LightMapper.cameraVectorLight=false;
            }
        System.out.println("PASS 9/13 texture*baked colors only / six polygon types / all color qualities / affine+perspective / extreme Fog ranges / near+far / no native Fog or OMNI / texture reuse");

        for(int kind=0;kind<6;kind++) for(int mode:new int[]{9,13}) for(int pers:new int[]{0,4})
            for(int quality:new int[]{0,1,2}) for(boolean floor:new boolean[]{false,true}) {
                Main.persQ=pers;Main.fogQ=quality;DirectX7 g=M3GTest.renderer();Texture t=texture(mode);int[] baseline=null;
                for(int distance:new int[]{0,1000}) for(int depth:new int[]{100,2000,10000}) {
                    DirectX7.fDist=distance;g.clearDisplay(0x123456);RenderObject p=face(kind,depth);
                    p.ny=floor?4096:0;M3GTest.submit(g,p,t,0);g.renderSoftware();
                    if(baseline==null) baseline=g.display.clone();
                    check(Arrays.equals(baseline,g.display),"CPU/floor fallback still fades baked color with distance");
                }
                check(!g.isStaticLightmapPass(),"CPU static scope leaked");g.destroy();
            }
        System.out.println("PASS selected-M3G CPU/floor 9/13 preserve baked colors across distance/range / unchanged fogQ color quality");

        Main.fogQ=2;Main.persQ=4;Main.mipMapping=false;
        for(int kind:new int[]{2,3,4,5}) for(int mode:new int[]{9,13}) for(boolean readback:new boolean[]{false,true}) {
            DirectX7 g=M3GTest.renderer(),cpu=M3GTest.renderer();DirectX7.fDist=1000;Texture t=texture(mode);
            M3GTest.submit(cpu,face(kind,2000),t,0);cpu.renderSoftware();
            M3GTest.submit(g,face(kind,2000),t,0);
            if(readback) System.setProperty("readback.fail","true");else Graphics3D.failAt=Graphics3D.calls+1;
            g.render();Graphics3D.failAt=0;System.clearProperty("readback.fail");
            check(!g.isM3GActive() && Graphics3D.target==null && Arrays.equals(g.display,cpu.display),"black Fog returned during frame replay");
            g.destroy();cpu.destroy();
        }
        for(int kind:new int[]{2,3,4,5}) for(int mode:new int[]{9,13}) {
            DirectX7 g=new DirectX7(32,32);DirectX7.fDist=1000;Texture t=texture(mode);
            M3GTest.submit(g,face(kind,100),t,0);g.render();int[] near=g.display.clone();
            M3GTest.submit(g,face(kind,2000),t,0);g.render();
            check(!Arrays.equals(near,g.display),"original Software distance fade was removed");g.destroy();
        }
        DirectX7 g=M3GTest.renderer();
        check(g.lightmapColor(-20,Integer.MIN_VALUE)==0 && g.lightmapColor(128,Integer.MAX_VALUE)==128
                && g.lightmapColor(999,-10000)==255,"API-free baked clamp depends on depth");g.destroy();
        System.out.println("PASS 9/13 native draw/readback atomic replay stays fog-free / original Software distance fade retained / API-free clamp");
    }
}
