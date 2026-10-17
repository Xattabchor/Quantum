package code.Rendering;

import javax.microedition.m3g.*;

/** Mode 5 only: white ambient 25% + directional 75%, no positional lights.
 * Normals and lightdir share the engine's normal frame. Projected positions
 * are deliberately not used to derive light vectors or camera-dependent light. */
final class M3GMode5Lighting {
    final Material material=new Material();
    private final Light ambient=new Light(),directional=new Light();
    private final Transform transform=new Transform();
    private final float[] matrix=new float[16];
    private boolean installed,ready;
    private int x,y,z,directionalSlot;

    M3GMode5Lighting() {
        material.setColor(Material.AMBIENT | Material.DIFFUSE,0xffffffff);
        material.setColor(Material.EMISSIVE | Material.SPECULAR,0);
        material.setShininess(0);
        material.setVertexColorTrackingEnable(false);
        ambient.setMode(Light.AMBIENT);ambient.setColor(0xffffff);ambient.setIntensity(0.25f);
        directional.setMode(Light.DIRECTIONAL);directional.setColor(0xffffff);
    }

    void resetBinding() {installed=false;}

    void disable(Graphics3D graphics) {
        if(installed) {graphics.resetLights();installed=false;}
    }

    void apply(Graphics3D graphics,int lx,int ly,int lz) {
        boolean changed=!ready || x!=lx || y!=ly || z!=lz;
        if(changed) {
            ready=true;x=lx;y=ly;z=lz;
            double length=Math.sqrt((double)lx*lx+(double)ly*ly+(double)lz*lz);
            directional.setIntensity(length>0?0.75f:0);
            float zx=0,zy=0,zz=1;
            if(length>0) {zx=(float)(lx/length);zy=(float)(ly/length);zz=(float)(lz/length);}
            // Orthonormal basis: local +Z points TOWARDS the source, so the
            // DIRECTIONAL light's local -Z rays point opposite to lightdir.
            float xx=zz,xy=0,xz=-zx;
            if(Math.abs(zy)>0.99f) {xx=0;xy=zz;xz=-zy;}
            float inv=(float)(1.0/Math.sqrt(xx*xx+xy*xy+xz*xz));
            xx*=inv;xy*=inv;xz*=inv;
            matrix[0]=xx;matrix[1]=zy*xz-zz*xy;matrix[2]=zx;
            matrix[4]=xy;matrix[5]=zz*xx-zx*xz;matrix[6]=zy;
            matrix[8]=xz;matrix[9]=zx*xy-zy*xx;matrix[10]=zz;matrix[15]=1;
            transform.set(matrix);
        }
        if(!installed) {
            // Include partially installed state in dispose/recovery cleanup.
            installed=true;
            graphics.addLight(ambient,null);
            directionalSlot=graphics.addLight(directional,transform);
        } else if(changed) graphics.setLight(directionalSlot,directional,transform);
    }

    static void packNormals(RenderObject object,int count,short[] out,int lx,int ly,int lz) {
        double x=object.nx,y=object.ny,z=object.nz;
        double length=Math.sqrt(x*x+y*y+z*z);
        if(length==0) {
            // Never submit a zero vector for driver-side normalization. A normal
            // facing away from the source has exactly the ambient-only result.
            x=-(double)lx;y=-(double)ly;z=-(double)lz;
            length=Math.sqrt(x*x+y*y+z*z);
            if(length==0) {z=1;length=1;}
        }
        short nx=component(x*32767/length),ny=component(y*32767/length),nz=component(z*32767/length);
        for(int i=0;i<count;i++) {out[i*3]=nx;out[i*3+1]=ny;out[i*3+2]=nz;}
    }

    private static short component(double v) {return (short)(int)(v+(v<0?-0.5:0.5));}
}
