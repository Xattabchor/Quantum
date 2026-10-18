package code.Rendering;

import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Native Canvas foreground. Source pixels are read on cache miss only; the
 * world Image is never read back, copied or rebound for this pass. */
final class M3GCanvasImages {
    private static final int CACHE_ENTRIES=64, CACHE_PIXELS=262144, MAX_QUADS=512;
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final int tileSize;
    private final Vector cache=new Vector();
    private int cachedPixels;
    private final Appearance appearance=new Appearance();
    private final CompositingMode composite=new CompositingMode();
    private final VertexBuffer vertices=new VertexBuffer();
    private final VertexArray positions=new VertexArray(4,3,2),coords=new VertexArray(4,2,2),colors=new VertexArray(4,4,1);
    private final TriangleStripArray indices=new TriangleStripArray(new int[]{0,1,3,1,2,3},new int[]{3,3});
    private final short[] xyz=new short[12],uv=new short[8];
    private final byte[] rgba=new byte[16];
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform projection=new Transform();
    private final float[] matrix={0,0,0,0,0,0,0,0,0,0,-1,-0.02f,0,0,-1,0};
    private int projectionWidth,projectionHeight;
    private Texture2D[] prepared=new Texture2D[32];
    private int[] geometry=new int[32*9];
    private int count;

    private static final class Tile {
        Image source;
        int x,y,width,height,cost;
        Texture2D texture;
    }

    M3GCanvasImages(M3GSceneRenderer owner,Graphics3D graphics,int maxTexture) {
        this.owner=owner;this.graphics=graphics;
        tileSize=M3GSceneRenderer.spriteDimension(Math.min(128,maxTexture));
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setPerspectiveCorrectionEnable(false);
        appearance.setPolygonMode(polygon);appearance.setFog(null);
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setAlphaThreshold(0);appearance.setCompositingMode(composite);
        vertices.setTexCoords(0,coords,1.0f/4096,null);vertices.setColors(colors);
    }

    void draw(DirectX7 g,ScreenImages commands,Graphics target) {
        if(target.getTranslateX()!=0 || target.getTranslateY()!=0)
            throw new IllegalArgumentException("foreground needs untranslated Canvas Graphics");
        int precision=Math.min(16,30000/Math.max(commands.width,commands.height));
        if(precision<1 || tileSize<1) throw new IllegalArgumentException("foreground native limits");
        int clipX=target.getClipX(),clipY=target.getClipY(),clipW=target.getClipWidth(),clipH=target.getClipHeight();
        boolean bound=false;
        try {
            prepare(g,commands); // Upload ALL resources before binding Canvas.
            if(count==0) return;
            if(projectionWidth!=commands.width || projectionHeight!=commands.height) {
                matrix[0]=2.0f/commands.width;matrix[2]=1-2.0f*(commands.width/2)/commands.width;
                matrix[5]=2.0f/commands.height;matrix[6]=2.0f*(commands.height/2)/commands.height-1;
                projection.set(matrix);camera.setGeneric(projection);
                projectionWidth=commands.width;projectionHeight=commands.height;
            }
            target.setClip(0,0,commands.width,commands.height);
            owner.bindCanvasTarget(target);bound=true;
            graphics.setViewport(0,0,commands.width,commands.height);
            graphics.setCamera(camera,null);graphics.resetLights();
            for(int i=0;i<count;i++) {
                int p=i*9,left=geometry[p],top=geometry[p+1],right=geometry[p+2],bottom=geometry[p+3];
                vertex(0,left,top,commands,precision);vertex(1,right,top,commands,precision);
                vertex(2,right,bottom,commands,precision);vertex(3,left,bottom,commands,precision);
                uv[0]=uv[6]=(short)geometry[p+4];uv[2]=uv[4]=(short)geometry[p+6];
                uv[1]=uv[3]=(short)geometry[p+5];uv[5]=uv[7]=(short)geometry[p+7];
                int color=geometry[p+8];
                for(int v=0;v<4;v++) {
                    rgba[v*4]=(byte)(color>>16);rgba[v*4+1]=(byte)(color>>8);
                    rgba[v*4+2]=(byte)color;rgba[v*4+3]=(byte)255;
                }
                positions.set(0,4,xyz);coords.set(0,4,uv);colors.set(0,4,rgba);
                vertices.setPositions(positions,1.0f/precision,null);
                appearance.setTexture(0,prepared[i]);
                composite.setBlending(prepared[i]==null?CompositingMode.REPLACE:CompositingMode.ALPHA);
                owner.renderNative(g,vertices,indices,appearance);
            }
        } finally {
            try {if(bound) owner.releaseCanvasTarget();}
            finally {
                target.setClip(clipX,clipY,clipW,clipH);
                for(int i=0;i<count;i++) prepared[i]=null;
                count=0;
            }
        }
    }

    private void vertex(int i,int x,int y,ScreenImages commands,int precision) {
        xyz[i*3]=(short)((x-commands.width/2)*precision);
        xyz[i*3+1]=(short)((commands.height/2-y)*precision);xyz[i*3+2]=(short)-precision;
    }
    private void prepare(DirectX7 g,ScreenImages commands) {
        for(int i=0;i<commands.count;i++) {
            int x=commands.get(i,1),y=commands.get(i,2),w=commands.get(i,3),h=commands.get(i,4);
            int left=Math.max(x,commands.get(i,8)),top=Math.max(y,commands.get(i,9));
            int right=(int)Math.min((long)x+w,commands.get(i,10));
            int bottom=(int)Math.min((long)y+h,commands.get(i,11));
            if(left>=right || top>=bottom) continue;
            if(commands.get(i,0)==ScreenImages.RECT) {
                add(null,left,top,right,bottom,0,0,0,0,commands.get(i,7));continue;
            }
            Image source=commands.image(i);
            int sx=commands.get(i,5),sy=commands.get(i,6);
            boolean mirror=commands.get(i,7)==2;
            int sl=sx+(mirror?w-(right-x):left-x),sr=sx+(mirror?w-(left-x):right-x);
            int st=sy+top-y,sb=sy+bottom-y;
            for(int ty=st/tileSize;ty<=(sb-1)/tileSize;ty++)
                for(int tx=sl/tileSize;tx<=(sr-1)/tileSize;tx++) {
                    Tile tile=tile(g,source,tx*tileSize,ty*tileSize);
                    int a=Math.max(sl,tile.x),b=Math.min(sr,tile.x+tileSize);
                    int c=Math.max(st,tile.y),d=Math.min(sb,tile.y+tileSize);
                    int dl=mirror?x+w-(b-sx):x+a-sx,dr=mirror?x+w-(a-sx):x+b-sx;
                    int u1=(mirror?b:a)-tile.x,u2=(mirror?a:b)-tile.x;
                    add(tile.texture,dl,y+c-sy,dr,y+d-sy,
                            u1*4096/tile.width,(c-tile.y)*4096/tile.height,
                            u2*4096/tile.width,(d-tile.y)*4096/tile.height,0xffffff);
                }
        }
    }
    private Tile tile(DirectX7 g,Image source,int x,int y) {
        for(int i=0;i<cache.size();i++) {
            Tile t=(Tile)cache.elementAt(i);
            if(t.source==source && t.x==x && t.y==y) {
                cache.removeElementAt(i);cache.addElement(t);return t;
            }
        }
        int w=Math.min(tileSize,source.getWidth()-x),h=Math.min(tileSize,source.getHeight()-y);
        Tile t=new Tile();t.source=source;t.x=x;t.y=y;t.width=pot(w);t.height=pot(h);t.cost=t.width*t.height;
        int[] pixels=new int[w*h];source.getRGB(pixels,0,w,x,y,w,h);
        byte[] bytes=new byte[t.cost*4];
        for(int j=0;j<h;j++) for(int i=0;i<w;i++) {
            int c=pixels[j*w+i],p=(j*t.width+i)*4;
            bytes[p]=(byte)(c>>16);bytes[p+1]=(byte)(c>>8);bytes[p+2]=(byte)c;bytes[p+3]=(byte)(c>>>24);
        }
        Texture2D texture=new Texture2D(owner.uploadImage(g,Image2D.RGBA,t.width,t.height,bytes));
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP,Texture2D.WRAP_CLAMP);
        texture.setBlending(Texture2D.FUNC_MODULATE);t.texture=texture;
        while(cache.size()>0 && (cache.size()>=CACHE_ENTRIES || cachedPixels+t.cost>CACHE_PIXELS)) {
            Tile old=(Tile)cache.elementAt(0);cachedPixels-=old.cost;cache.removeElementAt(0);
        }
        cache.addElement(t);cachedPixels+=t.cost;
        return t;
    }
    private static int pot(int n) {int p=1;while(p<n)p<<=1;return p;}
    private void add(Texture2D texture,int l,int t,int r,int b,int u1,int v1,int u2,int v2,int color) {
        if(count==MAX_QUADS) throw new IllegalStateException("foreground quad limit");
        if(count==prepared.length) {
            int n=Math.min(MAX_QUADS,prepared.length*2);Texture2D[] next=new Texture2D[n];int[] values=new int[n*9];
            System.arraycopy(prepared,0,next,0,count);System.arraycopy(geometry,0,values,0,count*9);
            prepared=next;geometry=values;
        }
        int p=count*9;prepared[count++]=texture;
        geometry[p]=l;geometry[p+1]=t;geometry[p+2]=r;geometry[p+3]=b;
        geometry[p+4]=u1;geometry[p+5]=v1;geometry[p+6]=u2;geometry[p+7]=v2;geometry[p+8]=color;
    }
}
