package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;

/** Image ownership, conservative coverage and atomic clear failure, not GPU FPS. */
public final class SkyBackgroundTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static void queue(DirectX7 g,SkyMesh sky) {
        M3GTest.submit(g,sky,null,0);g.renderBackground();
    }
    private static void frame(DirectX7 g,SkyMesh sky) {
        queue(g,sky);g.renderGameFrameImage(null,false,false,null,0,null);
    }
    private static SkyMesh cube(DirectX7 g) {
        SkyMesh sky=SkyTest.cube();sky.set(new Matrix(),0,0,g.width,g.height);return sky;
    }
    private static DirectX7 renderer() {DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);return g;}
    private static void preserved(SkyMesh sky,DirectX7 g,String why) {
        check(!sky.clearsBackground(g),"unsafe certificate: "+why);
        M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();g.renderGameFrameImage(null,false,false,null,0,null);
        int reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls,clears=Graphics3D.clears;
        g.beginFrameStats();frame(g,sky);
        check(g.getM3GCpuBarriers()==1 && Image.getRGBCalls==reads+1 && Graphics.drawRGBCalls==copies+(g.getM3GDrawCalls()==0?0:1) && Graphics3D.clears==clears,"partial sky lost previous image: "+why);
        g.destroy();
    }
    public static void main(String[] args) {
        Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7 g=renderer();SkyMesh sky=cube(g);
        check(sky.clearsBackground(g),"closed opaque cube was not certified");
        int reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls,creates=Image.creates;
        System.setProperty("readback.fail","true");
        ScreenLines rain=new ScreenLines();rain.line(5,5,6,15,1,0x99d49f,false);
        for(int n=0;n<5;n++) {
            g.beginFrameStats();queue(g,sky);
            check(g.renderGameFrameImage(null,false,false,null,0,rain),"native weather failed");
            check(g.isM3GActive() && g.getM3GCpuBarriers()==0 && g.getM3GDrawCalls()==2,"certified sky retained B or extra draw");
        }
        System.clearProperty("readback.fail");
        check(Image.getRGBCalls==reads && Graphics.drawRGBCalls==copies,"hidden sky readback/upload");
        check(Image.creates==creates+1,"allocated a second native framebuffer");
        int pixel=g.getDisplay()[0];check(Image.getRGBCalls==reads+1 && pixel!=0,"pause snapshot stale");
        M3GTest.submit(g,new SkyFlash(),null,0);g.renderBackground();g.renderGameFrameImage(null,false,false,null,0,null);
        check(g.getDisplay()[0]==(0xff000000|SkyFlash.COLOR),"model base color leaked into lightning clear");
        frame(g,sky);check(g.getDisplay()[0]==0xff6699cc,"lightning color leaked into model clear");g.destroy();
        System.out.println("PASS closed opaque sky + rain / B:0 / no cold or warm getRGB/drawRGB / single Image / explicit snapshot");

        g=renderer();sky=cube(g);sky.x1=1;preserved(sky,g,"portal crop");
        for(int mode:new int[]{1,2,4,5,7,10}) {
            g=renderer();sky=cube(g);sky.mesh.getTexture().textures[0].drawmode=(byte)mode;preserved(sky,g,"material "+mode);
        }
        g=renderer();sky=cube(g);sky.mesh.getTexture().textures[0].rImg=M3GTest.texture(0).rImg;preserved(sky,g,"cutout");
        g=renderer();sky=cube(g);RenderObject[] faces=sky.mesh.getPolygons();RenderObject[] open=new RenderObject[5];System.arraycopy(faces,0,open,0,5);
        sky=new SkyMesh(new Mesh(sky.mesh.getVertices(),open,sky.mesh.getTexture()));sky.set(new Matrix(),0,0,g.width,g.height);preserved(sky,g,"open model");
        g=renderer();sky=cube(g);sky.matrix.setPosition(5000,0,0);preserved(sky,g,"camera outside model");
        System.out.println("PASS cropped / ALPHA / cutout / open / translated sky keeps previous pixels and honest B");

        g=renderer();sky=cube(g);check(sky.clearsBackground(g),"initial certificate missing");
        RawImage opaque=sky.mesh.getTexture().textures[0].rImg;
        sky.mesh.getTexture().textures[0].rImg=M3GTest.texture(0).rImg;check(!sky.clearsBackground(g),"RawImage replacement retained opacity");
        sky.mesh.getTexture().textures[0].rImg=opaque;check(sky.clearsBackground(g),"restored opaque image not recognized");
        int[] old=opaque.img;opaque.img=new int[old.length];check(!sky.clearsBackground(g),"pixel-array replacement retained opacity");
        opaque.img=old;check(sky.clearsBackground(g),"restored pixel array not recognized");
        Vertex[] vertices=sky.mesh.getVertices();for(Vertex v:vertices) v.x+=10000;
        check(!sky.clearsBackground(g),"coordinate mutation retained enclosure");for(Vertex v:vertices) v.x-=10000;
        check(sky.clearsBackground(g),"restored geometry not recognized");
        Polygon4V face=(Polygon4V)sky.mesh.getPolygons()[0];Vertex previous=face.a;face.a=face.b;
        check(!sky.clearsBackground(g),"topology mutation retained certificate");face.a=previous;
        check(sky.clearsBackground(g),"restored topology not recognized");
        g.updateFov(179);check(!sky.clearsBackground(g),"extreme FOV ignored near viewport/precision");g.destroy();
        g=renderer();sky=cube(g);Vertex[] original=sky.mesh.getVertices(),extended=new Vertex[original.length+1];
        System.arraycopy(original,0,extended,0,original.length);extended[original.length]=new Vertex(0,0,0);
        sky=new SkyMesh(new Mesh(extended,sky.mesh.getPolygons(),sky.mesh.getTexture()));sky.set(new Matrix(),0,0,g.width,g.height);
        check(sky.clearsBackground(g),"unused near vertex rejected valid enclosure");
        extended[original.length].z=32700;check(!sky.clearsBackground(g),"unused far vertex ignored global short-scale precision");g.destroy();
        System.out.println("PASS coverage invalidation / RawImage+pixels / coordinates+topology / extreme FOV conservative rejection");

        for(int failure=0;failure<3;failure++) {
            g=renderer();sky=cube(g);Texture red=M3GTest.texture(0xffff0000);
            M3GTest.submit(g,M3GTest.triangle(),red,0);g.render(); // Warm the later world's texture.
            frame(g,sky); // Pending prior Image, no CPU snapshot.
            DirectX7 cpu=renderer();SkyMesh cpuSky=cube(cpu);queue(cpu,cpuSky);
            if(failure==1) M3GTest.submit(cpu,M3GTest.triangle(),red,0);
            cpu.renderSoftware();
            queue(g,sky);
            if(failure==0) System.setProperty("clear.fail","true");
            else if(failure==1) {M3GTest.submit(g,M3GTest.triangle(),red,0);Graphics3D.failAt=Graphics3D.calls+2;}
            else System.setProperty("m3g.oom","true");
            int before=Image.getRGBCalls;
            g.renderGameFrameImage(null,false,false,null,0,null);
            Graphics3D.failAt=0;System.clearProperty("clear.fail");System.clearProperty("m3g.oom");
            check(!g.isM3GActive() && Graphics3D.target==null,"clear/world/OOM failure not latched/released");
            check(Image.getRGBCalls==before && Arrays.equals(g.getDisplay(),cpu.display),"atomic recovery depended on discarded prior Image, failure="+failure+" reads="+(Image.getRGBCalls-before)+" pixelsEqual="+Arrays.equals(g.display,cpu.display));
            g.destroy();cpu.destroy();
        }
        System.out.println("PASS native clear failure / later world failure / OOM / full selected-M3G CPU replay without old Image");
    }
}
