package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Portal rooms plus per-room median BVH. Vertex/texture buffers stay cold.
 * Selected strips are batched once per room; only new visibility masks build
 * immutable M3G indices (two-entry bounded cache). Not occlusion or BSP.
 */
public final class PortalMapBvhMesh {
    private static final int MAX_VERTICES=16384, MAX_PARTS=128, MAX_TEXTURE_PIXELS=262144;
    private final Vector parts=new Vector(),images=new Vector();
    private final Graphics3D graphics=Graphics3D.getInstance();
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform projection=new Transform(),view=new Transform();
    private final float[] matrix=new float[16];
    private final Background background=new Background();
    private final int maxTexture;
    private int pixels;
    private final PortalMapBvhVisibility selector;
    private boolean portals=true,bvhEnabled=true,haveVisibility;
    private final PortalMapBvhFrustum frustum=new PortalMapBvhFrustum();
    private final float[] previousCamera=new float[16];
    private int previousWidth,previousHeight,previousFx,previousFy,previousFar,previousNear,previousMask,previousRoom;
    public int maskUploads,bvhTests,culledNodes;public boolean bvhCached,memoryFallback;
    public boolean isBvhEnabled(){return bvhEnabled;}
    public void setBvhEnabled(boolean enabled){if(disposed)throw new IllegalStateException("disposed map");if(bvhEnabled!=enabled)haveVisibility=false;bvhEnabled=enabled;}

    public int prepareMs,bindMs,clearMs,drawMs,releaseMs,submittedBufferVertices;
    public boolean selectionCached;
    public int roomMask,selectedRooms,currentRoom=-1,portalTests;
    public boolean isPortalsEnabled(){return portals;}
    public void setPortalsEnabled(boolean enabled){if(disposed)throw new IllegalStateException("disposed map");if(portals!=enabled)haveVisibility=false;portals=enabled;}
    public int triangles,uploadedVertices,geometryUploads,textureUploads,draws;
    private boolean disposed;
    private int nearPlane=1;
    public int submittedTriangles,submittedCorners;

    private static final class NativeImage {
        RawImage source; Texture2D texture; float threshold;
    }
    private static final class Part {
        VertexBuffer vertices; TriangleStripArray indices; Appearance appearance;
        Texture material; RawImage image; int mode;
        int room,vertexCount,fullTriangles,fullCorners;
        PortalMapBvh bvh;int[] sourceIndices,sourceStrips;
        TriangleStripArray selected;int selectedCorners,selectedTriangles,nextMask;
        final Masked[] masks=new Masked[2];
    }
    private static final class Masked {
        byte[] faces;TriangleStripArray indices;int corners,triangles;
    }

    public PortalMapBvhMesh(Mesh[] meshes,int lightQuality,boolean perspective) {
        if(meshes==null || meshes.length!=16)throw new IllegalArgumentException("requires fifteen rooms plus portal mesh");
        selector=new PortalMapBvhVisibility(meshes);
        Object cap=Graphics3D.getProperties().get("maxTextureDimension");maxTexture=cap instanceof Integer?((Integer)cap).intValue():256;
        try {
            for(int r=0;r<meshes.length-1;r++){
                Mesh mesh=meshes[r];Texture[] materials=mesh.getTexture().textures;
                if(materials.length!=1 || materials[0].drawmode!=13)throw new IllegalArgumentException("map must keep single atlas/mode13");
                Texture material=materials[0];checkImage(material.rImg);image(material.rImg);
                RenderObject[] polygons=mesh.getPolygons();Vector faces=new Vector();int count=0;
                for(int i=0;i<polygons.length;i++){
                    if(materialIndex(polygons[i])!=0)throw new IllegalArgumentException("map material index");
                    faces.addElement(polygons[i]);count+=polygons[i] instanceof Polygon4V?4:3;
                }
                if(count==0 || count>MAX_VERTICES)throw new IllegalArgumentException("room vertex budget");
                build(faces,material,lightQuality,perspective);((Part)parts.lastElement()).room=r;
            }
        }catch(RuntimeException ex){dispose();throw ex;}catch(Error ex){dispose();throw ex;}
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
        int[] faceBounds=new int[faces.size()*6];
        for(int f=0;f<faces.size();f++){
            RenderObject p=(RenderObject)faces.elementAt(f);int count=p instanceof Polygon4V?4:3;
            for(int k=0;k<3;k++){faceBounds[f*6+k]=Integer.MAX_VALUE;faceBounds[f*6+3+k]=Integer.MIN_VALUE;}
            for(int i=0;i<count;i++){Vertex v=vertex(p,i);for(int k=0;k<3;k++){
                int value=k==0?v.x:k==1?v.y:v.z;faceBounds[f*6+k]=Math.min(faceBounds[f*6+k],value);faceBounds[f*6+3+k]=Math.max(faceBounds[f*6+3+k],value);
            }}
        }
        Part part=new Part();part.bvh=new PortalMapBvh(faceBounds);part.sourceIndices=indices;part.sourceStrips=strips;part.material=material;part.mode=material.drawmode;
        VertexArray positions=new VertexArray(n,3,2),uvs=new VertexArray(n,2,2),rgba=new VertexArray(n,4,1);
        positions.set(0,n,xyz);uvs.set(0,n,coords);rgba.set(0,n,colors);
        part.vertices=new VertexBuffer();part.vertices.setPositions(positions,step,bias);
        part.vertices.setTexCoords(0,uvs,1.0f/256,null);part.vertices.setColors(rgba);
        part.indices=new TriangleStripArray(indices,strips);part.appearance=new Appearance();
        part.fullCorners=n;part.fullTriangles=n-2*strips.length;
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_BACK);polygon.setWinding(PolygonMode.WINDING_CW);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);polygon.setPerspectiveCorrectionEnable(perspective && material.perspectiveCorrection);
        part.appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();composite.setDepthTestEnable(true);composite.setDepthWriteEnable(part.mode!=7);
        composite.setBlending(part.mode==7?CompositingMode.MODULATE:CompositingMode.REPLACE);part.appearance.setCompositingMode(composite);
        NativeImage texture=image(material.rImg);part.appearance.setTexture(0,texture.texture);composite.setAlphaThreshold(texture.threshold);part.image=material.rImg;
        part.vertexCount=n;parts.addElement(part);uploadedVertices+=n;geometryUploads+=3;
    }

    private static boolean equalFaces(byte[] a,byte[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true;}
    private void select(Part p){
        if(!bvhEnabled || memoryFallback){p.selected=p.indices;p.selectedCorners=p.fullCorners;p.selectedTriangles=p.fullTriangles;return;}
        p.bvh.select(frustum);bvhTests+=p.bvh.tests;culledNodes+=p.bvh.culled;
        if(p.bvh.visible==0){p.selected=null;p.selectedCorners=p.selectedTriangles=0;return;}
        if(p.bvh.visible==p.sourceStrips.length){p.selected=p.indices;p.selectedCorners=p.fullCorners;p.selectedTriangles=p.fullTriangles;return;}
        byte[] faces=p.bvh.selected;
        for(int i=0;i<p.masks.length;i++)if(p.masks[i]!=null && equalFaces(p.masks[i].faces,faces)){
            Masked hit=p.masks[i];p.selected=hit.indices;p.selectedCorners=hit.corners;p.selectedTriangles=hit.triangles;return;
        }
        int corners=0;for(int i=0;i<faces.length;i++)if(faces[i]!=0)corners+=p.sourceStrips[i];
        int[] indices=new int[corners],strips=new int[p.bvh.visible];int offset=0,dest=0,strip=0;
        for(int f=0;f<faces.length;f++){int length=p.sourceStrips[f];if(faces[f]!=0){System.arraycopy(p.sourceIndices,offset,indices,dest,length);dest+=length;strips[strip++]=length;}offset+=length;}
        Masked next=new Masked();next.faces=new byte[faces.length];System.arraycopy(faces,0,next.faces,0,faces.length);
        next.corners=corners;next.triangles=corners-2*strips.length;next.indices=new TriangleStripArray(indices,strips);maskUploads++;
        p.masks[p.nextMask]=next;if(++p.nextMask==p.masks.length)p.nextMask=0;
        p.selected=next.indices;p.selectedCorners=next.corners;p.selectedTriangles=next.triangles;
    }
    private void prepareSelection(int width,int height,int fx,int fy,int far,float pz,float pw){
        bvhTests=culledNodes=0;
        boolean same=haveVisibility && previousWidth==width && previousHeight==height && previousFx==fx && previousFy==fy && previousFar==far && previousNear==nearPlane && previousMask==roomMask && previousRoom==currentRoom;
        if(same)for(int i=0;i<16;i++)if(previousCamera[i]!=matrix[i]){same=false;break;}
        bvhCached=same;if(same)return;
        try{
            for(int i=0;i<parts.size();i++){
                Part p=(Part)parts.elementAt(i);if((roomMask&(1<<p.room))==0){p.selected=null;continue;}
                int left=0,top=0,right=width,bottom=height;
                if(portals && currentRoom>=0){left=selector.window(p.room,0);top=selector.window(p.room,1);right=selector.window(p.room,2);bottom=selector.window(p.room,3);}
                if(bvhEnabled && !memoryFallback)frustum.set(matrix,pz,pw,width,height,fx,fy,left,top,right,bottom);select(p);
            }
        }catch(OutOfMemoryError ex){
            // Drop index caches and fail open to the original room buffers.
            // Both HUD and report distinguish fallback from actual BVH culling.
            memoryFallback=true;for(int i=0;i<parts.size();i++){Part p=(Part)parts.elementAt(i);p.masks[0]=p.masks[1]=null;if((roomMask&(1<<p.room))!=0)select(p);else p.selected=null;}
        }
        System.arraycopy(matrix,0,previousCamera,0,16);previousWidth=width;previousHeight=height;previousFx=fx;previousFy=fy;previousFar=far;previousNear=nearPlane;previousMask=roomMask;previousRoom=currentRoom;haveVisibility=true;
    }

    /** Diagnostic parameter; changing near clipping can remove very close surfaces.
     * Keep the configured far distance. No projection-only depth bias or sorting.
     */
    public void setNearPlane(int value){if(value<1 || value>4096)throw new IllegalArgumentException("near plane");nearPlane=value;}
    public int getNearPlane(){return nearPlane;}

    /** Owns its target transaction; do NOT mix this depth buffer with the painter path.
     * Conventional world-space interpolation differs from legacy projected UV weights.
     * Supply the same camera and configured distance for reproducible preview poses.
     */
    public void render(Graphics target,Matrix cameraTransform,int width,int height,int focalX,int focalY,int far) {
        if(disposed)throw new IllegalStateException("disposed room");
        if(target==null || cameraTransform==null || width<=0 || height<=0 || focalX<=0 || focalY<=0 || far<=nearPlane)throw new IllegalArgumentException("camera/viewport");
        long began=System.currentTimeMillis();
        // Resolve animated images BEFORE bindTarget. Vertex/texture uploads stay
        // cold-only. New selected-index masks are built BEFORE bindTarget.
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
        // near=1 reproduces the original projection exactly. A larger near
        // distributes finite depth precision more usefully, but moves clipping.
        float near=nearPlane;
        matrix[10]=-(far+near)/(far-near);matrix[11]=-2.0f*near*far/(far-near);matrix[14]=-1;
        float pz=matrix[10],pw=matrix[11];projection.set(matrix);camera.setGeneric(projection);
        Matrix m=cameraTransform;float fp=Matrix.FP;
        matrix[0]=(m.m00>>2)/(fp/4);matrix[1]=(m.m01>>2)/(fp/4);matrix[2]=(m.m02>>2)/(fp/4);matrix[3]=m.m03;
        matrix[4]=(m.m10>>2)/(fp/4);matrix[5]=(m.m11>>2)/(fp/4);matrix[6]=(m.m12>>2)/(fp/4);matrix[7]=m.m13;
        matrix[8]=(m.m20>>2)/(fp/4);matrix[9]=(m.m21>>2)/(fp/4);matrix[10]=(m.m22>>2)/(fp/4);matrix[11]=m.m23;
        matrix[12]=matrix[13]=matrix[14]=0;matrix[15]=1;
        // Match the legacy focal-plane shift when distX == distY. Integer screen
        // rounding and the old texture-perspective denominator are not reproduced.
        matrix[3]+=matrix[2]*focalY;matrix[7]+=matrix[6]*focalY;matrix[11]+=matrix[10]*focalY;
        view.set(matrix);submittedBufferVertices=0;draws=submittedTriangles=submittedCorners=0;
        roomMask=selector.select(portals,matrix,width,height,focalX,focalY);
        selectedRooms=selector.selectedRooms;currentRoom=selector.currentRoom;portalTests=selector.portalTests;selectionCached=selector.cachedFrame;
        prepareSelection(width,height,focalX,focalY,far,pz,pw);
        long prepared=System.currentTimeMillis();prepareMs=duration(began,prepared);
        graphics.bindTarget(target,true,0);
        long bound=System.currentTimeMillis(),drawEnd=bound;bindMs=duration(prepared,bound);
        try {
            graphics.setViewport(0,0,width,height);graphics.setCamera(camera,view);graphics.resetLights();graphics.clear(background);
            long cleared=System.currentTimeMillis();clearMs=duration(bound,cleared);
            for(int i=0;i<parts.size();i++) {
                Part p=(Part)parts.elementAt(i);if((roomMask&(1<<p.room))==0 || p.selected==null)continue;
                graphics.render(p.vertices,p.selected,p.appearance,null);draws++;submittedBufferVertices+=p.vertexCount;
                submittedTriangles+=p.selectedTriangles;submittedCorners+=p.selectedCorners;
            }
            drawEnd=System.currentTimeMillis();drawMs=duration(cleared,drawEnd);
        } finally {graphics.releaseTarget();releaseMs=duration(drawEnd,System.currentTimeMillis());}
    }
    private static int duration(long a,long b){return b>=a?(int)Math.min(Integer.MAX_VALUE,b-a):0;}
    public int parts(){return parts.size();}
    public void dispose(){parts.removeAllElements();images.removeAllElements();selector.dispose();pixels=0;disposed=true;}
}
