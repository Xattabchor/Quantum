package code.Rendering;

/** Conservative world AABB / actual native clip-plane tests; no vertex projection.
 * Uses the inverse of the SAME quantized camera transform passed to M3G, not
 * an assumed orthonormal transpose. An invalid transform keeps everything.
 */
final class PortalMapBvhFrustum {
    private final float[] planes=new float[24];
    private boolean valid;
    void set(float[] m,float pz,float pw,int width,int height,int fx,int fy,int left,int top,int right,int bottom) {
        float a=m[0],b=m[1],c=m[2],d=m[4],e=m[5],f=m[6],g=m[8],h=m[9],i=m[10];
        float det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        valid=!(Float.isNaN(det) || Float.isInfinite(det) || Math.abs(det)<0.00001f);
        if(!valid)return;
        float r00=(e*i-f*h)/det,r01=(c*h-b*i)/det,r02=(b*f-c*e)/det;
        float r10=(f*g-d*i)/det,r11=(a*i-c*g)/det,r12=(c*d-a*f)/det;
        float r20=(d*h-e*g)/det,r21=(b*g-a*h)/det,r22=(a*e-b*d)/det;
        float tx=-(r00*m[3]+r01*m[7]+r02*m[11]);
        float ty=-(r10*m[3]+r11*m[7]+r12*m[11]);
        float tz=-(r20*m[3]+r21*m[7]+r22*m[11]);
        for(int n=0;n<6;n++) {
            // Derive near/far from actual float projection coefficients. Using
            // the ideal requested far distance can disagree with native rounding.
            float z=n==0?(left-width*.5f)/fx:n==1?(width*.5f-right)/fx:n==2?(height*.5f-bottom)/fy:n==3?(top-height*.5f)/fy:n==4?pz-1:-pz-1,w=n<4?0:n==4?pw:-pw;
            // Side planes in camera coordinates, through the portal window.
            float x=n==0?1:n==1?-1:0,y=n==2?1:n==3?-1:0;
            int k=n*4;planes[k]=x*r00+y*r10+z*r20;planes[k+1]=x*r01+y*r11+z*r21;
            planes[k+2]=x*r02+y*r12+z*r22;planes[k+3]=x*tx+y*ty+z*tz+w;
        }
    }
    boolean outside(int[] bounds,int offset) {
        if(!valid)return false;
        for(int n=0;n<6;n++) {
            int k=n*4;float a=planes[k],b=planes[k+1],c=planes[k+2],d=planes[k+3];
            float x=a>=0?bounds[offset+3]:bounds[offset],y=b>=0?bounds[offset+4]:bounds[offset+1],z=c>=0?bounds[offset+5]:bounds[offset+2];
            float ax=a*x,by=b*y,cz=c*z;
            float guard=4*(Math.abs(a)+Math.abs(b)+Math.abs(c))
                    +0.00001f*(Math.abs(ax)+Math.abs(by)+Math.abs(cz)+Math.abs(d));
            if(ax+by+cz+d < -guard)return true;
        }
        return false;
    }
}
