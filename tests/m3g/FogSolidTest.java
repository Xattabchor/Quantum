package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.*;
import javax.microedition.m3g.*;

/** Constant LINEAR Fog algebra/coverage/API/recovery, not a GPU rasterizer. */
public final class FogSolidTest {
    private static void check(boolean b,String why){M3GTest.check(b,why);}
    private static final int[] TRI={0,1,2};
    private static final byte[] COLORS={(byte)255,5,9,(byte)255,7,8,9,(byte)255,10,11,12,(byte)255};
    private static final short[] UV={0,0,256,0,0,256};
    private static short[] points(int q){return new short[]{(short)-q,(short)-q,(short)-q,(short)q,(short)-q,(short)-q,0,(short)q,(short)-q};}
    private static M3GPolygonBatch.State state(Texture2D t) {
        M3GPolygonBatch.State s=new M3GPolygonBatch.State();s.texture=t;s.polygon=new PolygonMode();
        s.blend=CompositingMode.REPLACE;s.function=Texture2D.FUNC_MODULATE;s.opaque=true;
        s.fogged=true;s.fogMode=Fog.LINEAR;s.near=1;s.far=10;s.fogColor=0x284060;return s;
    }
    private static String projected(int start) {
        StringBuffer out=new StringBuffer();
        for(int i=start;i<Graphics3D.history.size();i++) {
            Graphics3D.Draw d=Graphics3D.history.elementAt(i);
            for(int v:d.indices) {
                double z=-d.positions[v*3+2];
                out.append(d.positions[v*3]/z).append(',').append(d.positions[v*3+1]/z).append(',');
                for(int k=0;k<2;k++)out.append(d.coords[v*2+k]).append(',');
                for(int k=0;k<4;k++)out.append(d.colors[v*4+k]).append(',');
            }
        }
        return out.toString();
    }
    private static void pairs() {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer owner=new M3GSceneRenderer();
        Texture2D a=owner.texture(g,M3GTest.texture(0xff123456).rImg,0),b=owner.texture(g,M3GTest.texture(0xffabcdef).rImg,0);
        M3GPolygonBatch.State s=state(a);M3GPolygonBatch batch=new M3GPolygonBatch(owner,Graphics3D.getInstance());
        TriangleStripArray index=new TriangleStripArray(0,new int[]{3});
        for(int scenario=0;scenario<13;scenario++) {
            s=state(a);s.polygon=new PolygonMode();short[] first=points(100),second=points(200);byte[] color=COLORS.clone();
            float inputScale=scenario==12?0.1234567f:0.5f;
            // Rejections are intentional, not merely cases with matching pixels.
            if(scenario==1)s.opaque=false;
            if(scenario==2)s.threshold=0.5f;
            if(scenario==3)s.blend=CompositingMode.ALPHA;
            if(scenario==4)s.function=Texture2D.FUNC_ADD;
            if(scenario==5)s.fogMode=Fog.EXPONENTIAL;
            if(scenario==6)second=points(10); // crosses the far boundary
            if(scenario==7)color[3]=(byte)128;
            if(scenario==8)s.fogged=false;
            if(scenario==9)second[2]=0; // eye/near clipping must not change
            if(scenario==10)second=points(20); // exact far plane, guarded
            boolean merge=scenario==0 || scenario==12;
            int start=Graphics3D.history.size();
            s.texture=a;batch.add(g,3,0.5f,first,UV,COLORS,TRI,index,s);batch.flush(g);
            s.texture=b;if(scenario==11)s.fogColor++;
            batch.add(g,3,inputScale,second,UV,color,TRI,index,s);batch.flush(g);
            String reference=projected(start);owner.finish(g);int calls=Graphics3D.calls;start=Graphics3D.history.size();
            if(scenario==11)s.fogColor--;
            s.texture=a;batch.add(g,3,0.5f,first,UV,COLORS,TRI,index,s);
            s.texture=b;if(scenario==11)s.fogColor++;
            batch.add(g,3,inputScale,second,UV,color,TRI,index,s);batch.flush(g);
            check(Graphics3D.calls-calls==(merge?1:2),"unexpected solid Fog merge/rejection "+scenario);
            check(reference.equals(projected(start)),"ordered projected XY/UV/RGBA changed");
            if(merge) {
                Graphics3D.Draw d=Graphics3D.history.lastElement();
                check(d.source==null && d.fog && d.fogMode==Fog.LINEAR && d.near==0 && d.far==0.5f,"not fully saturated native LINEAR Fog");
                check((Graphics3D.target.image.pixels[0]&0xffffff)==s.fogColor,"constant Fog RGB changed");
                // Convex combinations of positive eye depths stay >= min.
                // Hence both vertex- and fragment-evaluated LINEAR Fog clamp
                // identically, independently of source texel RGB and UV.
                Random r=new Random(12);
                for(int i=0;i<1000;i++) {
                    double u=r.nextDouble(),v=r.nextDouble()*(1-u),w=1-u-v;
                    double depth=u*50+v*50+w*50;
                    double original=Math.max(0,Math.min(1,(10-depth)/9));
                    double folded=Math.max(0,Math.min(1,(0.5-(u*100+v*100+w*100))/0.5));
                    check(original==0 && folded==0,"LINEAR Fog is not constant throughout covered triangle");
                }
            }
            owner.finish(g);
        }
        // Do NOT eagerly canonicalize one far face: a following near face of
        // the same material must retain the old single-packet path.
        s=state(a);int calls=Graphics3D.calls;
        batch.add(g,3,0.5f,points(100),UV,COLORS,TRI,index,s);
        batch.add(g,3,0.5f,points(10),UV,COLORS,TRI,index,s);batch.flush(g);
        check(Graphics3D.calls-calls==1 && Graphics3D.history.lastElement().source==a,"isolated far face broke an existing near run");
        owner.finish(g);
        // Capacity is not a saved material boundary. Folding a full packet
        // must not turn the next isolated far face into a canonical singleton.
        s=state(a);calls=Graphics3D.calls;
        for(int i=0;i<64;i++)batch.add(g,3,0.5f,points(100),UV,COLORS,TRI,index,s);
        s.texture=b;batch.add(g,3,0.5f,points(100),UV,COLORS,TRI,index,s);
        batch.add(g,3,0.5f,points(10),UV,COLORS,TRI,index,s);batch.flush(g);
        check(Graphics3D.calls-calls==2,"capacity spill created an unnecessary solid singleton");
        owner.finish(g);owner.dispose();g.destroy();
        System.out.println("PASS saturated LINEAR Fog: opaque/material/alpha/far/near/EXP guards / arbitrary old scales / exact ordered XY+UV+RGBA / clamp algebra / no eager near-run split");
    }
    private static void randomized() {
        DirectX7 g=M3GTest.renderer();M3GSceneRenderer owner=new M3GSceneRenderer();
        Texture2D[] textures=new Texture2D[3];
        for(int i=0;i<3;i++)textures[i]=owner.texture(g,M3GTest.texture(0xff8090a0+i).rImg,0);
        M3GPolygonBatch batch=new M3GPolygonBatch(owner,Graphics3D.getInstance());
        TriangleStripArray index=new TriangleStripArray(0,new int[]{3});int saved=0;
        for(int seed=0;seed<500;seed++) {
            int oldCalls=0;String reference=null;
            for(int pass=0;pass<2;pass++) {
                Random r=new Random(seed);M3GPolygonBatch.State s=state(textures[0]);s.opaque=pass==1;
                Graphics3D.history.clear();int before=Graphics3D.calls;
                for(int i=0;i<100;i++) {
                    s.texture=textures[r.nextInt(3)];int q=r.nextBoolean()?60+r.nextInt(300):2+r.nextInt(10);
                    float scale=new float[]{0.5f,1,2}[r.nextInt(3)];
                    batch.add(g,3,scale,points(q),UV,COLORS,TRI,index,s);
                }
                batch.flush(g);int calls=Graphics3D.calls-before;String witness=projected(0);
                if(pass==0){oldCalls=calls;reference=witness;}
                else {
                    check(calls<=oldCalls,"deferred saturated folding increased draw count seed="+seed);
                    check(reference.equals(witness),"seeded ordered projected XY/UV/RGBA changed");saved+=oldCalls-calls;
                }
                owner.finish(g);
            }
        }
        check(saved>100,"randomized saturated merge coverage missing");
        owner.dispose();g.destroy();Graphics3D.history.clear();
        System.out.println("PASS 500 seeded mixed near/far/material/scale streams / no draw-count regressions / ordered XY+UV+RGBA unchanged");
    }
    private static void queue(DirectX7 g,RenderObject[] faces,Texture[] textures) {
        g.beginFrameStats();M3GTest.submit(g,new SolidSky(0x123456),null,0);g.renderBackground();
        for(int i=0;i<faces.length;i++)M3GTest.submit(g,faces[i],textures[i%2],i);
    }
    private static void actual() {
        for(int mode:new int[]{3,6})for(boolean correction:new boolean[]{true,false}) {
            Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=correction;DirectX7.setFogDist(1000);
            DirectX7 g=M3GTest.renderer();Texture[] tex={M3GTest.texture(0xff8090a0),M3GTest.texture(0xff204060)};
            tex[0].drawmode=tex[1].drawmode=(byte)mode;RenderObject[] faces=new RenderObject[70];
            for(int i=0;i<faces.length;i++)faces[i]=new Polygon4V(M3GTest.vertex(10,10,-3000),M3GTest.vertex(30,10,-3000),
                M3GTest.vertex(30,30,-3000),M3GTest.vertex(10,30,-3000),(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
            queue(g,faces,tex);g.renderGameFrameImage(null,false,false,null,0,null);
            queue(g,faces,tex);g.renderGameFrameImage(null,false,false,null,0,null);
            int arrays=VertexArray.created,indices=TriangleStripArray.created;
            queue(g,faces,tex);g.renderGameFrameImage(null,false,false,null,0,null);
            check(g.getM3GDrawCalls()==4 && g.getM3GTextureUploads()==0 && g.getM3GCpuBarriers()==0,"70 far quads did not become 3 world packets / warm B0 T0");
            check(VertexArray.created==arrays && TriangleStripArray.created==indices,"warm solid Fog allocated native arrays/indices");
            DirectX7 cpu=M3GTest.renderer();M3GTest.Marker expected=new M3GTest.Marker(false),tail=new M3GTest.Marker(false);
            queue(cpu,faces,tex);M3GTest.submit(cpu,expected,null,1000);cpu.renderSoftware();
            queue(g,faces,tex);M3GTest.submit(g,tail,null,1000);Graphics3D.failAt=Graphics3D.calls+3;
            try {g.renderGameFrameImage(null,false,false,null,0,null);}finally{Graphics3D.failAt=0;}
            check(!g.isM3GActive() && Graphics3D.target==null && tail.calls==1 && expected.calls==1 && Arrays.equals(cpu.display,g.display),"solid Fog failure lost exact complete CPU replay");
            g.destroy();cpu.destroy();
        }
        // The real texture conversion, not a test-only opacity assertion:
        // zero texels retain cutout coverage even beyond the far Fog plane.
        Main.persQ=4;Main.fogQ=2;DirectX7.setFogDist(1000);
        DirectX7 holes=M3GTest.renderer();Texture[] alpha={M3GTest.texture(0xff123456),M3GTest.texture(0)};
        alpha[0].drawmode=alpha[1].drawmode=3;
        RenderObject[] cutouts=new RenderObject[4];
        for(int i=0;i<cutouts.length;i++) {
            Polygon3V p=M3GTest.triangle();p.a.rz=p.b.rz=p.c.rz=-3000;cutouts[i]=p;
        }
        queue(holes,cutouts,alpha);holes.renderGameFrameImage(null,false,false,null,0,null);
        queue(holes,cutouts,alpha);holes.renderGameFrameImage(null,false,false,null,0,null);
        check(holes.getM3GDrawCalls()==5,"actual transparent cutout was incorrectly folded");holes.destroy();
        Main.m3gPerspective=true;
        System.out.println("PASS actual opaque Fog3/6: 70 alternating-texture far quads -> 3 world calls / bounded packets / HW ON+OFF / warm arrays+indices reused B0 T0 / second-packet failure -> complete original CPU replay");
    }
    public static void main(String[] args){pairs();randomized();actual();}
}
