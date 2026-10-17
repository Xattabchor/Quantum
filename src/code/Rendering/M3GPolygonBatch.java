package code.Rendering;

import javax.microedition.m3g.*;

/** Ordered runs only: never sort by texture or move a polygon past a barrier. */
final class M3GPolygonBatch {
    static final class State {
        Texture2D texture;
        Material material;
        int lightX,lightY,lightZ;
        PolygonMode polygon;
        int blend,function,imageFilter=Texture2D.FILTER_NEAREST,fogColor;
        float threshold,near,far;
        boolean fogged;
    }

    private static final int MAX_VERTICES=192; // 64 triangles / 32 quads
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final short[] xyz=new short[MAX_VERTICES*3],uv=new short[MAX_VERTICES*2];
    private final byte[] rgba=new byte[MAX_VERTICES*4];
    private final short[] firstXYZ=new short[12],firstUV=new short[8],firstNormals=new short[12];
    private final short[] packedNormals=new short[MAX_VERTICES*3];
    private final byte[] firstRGBA=new byte[16];
    private final Appearance appearance=new Appearance();
    private final CompositingMode composite=new CompositingMode();
    private final Fog fog=new Fog();
    private final TriangleStripArray[] indexCache=new TriangleStripArray[MAX_VERTICES/3];
    private VertexBuffer buffer;
    private VertexArray positions,coords,colors,normals;
    private Material material;
    private int lightX,lightY,lightZ;
    private Texture2D texture;
    private PolygonMode polygon;
    private int blend,function,levelFilter,imageFilter,fogColor;
    private float threshold,near,far,scale;
    private boolean fogged;
    private int firstCount,size;
    private int[] firstOrder;
    private TriangleStripArray firstIndices;

    M3GPolygonBatch(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        appearance.setCompositingMode(composite);fog.setMode(Fog.LINEAR);
    }

    void add(DirectX7 g,int count,float scale,short[] positions,short[] coords,byte[] colors,short[] normals,
            int[] order,TriangleStripArray indices,State source) {
        Texture2D tex=source.texture;PolygonMode poly=source.polygon;
        int blend=source.blend,function=source.function;
        int level=Texture2D.FILTER_BASE_LEVEL,filter=source.imageFilter;
        float threshold=source.threshold;
        boolean fogged=source.fogged;
        int color=fogged?source.fogColor:0;
        float near=fogged?source.near:0,far=fogged?source.far:0;
        // Capture desired state BEFORE flushing: a cached Texture2D may already
        // have been changed by preparation of the next material.
        if(firstCount!=0 && (material!=source.material || lightX!=source.lightX || lightY!=source.lightY || lightZ!=source.lightZ
                || texture!=tex || polygon!=poly || this.blend!=blend
                || this.function!=function || levelFilter!=level || imageFilter!=filter
                || this.threshold!=threshold || this.fogged!=fogged || fogColor!=color
                || this.near!=near || this.far!=far || (fogged && this.scale!=scale)
                || (size==0?firstOrder.length:size)+order.length>MAX_VERTICES)) flush(g);
        if(firstCount==0) {
            material=source.material;lightX=source.lightX;lightY=source.lightY;lightZ=source.lightZ;
            texture=tex;polygon=poly;this.blend=blend;this.function=function;
            levelFilter=level;imageFilter=filter;this.threshold=threshold;
            this.fogged=fogged;fogColor=color;this.near=near;this.far=far;this.scale=scale;
            firstCount=count;firstOrder=order;firstIndices=indices;
            System.arraycopy(positions,0,firstXYZ,0,count*3);
            System.arraycopy(coords,0,firstUV,0,count*2);
            System.arraycopy(colors,0,firstRGBA,0,count*4);
            if(material!=null) System.arraycopy(normals,0,firstNormals,0,count*3);
            return;
        }
        if(size==0) append(firstXYZ,firstUV,firstRGBA,firstNormals,firstOrder);
        append(positions,coords,colors,normals,order);
    }

    private void append(short[] positions,short[] coords,byte[] colors,short[] normals,int[] order) {
        for(int i=0;i<order.length;i++) {
            int vertex=order[i];
            System.arraycopy(positions,vertex*3,xyz,size*3,3);
            System.arraycopy(coords,vertex*2,uv,size*2,2);
            System.arraycopy(colors,vertex*4,rgba,size*4,4);
            if(material!=null) System.arraycopy(normals,vertex*3,packedNormals,size*3,3);size++;
        }
    }

    void discard() {firstCount=0;size=0;texture=null;polygon=null;firstOrder=null;firstIndices=null;}

    void flush(DirectX7 g) {
        if(firstCount==0) return;
        int count=firstCount,vertices=size;TriangleStripArray indices=firstIndices;
        firstCount=0;size=0; // No retries of a partially failed native submission.
        appearance.setPolygonMode(polygon);appearance.setTexture(0,texture);appearance.setMaterial(material);
        composite.setBlending(blend);composite.setAlphaThreshold(threshold);
        if(texture!=null) {texture.setBlending(function);texture.setFiltering(levelFilter,imageFilter);}
        if(fogged) {fog.setColor(fogColor);fog.setLinear(near,far);appearance.setFog(fog);}
        else appearance.setFog(null);
        if(vertices==0) {
            owner.renderSingle(g,count,scale,firstXYZ,firstUV,firstRGBA,firstNormals,indices,appearance,material!=null,lightX,lightY,lightZ);
        } else {
            if(buffer==null) {
                buffer=new VertexBuffer();positions=new VertexArray(MAX_VERTICES,3,2);
                coords=new VertexArray(MAX_VERTICES,2,2);colors=new VertexArray(MAX_VERTICES,4,1);
                buffer.setColors(colors);buffer.setTexCoords(0,coords,1.0f/256,null);
            }
            positions.set(0,vertices,xyz);coords.set(0,vertices,uv);colors.set(0,vertices,rgba);
            if(material!=null) {
                if(normals==null) normals=new VertexArray(MAX_VERTICES,3,2);
                normals.set(0,vertices,packedNormals);buffer.setNormals(normals);
            } else buffer.setNormals(null);
            // With no eye-space Fog, a common positive factor per polygon cancels
            // in projection and perspective interpolation. Keep each original
            // integer homogeneous weight: no re-quantization or shared-edge drift.
            // Native Fog still requires an EXACT common eye-space scale. Fog
            // packing now shares a binary depth lattice when representable.
            buffer.setPositions(positions,fogged?scale:1.0f,null);
            int triangles=vertices/3;
            if(indexCache[triangles-1]==null) {
                int[] lengths=new int[triangles];for(int i=0;i<triangles;i++) lengths[i]=3;
                indexCache[triangles-1]=new TriangleStripArray(0,lengths);
            }
            owner.bind(g);owner.restoreCamera(g);
            owner.renderNative(g,buffer,indexCache[triangles-1],appearance,material!=null,lightX,lightY,lightZ);
        }
        texture=null;polygon=null;firstOrder=null;firstIndices=null;
    }
}
