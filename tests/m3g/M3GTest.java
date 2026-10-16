package code.Rendering;

import code.HUD.DeveloperMenu;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.CompositingMode;

public final class M3GTest {
    static void check(boolean ok, String message) {
        if(!ok) throw new AssertionError(message);
    }
    static Texture texture(int color) {
        Texture t = new Texture(new RawImage(new int[]{color,color,color,color},2,2),true,null);
        t.drawmode = 0;
        return t;
    }
    static Vertex vertex(int sx, int sy, int z) {
        Vertex v = new Vertex(sx, sy, z);
        v.sx = sx; v.sy = sy; v.rz = z;
        return v;
    }
    static Polygon3V triangle() {
        return new Polygon3V(vertex(2,2,-100),vertex(10,2,-100),vertex(2,10,-300),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
    }
    static DirectX7 renderer() {
        DirectX7 g = new DirectX7(32,32);
        g.clearDisplay(0x123456);
        g.setRenderer(DirectX7.M3G);
        return g;
    }
    static void submit(DirectX7 g, RenderObject o, Texture t, int depth) {
        o.sz = depth;
        g.addRenderObjectDT(o,t);
    }
    static final class Marker extends RenderObject {
        boolean verify;
        int calls;
        Marker(boolean verify) { this.verify=verify; }
        public void render(DirectX7 g,Texture t) {
            calls++;
            if(verify) check(g.display[0]==0xffff0000,"M3G readback missing before CPU");
            g.display[0]=0xff00ff00;
        }
        public void renderFast(DirectX7 g,Texture t) {render(g,t);}
        public boolean isVisible(int a,int b,int c,int d) {return true;}
    }
    static void queue(DirectX7 g,boolean verify) {
        submit(g,triangle(),texture(0xffff0000),-300);
        submit(g,new Marker(verify),null,-200);
        submit(g,triangle(),texture(0xff0000ff),-100);
    }
    public static void main(String[] args) {
        Main.mipMapping=false;
        Main.persQ=4;
        Main.fogQ=2;
        DirectX7 g=renderer();
        queue(g,true);
        g.render();
        check(g.isM3GActive(),"native initialization");
        check(Graphics3D.calls==2,"native rasterizer was not used");
        check(Graphics3D.before==0xff00ff00,"CPU pixels missing before M3G");
        check(g.display[0]==0xff0000ff,"last native segment not read back");
        check(Graphics3D.target==null,"target not released");
        int calls=Graphics3D.calls;
        g.render();
        check(Graphics3D.calls==calls,"render queue not reset");
        g.releaseRenderer();
        System.out.println("PASS hybrid ordering / framebuffer / queue cleanup");

        // A failure after the first native segment must replay the WHOLE queue,
        // including CPU objects, from the pre-render background.
        DirectX7 cpu=new DirectX7(32,32);
        cpu.clearDisplay(0x123456); queue(cpu,false); cpu.render();
        g=renderer(); queue(g,false);
        Graphics3D.failAt=Graphics3D.calls+2;
        g.render();
        check(!g.isM3GActive() && g.getRendererError()!=null,"failure not latched");
        for(int i=0;i<g.display.length;i++) check(g.display[i]==cpu.display[i],"rollback mismatch "+i);
        check(Graphics3D.target==null,"failure leaked target");
        calls=Graphics3D.calls;
        queue(g,false);g.render();
        check(Graphics3D.calls==calls,"failed backend retried every frame");
        Graphics3D.failAt=0;
        g.setRenderer(DirectX7.SOFTWARE);g.setRenderer(DirectX7.M3G);
        submit(g,triangle(),texture(0xffabcdef),0);g.render();
        check(g.isM3GActive(),"explicit retry did not recover");
        g.destroy();
        System.out.println("PASS native failure / complete rollback / retry / disposal");

        // Readback itself can fail: no half-M3G frame may reach the caller.
        g=renderer();submit(g,triangle(),texture(0xffff0000),0);
        cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);
        submit(cpu,triangle(),texture(0xffff0000),0);cpu.render();
        System.setProperty("readback.fail","true");g.render();System.clearProperty("readback.fail");
        for(int i=0;i<g.display.length;i++) check(g.display[i]==cpu.display[i],"readback rollback");
        check(Graphics3D.target==null,"readback leaked target");
        System.out.println("PASS readback failure rollback");

        g=renderer();submit(g,triangle(),texture(0xffff0000),0);
        System.setProperty("m3g.oom","true");g.render();System.clearProperty("m3g.oom");
        for(int i=0;i<g.display.length;i++)check(g.display[i]==cpu.display[i],"OOM rollback");
        check(Graphics3D.target==null,"OOM leaked target");
        System.out.println("PASS native OOM rollback");

        g=renderer();Texture t=texture(0xffaabbcc);
        int uploads=Graphics3D.uploads;
        submit(g,triangle(),t,-200);submit(g,triangle(),t,-100);g.render();
        check(Graphics3D.uploads==uploads+1,"texture cache not reused");
        t.rImg.img=new int[]{0xff111111,0xff111111,0xff111111,0xff111111};
        submit(g,triangle(),t,0);g.render();
        check(Graphics3D.uploads==uploads+2,"changed pixel array was not uploaded");
        g.resize(33,35);submit(g,triangle(),t,0);g.render();
        check(g.isM3GActive() && g.display.length==1155,"resize");
        g.destroy();
        System.out.println("PASS texture identity cache / replacement / resize");

        g=renderer();t=texture(0xff112233);
        t.animMIP=new Texture[]{texture(0xff445566)};t.updateAnimation();
        submit(g,triangle(),t,0);g.render();check(g.display[0]==0xff445566,"texture animation");
        Texture oldest=texture(0xff010101);submit(g,triangle(),oldest,0);g.render();
        for(int i=0;i<17;i++){submit(g,triangle(),texture(0xff000010+i),0);g.render();}
        uploads=Graphics3D.uploads;submit(g,triangle(),oldest,0);g.render();
        check(Graphics3D.uploads==uploads+1,"bounded FIFO cache did not evict");g.destroy();
        System.out.println("PASS texture animation / bounded cache eviction");

        g=renderer();t=texture(0x80ffffff);t.drawmode=4;
        submit(g,triangle(),t,0);g.render();
        check(Graphics3D.lastAppearance.comp.blend==CompositingMode.ALPHA,"alpha material");
        Polygon4V quad=new Polygon4V(vertex(2,2,-100),vertex(10,2,-100),vertex(10,10,-100),vertex(2,10,-100),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        submit(g,quad,texture(0xffdddddd),0);g.render();
        int[] expected={0,1,3,1,2,3};
        for(int i=0;i<6;i++)check(Graphics3D.lastIndices.indices[i]==expected[i],"quad diagonal");
        Polygon3V base=triangle();
        LightedPolygon3V lit=new LightedPolygon3V(base.a,base.b,base.c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);lit.la=20;lit.lb=100;lit.lc=200;
        t=texture(0xffffffff);t.drawmode=9;
        submit(g,lit,t,0);g.render();
        check((Graphics3D.lastVertices.colors.bytes[0]&255)==lit.getLight(lit.a,20,g),"vertex lighting");
        g.destroy();
        System.out.println("PASS alpha / quad diagonal / original light function");

        g=renderer();calls=Graphics3D.calls;
        t=texture(0xff888888);t.drawmode=7;submit(g,triangle(),t,0);g.render();
        check(Graphics3D.calls==calls,"unsupported glass material did not fall back");
        t=texture(0xff999999);g.flushAlpha=true;submit(g,triangle(),t,0);g.render();
        check(Graphics3D.calls==calls,"flushAlpha must remain software");
        g.flushAlpha=false;DeveloperMenu.renderPolygonsOverwrite=true;
        submit(g,triangle(),t,0);g.render();DeveloperMenu.renderPolygonsOverwrite=false;
        check(Graphics3D.calls==calls,"overwrite debug must remain software");
        g.destroy();
        System.out.println("PASS unsupported material / framebuffer alpha / debug fallback");

        // MeshImage uses a second, nested polygon queue, not the main one.
        Vertex a=new Vertex(-50,50,-100),b=new Vertex(50,50,-100),c=new Vertex(-50,-50,-100);
        Polygon3V p=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture mt=new MultyTexture();mt.textures=new Texture[]{texture(0xffbbbbbb)};
        Mesh mesh=new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p},mt);
        MeshImage image=new MeshImage(mesh);image.setMatrix(new Matrix());
        g=renderer();calls=Graphics3D.calls;g.addRenderObject(image);g.render();
        check(Graphics3D.calls==calls+1,"MeshImage bypassed native dispatch");g.destroy();
        System.out.println("PASS MeshImage nested queue");
        Marker counter=new Marker(false);counter.sz=-100;
        Mesh failingMesh=new Mesh(new Vertex[]{a,b,c},new RenderObject[]{p,counter},mt);
        MeshImage failingImage=new MeshImage(failingMesh);failingImage.setMatrix(new Matrix());
        g=renderer();Graphics3D.failAt=Graphics3D.calls+1;
        g.addRenderObject(failingImage);g.render();Graphics3D.failAt=0;
        check(counter.calls==1,"nested queue retained entries after native failure");g.destroy();
        System.out.println("PASS MeshImage failure queue cleanup");

        Vertex[] v={vertex(1,3,-100),vertex(31,5,-300),vertex(2,29,-1000)};
        short[] packed=new short[9];float[] work=new float[9];
        check(ProjectedGeometry.pack(v,3,16,16,21,true,packed,work)>0,"homogeneous pack");
        for(int i=0;i<3;i++) {
            check(Math.abs(16-packed[i*3]/(float)packed[i*3+2]-v[i].sx)<0.75,"project x");
            check(Math.abs(16+packed[i*3+1]/(float)packed[i*3+2]-v[i].sy)<0.75,"project y");
        }
        v[0].rz=1;check(ProjectedGeometry.pack(v,3,16,16,21,true,packed,work)==0,"near fallback");
        System.out.println("PASS projection reconstruction / near fallback");
    }
}
