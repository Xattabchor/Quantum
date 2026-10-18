package code.Rendering;

import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Buffer/API work contracts only, not ARM9 timing or actual GPU output. */
public final class Arm9WorldBuffersTest {
    private static void check(boolean ok,String why){M3GTest.check(ok,why);}
    private static final short[] XYZ={-40,40,-300,40,40,-300,-40,-40,-300};
    private static final short[] UV={0,0,256,0,0,256};
    private static final byte[] RGBA={17,34,51,-1,68,85,102,-1,119,-120,-103,-1};
    private static final int[] ORDER={0,1,2};
    private static final TriangleStripArray INDEX=new TriangleStripArray(0,new int[]{3});
    private static void packet(M3GPolygonBatch batch,DirectX7 g,int triangles,float scale,boolean fogged) {
        M3GPolygonBatch.State state=new M3GPolygonBatch.State();
        state.polygon=new PolygonMode();state.blend=CompositingMode.REPLACE;
        state.fogged=fogged;state.near=0;state.far=1000;
        for(int i=0;i<triangles;i++)batch.add(g,3,scale,XYZ,UV,RGBA,ORDER,INDEX,state);
        batch.flush(g);
    }
    private static Object field(M3GPolygonBatch batch,String name) throws Exception {
        Field f=M3GPolygonBatch.class.getDeclaredField(name);f.setAccessible(true);return f.get(batch);
    }
    private static void stream(Graphics3D.Draw d,int triangles,float scale) {
        check(d.indices.length==triangles*3 && d.scale==scale,"world packet topology/scale changed");
        for(int i=0;i<d.indices.length;i++) {
            int vertex=d.indices[i],source=i%3;
            for(int j=0;j<3;j++)check(d.positions[vertex*3+j]==XYZ[source*3+j],"world XYZ changed");
            for(int j=0;j<2;j++)check(d.coords[vertex*2+j]==UV[source*2+j],"world UV changed");
            for(int j=0;j<4;j++)check(d.colors[vertex*4+j]==RGBA[source*4+j],"world RGBA changed");
        }
    }
    private static void singles() {
        DirectX7 g=M3GTest.renderer();g.requireM3G();M3GSceneRenderer owner=new M3GSceneRenderer();
        short[] p=Arrays.copyOf(XYZ,12),u=Arrays.copyOf(UV,8);byte[] c=Arrays.copyOf(RGBA,16);
        p[9]=40;p[10]=-40;p[11]=-300;u[6]=256;u[7]=256;Arrays.fill(c,12,16,(byte)255);
        TriangleStripArray quad=new TriangleStripArray(new int[]{0,1,3,1,2,3},new int[]{3,3});
        Appearance state=new Appearance();state.setPolygonMode(new PolygonMode());
        CompositingMode comp=new CompositingMode();comp.setBlending(CompositingMode.REPLACE);
        comp.setDepthTestEnable(false);comp.setDepthWriteEnable(false);state.setCompositingMode(comp);
        float[] previous={Float.NaN,Float.NaN};
        for(int i=0;i<20;i++) {
            int count=i%2+3,slot=count-3;float scale=i<8?0.25f:i<16?0.5f:0.25f;
            int binds=VertexBuffer.positionBindings;
            owner.renderSingle(g,count,scale,p,u,c,count==3?INDEX:quad,state);owner.finish(g);
            Graphics3D.Draw d=Graphics3D.history.lastElement();
            check(d.scale==scale && Arrays.equals(d.positions,Arrays.copyOf(p,count*3)) && Arrays.equals(d.coords,Arrays.copyOf(u,count*2)) && Arrays.equals(d.colors,Arrays.copyOf(c,count*4)),"single-buffer cache changed geometry/attributes/scale");
            check(VertexBuffer.positionBindings-binds==(previous[slot]==scale?0:1),"unchanged triangle/quad position scale rebound");
            previous[slot]=scale;
        }
        owner.dispose();owner.renderSingle(g,3,0.25f,p,u,c,INDEX,state);owner.finish(g);
        check(Graphics3D.history.lastElement().scale==0.25f,"renderer reuse after dispose lost positions");
        owner.dispose();g.destroy();
    }
    public static void main(String[] args) throws Exception {
        DirectX7 g=M3GTest.renderer();g.requireM3G();
        M3GSceneRenderer owner=new M3GSceneRenderer();M3GPolygonBatch batch=new M3GPolygonBatch(owner,Graphics3D.getInstance());
        int capacity=0,growths=0;
        for(int n:new int[]{2,3,4,5,8,9,16,17,32,33,64,2,64}) {
            int expected=6;while(expected<n*3)expected*=2;
            capacity=Math.max(capacity,expected);
            int arrays=VertexArray.created,binds=VertexBuffer.positionBindings;
            int oldCapacity=growths==0?0:((VertexArray)field(batch,"positions")).capacity;
            packet(batch,g,n,0.25f,false);owner.finish(g);
            Graphics3D.Draw draw=Graphics3D.history.lastElement();stream(draw,n,1);
            check(draw.positionCapacity==capacity,"ARM9 world small packet still uses 192 vertex capacity");
            int grew=oldCapacity<capacity?1:0;growths+=grew;
            check(VertexArray.created-arrays==3*grew,"world buffer allocation not bounded to growth");
            check(VertexBuffer.positionBindings-binds==grew,"unchanged world scale rebound");
            arrays=VertexArray.created;int indices=TriangleStripArray.created;binds=VertexBuffer.positionBindings;
            packet(batch,g,n,0.5f,false);owner.finish(g);
            stream(Graphics3D.history.lastElement(),n,1);
            check(VertexArray.created==arrays && TriangleStripArray.created==indices && VertexBuffer.positionBindings==binds,"warm world allocation/scale bind");
        }
        check(growths==6,"capacity buckets not bounded to six growth steps");
        for(float scale:new float[]{0.25f,0.25f,0.5f,0.5f,1,1,0.25f}) {
            int binds=VertexBuffer.positionBindings;float old=((VertexBuffer)field(batch,"buffer")).scale;
            packet(batch,g,2,scale,true);owner.finish(g);stream(Graphics3D.history.lastElement(),2,scale);
            check(VertexBuffer.positionBindings-binds==(old==scale?0:1),"fog scale cache corrupted native eye depth");
        }
        batch.discard();int bindings=VertexBuffer.positionBindings;
        packet(batch,g,2,0.25f,true);owner.finish(g);
        check(VertexBuffer.positionBindings==bindings,"discard invalidated still-owned world buffer");
        owner.dispose();g.destroy();

        g=M3GTest.renderer();g.requireM3G();owner=new M3GSceneRenderer();batch=new M3GPolygonBatch(owner,Graphics3D.getInstance());
        packet(batch,g,2,1,false);owner.finish(g);
        Object oldBuffer=field(batch,"buffer"),oldPositions=field(batch,"positions");
        VertexArray.failAt=VertexArray.created+2;boolean failed=false;
        try{packet(batch,g,3,1,false);}catch(OutOfMemoryError expected){failed=true;}finally{VertexArray.failAt=0;}
        check(failed && field(batch,"buffer")==oldBuffer && field(batch,"positions")==oldPositions && Graphics3D.target==null,"failed growth published incomplete native buffers");
        check(((Integer)field(batch,"firstCount")).intValue()==0,"failed packet could be replayed");
        packet(batch,g,2,1,false);owner.finish(g);stream(Graphics3D.history.lastElement(),2,1);
        owner.dispose();g.destroy();singles();
        check(Arrays.equals(XYZ,new short[]{-40,40,-300,40,40,-300,-40,-40,-300}),"input geometry mutated");
        System.out.println("PASS ARM9 world buffer capacity 192->6 for two triangles / six bounded growth steps / warm no reallocations or scale binds / exact XYZ UV RGBA Fog scales / atomic growth OOM / triangle+quad scale cache");
    }
}
