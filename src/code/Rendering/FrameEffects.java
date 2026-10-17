package code.Rendering;

/** Selected-M3G post effects, outside the sorted world queue. No optional API. */
public final class FrameEffects extends RenderObject {
    byte[] vignette;
    int tint;
    // CPU recovery only: 2 KiB, lazy and reused across tint/size changes.
    private int[] cpuTint;
    private int cpuTintColor=-1;
    private boolean cpuTintUnavailable;

    void set(byte[] mask,boolean dead,boolean damaged) {
        vignette=mask!=null && mask.length>0?mask:null;
        // A red multiply filter and black death overlay compose by multiplying
        // their factors. One native MODULATE draw, no alpha/extra overlay pass.
        int r=dead?128:255;
        int gb=damaged?(dead?32:64):r;
        tint=(r<<16)|(gb<<8)|gb;
    }
    boolean active() {return vignette!=null || tint!=0xffffff;}

    int factor(int index,int pixels) {
        if(vignette==null) return 256;
        int size=Math.min(pixels,vignette.length);
        if(index>=size) return 256;
        // Original unrolled vignette shares each even coefficient with its
        // next pixel; the final incomplete block uses individual coefficients.
        if(index<(size&~7)) index&=~1;
        return vignette[index]+128;
    }
    public boolean isVisible(int a,int b,int c,int d) {return true;}
    public void renderFast(DirectX7 g,Texture t) {render(g,t);}
    public void render(DirectX7 g,Texture t) {
        if(!active()) return;
        // set() uses equal green/blue; preserve custom package callers too.
        if(((tint>>8)&255)!=(tint&255)) {renderArithmetic(g);return;}
        if(cpuTint==null) {
            if(cpuTintUnavailable) {renderArithmetic(g);return;}
            try {cpuTint=new int[512];cpuTintColor=~tint;}
            catch(OutOfMemoryError ex) {cpuTintUnavailable=true;renderArithmetic(g);return;}
        }
        int[] table=cpuTint,pixels=g.display;
        if(cpuTintColor!=tint) {
            int red=(tint>>16)&255,gb=tint&255;
            for(int i=0;i<256;i++) {table[i]=(i*red/255)<<16;table[256+i]=i*gb/255;}
            cpuTintColor=tint;
        }
        byte[] mask=vignette;
        int size=pixels.length,masked=mask==null?0:Math.min(size,mask.length);
        int paired=masked&~7,i=0;
        // Match factor(): only the complete eight-pixel prefix shares pairs.
        for(;i<paired;i+=2) {
            int v=mask[i]+128;
            pixels[i]=maskedPixel(pixels[i],v,table);
            pixels[i+1]=maskedPixel(pixels[i+1],v,table);
        }
        for(;i<masked;i++) pixels[i]=maskedPixel(pixels[i],mask[i]+128,table);
        for(;i<size;i++) {
            int p=pixels[i];
            pixels[i]=(p&0xff000000)|table[(p>>16)&255]
                    |(table[256+((p>>8)&255)]<<8)|table[256+(p&255)];
        }
    }
    private static int maskedPixel(int p,int v,int[] table) {
        return (p&0xff000000)|table[(((p>>16)&255)*v)>>8]
                |(table[256+((((p>>8)&255)*v)>>8)]<<8)|table[256+(((p&255)*v)>>8)];
    }

    private void renderArithmetic(DirectX7 g) {
        // Allocation failure must still recover the frame with the old loop.

        int red=(tint>>16)&255,green=(tint>>8)&255,blue=tint&255;
        int size=g.display.length;
        for(int i=0;i<size;i++) {
            int pixel=g.display[i],v=factor(i,size);
            int r=(((pixel>>16)&255)*v)>>8;
            int b=((pixel&255)*v)>>8;
            int c=(((pixel>>8)&255)*v)>>8;
            r*=red;c*=green;b*=blue;
            // Exact /255 for byte products, without three integer divisions.
            r=(r+1+(r>>>8))>>>8;c=(c+1+(c>>>8))>>>8;b=(b+1+(b>>>8))>>>8;
            g.display[i]=(pixel&0xff000000)|(r<<16)|(c<<8)|b;
        }
    }
}
