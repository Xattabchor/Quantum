package code.Rendering;

import code.utils.Main;
import java.lang.reflect.*;
import java.util.*;
import javax.microedition.m3g.*;

/** Lossless short XYZ + binary scale identities, not GPU screenshots/timing. */
public final class FogRebaseTest {
    private static void check(boolean b,String why){M3GTest.check(b,why);}
    private static Field field(String name) throws Exception {
        Field f=M3GPolygonBatch.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void exact(short[] old,float oldScale,short[] now,float newScale,int count) {
        for(int i=0;i<count;i++) {
            check(Float.floatToIntBits(old[i]*oldScale)==Float.floatToIntBits(now[i]*newScale),"native XYZ/depth not bit-exact");
            check(Math.abs((int)now[i])<=32767,"short overflow");
        }
        for(int i=0;i<count;i+=3) {
            check((long)old[i]*now[i+2]==(long)now[i]*old[i+2],"projected X moved");
            check((long)old[i+1]*now[i+2]==(long)now[i+1]*old[i+2],"projected Y moved");
            if(i>0)check((long)old[i+2]*now[2]==(long)now[i+2]*old[2],"relative perspective W changed");
        }
    }
    private static boolean possible(short[] p,int length,int shift,boolean divide) {
        int factor=1<<shift;
        for(int i=0;i<length;i++) {
            if(divide){if(p[i]%factor!=0)return false;}
            else if((long)p[i]*factor < -32767 || (long)p[i]*factor>32767)return false;
        }
        return true;
    }
    private static void randomized() throws Exception {
        M3GPolygonBatch batch=new M3GPolygonBatch(null,null);
        Field scale=field("scale"),size=field("size"),first=field("firstCount");
        short[] one=(short[])field("firstXYZ").get(batch),many=(short[])field("xyz").get(batch),rebased=(short[])field("rebasedXYZ").get(batch);
        Method rebase=M3GPolygonBatch.class.getDeclaredMethod("rebase",Float.TYPE,short[].class,Integer.TYPE);rebase.setAccessible(true);
        Random r=new Random(1710184);int fine=0,coarse=0,rejected=0;
        for(int test=0;test<16000;test++) {
            int shift=1+r.nextInt(15),count=3+r.nextInt(2),vertices=test%2==0?0:3*(1+r.nextInt(64));
            int firstVertices=3+(test/2)%2;
            boolean pendingFine=r.nextBoolean();int base=(70+r.nextInt(60))<<23|r.nextInt(0x800000);
            float f=Float.intBitsToFloat(base),c=Float.intBitsToFloat(base+(shift<<23));
            float a=pendingFine?f:c,b=pendingFine?c:f;
            scale.setFloat(batch,a);size.setInt(batch,vertices);first.setInt(batch,firstVertices);
            short[] pending=vertices==0?one:many;int length=(vertices==0?firstVertices:vertices)*3;
            short[] input=new short[count*3];
            for(int i=0;i<length;i++)pending[i]=(short)(r.nextInt(65535)-32767);
            for(int i=0;i<input.length;i++)input[i]=(short)(r.nextInt(65535)-32767);
            // Exercise successful fine shifts, successful exact coarsening,
            // and late odd components in a large already-expanded packet.
            if(test%4==0) {
                short[] v=pendingFine?input:pending;int n=pendingFine?input.length:length;
                int max=32767>>shift;
                for(int i=0;i<n;i++)v[i]=(short)(max==0?0:r.nextInt(max*2+1)-max);
            } else if(test%4==1) {
                short[] v=pendingFine?pending:input;int n=pendingFine?length:input.length;
                for(int i=0;i<n;i++)v[i]=(short)((v[i]>>(shift))<<shift);
                // -32768 isn't produced by the engine's packers.
                for(int i=0;i<n;i++)if(v[i]==Short.MIN_VALUE)v[i]=0;
            }
            short[] before=pending.clone(),saved=input.clone();float beforeScale=a;
            boolean fitsFine=possible(pendingFine?input:pending,pendingFine?input.length:length,shift,false);
            boolean fitsCoarse=possible(pendingFine?pending:input,pendingFine?length:input.length,shift,true);
            boolean ok=((Boolean)rebase.invoke(batch,b,input,count)).booleanValue();
            check(ok==(fitsFine || fitsCoarse),"lost compatible packet / accepted lossy conversion");
            check(Arrays.equals(input,saved),"source XYZ modified");
            if(ok) {
                float afterScale=scale.getFloat(batch);
                exact(before,beforeScale,pending,afterScale,length);exact(saved,b,rebased,afterScale,input.length);
                if(afterScale==f)fine++;else {check(afterScale==c,"unexpected scale");coarse++;}
            } else {
                rejected++;check(Arrays.equals(before,pending) && scale.getFloat(batch)==beforeScale,"failed preflight partly changed pending packet");
            }
        }
        check(fine>1000 && coarse>1000 && rejected>1000,"insufficient fine/coarse/reject coverage");
        for(float bad:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.MIN_VALUE,0.3f}) {
            scale.setFloat(batch,0.5f);size.setInt(batch,0);first.setInt(batch,3);Arrays.fill(one,(short)8);short[] saved=one.clone();
            check(!((Boolean)rebase.invoke(batch,bad,new short[9],3)).booleanValue(),"invalid/nonbinary scale accepted");
            check(Arrays.equals(one,saved) && scale.getFloat(batch)==0.5f,"invalid scale touched pending packet");
        }
        System.out.println("PASS 16000 exact Fog scale rebases / expanded packets / bit-identical XYZ+depth / exact screen+relative W / atomic overflow+divisibility rejection / unchanged input / invalid scales");
    }
    private static final int[] TRI={0,1,2};
    private static short[] points(int x,int z) {return new short[]{(short)x,0,(short)-z,0,(short)x,(short)-z,0,0,(short)-z,(short)x,(short)x,(short)-z};}
    private static String stream(int start) {
        StringBuffer out=new StringBuffer();
        for(int n=start;n<Graphics3D.history.size();n++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(n);
            for(int v:d.indices) {
                out.append(d.fogMode).append(',').append(d.fogColor).append(',').append(d.near).append(',').append(d.far).append(',').append(d.density).append(',');
                for(int k=0;k<3;k++)out.append(Float.floatToIntBits(d.positions[v*3+k]*d.scale)).append(',');
                for(int k=0;k<2;k++)out.append(d.coords[v*2+k]).append(',');
                for(int k=0;k<4;k++)out.append(d.colors[v*4+k]).append(',');
                out.append(';');
            }
        }
        return out.toString();
    }
    private static void run(int mode,boolean reverse,boolean coarsen,boolean incompatible,boolean quad) {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer owner=new M3GSceneRenderer();
        short[][] p={points(coarsen?30000:1000,incompatible?101:100),points(coarsen?20000:2000,51)};
        float[] scales={0.5f,1};if(reverse){short[] t=p[0];p[0]=p[1];p[1]=t;scales[0]=1;scales[1]=0.5f;}
        short[] uv={0,0,256,0,0,256};byte[] rgba={(byte)255,1,2,(byte)255,3,4,5,(byte)255,6,7,8,(byte)255};
        M3GPolygonBatch.State state=new M3GPolygonBatch.State();state.texture=owner.texture(g,M3GTest.texture(0xff557799).rImg,0);
        state.polygon=new PolygonMode();state.blend=CompositingMode.REPLACE;state.function=Texture2D.FUNC_MODULATE;
        state.fogged=true;state.fogMode=mode;state.fogColor=0x234567;state.near=1;state.far=150;state.density=0.02f;
        int count=quad?4:3;int[] order=quad?new int[]{0,1,3,1,2,3}:TRI;
        if(quad){uv=new short[]{0,0,256,0,256,256,0,256};rgba=Arrays.copyOf(rgba,16);}
        TriangleStripArray index=new TriangleStripArray(order,quad?new int[]{3,3}:new int[]{3});
        M3GPolygonBatch b=new M3GPolygonBatch(owner,Graphics3D.getInstance());
        int start=Graphics3D.history.size();
        for(int i=0;i<2;i++){b.add(g,count,scales[i],p[i],uv,rgba,order,index,state);b.flush(g);}
        String reference=stream(start);owner.finish(g);int calls=Graphics3D.calls;start=Graphics3D.history.size();
        for(int i=0;i<2;i++)b.add(g,count,scales[i],p[i],uv,rgba,order,index,state);b.flush(g);
        check(reference.equals(stream(start)),"native Fog/depth/UV/RGBA/order changed");
        check(Graphics3D.calls-calls==(incompatible?2:1),"incorrect exact scale merge count");
        owner.finish(g);owner.dispose();g.destroy();
    }
    private static RenderObject face(boolean large,boolean quad) {
        int lo=large?-64:10,hi=large?96:20;
        if(quad)return new code.Rendering.Meshes.Polygon4V(M3GTest.vertex(lo,lo,-450),M3GTest.vertex(hi,lo,-450),M3GTest.vertex(hi,hi,-450),M3GTest.vertex(lo,hi,-450),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        return new code.Rendering.Meshes.Polygon3V(M3GTest.vertex(lo,lo,-450),M3GTest.vertex(hi,lo,-450),M3GTest.vertex(lo,hi,-450),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
    }
    private static void queue(DirectX7 g,RenderObject[] faces,Texture texture) {
        g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();
        for(int i=0;i<faces.length;i++)M3GTest.submit(g,faces[i],texture,i);
    }
    private static void recovery() {
        for(int mode:new int[]{3,6,12})for(boolean correction:new boolean[]{true,false})for(boolean quad:new boolean[]{false,true}) {
            Main.m3gPerspective=correction;DirectX7.setFogDist(1000);DirectX7.configureM3GExpFog(null,null);
            DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff8090a0);t.drawmode=(byte)mode;
            RenderObject[] faces=new RenderObject[70];int draws=quad?4:3;
            for(int i=0;i<faces.length;i++)faces[i]=face((i&1)!=0,quad);
            queue(g,faces,t);g.renderGameFrameImage(null,false,false,null,0,null);
            check(g.isM3GActive() && g.getM3GDrawCalls()==draws,"70 alternating Fog scales did not respect merged packet capacity");
            int arrays=VertexArray.created,index=TriangleStripArray.created;
            queue(g,faces,t);g.renderGameFrameImage(null,false,false,null,0,null);
            check(g.getM3GDrawCalls()==draws && g.getM3GTextureUploads()==0 && g.getM3GCpuBarriers()==0
                    && VertexArray.created==arrays && TriangleStripArray.created==index,"warm rebasing adds native allocations/uploads/barriers");
            // Distinct queued CPU tail must execute once, even though the
            // second native packet fails before reaching it.
            M3GTest.Marker expectedTail=new M3GTest.Marker(false),tail=new M3GTest.Marker(false);
            DirectX7 cpu=M3GTest.renderer();queue(cpu,faces,t);M3GTest.submit(cpu,expectedTail,null,1000);cpu.renderSoftware();
            queue(g,faces,t);M3GTest.submit(g,tail,null,1000);Graphics3D.failAt=Graphics3D.calls+3;
            try {g.renderGameFrameImage(null,false,false,null,0,null);}finally{Graphics3D.failAt=0;}
            check(!g.isM3GActive() && Graphics3D.target==null && tail.calls==1 && expectedTail.calls==1 && g.display[0]==0xff00ff00 && Arrays.equals(cpu.display,g.display),"second rebased-packet failure lost complete selected-M3G CPU replay");
            g.destroy();cpu.destroy();
        }
        Main.m3gPerspective=true;
        System.out.println("PASS Fog 3/6/12: 70 alternating triangles/quads -> 2/3 world draws / HW ON+OFF / warm arrays+indices reused B0/T0 / failure after first rebased packet -> exact full CPU replay");
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        randomized();for(int mode:new int[]{Fog.LINEAR,Fog.EXPONENTIAL})for(boolean reverse:new boolean[]{false,true})for(boolean quad:new boolean[]{false,true}) {
            run(mode,reverse,false,false,quad);run(mode,reverse,true,false,quad);run(mode,reverse,true,true,quad);
        }
        System.out.println("PASS native LINEAR+EXP exact rebasing both directions / finer+coarser / unmergeable remains two draws / ordered physical XYZ+UV+RGBA+Fog stream identical");
        recovery();
    }
}
