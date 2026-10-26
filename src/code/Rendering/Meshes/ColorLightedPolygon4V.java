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
public class ColorLightedPolygon4V extends Polygon4V {

    public volatile int ar,ag,ab;
    public volatile int br,bg,bb;
    public volatile int cr,cg,cb;
    public volatile int dr,dg,db;
    
    private static int q,sz2;
    private static int sizex,sizey,sizeu,sizev;
    private static int addmip,fog;
    
    public ColorLightedPolygon4V(ColorLightedPolygon4V p) {
        super((Polygon4V)p);
        ar=p.ar; ag=p.ag; ab=p.ab;
        br=p.br; bg=p.bg; bb=p.bb;
        cr=p.cr; cg=p.cg; cb=p.cb;
        dr=p.dr; dg=p.dg; db=p.db;
    }
    
    public ColorLightedPolygon4V(Vertex a2, Vertex b2, Vertex c2, Vertex d2, byte au, byte av, byte bu, byte bv, byte cu, byte cv, byte du, byte dv) {
        super(a2, b2, c2, d2, au, av, bu, bv, cu, cv, du, dv);
        ar=ag=ab=br=bg=bb=cr=cg=cb=dr=dg=db=255;
    }
    

    // CPU rasterization removed - M3G-only build (kordos).
    public void render(DirectX7 g3d, Texture texture) {}

    public void renderFast(DirectX7 g3d, Texture texture) {}

}
