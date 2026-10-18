package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Native EXP state, level config and deterministic CPU replay contracts. */
public final class Mode12FogTest {
    static void check(boolean value,String why) {M3GTest.check(value,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static Texture texture(int mode,int pixel) {Texture t=M3GTest.texture(pixel);t.drawmode=(byte)mode;return t;}
    private static void submit(DirectX7 g,RenderObject p,Texture t) {M3GTest.submit(g,p,t,0);g.render();}
    public static void main(String[] args) {
        DirectX7.fogc=0xabcdef;DirectX7.configureM3GExpFog(null,null);
        check(DirectX7.getM3GExpFogDensity()==0.02f && DirectX7.getM3GExpFogColor()==0xabcdef,"EXP defaults");
        DirectX7.configureM3GExpFog("12, 34,56","0.125");
        check(DirectX7.getM3GExpFogColor()==0x0c2238 && DirectX7.getM3GExpFogDensity()==0.125f,"EXP level values");
        for(String bad:new String[]{"-1","NaN","Infinity","1001","oops",""}) {
            DirectX7.configureM3GExpFog("300,0,0",bad);
            check(DirectX7.getM3GExpFogDensity()==0.02f && DirectX7.getM3GExpFogColor()==0xabcdef,"invalid config inherited prior level");
        }
        for(float density:new float[]{0,0.001f,0.02f,0.2f,5,1000}) {
            DirectX7.configureM3GExpFog(null,""+density);
            for(int depth:new int[]{0,1,100,1000,50000,Integer.MAX_VALUE}) {
                int expected=(int)(255*Math.exp(-density*(1+depth/(double)TexturingPers.move))+0.5);
                check(Math.abs(expected-Mode12Fog.factor(-depth))<=1,"CLDC exp approximation");
            }
        }
        System.out.println("PASS EXP12 level keys / defaults+reset / invalid finite ranges / RGB / CLDC exp approximation");

        Main.mipMapping=false;Main.forceLQFog=false;
        for(int kind=0;kind<6;kind++) for(int quality:new int[]{0,1,2}) for(int pers:new int[]{0,4}) {
            DirectX7.configureM3GExpFog("24,48,96","0.1");Main.fogQ=quality;Main.persQ=pers;
            DirectX7 g=M3GTest.renderer();DirectX7.fDist=0;DirectX7.waterDistance=0;
            Texture t=texture(12,0x80e08020);RenderObject p=DitherDisabledTest.polygon(kind);
            int[] pixels=t.rImg.img.clone();submit(g,p,t);
            check(g.isM3GActive() && last().fog==(quality>0),"EXP native route/quality gate");
            if(quality>0) {
                check(last().fogMode==Fog.EXPONENTIAL && last().density==0.1f && last().fogColor==0x183060,"EXP state/color/density");
                check(pers!=0 || !last().perspective,"affine EXP enabled perspective UV");
                double z=-last().positions[2]*last().scale;
                int expected=(int)Math.round(224*Math.exp(-0.1*z)+24*(1-Math.exp(-0.1*z)));
                check(Math.abs(((g.display[0]>>16)&255)-expected)<=1,"EXP color equation/eye depth");
            }
            check(last().textureAlpha==255 && t.drawmode==12 && Arrays.equals(pixels,t.rImg.img),"EXP mutated opaque source/material");
            int uploads=Graphics3D.uploads;submit(g,p,t);check(Graphics3D.uploads==uploads,"EXP warm upload");
            Texture zero=texture(12,0);g.clearDisplay(0x112233);submit(g,p,zero);check(g.display[0]==0x112233,"EXP lost cutout");
            submit(g,p,texture(11,0xffe08020));check(!last().fog && last().function==Texture2D.FUNC_DECAL,"EXP leaked into DECAL");
            DirectX7.fDist=1000;submit(g,p,texture(3,0xffe08020));check(last().fogMode==Fog.LINEAR || !last().fog,"EXP leaked into linear Fog");g.destroy();
        }
        System.out.println("PASS EXP12 six polygon classes / affine+perspective / quality OFF-LQ-HQ / native equation / fDist0 / cutout / warm cache / DECAL+linear reset");

        Main.persQ=4;Main.fogQ=2;DirectX7.configureM3GExpFog("20,40,60","0.1");
        for(int scenario=0;scenario<5;scenario++) {
            DirectX7 g=M3GTest.renderer(),cpu=new DirectX7(32,32);cpu.setRenderer(DirectX7.M3G);
            Texture t=texture(12,0xffe08020);RenderObject p=DitherDisabledTest.polygon(0),ref=DitherDisabledTest.polygon(0);
            if(scenario==0) ((Polygon3V)p).a.rz=((Polygon3V)ref).a.rz=1;
            if(scenario==1) g.flushAlpha=cpu.flushAlpha=true;
            g.clearDisplay(0x123456);cpu.clearDisplay(0x123456);
            M3GTest.submit(cpu,ref,t,0);cpu.renderSoftware();
            if(scenario==2) Graphics3D.failAt=Graphics3D.calls+1;
            if(scenario==3) System.setProperty("readback.fail","true");
            if(scenario==4) System.setProperty("exp.unsupported","true");
            submit(g,p,t);Graphics3D.failAt=0;System.clearProperty("readback.fail");System.clearProperty("exp.unsupported");
            check(Arrays.equals(g.display,cpu.display),"EXP full CPU recovery differs");
            check(!g.isExpFogPass() && t.drawmode==12,"EXP leaked CPU scope");g.destroy();cpu.destroy();
        }
        System.out.println("PASS EXP12 geometry+flushAlpha fallback / native+readback complete replay / CPU scope cleanup");

        for(int pers:new int[]{0,4}) for(boolean floor:new boolean[]{false,true}) {
            Main.persQ=pers;Main.fogQ=1;Main.forceLQFog=true;
            DirectX7.configureM3GExpFog("24,48,96","0.1");DirectX7 g=M3GTest.renderer();DirectX7.fDist=0;
            Polygon3V p=M3GTest.triangle();p.a.rz=p.b.rz=p.c.rz=-1000;if(floor) p.ny=4096;
            g.drawSoftwareObject(p,texture(12,0xffe08020),false);
            int pixel=g.display[4*32+4];double weight=Math.exp(-0.1*(1+1000.0/TexturingPers.move));
            for(int channel=0;channel<3;channel++) {
                int shift=16-channel*8,src=(0xe08020>>shift)&255,fog=(0x183060>>shift)&255;
                check(Math.abs(((pixel>>shift)&255)-Math.round(src*weight+fog*(1-weight)))<=3,"CPU EXP didn't reach actual colored raster: "+Integer.toHexString(pixel));
            }
            check(DirectX7.fDist==0,"CPU EXP leaked temporary distance");g.destroy();
        }
        System.out.println("PASS actual EXP CPU pixels / affine+perspective+floor / colored vertex fog / force-LQ recovery / zero original distance");

        Main.persQ=4;Main.fogQ=2;Main.forceLQFog=false;
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer nativeRenderer=new M3GSceneRenderer();
        Texture exp=texture(12,0xffe08020),linear=new Texture(exp.rImg,true,null);linear.drawmode=3;
        Texture plain=new Texture(exp.rImg,true,null);plain.drawmode=0;
        Polygon3V p=M3GTest.triangle();p.a.rz=p.b.rz=p.c.rz=-1000;DirectX7.fDist=1000;
        nativeRenderer.draw(g,p,plain,false);nativeRenderer.finish(g);int start=Graphics3D.history.size();
        DirectX7.configureM3GExpFog("20,40,60","0.1");nativeRenderer.draw(g,p,exp,false);
        DirectX7.configureM3GExpFog("20,40,60","0.2");nativeRenderer.draw(g,p,exp,false);
        nativeRenderer.draw(g,p,linear,false);nativeRenderer.draw(g,p,exp,false);nativeRenderer.finish(g);
        check(Graphics3D.history.size()==start+4,"EXP density/type boundary merged");
        check(Graphics3D.history.elementAt(start).density==0.1f && Graphics3D.history.elementAt(start+1).density==0.2f,"EXP changed pending packet density");
        check(Graphics3D.history.elementAt(start+2).fogMode==Fog.LINEAR && last().fogMode==Fog.EXPONENTIAL && last().density==0.2f,"LINEAR/EXP switch lost mode/density");
        g.beginFrameStats();start=Graphics3D.history.size();
        for(int i=0;i<65;i++) nativeRenderer.draw(g,p,exp,false);nativeRenderer.finish(g);
        check(Graphics3D.history.size()==start+2 && g.getM3GTextureUploads()==0,"compatible EXP lost 64-triangle batching/cache");
        nativeRenderer.dispose();g.destroy();
        System.out.println("PASS EXP density snapshot / LINEAR-EXP type isolation / shared texture / 65 faces -> 2 draws / warm T0");

        g=M3GTest.renderer();DirectX7.fDist=1234;DirectX7.fogc=0xabcdef;
        final Texture scoped=texture(12,0xffe08020);
        RenderObject probe=new RenderObject() {
            public boolean isVisible(int a,int b,int c,int d) {return true;}
            public void renderFast(DirectX7 g,Texture t) {render(g,t);}
            public void render(DirectX7 g,Texture t) {check(g.isExpFogPass() && t.drawmode==3,"CPU EXP not scoped");throw new IllegalStateException("scope");}
        };
        try {g.drawSoftwareObject(probe,scoped,false);throw new AssertionError("no scope exception");}
        catch(IllegalStateException expected) { }
        check(!g.isExpFogPass() && scoped.drawmode==12 && DirectX7.fDist==1234 && DirectX7.fogc==0xabcdef,"exception leaked EXP/global material scope");g.destroy();
        for(int pers:new int[]{0,4}) {
            Main.persQ=pers;g=new DirectX7(32,32);DirectX7.fDist=1000;DirectX7.fogc=0xabcdef;
            DirectX7.configureM3GExpFog("1,2,3","0");Texture t=texture(12,0xffe08020);Polygon3V face=M3GTest.triangle();
            submit(g,face,t);int[] before=g.display.clone();g.clearDisplay(0);
            DirectX7.configureM3GExpFog("200,220,240","1000");submit(g,face,t);
            check(Arrays.equals(before,g.display),"M3G EXP keys changed original Software mode12");g.destroy();
        }
        System.out.println("PASS EXP exception cleanup / original Software mode12 ignores native keys");
        DirectX7.configureM3GExpFog(null,null);
    }
}
