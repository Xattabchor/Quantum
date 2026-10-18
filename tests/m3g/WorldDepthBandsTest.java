package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.Graphics3D;

/** Actual Kordon near/far quad regression; API/geometry contracts, not GPU pixels. */
public final class WorldDepthBandsTest {
    private static void check(boolean ok,String why){M3GTest.check(ok,why);}
    private static final short[] UV={0,0,256,0,256,256,0,256};
    private static byte[] colors() {byte[] c=new byte[16];Arrays.fill(c,(byte)255);return c;}
    private static Vertex[] source(DirectX7 g) {
        int[][] xyz={{-3915,-1071,2352},{-3069,-735,-3390},{2463,-381,-3246},{393,-723,1986}};
        Vertex[] v=new Vertex[4];
        for(int i=0;i<4;i++) {v[i]=new Vertex(xyz[i][0],xyz[i][1],xyz[i][2]);v[i].sx=v[i].x;v[i].sy=v[i].y;v[i].rz=v[i].z;v[i].project(g);}
        return v;
    }
    private static float x(WorldClipGeometry c,int i){return 120+c.xyz[i*3]/(float)-c.xyz[i*3+2];}
    private static float y(WorldClipGeometry c,int i){return 160-c.xyz[i*3+1]/(float)-c.xyz[i*3+2];}
    private static float cross(float ax,float ay,float bx,float by,float px,float py){return (bx-ax)*(py-ay)-(by-ay)*(px-ax);}
    private static boolean contains(WorldClipGeometry c,float px,float py) {
        for(int t=0;t<c.triangles;t++) {
            int i=t*3;float a=cross(x(c,i),y(c,i),x(c,i+1),y(c,i+1),px,py);
            float b=cross(x(c,i+1),y(c,i+1),x(c,i+2),y(c,i+2),px,py);
            float d=cross(x(c,i+2),y(c,i+2),x(c,i),y(c,i),px,py);
            if((a>=0 && b>=0 && d>=0)||(a<=0 && b<=0 && d<=0))return true;
        }
        return false;
    }
    private static boolean nearEdge(WorldClipGeometry c,float px,float py) {
        for(int t=0;t<c.triangles;t++)for(int k=0;k<3;k++) {
            int a=t*3+k,b=t*3+(k+1)%3;float dx=x(c,b)-x(c,a),dy=y(c,b)-y(c,a);
            if(Math.abs(cross(x(c,a),y(c,a),x(c,b),y(c,b),px,py))<=1.5*Math.sqrt(dx*dx+dy*dy))return true;
        }
        return false;
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.fogQ=2;Main.m3gPerspective=true;Main.mipMapping=false;
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);Vertex[] source=source(g);
        byte[] colors=colors();short[] originalUV=UV.clone();byte[] originalColors=colors.clone();
        int recovered=0;
        for(boolean alternate:new boolean[]{false,true})for(boolean fog:new boolean[]{false,true})for(boolean correction:new boolean[]{false,true}) {
            WorldClipGeometry legacy=new WorldClipGeometry(),split=new WorldClipGeometry(),reference=new WorldClipGeometry();
            boolean old=legacy.prepare(g,source,4,UV,colors,150,true,fog,alternate,correction,false);
            check(split.prepare(g,source,4,UV,colors,150,true,fog,alternate,correction,true),"bounded near/far quad not recovered");
            if(!old)recovered++;
            check(reference.prepare(g,source,4,UV,colors,150,false,false,alternate,correction),"affine silhouette reference failed");
            check(split.triangles>0,"recovery silently discarded visible quad");
            for(int n=0;n<split.triangles*3;n++) {
                check(split.xyz[n*3+2]<0 && x(split,n)>=0 && x(split,n)<=240 && y(split,n)>=0 && y(split,n)<=320,"invalid clipped endpoint");
                check((split.rgba[n*4]&255)==255 && (split.rgba[n*4+3]&255)==255,"uniform vertex color/alpha changed");
                check(split.uv[n*2]>=0 && split.uv[n*2]<=256 && split.uv[n*2+1]>=0 && split.uv[n*2+1]<=256,"UV left source bounds");
            }
            // Newly clipped vertices use the existing integer screen lattice.
            // Permit its boundary rounding, but not interior holes/lost regions.
            for(int py=0;py<320;py++)for(int px=0;px<240;px++) {
                boolean expected=contains(reference,px+0.5f,py+0.5f),actual=contains(split,px+0.5f,py+0.5f);
                check(expected==actual || nearEdge(reference,px+0.5f,py+0.5f),"interior coverage changed away from boundary");
            }
            short[] storage=split.xyz;float[] scales=split.scales;
            check(split.prepare(g,source,4,UV,colors,150,true,fog,alternate,correction,true) && split.xyz==storage && split.scales==scales,"warm recovery replaced reusable buffers");
        }
        check(recovered>0,"regression never exercised old precision rejection");
        check(Arrays.equals(UV,originalUV) && Arrays.equals(colors,originalColors),"source UV/colors mutated");g.destroy();
        System.out.println("PASS actual Kordon near/far quad / both diagonals / Fog and affine attributes / bounds + interior coverage / reusable storage");
        for(int mode:new int[]{0,3,6,9,13}) {
            g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);source=source(g);DirectX7.setFogDist(16000);
            Texture texture=M3GTest.texture(0xff446688);texture.drawmode=(byte)mode;
            Polygon4V p=mode==13?new ColorLightedPolygon4V(source[0],source[1],source[2],source[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255)
                :new Polygon4V(source[0],source[1],source[2],source[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            for(int frame=0;frame<3;frame++) {
                Graphics3D.history.clear();g.beginFrameStats();M3GTest.submit(g,new SolidSky(0),null,0);g.renderBackground();M3GTest.submit(g,p,texture,0);
                g.renderGameFrameImage(null,false,false,null,0,null);
                check(g.isM3GActive(),"native recovery failed mode="+mode);
                if(frame>0)check(g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0,"recovery crossed CPU/upload on warm frame");
                boolean world=false;
                for(Graphics3D.Draw d:Graphics3D.history)if(d.cull==javax.microedition.m3g.PolygonMode.CULL_BACK) {
                    world=true;check(d.fog==(mode==3||mode==6),"recovery changed Fog/material semantics");check(!d.lit,"recovery added dynamic lighting");
                }
                check(world,"recovered world disappeared");
            }
            g.destroy();
        }
        System.out.println("PASS native depth-band modes 0/3/6/9/13 / warm B0 T0 / material Fog + baked colors contract");
    }
}
