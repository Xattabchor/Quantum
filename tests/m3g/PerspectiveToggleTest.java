package code.Rendering;

import code.Gameplay.Map.Skybox;
import code.Rendering.Meshes.*;
import code.Math.Matrix;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.PolygonMode;

/** Actual native PolygonMode state, not a software approximation of the toggle. */
public final class PerspectiveToggleTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static void submit(DirectX7 g,RenderObject p,Texture t) {
        M3GTest.submit(g,p,t,0);g.render();check(g.isM3GActive(),"toggle disabled native backend: "+g.getRendererError());
    }
    private static void state(int start,boolean flag) {
        check(Graphics3D.history.size()>start,"missing native draw");
        for(int i=start;i<Graphics3D.history.size();i++) check(Graphics3D.history.elementAt(i).perspective==flag,"PolygonMode didn't follow hardware perspective toggle");
    }
    private static SkyMesh model() {
        Vertex a=new Vertex(-40,-40,-100),b=new Vertex(40,-40,-100),c=new Vertex(-40,40,-200);
        Polygon3V p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture t=new MultyTexture();t.textures=new Texture[]{M3GTest.texture(0xff7090b0)};
        SkyMesh s=new SkyMesh(new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p},t));s.set(new Matrix(),0,0,32,32);return s;
    }
    public static void main(String[] args) {
        check(Main.m3gPerspective,"old installs must default to hardware PC on");
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;DirectX7.fDist=1000;
        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x807090b0);
        for(int mode:new int[]{0,1,2,3,4,5,6,7,8,9,13}) {
            t.drawmode=(byte)mode;RenderObject p=M3GTest.triangle();Main.m3gPerspective=true;submit(g,p,t);
            Graphics3D.Draw original=last();int uploads=Graphics3D.uploads;
            for(boolean flag:new boolean[]{false,true,false,true}) {
                int binds=Graphics3D.binds,releases=Graphics3D.releases,reads=javax.microedition.lcdui.Image.getRGBCalls;
                Main.m3gPerspective=flag;int start=Graphics3D.history.size();submit(g,p,t);state(start,flag);
                check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1
                        && javax.microedition.lcdui.Image.getRGBCalls==reads+1,"toggle added framebuffer transfers");
                Graphics3D.Draw current=last();
                check(Arrays.equals(original.positions,current.positions) && Arrays.equals(original.coords,current.coords)
                        && Arrays.equals(original.colors,current.colors) && Arrays.equals(original.indices,current.indices)
                        && original.scale==current.scale && original.uvScale==current.uvScale
                        && original.fog==current.fog && original.near==current.near && original.far==current.far,
                        "hardware toggle changed geometry/UV/light/fog weights mode "+mode);
                check(Graphics3D.uploads==uploads && t.drawmode==mode && Main.persQ==4,"toggle reuploaded texture or changed legacy quality/material");
            }
        }
        System.out.println("PASS hardware perspective ON/OFF across world materials / geometry+UV+fog+lighting unchanged / no texture reupload / old PERSQ unchanged");

        Sprite sprite=new Sprite(M3GTest.texture(0x80abcdef),5);sprite.mode=3;
        sprite.pos.sx=0;sprite.pos.sy=0;sprite.pos.rz=-100;sprite.size.sx=32;sprite.size.sy=32;
        SkyMesh sky=model();Skybox panorama=new Skybox(M3GTest.texture(0x807090b0),2,1,-91,0.2f,0,0,0);panorama.skyboxAlways=true;
        ScreenLines weather=new ScreenLines();weather.line(2,2,4,10,1,0xffffff,false);
        for(boolean flag:new boolean[]{false,true,false,true}) {
            Main.m3gPerspective=flag;int start=Graphics3D.history.size();submit(g,sky,null);state(start,flag);
            start=Graphics3D.history.size();submit(g,sprite,null);state(start,flag);
            start=Graphics3D.history.size();panorama.render(null,g);g.render();state(start,flag);
            start=Graphics3D.history.size();check(g.renderScreenLines(weather),"weather draw failed");state(start,flag);
            start=Graphics3D.history.size();submit(g,new SolidSky(0x123456),null);state(start,flag);
        }
        int setters=PolygonMode.perspectiveCalls;
        submit(g,sprite,null);panorama.render(null,g);g.render();submit(g,sky,null);
        check(PolygonMode.perspectiveCalls==setters,"unchanged preference resets native PolygonMode every draw");
        Main.m3gPerspective=false;submit(g,sky,null);g.releaseRenderer();
        int start=Graphics3D.history.size();submit(g,sky,null);state(start,false);
        g.resize(40,24);start=Graphics3D.history.size();submit(g,M3GTest.triangle(),M3GTest.texture(0xffffffff));state(start,false);g.destroy();
        System.out.println("PASS shared hardware toggle for model sky+sprites+panorama+weather+solid / existing+new helpers / cached property / resize+release");

        g=M3GTest.renderer();t=M3GTest.texture(0xff7090b0);
        for(int frame=0;frame<4;frame++) {
            Main.m3gPerspective=(frame%2)==1;
            int binds=Graphics3D.binds,releases=Graphics3D.releases,reads=javax.microedition.lcdui.Image.getRGBCalls;
            int uploads=Graphics3D.uploads;start=Graphics3D.history.size();
            M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();
            M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();state(start,Main.m3gPerspective);
            if(frame>0) check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1
                    && javax.microedition.lcdui.Image.getRGBCalls==reads+1 && Graphics3D.uploads==uploads,
                    "hardware toggle split warm combined sky/world transaction");
        }
        g.destroy();
        System.out.println("PASS live ON/OFF retains one warm combined sky/world transaction with no extra uploads/readbacks");

        // Native OFF does not flatten homogeneous geometry or force legacy CPU
        // fallback to affine: Software still follows its original PERSQ setting.
        for(int quality:new int[]{0,2,4}) {
            Main.persQ=quality;DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
            t=M3GTest.texture(0xff7090b0);Main.m3gPerspective=true;M3GTest.submit(cpu,M3GTest.triangle(),t,0);cpu.render();int[] on=cpu.display.clone();
            Main.m3gPerspective=false;cpu.clearDisplay(0x123456);M3GTest.submit(cpu,M3GTest.triangle(),t,0);cpu.render();
            check(Arrays.equals(on,cpu.display),"hardware toggle changed Software rendering");
            g=M3GTest.renderer();Graphics3D.failAt=Graphics3D.calls+1;M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();Graphics3D.failAt=0;
            check(Arrays.equals(on,g.display) && Main.persQ==quality,"hardware-OFF failure didn't replay original CPU quality");g.destroy();cpu.destroy();
        }
        Main.m3gPerspective=true;
        System.out.println("PASS Software unchanged for old quality 0/2/4 / native-OFF failure replays original CPU quality");
    }
}
