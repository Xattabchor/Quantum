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
        /* CPU fallback removed - native M3G path owns this object (kordos). */
    }
}
