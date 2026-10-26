package code.Rendering;

import code.Gameplay.Map.Skybox;

/** Captured 2D panorama layout, without a dependency on the optional M3G API. */
public final class PanoramaSky extends RenderObject {
    private final Skybox owner;
    public final Texture texture;
    public final int color,groundColor;
    public int x1,y1,x2,y2,uStart,startY,endY,du,dv;

    public PanoramaSky(Skybox owner,Texture texture,int color,int groundColor) {
        this.owner=owner;this.texture=texture;this.color=color;this.groundColor=groundColor;
    }
    public void set(int x1,int y1,int x2,int y2,int uStart,int startY,int endY,int du,int dv) {
        this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;this.uStart=uStart;
        this.startY=startY;this.endY=endY;this.du=du;this.dv=dv;
    }
    public boolean isVisible(int x1,int y1,int x2,int y2) {return true;}
    public void renderFast(DirectX7 g,Texture t) {render(g,t);}
    public void render(DirectX7 g,Texture t) { 
        /* CPU fallback removed - native M3G path owns this object (kordos). */
    }
}
