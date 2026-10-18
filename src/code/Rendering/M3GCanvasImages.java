package code.Rendering;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Native Canvas foreground. Source pixels are read on cache miss only; the
 * world Image is never read back, copied or rebound for this pass. */
final class M3GCanvasImages {
    private static final int CACHE_ENTRIES=64, CACHE_PIXELS=262144, MAX_QUADS=512, MAX_PACKET=16;
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final int tileSize,tileShift;
    // Render-thread-only bounded LRU: no synchronized Vector scans or shifts.
    private final Tile[] cache=new Tile[CACHE_ENTRIES];
    private int cacheSize,cachedPixels;
    private Tile oldest,lastTile;
    private int[] sourcePixels;
    private final Appearance appearance=new Appearance();
    private final CompositingMode composite=new CompositingMode();
    // Exact counts, not a 64-vertex buffer for every single-tile image.
    private final Packet[] packets=new Packet[MAX_PACKET];
    private static final int[] ORDER={0,1,3,1,2,3};
    private final short[] xyz=new short[MAX_PACKET*12],uv=new short[MAX_PACKET*8];
    private final byte[] rgba=new byte[MAX_PACKET*16];
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform projection=new Transform();
    private final float[] matrix={0,0,0,0,0,0,0,0,0,0,-1,-0.02f,0,0,-1,0};
    private int projectionWidth,projectionHeight;
    private Texture2D[] prepared=new Texture2D[32];
    private int[] geometry=new int[32*9];
    private int count;

    private static final class Tile {
        Image source;
        int x,y,width,height,cost,uScale,vScale,slot;
        Tile previous,next;
        Texture2D texture;
    }

    private static final class Packet {
        final VertexBuffer vertices=new VertexBuffer();
        final VertexArray positions,coords,colors,white;
        final TriangleStripArray indices;
        int precision;
        boolean whiteColors=true;
        Packet(int quads) {
            int count=quads*4;
            positions=new VertexArray(count,3,2);coords=new VertexArray(count,2,2);
            colors=new VertexArray(count,4,1);white=new VertexArray(count,4,1);
            byte[] values=new byte[count*4];for(int i=0;i<values.length;i++)values[i]=(byte)255;
            white.set(0,count,values);vertices.setColors(white);vertices.setTexCoords(0,coords,1.0f/4096,null);
            int[] order=new int[quads*6],lengths=new int[quads*2];
            for(int i=0;i<lengths.length;i++)lengths[i]=3;
            for(int i=0;i<quads;i++)for(int j=0;j<6;j++)order[i*6+j]=i*4+ORDER[j];
            indices=new TriangleStripArray(order,lengths);
        }
    }

    M3GCanvasImages(M3GSceneRenderer owner,Graphics3D graphics,int maxTexture) {
        this.owner=owner;this.graphics=graphics;
        tileSize=M3GSceneRenderer.spriteDimension(Math.min(128,maxTexture));
        int shift=0;while((1<<shift)<tileSize)shift++;tileShift=shift;
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setPerspectiveCorrectionEnable(false);
        appearance.setPolygonMode(polygon);appearance.setFog(null);
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setAlphaThreshold(0);appearance.setCompositingMode(composite);
    }

    void draw(DirectX7 g,ScreenImages commands,Graphics target) {
        if(target.getTranslateX()!=0 || target.getTranslateY()!=0)
            throw new IllegalArgumentException("foreground needs untranslated Canvas Graphics");
        int precision=Math.min(16,30000/Math.max(commands.width,commands.height));
        int centreX=commands.width>>1,centreY=commands.height>>1;
        if(precision<1 || tileSize<1) throw new IllegalArgumentException("foreground native limits");
        int clipX=target.getClipX(),clipY=target.getClipY(),clipW=target.getClipWidth(),clipH=target.getClipHeight();
        boolean bound=false;
        try {
            prepare(g,commands); // Upload ALL resources before binding Canvas.
            if(count==0) return;
            // Lazy exact-prefix index packets are also prepared BEFORE bind.
            for(int first=0;first<count;) {int end=packetEnd(first);packet(end-first,precision);first=end;}
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
            Texture2D active=null;int activeBlend=-1;
            for(int first=0;first<count;) {
                int end=packetEnd(first),quads=end-first;
                Texture2D texture=prepared[first];Packet packet=packets[quads-1];
                for(int i=first;i<end;i++) {
                    int p=i*9,v=(i-first)*4,u=(i-first)*8;
                    // Four edges once, not four Java calls/eight centre divisions per quad.
                    int j=v*3;
                    short left=(short)((geometry[p]-centreX)*precision),right=(short)((geometry[p+2]-centreX)*precision);
                    short top=(short)((centreY-geometry[p+1])*precision),bottom=(short)((centreY-geometry[p+3])*precision);
                    xyz[j]=xyz[j+9]=left;xyz[j+3]=xyz[j+6]=right;
                    xyz[j+1]=xyz[j+4]=top;xyz[j+7]=xyz[j+10]=bottom;
                    xyz[j+2]=xyz[j+5]=xyz[j+8]=xyz[j+11]=(short)-precision;
                    if(texture!=null) {
                        uv[u]=uv[u+6]=(short)geometry[p+4];uv[u+2]=uv[u+4]=(short)geometry[p+6];
                        uv[u+1]=uv[u+3]=(short)geometry[p+5];uv[u+5]=uv[u+7]=(short)geometry[p+7];
                    } else {
                        int color=geometry[p+8];
                        for(int n=v;n<v+4;n++) {
                            rgba[n*4]=(byte)(color>>16);rgba[n*4+1]=(byte)(color>>8);
                            rgba[n*4+2]=(byte)color;rgba[n*4+3]=(byte)255;
                        }
                    }
                }
                packet.positions.set(0,quads*4,xyz);
                boolean textured=texture!=null;
                if(textured) packet.coords.set(0,quads*4,uv);else packet.colors.set(0,quads*4,rgba);
                if(packet.whiteColors!=textured) {packet.vertices.setColors(textured?packet.white:packet.colors);packet.whiteColors=textured;}
                if(first==0 || active!=texture) {appearance.setTexture(0,texture);active=texture;}
                int blend=texture==null?CompositingMode.REPLACE:CompositingMode.ALPHA;
                if(activeBlend!=blend) {composite.setBlending(blend);activeBlend=blend;}
                owner.renderNative(g,packet.vertices,packet.indices,appearance);
                first=end;
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

    private int packetEnd(int first) {
        int end=first+1,limit=Math.min(count,first+MAX_PACKET);
        while(end<limit && prepared[end]==prepared[first]) end++;
        return end;
    }
    private Packet packet(int quads,int precision) {
        Packet packet=packets[quads-1];
        if(packet==null) packets[quads-1]=packet=new Packet(quads);
        if(packet.precision!=precision) {
            packet.vertices.setPositions(packet.positions,1.0f/precision,null);packet.precision=precision;
        }
        return packet;
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
            for(int ty=st>>tileShift;ty<=((sb-1)>>tileShift);ty++)
                for(int tx=sl>>tileShift;tx<=((sr-1)>>tileShift);tx++) {
                    Tile tile=tile(g,source,tx<<tileShift,ty<<tileShift);
                    int a=Math.max(sl,tile.x),b=Math.min(sr,tile.x+tileSize);
                    int c=Math.max(st,tile.y),d=Math.min(sb,tile.y+tileSize);
                    int dl=mirror?x+w-(b-sx):x+a-sx,dr=mirror?x+w-(a-sx):x+b-sx;
                    int u1=(mirror?b:a)-tile.x,u2=(mirror?a:b)-tile.x;
                    add(tile.texture,dl,y+c-sy,dr,y+d-sy,
                            u1*tile.uScale,(c-tile.y)*tile.vScale,
                            u2*tile.uScale,(d-tile.y)*tile.vScale,0xffffff);
                }
        }
    }
    private Tile tile(DirectX7 g,Image source,int x,int y) {
        Tile recent=lastTile;
        if(recent!=null && recent.source==source && recent.x==x && recent.y==y) return recent;
        for(int i=0;i<cacheSize;i++) {
            Tile t=cache[i];
            if(t.source==source && t.x==x && t.y==y) {
                if(t.previous==null) oldest=t.next;else t.previous.next=t.next;
                t.next.previous=t.previous; // Non-recent entries always have a successor.
                append(t);return t;
            }
        }
        int w=Math.min(tileSize,source.getWidth()-x),h=Math.min(tileSize,source.getHeight()-y);
        Tile t=new Tile();t.source=source;t.x=x;t.y=y;t.width=pot(w);t.height=pot(h);t.cost=t.width*t.height;
        t.uScale=4096/t.width;t.vScale=4096/t.height;
        if(sourcePixels==null || sourcePixels.length<w*h) sourcePixels=new int[w*h];
        int[] pixels=sourcePixels;source.getRGB(pixels,0,w,x,y,w,h);
        byte[] bytes=new byte[t.cost*4];
        for(int j=0;j<h;j++) for(int i=0;i<w;i++) {
            int c=pixels[j*w+i],p=(j*t.width+i)*4;
            bytes[p]=(byte)(c>>16);bytes[p+1]=(byte)(c>>8);bytes[p+2]=(byte)c;bytes[p+3]=(byte)(c>>>24);
        }
        Texture2D texture=new Texture2D(owner.uploadImage(g,Image2D.RGBA,t.width,t.height,bytes));
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP,Texture2D.WRAP_CLAMP);
        texture.setBlending(Texture2D.FUNC_MODULATE);t.texture=texture;
        while(cacheSize>0 && (cacheSize>=CACHE_ENTRIES || cachedPixels+t.cost>CACHE_PIXELS)) {
            Tile old=oldest;oldest=old.next;
            if(oldest==null) lastTile=null;else oldest.previous=null;
            cachedPixels-=old.cost;
            Tile moved=cache[--cacheSize];cache[cacheSize]=null;
            if(old.slot!=cacheSize) {cache[old.slot]=moved;moved.slot=old.slot;}
            old.previous=old.next=null;
        }
        t.slot=cacheSize;cache[cacheSize++]=t;cachedPixels+=t.cost;append(t);
        return t;
    }
    private void append(Tile tile) {
        tile.previous=lastTile;tile.next=null;
        if(lastTile==null) oldest=tile;else lastTile.next=tile;
        lastTile=tile;
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
