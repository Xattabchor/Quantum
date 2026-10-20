package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** River-map overdraw diagnostic based on the Packets renderer.
 * Same cold geometry/textures/order data; compact packets may optionally be
 * submitted front-to-back and with an M3G 1.0 depth-only prepass.
 * Build after the baked lightmap has loaded. Owns one room; dispose on unload.
 */
public final class RiverOverdrawMesh {
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
    public int submittedBufferVertices,compactUploadedVertices,compactGeometryUploads,compactMaskUploads,packetCount;
    public int prepareMs,bindMs,clearMs,drawMs,releaseMs,sortedPackets;
    private boolean compactEnabled,compactUnavailable,frontToBackEnabled,depthPrepassEnabled;
    private int[] drawOrder;private float[] drawDepth;
    private static final int PACKET_VERTICES=512,MAX_PACKETS=1024;
    private boolean disposed,cullingEnabled,maskMemoryFallback;
    private int nearPlane=1;
    private boolean bvhEnabled=true;
    private final StaticRoomVisibility visibility=new StaticRoomVisibility();
    public int submittedTriangles,submittedCorners,visibilityTests,culledCells,maskUploads;

    private static final class NativeImage {
        RawImage source; Texture2D texture; float threshold;
    }
    private static final class Part {
        VertexBuffer vertices; TriangleStripArray indices; Appearance appearance,depthAppearance,colorAppearance; float threshold; boolean perspectiveCorrection;
        short[] packedXYZ,packedUV;byte[] packedColor;float packedScale;float[] packedBias;
        int vertexCount;Packet[] packets;TriangleStripArray[] fullPackets,selectedPackets;
        Texture material; RawImage image; int mode;
        int[] sourceIndices,sourceStrips,bounds;
        byte[] faceCells;
        int occupiedMask,fullTriangles;
        StaticRoomBvh bvh;
        final Masked[] masks=new Masked[2];
        int nextMask;
        TriangleStripArray selected;
        int selectedCorners,selectedTriangles;
    }

    private static final class Masked {
        int mask,corners,triangles;TriangleStripArray indices;byte[] faces;TriangleStripArray[] packets;
    }

    public RiverOverdrawMesh(Mesh room,int lightQuality,boolean perspective) {
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
            Pending pending=null;
            for(int t=0;t<materials.length;t++) {
                Texture material=materials[t];boolean used=false;
                for(int i=0;i<polygons.length;i++)if(materialIndex(polygons[i])==t){used=true;break;}
                if(!used)continue;
                image(material.rImg);
                if(material.animMIP!=null)for(int f=0;f<material.animMIP.length;f++)image(material.animMIP[f].rImg);
                Vector faces=new Vector();int count=0;
                for(int i=0;i<polygons.length;i++)if(materialIndex(polygons[i])==t) {
                    int n=polygons[i] instanceof Polygon4V?4:3;
                    if(count+n>MAX_VERTICES){pending=stage(pending,faces,count,material,lightQuality,perspective);faces=new Vector();count=0;}
                    faces.addElement(polygons[i]);count+=n;
                }
                if(count>0)pending=stage(pending,faces,count,material,lightQuality,perspective);
            }
            if(pending!=null)build(pending.faces,pending.material,lightQuality,perspective);
            buildCompact();
            if(packetCount>0){drawOrder=new int[packetCount];drawDepth=new float[packetCount];}
        } catch(RuntimeException ex){dispose();throw ex;}
          catch(Error ex){dispose();throw ex;}
        background.setColor(0x203040);background.setColorClearEnable(true);background.setDepthClearEnable(true);
    }

    private static final class Pending {
        Vector faces;Texture material;int vertices;
    }
    private static boolean compatible(Texture a,Texture b) {
        // Merge adjacent static REPLACE runs only. Animation and MODULATE remain
        // independent; never merge merely because two frames happen to match.
        return a.animMIP==null && b.animMIP==null && a.drawmode!=7
                && a.drawmode==b.drawmode && a.rImg==b.rImg
                && a.perspectiveCorrection==b.perspectiveCorrection;
    }
    private Pending stage(Pending pending,Vector faces,int count,Texture material,int quality,boolean perspective) {
        if(pending!=null && compatible(pending.material,material)
                && pending.vertices+count<=MAX_VERTICES && combinedFits(pending.faces,faces)) {
            // Concatenate in the PREVIOUS material submission order. Do not
            // gather by texture globally or change equal-depth tie ordering.
            for(int i=0;i<faces.size();i++)pending.faces.addElement(faces.elementAt(i));
            pending.vertices+=count;return pending;
        }
        if(pending==null)pending=new Pending();
        else build(pending.faces,pending.material,quality,perspective);
        pending.faces=faces;pending.vertices=count;pending.material=material;return pending;
    }
    private static boolean combinedFits(Vector first,Vector second) {
        // A merge must not turn two lossless packets into a lossy/rejected one.
        int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        Vertex origin=vertex((RenderObject)first.elementAt(0),0);int step=0;
        for(int part=0;part<2;part++) {
            Vector faces=part==0?first:second;
            for(int f=0;f<faces.size();f++) {
                RenderObject p=(RenderObject)faces.elementAt(f);int count=p instanceof Polygon4V?4:3;
                for(int v=0;v<count;v++) {
                    Vertex point=vertex(p,v);int[] xyz={point.x,point.y,point.z},base={origin.x,origin.y,origin.z};
                    for(int c=0;c<3;c++) {
                        if(xyz[c]<-8000000 || xyz[c]>8000000)return false;
                        min[c]=Math.min(min[c],xyz[c]);max[c]=Math.max(max[c],xyz[c]);step=gcd(step,Math.abs(xyz[c]-base[c]));
                    }
                }
            }
        }
        if(step==0)step=1;
        for(int c=0;c<3;c++)if((max[c]-min[c])/step>65535)return false;
        return true;
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
        part.sourceIndices=indices;part.sourceStrips=strips;part.fullTriangles=n-2*strips.length;
        part.faceCells=new byte[strips.length];part.bounds=new int[16*6];int[] faceBounds=new int[strips.length*6];
        for(int cell=0;cell<16;cell++)for(int k=0;k<3;k++) {
            part.bounds[cell*6+k]=Integer.MAX_VALUE;part.bounds[cell*6+k+3]=Integer.MIN_VALUE;
        }
        for(int f=0;f<faces.size();f++) {
            RenderObject face=(RenderObject)faces.elementAt(f);int count=face instanceof Polygon4V?4:3;
            int[] low={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},high={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            for(int v=0;v<count;v++) {
                Vertex point=vertex(face,v);int[] values={point.x,point.y,point.z};
                for(int k=0;k<3;k++){low[k]=Math.min(low[k],values[k]);high[k]=Math.max(high[k],values[k]);}
            }
            for(int k=0;k<3;k++){faceBounds[f*6+k]=low[k];faceBounds[f*6+3+k]=high[k];}
            int cellX=(int)(((long)low[0]+high[0]-2L*min[0])*4/(2L*((long)max[0]-min[0]+1)));
            int cellZ=(int)(((long)low[2]+high[2]-2L*min[2])*4/(2L*((long)max[2]-min[2]+1)));
            int cell=Math.min(3,cellX)+Math.min(3,cellZ)*4;part.faceCells[f]=(byte)cell;part.occupiedMask|=1<<cell;
            // Full face bounds, NOT just a grid tile or centroid. Long faces
            // crossing cells must never be accidentally clipped by this test.
            for(int k=0;k<3;k++){part.bounds[cell*6+k]=Math.min(part.bounds[cell*6+k],low[k]);part.bounds[cell*6+k+3]=Math.max(part.bounds[cell*6+k+3],high[k]);}
        }
        part.bvh=new StaticRoomBvh(faceBounds);
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_BACK);polygon.setWinding(PolygonMode.WINDING_CW);
        polygon.setShading(PolygonMode.SHADE_SMOOTH);polygon.setPerspectiveCorrectionEnable(perspective && material.perspectiveCorrection);
        part.appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();composite.setDepthTestEnable(true);composite.setDepthWriteEnable(part.mode!=7);
        composite.setBlending(part.mode==7?CompositingMode.MODULATE:CompositingMode.REPLACE);part.appearance.setCompositingMode(composite);
        NativeImage texture=image(material.rImg);part.appearance.setTexture(0,texture.texture);composite.setAlphaThreshold(texture.threshold);part.image=material.rImg;part.threshold=texture.threshold;
        part.perspectiveCorrection=perspective && material.perspectiveCorrection;
        part.vertexCount=n;parts.addElement(part);uploadedVertices+=n;geometryUploads+=3;
        part.packedXYZ=xyz;part.packedUV=coords;part.packedColor=colors;part.packedScale=step;part.packedBias=bias;
    }

    /** Diagnostic parameter; changing near clipping can remove very close surfaces.
     * Keep the configured far distance. No projection-only depth bias or sorting.
     */
    public void setNearPlane(int value){if(value<1 || value>4096)throw new IllegalArgumentException("near plane");nearPlane=value;}
    public int getNearPlane(){return nearPlane;}

    public void setBvhEnabled(boolean enabled){bvhEnabled=enabled;}
    public boolean isBvhEnabled(){return bvhEnabled;}

    public void setCullingEnabled(boolean enabled){cullingEnabled=enabled;}
    public boolean isCullingFallbackActive(){return maskMemoryFallback;}

    private void selectVisible(Part p) {
        if(cullingEnabled && bvhEnabled && !maskMemoryFallback){selectBvh(p);return;}
        int mask=p.occupiedMask;
        if(cullingEnabled && !maskMemoryFallback) {
            mask=0;
            for(int cell=0;cell<16;cell++)if((p.occupiedMask&(1<<cell))!=0) {
                visibilityTests++;
                if(visibility.outside(p.bounds,cell*6))culledCells++;else mask|=1<<cell;
            }
        }
        if(mask==0){p.selected=null;p.selectedCorners=p.selectedTriangles=0;return;}
        if(mask==p.occupiedMask){p.selected=p.indices;p.selectedCorners=p.sourceIndices.length;p.selectedTriangles=p.fullTriangles;return;}
        for(int i=0;i<p.masks.length;i++) {
            Masked cached=p.masks[i];
            if(cached!=null && cached.faces==null && cached.mask==mask){p.selected=cached.indices;p.selectedCorners=cached.corners;p.selectedTriangles=cached.triangles;return;}
        }
        // Only a NEW visibility mask builds an index list. Vertex arrays never
        // change. Preserve the exact original strip/face order, not cell order.
        int corners=0,faces=0;
        for(int f=0;f<p.faceCells.length;f++)if((mask&(1<<p.faceCells[f]))!=0){corners+=p.sourceStrips[f];faces++;}
        int[] indices=new int[corners],strips=new int[faces];int offset=0,dest=0,strip=0;
        for(int f=0;f<p.faceCells.length;f++) {
            int length=p.sourceStrips[f];
            if((mask&(1<<p.faceCells[f]))!=0){System.arraycopy(p.sourceIndices,offset,indices,dest,length);dest+=length;strips[strip++]=length;}
            offset+=length;
        }
        Masked next=new Masked();next.mask=mask;next.corners=corners;next.triangles=corners-2*faces;
        next.indices=new TriangleStripArray(indices,strips);maskUploads++;
        p.masks[p.nextMask]=next;if(++p.nextMask==p.masks.length)p.nextMask=0;
        p.selected=next.indices;p.selectedCorners=next.corners;p.selectedTriangles=next.triangles;
    }

    private static boolean equalFaces(byte[] a,byte[] b) {
        if(a==null || a.length!=b.length)return false;
        for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true;
    }
    private void selectBvh(Part p) {
        p.bvh.select(visibility);visibilityTests+=p.bvh.tests;culledCells+=p.bvh.culled;
        if(p.bvh.visible==0){p.selected=null;p.selectedCorners=p.selectedTriangles=0;return;}
        if(p.bvh.visible==p.sourceStrips.length){p.selected=p.indices;p.selectedCorners=p.sourceIndices.length;p.selectedTriangles=p.fullTriangles;return;}
        byte[] selected=p.bvh.selected;
        for(int i=0;i<p.masks.length;i++) {
            Masked cached=p.masks[i];
            if(cached!=null && equalFaces(cached.faces,selected)){p.selected=cached.indices;p.selectedCorners=cached.corners;p.selectedTriangles=cached.triangles;return;}
        }
        int corners=0,faces=p.bvh.visible;
        for(int f=0;f<selected.length;f++)if(selected[f]!=0)corners+=p.sourceStrips[f];
        int[] indices=new int[corners],strips=new int[faces];int offset=0,dest=0,strip=0;
        for(int f=0;f<selected.length;f++) {
            int length=p.sourceStrips[f];
            if(selected[f]!=0){System.arraycopy(p.sourceIndices,offset,indices,dest,length);dest+=length;strips[strip++]=length;}
            offset+=length;
        }
        Masked next=new Masked();next.faces=new byte[selected.length];System.arraycopy(selected,0,next.faces,0,selected.length);
        next.corners=corners;next.triangles=corners-2*faces;next.indices=new TriangleStripArray(indices,strips);maskUploads++;
        p.masks[p.nextMask]=next;if(++p.nextMask==p.masks.length)p.nextMask=0;
        p.selected=next.indices;p.selectedCorners=next.corners;p.selectedTriangles=next.triangles;
    }

    private void ensurePrepassAppearances() {
        // Lazily created on first ZPRE frame so A/C/F2B never touch color-write masks.
        for(int i=0;i<parts.size();i++) {
            Part part=(Part)parts.elementAt(i);
            if(part.mode==7 || part.depthAppearance!=null)continue;
            NativeImage texture=image(part.material.rImg);part.threshold=texture.threshold;
            PolygonMode depthPolygon=new PolygonMode();depthPolygon.setCulling(PolygonMode.CULL_BACK);depthPolygon.setWinding(PolygonMode.WINDING_CW);
            depthPolygon.setShading(PolygonMode.SHADE_SMOOTH);depthPolygon.setPerspectiveCorrectionEnable(part.perspectiveCorrection);
            CompositingMode depthComposite=new CompositingMode();depthComposite.setColorWriteEnable(false);depthComposite.setAlphaWriteEnable(false);
            depthComposite.setDepthTestEnable(true);depthComposite.setDepthWriteEnable(true);depthComposite.setBlending(CompositingMode.REPLACE);depthComposite.setAlphaThreshold(texture.threshold);
            Appearance depth=new Appearance();depth.setPolygonMode(depthPolygon);depth.setCompositingMode(depthComposite);
            if(texture.threshold>0)depth.setTexture(0,texture.texture);
            PolygonMode colorPolygon=new PolygonMode();colorPolygon.setCulling(PolygonMode.CULL_BACK);colorPolygon.setWinding(PolygonMode.WINDING_CW);
            colorPolygon.setShading(PolygonMode.SHADE_SMOOTH);colorPolygon.setPerspectiveCorrectionEnable(part.perspectiveCorrection);
            CompositingMode colorComposite=new CompositingMode();colorComposite.setDepthTestEnable(true);colorComposite.setDepthWriteEnable(false);
            colorComposite.setBlending(CompositingMode.REPLACE);colorComposite.setAlphaThreshold(texture.threshold);
            Appearance color=new Appearance();color.setPolygonMode(colorPolygon);color.setCompositingMode(colorComposite);color.setTexture(0,texture.texture);
            part.depthAppearance=depth;part.colorAppearance=color;
        }
    }

    private void sortPackets(Matrix m) {
        sortedPackets=0;
        if(drawOrder==null)return;
        float cx=m.m03,cy=m.m13,cz=m.m23;
        float fx=-(m.m02/(float)Matrix.FP),fy=-(m.m12/(float)Matrix.FP),fz=-(m.m22/(float)Matrix.FP);
        float ax=fx<0?-fx:fx,ay=fy<0?-fy:fy,az=fz<0?-fz:fz;
        for(int i=0;i<parts.size();i++) {
            Part part=(Part)parts.elementAt(i);
            if(part.mode==7 || part.selectedPackets==null)continue;
            for(int j=0;j<part.packets.length;j++) {
                if(part.selectedPackets[j]==null)continue;
                Packet q=part.packets[j];float depth=0;
                if(q.bounds!=null) {
                    int[] b=q.bounds;
                    float centerX=(b[0]+b[3])*0.5f,centerY=(b[1]+b[4])*0.5f,centerZ=(b[2]+b[5])*0.5f;
                    float halfX=(b[3]-b[0])*0.5f,halfY=(b[4]-b[1])*0.5f,halfZ=(b[5]-b[2])*0.5f;
                    depth=(centerX-cx)*fx+(centerY-cy)*fy+(centerZ-cz)*fz-(ax*halfX+ay*halfY+az*halfZ);
                }
                int code=i*2048+j,at=sortedPackets++;
                while(at>0 && drawDepth[at-1]>depth){drawOrder[at]=drawOrder[at-1];drawDepth[at]=drawDepth[at-1];at--;}
                drawOrder[at]=code;drawDepth[at]=depth;
            }
        }
    }

    /** Owns its target transaction; do NOT mix this depth buffer with the painter path.
     * Conventional world-space interpolation differs from legacy projected UV weights.
     * Supply the same camera and configured distance for reproducible preview poses.
     */
    public void render(Graphics target,Matrix cameraTransform,int width,int height,int focalX,int focalY,int far) {
        if(disposed)throw new IllegalStateException("disposed room");
        if(target==null || cameraTransform==null || width<=0 || height<=0 || focalX<=0 || focalY<=0 || far<=nearPlane)throw new IllegalArgumentException("camera/viewport");
        long began=System.currentTimeMillis();
        // Resolve animated images BEFORE bindTarget. Vertex/texture uploads stay
        // cold-only; a new visibility mask may build indices before binding.
        for(int i=0;i<parts.size();i++) {
            Part p=(Part)parts.elementAt(i);if(p.material.drawmode!=p.mode)throw new IllegalStateException("material edited; rebuild room");
            if(p.image!=p.material.rImg) {
                NativeImage n=null;for(int j=0;j<images.size();j++){NativeImage c=(NativeImage)images.elementAt(j);if(c.source==p.material.rImg){n=c;break;}}
                if(n==null)throw new IllegalStateException("unprepared animation; rebuild room");
                p.appearance.setTexture(0,n.texture);p.appearance.getCompositingMode().setAlphaThreshold(n.threshold);p.threshold=n.threshold;
                if(p.depthAppearance!=null){p.depthAppearance.setTexture(0,n.threshold>0?n.texture:null);p.depthAppearance.getCompositingMode().setAlphaThreshold(n.threshold);}
                if(p.colorAppearance!=null){p.colorAppearance.setTexture(0,n.texture);p.colorAppearance.getCompositingMode().setAlphaThreshold(n.threshold);}
                p.image=p.material.rImg;
            }
        }
        for(int i=0;i<16;i++)matrix[i]=0;
        matrix[0]=2.0f*focalX/width;matrix[5]=2.0f*focalY/height;
        // near=1 reproduces the original projection exactly. A larger near
        // distributes finite depth precision more usefully, but moves clipping.
        float near=nearPlane;
        matrix[10]=-(far+near)/(far-near);matrix[11]=-2.0f*near*far/(far-near);matrix[14]=-1;
        float px=matrix[0],py=matrix[5],pz=matrix[10],pw=matrix[11];
        projection.set(matrix);camera.setGeneric(projection);
        Matrix m=cameraTransform;float fp=Matrix.FP;
        matrix[0]=(m.m00>>2)/(fp/4);matrix[1]=(m.m01>>2)/(fp/4);matrix[2]=(m.m02>>2)/(fp/4);matrix[3]=m.m03;
        matrix[4]=(m.m10>>2)/(fp/4);matrix[5]=(m.m11>>2)/(fp/4);matrix[6]=(m.m12>>2)/(fp/4);matrix[7]=m.m13;
        matrix[8]=(m.m20>>2)/(fp/4);matrix[9]=(m.m21>>2)/(fp/4);matrix[10]=(m.m22>>2)/(fp/4);matrix[11]=m.m23;
        matrix[12]=matrix[13]=matrix[14]=0;matrix[15]=1;
        // Match the legacy focal-plane shift when distX == distY. Integer screen
        // rounding and the old texture-perspective denominator are not reproduced.
        matrix[3]+=matrix[2]*focalY;matrix[7]+=matrix[6]*focalY;matrix[11]+=matrix[10]*focalY;
        view.set(matrix);draws=submittedTriangles=submittedCorners=submittedBufferVertices=visibilityTests=culledCells=sortedPackets=0;
        if(cullingEnabled && !maskMemoryFallback)visibility.set(matrix,px,py,pz,pw);
        // Fail before binding on selection OOM. Do not change visible geometry
        // to a whole-room fallback during an A/B comparison.
        for(int i=0;i<parts.size();i++)selectVisible((Part)parts.elementAt(i));
        if(compactEnabled)for(int i=0;i<parts.size();i++)selectPackets((Part)parts.elementAt(i));
        if(compactEnabled && frontToBackEnabled){if(depthPrepassEnabled)ensurePrepassAppearances();sortPackets(cameraTransform);}
        long prepared=System.currentTimeMillis();prepareMs=duration(began,prepared);
        graphics.bindTarget(target,true,0);long bound=System.currentTimeMillis();bindMs=duration(prepared,bound);long drawEnd=bound;
        try {
            graphics.setViewport(0,0,width,height);graphics.setCamera(camera,view);graphics.resetLights();graphics.clear(background);
            long cleared=System.currentTimeMillis();clearMs=duration(bound,cleared);
            if(compactEnabled && frontToBackEnabled) {
                for(int i=0;i<parts.size();i++) {
                    Part p=(Part)parts.elementAt(i);
                    if(p.selected!=null){submittedTriangles+=p.selectedTriangles;submittedCorners+=p.selectedCorners;}
                }
                if(depthPrepassEnabled)for(int k=0;k<sortedPackets;k++) {
                    int code=drawOrder[k];Part p=(Part)parts.elementAt(code/2048);int j=code%2048;Packet q=p.packets[j];
                    graphics.render(q.vertices,p.selectedPackets[j],p.depthAppearance,null);draws++;submittedBufferVertices+=q.count;
                }
                for(int k=0;k<sortedPackets;k++) {
                    int code=drawOrder[k];Part p=(Part)parts.elementAt(code/2048);int j=code%2048;Packet q=p.packets[j];
                    graphics.render(q.vertices,p.selectedPackets[j],depthPrepassEnabled?p.colorAppearance:p.appearance,null);draws++;submittedBufferVertices+=q.count;
                }
                for(int i=0;i<parts.size();i++) {
                    Part p=(Part)parts.elementAt(i);if(p.mode!=7 || p.selectedPackets==null)continue;
                    for(int j=0;j<p.packets.length;j++)if(p.selectedPackets[j]!=null) {
                        Packet q=p.packets[j];graphics.render(q.vertices,p.selectedPackets[j],p.appearance,null);draws++;submittedBufferVertices+=q.count;
                    }
                }
            } else {
                for(int pass=0;pass<2;pass++)for(int i=0;i<parts.size();i++) {
                    Part p=(Part)parts.elementAt(i);if((p.mode==7)!=(pass==1) || p.selected==null)continue;
                    if(!compactEnabled){graphics.render(p.vertices,p.selected,p.appearance,null);draws++;submittedBufferVertices+=p.vertexCount;}
                    else for(int j=0;j<p.packets.length;j++)if(p.selectedPackets[j]!=null) {
                        Packet q=p.packets[j];graphics.render(q.vertices,p.selectedPackets[j],p.appearance,null);draws++;submittedBufferVertices+=q.count;
                    }
                    submittedTriangles+=p.selectedTriangles;submittedCorners+=p.selectedCorners;
                }
            }
            drawEnd=System.currentTimeMillis();drawMs=duration(cleared,drawEnd);
        } finally {graphics.releaseTarget();releaseMs=duration(drawEnd,System.currentTimeMillis());}
    }
    private static int duration(long start,long end){return (int)Math.max(0,end-start);}

    /** Contiguous spatial runs: keep ORIGINAL face/strip submission order,
     * including equal-depth surfaces and river MODULATE. No sorting or repacking
     * coordinates: each buffer is an exact slice of the parent packed arrays.
     */
    private static final class Packet {
        int firstFace,endFace,firstVertex,count;
        int[] bounds;
        VertexBuffer vertices;TriangleStripArray indices;
    }
    private static int packetEnd(Part p,int first) {
        int corners=0,minX=4,minZ=4,maxX=-1,maxZ=-1,f=first;
        for(;f<p.sourceStrips.length;f++) {
            int cell=p.faceCells[f]&255,x=cell%4,z=cell/4;
            int x0=Math.min(minX,x),z0=Math.min(minZ,z),x1=Math.max(maxX,x),z1=Math.max(maxZ,z);
            // At most 2x2 parent-grid centroid cells, and 512 corners. Faces
            // crossing cells are kept WHOLE; this is not a new visibility test.
            if(f>first && (corners+p.sourceStrips[f]>PACKET_VERTICES || x1-x0>=2 || z1-z0>=2))break;
            corners+=p.sourceStrips[f];minX=x0;minZ=z0;maxX=x1;maxZ=z1;
        }
        return f;
    }
    private void buildCompact() {
        // A is complete first. Failure allocating optional B cannot prevent A
        // loading just because B consumed the remaining native heap.
        for(int i=0;i<parts.size();i++) {
            Part p=(Part)parts.elementAt(i);
            if(!compactUnavailable)try{buildPackets(p,p.packedXYZ,p.packedUV,p.packedColor,p.packedScale,p.packedBias);}
            catch(OutOfMemoryError ex){dropPackets();}
            p.packedXYZ=p.packedUV=null;p.packedColor=null;p.packedBias=null;
        }
    }
    public void discardCompact(){dropPackets();}
    private void dropPackets() {
        for(int i=0;i<parts.size();i++){Part p=(Part)parts.elementAt(i);p.packets=null;p.fullPackets=p.selectedPackets=null;for(int j=0;j<p.masks.length;j++)if(p.masks[j]!=null)p.masks[j].packets=null;}
        compactUnavailable=true;compactEnabled=false;packetCount=compactUploadedVertices=compactGeometryUploads=0;
    }
    private void buildPackets(Part p,short[] xyz,short[] uv,byte[] color,float scale,float[] bias) {
        int count=0;for(int f=0;f<p.sourceStrips.length;){f=packetEnd(p,f);count++;}
        if(packetCount+count>MAX_PACKETS){dropPackets();return;}
        p.packets=new Packet[count];p.fullPackets=new TriangleStripArray[count];int face=0,vertex=0;
        for(int i=0;i<count;i++) {
            Packet q=new Packet();q.firstFace=face;q.endFace=packetEnd(p,face);q.firstVertex=vertex;
            for(int f=face;f<q.endFace;f++)q.count+=p.sourceStrips[f];
            short[] positions=new short[q.count*3],coords=new short[q.count*2];byte[] colors=new byte[q.count*4];
            System.arraycopy(xyz,vertex*3,positions,0,positions.length);System.arraycopy(uv,vertex*2,coords,0,coords.length);System.arraycopy(color,vertex*4,colors,0,colors.length);
            VertexArray a=new VertexArray(q.count,3,2),b=new VertexArray(q.count,2,2),c=new VertexArray(q.count,4,1);
            a.set(0,q.count,positions);b.set(0,q.count,coords);c.set(0,q.count,colors);
            q.vertices=new VertexBuffer();q.vertices.setPositions(a,scale,bias);q.vertices.setTexCoords(0,b,1.0f/256,null);q.vertices.setColors(c);
            q.bounds=new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            int step=(int)scale;
            for(int v=0;v<q.count;v++)for(int axis=0;axis<3;axis++) {
                int world=positions[v*3+axis]*step+(int)bias[axis];
                if(world<q.bounds[axis])q.bounds[axis]=world;
                if(world>q.bounds[axis+3])q.bounds[axis+3]=world;
            }
            int[] indices=new int[q.count],strips=new int[q.endFace-face];
            for(int j=0;j<indices.length;j++)indices[j]=p.sourceIndices[vertex+j]-vertex;
            System.arraycopy(p.sourceStrips,face,strips,0,strips.length);q.indices=new TriangleStripArray(indices,strips);
            p.packets[i]=q;p.fullPackets[i]=q.indices;packetCount++;compactUploadedVertices+=q.count;compactGeometryUploads+=3;
            face=q.endFace;vertex+=q.count;
        }
    }
    private static boolean included(Part p,Masked mask,int face){return mask.faces!=null?mask.faces[face]!=0:(mask.mask&(1<<p.faceCells[face]))!=0;}
    private void selectPackets(Part p) {
        if(p.selected==null){p.selectedPackets=null;return;}
        if(p.selected==p.indices){p.selectedPackets=p.fullPackets;return;}
        Masked mask=null;for(int i=0;i<p.masks.length;i++)if(p.masks[i]!=null && p.masks[i].indices==p.selected){mask=p.masks[i];break;}
        if(mask==null)throw new IllegalStateException("selected mask missing");
        if(mask.packets==null) {
            TriangleStripArray[] selected=new TriangleStripArray[p.packets.length];
            for(int i=0;i<p.packets.length;i++) {
                Packet q=p.packets[i];int corners=0,faces=0;
                for(int f=q.firstFace;f<q.endFace;f++)if(included(p,mask,f)){corners+=p.sourceStrips[f];faces++;}
                if(faces==0)continue;
                if(faces==q.endFace-q.firstFace){selected[i]=q.indices;continue;}
                int[] indices=new int[corners],strips=new int[faces];int source=q.firstVertex,dest=0,strip=0;
                for(int f=q.firstFace;f<q.endFace;f++) {
                    int length=p.sourceStrips[f];
                    if(included(p,mask,f)){for(int j=0;j<length;j++)indices[dest++]=p.sourceIndices[source+j]-q.firstVertex;strips[strip++]=length;}
                    source+=length;
                }
                selected[i]=new TriangleStripArray(indices,strips);compactMaskUploads++;
            }
            // Atomic publish: failure cannot leave a half-built mask drawable.
            mask.packets=selected;
        }
        p.selectedPackets=mask.packets;
    }
    public void setCompactEnabled(boolean enabled){if(enabled && compactUnavailable)throw new IllegalStateException("compact buffers unavailable; use A");compactEnabled=enabled;}
    public boolean isCompactEnabled(){return compactEnabled;}
    public boolean isCompactAvailable(){return !compactUnavailable;}
    public void setFrontToBackEnabled(boolean enabled){frontToBackEnabled=enabled;}
    public boolean isFrontToBackEnabled(){return frontToBackEnabled;}
    public void setDepthPrepassEnabled(boolean enabled){depthPrepassEnabled=enabled;}
    public boolean isDepthPrepassEnabled(){return depthPrepassEnabled;}

    public int parts(){return parts.size();}
    public void dispose(){parts.removeAllElements();images.removeAllElements();pixels=0;disposed=true;}
}
