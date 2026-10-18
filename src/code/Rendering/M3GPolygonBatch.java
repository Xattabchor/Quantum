package code.Rendering;

import javax.microedition.m3g.*;

/** Ordered runs only: never sort by texture or move a polygon past a barrier. */
final class M3GPolygonBatch {
    static final class State {
        Texture2D texture;
        PolygonMode polygon;
        int blend,function,blendColor,imageFilter=Texture2D.FILTER_NEAREST,fogColor,fogMode=Fog.LINEAR;
        float threshold,near,far,density;
        boolean fogged,opaque;
    }

    private static final int MAX_VERTICES=192; // 64 triangles / 32 quads
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final short[] xyz=new short[MAX_VERTICES*3],uv=new short[MAX_VERTICES*2];
    private final byte[] rgba=new byte[MAX_VERTICES*4];
    private final short[] firstXYZ=new short[12],firstUV=new short[8];
    private final short[] rebasedXYZ=new short[12];
    private final byte[] firstRGBA=new byte[16];
    private final Appearance appearance=new Appearance();
    private final CompositingMode composite=new CompositingMode();
    private final Fog fog=new Fog();
    private final TriangleStripArray[] indexCache=new TriangleStripArray[MAX_VERTICES/3];
    private VertexBuffer buffer;
    // One grow-only set: do not make a two-triangle packet a 192-vertex buffer.
    private int capacity;
    private float appliedScale;
    private boolean scaleReady;
    private VertexArray positions,coords,colors;
    private Texture2D texture;
    private PolygonMode polygon;
    private int blend,function,blendColor,levelFilter,imageFilter,fogColor,fogMode;
    private float threshold,near,far,scale,density;
    private boolean fogged,opaque,solid;
    private int appliedFogMode=Fog.LINEAR;
    private float appliedDensity=-1;
    private float guardedFar=-1,guardLimit;
    private int firstCount,size;
    private int[] firstOrder;
    private TriangleStripArray firstIndices;

    M3GPolygonBatch(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        appearance.setCompositingMode(composite);fog.setMode(Fog.LINEAR);
    }

    void add(DirectX7 g,int count,float scale,short[] positions,short[] coords,byte[] colors,
            int[] order,TriangleStripArray indices,State source) {
        Texture2D tex=source.texture;PolygonMode poly=source.polygon;
        int blend=source.blend,function=source.function;
        int level=Texture2D.FILTER_BASE_LEVEL,filter=source.imageFilter;
        float threshold=source.threshold;
        boolean fogged=source.fogged;
        int color=fogged?source.fogColor:0;
        float near=fogged?source.near:0,far=fogged?source.far:0;
        int fogMode=fogged?source.fogMode:Fog.LINEAR;
        float density=fogged?source.density:0;
        // Capture desired state BEFORE flushing: a cached Texture2D may already
        // have been changed by preparation of the next material.
        boolean different=texture!=tex || polygon!=poly || this.blend!=blend
                || this.function!=function || blendColor!=source.blendColor || levelFilter!=level || imageFilter!=filter
                || this.threshold!=threshold || this.fogged!=fogged || fogColor!=color
                || this.near!=near || this.far!=far || this.fogMode!=fogMode || this.density!=density;
        // Exact powers-of-two rebasing only: keep XYZ * scale, screen endpoints,
        // perspective weights and native Fog depth unchanged. No quantization.
        if(firstCount!=0 && !different && fogged && this.scale!=scale
                && (size==0?firstOrder.length:size)+order.length<=MAX_VERTICES
                && rebase(scale,positions,count)) {
            positions=rebasedXYZ;scale=this.scale;
        }
        // Only fold when an ORIGINAL packet boundary would otherwise occur.
        // An isolated far polygon keeps its original texture/Fog state, so it
        // can still batch with a following near polygon of the same material.
        if(firstCount!=0 && (different || this.scale!=scale) && fogged && this.fogged
                && (size==0?firstOrder.length:size)+order.length<=MAX_VERTICES
                && source.opaque && opaque && fogMode==Fog.LINEAR && this.fogMode==Fog.LINEAR
                && blend==CompositingMode.REPLACE && this.blend==blend
                && function==Texture2D.FUNC_MODULATE && (solid || this.function==function)
                && threshold==0 && this.threshold==0 && polygon==poly && fogColor==color
                && blendColor==source.blendColor && near>=0 && far>near && this.far>this.near
                && saturated(positions,colors,count,scale,far)
                && (solid || saturated(size==0?firstXYZ:xyz,size==0?firstRGBA:rgba,
                    size==0?firstCount:size,this.scale,this.far))) {
            // Every covered pixel is opaque Fog color, regardless of texture
            // or depth. Preserve ordered XYZ ratios/UV/RGBA, using native Fog
            // at full saturation and a common positive homogeneous scale.
            texture=null;this.function=0;imageFilter=Texture2D.FILTER_NEAREST;
            this.near=0;this.far=0.5f;this.scale=1;solid=true;
            tex=null;function=0;filter=Texture2D.FILTER_NEAREST;near=0;far=0.5f;scale=1;
            different=false;
        }
        if(firstCount!=0 && (different || (fogged && this.scale!=scale)
                || (size==0?firstOrder.length:size)+order.length>MAX_VERTICES)) flush(g);
        if(firstCount==0) {
            texture=tex;polygon=poly;this.blend=blend;this.function=function;blendColor=source.blendColor;
            levelFilter=level;imageFilter=filter;this.threshold=threshold;
            this.fogged=fogged;fogColor=color;this.near=near;this.far=far;this.scale=scale;this.fogMode=fogMode;this.density=density;
            firstCount=count;firstOrder=order;firstIndices=indices;opaque=source.opaque;solid=false;
            System.arraycopy(positions,0,firstXYZ,0,count*3);
            System.arraycopy(coords,0,firstUV,0,count*2);
            System.arraycopy(colors,0,firstRGBA,0,count*4);
            return;
        }
        opaque &= source.opaque;
        if(size==0) append(firstXYZ,firstUV,firstRGBA,firstOrder);
        append(positions,coords,colors,order);
    }

    private boolean saturated(short[] positions,byte[] colors,int count,float scale,float far) {
        if(!(scale>0) || !(scale<1.0e30f) || !(far<Float.MAX_VALUE)) return false;
        if(guardedFar!=far) {guardedFar=far;guardLimit=far*1.001f+0.01f;}
        int closest=positions[2];
        for(int i=1;i<count;i++) closest=Math.max(closest,positions[i*3+2]);
        if(closest>=0 || -closest*scale <= guardLimit) return false;
        for(int i=0;i<count;i++) if((colors[i*4+3]&255)!=255) return false;
        return true;
    }

    private boolean rebase(float incoming,short[] source,int count) {
        int a=Float.floatToIntBits(scale),b=Float.floatToIntBits(incoming);
        int ea=(a>>>23)&255,eb=(b>>>23)&255;
        // Identical mantissas + normal positive exponents prove an exact binary
        // ratio, without a soft-float divide on ARM9. Never merge unrelated
        // legacy scales, NaN/Inf, zero, negative or denormal values.
        if(a<=0 || b<=0 || ea==0 || eb==0 || ea==255 || eb==255
                || ((a^b)&0x7fffff)!=0) return false;
        int shift=Math.abs(ea-eb);
        if(shift==0 || shift>15) return false;
        short[] pending=size==0?firstXYZ:xyz;int length=(size==0?firstCount:size)*3;
        int input=count*3;boolean pendingFine=a<b;
        // Prefer the finer scale if the coarser packet fits after exact shift.
        if(fits(pendingFine?source:pending,pendingFine?input:length,shift,false)) {
            if(!pendingFine) convert(pending,pending,length,shift,false);
            convert(source,rebasedXYZ,input,pendingFine?shift:0,false);
            if(!pendingFine) scale=incoming;
            return true;
        }
        // Coarsening is allowed only when EVERY component divides exactly.
        // Preflight the whole packet before touching any pending vertex.
        if(!fits(pendingFine?pending:source,pendingFine?length:input,shift,true)) return false;
        if(pendingFine) convert(pending,pending,length,shift,true);
        convert(source,rebasedXYZ,input,pendingFine?0:shift,true);
        if(pendingFine) scale=incoming;
        return true;
    }
    private static boolean fits(short[] values,int length,int shift,boolean divide) {
        int mask=(1<<shift)-1;
        for(int i=0;i<length;i++) {
            int v=values[i];
            if(divide) {if((v&mask)!=0) return false;}
            else if((v<<shift) < -32767 || (v<<shift)>32767) return false;
        }
        return true;
    }
    private static void convert(short[] source,short[] target,int length,int shift,boolean divide) {
        for(int i=0;i<length;i++) target[i]=(short)(divide?source[i]>>shift:source[i]<<shift);
    }

    private void append(short[] positions,short[] coords,byte[] colors,int[] order) {
        for(int i=0;i<order.length;i++) {
            int vertex=order[i];
            System.arraycopy(positions,vertex*3,xyz,size*3,3);
            System.arraycopy(coords,vertex*2,uv,size*2,2);
            System.arraycopy(colors,vertex*4,rgba,size*4,4);
            size++;
        }
    }

    void discard() {firstCount=0;size=0;texture=null;polygon=null;firstOrder=null;firstIndices=null;}

    void flush(DirectX7 g) {
        if(firstCount==0) return;
        int count=firstCount,vertices=size;TriangleStripArray indices=firstIndices;
        firstCount=0;size=0; // No retries of a partially failed native submission.
        appearance.setPolygonMode(polygon);appearance.setTexture(0,texture);
        composite.setBlending(blend);composite.setAlphaThreshold(threshold);
        if(texture!=null) {texture.setBlending(function);if(function==Texture2D.FUNC_BLEND) texture.setBlendColor(blendColor);texture.setFiltering(levelFilter,imageFilter);}
        if(fogged) {
            if(appliedFogMode!=fogMode) {fog.setMode(fogMode);appliedFogMode=fogMode;}
            fog.setColor(fogColor);
            if(fogMode==Fog.EXPONENTIAL) {
                if(appliedDensity!=density) {fog.setDensity(density);appliedDensity=density;}
            } else fog.setLinear(near,far);
            appearance.setFog(fog);
        }
        else appearance.setFog(null);
        if(vertices==0) {
            owner.renderSingle(g,count,scale,firstXYZ,firstUV,firstRGBA,indices,appearance);
        } else {
            if(capacity<vertices) {
                int nextCapacity=6;while(nextCapacity<vertices) nextCapacity*=2;
                // Publish only a complete, matching set. On allocation/API failure
                // the old buffer remains valid, never paired with partial arrays.
                VertexBuffer next=new VertexBuffer();
                VertexArray nextPositions=new VertexArray(nextCapacity,3,2);
                VertexArray nextCoords=new VertexArray(nextCapacity,2,2);
                VertexArray nextColors=new VertexArray(nextCapacity,4,1);
                next.setColors(nextColors);next.setTexCoords(0,nextCoords,1.0f/256,null);
                buffer=next;positions=nextPositions;coords=nextCoords;colors=nextColors;
                capacity=nextCapacity;scaleReady=false;
            }
            positions.set(0,vertices,xyz);coords.set(0,vertices,uv);colors.set(0,vertices,rgba);
            // With no eye-space Fog, a common positive factor per polygon cancels
            // in projection and perspective interpolation. Keep each original
            // integer homogeneous weight: no re-quantization or shared-edge drift.
            // Native Fog still requires an EXACT common eye-space scale. Fog
            // packing now shares a binary depth lattice when representable.
            float positionScale=fogged?scale:1.0f;
            if(!scaleReady || appliedScale!=positionScale) {
                buffer.setPositions(positions,positionScale,null);
                appliedScale=positionScale;scaleReady=true;
            }
            int triangles=vertices/3;
            if(indexCache[triangles-1]==null) {
                int[] lengths=new int[triangles];for(int i=0;i<triangles;i++) lengths[i]=3;
                indexCache[triangles-1]=new TriangleStripArray(0,lengths);
            }
            owner.bind(g);owner.restoreCamera(g);
            owner.renderNative(g,buffer,indexCache[triangles-1],appearance);
        }
        texture=null;polygon=null;firstOrder=null;firstIndices=null;
    }
}
