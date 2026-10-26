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
public class LightedPolygon3V extends Polygon3V {

    public volatile int la,lb,lc;
    
    private static int q,sz2;
    private static int sizex,sizey,sizeu,sizev;
    private static int addmip,fog;
    
    public LightedPolygon3V(LightedPolygon3V p) {
        super((Polygon3V)p);
        la=p.la; lb=p.lb; lc=p.lc;
    }
    
    public LightedPolygon3V(ColorLightedPolygon3V p) {
        super((Polygon3V)p);
        la=((p.ar+p.ag+p.ab)/3); 
        lb=((p.br+p.bg+p.bb)/3); 
        lc=((p.cr+p.cg+p.cb)/3);
    }
    
    public LightedPolygon3V(Vertex a2, Vertex b2, Vertex c2, byte au, byte av, byte bu, byte bv, byte cu, byte cv) {
        super(a2, b2, c2, au, av, bu, bv, cu, cv);
        la=lb=lc=255;
    }
    

    // CPU rasterization removed - M3G-only build (kordos).
    public void render(DirectX7 g3d, Texture texture) {}

    public void renderFast(DirectX7 g3d, Texture texture) {}

}
