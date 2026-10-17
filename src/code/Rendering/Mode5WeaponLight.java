package code.Rendering;

import code.Math.Matrix;

/** API-free shot pulse and metric eye-space reconstruction for mode 5 only. */
public final class Mode5WeaponLight {
    public static final int DURATION=120, HALF_DISTANCE=1000;
    public static final float X=40,Y=-40,Z=-120, POSITION_SCALE=0.25f;
    private long started;
    private boolean active;
    int level;

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
        if(g.distX<=0 || g.distY<=0) return 0;
        for(int i=0;i<count;i++) {
            Vertex v=vertices[i];if(v.rz>0) return 0;
            double x=positionX(g,v)/POSITION_SCALE,y=positionY(g,v)/POSITION_SCALE,z=v.rz/POSITION_SCALE;
            if(Math.abs(x)>32767 || Math.abs(y)>32767 || Math.abs(z)>32767) return 0;
            out[i*3]=round(x);out[i*3+1]=round(y);out[i*3+2]=round(z);
        }
        return POSITION_SCALE;
    }
    private static short round(double v) {return (short)(int)(v+(v<0?-0.5:0.5));}

    /** Selected-M3G CPU replay: same white ambient/directional + OMNI equation.
     * Per-vertex factors feed the original grey interpolators, no distance Fog. */
    public int shade(DirectX7 g,Vertex v,int nx,int ny,int nz) {
        int base=DirectX7.mode5Shade(nx,ny,nz);
        if(level<=0 || g.distX<=0 || g.distY<=0 || (nx==0 && ny==0 && nz==0)) return base;
        double x=eyeX(g,nx,ny,nz),y=eyeY(g,nx,ny,nz),z=eyeZ(g,nx,ny,nz);
        double normal=Math.sqrt(x*x+y*y+z*z);
        double dx=X-positionX(g,v),dy=Y-positionY(g,v),dz=Z-v.rz;
        double d2=dx*dx+dy*dy+dz*dz,dist=Math.sqrt(d2);
        if(normal==0 || dist==0) return base;
        double dot=Math.max(0,(x*dx+y*dy+z*dz)/(normal*dist));
        double point=level*dot/(1+d2/((double)HALF_DISTANCE*HALF_DISTANCE));
        return Math.min(256,base+(int)(point+0.5));
    }
}
