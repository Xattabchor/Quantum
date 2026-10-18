package code.Rendering;

/** Reusable near + viewport clipping for rejected projected WORLD polygons.
 * CPU geometry only; texture/color rasterization remains in M3G. Prepare ALL
 * triangles before submitting any, so an unrepresentable result keeps atomic
 * whole-polygon CPU fallback. Existing successful packets never enter here.
 */
public final class WorldClipGeometry {
    private static final int STRIDE=11,MAX_TRIANGLES=16;
    private final float[] first=new float[STRIDE*10],second=new float[STRIDE*10];
    private float[] polygon;
    private int size;
    private boolean affineAttributes;
    private final Vertex[] vertices={new Vertex(),new Vertex(),new Vertex()};
    private final float[] work=new float[9];
    private final short[] positions=new short[9];
    public final short[] xyz=new short[MAX_TRIANGLES*9],uv=new short[MAX_TRIANGLES*6];
    public final byte[] rgba=new byte[MAX_TRIANGLES*12];
    public final float[] scales=new float[MAX_TRIANGLES];
    public int triangles;

    public boolean prepare(DirectX7 g,Vertex[] source,int count,short[] coords,byte[] colors,
            int distance,boolean perspective,boolean fog,boolean alternate,boolean correction) {
        triangles=0;affineAttributes=!perspective || !correction;
        if(distance<=0 || g.distX<=0 || g.distY<=0) return false;
        for(int i=0;i<count;i++) if(!source[i].hasView(g)) return false;
        if(count==3) return triangle(g,source,coords,colors,0,1,2,distance,perspective,fog);
        if(alternate) return triangle(g,source,coords,colors,0,1,2,distance,perspective,fog)
                && triangle(g,source,coords,colors,0,2,3,distance,perspective,fog);
        return triangle(g,source,coords,colors,0,1,3,distance,perspective,fog)
                && triangle(g,source,coords,colors,1,2,3,distance,perspective,fog);
    }
    private void set(int slot,Vertex v,int u,int t,byte[] colors,int color) {
        int p=slot*STRIDE;first[p]=v.getViewX();first[p+1]=v.getViewY();first[p+2]=v.rz;
        first[p+3]=u;first[p+4]=t;
        for(int k=0;k<4;k++) first[p+5+k]=colors[color*4+k]&255;
        first[p+9]=v.sx;first[p+10]=v.sy;
    }
    private boolean triangle(DirectX7 g,Vertex[] source,short[] coords,byte[] colors,
            int a,int b,int c,int distance,boolean perspective,boolean fog) {
        set(0,source[a],coords[a*2],coords[a*2+1],colors,a);
        set(1,source[b],coords[b*2],coords[b*2+1],colors,b);
        set(2,source[c],coords[c*2],coords[c*2+1],colors,c);
        polygon=first;size=3;plane(0,0,-1,0,true); // Engine near plane in view space: rz<=0.
        for(int i=0;i<size;i++) {
            int p=i*STRIDE;float z=polygon[p+2];
            float sx=polygon[p+9],sy=polygon[p+10];
            if(Float.isNaN(sx)) {
                sx=g.centreX+polygon[p]*g.distX/(g.distX-z);
                sy=g.centreY-polygon[p+1]*g.distY/(g.distY-z);
            }
            float w=perspective || fog ? 1-z/distance : 1;
            polygon[p]=(sx-g.centreX)*w;polygon[p+1]=(g.centreY-sy)*w;
            polygon[p+2]=w;polygon[p+9]=z;
        }
        // Geometry/depth clip in homogeneous space. Attribute interpolation
        // follows the actual native perspective-correction mode.
        plane(1,0,g.centreX,0,false);
        plane(-1,0,g.width-g.centreX,0,false);
        plane(0,-1,g.centreY,0,false);
        plane(0,1,g.height-g.centreY,0,false);
        for(int i=1;i+1<size;i++) {
            if(triangles==MAX_TRIANGLES) return false;
            int p0=0,p1=i*STRIDE,p2=(i+1)*STRIDE;
            point(g,0,p0);point(g,1,p1);point(g,2,p2);
            // Use the same integer screen lattice as original projected vertices.
            // Canonical intersections make adjacent faces share exact endpoints,
            // independently of each packet's homogeneous quantization scale.
            long area=((long)vertices[1].sx-vertices[0].sx)*(vertices[2].sy-vertices[0].sy)
                    -((long)vertices[1].sy-vertices[0].sy)*(vertices[2].sx-vertices[0].sx);
            if(area==0) continue;
            float scale=fog?ProjectedGeometry.packFog(vertices,3,g.centreX,g.centreY,distance,positions,work)
                    :ProjectedGeometry.pack(vertices,3,g.centreX,g.centreY,distance,perspective,positions,work);
            if(scale==0) return false;
            System.arraycopy(positions,0,xyz,triangles*9,9);scales[triangles]=scale;
            attributes(0,p0);attributes(1,p1);attributes(2,p2);triangles++;
        }
        return true;
    }
    private void point(DirectX7 g,int slot,int p) {
        float w=polygon[p+2];Vertex v=vertices[slot];
        v.sx=round(g.centreX+polygon[p]/w);v.sy=round(g.centreY-polygon[p+1]/w);
        v.rz=Math.min(0,round(polygon[p+9]));
    }
    private void attributes(int slot,int p) {
        uv[triangles*6+slot*2]=(short)round(polygon[p+3]);
        uv[triangles*6+slot*2+1]=(short)round(polygon[p+4]);
        for(int k=0;k<4;k++) rgba[triangles*12+slot*4+k]=(byte)Math.max(0,Math.min(255,round(polygon[p+5+k])));
    }
    private void plane(float x,float y,float z,float constant,boolean near) {
        if(size==0) return;
        float[] input=polygon,output=input==first?second:first;int n=0;
        for(int i=0;i<size;i++) {
            int a=((i+size-1)%size)*STRIDE,b=i*STRIDE;
            float da=input[a]*x+input[a+1]*y+input[a+2]*z+constant;
            float db=input[b]*x+input[b+1]*y+input[b+2]*z+constant;
            if((da<0 && db>0) || (da>0 && db<0)) {
                // Always start at the outside endpoint, independent of winding.
                int low=da<db?a:b,high=da<db?b:a;
                float dl=Math.min(da,db),dh=Math.max(da,db),t=-dl/(dh-dl);
                for(int k=0;k<STRIDE;k++) output[n*STRIDE+k]=input[low+k]+(input[high+k]-input[low+k])*t;
                if(!near && affineAttributes) {
                    // An affine native material interpolates attributes in screen
                    // space even when geometry keeps W for eye-space Fog.
                    float screenT=t*input[high+2]/output[n*STRIDE+2];
                    for(int k=3;k<9;k++) output[n*STRIDE+k]=input[low+k]+(input[high+k]-input[low+k])*screenT;
                }
                if(near) {output[n*STRIDE+2]=0;output[n*STRIDE+9]=Float.NaN;}
                n++;
            }
            if(db>=0) {System.arraycopy(input,b,output,n*STRIDE,STRIDE);n++;}
        }
        polygon=output;size=n;
    }
    private static int round(float value) {return (int)(value+(value<0?-0.5f:0.5f));}
}
