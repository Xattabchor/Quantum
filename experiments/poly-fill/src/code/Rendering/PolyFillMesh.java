package code.Rendering;

import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Synthetic workload using the engine's native cached VertexArray/Image-target
 * path and Texture/RawImage conversion. One render call, no per-frame uploads.
 */
final class PolyFillMesh {
    static final int[] GRIDS={8,16,24,32,48,64,96,120};
    static final int[] LAYERS={1,2,4,8,16,32};
    final boolean fill;
    final int level,triangles,vertexCount,indexCount,stripCount,layers;
    private VertexBuffer vertices;
    private TriangleStripArray indices;
    private Appearance appearance;
    private final Graphics3D graphics=Graphics3D.getInstance();
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform view=new Transform();
    private final Background background=new Background();
    private boolean disposed;

    static int levels(boolean fill){return fill?LAYERS.length:GRIDS.length;}
    static int slot(boolean fill,int level){return (fill?GRIDS.length:0)+level;}
    static int total(){return GRIDS.length+LAYERS.length;}

    static Texture2D checker() {
        Object cap=Graphics3D.getProperties().get("maxTextureDimension");
        if(cap instanceof Integer && ((Integer)cap).intValue()<64)throw new IllegalArgumentException("64x64 texture unsupported");
        int[] pixels=new int[64*64];
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)pixels[y*64+x]=(((x/8)^(y/8))&1)==0?0xffb8dc72:0xff365b79;
        Texture engine=new Texture(new RawImage(pixels,64,64),true,null);engine.drawmode=9;
        byte[] rgba=new byte[64*64*4];
        for(int i=0;i<engine.rImg.img.length;i++) {
            int p=engine.rImg.img[i];rgba[i*4]=(byte)(p>>16);rgba[i*4+1]=(byte)(p>>8);rgba[i*4+2]=(byte)p;rgba[i*4+3]=(byte)255;
        }
        Texture2D texture=new Texture2D(new Image2D(Image2D.RGBA,64,64,rgba));
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_REPEAT,Texture2D.WRAP_REPEAT);texture.setBlending(Texture2D.FUNC_MODULATE);return texture;
    }
    PolyFillMesh(boolean fill,int level,Texture2D texture) {
        if(level<0 || level>=levels(fill) || texture==null)throw new IllegalArgumentException("workload");
        this.fill=fill;this.level=level;layers=fill?LAYERS[level]:1;
        int grid=fill?1:GRIDS[level];
        vertexCount=fill?4*layers:(grid+1)*(grid+1);
        triangles=fill?2*layers:2*grid*grid;
        stripCount=fill?layers:grid;indexCount=fill?4*layers:2*(grid+1)*grid;
        if(vertexCount>16384)throw new IllegalArgumentException("engine packet vertex budget");
        short[] xyz=new short[vertexCount*3],uv=new short[vertexCount*2];byte[] colors=new byte[vertexCount*4];
        int[] order=new int[indexCount],lengths=new int[stripCount];
        if(fill) {
            for(int layer=0;layer<layers;layer++) {
                int base=layer*4;
                for(int row=0;row<2;row++)for(int col=0;col<2;col++)point(xyz,uv,colors,base+row*2+col,col,row,1);
                int o=layer*4;order[o]=base+2;order[o+1]=base;order[o+2]=base+3;order[o+3]=base+1;lengths[layer]=4;
            }
        } else {
            for(int row=0;row<=grid;row++)for(int col=0;col<=grid;col++)point(xyz,uv,colors,row*(grid+1)+col,col,row,grid);
            int dest=0;
            for(int row=0;row<grid;row++) {
                lengths[row]=2*(grid+1);
                for(int col=0;col<=grid;col++){order[dest++]=(row+1)*(grid+1)+col;order[dest++]=row*(grid+1)+col;}
            }
        }
        VertexArray positions=new VertexArray(vertexCount,3,2),coords=new VertexArray(vertexCount,2,2),rgba=new VertexArray(vertexCount,4,1);
        positions.set(0,vertexCount,xyz);coords.set(0,vertexCount,uv);rgba.set(0,vertexCount,colors);
        vertices=new VertexBuffer();vertices.setPositions(positions,1.0f/16384,null);vertices.setTexCoords(0,coords,1.0f/256,null);vertices.setColors(rgba);
        indices=new TriangleStripArray(order,lengths);
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_BACK);polygon.setWinding(PolygonMode.WINDING_CW);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);polygon.setPerspectiveCorrectionEnable(true);
        CompositingMode composite=new CompositingMode();composite.setBlending(CompositingMode.REPLACE);
        // FILL intentionally has no early-Z rejection of overlapping layers.
        composite.setDepthTestEnable(!fill);composite.setDepthWriteEnable(!fill);composite.setAlphaThreshold(.5f);
        appearance=new Appearance();appearance.setTexture(0,texture);appearance.setPolygonMode(polygon);appearance.setCompositingMode(composite);
        float[] p=new float[16];p[0]=p[5]=p[15]=1;p[10]=-2;p[11]=-2;
        Transform projection=new Transform();projection.set(p);camera.setGeneric(projection);
        background.setColor(0x102030);background.setColorClearEnable(true);background.setDepthClearEnable(true);
    }
    private static void point(short[] xyz,short[] uv,byte[] color,int index,int col,int row,int grid) {
        xyz[index*3]=(short)(-16384+32768*col/grid);xyz[index*3+1]=(short)(16384-32768*row/grid);xyz[index*3+2]=-16384;
        uv[index*2]=(short)(256*col/grid);uv[index*2+1]=(short)(256*row/grid);
        color[index*4]=color[index*4+1]=color[index*4+2]=(byte)220;color[index*4+3]=(byte)255;
    }
    void render(Graphics target,int width,int height) {
        if(disposed)throw new IllegalStateException("disposed workload");
        if(target==null || width<1 || height<1)throw new IllegalArgumentException("viewport");
        graphics.bindTarget(target,true,0);
        try {
            graphics.setViewport(0,0,width,height);graphics.setCamera(camera,view);graphics.resetLights();graphics.clear(background);
            graphics.render(vertices,indices,appearance,null);
        }finally{graphics.releaseTarget();}
    }
    void dispose(){vertices=null;indices=null;appearance=null;disposed=true;}
}
