package code.Rendering;

import javax.microedition.m3g.*;
import code.Math.Matrix;

/** Mode 5 only: white ambient 25% + directional 75%, plus a short weapon OMNI.
 * Base packets use the engine normal frame; pulse packets use metric eye-space
 * positions and rotate both normals and lightdir into the same eye frame. */
final class M3GMode5Lighting {
    final Material material=new Material();
    private final Light ambient=new Light(),directional=new Light();
    private final Transform transform=new Transform();
    private final float[] matrix=new float[16];
    private Light omni;
    private Transform omniTransform;
    private int omniSlot=-1,omniLevel;
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

    void resetBinding() {installed=false;omniSlot=-1;}

    void disable(Graphics3D graphics) {
        if(installed) {graphics.resetLights();installed=false;omniSlot=-1;}
    }

    void apply(Graphics3D graphics,int lx,int ly,int lz,int pulse) {
        if(pulse==0 && omniSlot>=0) disable(graphics);
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
        if(pulse>0) {
            if(omni==null) {
                omni=new Light();omni.setMode(Light.OMNI);omni.setColor(0xffffff);
                omni.setAttenuation(1,0,1.0f/(Mode5WeaponLight.HALF_DISTANCE*Mode5WeaponLight.HALF_DISTANCE));
                omniTransform=new Transform();
                omniTransform.set(new float[]{1,0,0,Mode5WeaponLight.X,0,1,0,Mode5WeaponLight.Y,0,0,1,Mode5WeaponLight.Z,0,0,0,1});
            }
            if(omniLevel!=pulse || omniSlot<0) {omni.setIntensity(pulse/256.0f);omniLevel=pulse;}
            if(omniSlot<0) omniSlot=graphics.addLight(omni,omniTransform);
        }

    }

    // One bounded value-keyed entry, shared with the singleton native pipeline.
    // No Material/Light construction is needed by this CPU-only packing method.
    private static boolean packedReady,packedEye,packedFallback;
    private static int normalX,normalY,normalZ,fallbackX,fallbackY,fallbackZ;
    private static int c00,c01,c02,c10,c11,c12,c20,c21,c22;
    private static short packedX,packedY,packedZ;

    static void packNormals(RenderObject object,int count,short[] out,int lx,int ly,int lz,DirectX7 eye) {
        if(eye==null) {
            if(packedEye) {packedEye=false;packedReady=false;}
        } else {
            Matrix c=eye.camera;
            if(!packedEye || c00!=c.m00 || c01!=c.m01 || c02!=c.m02
                    || c10!=c.m10 || c11!=c.m11 || c12!=c.m12
                    || c20!=c.m20 || c21!=c.m21 || c22!=c.m22) {
                packedEye=true;packedReady=false;
                c00=c.m00;c01=c.m01;c02=c.m02;c10=c.m10;c11=c.m11;c12=c.m12;
                c20=c.m20;c21=c.m21;c22=c.m22;
            }
        }
        if(!packedReady || normalX!=object.nx || normalY!=object.ny || normalZ!=object.nz
                || (packedFallback && (fallbackX!=lx || fallbackY!=ly || fallbackZ!=lz))) {
            normalX=object.nx;normalY=object.ny;normalZ=object.nz;
            double x=object.nx,y=object.ny,z=object.nz;
            if(eye!=null) {
                x=Mode5WeaponLight.eyeX(eye,object.nx,object.ny,object.nz);
                y=Mode5WeaponLight.eyeY(eye,object.nx,object.ny,object.nz);
                z=Mode5WeaponLight.eyeZ(eye,object.nx,object.ny,object.nz);
            }
            double length=Math.sqrt(x*x+y*y+z*z);
            packedFallback=length==0;
            if(packedFallback) {
                fallbackX=lx;fallbackY=ly;fallbackZ=lz;
                // Never submit a zero vector for driver-side normalization. A normal
                // facing away from the source has exactly the ambient-only result.
                x=-(double)lx;y=-(double)ly;z=-(double)lz;
                length=Math.sqrt(x*x+y*y+z*z);
                if(length==0) {z=1;length=1;}
            }
            packedX=component(x*32767/length);packedY=component(y*32767/length);packedZ=component(z*32767/length);
            packedReady=true;
        }
        for(int i=0,j=0;i<count;i++,j+=3) {out[j]=packedX;out[j+1]=packedY;out[j+2]=packedZ;}
    }

    private static short component(double v) {return (short)(int)(v+(v<0?-0.5:0.5));}
}
