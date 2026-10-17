package code.Rendering;

/** Full-frame solid sky, rendered before the world, without a texture. */
public final class SolidSky extends RenderObject {
    final int color;
    final Vertex[] vertices={new Vertex(0,0,-1),new Vertex(0,0,-1),
            new Vertex(0,0,-1),new Vertex(0,0,-1)};

    public SolidSky(int color) { this.color=color; }

    void prepare(DirectX7 g) {
        set(vertices[0],0,0);set(vertices[1],g.width,0);
        set(vertices[2],g.width,g.height);set(vertices[3],0,g.height);
    }
    private static void set(Vertex v,int x,int y) {v.sx=x;v.sy=y;v.rz=-1;}
    public boolean isVisible(int x1,int y1,int x2,int y2) {return true;}
    public void render(DirectX7 g,Texture t) {g.clearDisplay(color);}
    public void renderFast(DirectX7 g,Texture t) {render(g,t);}
}
