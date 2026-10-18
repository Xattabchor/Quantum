package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Graphics;

/** Native clipping geometry/dispatch contracts, not a GPU visual/FPS benchmark. */
public final class WorldClipTest {
    private static void check(boolean value,String why) {M3GTest.check(value,why);}
    private static DirectX7 renderer() {DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);return g;}
    private static Vertex[] crossing(DirectX7 g) {
        Vertex[] v={new Vertex(-20,-20,-100),new Vertex(20,-20,100),new Vertex(0,20,-100)};
        g.transformAndProjectVertices(v,new Matrix());return v;
    }
    private static final short[] UV={0,0,256,0,0,256};
    private static final byte[] RGB={(byte)255,0,0,(byte)255,0,(byte)255,0,(byte)255,0,0,(byte)255,(byte)255};
    private static float screenX(DirectX7 g,WorldClipGeometry clip,int n) {
        return g.centreX+clip.xyz[n*3]/(float)-clip.xyz[n*3+2];
    }
    private static float screenY(DirectX7 g,WorldClipGeometry clip,int n) {
        return g.centreY-clip.xyz[n*3+1]/(float)-clip.xyz[n*3+2];
    }
    private static void bounds(DirectX7 g,WorldClipGeometry clip) {
        for(int n=0;n<clip.triangles*3;n++) {
            float x=screenX(g,clip,n),y=screenY(g,clip,n);
            check(x>=0 && x<=g.width && y>=0 && y<=g.height,"clipped point outside framebuffer");
            check(clip.xyz[n*3+2]<0,"clipped point has nonpositive homogeneous W");
        }
    }
    public static void main(String[] args) {
        Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        DirectX7 g=renderer();Vertex[] source=crossing(g);WorldClipGeometry clip=new WorldClipGeometry();
        check(ProjectedGeometry.pack(source,3,g.centreX,g.centreY,150,true,new short[9],new float[9])==0,"fixture didn't reject original near pack");
        check(clip.prepare(g,source,3,UV,RGB,150,true,false,false,true) && clip.triangles==2,"crossing near plane did not produce two native triangles");bounds(g,clip);
        boolean midpoint=false;
        for(int n=0;n<clip.triangles*3;n++) if(screenX(g,clip,n)==120 && screenY(g,clip,n)==180) {
            check(clip.uv[n*2]==128 && clip.uv[n*2+1]==0,"near UV not interpolated");
            check((clip.rgba[n*4]&255)==128 && (clip.rgba[n*4+1]&255)==128 && (clip.rgba[n*4+2]&255)==0,"near RGB not interpolated");midpoint=true;
        }
        check(midpoint,"near intersection not found");
        // Reversing a shared edge must produce identical integer positions/UV/RGB.
        Vertex[] reverse={source[1],source[0],source[2]};short[] reverseUV={256,0,0,0,0,256};
        byte[] reverseRGB={0,(byte)255,0,(byte)255,(byte)255,0,0,(byte)255,0,0,(byte)255,(byte)255};
        WorldClipGeometry other=new WorldClipGeometry();check(other.prepare(g,reverse,3,reverseUV,reverseRGB,150,true,false,false,true),"reverse clip failed");
        for(int n=0;n<clip.triangles*3;n++) {
            boolean found=false;
            for(int k=0;k<other.triangles*3;k++) if(screenX(g,clip,n)==screenX(g,other,k) && screenY(g,clip,n)==screenY(g,other,k)
                    && clip.uv[n*2]==other.uv[k*2] && clip.uv[n*2+1]==other.uv[k*2+1]) {found=true;break;}
            check(found,"shared-edge endpoint depends on winding");
        }
        System.out.println("PASS world near-plane clipping / physical view provenance / canonical shared edges / UV + RGB interpolation");

        Vertex[] wide={new Vertex(-100000,-100,-100),new Vertex(100000,-100,-100),new Vertex(0,100,-100)};
        g.transformAndProjectVertices(wide,new Matrix());
        check(ProjectedGeometry.pack(wide,3,g.centreX,g.centreY,150,true,new short[9],new float[9])==0,"viewport fixture didn't reject original pack");
        check(clip.prepare(g,wide,3,UV,RGB,150,true,false,false,true) && clip.triangles>0,"viewport clipping did not recover offscreen overflow");bounds(g,clip);
        for(Vertex v:source) {v.sx=0;v.sy=0;v.rz=100;v.project(g);}
        check(clip.prepare(g,source,3,UV,RGB,150,true,false,false,true) && clip.triangles==0,"all-behind geometry not discarded");
        source=crossing(g);source[0].sx++;
        check(!clip.prepare(g,source,3,UV,RGB,150,true,false,false,true),"stale screen/view data accepted");
        check(!clip.prepare(g,new Vertex[]{M3GTest.vertex(10,10,-100),M3GTest.vertex(20,10,20),M3GTest.vertex(10,20,-100)},3,UV,RGB,150,true,false,false,true),"uncaptured synthetic vertices accepted");
        source=crossing(g);check(clip.prepare(g,source,3,UV,RGB,150,false,true,false,true),"affine UV with eye-space Fog did not clip");bounds(g,clip);
        Vertex[] varying={new Vertex(-1000,-20,-100),new Vertex(200,-20,-1000),new Vertex(50,40,-100)};
        g.transformAndProjectVertices(varying,new Matrix());
        int expected=(int)(256*(-varying[0].sx)/(float)(varying[1].sx-varying[0].sx)+0.5f);
        for(boolean fog:new boolean[]{false,true}) {
            check(clip.prepare(g,varying,3,UV,RGB,150,!fog,fog,false,false),"affine viewport interpolation failed");
            int max=0;for(int n=0;n<clip.triangles*3;n++) if(screenX(g,clip,n)==0) max=Math.max(max,clip.uv[n*2]);
            check(max==expected,"affine viewport UV used homogeneous instead of screen interpolation: "+max+" expected "+expected);
        }
        g.destroy();
        System.out.println("PASS world viewport clipping / overflow recovery / all-behind discard / stale+missing view fallback / affine native Fog");

        // Quad preflight: ABD can pack, BCD has too much depth range. The helper
        // must return false BEFORE any geometry/material submission takes place.
        g=renderer();Vertex[] quad={new Vertex(),new Vertex(),new Vertex(),new Vertex()};
        int[][] raw={{-10,-10,-100},{10,-10,-100},{10000000,0,-100000000},{-10,10,-100}};
        for(int n=0;n<4;n++) {quad[n].sx=raw[n][0];quad[n].sy=raw[n][1];quad[n].rz=raw[n][2];quad[n].project(g);}
        int calls=Graphics3D.calls;
        check(!clip.prepare(g,quad,4,new short[]{0,0,256,0,256,256,0,256},new byte[16],150,true,false,false,true),"unrepresentable weight range silently degraded");
        check(clip.triangles==1 && Graphics3D.calls==calls,"partial quad preflight submitted a native triangle");g.destroy();
        System.out.println("PASS whole-quad preflight / later triangle precision failure / no partial native submission");

        for(int mode:new int[]{0,1,2,3,4,5,6,7,9,10,13}) {
            g=renderer();DirectX7.setFogDist(1000);source=crossing(g);
            ColorLightedPolygon3V p=new ColorLightedPolygon3V(source[0],source[2],source[1],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
            p.ar=255;p.ag=0;p.ab=0;p.br=0;p.bg=255;p.bb=0;p.cr=0;p.cg=0;p.cb=255;
            Texture t=M3GTest.texture(mode==1 || mode==4?0x80557799:0xff557799);t.drawmode=(byte)mode;
            for(int frame=0;frame<3;frame++) {
                Main.persQ=frame==2?0:4;Main.m3gPerspective=frame!=1;
                g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();M3GTest.submit(g,p,t,0);
                int first=Graphics3D.history.size(),reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls;
                g.renderGameFrameImage(null,false,false,null,0,null);
                check(g.isM3GActive(),"clipped material failed: "+mode);
                if(frame>0) check(g.getM3GCpuBarriers()==0 && Image.getRGBCalls==reads && Graphics.drawRGBCalls==copies,"clipped material crossed CPU: "+mode);
                boolean textured=false;
                for(int n=first;n<Graphics3D.history.size();n++) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(n);if(!d.textured) continue;textured=true;
                    check(d.fog==(mode==3 || mode==6),"clip inherited wrong Fog");
                    if(!Main.m3gPerspective) check(!d.perspective,"HW OFF lost during clipping");
                    if((mode==3 || mode==6) && Main.persQ==0) check(!d.perspective,"affine Fog enabled perspective UV");
                    check(d.cull==javax.microedition.m3g.PolygonMode.CULL_BACK,"clipped world lost backface culling");
                }
                check(textured,"crossing geometry silently disappeared: "+mode);
            }
            check(p.ar==255 && p.bg==255 && p.cb==255,"clip mutated static RGB");g.destroy();
        }
        System.out.println("PASS native clipped materials 0/1/2/3/4/5/6/7/9/10/13 / warm B:0 / Fog+culling / static RGB preserved");

        Main.persQ=4;Main.m3gPerspective=true;
        g=renderer();source=crossing(g);Texture meshTexture=M3GTest.texture(0xff557799);
        Polygon3V meshFace=new Polygon3V(source[0],source[2],source[1],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture materials=new MultyTexture();materials.textures=new Texture[]{meshTexture};
        MeshImage image=new MeshImage(new Mesh(source,new RenderObject[]{meshFace},materials));
        for(int frame=0;frame<3;frame++) {
            Matrix transform=new Matrix();transform.setPosition(frame*3,frame*2,-10);image.setMatrix(transform);
            check(image.isVisible(0,0,g.width,g.height),"MeshImage fixture culled");
            g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();M3GTest.submit(g,image,null,0);
            g.renderGameFrameImage(null,false,false,null,0,null);
            check(meshFace.a.hasView(g) && meshFace.a.getViewX()==-20+frame*3,"MeshImage used stale/wrong view matrix");
            // The pre-existing MeshImage wrapper itself has one barrier; clipping must not add another.
            if(frame>0) check(g.getM3GCpuBarriers()==1 && g.getM3GDrawCalls()==2,"MeshImage clipped geometry fell back or disappeared B="+g.getM3GCpuBarriers()+" D="+g.getM3GDrawCalls()+" active="+g.isM3GActive());
        }
        g.destroy();System.out.println("PASS actual MeshImage dispatch / mesh projection overload / moving model matrix / clipped native batching");

        g=renderer();source=crossing(g);Texture t=M3GTest.texture(0xff557799);
        Polygon3V p=new Polygon3V(source[0],source[2],source[1],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();M3GTest.submit(g,p,t,0);
        DirectX7 cpu=renderer();M3GTest.submit(cpu,new SolidSky(0x123456),null,0);cpu.renderBackground();M3GTest.submit(cpu,p,t,0);cpu.renderSoftware();
        Graphics3D.failAt=Graphics3D.calls+2;g.renderGameFrameImage(null,false,false,null,0,null);Graphics3D.failAt=0;
        check(!g.isM3GActive() && Graphics3D.target==null,"clipped render failure not latched/released");
        check(Arrays.equals(g.getDisplay(),cpu.display),"clipped failure didn't replay whole original polygon");g.destroy();cpu.destroy();
        DirectX7 software=new DirectX7(240,320);Vertex[] input={new Vertex(-20,0,-100),new Vertex(20,0,100),new Vertex(0,20,-100)};
        software.transformAndProjectVertices(input,new Matrix());check(!input[0].hasView(software),"Software projection acquired M3G view capture");software.destroy();
        System.out.println("PASS clipped native failure / original whole-frame CPU replay / Software isolation");
    }
}
