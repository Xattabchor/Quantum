package code.Rendering;

import java.lang.reflect.Field;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Work-count and ordered-pixel contracts, not ARM9 timing or actual GPU output. */
public final class Arm9CanvasTest {
    static void check(boolean b,String why){M3GTest.check(b,why);}
    static ScreenImages mixed(Image a,Image b) {
        ScreenImages q=new ScreenImages();q.reset(64,64);
        for(int i=0;i<5;i++){q.setColor(0x132457*i);q.fillRect(i,i,18,18);}
        for(int i=0;i<4;i++)q.drawRegion(a,0,0,8,8,i%2==0?0:2,3+i,4+i,20);
        q.drawImage(b,6,5,20);q.drawImage(a,5,6,20);
        for(int i=0;i<3;i++){q.setColor(0x123456+i);q.fillRect(4+i,4+i,11,8);}
        return q;
    }
    static Object helper(DirectX7 g) throws Exception {
        Field renderer=DirectX7.class.getDeclaredField("sceneRenderer");renderer.setAccessible(true);Object r=renderer.get(g);
        Field images=r.getClass().getDeclaredField("canvasImages");images.setAccessible(true);return images.get(r);
    }
    static Object field(Object object,String name) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    public static void main(String[] args) throws Exception {
        DirectX7 g=CanvasImagesTest.required();Image a=CanvasImagesTest.image(8,8),b=CanvasImagesTest.image(8,8);
        Graphics target=Image.createImage(64,64).getGraphics();
        for(int frame=0;frame<3;frame++) {
            ScreenImages q=mixed(a,b);int[] expected=CanvasImagesTest.reference(q,0xff456789);
            int first=Graphics3D.history.size(),sets=VertexArray.sets,arrays=VertexArray.created,indices=TriangleStripArray.created;
            int reads=Image.getRGBCalls,uploads=Graphics3D.uploads,bindings=VertexBuffer.positionBindings;g.beginFrameStats();g.renderCanvasImages(target,q);
            check(Arrays.equals(expected,CanvasImagesTest.nativeCommands(first,64,64,0xff456789)),"adjacent batching changed alpha/order/rect colors");
            check(g.getM3GDrawCalls()==5 && Graphics3D.history.size()==first+5,"ARM9 mixed foreground D14 -> D5 contract");
            if(frame>0) {
                check(VertexBuffer.positionBindings==bindings,"warm position scale rebound/divided per quad");
                check(VertexArray.sets-sets==10,"expected 2 attribute updates per packet, not per quad");
                check(VertexArray.created==arrays && TriangleStripArray.created==indices,"warm native geometry allocated");
                check(Image.getRGBCalls==reads && Graphics3D.uploads==uploads && g.getM3GCpuBarriers()==0,"warm CPU transfer regression");
            }
        }
        // Reuse the exact same packet count across opaque and textured frames.
        for(int phase=0;phase<4;phase++) {
            ScreenImages q=new ScreenImages();q.reset(64,64);
            for(int i=0;i<2;i++) {
                if(phase%2==0){q.setColor(0x123456+i*0x234567);q.fillRect(i*3,i*2,15,10);}
                else q.drawImage(a,i*3,i*2,20);
            }
            int[] expected=CanvasImagesTest.reference(q,0xff456789);int first=Graphics3D.history.size();
            g.renderCanvasImages(target,q);
            check(Arrays.equals(expected,CanvasImagesTest.nativeCommands(first,64,64,0xff456789)),"white/colored packet buffer state leaked");
        }
        // Bounded packet splitting and exact prefix indices, including stale tails.
        for(int n:new int[]{1,2,3,5,8,15,16,17,31,32,33,255,256,1}) {
            ScreenImages q=new ScreenImages();q.reset(64,64);
            for(int i=0;i<n;i++)q.drawImage(a,i%8,i%9,20);
            int[] expected=CanvasImagesTest.reference(q,0xff456789);int first=Graphics3D.history.size();
            g.beginFrameStats();g.renderCanvasImages(target,q);
            check(g.getM3GDrawCalls()==(n+15)/16,"foreground packet limit/count");
            check(Arrays.equals(expected,CanvasImagesTest.nativeCommands(first,64,64,0xff456789)),"prefix/index packet changed pixels");
            for(int i=first;i<Graphics3D.history.size();i++) {
                Graphics3D.Draw d=Graphics3D.history.elementAt(i);
                check(d.indices.length<=16*6 && d.positionCapacity==d.indices.length/6*4,"single quad uses oversized vertex capacity / packet limit");
            }
        }
        // Precision changes invalidate only the position scale; restore a small Canvas afterwards.
        for(int[] size:new int[][]{{4095,17},{64,64},{4095,17}}) {
            ScreenImages q=new ScreenImages();q.reset(size[0],size[1]);q.drawImage(a,size[0]/2,3,20);
            int[] expected=CanvasImagesTest.reference(q,0xff456789);int first=Graphics3D.history.size();
            g.renderCanvasImages(Image.createImage(size[0],size[1]).getGraphics(),q);
            check(Arrays.equals(expected,CanvasImagesTest.nativeCommands(first,size[0],size[1],0xff456789)),"cached scale survived precision change");
        }
        ScreenImages reload=new ScreenImages();reload.reset(64,64);reload.setColor(0xffffff);
        reload.drawRect(16,45,32,6);reload.fillRect(16,45,16,6);
        int[] reloadExpected=CanvasImagesTest.reference(reload,0xff456789);int reloadFirst=Graphics3D.history.size();
        g.beginFrameStats();g.renderCanvasImages(target,reload);
        check(g.getM3GDrawCalls()==1 && Arrays.equals(reloadExpected,CanvasImagesTest.nativeCommands(reloadFirst,64,64,0xff456789)),"reload D5 -> D1 lost outline/fill");
        // The most recent tile is a direct hit; source int scratch is reused.
        Object helper=helper(g),scratch=field(helper,"sourcePixels");
        for(int i=0;i<4;i++){ScreenImages q=new ScreenImages();q.reset(64,64);q.drawImage(CanvasImagesTest.image(8,8),0,0,20);g.renderCanvasImages(target,q);}
        check(field(helper,"sourcePixels")==scratch,"same-size cold sources allocate an int scratch per tile");
        check(((int[])scratch).length<=128*128,"unbounded int source scratch");
        ScreenImages large=new ScreenImages();large.reset(64,64);large.drawImage(CanvasImagesTest.image(128,128),0,0,20);g.renderCanvasImages(target,large);
        Object grown=field(helper,"sourcePixels");check(((int[])grown).length==128*128,"scratch growth limit");
        large.reset(64,64);large.drawImage(CanvasImagesTest.image(128,128),0,0,20);g.renderCanvasImages(target,large);
        check(field(helper,"sourcePixels")==grown,"large cold tile scratch not reused");g.destroy();
        // Exact LRU order survives oldest/middle hits, direct recent hits and swap-removal eviction.
        g=CanvasImagesTest.required();Image[] sources=new Image[65];ScreenImages lru=new ScreenImages();lru.reset(64,64);
        for(int i=0;i<sources.length;i++){sources[i]=CanvasImagesTest.image(2,2);if(i<64)lru.drawImage(sources[i],0,0,20);}
        g.renderCanvasImages(target,lru);Object lruCache=helper(g);int uploads=Graphics3D.uploads;
        for(int id:new int[]{0,0,32,64}){lru.reset(64,64);lru.drawImage(sources[id],0,0,20);g.renderCanvasImages(target,lru);}
        check(Graphics3D.uploads==uploads+1,"LRU cache hit reuploaded / insertion lost");
        int[] expectedOrder=new int[64];int n=0;
        for(int i=2;i<64;i++)if(i!=32)expectedOrder[n++]=i;
        expectedOrder[n++]=0;expectedOrder[n++]=32;expectedOrder[n++]=64;
        Object cursor=field(lruCache,"oldest"),previous=null;
        for(int id:expectedOrder) {
            check(cursor!=null && field(cursor,"source")==sources[id] && field(cursor,"previous")==previous,"array LRU chain/order");
            previous=cursor;cursor=field(cursor,"next");
        }
        check(cursor==null && field(lruCache,"lastTile")==previous,"array LRU tail/cycle");
        Object[] entries=(Object[])field(lruCache,"cache");
        for(int i=0;i<64;i++)check(((Integer)field(entries[i],"slot")).intValue()==i,"swap-removal cache slot mismatch");
        lru.reset(64,64);lru.drawImage(sources[1],0,0,20);g.renderCanvasImages(target,lru);
        check(Graphics3D.uploads==uploads+2 && field(field(lruCache,"oldest"),"source")==sources[3],"wrong tile evicted");g.destroy();
        // Allocate a previously unseen packet only in preparation, before binding Canvas.
        g=CanvasImagesTest.required();ScreenImages q=new ScreenImages();q.reset(64,64);q.fillRect(0,0,3,3);g.renderCanvasImages(target,q);
        q.reset(64,64);q.fillRect(0,0,3,3);q.fillRect(2,2,3,3);int binds=Graphics3D.binds;
        System.setProperty("canvas.indices.fail","true");boolean failed=false;
        try{g.renderCanvasImages(target,q);}catch(IllegalStateException expected){failed=true;}finally{System.clearProperty("canvas.indices.fail");}
        check(failed && q.count==0 && Graphics3D.target==null && Graphics3D.binds==binds && g.getRendererError()!=null,"lazy Canvas index failure bound target/fell back");g.destroy();
        g=CanvasImagesTest.required();ScreenImages partial=mixed(a,b);g.renderCanvasImages(target,partial);
        partial=mixed(a,b);Graphics3D.failAt=Graphics3D.calls+2;failed=false;
        try{g.renderCanvasImages(target,partial);}catch(IllegalStateException expected){failed=true;}finally{Graphics3D.failAt=0;}
        check(failed && g.getRendererError()!=null && partial.count==0 && Graphics3D.target==null && g.isM3GRequired(),"partial packet failure leaked target/replayed CPU");g.destroy();
        System.out.println("PASS ARM9 Canvas mixed D14->5 / attribute updates 42->10 / adjacent-only alpha order / 1..256 quad prefixes / exact vertex capacity / reload D5->1 / LRU order / precision changes / warm geometry reuse / source scratch reuse / prebind lazy-index failure");
    }
}
