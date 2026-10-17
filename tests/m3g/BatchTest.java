package code.Rendering;

import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.Math.Matrix;
import code.utils.Main;
import java.util.*;
import javax.microedition.m3g.*;
import javax.microedition.lcdui.Image;

/** Compare the ordered triangle stream, not a fake GPU screenshot. */
public final class BatchTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static DirectX7 renderer() {DirectX7.fDist=1000;return M3GTest.renderer();}
    private static RenderObject face(int i,boolean quad,boolean fixed) {
        int x=fixed?0:i%4,y=fixed?0:i%3,z=fixed?0:i%19;
        Vertex a=M3GTest.vertex(2+x,2+y,-100-z),b=M3GTest.vertex(22+x,2+y,-200-z),
                c=M3GTest.vertex(22+x,22+y,-300-z),d=M3GTest.vertex(2+x,22+y,-150-z);
        if(!quad) c=d;
        if(!quad) {
            ColorLightedPolygon3V p=new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
            p.ar=30+i%200;p.ag=50;p.ab=90;p.br=180;p.bg=90;p.bb=120;p.cr=70;p.cg=200;p.cb=80;return p;
        }
        return new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }
    private static RenderObject[] faces(int n,boolean quad,boolean fixed) {
        RenderObject[] result=new RenderObject[n];for(int i=0;i<n;i++) result[i]=face(i,quad,fixed);return result;
    }
    private static void queue(DirectX7 g,RenderObject[] objects,Texture[] textures) {
        for(int i=0;i<objects.length;i++) M3GTest.submit(g,objects[i],textures[i%textures.length],i);
        g.render();check(g.isM3GActive(),"batch disabled native renderer: "+g.getRendererError());
    }
    private static List<String> triangles(int start) {
        List<String> result=new ArrayList<String>();
        for(int draw=start;draw<Graphics3D.history.size();draw++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(draw);int offset=0;
            for(int length:d.lengths) {
                for(int i=2;i<length;i++) {
                    int[] indices={d.indices[offset+i-2],d.indices[offset+i-1],d.indices[offset+i]};
                    if((i&1)!=0) {int t=indices[0];indices[0]=indices[1];indices[1]=t;}
                    String row=d.blend+":"+d.function+":"+d.filter+":"+d.sample+":"+d.threshold+":"+d.textured
                            +":"+d.cull+":"+d.winding+":"+d.perspective+":"+d.fog+":"+d.fogColor+":"+d.near+":"+d.far;
                    for(int vertex:indices) {
                        int p=vertex*3;double w=-(double)d.positions[p+2];
                        row+="/"+d.positions[p]/w+","+d.positions[p+1]/w+","+(w/-d.positions[indices[0]*3+2]);
                        if(d.fog) row+=","+Float.floatToIntBits(-d.positions[p+2]*d.scale);
                        if(d.textured) row+=","+d.coords[vertex*2]*d.uvScale+","+d.coords[vertex*2+1]*d.uvScale;
                        for(int channel=0;channel<4;channel++) row+=","+(d.colors[vertex*4+channel]&255);
                    }
                    result.add(row);
                }
                offset+=length;
            }
        }
        return result;
    }
    private static List<String> reference(RenderObject[] objects,Texture[] textures) {
        DirectX7 g=renderer();M3GSceneRenderer renderer=new M3GSceneRenderer();int start=Graphics3D.history.size();
        for(int i=0;i<objects.length;i++) {
            check(renderer.draw(g,objects[i],textures[i%textures.length],false),"reference unsupported");
            renderer.finish(g); // Force the original one-polygon submission.
        }
        List<String> result=triangles(start);renderer.dispose();g.destroy();return result;
    }
    private static void geometry(int n,boolean quad,int mode,boolean fixed,int expectedCalls) {
        RenderObject[] objects=faces(n,quad,fixed);Texture tex=M3GTest.texture(0x807090b0);tex.drawmode=(byte)mode;
        Texture[] textures={tex};List<String> expected=reference(objects,textures);
        DirectX7 g=renderer();int start=Graphics3D.history.size(),calls=Graphics3D.calls,sets=VertexArray.sets;
        queue(g,objects,textures);
        check(expected.equals(triangles(start)),"ordered projected geometry/relative W/UV/light/material changed in mode "+mode+" quad="+quad);
        check(VertexArray.sets==sets+3*(Graphics3D.calls-calls),"per-polygon native vertex uploads survived batching");
        if(expectedCalls>=0) check(Graphics3D.calls==calls+expectedCalls,"wrong draw reduction "+(Graphics3D.calls-calls)+" mode "+mode);
        int arrays=VertexArray.created,indices=TriangleStripArray.created,uploads=Graphics3D.uploads;
        int binds=Graphics3D.binds,releases=Graphics3D.releases,reads=Image.getRGBCalls;
        start=Graphics3D.history.size();queue(g,objects,textures);
        check(expected.equals(triangles(start)),"warm batch changed stream");
        check(VertexArray.created==arrays && TriangleStripArray.created==indices && Graphics3D.uploads==uploads,"warm batch allocates native buffers/indices or reuploads textures");
        check(Graphics3D.binds==binds+1 && Graphics3D.releases==releases+1 && Image.getRGBCalls==reads+1,"batch added frame transfers");
        int completed=Graphics3D.calls;g.render();check(Graphics3D.calls==completed,"empty frame repeated stale packet");g.destroy();
    }
    private static Mesh mesh(int n) {
        Vertex a=new Vertex(-40,40,-100),b=new Vertex(40,40,-100),c=new Vertex(-40,-40,-150);
        RenderObject[] faces=new RenderObject[n];for(int i=0;i<n;i++) faces[i]=new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        MultyTexture textures=new MultyTexture();textures.textures=new Texture[]{M3GTest.texture(0xff7090b0)};
        return new Mesh(new Vertex[]{a,b,c},faces,textures);
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.mipMapping=false;Main.fogQ=2;Main.forceLQFog=false;DirectX7.fDist=1000;
        for(boolean flag:new boolean[]{true,false}) {
            Main.m3gPerspective=flag;
            geometry(64,false,0,false,1);geometry(128,false,0,false,2);
            geometry(100,false,0,false,2);geometry(100,true,0,false,4);
            geometry(65,false,13,true,2);geometry(65,false,13,false,2);geometry(65,true,7,false,3);
            geometry(65,false,3,true,2);geometry(65,false,6,true,2);
            geometry(65,false,3,false,2); // Different legacy scales now share an exact common lattice.
            for(int mode:new int[]{1,9,13}) {geometry(17,false,mode,true,1);geometry(17,false,mode,false,1);}
            for(int mode:new int[]{2,4,5,8,10,11,12}) geometry(17,false,mode,false,1);
        }
        System.out.println("PASS 100 triangles -> 2 draws / 100 quads -> 4 draws / ordered geometry+relative W+UV+colors+materials / exact native Fog scales / PC ON+OFF");
        System.out.println("PASS 64-triangle capacity+partial packets / warm native buffers+indices+texture reuse / one frame transfer / no per-frame batch buffers");

        RenderObject[] mixed=new RenderObject[12];for(int i=0;i<mixed.length;i++) mixed[i]=face(i,(i&1)!=0,true);
        Texture mixedTex=M3GTest.texture(0xff7090b0);mixedTex.drawmode=13;
        List<String> mixedReference=reference(mixed,new Texture[]{mixedTex});DirectX7 mixedRenderer=renderer();int mixedStart=Graphics3D.history.size();
        queue(mixedRenderer,mixed,new Texture[]{mixedTex});check(Graphics3D.history.size()==mixedStart+1
                && mixedReference.equals(triangles(mixedStart)),"mixed tri/quad/static RGB stream changed");mixedRenderer.destroy();

        // Same RawImage/Texture2D with native Fog vs no Fog: capture state before flush.
        Texture add=M3GTest.texture(0xff405060),normal=new Texture(add.rImg,true,null);add.drawmode=1;
        RenderObject[] objects=faces(9,false,false);Texture[] materials={add,add,normal,normal,add,normal};
        List<String> expected=reference(objects,materials);DirectX7 g=renderer();int start=Graphics3D.history.size();queue(g,objects,materials);
        check(expected.equals(triangles(start)),"texture-function transition lost material or reordered triangles");g.destroy();
        // Overlapping ALPHA and ADD geometry may merge only in adjacent runs.
        Texture alpha=M3GTest.texture(0x80405060),other=M3GTest.texture(0x80a0b0c0);alpha.drawmode=other.drawmode=4;
        materials=new Texture[]{alpha,alpha,other,alpha,alpha};objects=faces(5,true,false);expected=reference(objects,materials);
        g=renderer();start=Graphics3D.history.size();queue(g,objects,materials);
        check(expected.equals(triangles(start)) && Graphics3D.history.size()==start+3,"alpha run moved past another texture");g.destroy();
        System.out.println("PASS adjacent runs only / alpha painter order / same native texture Fog<->no Fog snapshot / no texture sorting");

        // A CPU barrier must see all pending geometry, and a sprite/sky cannot
        // overtake a delayed polygon. The existing marker checks readback order.
        Texture tex=M3GTest.texture(0xffff0000);g=renderer();
        M3GTest.submit(g,face(0,false,false),tex,0);M3GTest.submit(g,face(1,false,false),tex,1);
        M3GTest.submit(g,new M3GTest.Marker(true),null,2);M3GTest.submit(g,face(2,false,false),tex,3);g.render();check(g.isM3GActive(),"CPU barrier disabled native");g.destroy();
        g=renderer();queue(g,faces(1,false,false),new Texture[]{tex});SolidSky sky=new SolidSky(0x123456);M3GTest.submit(g,sky,null,0);g.renderBackground();
        int binds=Graphics3D.binds,reads=Image.getRGBCalls;
        queue(g,faces(100,false,false),new Texture[]{tex});check(Graphics3D.binds==binds+1 && Image.getRGBCalls==reads+1,"combined sky/world packet split");g.destroy();
        Sprite sprite=new Sprite(tex,5);sprite.pos.sx=0;sprite.pos.sy=0;sprite.pos.rz=-100;sprite.size.sx=32;sprite.size.sy=32;
        g=renderer();start=Graphics3D.history.size();
        M3GTest.submit(g,face(0,false,false),tex,0);M3GTest.submit(g,face(1,false,false),tex,1);
        M3GTest.submit(g,sprite,null,2);M3GTest.submit(g,face(2,false,false),tex,3);g.render();
        check(Graphics3D.history.size()==start+3 && Graphics3D.history.elementAt(start).indices.length==6
                && Graphics3D.history.elementAt(start+1).cull==PolygonMode.CULL_NONE,"sprite overtook pending polygons");g.destroy();
        System.out.println("PASS pending geometry flushes before CPU/sprite/sky barriers / combined sky+100 world polygons one bind/readback");

        Mesh mesh=mesh(100);MeshImage image=new MeshImage(mesh);image.setMatrix(new Matrix());g=renderer();int calls=Graphics3D.calls;
        g.addRenderObject(image);g.render();check(g.isM3GActive() && Graphics3D.calls==calls+2,"nested animated-mesh path not batched");g.destroy();
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);cpu.addRenderObject(image);cpu.render();int[] cpuPixels=cpu.display.clone();
        for(int failure:new int[]{1,2}) {
            g=renderer();Graphics3D.failAt=Graphics3D.calls+failure;g.addRenderObject(image);g.render();Graphics3D.failAt=0;
            check(!g.isM3GActive() && Arrays.equals(cpuPixels,g.display),"failed packet didn't replay entire mesh");
            int before=Graphics3D.calls;g.render();check(Graphics3D.calls==before,"failed packet survived queue cleanup");g.destroy();
        }
        g=renderer();g.addRenderObject(image);System.setProperty("readback.fail","true");g.render();System.clearProperty("readback.fail");
        check(!g.isM3GActive() && Arrays.equals(cpuPixels,g.display),"batch readback didn't roll back");g.destroy();cpu.destroy();
        g=renderer();g.addRenderObject(image);System.setProperty("m3g.oom","true");g.render();System.clearProperty("m3g.oom");
        check(!g.isM3GActive() && Arrays.equals(cpuPixels,g.display),"batch OOM didn't roll back");g.destroy();
        g=renderer();queue(g,faces(100,false,false),new Texture[]{tex});g.resize(40,24);
        calls=Graphics3D.calls;queue(g,faces(100,false,false),new Texture[]{tex});check(Graphics3D.calls==calls+2,"resize retained old packet");g.destroy();
        // A pending packet is discarded on dispose, never submitted next frame.
        g=renderer();M3GSceneRenderer backend=new M3GSceneRenderer();backend.draw(g,face(0,false,false),tex,false);
        calls=Graphics3D.calls;backend.dispose();backend.finish(g);check(Graphics3D.calls==calls,"dispose retained packet");g.destroy();
        g=renderer();backend=new M3GSceneRenderer();Main.m3gPerspective=true;start=Graphics3D.history.size();
        backend.draw(g,face(0,false,false),tex,false);Main.m3gPerspective=false;backend.draw(g,face(1,false,false),tex,false);backend.finish(g);
        check(Graphics3D.history.size()==start+2 && Graphics3D.history.elementAt(start).perspective
                && !Graphics3D.history.elementAt(start+1).perspective,"live PC change mutated a pending packet");backend.dispose();g.destroy();Main.m3gPerspective=true;
        System.out.println("PASS MeshImage 100 polygons -> 2 draws / first+last packet and final readback rollback / no stale packets on disposal");
    }
}
