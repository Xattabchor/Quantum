package code.Rendering;

import code.utils.Main;
import java.util.Arrays;
import java.util.Random;
import javax.microedition.m3g.Graphics3D;

/** Independent geometry/error/reference checks, NOT a GPU image/FPS benchmark. */
public final class FogPackingTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static float old(Vertex[] v,int cx,int cy,int move,short[] p,float[] work) {
        return ProjectedGeometry.pack(v,v.length,cx,cy,move,true,p,work);
    }
    private static void verify(Vertex[] v,int cx,int cy,int move,short[] p,float scale) {
        for(int i=0;i<v.length;i++) {
            int q=-p[i*3+2];float expected=1.0f-(float)v[i].rz/move;
            check(q>0 && (long)p[i*3]==((long)v[i].sx-cx)*q && (long)p[i*3+1]==((long)cy-v[i].sy)*q,"fog screen endpoint moved");
            check(Math.abs(q*scale-expected)<=expected/128,"fog weight/depth exceeded original 1/128 bound");
        }
    }
    public static void main(String[] args) {
        Random random=new Random(38184);short[] p=new short[12],before=new short[12];float[] work=new float[12];
        int accepted=0,oldAccepted=0,identicalFallback=0;
        for(int n=0;n<50000;n++) {
            int cx=n%4==0?16:160,cy=n%4==0?16:120;
            int move=new int[]{1,150,200,1000,Integer.MAX_VALUE}[n%5];
            Vertex[] v=new Vertex[3+n%2];
            for(int i=0;i<v.length;i++) {
                int z=n%13==0?Integer.MIN_VALUE:-random.nextInt(30000);
                v[i]=M3GTest.vertex(random.nextInt(cx*3+1)-cx/2,random.nextInt(cy*3+1)-cy/2,z);
            }
            float reference=old(v,cx,cy,move,before,work);
            float scale=ProjectedGeometry.packFog(v,v.length,cx,cy,move,p,work);
            if(reference>0) {oldAccepted++;check(scale>0,"new lattice introduced CPU fallback");}
            if(scale>0) {accepted++;verify(v,cx,cy,move,p,scale);}
            if(scale==reference && reference>0 && Arrays.equals(p,before)) identicalFallback++;
        }
        check(accepted>10000 && oldAccepted>10000 && identicalFallback>100,"exercise shared lattice and exact legacy fallback");
        // Same vertices, changing depth/screen bounds: old unique scales, new common scale.
        Vertex[] a={M3GTest.vertex(2,2,-100),M3GTest.vertex(22,2,-200),M3GTest.vertex(2,22,-150)};
        Vertex[] b={a[0],a[1],M3GTest.vertex(0,24,-168)};
        float first=ProjectedGeometry.packFog(a,3,16,16,200,p,work);
        float second=ProjectedGeometry.packFog(b,3,16,16,200,before,work);
        check(first==second && first>0 && old(a,16,16,200,p,work)!=old(b,16,16,200,before,work),"legacy unique scales not grouped");
        ProjectedGeometry.packFog(a,3,16,16,200,p,work);ProjectedGeometry.packFog(b,3,16,16,200,before,work);
        for(int i=0;i<2;i++) for(int c=0;c<3;c++) check(p[i*3+c]==before[i*3+c],"shared vertex differs on common lattice");
        // Changed bins still share exact screen edges, never independently rounded XYZ.
        b[2].sx=80;b[2].sy=80;b[2].rz=-300;
        second=ProjectedGeometry.packFog(b,3,16,16,200,before,work);check(second>0 && second!=first,"different lattice-bin fixture");
        for(int i=0;i<2;i++) {
            check((long)p[i*3]*before[i*3+2]==(long)before[i*3]*p[i*3+2],"cross-bin shared X crack");
            check((long)p[i*3+1]*before[i*3+2]==(long)before[i*3+1]*p[i*3+2],"cross-bin shared Y crack");
        }
        a[0].rz=1;check(ProjectedGeometry.packFog(a,3,16,16,200,p,work)==0,"behind-plane accepted");a[0].rz=-100;
        a[0].sx=Integer.MIN_VALUE;check(ProjectedGeometry.packFog(a,3,Integer.MAX_VALUE,16,200,p,work)==0,"offset overflow accepted");
        check(ProjectedGeometry.packFog(b,3,16,16,0,p,work)==0,"invalid move accepted");
        System.out.println("PASS 50000 Fog packing cases / no new legacy CPU fallbacks / exact endpoints + shared edges / <=1/128 original depth error / common lattice + exact legacy fallback / extreme inputs");

        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        for(boolean hardware:new boolean[]{false,true}) for(int mode:new int[]{1,3,6,9,13}) {
            Main.m3gPerspective=hardware;DirectX7 g=M3GTest.renderer();DirectX7.fDist=1000;
            Texture t=M3GTest.texture(0xff8090a0);t.drawmode=(byte)mode;
            int start=Graphics3D.calls,oldRuns=0;float lastOld=0;
            for(int i=0;i<100;i++) {
                code.Rendering.Meshes.Polygon3V face=M3GTest.triangle();face.a.rz-=i%11;face.b.rz-=i%17;face.c.rz-=i%23;
                face.a.sx+=i%3;face.b.sy+=i%4;
                float previousPack=old(new Vertex[]{face.a,face.b,face.c},16,16,TexturingPers.move,before,work);
                check(previousPack>0,"legacy count fixture must be natively representable");
                if(i==0 || previousPack!=lastOld) oldRuns++;lastOld=previousPack;
                M3GTest.submit(g,face,t,i);
            }
            check(oldRuns==100,"legacy scale fixture no longer exercises 100 separate runs");
            g.render();check(g.isM3GActive() && Graphics3D.calls==start+2,"100 varying-depth fog faces not reduced to 2 packets mode "+mode);
            g.destroy();
        }
        Main.m3gPerspective=true;
        System.out.println("PASS 100 varying-depth/screen-bound Fog triangles -> 2 draws for 1/3/6/9/13 / HW ON+OFF (not FPS)");
    }
}
