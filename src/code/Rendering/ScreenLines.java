package code.Rendering;

/** Reusable screen-space effect batch, without optional API dependencies.
 * Queue only LEGACY_LINE batches (lightning). Rain/snow must be submitted with
 * DirectX7.renderGameFrame (or standalone renderScreenLines), with
 * WeatherGenerator.paintAfterScene as LCDUI fallback.
 */
public final class ScreenLines extends RenderObject {
    public static final int LINE = 0, LEGACY_LINE = 1, RECT = 2;
    private int[] data = new int[0];
    public int count;

    public void clear() { count = 0; }
    public int get(int index, int field) { return data[index*7+field]; }

    public void line(int x1,int y1,int x2,int y2,int width,int rgb,boolean legacy) {
        add(x1,y1,x2,y2,width,rgb,legacy ? LEGACY_LINE : LINE);
    }
    public void rect(int x,int y,int width,int height,int rgb) {
        if(width > 0 && height > 0) add(x,y,x+width,y+height,0,rgb,RECT);
    }
    private void add(int x1,int y1,int x2,int y2,int width,int rgb,int kind) {
        if((count+1)*7 > data.length) {
            int[] next = new int[data.length+64*7];
            System.arraycopy(data,0,next,0,count*7); data = next;
        }
        int p = count++*7;
        data[p]=x1; data[p+1]=y1; data[p+2]=x2; data[p+3]=y2;
        data[p+4]=width; data[p+5]=rgb; data[p+6]=kind;
    }
    public boolean isVisible(int x1,int y1,int x2,int y2) { return count > 0; }
    public void renderFast(DirectX7 g,Texture t) { render(g,t); }
    public void render(DirectX7 g,Texture t) { 
        /* CPU fallback removed - native M3G path owns this object (kordos). */
    }
}
