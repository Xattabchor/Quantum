package code.Rendering;

/** Actual API-free production entry points; no fake graphics implementation. */
public final class KvmNativePrep {
    private static final int[] TRI={0,1,2},QUAD={0,1,3,1,2,3};
    private static final short[] positions={100,200,300,400,500,600,700,800,900,1000,1100,1200};
    private static final short[] coords={10,20,30,40,50,60,70,80};
    private static final byte[] colors={1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
    private static final short[] normals={1,2,3,4,5,6,7,8,9,10,11,12};
    private static final short[] xyz=new short[192*3],uv=new short[192*2],normalOut=new short[192*3];
    private static final byte[] rgba=new byte[192*4];
    private static DirectX7 renderer;
    private static Face face;
    private static int sink;
    static final class Face extends RenderObject {
        public boolean isVisible(int a,int b,int c,int d) {return true;}
        public void render(DirectX7 g,Texture t) { }
        public void renderFast(DirectX7 g,Texture t) { }
    }
    private static int batch(String task,int repeats) {
        int checksum=0;
        if(task.startsWith("normal_")) {
            boolean eye=task.indexOf("_eye_")>=0,varying=task.indexOf("_vary_")>=0;
            face.nx=0;face.ny=0;face.nz=4096;
            DirectX7 g=eye?renderer:null;
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) {
                    if(varying) {face.nx=i%101-50;face.ny=i%73-36;}
                    M3GMode5Lighting.packNormals(face,3,normalOut,1000,-2000,4096,g);
                }
                checksum+=normalOut[0]+normalOut[1]+normalOut[2];
            }
        } else if(task.startsWith("batch_")) {
            boolean lit=task.indexOf("_unlit_")<0;
            int[] order=task.indexOf("_tri_")>=0?TRI:QUAD;
            for(int n=0;n<repeats;n++) {
                int size=0;
                for(int i=0;i<1000;i++) {
                    size=M3GPolygonBatch.appendVertices(positions,coords,colors,normals,order,
                            xyz,uv,rgba,normalOut,size,lit);
                    if(size+order.length>192) size=0;
                }
                checksum+=size+xyz[0]+uv[0]+rgba[0]+(lit?normalOut[0]:0);
            }
        } else throw new IllegalArgumentException(task);
        sink=checksum;return checksum;
    }
    private static void check() {
        face=new Face();face.nz=4096;
        M3GMode5Lighting.packNormals(face,3,normalOut,0,0,4096,null);
        if(normalOut[0]!=0 || normalOut[1]!=0 || normalOut[2]!=32767)
            throw new RuntimeException("normal equation");
        int size=M3GPolygonBatch.appendVertices(positions,coords,colors,normals,QUAD,
                xyz,uv,rgba,normalOut,3,true);
        if(size!=9) throw new RuntimeException("batch size");
        for(int i=0;i<6;i++) {
            for(int c=0;c<3;c++) if(xyz[(i+3)*3+c]!=positions[QUAD[i]*3+c]
                    || normalOut[(i+3)*3+c]!=normals[QUAD[i]*3+c]) throw new RuntimeException("batch XYZ/normal order");
            for(int c=0;c<2;c++) if(uv[(i+3)*2+c]!=coords[QUAD[i]*2+c]) throw new RuntimeException("batch UV order");
            for(int c=0;c<4;c++) if(rgba[(i+3)*4+c]!=colors[QUAD[i]*4+c]) throw new RuntimeException("batch RGBA order");
        }
        renderer=new DirectX7(240,320);renderer.camera.setRotY(37);
    }
    public static void main(String[] args) {
        if(args[0].equals("health") || args[0].equals("probe")) {KvmPerformance.main(args);return;}
        String task=args[0];check();int repeats=1;long elapsed;
        do {
            long start=System.currentTimeMillis();batch(task,repeats);
            elapsed=System.currentTimeMillis()-start;
            if(elapsed<250) repeats*=2;
        } while(elapsed<250 && repeats<1048576);
        batch(task,repeats);
        System.out.println("TASK "+task+" repeats="+repeats+" heap="+Runtime.getRuntime().totalMemory());
        for(int i=0;i<5;i++) {
            long start=System.currentTimeMillis();batch(task,repeats);
            elapsed=System.currentTimeMillis()-start;
            if(elapsed<=0) throw new RuntimeException("clock resolution");
            System.out.println("SAMPLE "+elapsed+" "+repeats+" "+sink);
        }
    }
}
