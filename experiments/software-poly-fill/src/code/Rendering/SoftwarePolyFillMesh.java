package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.LightedPolygon3V;
import code.utils.Main;
import code.HUD.DeveloperMenu;
import javax.microedition.lcdui.Graphics;

/** Frozen Quantum SOFTWARE: clear, transform/project, visibility/queue/sort,
 * original LightedPolygon3V + TexturingPers/Affine, MIDP drawRGB presentation.
 * No M3G use and no replacement rasterizer. Geometry is cold-built. */
final class SoftwarePolyFillMesh {
    static final int[] GRIDS={8,16,24,32,48,64,96,120};
    static final int[] LAYERS={1,2,4,8,16,32};
    final boolean fill,perspective;
    final int level,triangles,vertexCount,layers,width,height;
    private DirectX7 cpu;
    private Mesh mesh;
    private final Matrix identity=new Matrix();
    private boolean disposed;
    static int levels(boolean fill){return fill?LAYERS.length:GRIDS.length;}
    static int slot(boolean fill,int level){return (fill?GRIDS.length:0)+level;}
    static int total(){return GRIDS.length+LAYERS.length;}
    static Texture checker() {
        int[] pixels=new int[64*64];
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)pixels[y*64+x]=(((x/8)^(y/8))&1)==0?0xffb8dc72:0xff365b79;
        Texture texture=new Texture(new RawImage(pixels,64,64),true,null);texture.drawmode=9;return texture;
    }
    SoftwarePolyFillMesh(boolean fill,int level,Texture texture,int width,int height,boolean perspective) {
        if(level<0 || level>=levels(fill) || texture==null || width<1 || height<1 || width>2048 || height>2048)throw new IllegalArgumentException("workload/viewport");
        this.fill=fill;this.level=level;this.width=width;this.height=height;this.perspective=perspective;
        int grid=fill?1:GRIDS[level];layers=fill?LAYERS[level]:1;
        if(width<grid || height<grid)throw new IllegalArgumentException("grid exceeds pixel resolution");
        vertexCount=fill?4*layers:(grid+1)*(grid+1);triangles=2*grid*grid*layers;
        cpu=new DirectX7(width,height);cpu.setRenderer(DirectX7.SOFTWARE);
        // The engine's focal-plane-shift projection halves these exact integer
        // world coordinates at constant Z. This maps to an entire pixel rectangle.
        cpu.distX=cpu.distY=16384;DirectX7.fDist=1;DirectX7.drDist=30000000;
        Vertex[] vertices=new Vertex[vertexCount];LightedPolygon3V[] faces=new LightedPolygon3V[triangles];
        for(int l=0;l<layers;l++) {
            int base=l*(grid+1)*(grid+1);
            for(int row=0;row<=grid;row++)for(int col=0;col<=grid;col++) {
                int sx=width*col/grid,sy=height*row/grid;
                vertices[base+row*(grid+1)+col]=new Vertex(2*(sx-cpu.centreX),2*(cpu.centreY-sy),-16384);
            }
            int face=l*grid*grid*2;
            for(int row=0;row<grid;row++)for(int col=0;col<grid;col++) {
                int tl=base+row*(grid+1)+col,tr=tl+1,bl=tl+grid+1,br=bl+1;
                faces[face++]=triangle(vertices,bl,tl,br,col,row+1,col,row,col+1,row+1,grid);
                faces[face++]=triangle(vertices,br,tl,tr,col+1,row+1,col,row,col+1,row,grid);
            }
        }
        MultyTexture materials=new MultyTexture();materials.textures=new Texture[]{texture};
        mesh=new Mesh(vertices,faces,materials);configure();cpu.transformAndProjectVertices(mesh,identity);
        for(int i=0;i<faces.length;i++)if(!faces[i].isVisible(0,0,width,height))throw new IllegalArgumentException("collapsed/backface triangle");
        // Allocate the real engine queue once, outside the measurement window.
        cpu.addMesh(mesh,0,0,width,height);cpu.renderSoftware();
    }
    private LightedPolygon3V triangle(Vertex[] v,int a,int b,int c,int ax,int ay,int bx,int by,int cx,int cy,int grid) {
        LightedPolygon3V p=new LightedPolygon3V(v[a],v[b],v[c],(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0);
        p.au=256*ax/grid;p.av=256*ay/grid;p.bu=256*bx/grid;p.bv=256*by/grid;p.cu=256*cx/grid;p.cv=256*cy/grid;
        // Original software getLight multiplies by255/256 even with fDist1:
        //221*255>>8 gives220 in P4. P0 renderFast bypasses getLight,
        // so its original affine sampler receives220 directly.
        p.la=p.lb=p.lc=perspective?221:220;return p;
    }
    private void configure() {
        Main.persQ=perspective?4:0;Main.q=24;Main.fogQ=2;Main.pixelsQ=2;Main.mipMapping=false;
        DeveloperMenu.renderPolygonsOverwrite=false;DirectX7.fDist=1;
    }
    void render(int width,int height) {
        if(disposed)throw new IllegalStateException("disposed workload");
        if(width!=this.width || height!=this.height)throw new IllegalArgumentException("rebuild after resize");
        configure();cpu.clearDisplay(0x102030);cpu.transformAndProjectVertices(mesh,identity);
        cpu.addMesh(mesh,0,0,width,height);cpu.renderSoftware();
    }
    void present(Graphics target,int x,int y){if(disposed)throw new IllegalStateException("disposed workload");cpu.flush(target,x,y);}
    void dispose(){if(cpu!=null)cpu.destroy();cpu=null;mesh=null;disposed=true;}
}
