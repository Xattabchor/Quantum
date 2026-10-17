package code.Rendering;

import code.Math.Matrix;

/** API-free shot pulse and metric eye-space reconstruction for mode 5 only. */
public final class Mode5WeaponLight {
    public static final int DURATION=120, HALF_DISTANCE=1000;
    public static final float X=40,Y=-40,Z=-120, POSITION_SCALE=0.25f;
    private long started;
    private boolean active;
    int level;
    private boolean normalReady;
    private int nx0,ny0,nz0,c00,c01,c02,c10,c11,c12,c20,c21,c22;
    private double normalX,normalY,normalZ,normalLength;

    void start(long now) {started=now;active=true;level=256;}
    void stop() {active=false;level=0;}
    void prepare(long now) {
        long age=now-started;
        if(!active || age<0 || age>=DURATION) {stop();return;}
        level=(int)((DURATION-age)*256/DURATION);
    }

    static double eyeX(DirectX7 g,double x,double y,double z) {
        Matrix c=g.camera;return (c.m00*x+c.m10*y+c.m20*z)/Matrix.FP;
    }
    static double eyeY(DirectX7 g,double x,double y,double z) {
        Matrix c=g.camera;return (c.m01*x+c.m11*y+c.m21*z)/Matrix.FP;
    }
    static double eyeZ(DirectX7 g,double x,double y,double z) {
        Matrix c=g.camera;return (c.m02*x+c.m12*y+c.m22*z)/Matrix.FP;
    }
    static double positionX(DirectX7 g,Vertex v) {
        return ((double)v.sx-g.centreX)*((double)g.distX-v.rz)/g.distX;
    }
    static double positionY(DirectX7 g,Vertex v) {
        return ((double)g.centreY-v.sy)*((double)g.distX-v.rz)/g.distY;
    }

    /** Fixed common 1/4-unit lattice: no polygon-dependent light radius.
     * Too-large/near-plane geometry uses CPU recovery, never wraps shorts. */
    static float pack(DirectX7 g,Vertex[] vertices,int count,short[] out) {
        int distX=g.distX,distY=g.distY,cx=g.centreX,cy=g.centreY;
        if(distX<=0 || distY<=0) return 0;
        for(int i=0,j=0;i<count;i++,j+=3) {
            Vertex v=vertices[i];int z=v.rz;
            if(z>0 || z < -8191) return 0;
            // Multiplication by four is exact in binary; preserve the original
            // divisions/rounding, but reconstruct depth only once per vertex.
            double w=((double)distX-z)*4;
            double x=((double)v.sx-cx)*w/distX,y=((double)cy-v.sy)*w/distY;
            if(x < -32767 || x > 32767 || y < -32767 || y > 32767) return 0;
            out[j]=(short)(int)(x+(x<0?-0.5:0.5));
            out[j+1]=(short)(int)(y+(y<0?-0.5:0.5));out[j+2]=(short)(z<<2);
        }
        return POSITION_SCALE;
    }

    private void normal(DirectX7 g,int nx,int ny,int nz) {
        Matrix c=g.camera;
        if(normalReady && nx0==nx && ny0==ny && nz0==nz
                && c00==c.m00 && c01==c.m01 && c02==c.m02
                && c10==c.m10 && c11==c.m11 && c12==c.m12
                && c20==c.m20 && c21==c.m21 && c22==c.m22) return;
        normalReady=true;nx0=nx;ny0=ny;nz0=nz;
        c00=c.m00;c01=c.m01;c02=c.m02;c10=c.m10;c11=c.m11;c12=c.m12;
        c20=c.m20;c21=c.m21;c22=c.m22;
        normalX=eyeX(g,nx,ny,nz);normalY=eyeY(g,nx,ny,nz);normalZ=eyeZ(g,nx,ny,nz);
        normalLength=Math.sqrt(normalX*normalX+normalY*normalY+normalZ*normalZ);
    }

    /** Selected-M3G CPU replay: same white ambient/directional + OMNI equation.
     * Per-vertex factors feed the original grey interpolators, no distance Fog. */
    public int shade(DirectX7 g,Vertex v,int nx,int ny,int nz) {
        int base=DirectX7.mode5Shade(nx,ny,nz);
        if(base==256 || level<=0 || g.distX<=0 || g.distY<=0 || (nx==0 && ny==0 && nz==0)) return base;
        normal(g,nx,ny,nz);
        double dx=X-positionX(g,v),dy=Y-positionY(g,v),dz=Z-v.rz;
        double dot=normalX*dx+normalY*dy+normalZ*dz;
        if(dot<=0 || normalLength==0) return base;
        double d2=dx*dx+dy*dy+dz*dz,dist=Math.sqrt(d2);
        if(dist==0) return base;
        dot=dot/(normalLength*dist);
        double point=level*dot/(1+d2/((double)HALF_DISTANCE*HALF_DISTANCE));
        return Math.min(256,base+(int)(point+0.5));
    }
}
