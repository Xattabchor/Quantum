package code.Rendering;

import code.Rendering.Meshes.Sprite;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Ordered API streams, NOT GPU raster output or a phone FPS benchmark. */
public final class SpriteBatchTest {
    private static void check(boolean ok,String why){M3GTest.check(ok,why);}
    private static String stream(int start) {
        StringBuilder out=new StringBuilder();
        for(int n=start;n<Graphics3D.history.size();n++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(n);
            String state=d.blend+":"+d.function+":"+d.filter+":"+d.threshold+":"+d.fog+":"+d.fogMode
                    +":"+d.fogColor+":"+d.near+":"+d.far+":"+d.density+":"+d.cull+":"+d.winding+":"+d.perspective
                    +":"+d.source.image.width+":"+d.source.image.height+":"+Arrays.toString(d.source.image.pixels)
                    +":"+Arrays.toString(d.projection);
            int offset=0;
            for(int length:d.lengths) {
                for(int i=2;i<length;i++) {
                    int a=d.indices[offset+i-2],b=d.indices[offset+i-1],c=d.indices[offset+i];
                    if((i&1)!=0){int t=a;a=b;b=t;}
                    out.append(state);
                    for(int v:new int[]{a,b,c}) {
                        for(int k=0;k<3;k++)out.append(':').append(Float.floatToIntBits(d.positions[v*3+k]*d.scale));
                        for(int k=0;k<2;k++)out.append(':').append(Float.floatToIntBits(d.coords[v*2+k]*d.uvScale));
                        for(int k=0;k<d.colorComponents;k++)out.append(':').append(d.colors[v*d.colorComponents+k]);
                    }
                    out.append(';');
                }
                offset+=length;
            }
        }
        return out.toString();
    }
    private static Sprite sprite(Texture t,int mode,int i,boolean fog,boolean depthGroups) {
        Sprite s=SpriteTest.sprite(t,mode);s.fog=fog;s.color=4;
        s.pos.sx=i%5-6;s.pos.sy=i%7-6;s.size.sx=28+i%5;s.size.sy=28+i%7;
        s.mirX=(i&1)!=0;s.mirY=(i&2)!=0;
        s.pos.rz=-400-(depthGroups?i/7*37:0);
        return s;
    }
    private static void streams() {
        Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;
        DirectX7.fDist=1000;DirectX7.fogc=0x507090;DirectX7.standartDrawmode=3;
        for(boolean correction:new boolean[]{true,false})for(int mode=0;mode<=3;mode++)
            for(boolean fog:new boolean[]{false,true})for(boolean groups:new boolean[]{false,true}) {
                Main.m3gPerspective=correction;
                DirectX7 g=M3GTest.renderer();M3GSceneRenderer owner=new M3GSceneRenderer();
                Texture t=M3GTest.texture(0x804181f1);Sprite[] sprites=new Sprite[70];
                for(int i=0;i<sprites.length;i++)sprites[i]=sprite(t,mode,i,fog,groups);
                check(owner.draw(g,sprites[0],null,false),"warm sprite unsupported");owner.finish(g);
                int start=Graphics3D.history.size();
                for(Sprite s:sprites){check(owner.draw(g,s,null,false),"reference sprite unsupported");owner.finish(g);}
                String reference=stream(start);int calls=Graphics3D.calls;start=Graphics3D.history.size();
                for(Sprite s:sprites)check(owner.draw(g,s,null,false),"batch sprite unsupported");
                owner.finish(g);
                check(reference.equals(stream(start)),"sprite XYZ/UV/RGBA/Fog/primitive order changed");
                int expected=fog && mode!=1 && mode!=2 && groups?10:3;
                check(Graphics3D.calls-calls==expected,"70 sprite packet count: expected "+expected);
                owner.dispose();g.destroy();
            }
        Main.m3gPerspective=true;
        System.out.println("PASS 70 adjacent NPC billboards: 70 -> 3 draws / cutout+alpha+ADD+MUL / HW ON+OFF / clip+mirror / bit-exact ordered XYZ+UV+RGBA+Fog streams / distinct Fog depths remain split");
    }
    private static void queue(DirectX7 g,Sprite[] sprites) {
        g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();
        for(int i=0;i<sprites.length;i++)M3GTest.submit(g,sprites[i],null,i);
    }
    private static void warmAndRecovery() {
        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0x408090a0);Sprite[] sprites=new Sprite[70];
        for(int i=0;i<sprites.length;i++) {
            Sprite s=sprites[i]=SpriteTest.sprite(t,3);
            s.pos.sx=i%16*2;s.pos.sy=i/16*2;s.size.sx=s.pos.sx+2;s.size.sy=s.pos.sy+2;
        }
        queue(g,sprites);g.renderGameFrameImage(null,false,false,null,0,null);
        int arrays=VertexArray.created,indices=TriangleStripArray.created,sets=VertexArray.sets;
        queue(g,sprites);g.renderGameFrameImage(null,false,false,null,0,null);
        check(g.getM3GDrawCalls()==4 && g.getM3GCpuBarriers()==0 && g.getM3GTextureUploads()==0,
                "warm NPC batch adds draws/barriers/uploads");
        check(VertexArray.created==arrays && TriangleStripArray.created==indices,"warm NPC native arrays/indices allocated");
        check(VertexArray.sets-sets==8,"warm NPC position/UV writes or constant colors uploaded per sprite");
        for(int i=Graphics3D.history.size()-3;i<Graphics3D.history.size();i++) {
            Graphics3D.Draw d=Graphics3D.history.get(i);
            check(d.positions.length==(d.lengths.length/2)*12,"NPC quad vertices were duplicated per triangle");
        }
        DirectX7 cpu=M3GTest.renderer();M3GTest.Marker expected=new M3GTest.Marker(false),tail=new M3GTest.Marker(false);
        queue(cpu,sprites);M3GTest.submit(cpu,expected,null,1000);cpu.renderSoftware();
        queue(g,sprites);M3GTest.submit(g,tail,null,1000);Graphics3D.failAt=Graphics3D.calls+3;
        try{g.renderGameFrameImage(null,false,false,null,0,null);}finally{Graphics3D.failAt=0;}
        check(!g.isM3GActive() && Graphics3D.target==null && tail.calls==1 && expected.calls==1
                && Arrays.equals(cpu.display,g.display),"second NPC packet failure lost prefix/suffix/CPU tail");
        g.destroy();cpu.destroy();
        System.out.println("PASS warm NPC batching: 3 sprite draws + 1 sky / 8 VertexArray.set calls / 4 indexed vertices per sprite / B0 T0 / reused native arrays+indices / second packet failure -> complete selected-M3G CPU replay including queued tail");
    }
    private static void boundaries() {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer owner=new M3GSceneRenderer();
        Texture a=M3GTest.texture(0xff8090a0),b=M3GTest.texture(0xff203040);
        Sprite[] sprites={SpriteTest.sprite(a,0),SpriteTest.sprite(b,0),SpriteTest.sprite(a,0)};
        for(Sprite s:sprites){owner.draw(g,s,null,false);owner.finish(g);}
        int start=Graphics3D.history.size(),calls=Graphics3D.calls;
        for(Sprite s:sprites)owner.draw(g,s,null,false);owner.finish(g);
        check(Graphics3D.calls-calls==3,"NPCs sorted across another texture");
        check(Graphics3D.history.get(start).source==Graphics3D.history.get(start+2).source
                && Graphics3D.history.get(start).source!=Graphics3D.history.get(start+1).source,"A/B/A texture order changed");
        Texture alpha=M3GTest.texture(0x804080c0);
        for(int mode:new int[]{0,1,2,3}) {owner.draw(g,SpriteTest.sprite(alpha,mode),null,false);owner.finish(g);}
        calls=Graphics3D.calls;
        for(int mode:new int[]{0,3,1,2,0})owner.draw(g,SpriteTest.sprite(alpha,mode),null,false);
        owner.finish(g);check(Graphics3D.calls-calls==5,"blend/cache-kind state merged");
        Sprite same=SpriteTest.sprite(a,0);same.fog=true;DirectX7.fogc=0x123456;
        start=Graphics3D.history.size();owner.draw(g,same,null,false);
        DirectX7.fogc=0x654321;owner.draw(g,same,null,false);
        DirectX7.fDist=1200;owner.draw(g,same,null,false);owner.finish(g);
        check(Graphics3D.history.size()-start==3 && Graphics3D.history.get(start).fogColor==0x123456
                && Graphics3D.history.get(start+1).fogColor==0x654321
                && Graphics3D.history.get(start+1).far!=Graphics3D.history.get(start+2).far,"deferred Fog snapshot changed");
        same.fog=false;Main.m3gPerspective=true;
        start=Graphics3D.history.size();owner.draw(g,same,null,false);owner.draw(g,same,null,false);
        Main.m3gPerspective=false;owner.draw(g,same,null,false);owner.finish(g);
        check(Graphics3D.history.size()-start==2 && Graphics3D.history.get(start).perspective
                && !Graphics3D.history.get(start+1).perspective,"HW toggle mutated pending NPC polygon mode");
        Main.m3gPerspective=true;
        Sprite invisible=SpriteTest.sprite(b,0);invisible.pos.sx=40;invisible.size.sx=48;
        calls=Graphics3D.calls;owner.draw(g,same,null,false);owner.draw(g,invisible,null,false);owner.draw(g,same,null,false);
        owner.finish(g);check(Graphics3D.calls-calls==1,"invisible NPC splits a compatible run");
        owner.draw(g,same,null,false);owner.draw(g,same,null,false);
        M3GTest.Marker marker=new M3GTest.Marker(false);
        owner.barrier(g);marker.render(g,null);check(Graphics3D.target==null,"NPC CPU barrier retains native target");
        same.mode=99;check(!owner.draw(g,same,null,false),"unsupported NPC sprite swallowed");
        owner.dispose();g.destroy();
        System.out.println("PASS NPC batch boundaries: A/B/A painter order / texture/state/capacity/depth splits / CPU barrier / unsupported sprite dispatch");
    }
    public static void main(String[] args){streams();warmAndRecovery();boundaries();}
}
