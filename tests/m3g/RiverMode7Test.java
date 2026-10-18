package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.LightedPolygon4V;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Actual Level41 first river quad, camera at (-99642,-2798,22200).
 * Recorded geometry/pixel coverage contracts, NOT real GPU raster or timing. */
public final class RiverMode7Test {
    private static void check(boolean ok,String why){M3GTest.check(ok,why);}
    private static Vertex[] source(DirectX7 g) {
        Vertex[] v={new Vertex(0,-10,0),new Vertex(-7710,-118,0),new Vertex(-7716,-118,-4920),new Vertex(-6,-16,-4920)};
        g.transformAndProjectVertices(v,new Matrix());return v;
    }
    private static int x(WorldClipGeometry c,int i){return 120+c.xyz[i*3]/-c.xyz[i*3+2];}
    private static int y(WorldClipGeometry c,int i){return 160-c.xyz[i*3+1]/-c.xyz[i*3+2];}
    private static long edge(int ax,int ay,int bx,int by,int px,int py){return (long)(bx-ax)*(py-ay)-(long)(by-ay)*(px-ax);}
    private static boolean inside(int ax,int ay,int bx,int by,int px,int py) {
        long e=edge(ax,ay,bx,by,px,py);return e>0 || e==0 && (by<ay || by==ay && bx>ax);
    }
    private static void coverage(WorldClipGeometry c) {
        int[] pixels=new int[240*320];
        for(int t=0;t<c.triangles;t++) {
            int a=t*3,b=a+1,d=a+2;
            int ax=x(c,a)*2,ay=y(c,a)*2,bx=x(c,b)*2,by=y(c,b)*2,dx=x(c,d)*2,dy=y(c,d)*2;
            if(edge(ax,ay,bx,by,dx,dy)<0){int n=bx;bx=dx;dx=n;n=by;by=dy;dy=n;}
            for(int py=0;py<320;py++)for(int px=0;px<240;px++) {
                int sx=px*2+1,sy=py*2+1;
                if(inside(ax,ay,bx,by,sx,sy)&&inside(bx,by,dx,dy,sx,sy)&&inside(dx,dy,ax,ay,sx,sy))
                    check(++pixels[py*240+px]==1,"depth bands multiply a river pixel twice");
            }
        }
        int covered=0;for(int n:pixels)if(n==1)covered++;
        check(covered>100,"river silently discarded");
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.mipMapping=false;Main.fogQ=2;
        DirectX7 g=new DirectX7(240,320);g.requireM3G();Vertex[] v=source(g);
        short[] uv={0,0,256,0,256,256,0,256};byte[] rgba=new byte[16];Arrays.fill(rgba,(byte)255);
        WorldClipGeometry clip=new WorldClipGeometry();
        check(ProjectedGeometry.pack(v,4,120,160,150,true,new short[12],new float[12])==0,"real river fixture no longer rejects simple pack");
        check(!clip.prepare(g,v,4,uv,rgba,150,true,false,false,true,false),"fixture misses old non-band failure");
        check(clip.prepare(g,v,4,uv,rgba,150,true,false,false,true,true),"river depth bands fail");coverage(clip);g.destroy();
        for(boolean hw:new boolean[]{true,false})for(int quality:new int[]{4,0}) {
            Main.m3gPerspective=hw;Main.persQ=quality;
            g=new DirectX7(240,320);g.requireM3G();v=source(g);
            LightedPolygon4V p=new LightedPolygon4V(v[0],v[1],v[2],v[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            Texture river=null;Texture[] frames=new Texture[4];int[][] originals=new int[4][];
            for(int i=0;i<4;i++) {
                int[] pixels=new int[32*32];Arrays.fill(pixels,0xff224466+i*0x112211);originals[i]=pixels.clone();
                frames[i]=new Texture(new RawImage(pixels,32,32),true,null);
            }
            river=new Texture(frames[0].rImg,true,null);river.animMIP=frames;river.animation_speed=8;river.drawmode=7;
            for(int frame=0;frame<12;frame++) {
                // Exactly the image/mip assignment performed by updateAnimation,
                // without depending on wall clock or sleeping to hit all four frames.
                river.rImg=frames[frame%4].rImg;river.mip=frames[frame%4].mip;
                g.beginFrameStats();M3GTest.submit(g,new SolidSky(0xabcdef),null,0);g.renderBackground();
                int first=Graphics3D.history.size();M3GTest.submit(g,p,river,0);
                g.renderGameFrameImage(null,false,false,null,0,null);
                check(g.getRendererError()==null && g.isM3GRequired(),"river replayed CPU/failed");
                if(frame>=4)check(g.getM3GTextureUploads()==0 && g.getM3GCpuBarriers()==0,"warm animated frame reuploaded");
                int draws=0;
                for(int n=first;n<Graphics3D.history.size();n++) {
                    Graphics3D.Draw d=Graphics3D.history.elementAt(n);if(!d.textured)continue;draws++;
                    check(d.blend==CompositingMode.MODULATE && d.function==Texture2D.FUNC_MODULATE && !d.fog && !d.lit && d.perspective==hw,"river mode 7 material/state changed");
                    check(d.source.image.width==32 && d.source.image.height==32,"river texture resized");
                    byte[] bytes=d.source.image.pixels;int rgb=originals[frame%4][0];
                    check((bytes[0]&255)==((rgb>>16)&255)&&(bytes[1]&255)==((rgb>>8)&255)&&(bytes[2]&255)==(rgb&255),"wrong animation frame");
                }
                check(draws>0,"river disappeared");
            }
            for(int i=0;i<4;i++)check(Arrays.equals(originals[i],frames[i].rImg.img),"river frame pixels mutated");
            check(river.drawmode==7 && river.animMIP.length==4 && river.animation_speed==8,"animation/material metadata changed");g.destroy();
        }
        System.out.println("PASS real Level41 LightedPolygon4V river mode7 / old precision rejection / non-overlapping depth-band pixel coverage / 4 animated frames / HW ON+OFF / affine+perspective / MODULATE / warm T0 B0 / no CPU replay");
    }
}
