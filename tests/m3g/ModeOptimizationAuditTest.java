package code.Rendering;

import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.Math.Matrix;
import code.utils.Main;
import javax.microedition.m3g.*;
import javax.microedition.lcdui.Image;
import java.util.Arrays;

/** Cross-mode regression audit: native work/transfer counts, not device FPS. */
public final class ModeOptimizationAuditTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    static SkyMesh sky(int mode) {
        Vertex a=new Vertex(-40,40,-100),b=new Vertex(40,40,-100),c=new Vertex(-40,-40,-150);
        Polygon3V p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        Texture t=new Texture(new RawImage(new int[]{0xff001122,0xff334455,0xff667788,0xff99aabb,
                0xffcc0011,0xffbb5500,0xff0000aa,0xffddffff},2,4),false,null);t.drawmode=(byte)mode;
        MultyTexture mt=new MultyTexture();mt.textures=new Texture[]{t};
        SkyMesh s=new SkyMesh(new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p},mt));
        s.set(new Matrix(),0,0,32,32);return s;
    }
    private static void faces(DirectX7 g,Texture t) {
        for(int i=0;i<100;i++) {
            Polygon3V p=M3GTest.triangle();p.a.rz-=i%11;p.b.rz-=i%17;p.c.rz-=i%23;
            p.a.sx+=i%3;p.b.sy+=i%4;M3GTest.submit(g,p,t,i);
        }
        g.render();
    }
    static void cpuSkyAliases() {
        for(int mode:new int[]{11}) {
            DirectX7 actual=M3GTest.renderer(),expected=M3GTest.renderer();
            DirectX7.fDist=100;DirectX7.waterDistance=100;
            M3GTest.submit(actual,sky(mode),null,0);actual.renderSoftware();
            M3GTest.submit(expected,sky(0),null,0);expected.renderSoftware();
            boolean changed=false;for(int pixel:expected.display) if(pixel!=0x123456) changed=true;
            check(changed,"CPU sky alias reference drew nothing");
            check(Arrays.equals(actual.display,expected.display),"sky CPU replay bypassed material alias "+mode);
            actual.destroy();expected.destroy();
        }
    }
    private static RenderObject polygon(int kind) {
        Polygon3V p=M3GTest.triangle();
        Vertex a=p.a,b=p.b,c=p.c,d=M3GTest.vertex(10,10,-200);
        if(kind==0) return p;
        if(kind==1) return new Polygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==2) return new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==3) return new LightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==4) return new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        return new ColorLightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
    }
    private static void combined(DirectX7 g,Texture world,Texture mul,byte[] mask,ScreenLines weather) {
        M3GTest.submit(g,new SolidSky(0x345678),null,0);g.renderBackground();
        for(int i=0;i<100;i++) M3GTest.submit(g,M3GTest.triangle(),world,-1000+i);
        M3GTest.submit(g,SpriteTest.sprite(mul,2),null,0);
        check(g.renderGameFrame(mask,false,true,null,0,weather),"changed-material combined weather failed");
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(int mode:new int[]{10,1,7,2}) {
            DirectX7 g=M3GTest.renderer();SkyMesh s=sky(mode);
            int calls=Graphics3D.calls;M3GTest.submit(g,s,null,0);g.render();
            check(g.isM3GActive() && Graphics3D.calls>calls,"changed sky material silently fell back to CPU: "+mode);
            int blend=mode==1?CompositingMode.ALPHA:mode==2?CompositingMode.ALPHA_ADD:
                    mode==7?CompositingMode.MODULATE:CompositingMode.MODULATE_X2;
            check(Graphics3D.history.lastElement().blend==blend,"sky blend wrong "+mode);
            Texture world=M3GTest.texture(0xff8090a0);world.drawmode=(byte)mode;
            M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
            g.beginFrameStats();int binds=Graphics3D.binds,reads=Image.getRGBCalls;
            M3GTest.submit(g,s,null,0);g.renderBackground();M3GTest.submit(g,M3GTest.triangle(),world,0);g.render();
            check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1
                    && g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0,"warm changed sky/world split transaction "+mode);
            g.destroy();
        }
        for(int mode:new int[]{1,5,7,10}) for(int failure=0;failure<4;failure++) {
            DirectX7 g=M3GTest.renderer(),cpu=M3GTest.renderer();
            Texture world=M3GTest.texture(0x808090a0);world.drawmode=(byte)mode;
            M3GTest.submit(cpu,sky(mode),null,0);cpu.renderBackground();
            M3GTest.submit(cpu,M3GTest.triangle(),world,Integer.MIN_VALUE);cpu.renderSoftware();
            if(failure<2) Graphics3D.failAt=Graphics3D.calls+1+failure;
            if(failure==2) System.setProperty("readback.fail","true");
            if(failure==3) System.setProperty("m3g.oom","true");
            M3GTest.submit(g,sky(mode),null,0);g.renderBackground();
            M3GTest.submit(g,M3GTest.triangle(),world,Integer.MIN_VALUE);g.render();
            Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("m3g.oom");
            check(!g.isM3GActive() && Arrays.equals(g.display,cpu.display) && Graphics3D.target==null,
                    "changed sky/world complete rollback failed "+mode+"/"+failure);g.destroy();cpu.destroy();
        }
        cpuSkyAliases();
        for(boolean hw:new boolean[]{false,true}) for(int mode:new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13}) {
            Main.m3gPerspective=hw;DirectX7 g=M3GTest.renderer();DirectX7.fDist=1000;
            Texture t=M3GTest.texture(0x808090a0);t.drawmode=(byte)mode;faces(g,t);
            g.beginFrameStats();int binds=Graphics3D.binds,reads=Image.getRGBCalls;
            faces(g,t);
            check(g.isM3GActive() && g.getM3GDrawCalls()==2,"100 faces lost batching mode="+mode+" D="+g.getM3GDrawCalls());
            check(g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0
                    && Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,"warm world transfer/cache regressed "+mode);
            g.destroy();
        }
        Main.m3gPerspective=true;
        for(int mode:new int[]{1,5,7,10}) {
            DirectX7 g=M3GTest.renderer();Texture world=M3GTest.texture(0x808090a0);world.drawmode=(byte)mode;
            Texture mul=M3GTest.texture(0xff808080);byte[] mask=new byte[32*32];
            ScreenLines weather=new ScreenLines();weather.rect(2,2,1,1,0x99aabb);
            combined(g,world,mul,mask,weather);g.beginFrameStats();
            int binds=Graphics3D.binds,reads=Image.getRGBCalls;
            combined(g,world,mul,mask,weather);
            check(g.getM3GDrawCalls()==6 && g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0
                    && Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,
                    "changed world + MUL/post/weather lost shared transaction mode="+mode);
            g.destroy();
        }
        for(boolean sameBlend:new boolean[]{false,true}) {
            DirectX7 g=M3GTest.renderer();Texture a=M3GTest.texture(0x808090a0),b=new Texture(a.rImg,true,null);
            a.drawmode=(byte)(sameBlend?1:7);b.drawmode=(byte)(sameBlend?4:10);
            M3GTest.submit(g,M3GTest.triangle(),a,0);M3GTest.submit(g,M3GTest.triangle(),b,1);g.render();g.beginFrameStats();
            for(int i=0;i<100;i++) M3GTest.submit(g,M3GTest.triangle(),i%2==0?a:b,i);
            g.render();check(g.getM3GDrawCalls()==(sameBlend?2:100) && g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0,
                    "blend equivalence/boundaries lost shared cache or painter order");g.destroy();
        }
        for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) {
            Main.persQ=pers;DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x808090a0);t.drawmode=1;
            RenderObject p=polygon(kind);p.sz=1577;g.drawSoftwareObject(p,t,false);
            check(p.sz==1577,"ALPHA still computes obsolete additive fog bounds kind="+kind+" pers="+pers);
            g.destroy();
        }
        System.out.println("PASS mode audit: 100 varying faces -> 2 draws for all 0..13 / HW ON+OFF / warm B0 T0 one bind+readback / changed native sky blends / CPU sky aliases / ALPHA equivalence+MODULATE boundaries / changed world+MUL+post+weather one transaction");
    }
}
