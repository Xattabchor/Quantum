package code.Rendering.Meshes;

import code.Gameplay.Map.LightMapper;
import code.Math.MathUtils;
import code.Rendering.DirectX7;
import code.Rendering.Texture;
import code.Rendering.Vertex;
import code.utils.Main;

/**
 *
 * @author Roman Lahin
 */
public class ColorLightedPolygon3V extends Polygon3V {

    public volatile int ar,ag,ab;
    public volatile int br,bg,bb;
    public volatile int cr,cg,cb;
    
    private static int q,sz2;
    private static int sizex,sizey,sizeu,sizev;
    private static int addmip,fog;
    
    public ColorLightedPolygon3V(ColorLightedPolygon3V p) {
        super((Polygon3V)p);
        ar=p.ar; ag=p.ag; ab=p.ab;
        br=p.br; bg=p.bg; bb=p.bb;
        cr=p.cr; cg=p.cg; cb=p.cb;
    }
    
    public ColorLightedPolygon3V(Vertex a2, Vertex b2, Vertex c2, byte au, byte av, byte bu, byte bv, byte cu, byte cv) {
        super(a2, b2, c2, au, av, bu, bv, cu, cv);
        ar=ag=ab=br=bg=bb=cr=cg=cb=255;
    }
    

    // CPU rasterization removed - M3G-only build (kordos).
    public void render(DirectX7 g3d, Texture texture) {}

    public void renderFast(DirectX7 g3d, Texture texture) {}

}
