package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.Transform;

/** API work/allocation contracts, NOT an ARM9 timing or GPU raster benchmark. */
public final class Arm9PreparationTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Field field(String name) throws Exception {
        Field f=M3GSceneRenderer.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static class CountingVector extends Vector {
        int reads;
        public synchronized Object elementAt(int i) {reads++;return super.elementAt(i);}
    }
    // Inherited implementation deliberately exercises the conservative wrapper
    // route, reproducing the old wrapper barrier without changing production.
    private static class UnknownImage extends MeshImage {UnknownImage(Mesh m){super(m);}}
    private static class UnknownMesh extends Mesh {
        UnknownMesh(Vertex[] v,RenderObject[] p,MultyTexture t){super(v,p,t);}
    }
    private static class CpuMark extends RenderObject {
        int calls;
        public boolean isVisible(int x,int y,int w,int h){sz=-1200;return true;}
        public void renderFast(DirectX7 g,Texture t){render(g,t);}
        public void render(DirectX7 g,Texture t){
            check(Graphics3D.target==null,"CPU child ran with bound native target");
            calls++;g.display[0]=0xff123456;
        }
    }
    private static MeshImage image(Texture t,int kind,CpuMark mark) {
        Vertex[] v={new Vertex(-30,30,-300),new Vertex(30,30,-300),new Vertex(-30,-30,-300)};
        Polygon3V p=new Polygon3V(v[0],v[1],v[2],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture mt=new MultyTexture();mt.textures=new Texture[]{t};
        RenderObject[] faces=mark==null?new RenderObject[]{p}:new RenderObject[]{p,mark};
        Mesh m=kind==2?new UnknownMesh(v,faces,mt):new Mesh(v,faces,mt);
        return kind==1?new UnknownImage(m):new MeshImage(m);
    }
    private static void queue(DirectX7 g,MeshImage[] images) {
        g.beginFrameStats();M3GTest.submit(g,new SolidSky(0xabcdef),null,0);g.renderBackground();
        for(int i=0;i<images.length;i++) {
            Matrix m=new Matrix();m.setPosition(i-10,i%3,-i);images[i].setMatrix(m);
            check(images[i].isVisible(0,0,g.width,g.height),"mesh fixture invisible");
            M3GTest.submit(g,images[i],null,i);
        }
    }
    // Compare the ordered submitted triangle stream independently of packet
    // boundaries and homogeneous position scale. No triangle rasterizer here.
    private static String stream(int first) {
        StringBuffer s=new StringBuffer();int vertices=0;
        for(int n=first;n<Graphics3D.history.size();n++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(n);if(!d.textured)continue;
            check(!d.fog && d.coords!=null,"unexpected test material");
            for(int k=0;k<d.indices.length;k++) {
                int v=d.indices[k],z=d.positions[v*3+2];
                s.append(d.positions[v*3]/(float)-z).append(',').append(d.positions[v*3+1]/(float)-z).append(',');
                s.append(d.coords[v*2]).append(',').append(d.coords[v*2+1]).append(',');
                for(int c=0;c<4;c++)s.append(d.colors[v*4+c]).append(',');
                s.append(d.blend).append(',').append(d.function).append(',').append(d.cull).append(',').append(d.winding).append(';');vertices++;
            }
        }
        check(vertices==60,"20 mesh fixture lost faces: "+vertices);return s.toString();
    }
    private static void meshes() {
        String reference=null;
        for(int kind=1;kind>=0;kind--) {
            DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
            Texture t=M3GTest.texture(0xff778899);MeshImage[] images=new MeshImage[20];
            for(int i=0;i<images.length;i++)images[i]=image(t,kind,null);
            queue(g,images);g.renderGameFrameImage(null,false,false,null,0,null);
            queue(g,images);int first=Graphics3D.history.size(),reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls;
            g.renderGameFrameImage(null,false,false,null,0,null);
            int barriers=kind==1?20:0,draws=kind==1?21:2;
            check(g.isM3GActive() && g.getM3GCpuBarriers()==barriers && g.getM3GDrawCalls()==draws
                    && g.getM3GTextureUploads()==0,"mesh dispatch counters B="+g.getM3GCpuBarriers()+" D="+g.getM3GDrawCalls());
            check(Image.getRGBCalls-reads==barriers && Graphics.drawRGBCalls-copies==barriers,"wrapper framebuffer transfer count");
            String actual=stream(first);if(reference==null)reference=actual;else check(reference.equals(actual),"mesh batching changed ordered vertices/UV/RGBA/state");
            g.destroy();
        }
        System.out.println("PASS ARM9 20 MeshImage warm: conservative D21/B20/read20/copy20 -> exact D2/B0/read0/copy0/T0; ordered stream equal");
        for(int kind=0;kind<=2;kind++) {
            DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff778899);
            CpuMark mark=new CpuMark();MeshImage[] images={image(t,kind,mark)};
            queue(g,images);g.renderGameFrameImage(null,false,false,null,0,null);
            queue(g,images);int before=mark.calls;g.renderGameFrameImage(null,false,false,null,0,null);
            check(g.isM3GActive() && mark.calls==before+1 && g.getM3GCpuBarriers()>=1,"mixed CPU child lost its barrier / subclass bypass");
            g.destroy();
        }
        DirectX7 unknown=M3GTest.renderer();
        MeshImage[] custom={image(M3GTest.texture(0xff778899),2,null)};
        queue(unknown,custom);unknown.renderGameFrameImage(null,false,false,null,0,null);
        queue(unknown,custom);unknown.renderGameFrameImage(null,false,false,null,0,null);
        check(unknown.isM3GActive() && unknown.getM3GCpuBarriers()==1,"unknown Mesh subclass bypassed conservative wrapper barrier");
        unknown.destroy();
        // Fail after the first 64-face native packet, then compare a complete
        // CPU replay of all wrappers with the original Software render.
        Texture t=M3GTest.texture(0xff778899);MeshImage[] images=new MeshImage[70];
        for(int i=0;i<images.length;i++)images[i]=image(t,0,null);
        DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);DirectX7 cpu=new DirectX7(240,320);
        queue(cpu,images);cpu.renderSoftware();queue(g,images);
        Graphics3D.failAt=Graphics3D.calls+3;
        try {g.renderGameFrameImage(null,false,false,null,0,null);}finally{Graphics3D.failAt=0;}
        check(!g.isM3GActive() && Graphics3D.target==null && Arrays.equals(cpu.display,g.display),"multi-wrapper partial native failure lost atomic replay");
        g.destroy();cpu.destroy();
        System.out.println("PASS ARM9 mixed native/CPU children / unknown wrapper+mesh conservative dispatch / multi-packet atomic replay");
    }
    private static void projection() {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer r=new M3GSceneRenderer();
        r.bind(g);r.finish(g);javax.microedition.m3g.Camera camera=Graphics3D.camera;
        int created=Transform.created,sets=javax.microedition.m3g.Camera.generics;
        for(int i=0;i<50;i++) {
            Graphics3D.camera=new javax.microedition.m3g.Camera();r.bind(g);
            check(Graphics3D.camera==camera,"world camera not restored after helper");r.finish(g);
        }
        check(Transform.created==created && javax.microedition.m3g.Camera.generics==sets,"warm rebind allocated/redefined projection");
        g.centreX++;r.bind(g);r.finish(g);check(javax.microedition.m3g.Camera.generics==++sets,"centreX didn't invalidate projection");
        check(camera.projection.matrix[2]==-(2.0f*g.centreX/g.width-1),"centreX projection mismatch");
        g.centreY--;r.bind(g);r.finish(g);check(javax.microedition.m3g.Camera.generics==++sets,"centreY didn't invalidate projection");
        check(camera.projection.matrix[6]==-(1-2.0f*g.centreY/g.height),"centreY projection mismatch");
        g.resize(240,320);r.bind(g);r.finish(g);check(javax.microedition.m3g.Camera.generics==++sets,"resize didn't invalidate projection");
        check(camera.projection.matrix[0]==2.0f/240 && camera.projection.matrix[5]==2.0f/320,"resized projection mismatch");
        r.dispose();r.bind(g);r.finish(g);check(javax.microedition.m3g.Camera.generics==++sets && Transform.created==created,"dispose projection invalidation/reuse");
        r.dispose();g.destroy();System.out.println("PASS ARM9 projection reuse across 50 rebinds / camera restoration / centre+resize+dispose invalidation");
    }
    private static void textures() throws Exception {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer r=new M3GSceneRenderer();
        CountingVector cache=new CountingVector();field("textures").set(r,cache);
        RawImage[] raw=new RawImage[16];Texture2D oldest=null,last=null;
        for(int i=0;i<raw.length;i++) {raw[i]=M3GTest.texture(0xff123400+i).rImg;last=r.texture(g,raw[i],0);if(i==0)oldest=last;}
        cache.reads=0;int uploads=Graphics3D.uploads;
        for(int i=0;i<100;i++)check(r.texture(g,raw[15],0)==last,"last texture identity");
        check(cache.reads==0 && Graphics3D.uploads==uploads,"last texture still scans synchronized Vector / uploads");
        check(r.texture(g,raw[0],0)==oldest && cache.reads>0,"nonadjacent texture didn't scan cache");
        cache.reads=0;for(int i=0;i<100;i++)r.texture(g,raw[0],0);
        check(cache.reads==0,"new last texture not remembered");
        System.setProperty("texture.upload.fail","true");
        try {r.texture(g,M3GTest.texture(0xffaabbcc).rImg,0);throw new AssertionError("texture OOM not injected");}
        catch(OutOfMemoryError expected){}finally{System.clearProperty("texture.upload.fail");}
        check(r.texture(g,raw[0],0)!=oldest && Graphics3D.uploads==uploads+1,"evicted last pointer resurrected after failed replacement");
        Texture2D previous=r.texture(g,raw[0],0);raw[0].img=raw[0].img.clone();
        check(r.texture(g,raw[0],0)!=previous,"pixel array replacement reused stale native texture");
        previous=r.texture(g,raw[0],0);check(r.texture(g,raw[0],1)!=previous,"kind ignored by fast key");
        previous=r.texture(g,raw[0],1);raw[0].w=1;raw[0].h=4;
        check(r.texture(g,raw[0],1)!=previous,"dimensions ignored by fast key");
        r.dispose();check(cache.size()==0 && field("lastTexture").get(r)==null,"dispose retained texture references");
        g.destroy();System.out.println("PASS ARM9 adjacent texture skips Vector scan / nonadjacent lookup / pixels+kind+size keys / eviction+OOM+dispose");
    }
    private static ScreenLines lines(int count) {
        ScreenLines b=new ScreenLines();for(int i=0;i<count;i++)b.line(10,2,8,22,1,0x123400+i,false);return b;
    }
    private static void weather() {
        DirectX7 g=M3GTest.renderer();check(g.renderScreenLines(lines(1)),"weather warmup unavailable");
        int created=TriangleStripArray.created;
        for(int pass=0;pass<2;pass++)for(int n=32;n>=1;n--) {
            int first=Graphics3D.history.size();check(g.renderScreenLines(lines(n)),"weather unavailable");
            check(Graphics3D.history.size()==first+1,"bucket added draw calls");
            Graphics3D.Draw d=Graphics3D.history.lastElement();int bucket=1;while(bucket<n)bucket*=2;
            check(d.indices.length==bucket*4 && d.positions.length==bucket*12 && d.colors.length==bucket*12,"wrong index/upload bucket "+n);
            for(int i=0;i<n;i++)for(int v=0;v<4;v++)check((d.colors[(i*4+v)*3+2]&255)==i,"RGB order changed");
            for(int i=n*4;i<bucket*4;i++)check(d.positions[i*3]==0 && d.positions[i*3+1]==0,"stale bucket tail indexed");
        }
        check(TriangleStripArray.created==created+5,"weather cache not bounded/reused at six index arrays");
        check(g.renderScreenLines(lines(65)),"multi-packet weather unavailable");
        check(Graphics3D.history.lastElement().indices.length==4 && TriangleStripArray.created==created+5,"32+32+1 prefix cache not reused");
        g.destroy();
        g=M3GTest.renderer();check(g.renderScreenLines(lines(1)),"lazy-index failure warmup");
        int[] before=g.display.clone();System.setProperty("weather.indices.fail","true");
        try {check(!g.renderScreenLines(lines(3)),"lazy index allocation OOM ignored");}
        finally {System.clearProperty("weather.indices.fail");}
        check(!g.isM3GActive() && Graphics3D.target==null && Arrays.equals(before,g.display),"lazy index OOM failed atomic rollback");
        g.destroy();System.out.println("PASS ARM9 all 1..32 weather counts twice / six lazy reused buckets / exact prefix uploads / stale-tail isolation / 65 strips / lazy index OOM rollback");
    }
    public static void main(String[] args) throws Exception {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        meshes();projection();textures();weather();
    }
}
