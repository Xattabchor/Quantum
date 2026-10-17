package code.Rendering;

import java.util.Arrays;
import java.util.Random;

/** Exact pre-optimization normal and vertex stream references; not GPU timing. */
public final class NativePrepTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
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
        final java.lang.reflect.Field size=field("size");
        final java.lang.reflect.Method method=M3GPolygonBatch.class.getDeclaredMethod("append",
                short[].class,short[].class,byte[].class,int[].class);
        BatchProbe() throws Exception {method.setAccessible(true);}
        int append(short[] p,short[] uv,byte[] c,short[] n,int[] order,int offset,boolean lighting) throws Exception {
            size.setInt(batch,offset);
            method.invoke(batch,p,uv,c,order);return size.getInt(batch);
        }
    }
    private static void vertices() throws Exception {
        Random r=new Random(192);
        short[] p=new short[12],uv=new short[8],n=new short[12];byte[] c=new byte[16];
        BatchProbe probe=new BatchProbe();
        short[] actualP=(short[])field("xyz").get(probe.batch),actualUV=(short[])field("uv").get(probe.batch);
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
            Arrays.fill(expectedN,(short)444);
            Arrays.fill(actualC,(byte)99);Arrays.fill(expectedC,(byte)99);
            short[] savedP=p.clone(),savedUV=uv.clone(),savedN=n.clone();byte[] savedC=c.clone();
            int count=reference(p,uv,c,n,order,expectedP,expectedUV,expectedC,expectedN,offset,lit);
            int actual=probe.append(p,uv,c,lit?n:null,order,offset,lit);
            check(count==actual && Arrays.equals(actualP,expectedP) && Arrays.equals(actualUV,expectedUV)
                    && Arrays.equals(actualC,expectedC),"exact ordered batch / both diagonals / prefix+suffix");
            check(Arrays.equals(p,savedP) && Arrays.equals(uv,savedUV) && Arrays.equals(n,savedN) && Arrays.equals(c,savedC),"batch changed input arrays");
            // An order array may be edited in place; no identity-only cache.
            if(order.length>0) {
                order[0]=(order[0]+1)%4;
                reference(p,uv,c,n,order,expectedP,expectedUV,expectedC,expectedN,offset,lit);
                probe.append(p,uv,c,lit?n:null,order,offset,lit);
                check(Arrays.equals(actualP,expectedP) && Arrays.equals(actualUV,expectedUV)
                        && Arrays.equals(actualC,expectedC),"in-place order mutation");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        vertices();
        System.out.println("PASS restored pre-KVM native prep: 12000 ordered batch streams / both diagonals / alpha+UV");
    }
}
