package code.Rendering;

import code.Rendering.Meshes.*;
import code.utils.Main;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

/** Compare optimized preparation against the original integer formulas. */
public final class MaterialPrepTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static int clamp(long value) {return (int)Math.max(0L,Math.min(255L,value));}
    private static int visible(int rz,int distance) {return distance<=1?255:255-clamp(-(long)rz*255/distance);}
    private static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static RenderObject polygon(int kind,Vertex[] v) {
        if(kind==0) return new Polygon3V(v[0],v[1],v[2],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        if(kind==1) return new Polygon4V(v[0],v[1],v[2],v[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        if(kind==2) return new LightedPolygon3V(v[0],v[1],v[2],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        if(kind==3) return new LightedPolygon4V(v[0],v[1],v[2],v[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        if(kind==4) return new ColorLightedPolygon3V(v[0],v[1],v[2],(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        return new ColorLightedPolygon4V(v[0],v[1],v[2],v[3],(byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }
    private static RawImage mip(int size) {int[] pixels=new int[size*size];java.util.Arrays.fill(pixels,-1);return new RawImage(pixels,size,size);}
    private static boolean perspective(Texture texture,Vertex[] v,int count,boolean fast) {
        if(fast || Main.persQ==0) return false;
        if(Main.persQ==4) return true;
        if(!texture.perspectiveCorrection) return false;
        int minX=v[0].sx,maxX=minX,minY=v[0].sy,maxY=minY;
        for(int i=1;i<count;i++) {minX=Math.min(minX,v[i].sx);maxX=Math.max(maxX,v[i].sx);minY=Math.min(minY,v[i].sy);maxY=Math.max(maxY,v[i].sy);}
        int xLimit=count==3?(Main.persQ<3?30:15):(Main.persQ<3?70:50);
        int yLimit=count==3?xLimit:(Main.persQ<3?100:70);
        return (long)maxX-minX>xLimit || (long)maxY-minY>yLimit;
    }
    private static RawImage select(Texture t,RenderObject p,Vertex[] v,int[] uv,int count,boolean fast) {
        RawImage raw=t.rImg;
        if(!fast && Main.mipMapping && t.mip!=null && t.mip.length>0) {
            raw=t.mip[0];int minX=v[0].sx,maxX=minX,minY=v[0].sy,maxY=minY;
            int minU=uv[0],maxU=minU,minV=uv[1],maxV=minV;
            for(int i=1;i<count;i++) {
                minX=Math.min(minX,v[i].sx);maxX=Math.max(maxX,v[i].sx);minY=Math.min(minY,v[i].sy);maxY=Math.max(maxY,v[i].sy);
                minU=Math.min(minU,uv[i*2]);maxU=Math.max(maxU,uv[i*2]);minV=Math.min(minV,uv[i*2+1]);maxV=Math.max(maxV,uv[i*2+1]);
            }
            int factor=p.ny>4000 || p.ny< -4000 || Main.q>999?2:3;
            for(int level=1;level<t.mip.length && level<3;level++) {
                if(raw==null || t.mip[level]==null) break;
                long screen=((long)maxX-minX+(long)maxY-minY)/2*factor;
                long texels=(((long)maxU-minU)*raw.w+((long)maxV-minV)*raw.h)/256;
                if(screen>=texels) break;raw=t.mip[level];
            }
        }
        return raw;
    }
    public static void main(String[] args) throws Exception {
        for(int value=-1;value<=256;value++) for(int visibility=0;visibility<=255;visibility++)
            check(M3GMaterialMath.shade(value,visibility)==clamp(value)*visibility/255,"divide-by-255 mismatch");
        for(int value:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE}) for(int visibility=0;visibility<=255;visibility++)
            check(M3GMaterialMath.shade(value,visibility)==clamp(value)*visibility/255,"clamp overflow");
        System.out.println("PASS exhaustive 256x256 exact shade / clamped out-of-range public light values / integer divide-by-255 eliminated");

        Random random=new Random(1417);M3GMaterialMath math=new M3GMaterialMath();
        int[] distances={Integer.MIN_VALUE,-1000,-1,0,1,2,3,254,255,256,1000,65535,Integer.MAX_VALUE,1000};
        for(int distance:distances) {
            DirectX7.fDist=distance;
            for(int n=0;n<3000;n++) {
                int z=n%3==0?random.nextInt():n%3==1?-random.nextInt(5000):new int[]{0,-1,Integer.MIN_VALUE,Integer.MAX_VALUE}[n%4];
                check(math.visibility(z)==visible(z,distance),"cached fade mismatch distance="+distance+" rz="+z);
                if(distance!=0) check(math.fogVisibility(z)==255-clamp(-(long)z*255/distance),"HQ fog mismatch");
                else {
                    boolean threw=false;
                    try {math.fogVisibility(z);} catch(ArithmeticException expected) {threw=true;}
                    check(threw,"HQ zero-distance division contract changed");
                }
                int divisor=distance/255;
                check(math.flatVisibility(z)==(divisor==0?255:255-clamp(-(long)z/divisor)),"flat visibility mismatch");
                check(math.additiveAmount(z)==clamp(-(long)z/(divisor==0?255:divisor)),"additive fog mismatch");
            }
            for(int move:new int[]{0,1,20,200,Integer.MAX_VALUE}) {
                float expected=1.0f+(float)distance/move;
                check(Float.floatToIntBits(math.fogFar(move))==Float.floatToIntBits(expected),"cached Fog far mismatch");
                check(Float.floatToIntBits(math.fogFar(move))==Float.floatToIntBits(expected),"warm Fog far mismatch");
            }
        }
        DirectX7.fDist=1000;math.visibility(-100);int[] keys=(int[])field(math,"depths"),values=(int[])field(math,"visibility");
        check(keys.length==64 && values.length==64,"unbounded fade cache");
        int slot=(-100^(-100>>>4))&63;
        for(int i=0;i<100;i++) check(math.visibility(-100)==230 && values[slot]==231,"shared depth cache");
        int collision=-101;while(((collision^(collision>>>4))&63)!=slot) collision--;
        check(math.visibility(collision)==visible(collision,1000) && keys[slot]==collision,"collision didn't replace key");
        check(math.visibility(-100)==230 && keys[slot]==-100,"collision reused stale value");
        DirectX7.fDist=2000;check(math.visibility(-100)==visible(-100,2000),"distance change didn't invalidate");
        int occupied=0;for(int value:values) if(value!=0) occupied++;check(occupied==1,"old distance entries survived invalidation");
        System.out.println("PASS exact distance+flat+additive fog / extreme depths+distance / bounded 64-slot reuse+collision+invalidation / cached float Fog endpoints");

        M3GSceneRenderer renderer=new M3GSceneRenderer();DirectX7 g=new DirectX7(32,32);
        Vertex[] v=(Vertex[])field(renderer,"vertices");for(int i=0;i<4;i++) v[i]=new Vertex();
        int[] uv=(int[])field(renderer,"uv");math=(M3GMaterialMath)field(renderer,"materialMath");
        Method use=renderer.getClass().getDeclaredMethod("usePerspective",Texture.class,int.class,boolean.class);use.setAccessible(true);
        Method select=renderer.getClass().getDeclaredMethod("selectImage",Texture.class,RenderObject.class,int.class,boolean.class);select.setAccessible(true);
        RawImage[] levels={mip(64),mip(32),mip(16),mip(8)};Texture t=new Texture(levels[0],true,null);
        for(int n=0;n<12000;n++) {
            int count=3+(n&1);RenderObject p=polygon(count==3?0:1,v);
            for(int i=0;i<count;i++) {
                v[i].sx=n%10==0?random.nextInt():random.nextInt(110);
                v[i].sy=n%10==0?random.nextInt():random.nextInt(110);
                uv[i*2]=random.nextInt(8193)-4096;uv[i*2+1]=random.nextInt(8193)-4096;
            }
            Main.persQ=n%5;Main.q=n%3==0?1000:24;Main.mipMapping=n%7!=0;
            t.perspectiveCorrection=n%11!=0;t.rImg=levels[n%levels.length];
            t.mip=n%13==0?null:n%17==0?new RawImage[]{levels[0],null,levels[2]}:levels;
            p.ny=(short)(n%3==0?4096:n%3==1?-4096:0);boolean fast=n%6==0;
            math.resetBounds();
            check(((Boolean)use.invoke(renderer,t,count,fast)).booleanValue()==perspective(t,v,count,fast),"perspective bounds changed");
            check(select.invoke(renderer,t,p,count,fast)==select(t,p,v,uv,count,fast),"selected mip differs from original thresholds");
        }
        math.resetBounds();math.measure(v,4);long span=math.spanX;v[0].sx=Integer.MIN_VALUE;
        math.measure(v,4);check(math.spanX==span,"bounds scanned twice in one polygon");
        math.resetBounds();math.measure(v,4);check(math.spanX!=span,"next polygon reused old bounds");
        System.out.println("PASS 12000 original mip+perspective decisions / one lazy bounds scan / reset per polygon / floor+q+fast+mip+UV+animation arrays / no hardware mip substitution");

        Method colors=renderer.getClass().getDeclaredMethod("setColors",DirectX7.class,RenderObject.class,int.class,int.class);colors.setAccessible(true);
        byte[] rgba=(byte[])field(renderer,"colors");
        for(int kind=0;kind<6;kind++) {
            RenderObject p=polygon(kind,v);int count=(kind&1)==0?3:4;
            for(int n=0;n<400;n++) {
                int[][] channels=new int[count][3];
                for(int i=0;i<count;i++) {
                    v[i].rz=n%9==0?random.nextInt():-random.nextInt(2000);
                    for(int c=0;c<3;c++) channels[i][c]=random.nextInt(800)-250;
                    if(kind>=2) {
                        String vertex="abcd".substring(i,i+1);
                        if(kind<=3) {p.getClass().getField("l"+vertex).setInt(p,channels[i][0]);channels[i][1]=channels[i][2]=channels[i][0];}
                        else for(int c=0;c<3;c++) p.getClass().getField(vertex+"rgb".substring(c,c+1)).setInt(p,channels[i][c]);
                    }
                }
                int mode=new int[]{0,9,13}[n%3];Main.fogQ=(n/3)%3;DirectX7.fDist=distances[n%distances.length];
                colors.invoke(renderer,g,p,count,mode);
                for(int i=0;i<count;i++) {
                    int r=255,green=255,b=255;
                    if(kind>=2 && (mode==9 || mode==13) && Main.fogQ>0) {
                        int visibility=visible(v[i].rz,DirectX7.fDist);
                        r=clamp(channels[i][0])*visibility/255;green=clamp(channels[i][1])*visibility/255;b=clamp(channels[i][2])*visibility/255;
                        if(mode==9) green=b=r;else if(Main.fogQ==1) r=green=b=(r+green+b)/3;
                    }
                    check((rgba[i*4]&255)==r && (rgba[i*4+1]&255)==green && (rgba[i*4+2]&255)==b && (rgba[i*4+3]&255)==255,"optimized baked colors differ from original");
                }
            }
        }
        renderer.dispose();g.destroy();
        System.out.println("PASS 2400 live-mutated polygon color fixtures / six types / original RGB+gray+LQ rounding / distance changes / no stale baked-light cache");
    }
}
