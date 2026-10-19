package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Isolated world-space experiment. NOT the production painter renderer.
 * Build after the baked lightmap has loaded. Rebuild after geometry/material/
 * light-quality edits. Owns one room; callers must dispose it on room unload.
 */
public final class StaticRoomMesh {
    private static final int MAX_VERTICES=16384, MAX_PARTS=128, MAX_TEXTURE_PIXELS=262144;
    private final Vector parts=new Vector(),images=new Vector();
    private final Graphics3D graphics=Graphics3D.getInstance();
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform projection=new Transform(),view=new Transform();
    private final float[] matrix=new float[16];
    private final Background background=new Background();
    private final int maxTexture;
    private int pixels;
    public int triangles,uploadedVertices,geometryUploads,textureUploads,draws;
    private boolean disposed;

    private static final class NativeImage {
        RawImage source; Texture2D texture; float threshold;
    }
    private static final class Part {
        VertexBuffer vertices; TriangleStripArray indices; Appearance appearance;
        Texture material; RawImage image; int mode;
    }

    public StaticRoomMesh(Mesh room,int lightQuality,boolean perspective) {
        if(room==null || room.getTexture()==null)throw new IllegalArgumentException("room textures missing");
        Object cap=Graphics3D.getProperties().get("maxTextureDimension");
        maxTexture=cap instanceof Integer?((Integer)cap).intValue():256;
        RenderObject[] polygons=room.getPolygons();Texture[] materials=room.getTexture().textures;
        // Preflight the WHOLE room. Never silently omit an unsupported material.
        for(int i=0;i<polygons.length;i++) {
            RenderObject p=polygons[i];int t=materialIndex(p);
            if(t<0 || t>=materials.length || materials[t]==null)throw new IllegalArgumentException("material index");
            Texture material=materials[t];int mode=material.drawmode;
            if(mode!=0 && mode!=9 && mode!=13 && mode!=7)throw new IllegalArgumentException("unsupported mode "+mode);
            checkImage(material.rImg);
            if(material.animMIP!=null)for(int f=0;f<material.animMIP.length;f++)checkImage(material.animMIP[f].rImg);
        }
        try {
            // Keep every source face; depth testing, not painter sorting, resolves
            // opaque overlap here. Material bias/addsz is NOT equivalent: preview only.
            for(int t=0;t<materials.length;t++) {
                Texture material=materials[t];boolean used=false;
                for(int i=0;i<polygons.length;i++)if(materialIndex(polygons[i])==t){used=true;break;}
                if(!used)continue;
                image(material.rImg);
                if(material.animMIP!=null)for(int f=0;f<material.animMIP.length;f++)image(material.animMIP[f].rImg);
                Vector faces=new Vector();int count=0;
                for(int i=0;i<polygons.length;i++)if(materialIndex(polygons[i])==t) {
                    int n=polygons[i] instanceof Polygon4V?4:3;
                    if(count+n>MAX_VERTICES){build(faces,material,lightQuality,perspective);faces.removeAllElements();count=0;}
                    faces.addElement(polygons[i]);count+=n;
                }
                if(count>0)build(faces,material,lightQuality,perspective);
            }
        } catch(RuntimeException ex){dispose();throw ex;}
          catch(Error ex){dispose();throw ex;}
        background.setColor(0x203040);background.setColorClearEnable(true);background.setDepthClearEnable(true);
    }

    private static int materialIndex(RenderObject p) {
        Class type=p.getClass();
        if(type!=Polygon3V.class && type!=Polygon4V.class && type!=LightedPolygon3V.class
                && type!=LightedPolygon4V.class && type!=ColorLightedPolygon3V.class && type!=ColorLightedPolygon4V.class)
            throw new IllegalArgumentException("non-static polygon");
        return p instanceof Polygon4V?((Polygon4V)p).tex&255:((Polygon3V)p).tex&255;
    }
    private void checkImage(RawImage raw) {
        if(raw==null || raw.img==null || raw.w<1 || raw.h<1 || (raw.w&(raw.w-1))!=0 || (raw.h&(raw.h-1))!=0
                || raw.w>maxTexture || raw.h>maxTexture || (long)raw.w*raw.h>raw.img.length)
            throw new IllegalArgumentException("unsupported texture; no resizing in preview");
    }
    private NativeImage image(RawImage raw) {
        for(int i=0;i<images.size();i++){NativeImage n=(NativeImage)images.elementAt(i);if(n.source==raw)return n;}
        if((long)pixels+(long)raw.w*raw.h>MAX_TEXTURE_PIXELS)throw new IllegalArgumentException("texture budget exceeded");
        byte[] rgba=new byte[raw.w*raw.h*4];boolean cutout=false;
        for(int i=0;i<raw.w*raw.h;i++) {
            int p=raw.img[i];rgba[i*4]=(byte)(p>>16);rgba[i*4+1]=(byte)(p>>8);rgba[i*4+2]=(byte)p;
            rgba[i*4+3]=(byte)(p==0?0:255);if(p==0)cutout=true;
        }
        NativeImage n=new NativeImage();n.source=raw;n.threshold=cutout?0.5f:0;
        n.texture=new Texture2D(new Image2D(Image2D.RGBA,raw.w,raw.h,rgba));
        n.texture.setWrapping(Texture2D.WRAP_REPEAT,Texture2D.WRAP_REPEAT);
        n.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);
        n.texture.setBlending(Texture2D.FUNC_MODULATE);
        images.addElement(n);pixels+=raw.w*raw.h;textureUploads++;return n;
    }
    private static Vertex vertex(RenderObject p,int i) {
        if(p instanceof Polygon4V){Polygon4V q=(Polygon4V)p;return i==0?q.a:i==1?q.b:i==2?q.c:q.d;}
        Polygon3V q=(Polygon3V)p;return i==0?q.a:i==1?q.b:q.c;
    }
    private static int uv(RenderObject p,int i,boolean v) {
        if(p instanceof Polygon4V){Polygon4V q=(Polygon4V)p;return v?(i==0?q.av:i==1?q.bv:i==2?q.cv:q.dv):(i==0?q.au:i==1?q.bu:i==2?q.cu:q.du);}
        Polygon3V q=(Polygon3V)p;return v?(i==0?q.av:i==1?q.bv:q.cv):(i==0?q.au:i==1?q.bu:q.cu);
    }
    private static int gcd(int a,int b){while(b!=0){int n=a%b;a=b;b=n;}return a;}
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    private static int color(RenderObject p,int i,int channel,int mode,int quality) {
        if(quality==0 || mode==0 || mode==7)return 255;
        int r=255,g=255,b=255;
        if(p instanceof LightedPolygon3V){LightedPolygon3V q=(LightedPolygon3V)p;r=g=b=i==0?q.la:i==1?q.lb:q.lc;}
        else if(p instanceof LightedPolygon4V){LightedPolygon4V q=(LightedPolygon4V)p;r=g=b=i==0?q.la:i==1?q.lb:i==2?q.lc:q.ld;}
        else if(p instanceof ColorLightedPolygon3V){ColorLightedPolygon3V q=(ColorLightedPolygon3V)p;
            r=i==0?q.ar:i==1?q.br:q.cr;g=i==0?q.ag:i==1?q.bg:q.cg;b=i==0?q.ab:i==1?q.bb:q.cb;}
        else if(p instanceof ColorLightedPolygon4V){ColorLightedPolygon4V q=(ColorLightedPolygon4V)p;
            r=i==0?q.ar:i==1?q.br:i==2?q.cr:q.dr;g=i==0?q.ag:i==1?q.bg:i==2?q.cg:q.dg;b=i==0?q.ab:i==1?q.bb:i==2?q.cb:q.db;}
        r=clamp(r);g=clamp(g);b=clamp(b);
        if(mode==9)return r;
        if(quality==1)return (r+g+b)/3;
        return channel==0?r:channel==1?g:b;
    }
    private void build(Vector faces,Texture material,int quality,boolean perspective) {
        if(parts.size()>=MAX_PARTS)throw new IllegalArgumentException("room part budget exceeded");
        int n=0,step=0;int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        Vertex first=vertex((RenderObject)faces.elementAt(0),0);int[] origin={first.x,first.y,first.z};
        for(int f=0;f<faces.size();f++) {
            RenderObject p=(RenderObject)faces.elementAt(f);int count=p instanceof Polygon4V?4:3;n+=count;
            for(int i=0;i<count;i++){Vertex v=vertex(p,i);int[] xyz={v.x,v.y,v.z};for(int c=0;c<3;c++){
                if(xyz[c]<-8000000 || xyz[c]>8000000)throw new IllegalArgumentException("world coordinate exceeds exact-float budget");
                min[c]=Math.min(min[c],xyz[c]);max[c]=Math.max(max[c],xyz[c]);step=gcd(step,Math.abs(xyz[c]-origin[c]));}}
        }
        if(step==0)step=1;
        float[] bias=new float[3];int[] centre=new int[3];
        for(int c=0;c<3;c++) {
            int range=(max[c]-min[c])/step;if(range>65535)throw new IllegalArgumentException("room cannot be packed losslessly; split it first");
            centre[c]=min[c]+((range+1)/2)*step;bias[c]=centre[c];
        }
        short[] xyz=new short[n*3],coords=new short[n*2];byte[] colors=new byte[n*4];
        int[] indices=new int[n],strips=new int[faces.size()];int offset=0,index=0;
        for(int f=0;f<faces.size();f++) {
            RenderObject p=(RenderObject)faces.elementAt(f);int count=p instanceof Polygon4V?4:3;strips[f]=count;
            for(int i=0;i<count;i++) {
                Vertex v=vertex(p,i);int dest=offset+i;
                xyz[dest*3]=(short)((v.x-centre[0])/step);xyz[dest*3+1]=(short)((v.y-centre[1])/step);xyz[dest*3+2]=(short)((v.z-centre[2])/step);
                coords[dest*2]=(short)uv(p,i,false);coords[dest*2+1]=(short)uv(p,i,true);
                for(int c=0;c<3;c++)colors[dest*4+c]=(byte)color(p,i,c,material.drawmode,quality);colors[dest*4+3]=(byte)255;
            }
            indices[index++]=offset;indices[index++]=offset+1;
            if(count==4){indices[index++]=offset+3;indices[index++]=offset+2;}else indices[index++]=offset+2;
            offset+=count;triangles+=count-2;
        }
        Part part=new Part();part.material=material;part.mode=material.drawmode;
        VertexArray positions=new VertexArray(n,3,2),uvs=new VertexArray(n,2,2),rgba=new VertexArray(n,4,1);
        positions.set(0,n,xyz);uvs.set(0,n,coords);rgba.set(0,n,colors);
        part.vertices=new VertexBuffer();part.vertices.setPositions(positions,step,bias);
        part.vertices.setTexCoords(0,uvs,1.0f/256,null);part.vertices.setColors(rgba);
        part.indices=new TriangleStripArray(indices,strips);part.appearance=new Appearance();
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_BACK);polygon.setWinding(PolygonMode.WINDING_CW);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);polygon.setPerspectiveCorrectionEnable(perspective && material.perspectiveCorrection);
        part.appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();composite.setDepthTestEnable(true);composite.setDepthWriteEnable(part.mode!=7);
        composite.setBlending(part.mode==7?CompositingMode.MODULATE:CompositingMode.REPLACE);part.appearance.setCompositingMode(composite);
        NativeImage texture=image(material.rImg);part.appearance.setTexture(0,texture.texture);composite.setAlphaThreshold(texture.threshold);part.image=material.rImg;
        parts.addElement(part);uploadedVertices+=n;geometryUploads+=3;
    }

    /** Owns its target transaction; do NOT mix this depth buffer with the painter path.
     * Conventional world-space interpolation differs from legacy projected UV weights.
     * Supply the same camera and configured distance for reproducible preview poses.
     */
    public void render(Graphics target,Matrix cameraTransform,int width,int height,int focalX,int focalY,int far) {
        if(disposed)throw new IllegalStateException("disposed room");
        if(target==null || width<=0 || height<=0 || focalX<=0 || focalY<=0 || far<=1)throw new IllegalArgumentException("camera/viewport");
        // Resolve animated images BEFORE bindTarget. Never allocate/upload during rendering.
        for(int i=0;i<parts.size();i++) {
            Part p=(Part)parts.elementAt(i);if(p.material.drawmode!=p.mode)throw new IllegalStateException("material edited; rebuild room");
            if(p.image!=p.material.rImg) {
                NativeImage n=null;for(int j=0;j<images.size();j++){NativeImage c=(NativeImage)images.elementAt(j);if(c.source==p.material.rImg){n=c;break;}}
                if(n==null)throw new IllegalStateException("unprepared animation; rebuild room");
                p.appearance.setTexture(0,n.texture);p.appearance.getCompositingMode().setAlphaThreshold(n.threshold);p.image=p.material.rImg;
            }
        }
        for(int i=0;i<16;i++)matrix[i]=0;
        matrix[0]=2.0f*focalX/width;matrix[5]=2.0f*focalY/height;
        matrix[10]=-(far+1.0f)/(far-1.0f);matrix[11]=-2.0f*far/(far-1.0f);matrix[14]=-1;
        projection.set(matrix);camera.setGeneric(projection);
        Matrix m=cameraTransform;float fp=Matrix.FP;
        matrix[0]=(m.m00>>2)/(fp/4);matrix[1]=(m.m01>>2)/(fp/4);matrix[2]=(m.m02>>2)/(fp/4);matrix[3]=m.m03;
        matrix[4]=(m.m10>>2)/(fp/4);matrix[5]=(m.m11>>2)/(fp/4);matrix[6]=(m.m12>>2)/(fp/4);matrix[7]=m.m13;
        matrix[8]=(m.m20>>2)/(fp/4);matrix[9]=(m.m21>>2)/(fp/4);matrix[10]=(m.m22>>2)/(fp/4);matrix[11]=m.m23;
        matrix[12]=matrix[13]=matrix[14]=0;matrix[15]=1;
        // Match the legacy focal-plane shift when distX == distY. Integer screen
        // rounding and the old texture-perspective denominator are not reproduced.
        matrix[3]+=matrix[2]*focalY;matrix[7]+=matrix[6]*focalY;matrix[11]+=matrix[10]*focalY;
        view.set(matrix);draws=0;
        graphics.bindTarget(target,true,0);
        try {
            graphics.setViewport(0,0,width,height);graphics.setCamera(camera,view);graphics.resetLights();graphics.clear(background);
            for(int pass=0;pass<2;pass++)for(int i=0;i<parts.size();i++) {
                Part p=(Part)parts.elementAt(i);if((p.mode==7)!=(pass==1))continue;
                graphics.render(p.vertices,p.indices,p.appearance,null);draws++;
            }
        } finally {graphics.releaseTarget();}
    }
    public int parts(){return parts.size();}
    public void dispose(){parts.removeAllElements();images.removeAllElements();pixels=0;disposed=true;}
}
