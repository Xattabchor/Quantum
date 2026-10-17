package code.Rendering;

import code.AI.NPC;
import code.Math.Matrix;
import code.Math.Vector3D;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.PolygonMode;

/** Culling state + projected winding contracts, not a GPU rasterizer. */
public final class BackfaceTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    private static Polygon3V triangle(Vertex a,Vertex b,Vertex c) {
        return new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
    }
    private static Polygon4V quad(Vertex a,Vertex b,Vertex c,Vertex d) {
        return new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }
    private static double area(Graphics3D.Draw draw,int a,int b,int c) {
        short[] p=draw.positions;
        double ax=(double)p[a*3]/-p[a*3+2],ay=(double)p[a*3+1]/-p[a*3+2];
        double bx=(double)p[b*3]/-p[b*3+2],by=(double)p[b*3+1]/-p[b*3+2];
        double cx=(double)p[c*3]/-p[c*3+2],cy=(double)p[c*3+1]/-p[c*3+2];
        return (bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
    }
    private static int survivors(Graphics3D.Draw draw) {
        int result=0,offset=0;
        for(int size:draw.lengths==null?new int[]{draw.indices.length}:draw.lengths) {
            for(int i=2;i<size;i++) {
                int a=draw.indices[offset+i-2],b=draw.indices[offset+i-1],c=draw.indices[offset+i];
                if((i&1)!=0) {int t=a;a=b;b=t;}
                double area=area(draw,a,b,c);
                boolean front=draw.winding==PolygonMode.WINDING_CW?area<0:area>0;
                if(area!=0 && (draw.cull==PolygonMode.CULL_NONE
                        || (draw.cull==PolygonMode.CULL_BACK?front:!front))) result++;
            }
            offset+=size;
        }
        return result;
    }
    private static void meshState(Graphics3D.Draw d) {
        check(d.cull==PolygonMode.CULL_BACK && d.winding==PolygonMode.WINDING_CW,"wrong native mesh culling/winding (screen Y is inverted)");
    }
    private static Mesh twoSidedMesh() {
        Vertex a=new Vertex(-40,40,0),b=new Vertex(40,40,0),c=new Vertex(40,-40,0),d=new Vertex(-40,-40,0);
        Polygon4V front=quad(a,b,c,d),back=quad(d,c,b,a);back.tex=1;
        MultyTexture mt=new MultyTexture();mt.textures=new Texture[]{M3GTest.texture(0xffe02020),M3GTest.texture(0xff20e020)};
        return new Mesh(new Vertex[]{a,b,c,d},new RenderObject[]{front,back},mt);
    }
    private static void objectPath(DirectX7 g,Mesh mesh,MeshImage image,int angle,boolean mirror) {
        Matrix transform=new Matrix();transform.setRotY(angle);transform.setPosition(0,0,-300);
        if(mirror) transform.m00=-transform.m00;
        int start=Graphics3D.history.size();
        if(image!=null) {image.setMatrix(transform);g.addRenderObject(image);}
        else {g.transformAndProjectVertices(mesh,transform);g.addMesh(mesh,0,0,g.width,g.height,mesh.getTexture());}
        g.render();check(g.isM3GActive(),"object disabled native backend: "+g.getRendererError());
        check(Graphics3D.history.size()==start+1,"rear object face reached native draw or whole object disappeared");
        Graphics3D.Draw draw=last();meshState(draw);check(survivors(draw)==2,"visible object front was hardware-culled");
    }
    public static void main(String[] args) {
        Main.persQ=4;Main.mipMapping=false;Main.fogQ=2;DirectX7.fDist=1000;
        DirectX7 g=M3GTest.renderer();Texture tex=M3GTest.texture(0xff8090a0);
        Vertex a=M3GTest.vertex(2,2,-100),b=M3GTest.vertex(24,2,-200),c=M3GTest.vertex(24,24,-300),d=M3GTest.vertex(2,24,-150);
        for(int mode:new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13}) {
            tex.drawmode=(byte)mode;
            for(boolean correction:new boolean[]{true,false}) {
                Main.m3gPerspective=correction;
                RenderObject[] faces={triangle(a,b,d),triangle(d,b,a),quad(a,b,c,d),quad(d,c,b,a)};
                for(int face=0;face<faces.length;face++) {
                    int start=Graphics3D.history.size();M3GTest.submit(g,faces[face],tex,0);g.render();
                    check(g.isM3GActive(),"material not native: "+mode);
                    int total=0;
                    for(int i=start;i<Graphics3D.history.size();i++) {Graphics3D.Draw draw=Graphics3D.history.elementAt(i);meshState(draw);total+=survivors(draw);check(draw.perspective==correction,"cull mode lost hardware PC option");}
                    check(total==((face&1)==1?0:face==0?1:2),"native winding rejects front or accepts rear, mode "+mode+" face "+face);
                }
            }
        }
        tex.drawmode=0;c.sx=3;c.sy=3;Polygon4V folded=quad(a,b,c,d);
        check(folded.isVisible(0,0,32,32),"folded quad should retain its front triangle");
        M3GTest.submit(g,folded,tex,0);g.render();check(survivors(last())==1,"folded quad back triangle not culled independently");g.destroy();
        System.out.println("PASS CULL_BACK+WINDING_CW / tri+quad / material 0..13 / hardware PC ON+OFF / folded quad per-triangle rejection");

        g=M3GTest.renderer();Mesh mesh=twoSidedMesh();MeshImage image=new MeshImage(mesh);
        for(int angle:new int[]{0,45,180,225,360}) {
            objectPath(g,mesh,null,angle,false);objectPath(g,mesh,image,angle,false);
        }
        objectPath(g,mesh,image,0,true);
        short[] frame={-40,40,0,40,40,0,40,-40,0,-40,-40,0};
        short[] flipped=frame.clone();for(int i=0;i<flipped.length;i+=3) flipped[i]=(short)-flipped[i];
        Morphing morph=new Morphing(new short[][]{frame,flipped},mesh);image.setAnimation(morph);
        for(int f:new int[]{0,Morphing.FP,0,Morphing.FP}) {image.setFrame(f);objectPath(g,mesh,image,0,false);}
        code.AI.misc.Blood.blood=M3GTest.texture(0xff800000);
        NPC npc=new NPC(new Vector3D(0,0,-300),image,100,mesh.getTexture());
        npc.visible=true;
        for(int angle:new int[]{0,180}) {
            Matrix matrix=npc.getCharacter().getTransform();matrix.setRotY(angle);matrix.setPosition(0,0,-300);
            int start=Graphics3D.history.size();npc.render(g,0,0,32,32);g.render();
            check(Graphics3D.history.size()==start+1,"actual NPC path lost front/rejected neither rear face");meshState(last());check(survivors(last())==2,"actual NPC front invisible");
        }
        g.destroy();
        System.out.println("PASS addMesh / nested MeshImage / real NPC.render / animation+rotation+mirroring / original CPU rejects rear before native submission");

        // The shared world Appearance must never leak culling into flat effects.
        g=M3GTest.renderer();Mesh skyMesh=twoSidedMesh();SkyMesh sky=new SkyMesh(skyMesh);
        Matrix matrix=new Matrix();matrix.setPosition(0,0,-300);sky.set(matrix,0,0,32,32);
        for(RenderObject object:new RenderObject[]{M3GTest.triangle(),new SolidSky(0x123456),M3GTest.triangle(),sky,M3GTest.triangle()}) {
            M3GTest.submit(g,object,object instanceof Polygon3V?tex:null,0);g.render();
            if(object instanceof Polygon3V) meshState(last());else check(last().cull==PolygonMode.CULL_NONE,"sky inherited mesh backface culling");
        }
        Sprite sprite=new Sprite(tex,5);sprite.pos.sx=0;sprite.pos.sy=0;sprite.pos.rz=-100;sprite.size.sx=32;sprite.size.sy=32;
        M3GTest.submit(g,sprite,null,0);g.render();check(last().cull==PolygonMode.CULL_NONE,"sprite inherited mesh culling");
        code.Gameplay.Map.Skybox pano=new code.Gameplay.Map.Skybox(tex,2,1,-91,0.2f,0,0,0);pano.skyboxAlways=true;pano.render(null,g);g.render();check(last().cull==PolygonMode.CULL_NONE,"panorama inherited mesh culling");
        ScreenLines lines=new ScreenLines();lines.line(2,2,4,10,1,0xffffff,false);check(g.renderScreenLines(lines),"weather failed");check(last().cull==PolygonMode.CULL_NONE,"weather inherited mesh culling");
        MuzzleGlow glow=new MuzzleGlow();glow.alpha=100;glow.texture=tex;glow.quad.a=a;glow.quad.b=b;glow.quad.c=c;glow.quad.d=d;
        check(g.renderOverlay(glow),"muzzle glow failed");check(last().cull==PolygonMode.CULL_NONE,"glow inherited mesh culling");
        M3GTest.submit(g,M3GTest.triangle(),tex,0);g.render();meshState(last());
        g.resize(40,24);M3GTest.submit(g,M3GTest.triangle(),tex,0);g.render();meshState(last());g.destroy();
        System.out.println("PASS sky+solid+sprite+panorama+weather+muzzle stay unculled / world state restored / resize");

        // GPU rejection leaves the original software mesh behavior and rollback.
        mesh=twoSidedMesh();image=new MeshImage(mesh);matrix=new Matrix();matrix.setPosition(0,0,-300);image.setMatrix(matrix);
        DirectX7 cpu=new DirectX7(32,32);cpu.clearDisplay(0x123456);cpu.addRenderObject(image);cpu.render();int[] expected=cpu.display.clone();
        g=M3GTest.renderer();Graphics3D.failAt=Graphics3D.calls+1;g.addRenderObject(image);g.render();Graphics3D.failAt=0;
        check(Arrays.equals(expected,g.display) && !g.isM3GActive(),"native culling failure didn't replay original CPU mesh");g.destroy();cpu.destroy();
        System.out.println("PASS unchanged Software mesh culling / full nested queue replay after native failure");
    }
}
