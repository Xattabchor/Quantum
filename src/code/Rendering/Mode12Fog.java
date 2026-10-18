package code.Rendering;

/** Level-local EXP parameters and API-free CPU vertex fog recovery. */
final class Mode12Fog {
    private static float density=0.02f;
    private static int color;
    private static boolean customColor;
    static float density() {return density;}
    static int color() {return customColor?color:DirectX7.fogc&0xffffff;}
    static void configure(String rgb,String value) {
        density=0.02f;customColor=false;color=0;
        if(value!=null) try {
            float parsed=Float.parseFloat(value.trim());
            if(parsed>=0 && parsed<=1000) density=parsed; // Also rejects NaN/infinity.
        } catch(RuntimeException invalid) { }
        if(rgb!=null) try {
            int first=rgb.indexOf(','),last=rgb.lastIndexOf(',');
            if(first<0 || first==last) return;
            int r=Integer.parseInt(rgb.substring(0,first).trim());
            int g=Integer.parseInt(rgb.substring(first+1,last).trim());
            int b=Integer.parseInt(rgb.substring(last+1).trim());
            if(r<0 || r>255 || g<0 || g>255 || b<0 || b>255) return;
            color=(r<<16)|(g<<8)|b;customColor=true;
        } catch(RuntimeException invalid) { }
    }
    static int factor(int rz) {
        // M3G eye distance is 1 - rz/move, NOT screen focal distance.
        double x=density*(1+Math.max(0,-(double)rz)/Math.max(1,TexturingPers.move));
        if(x>=20) return 0;
        // CLDC Math has no exp: range reduction + fourth-order Taylor, squared
        // eight times. CPU recovery only, never evaluated in the native path.
        double y=x/256,v=1-y+y*y*(0.5-y*(1.0/6-y/24));
        for(int i=0;i<8;i++) v*=v;
        return (int)Math.max(0,Math.min(255,v*255+0.5));
    }
}
