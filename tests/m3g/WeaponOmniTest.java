package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Lighting/state/projection contracts with a fake API, not a device renderer. */
public final class WeaponOmniTest {
    static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Texture texture(int mode) {Texture t=M3GTest.texture(0xff8090a0);t.drawmode=(byte)mode;return t;}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static void near(int a,int b,int tol,String why) {
        for(int s=0;s<24;s+=8) check(Math.abs(((a>>s)&255)-((b>>s)&255))<=tol,why);
    }
    private static void setPulse(DirectX7 g,Mode5WeaponLight p) throws Exception {
        Field f=DirectX7.class.getDeclaredField("mode5WeaponLight");f.setAccessible(true);f.set(g,p);
    }
    private static RenderObject face(int kind) {
        RenderObject p=NativeLightingTest.polygon(kind);p.nx=p.ny=0;p.nz=4096;return p;
    }
    private static void projection(Graphics3D.Draw d,DirectX7 g,Vertex[] v) {
        int count=v.length;
        for(int i=0;i<count;i++) {
            double x=d.positions[i*3]*d.scale,y=d.positions[i*3+1]*d.scale,z=d.positions[i*3+2]*d.scale;
            float[] m=d.projection;double w=m[12]*x+m[13]*y+m[14]*z+m[15];
            double sx=((m[0]*x+m[1]*y+m[2]*z+m[3])/w+1)*g.width/2;
            double sy=(1-(m[4]*x+m[5]*y+m[6]*z+m[7])/w)*g.height/2;
            check(Math.abs(sx-v[i].sx)<0.15 && Math.abs(sy-v[i].sy)<0.15,"metric projection moved an endpoint");
            check(z==v[i].rz,"metric depth is polygon-relative");
        }
    }
    public static void main(String[] args) throws Exception {
        Main.mipMapping=false;DirectX7.lightdirx=DirectX7.lightdiry=0;DirectX7.lightdirz=-4096;
        Mode5WeaponLight pulse=new Mode5WeaponLight();pulse.prepare(1000);check(pulse.level==0,"pulse without shot");
        pulse.start(1000);pulse.prepare(1000);check(pulse.level==256,"peak");
        pulse.prepare(1060);check(pulse.level==128,"linear 60ms fade");
        pulse.prepare(1120);check(pulse.level==0,"pulse after 120ms");
        pulse.start(1100);pulse.prepare(1099);check(pulse.level==0,"backward clock retained pulse");
        pulse.start(1000);pulse.start(1050);pulse.prepare(1050);check(pulse.level==256,"automatic weapon restart");
        DirectX7 software=new DirectX7(32,32);software.startMuzzleGlow();check(software.getMode5WeaponLight()==null,"Software acquired OMNI");software.destroy();

        for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) for(int quality:new int[]{0,1,2}) for(boolean hw:new boolean[]{false,true}) {
            Main.persQ=pers;Main.fogQ=quality;Main.m3gPerspective=hw;
            DirectX7 g=M3GTest.renderer();g.startMuzzleGlow();pulse=g.getMode5WeaponLight();
            check(pulse!=null,"real weapon hook did not start pulse");
            M3GSceneRenderer r=new M3GSceneRenderer();RenderObject p=face(kind);Texture t=texture(5);
            int start=Graphics3D.calls;check(r.draw(g,p,t,false),"OMNI polygon unsupported");r.finish(g);
            Graphics3D.Draw d=last();
            check(Graphics3D.calls==start+1 && d.lit && d.lights==3 && d.omniIntensity==1,"OMNI is not a native single pass");
            check(d.ambientIntensity==0.25f && d.directionalIntensity==0.75f && !d.fog,"base lighting changed");
            check(Arrays.equals(d.omniPosition,new float[]{40,-40,-120}) && d.omniQuadratic==0.000001f,"muzzle pose/attenuation");
            for(byte c:d.colors) check((c&255)==255,"CPU baked OMNI instead of native light");
            check(d.perspective==(pers>0&&hw),"affine or HW preference lost during pulse");
            Vertex a=p instanceof Polygon3V?((Polygon3V)p).a:((Polygon4V)p).a;
            int shade=pulse.shade(g,a,p.nx,p.ny,p.nz),expected=0;
            for(int s=0;s<24;s+=8) expected|=((((0x8090a0>>s)&255)*shade)>>8)<<s;
            near(g.display[0],expected,2,"CPU/native OMNI equation");
            if(p instanceof Polygon3V) {Polygon3V q=(Polygon3V)p;projection(d,g,new Vertex[]{q.a,q.b,q.c});}
            else {Polygon4V q=(Polygon4V)p;projection(d,g,new Vertex[]{q.a,q.b,q.c,q.d});}
            r.dispose();g.destroy();
        }
        System.out.println("PASS weapon OMNI 120ms/retrigger/clock / real shot hook / native single pass / 6 polygon classes / affine+perspective+fogQ+HW / metric endpoint+depth / CPU equation");

        // FOV changes and camera rotations do not turn the muzzle into a screen
        // radius or change ambient/directional. World normals rotate to eye space.
        for(int fov:new int[]{40,74,110}) for(int yaw:new int[]{0,90,180,270}) {
            DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);g.updateFov(fov);
            Matrix c=new Matrix();c.setRotY(yaw);g.setCamera(c);g.startMuzzleGlow();
            M3GSceneRenderer r=new M3GSceneRenderer();RenderObject p=face(0);check(r.draw(g,p,texture(5),false),"FOV/rotation metric pack");r.finish(g);
            Polygon3V q=(Polygon3V)p;projection(last(),g,new Vertex[]{q.a,q.b,q.c});
            if(yaw==90) check(last().normals[0]==-32767 && last().normals[1]==0 && last().normals[2]==0,"world normal was not rotated into eye space");
            check(last().omniPosition[0]==40 && last().omniPosition[2]==-120,"muzzle is world-fixed instead of camera-attached");
            r.dispose();g.destroy();
        }
        DirectX7 g=M3GTest.renderer();g.startMuzzleGlow();pulse=g.getMode5WeaponLight();
        Vertex near=new Vertex(),far=new Vertex();near.sx=far.sx=g.centreX;near.sy=far.sy=g.centreY;near.rz=-500;far.rz=-4000;
        check(pulse.shade(g,near,0,0,4096)>pulse.shade(g,far,0,0,4096),"distance falloff absent");
        check(pulse.shade(g,near,0,0,-4096)==DirectX7.mode5Shade(0,0,-4096),"back-facing wall lit");
        check(pulse.shade(g,near,0,0,0)==64,"degenerate normal received point light");
        M3GSceneRenderer r=new M3GSceneRenderer();Texture t=texture(5);int first=Graphics3D.history.size();
        check(r.draw(g,face(0),t,false),"snapshot peak");pulse.level=128;check(r.draw(g,face(0),t,false),"snapshot fade");r.finish(g);
        check(Graphics3D.history.size()==first+2 && Graphics3D.history.get(first).omniIntensity==1 && last().omniIntensity==0.5f,"pending pulse strength changed");
        pulse.stop();check(r.draw(g,face(0),t,false),"pulse ended");r.finish(g);check(last().lights==2 && last().omniIntensity==0,"stale OMNI after expiration");
        r.dispose();g.destroy();

        // Same warm texture, same strength: 100 lit triangles still batch to 2.
        g=M3GTest.renderer();g.startMuzzleGlow();r=new M3GSceneRenderer();t=texture(5);first=Graphics3D.calls;
        int arrays=VertexArray.created;
        for(int i=0;i<100;i++) check(r.draw(g,face(0),t,false),"batched pulse");r.finish(g);
        check(Graphics3D.calls==first+2 && last().scale==0.25f,"OMNI batching/metric scale");
        arrays=VertexArray.created;first=Graphics3D.calls;int uploads=Graphics3D.uploads;
        for(int i=0;i<100;i++) r.draw(g,face(0),t,false);r.finish(g);
        check(Graphics3D.calls==first+2 && VertexArray.created==arrays && Graphics3D.uploads==uploads,"warm OMNI allocations/uploads");
        for(int mode:new int[]{0,1,2,7,9,10,13}) {
            Texture unlit=texture(mode);DirectX7.fDist=1000;check(r.draw(g,face(0),unlit,false),"unlit packet");r.finish(g);
            check(!last().lit && last().lights==0 && last().projection[15]==0,"point light/camera leaked to mode "+mode);
            r.draw(g,face(0),t,false);r.finish(g);
        }
        r.dispose();g.destroy();
        System.out.println("PASS metric/FOV/camera normals / distance+backface / pending pulse snapshot / expiry / 100 triangles->2 draws / warm resources / other material light+camera isolation");

        // Degenerate faces never switch their legacy UV weight just because
        // another face receives an OMNI pulse.
        for(int pers:new int[]{0,4}) for(int quality:new int[]{0,2}) {
            Main.persQ=pers;Main.fogQ=quality;
            DirectX7 a=M3GTest.renderer(),b=M3GTest.renderer();a.startMuzzleGlow();
            RenderObject za=face(0),zb=face(0);za.nx=za.ny=za.nz=zb.nx=zb.ny=zb.nz=0;
            Texture pattern=new Texture(new RawImage(new int[]{0xff123456,0xff90b0c0,0xffb09070,0xff654321},2,2),true,null);pattern.drawmode=5;
            a.drawSoftwareObject(za,pattern,false);b.drawSoftwareObject(zb,pattern,false);
            check(Arrays.equals(a.display,b.display),"pulse changed zero-normal CPU UV/ambient");a.destroy();b.destroy();
        }
        // Failed frames replay the frozen shot, including partial native output.
        for(int kind=0;kind<6;kind++) for(int pers:new int[]{0,4}) for(int quality:new int[]{0,2})
            for(String failure:new String[]{"omni.fail","draw","readback.fail"}) {
                Main.persQ=pers;Main.fogQ=quality;g=M3GTest.renderer();g.startMuzzleGlow();t=texture(5);
                RenderObject a=face(kind),b=face(kind),ordinary=face(0);Texture opaque=texture(0);
                M3GTest.submit(g,ordinary,opaque,-100);M3GTest.submit(g,a,t,0);
                if(failure.equals("draw")) Graphics3D.failAt=Graphics3D.calls+2;else System.setProperty(failure,"true");
                g.render();Graphics3D.failAt=0;System.clearProperty(failure);
                check(!g.isM3GActive() && Graphics3D.target==null && Graphics3D.lights.size()==0,"pulse failure leaked target/lights");
                pulse=g.getMode5WeaponLight();check(pulse!=null,"native failure discarded frozen pulse before replay");
                DirectX7 cpu=M3GTest.renderer();setPulse(cpu,pulse);cpu.drawSoftwareObject(ordinary,opaque,false);cpu.drawSoftwareObject(b,t,false);
                check(Arrays.equals(cpu.display,g.display),"OMNI frame replay mismatch "+failure);
                g.startMuzzleGlow();check(g.getMode5WeaponLight()!=null,"new shots stopped on selected-M3G CPU fallback");
                cpu.destroy();g.destroy();
            }
        // Out-of-short-range geometry must not overflow or kill the backend.
        g=M3GTest.renderer();g.startMuzzleGlow();Polygon3V huge=(Polygon3V)face(0);huge.a.rz=huge.b.rz=huge.c.rz=-20000;
        M3GTest.submit(g,huge,texture(5),0);g.render();check(g.isM3GActive(),"large geometry disabled renderer instead of CPU material fallback");
        g.startMuzzleGlow();first=Graphics3D.history.size();M3GTest.submit(g,face(0),texture(5),0);g.renderGameFrame(null,true,false,null,0,null);
        check(g.getMode5WeaponLight()==null && Graphics3D.history.get(first).lights==2 && last().lights==0,"dead player retained point light");
        g.setRenderer(DirectX7.SOFTWARE);check(g.getMode5WeaponLight()==null,"renderer switch retained pulse");g.destroy();
        System.out.println("PASS native OMNI setup/draw/readback atomic frozen-pulse recovery / selected-M3G CPU future shots / large-coordinate fallback / death+backend reset");
    }
}
