package code.Rendering;

/** Selected-M3G post effects, outside the sorted world queue. No optional API. */
public final class FrameEffects extends RenderObject {
    byte[] vignette;
    int tint;

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
        // Failure/unsupported-target fallback: a SINGLE fused pixel loop.
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
