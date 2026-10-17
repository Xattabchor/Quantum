package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import javax.microedition.m3g.Graphics3D;

/** Native sky dispatch, homogeneous coverage and failure contracts, not GPU screenshots. */
public final class SkyTest {
    private static void check(boolean ok,String message) { M3GTest.check(ok,message); }
    static SkyMesh cube() {
        Vertex[] v = new Vertex[8];
        for(int i=0;i<8;i++) v[i]=new Vertex((i&1)==0?-1000:1000,(i&2)==0?-1000:1000,(i&4)==0?-1000:1000);
        int[][] faces={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
        RenderObject[] p = new RenderObject[6];
        for(int i=0;i<6;i++) {
            int[] f=faces[i];
            p[i]=new Polygon4V(v[f[0]],v[f[1]],v[f[2]],v[f[3]],
                    (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        }
        MultyTexture t=new MultyTexture();t.textures=new Texture[]{M3GTest.texture(0xff6699cc)};
        return new SkyMesh(new Mesh(v,p,t));
    }
    private static void submit(DirectX7 g,SkyMesh sky,Matrix transform,int left,int top,int right,int bottom) {
        sky.set(transform,left,top,right,bottom);M3GTest.submit(g,sky,null,0);g.render();
    }
    private static float[] screen(Graphics3D.Draw draw,int width,int height) {
        float[] p=new float[6];
        for(int i=0;i<3;i++) {
            float w=-draw.positions[i*3+2];
            check(w>0,"native sky vertex behind homogeneous camera");
            p[i*2]=(draw.positions[i*3]/w-draw.projection[2]+1)*width/2;
            p[i*2+1]=(1-draw.positions[i*3+1]/w+draw.projection[6])*height/2;
            check(!draw.fog,"level fog leaked into sky");
        }
        return p;
    }
    private static double cross(float ax,float ay,float bx,float by,float px,float py) {
        return (double)(bx-ax)*(py-ay)-(double)(by-ay)*(px-ax);
    }
    private static boolean contains(float[] v,float x,float y) {
        double a=cross(v[0],v[1],v[2],v[3],x,y),b=cross(v[2],v[3],v[4],v[5],x,y),c=cross(v[4],v[5],v[0],v[1],x,y);
        if(Math.abs(cross(v[0],v[1],v[2],v[3],v[4],v[5]))<0.00001) return false;
        return (a>=-0.00001 && b>=-0.00001 && c>=-0.00001)||(a<=0.00001 && b<=0.00001 && c<=0.00001);
    }
    private static void coverage(int first,int width,int height,int left,int top,int right,int bottom) {
        int count=Graphics3D.history.size()-first;
        check(count>0,"no native sky triangles");
        float[][] triangles=new float[count][];
        for(int i=0;i<count;i++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(first+i);
            check(d.projection[0]==1 && d.projection[5]==1,"ordinary projected polygon camera used for sky");
            triangles[i]=screen(d,width,height);
            for(int j=0;j<3;j++) {
                check(triangles[i][2*j]>=left-0.1 && triangles[i][2*j]<=right+0.1,"sky viewport X");
                check(triangles[i][2*j+1]>=top-0.1 && triangles[i][2*j+1]<=bottom+0.1,"sky viewport Y");
            }
        }
        // Geometric coverage only: these doubles do NOT rasterize triangles.
        for(int y=top;y<bottom;y+=3) for(int x=left;x<right;x+=3) {
            boolean covered=false;
            for(float[] t:triangles) if(contains(t,x+0.37f,y+0.43f)) {covered=true;break;}
            check(covered,"sky hole at "+x+","+y);
        }
    }
    public static void main(String[] args) throws Exception {
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        SkyMesh sky=cube();Matrix rx=new Matrix(),ry=new Matrix(),combined=new Matrix();
        int uploads=Graphics3D.uploads;
        for(int yaw=0;yaw<360;yaw+=15) for(int pitch:new int[]{-90,-65,-20,0,20,65,90}) {
            rx.setRotX(pitch);ry.setRotY(yaw);combined.mul(rx,ry);
            int first=Graphics3D.history.size();
            submit(g,sky,combined,0,0,g.width,g.height);
            check(g.isM3GActive() && g.getRendererError()==null,"sky silently fell back");
            coverage(first,g.width,g.height,0,0,g.width,g.height);
        }
        check(Graphics3D.uploads==uploads+1,"sky failed to reuse level texture cache");
        int first=Graphics3D.history.size();submit(g,sky,combined,37,51,194,278);
        coverage(first,g.width,g.height,37,51,194,278);
        int calls=Graphics3D.calls;submit(g,sky,combined,5,5,5,20);
        check(Graphics3D.calls==calls,"empty sky viewport drew triangles");
        System.out.println("PASS native cube sky / 168 camera poses / pole crossings / viewport / shared cache");

        // Distinct focal lengths, odd framebuffer and changed projection center.
        g.resize(241,319);g.updateFov(105);g.centreX=113;g.centreY=166;
        first=Graphics3D.history.size();submit(g,sky,combined,0,0,g.width,g.height);
        coverage(first,g.width,g.height,0,0,g.width,g.height);
        // Sky and level in the SAME queue must not share the sky projection.
        first=Graphics3D.history.size();sky.sz=-1000;sky.set(combined,0,0,g.width,g.height);
        M3GTest.submit(g,sky,null,-1000);M3GTest.submit(g,M3GTest.triangle(),M3GTest.texture(0xff00aa00),0);g.render();
        Graphics3D.Draw last=Graphics3D.history.lastElement();
        check(last.projection[0]==2.0f/g.width,"sky camera leaked to world");
        calls=Graphics3D.calls;g.render();check(Graphics3D.calls==calls,"sky queue retained entries");
        sky.mesh.getTexture().textures[0].rImg=M3GTest.texture(0xff998877).rImg;
        uploads=Graphics3D.uploads;submit(g,sky,combined,0,0,g.width,g.height);
        check(Graphics3D.uploads==uploads+1,"sky animation/RawImage replacement not uploaded");
        g.destroy();
        System.out.println("PASS sky FOV / aspect / center / level camera restoration / animation / queue cleanup");

        // Exercise the actual Skybox entry point without external game assets.
        code.Gameplay.Map.Skybox background=new code.Gameplay.Map.Skybox();
        java.lang.reflect.Field field=background.getClass().getDeclaredField("mesh");field.setAccessible(true);
        sky=cube();field.set(background,sky.mesh);
        field=background.getClass().getDeclaredField("matrix");field.setAccessible(true);field.set(background,new Matrix());
        background.skyboxAlways=true;
        g=new DirectX7(64,80);g.setRenderer(DirectX7.M3G);
        Matrix cameraPosition=new Matrix();cameraPosition.setPosition(5000,-3000,9000);g.setCamera(cameraPosition);
        first=Graphics3D.history.size();background.render(null,g);g.render();
        coverage(first,g.width,g.height,0,0,g.width,g.height);
        check(g.isM3GActive(),"Skybox did not enqueue native mesh");g.destroy();
        // Software selection still follows the original projected mesh path.
        g=new DirectX7(32,32);DirectX7 baseline=new DirectX7(32,32);
        calls=Graphics3D.calls;background.render(null,g);g.render();
        sky.set(new Matrix(),0,0,32,32);M3GTest.submit(baseline,sky,null,0);baseline.render();
        check(java.util.Arrays.equals(g.display,baseline.display),"original Software sky output changed");
        check(Graphics3D.calls==calls,"Software sky touched M3G");g.destroy();baseline.destroy();
        System.out.println("PASS real Skybox dispatch / camera translation cancellation / original Software output");

        g=M3GTest.renderer();sky=cube();
        for(Vertex v:sky.mesh.getVertices()) {v.x*=200;v.y*=200;v.z*=200;}
        first=Graphics3D.history.size();submit(g,sky,new Matrix(),0,0,32,32);
        coverage(first,32,32,0,0,32,32);
        check(g.getRendererError()==null,"large original sky model silently fell back");g.destroy();
        System.out.println("PASS large sky models / visible-weight preflight / no blanket size fallback");

        // Raw view-space near clipping: generated on-plane UV must be interpolated,
        // and a behind-camera vertex must not send an already projected triangle.
        g=new DirectX7(240,320);SkyGeometry clip=new SkyGeometry();
        Vertex a=M3GTest.vertex(-10,0,-10),b=M3GTest.vertex(10,0,10),c=M3GTest.vertex(0,10,-10);
        clip.clip(g,a,0,0,b,256,0,c,0,256,0,0,240,320);
        check(clip.count==4,"one behind-camera vertex should produce a quad");
        boolean uv=false;
        for(int i=0;i<clip.count;i++) {
            int p=i*5;check(clip.polygon[p+2]>=g.distY,"near plane violated");
            if(Math.abs(clip.polygon[p+2]-g.distY)<0.001 && clip.polygon[p+3]==128) uv=true;
        }
        check(uv,"near-plane UV interpolation missing");
        a.rz=b.rz=c.rz=10;clip.clip(g,a,0,0,b,256,0,c,0,256,0,0,240,320);
        check(clip.count==0,"fully behind-camera triangle not rejected");
        g.destroy();
        System.out.println("PASS view-space near clipping / UV interpolation / all-behind rejection");

        g=M3GTest.renderer();sky=cube();
        sky.mesh.getTexture().textures[0]=M3GTest.texture(0x806699cc);
        sky.mesh.getTexture().textures[0].drawmode=4;
        submit(g,sky,new Matrix(),0,0,32,32);
        check(Graphics3D.history.lastElement().blend==javax.microedition.m3g.CompositingMode.ALPHA
                && Graphics3D.history.lastElement().textureAlpha==128,"sky lost fractional alpha");
        sky.mesh.getTexture().textures[0]=M3GTest.texture(0);g.clearDisplay(0xff123456);
        submit(g,sky,new Matrix(),0,0,32,32);
        check(g.display[0]==0xff123456,"sky filled transparent texture holes");g.destroy();
        System.out.println("PASS sky alpha blending / cutout texture holes");

        // Failure after an actual native sky draw must restore the pre-pass pixels
        // and replay the whole original sky, not leave half native/half CPU faces.
        g=M3GTest.renderer();DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        Matrix identity=new Matrix();sky=cube();sky.set(identity,0,0,32,32);
        M3GTest.submit(cpu,sky,null,0);cpu.render();
        Graphics3D.failAt=Graphics3D.calls+2;
        submit(g,sky,identity,0,0,32,32);Graphics3D.failAt=0;
        check(!g.isM3GActive() && g.getRendererError()!=null,"sky native failure did not disable backend");
        check(java.util.Arrays.equals(g.display,cpu.display),"failed sky was not fully replayed");
        calls=Graphics3D.calls;submit(g,sky,identity,0,0,32,32);
        check(Graphics3D.calls==calls,"failed sky retried native every frame");g.destroy();cpu.destroy();
        System.out.println("PASS partial native sky failure / whole-sky rollback / no per-face CPU fallback");

        // Unsupported material in the LAST face must preflight before ANY draw.
        g=M3GTest.renderer();sky=cube();Texture unsupported=M3GTest.texture(0xff112233);unsupported.drawmode=3;
        sky.mesh.getTexture().textures=new Texture[]{sky.mesh.getTexture().textures[0],unsupported};
        ((Polygon4V)sky.mesh.getPolygons()[5]).tex=1;
        calls=Graphics3D.calls;submit(g,sky,identity,0,0,32,32);
        check(Graphics3D.calls==calls && g.getRendererError()==null,"unsupported sky was partially native");g.destroy();
        System.out.println("PASS unsupported sky material whole-mesh fallback");
    }
}
