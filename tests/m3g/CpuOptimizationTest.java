package code.Rendering;

import code.Math.Matrix;
import java.util.Arrays;
import java.util.Random;

/** Regression comparisons with the restored pre-KVM CPU equations. */
public final class CpuOptimizationTest {
    private static void check(boolean ok,String why) {M3GTest.check(ok,why);}
    private static int base(int nx,int ny,int nz) {
        double n=Math.sqrt((double)nx*nx+(double)ny*ny+(double)nz*nz);
        double l=Math.sqrt((double)DirectX7.lightdirx*DirectX7.lightdirx
                +(double)DirectX7.lightdiry*DirectX7.lightdiry+(double)DirectX7.lightdirz*DirectX7.lightdirz);
        double dot=0;
        if(n>0 && l>0) dot=((double)nx*DirectX7.lightdirx+(double)ny*DirectX7.lightdiry+(double)nz*DirectX7.lightdirz)/(n*l);
        return 64+(int)(192*Math.max(0,Math.min(1,dot))+0.5);
    }
    private static double px(DirectX7 g,Vertex v) {
        return ((double)v.sx-g.centreX)*((double)g.distX-v.rz)/g.distX;
    }
    private static double py(DirectX7 g,Vertex v) {
        return ((double)g.centreY-v.sy)*((double)g.distX-v.rz)/g.distY;
    }
    private static float pack(DirectX7 g,Vertex[] vertices,int count,short[] out) {
        if(g.distX<=0 || g.distY<=0) return 0;
        for(int i=0;i<count;i++) {
            Vertex v=vertices[i];if(v.rz>0) return 0;
            double x=px(g,v)/0.25f,y=py(g,v)/0.25f,z=v.rz/0.25f;
            if(Math.abs(x)>32767 || Math.abs(y)>32767 || Math.abs(z)>32767) return 0;
            out[i*3]=(short)(int)(x+(x<0?-0.5:0.5));
            out[i*3+1]=(short)(int)(y+(y<0?-0.5:0.5));
            out[i*3+2]=(short)(int)(z+(z<0?-0.5:0.5));
        }
        return 0.25f;
    }
    private static int shade(Mode5WeaponLight light,DirectX7 g,Vertex v,int nx,int ny,int nz) {
        int b=base(nx,ny,nz);
        if(light.level<=0 || g.distX<=0 || g.distY<=0 || (nx==0&&ny==0&&nz==0)) return b;
        Matrix c=g.camera;
        double x=(c.m00*(double)nx+c.m10*(double)ny+c.m20*(double)nz)/Matrix.FP;
        double y=(c.m01*(double)nx+c.m11*(double)ny+c.m21*(double)nz)/Matrix.FP;
        double z=(c.m02*(double)nx+c.m12*(double)ny+c.m22*(double)nz)/Matrix.FP;
        double normal=Math.sqrt(x*x+y*y+z*z);
        double dx=40-px(g,v),dy=-40-py(g,v),dz=-120-v.rz;
        double d2=dx*dx+dy*dy+dz*dz,dist=Math.sqrt(d2);
        if(normal==0 || dist==0) return b;
        double dot=Math.max(0,(x*dx+y*dy+z*dz)/(normal*dist));
        return Math.min(256,b+(int)(light.level*dot/(1+d2/1000000.0)+0.5));
    }
    private static int effect(int p,int v,int tint) {
        int r=(((p>>16)&255)*v)>>8,g=(((p>>8)&255)*v)>>8,b=((p&255)*v)>>8;
        return (p&0xff000000)|((r*((tint>>16)&255)/255)<<16)
                |((g*((tint>>8)&255)/255)<<8)|(b*(tint&255)/255);
    }
    private static void effects() throws Exception {
        DirectX7 g=new DirectX7(256,256);FrameEffects f=new FrameEffects();
        byte[] mask=new byte[g.display.length];
        // Every source-byte/coefficient pair, with distinct alpha/R/G/B.
        for(int i=0;i<mask.length;i++) mask[i]=(byte)((i/256)-128);
        for(boolean dead:new boolean[]{false,true}) for(boolean damage:new boolean[]{false,true}) {
            f.set(mask,dead,damage);
            int[] expected=new int[g.display.length];
            for(int i=0;i<expected.length;i++) {
                int p=(i<<24)|((i&255)<<16)|(((255-i)&255)<<8)|((i*37)&255);
                g.display[i]=p;expected[i]=effect(p,mask[i&~1]+128,f.tint);
            }
            f.render(g,null);check(Arrays.equals(expected,g.display),"exhaustive effect bytes/alpha/tint");
        }
        Random random=new Random(9701);
        for(int size=0;size<37;size++) for(int length=0;length<40;length++) {
            g.display=new int[size];mask=new byte[length];random.nextBytes(mask);
            for(int flags=0;flags<4;flags++) {
                f.set(mask,(flags&1)!=0,(flags&2)!=0);
                int[] expected=new int[size];
                for(int i=0;i<size;i++) {
                    int p=random.nextInt();g.display[i]=p;
                    int bound=Math.min(size,length),coefficient=256;
                    if(i<bound) coefficient=mask[i<(bound&~7)?i&~1:i]+128;
                    expected[i]=effect(p,coefficient,f.tint);
                }
                f.render(g,null);check(Arrays.equals(expected,g.display),"short/odd/oversized mask or display / tint invalidation");
            }
        }
        // The restored implementation is arithmetic-only, without a CPU LUT.
        f=new FrameEffects();
        for(int tint:new int[]{0xffffffff,0xff4040,0x802020,0x808080,0xffffff,0x123456,0x80123456}) {
            f.set(null,false,true);f.tint=tint;
            g.display=new int[]{0x12345678,0xffabcdef,0,0xffffffff};
            int[] expected=g.display.clone();
            for(int i=0;i<expected.length;i++) expected[i]=effect(expected[i],256,tint);
            f.render(g,null);check(Arrays.equals(expected,g.display),"no mask/custom tint arithmetic");
        }
        g.destroy();
    }
    public static void main(String[] args) throws Exception {
        effects();Random random=new Random(1841);DirectX7 g=new DirectX7(240,320);
        Mode5WeaponLight light=new Mode5WeaponLight();
        Vertex[] vertices={new Vertex(),new Vertex(),new Vertex(),new Vertex()};
        short[] expected=new short[12],actual=new short[12];
        for(int trial=0;trial<12000;trial++) {
            g.distX=1+random.nextInt(2000);g.distY=1+random.nextInt(2000);
            g.centreX=random.nextInt(400);g.centreY=random.nextInt(400);
            if(trial%71==0) g.distX=0;
            if(trial%73==0) g.distY=-1;
            for(Vertex v:vertices) {
                v.sx=random.nextInt(20000)-10000;v.sy=random.nextInt(20000)-10000;
                v.rz=-random.nextInt(10000);
                if(trial%3==0) {v.sx=random.nextInt(400);v.sy=random.nextInt(400);v.rz=-random.nextInt(500);}
                if(trial%13==0) {v.sx=random.nextInt();v.sy=random.nextInt();v.rz=random.nextInt();}
                if(trial%29==0) v.rz=-8191;
                if(trial%31==0) v.rz=-8192;
            }
            Arrays.fill(expected,(short)123);Arrays.fill(actual,(short)123);
            int count=trial%2+3;
            check(pack(g,vertices,count,expected)==Mode5WeaponLight.pack(g,vertices,count,actual)
                    && Arrays.equals(expected,actual),"exact metric pack/range/partial output");
            // Exercise repeated calls, changing normal, changing light, mutable camera,
            // FOV/position/pulse, extreme integer inputs and zero vectors.
            DirectX7.lightdirx=(short)random.nextInt();DirectX7.lightdiry=(short)random.nextInt();DirectX7.lightdirz=(short)random.nextInt();
            if(trial%5==0) DirectX7.lightdirx=DirectX7.lightdiry=DirectX7.lightdirz=0;
            int nx=random.nextInt(),ny=random.nextInt(),nz=random.nextInt();
            if(trial%7==0) nx=ny=nz=0;
            light.start(0);light.prepare(trial%125);
            for(int changed=0;changed<16;changed++) {
                int b=base(nx,ny,nz);
                check(DirectX7.mode5Shade(nx,ny,nz)==b && DirectX7.mode5Shade(nx,ny,nz)==b,"base shade/reference");
                check(light.shade(g,vertices[0],nx,ny,nz)==shade(light,g,vertices[0],nx,ny,nz),"exact restored OMNI");
                int value=random.nextInt();
                switch(changed) {
                    case 0:g.camera.m00=value;break;case 1:g.camera.m01=value;break;case 2:g.camera.m02=value;break;
                    case 3:g.camera.m10=value;break;case 4:g.camera.m11=value;break;case 5:g.camera.m12=value;break;
                    case 6:g.camera.m20=value;break;case 7:g.camera.m21=value;break;case 8:g.camera.m22=value;break;
                    case 9:DirectX7.lightdirx=(short)~DirectX7.lightdirx;break;
                    case 10:DirectX7.lightdiry=(short)~DirectX7.lightdiry;break;
                    case 11:DirectX7.lightdirz=(short)~DirectX7.lightdirz;break;
                    case 12:nx=~nx;break;case 13:ny=~ny;break;case 14:nz=~nz;break;
                }
            }
        }
        g.destroy();
        System.out.println("PASS exact restored pre-KVM CPU reference: exhaustive effect bytes / short+odd masks / alpha+tint / restored arithmetic / 12000 metric packs / changing normal+all 9 camera entries+light / extreme inputs");
    }
}
