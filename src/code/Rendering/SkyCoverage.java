package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;

/** Conservative API-free certificate for a closed, opaque enclosing sky.
 * Geometry is checked once (again after coordinate/topology edits). Per-frame
 * work is bounded by mesh corners, material identities and a 3x3 matrix bound.
 */
public final class SkyCoverage {
    private final Mesh mesh;
    private RenderObject[] polygons;
    private Vertex[] corners,vertices;
    private int[] vertexXYZ;
    private boolean oversized;
    private int[] xyz,counts;
    private double innerSquared,outerSquared;
    private boolean enclosed;
    private RawImage[] images;
    private int[][] pixels;
    private int[] widths,heights;
    private boolean opaque;

    public SkyCoverage(Mesh mesh) {this.mesh=mesh;}

    public boolean complete(DirectX7 g,Matrix m,int x1,int y1,int x2,int y2) {
        if(x1!=0 || y1!=0 || x2!=g.width || y2!=g.height || g.distX<=0 || g.distY<=0) return false;
        RenderObject[] current=mesh.getPolygons();
        if(!unchanged(current)) rebuild(current);
        if(!enclosed || !opaque()) return false;
        // Vertex.transform uses these truncated 12-bit coefficients. Gershgorin
        // bounds enclose squared singular values, including scale/shear. Each
        // transformed coordinate has <3 units of integer rounding error.
        double a=(m.m00>>2)/4096.0,b=(m.m01>>2)/4096.0,c=(m.m02>>2)/4096.0;
        double d=(m.m10>>2)/4096.0,e=(m.m11>>2)/4096.0,f=(m.m12>>2)/4096.0;
        double h=(m.m20>>2)/4096.0,i=(m.m21>>2)/4096.0,j=(m.m22>>2)/4096.0;
        double u=a*a+d*d+h*h,v=b*b+e*e+i*i,w=c*c+f*f+j*j;
        double uv=Math.abs(a*b+d*e+h*i),uw=Math.abs(a*c+d*f+h*j),vw=Math.abs(b*c+e*f+i*j);
        double lower=Math.min(u-uv-uw,Math.min(v-uv-vw,w-uw-vw));
        double upper=Math.max(u+uv+uw,Math.max(v+uv+vw,w+uw+vw));
        if(lower<=0 || upper>4) return false;
        double move=Math.sqrt((double)m.m03*m.m03+(double)m.m13*m.m13+(double)m.m23*m.m23);
        double radius=Math.sqrt(innerSquared*lower)-move-8;
        double x=Math.max(Math.abs((double)g.centreX),Math.abs((double)g.width-g.centreX));
        double y=Math.max(Math.abs((double)g.centreY),Math.abs((double)g.height-g.centreY));
        double near=Math.sqrt(x*x+y*y);
        if(radius<=near+8) return false;
        // Even the complete near viewport lies INSIDE the closed surface. Each
        // pixel ray must cross it. Reject very low short-W precision as well;
        // viewport clipping/quantization must not expose the previous image.
        double ray=Math.sqrt(1+x*x/g.distX/g.distX+y*y/g.distY/g.distY);
        double minW=g.distY+(radius-near)/ray;
        double bound=Math.max(1,Math.max(2*x/g.width,2*y/g.height));
        double scale=(g.distY+Math.sqrt(outerSquared*upper)+move+8)*bound/30000;
        return minW/scale>16*Math.max(g.width,g.height)*bound;
    }

    public int color() {return mesh.getTexture().textures[0].rImg.img[0]&0xffffff;}

    private static int count(RenderObject p) {
        Class type=p.getClass();
        if(type==Polygon3V.class || type==LightedPolygon3V.class || type==ColorLightedPolygon3V.class) return 3;
        if(type==Polygon4V.class || type==LightedPolygon4V.class || type==ColorLightedPolygon4V.class) return 4;
        return 0;
    }
    private static Vertex corner(RenderObject p,int index) {
        if(p instanceof Polygon3V) {Polygon3V t=(Polygon3V)p;return index==0?t.a:index==1?t.b:t.c;}
        Polygon4V q=(Polygon4V)p;return index==0?q.a:index==1?q.b:index==2?q.c:q.d;
    }
    private boolean unchanged(RenderObject[] current) {
        if(current!=polygons || counts==null || current.length!=counts.length) return false;
        if(oversized) return true; // Negative certificate, independent of coordinates.
        if(corners==null) return false;
        Vertex[] all=mesh.getVertices();
        if(all.length!=vertices.length) return false;
        for(int k=0;k<all.length;k++) {
            Vertex v=all[k];
            if(v!=vertices[k] || v.x!=vertexXYZ[k*3] || v.y!=vertexXYZ[k*3+1] || v.z!=vertexXYZ[k*3+2]) return false;
        }
        int n=0;
        for(int k=0;k<current.length;k++) {
            int count=count(current[k]);
            if(count!=counts[k]) return false;
            for(int t=0;t<count;t++,n++) {
                Vertex v=corner(current[k],t);
                if(v!=corners[n] || v.x!=xyz[n*3] || v.y!=xyz[n*3+1] || v.z!=xyz[n*3+2]) return false;
            }
        }
        return true;
    }
    private static boolean same(Vertex a,Vertex b) {return a.x==b.x && a.y==b.y && a.z==b.z;}
    private void rebuild(RenderObject[] current) {
        polygons=current;enclosed=false;innerSquared=Double.MAX_VALUE;outerSquared=0;
        counts=new int[current.length];int total=0;
        for(int k=0;k<current.length;k++) {counts[k]=count(current[k]);total+=counts[k];}
        oversized=total==0 || total>512;
        if(oversized) {corners=null;return;} // Small sky meshes only.
        corners=new Vertex[total];xyz=new int[total*3];int n=0;
        boolean valid=true;
        for(int k=0;k<current.length;k++) {
            if(counts[k]==0) valid=false;
            for(int t=0;t<counts[k];t++,n++) {
                Vertex v=corner(current[k],t);corners[n]=v;xyz[n*3]=v.x;xyz[n*3+1]=v.y;xyz[n*3+2]=v.z;
                if(Math.abs((double)v.x)>32767 || Math.abs((double)v.y)>32767 || Math.abs((double)v.z)>32767) valid=false;
                outerSquared=Math.max(outerSquared,(double)v.x*v.x+(double)v.y*v.y+(double)v.z*v.z);
            }
        }
        Vertex[] all=mesh.getVertices();vertices=new Vertex[all.length];vertexXYZ=new int[all.length*3];
        for(int k=0;k<all.length;k++) {
            Vertex v=all[k];vertices[k]=v;vertexXYZ[k*3]=v.x;vertexXYZ[k*3+1]=v.y;vertexXYZ[k*3+2]=v.z;
            if(Math.abs((double)v.x)>32767 || Math.abs((double)v.y)>32767 || Math.abs((double)v.z)>32767) valid=false;
            outerSquared=Math.max(outerSquared,(double)v.x*v.x+(double)v.y*v.y+(double)v.z*v.z);
        }
        for(int k=0;k<corners.length;k++) {
            boolean found=false;for(int t=0;t<vertices.length;t++) if(corners[k]==vertices[t]) {found=true;break;}
            if(!found) valid=false;
        }
        if(!valid) return;
        // Weld by position, not Vertex identity: UV seams may duplicate vertices.
        for(int k=0,start=0;k<counts.length;start+=counts[k++]) for(int t=0;t<counts[k];t++) {
            Vertex a=corners[start+t],b=corners[start+(t+1)%counts[k]];int matches=0;
            if(same(a,b)) return;
            for(int l=0,base=0;l<counts.length;base+=counts[l++]) for(int z=0;z<counts[l];z++) {
                Vertex c=corners[base+z],d=corners[base+(z+1)%counts[l]];
                if((same(a,c)&&same(b,d)) || (same(a,d)&&same(b,c))) matches++;
            }
            if(matches!=2) return;
        }
        int intersections=0;
        for(int k=0,start=0;k<counts.length;start+=counts[k++]) {
            int hit=triangle(corners[start],corners[start+1],corners[start+(counts[k]==3?2:3)]);
            if(hit<0) return;intersections+=hit;
            if(counts[k]==4) {hit=triangle(corners[start+1],corners[start+2],corners[start+3]);if(hit<0) return;intersections+=hit;}
        }
        enclosed=(intersections&1)!=0 && innerSquared>0;
    }
    private int triangle(Vertex a,Vertex b,Vertex c) {
        double x=b.x-a.x,y=b.y-a.y,z=b.z-a.z,u=c.x-a.x,v=c.y-a.y,w=c.z-a.z;
        double nx=y*w-z*v,ny=z*u-x*w,nz=x*v-y*u,length=nx*nx+ny*ny+nz*nz;
        double plane=nx*a.x+ny*a.y+nz*a.z;
        if(length==0 || plane==0) return -1;
        innerSquared=Math.min(innerSquared,plane*plane/length);
        // Mod-2 inside test. An edge/vertex hit is ambiguous: do not optimize.
        double ry=0.371390676,rz=0.529113,px=ry*w-rz*v,py=rz*u-w,pz=v-ry*u;
        double det=x*px+y*py+z*pz;
        if(Math.abs(det)<0.0000001) return 0;
        double s=(-a.x*px-a.y*py-a.z*pz)/det;
        double qx=-a.y*z+a.z*y,qy=-a.z*x+a.x*z,qz=-a.x*y+a.y*x;
        double t=(qx+ry*qy+rz*qz)/det,distance=(u*qx+v*qy+w*qz)/det;
        if(s< -0.0000001 || t< -0.0000001 || s+t>1.0000001 || distance<=0) return 0;
        if(s<0.0000001 || t<0.0000001 || 1-s-t<0.0000001) return -1;
        return 1;
    }
    private boolean opaque() {
        MultyTexture materials=mesh.getTexture();
        if(materials==null || materials.textures==null || materials.textures.length==0) return false;
        Texture[] ts=materials.textures;boolean changed=images==null || images.length!=ts.length;
        for(int n=0;n<ts.length;n++) {
            if(ts[n]==null || DirectX7.m3gDrawMode(ts[n].drawmode)!=0 || ts[n].rImg==null || ts[n].rImg.img==null) return false;
            RawImage r=ts[n].rImg;
            if(!changed && (images[n]!=r || pixels[n]!=r.img || widths[n]!=r.w || heights[n]!=r.h)) changed=true;
        }
        if(changed) {
            images=new RawImage[ts.length];pixels=new int[ts.length][];widths=new int[ts.length];heights=new int[ts.length];opaque=true;
            for(int n=0;n<ts.length;n++) {
                RawImage r=ts[n].rImg;images[n]=r;pixels[n]=r.img;widths[n]=r.w;heights[n]=r.h;
                long size=(long)r.w*r.h;
                if(r.w<=0 || r.h<=0 || size>r.img.length) {opaque=false;continue;}
                for(int k=0;k<(int)size;k++) if(r.img[k]==0) {opaque=false;break;}
            }
        }
        return opaque;
    }
}
