package code.Rendering.Meshes;
import code.Math.MathUtils;
import code.Rendering.DirectX7;
import code.Rendering.RenderObject;
import code.Rendering.Texture;
import code.Rendering.Vertex;
import code.utils.Main;

/**
 *
 * @author DDDENISSS
 */
public class Polygon3V extends RenderObject {
    
    public Vertex a, b, c;
    public int au, av;
    public int bu, bv;
    public int cu, cv;

public byte tex=(byte)0;
private static int q,sz2;
private static int sizex,sizey,sizeu,sizev;
private static int addmip,fog;



    public Polygon3V(Polygon3V p) {
        nx=p.nx;
        ny=p.ny;
        nz=p.nz;
        this.a = p.a;
        this.au = p.au;
        this.av = p.av;

        this.b = p.b;
        this.bu = p.bu;
        this.bv = p.bv;

        this.c = p.c;
        this.cu = p.cu;
        this.cv = p.cv;
        tex = p.tex;
    }

    public Polygon3V(Vertex a2, Vertex b2, Vertex c2,
            byte au, byte av,
            byte bu, byte bv,
            byte cu, byte cv) {
        super(a2, b2, c2);
        this.a = a2;
        this.au = au & 0xff;
        this.av = av & 0xff;
        
        this.b = b2;
        this.bu = bu & 0xff;
        this.bv = bv & 0xff;
        
        this.c = c2;
        this.cu = cu & 0xff;
        this.cv = cv & 0xff;
    }






public final void szCalcfogAdd() {
sz = a.rz;
if (c.rz<sz) sz=c.rz;
if (b.rz<sz) sz=b.rz;
sz2=-sz/255;
if(DirectX7.fDist/255!=0) sz2=-sz/(DirectX7.fDist/255);

if(sz2>255) sz2=255;
if(sz2<0) sz2=0;
sz2=255-sz2;
int cr = (DirectX7.fogc >> 16) & 0xff;
int cg = (DirectX7.fogc >> 8) & 0xff;
int cb = DirectX7.fogc & 0xff;

cr = cr-sz2;
if(cr<0) cr=0;
cg = cg-sz2;
if(cg<0) cg=0;
cb = cb-sz2;
if(cb<0) cb=0;

sz2 = (cr << 16) | (cg << 8) | cb;
}

    public final boolean isVisible(int x1, int y1, int x2, int y2) {
        if (a.sx < x1 && b.sx < x1 && c.sx < x1) return false;
        if (a.sx > x2 && b.sx > x2 && c.sx > x2) return false;
        if (a.sy < y1 && b.sy < y1 && c.sy < y1) return false;
        if (a.sy > y2 && b.sy > y2 && c.sy > y2) return false;
        
        if( (a.sx-b.sx)*(b.sy-c.sy) <=  (a.sy-b.sy)*(b.sx-c.sx)  ) return false;

        sz = a.rz;
        if (c.rz < sz) sz = c.rz;
        if (b.rz < sz) sz = b.rz;
        if (sz > 0) return false;
        
        if (-sz > DirectX7.drDist) return false;
        
        sz = ((a.rz + b.rz + c.rz)/*-MathUtils.pLength(size(a.x,b.x,c.x),size(a.y,b.y,c.y),size(a.z,b.z,c.z))*/)*4/3;
        if (ny > 4000 || ny < -4000) sz += Main.floorOffsetSZ;
        return true;
    }

    

    // CPU rasterization removed - M3G-only build (kordos).
    public void render(DirectX7 g3d, Texture texture) {}

    public void renderFast(DirectX7 g3d, Texture texture) {}

}
