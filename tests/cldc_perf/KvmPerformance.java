package code.Rendering;

import code.Math.Matrix;

/** CPU microbenchmarks against the unchanged production JAR. No MIDP/M3G doubles. */
public final class KvmPerformance {
    private static DirectX7 renderer;
    private static Matrix left, right, product;
    private static Vertex[] vertices;
    private static short[] packed;
    private static Mode5WeaponLight light;
    private static FrameEffects effects;
    private static int sink;

    private static void setup(String task) {
        int width=240,height=320;
        if(task.equals("post_176x220")) {width=176;height=220;}
        renderer=new DirectX7(width,height);
        left=new Matrix();left.setRotY(37);left.setPosition(123,-45,-800);
        right=new Matrix();right.setRotX(23);right.setPosition(-50,60,70);
        product=new Matrix();
        vertices=new Vertex[1000];
        for(int i=0;i<vertices.length;i++) {
            Vertex v=new Vertex(i%101-50,i%73-36,-1000-i%500);
            v.transform(left);v.project(renderer);vertices[i]=v;
        }
        packed=new short[9];
        light=new Mode5WeaponLight();light.start(0);light.prepare(30);
        DirectX7.lightdirx=1000;DirectX7.lightdiry=-2000;DirectX7.lightdirz=4096;
        effects=new FrameEffects();
        byte[] mask=new byte[width*height];
        for(int i=0;i<mask.length;i++) {
            mask[i]=(byte)(i%256-128);renderer.display[i]=0xff8090a0;
        }
        effects.set(mask,false,true);
        // Check actual production methods before timing. No substitute renderer.
        if(Mode5WeaponLight.pack(renderer,vertices,3,packed)!=0.25f)
            throw new RuntimeException("metric packing failed");
        int base=DirectX7.mode5Shade(0,0,4096);
        if(light.shade(renderer,vertices[0],0,0,4096)<base)
            throw new RuntimeException("OMNI shade failed");
        renderer.display[0]=0xff8090a0;
        effects.render(renderer,null);
        if(renderer.display[0]!=0xff000000)
            throw new RuntimeException("post-effect mask failed");
    }

    private static int batch(String task,int repeats) {
        int checksum=0;
        if(task.equals("transform_project_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) {
                    vertices[i].transform(left);vertices[i].project(renderer);
                }
                checksum+=vertices[n%1000].sx;
            }
        } else if(task.equals("matrix_mul_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) product.mul(left,right);
                checksum+=product.m03;
            }
        } else if(task.equals("pack_triangles_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++)
                    checksum+=(int)(Mode5WeaponLight.pack(renderer,vertices,3,packed)*4);
            }
        } else if(task.equals("base_shade_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) checksum+=DirectX7.mode5Shade(0,0,4096);
            }
        } else if(task.equals("base_shade_varying_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) checksum+=DirectX7.mode5Shade(i%101-50,i%73-36,4096);
            }
        } else if(task.equals("omni_shade_varying_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) checksum+=light.shade(renderer,vertices[i],i%101-50,i%73-36,4096);
            }
        } else if(task.equals("omni_shade_1000")) {
            for(int n=0;n<repeats;n++) {
                for(int i=0;i<1000;i++) checksum+=light.shade(renderer,vertices[i],0,0,4096);
            }
        } else if(task.equals("post_240x320") || task.equals("post_176x220")) {
            // Deliberately excludes world rendering and LCDUI presentation.
            // Reuses the framebuffer; repeated multiplication eventually darkens it.
            for(int n=0;n<repeats;n++) {
                effects.render(renderer,null);checksum+=renderer.display[n%renderer.display.length];
            }
        } else throw new IllegalArgumentException(task);
        sink=checksum;
        return checksum;
    }

    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("probe")) {
            String name=args[1];
            try {Class.forName(name);System.out.println("AVAILABLE "+name);}
            catch(Throwable ex) {System.out.println("UNAVAILABLE "+name+": "+ex);}
            return;
        }
        if(args.length>0 && args[0].equals("health")) {
            double value=System.currentTimeMillis()%1000+1;
            if(Math.abs(Math.sqrt(value*value)-value)>0.0001)
                throw new RuntimeException("floating point failed");
            System.out.println("PASS KVM floating point / sqrt / clock");
            return;
        }
        String task=args[0];setup(task);
        // Calibrate beyond the millisecond clock resolution, then warm once more.
        int repeats=1;long elapsed;
        do {
            long start=System.currentTimeMillis();batch(task,repeats);
            elapsed=System.currentTimeMillis()-start;
            if(elapsed<250) repeats*=2;
        } while(elapsed<250 && repeats<1048576);
        batch(task,repeats);
        System.out.println("TASK "+task+" repeats="+repeats+" heap="+Runtime.getRuntime().totalMemory());
        for(int sample=0;sample<5;sample++) {
            long start=System.currentTimeMillis();batch(task,repeats);
            elapsed=System.currentTimeMillis()-start;
            if(elapsed<=0) throw new RuntimeException("clock resolution insufficient");
            System.out.println("SAMPLE "+elapsed+" "+repeats+" "+sink);
        }
    }
}
