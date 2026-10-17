package code.Rendering;

import code.Math.Matrix;
import java.util.Arrays;
import java.util.Random;

/** Exact pre-optimization normal and vertex stream references; not GPU timing. */
public final class NativePrepTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static short round(double v) {return (short)(int)(v+(v<0?-0.5:0.5));}
    private static void normal(RenderObject p,int count,short[] out,int lx,int ly,int lz,DirectX7 eye) {
        double x=p.nx,y=p.ny,z=p.nz;
        if(eye!=null) {
            Matrix c=eye.camera;
            x=(c.m00*(double)p.nx+c.m10*(double)p.ny+c.m20*(double)p.nz)/Matrix.FP;
            y=(c.m01*(double)p.nx+c.m11*(double)p.ny+c.m21*(double)p.nz)/Matrix.FP;
            z=(c.m02*(double)p.nx+c.m12*(double)p.ny+c.m22*(double)p.nz)/Matrix.FP;
        }
        double len=Math.sqrt(x*x+y*y+z*z);
        if(len==0) {
            x=-(double)lx;y=-(double)ly;z=-(double)lz;
            len=Math.sqrt(x*x+y*y+z*z);if(len==0) {z=1;len=1;}
        }
        short a=round(x*32767/len),b=round(y*32767/len),c=round(z*32767/len);
        for(int i=0;i<count;i++) {out[i*3]=a;out[i*3+1]=b;out[i*3+2]=c;}
    }
    private static void normals() {
        Random r=new Random(185);DirectX7 g=new DirectX7(240,320),other=new DirectX7(176,220);
        RenderObject p=M3GTest.triangle();short[] actual=new short[15],expected=new short[15];
        for(int trial=0;trial<14000;trial++) {
            int lx=r.nextInt(),ly=r.nextInt(),lz=r.nextInt();
            p.nx=r.nextInt();p.ny=r.nextInt();p.nz=r.nextInt();
            if(trial%3==0) p.nx=p.ny=p.nz=0;
            if(trial%7==0) lx=ly=lz=0;
            DirectX7 eye=trial%5==0?null:g;
            int count=trial%5;
            for(int changed=0;changed<18;changed++) {
                Arrays.fill(actual,(short)123);Arrays.fill(expected,(short)123);
                normal(p,count,expected,lx,ly,lz,eye);
                M3GMode5Lighting.packNormals(p,count,actual,lx,ly,lz,eye);
                check(Arrays.equals(actual,expected),"native normal/reference");
                // Repeat with a different count; the original loop must still match.
                normal(p,4,expected,lx,ly,lz,eye);
                M3GMode5Lighting.packNormals(p,4,actual,lx,ly,lz,eye);
                check(Arrays.equals(actual,expected),"native normal cache count/duplicate");
                int v=r.nextInt();Matrix c=g.camera;
                switch(changed) {
                    case 0:c.m00=v;break;case 1:c.m01=v;break;case 2:c.m02=v;break;
                    case 3:c.m10=v;break;case 4:c.m11=v;break;case 5:c.m12=v;break;
                    case 6:c.m20=v;break;case 7:c.m21=v;break;case 8:c.m22=v;break;
                    case 9:lx=~lx;break;case 10:ly=~ly;break;case 11:lz=~lz;break;
                    case 12:p.nx=~p.nx;break;case 13:p.ny=~p.ny;break;case 14:p.nz=~p.nz;break;
                    case 15:eye=other;break;case 16:eye=null;break;
                }
            }
        }
        // A nonzero normal can transform to zero: fallback is keyed by light.
        g.camera.set(0,0,0,0,0,0,0,0,0,0,0,0);
        p.nx=1;p.ny=2;p.nz=3;
        for(int axis=0;axis<4;axis++) {
            int x=axis==0?4096:0,y=axis==1?-4096:0,z=axis==2?4096:0;
            normal(p,4,expected,x,y,z,g);M3GMode5Lighting.packNormals(p,4,actual,x,y,z,g);
            check(Arrays.equals(actual,expected),"singular camera / zero light fallback");
        }
        g.destroy();other.destroy();
    }
    private static int reference(short[] p,short[] uv,byte[] rgba,short[] n,int[] order,
            short[] po,short[] uvo,byte[] co,short[] no,int offset,boolean lit) {
        for(int i=0;i<order.length;i++,offset++) {
            int v=order[i];
            System.arraycopy(p,v*3,po,offset*3,3);System.arraycopy(uv,v*2,uvo,offset*2,2);
            System.arraycopy(rgba,v*4,co,offset*4,4);if(lit) System.arraycopy(n,v*3,no,offset*3,3);
        }
        return offset;
    }
    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field f=M3GPolygonBatch.class.getDeclaredField(name);
        f.setAccessible(true);return f;
    }
    private static final class BatchProbe {
        final M3GPolygonBatch batch=new M3GPolygonBatch(null,null);
        final java.lang.reflect.Field size=field("size"),material=field("material");
        final javax.microedition.m3g.Material lit=new javax.microedition.m3g.Material();
        final java.lang.reflect.Method method=M3GPolygonBatch.class.getDeclaredMethod("append",
                short[].class,short[].class,byte[].class,short[].class,int[].class);
        BatchProbe() throws Exception {method.setAccessible(true);}
        int append(short[] p,short[] uv,byte[] c,short[] n,int[] order,int offset,boolean lighting) throws Exception {
            size.setInt(batch,offset);material.set(batch,lighting?lit:null);
            method.invoke(batch,p,uv,c,n,order);return size.getInt(batch);
        }
    }
    private static void vertices() throws Exception {
        Random r=new Random(192);
        short[] p=new short[12],uv=new short[8],n=new short[12];byte[] c=new byte[16];
        BatchProbe probe=new BatchProbe();
        short[] actualP=(short[])field("xyz").get(probe.batch),actualUV=(short[])field("uv").get(probe.batch),
                actualN=(short[])field("packedNormals").get(probe.batch);
        byte[] actualC=(byte[])field("rgba").get(probe.batch);
        short[] expectedP=new short[576],expectedUV=new short[384],expectedN=new short[576];byte[] expectedC=new byte[768];
        int[][] orders={{0,1,2},{0,1,3,1,2,3},{0,1,2,0,2,3},{2,0,1},{3},{},{3,3,0,1,2,0,3}};
        for(int trial=0;trial<12000;trial++) {
            for(int i=0;i<p.length;i++) {p[i]=(short)r.nextInt();n[i]=(short)r.nextInt();}
            for(int i=0;i<uv.length;i++) uv[i]=(short)r.nextInt();r.nextBytes(c);
            int[] order=trial%2==0?orders[trial%orders.length].clone():new int[r.nextInt(33)];
            if(trial%2!=0) for(int i=0;i<order.length;i++) order[i]=r.nextInt(4);
            int offset=r.nextInt(193-order.length);boolean lit=trial%3==0;
            Arrays.fill(actualP,(short)77);Arrays.fill(expectedP,(short)77);
            Arrays.fill(actualUV,(short)-111);Arrays.fill(expectedUV,(short)-111);
            Arrays.fill(actualN,(short)444);Arrays.fill(expectedN,(short)444);
            Arrays.fill(actualC,(byte)99);Arrays.fill(expectedC,(byte)99);
            short[] savedP=p.clone(),savedUV=uv.clone(),savedN=n.clone();byte[] savedC=c.clone();
            int count=reference(p,uv,c,n,order,expectedP,expectedUV,expectedC,expectedN,offset,lit);
            int actual=probe.append(p,uv,c,lit?n:null,order,offset,lit);
            check(count==actual && Arrays.equals(actualP,expectedP) && Arrays.equals(actualUV,expectedUV)
                    && Arrays.equals(actualC,expectedC) && Arrays.equals(actualN,expectedN),"exact ordered batch / both diagonals / prefix+suffix / lit+unlit");
            check(Arrays.equals(p,savedP) && Arrays.equals(uv,savedUV) && Arrays.equals(n,savedN) && Arrays.equals(c,savedC),"batch changed input arrays");
            // An order array may be edited in place; no identity-only cache.
            if(order.length>0) {
                order[0]=(order[0]+1)%4;
                reference(p,uv,c,n,order,expectedP,expectedUV,expectedC,expectedN,offset,lit);
                probe.append(p,uv,c,lit?n:null,order,offset,lit);
                check(Arrays.equals(actualP,expectedP) && Arrays.equals(actualUV,expectedUV)
                        && Arrays.equals(actualC,expectedC) && Arrays.equals(actualN,expectedN),"in-place order mutation");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        normals();vertices();
        System.out.println("PASS restored pre-KVM native prep: exact restored normal quantization / all 9 mutable camera entries / light+normal+eye invalidation / degenerate fallback / 12000 ordered batch streams / both diagonals / alpha+UV / unlit normals untouched");
    }
}
