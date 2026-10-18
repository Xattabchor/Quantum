package code.Rendering;

import code.Rendering.Meshes.Sprite;
import javax.microedition.m3g.*;

/** Screen-aligned billboards in the original painter queue, not a HUD overlay. */
final class M3GSpriteRenderer {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final VertexBuffer buffer=new VertexBuffer();
    private final VertexArray positions=new VertexArray(4,3,2);
    private final VertexArray coords=new VertexArray(4,2,2);
    private final VertexArray colors=new VertexArray(4,4,1);
    private final TriangleStripArray indices=new TriangleStripArray(
            new int[]{0,1,3,1,2,3},new int[]{3,3});
    private final Appearance appearance=new Appearance();
    private final CompositingMode composite=new CompositingMode();
    private final Fog fog=new Fog();
    private final short[] xyz=new short[12],uv=new short[8];
    private final byte[] rgba=new byte[16];
    // Adjacent compatible billboards only; never sort NPCs by texture.
    private static final int MAX_SPRITES=32;
    private static final int[] ORDER={0,1,3,1,2,3};
    private final short[] firstXYZ=new short[12],firstUV=new short[8];
    private short[] batchXYZ,batchUV;
    private VertexBuffer batchBuffer;
    private VertexArray batchPositions,batchCoords;
    private final TriangleStripArray[] batchIndices=new TriangleStripArray[MAX_SPRITES];
    private int pending,blend,levelFilter,imageFilter,fogColor;
    private Texture2D image;
    private boolean fogged;
    private float scale,far,threshold;

    M3GSpriteRenderer(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        owner.registerPolygonMode(polygon);
        appearance.setPolygonMode(polygon);appearance.setCompositingMode(composite);
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        fog.setMode(Fog.LINEAR);
        buffer.setTexCoords(0,coords,1.0f/4096,null);buffer.setColors(colors);
        color(0xffffff,255);
    }

    boolean draw(DirectX7 g,Sprite sprite) {
        // Replace legacy MUL with native MODULATE, retaining its neutral key
        // and selected channel mask. Unknown modes/colors retain CPU dispatch.
        if(sprite.mode<0 || sprite.mode>3 || (sprite.mode==2 && (sprite.color<0 || sprite.color>6))) return false;
        if(sprite.textures==null || sprite.anim_index<0 || sprite.anim_index>=sprite.textures.length) return false;
        Texture texture=sprite.textures[sprite.anim_index];
        if(texture==null || sprite.pos==null || sprite.size==null) return false;
        RawImage raw=texture.mip!=null && texture.mip.length>0?texture.mip[0]:texture.rImg;
        int left=Math.min(sprite.pos.sx,sprite.size.sx),right=Math.max(sprite.pos.sx,sprite.size.sx);
        int top=Math.min(sprite.pos.sy,sprite.size.sy),bottom=Math.max(sprite.pos.sy,sprite.size.sy);
        int x1=Math.max(0,left),x2=Math.min(g.width,right),y1=Math.max(0,top),y2=Math.min(g.height,bottom);
        if(x2<=x1 || y2<=y1) return true;
        if(!owner.supportsSpriteTexture(raw)) return false;
        int bound=Math.max(Math.max(Math.abs(g.centreX),Math.abs(g.width-g.centreX)),
                Math.max(Math.abs(g.centreY),Math.abs(g.height-g.centreY)));
        int precision=Math.min(16,30000/Math.max(1,bound));
        if(precision<1) return false;
        // Clip geometry AND UVs before packing, avoiding overflow for huge near
        // billboards while keeping texture scale and mirroring unchanged.
        float u1=((float)x1-left)/((float)right-left),u2=((float)x2-left)/((float)right-left);
        float v1=((float)y1-top)/((float)bottom-top),v2=((float)y2-top)/((float)bottom-top);
        if(sprite.mirX) {u1=1-u1;u2=1-u2;}
        if(sprite.mirY) {v1=1-v1;v2=1-v2;}
        vertex(0,x1,y1,u1,v1,g,precision);
        vertex(1,x2,y1,u2,v1,g,precision);
        vertex(2,x2,y2,u2,v2,g,precision);
        vertex(3,x1,y2,u1,v2,g,precision);
        boolean alpha=sprite.mode==3 && raw.alphaMixing;
        int kind=sprite.mode==2?M3GSceneRenderer.SPRITE_MUL+sprite.color:
                sprite.mode==1?M3GSceneRenderer.SPRITE_ADD:
                alpha?M3GSceneRenderer.SPRITE_ALPHA:M3GSceneRenderer.SPRITE_CUTOUT;
        // ADD/MUL sprites keep their original no-fog semantics; fDist=0 disables
        // sprite fog as before. No CPU visibility or white mask texture remains.
        boolean fogged=sprite.fog && sprite.mode!=1 && sprite.mode!=2 && DirectX7.fDist!=0;
        float eye=1.0f,far=0;
        if(fogged) {
            if(DirectX7.fDist<0 || TexturingPers.move<=0) return false;
            // Legacy billboards can remain visible after their origin passes the
            // camera (large trees/cutoff). Their positive rz has ZERO fog, not
            // negative eye depth. Fog.LINEAR starts at 1: clamp there to retain
            // the image/UVs without crossing the generic Camera's near plane.
            eye=1.0f-(float)Math.min(0,sprite.pos.rz)/TexturingPers.move;
            far=owner.fogFar(TexturingPers.move);
            if(!(eye>0) || !(far>1)) return false;
        }
        Texture2D desired=owner.texture(g,raw,kind);
        float cutoutThreshold=owner.textureCutoutThreshold();
        float scale=eye/precision;
        int blend=sprite.mode==2?CompositingMode.MODULATE:
                sprite.mode==1?CompositingMode.ALPHA_ADD:
                alpha?CompositingMode.ALPHA:CompositingMode.REPLACE;
        float threshold=alpha || sprite.mode==1?0:cutoutThreshold;
        int fogColor=fogged?(DirectX7.standartDrawmode==6?0:DirectX7.fogc&0xffffff):0;
        int level=Texture2D.FILTER_BASE_LEVEL,filter=owner.textureImageFilter();
        // Keep exact eye depth: unrelated Fog scales deliberately split.
        // Snapshot all state before a later cache lookup can mutate Texture2D.
        if(pending!=0 && (pending==MAX_SPRITES || image!=desired || this.blend!=blend
                || this.threshold!=threshold || this.scale!=scale || this.fogged!=fogged
                || this.far!=far || this.fogColor!=fogColor
                || levelFilter!=level || imageFilter!=filter)) flush(g);
        if(pending==0) {
            image=desired;this.blend=blend;this.threshold=threshold;this.scale=scale;
            this.fogged=fogged;this.far=far;this.fogColor=fogColor;
            levelFilter=level;imageFilter=filter;
            System.arraycopy(xyz,0,firstXYZ,0,12);System.arraycopy(uv,0,firstUV,0,8);
        } else {
            if(pending==1) {prepareBatch();append(firstXYZ,firstUV,0);}
            append(xyz,uv,pending);
        }
        pending++;
        return true;
    }

    private void vertex(int i,int x,int y,float u,float v,DirectX7 g,int precision) {
        xyz[i*3]=(short)((x-g.centreX)*precision);
        xyz[i*3+1]=(short)((g.centreY-y)*precision);xyz[i*3+2]=(short)-precision;
        uv[i*2]=(short)(u*4096+0.5f);uv[i*2+1]=(short)(v*4096+0.5f);
    }
    private void color(int rgb,int alpha) {
        for(int i=0;i<4;i++) {
            rgba[i*4]=(byte)(rgb>>16);rgba[i*4+1]=(byte)(rgb>>8);
            rgba[i*4+2]=(byte)rgb;rgba[i*4+3]=(byte)alpha;
        }
        colors.set(0,4,rgba);
    }
    private void prepareBatch() {
        if(batchBuffer!=null) return;
        int vertices=MAX_SPRITES*4;
        batchXYZ=new short[vertices*3];batchUV=new short[vertices*2];
        batchPositions=new VertexArray(vertices,3,2);batchCoords=new VertexArray(vertices,2,2);
        VertexArray white=new VertexArray(vertices,4,1);byte[] rgba=new byte[vertices*4];
        for(int i=0;i<rgba.length;i++) rgba[i]=(byte)255;
        white.set(0,vertices,rgba);
        batchBuffer=new VertexBuffer();batchBuffer.setColors(white);
        batchBuffer.setTexCoords(0,batchCoords,1.0f/4096,null);
    }

    private void append(short[] source,short[] texture,int sprite) {
        System.arraycopy(source,0,batchXYZ,sprite*12,12);
        System.arraycopy(texture,0,batchUV,sprite*8,8);
    }

    void flush(DirectX7 g) {
        int count=pending;if(count==0) return;
        pending=0; // A failed native packet is recovered by full CPU replay.
        appearance.setTexture(0,image);image.setBlending(Texture2D.FUNC_MODULATE);
        image.setFiltering(levelFilter,imageFilter);
        composite.setBlending(blend);composite.setAlphaThreshold(threshold);
        if(fogged) {fog.setLinear(1.0f,far);fog.setColor(fogColor);}
        appearance.setFog(fogged?fog:null);
        VertexBuffer drawBuffer=buffer;TriangleStripArray drawIndices=indices;
        if(count==1) {
            positions.set(0,4,firstXYZ);coords.set(0,4,firstUV);
            buffer.setPositions(positions,scale,null);
        } else {
            int vertices=count*4;
            batchPositions.set(0,vertices,batchXYZ);batchCoords.set(0,vertices,batchUV);
            batchBuffer.setPositions(batchPositions,scale,null);
            if(batchIndices[count-1]==null) {
                int[] lengths=new int[count*2],order=new int[count*6];
                for(int i=0;i<lengths.length;i++) lengths[i]=3;
                for(int i=0;i<count;i++) for(int j=0;j<6;j++) order[i*6+j]=i*4+ORDER[j];
                batchIndices[count-1]=new TriangleStripArray(order,lengths);
            }
            drawBuffer=batchBuffer;drawIndices=batchIndices[count-1];
        }
        owner.bind(g);owner.restoreCamera(g);owner.renderNative(g,drawBuffer,drawIndices,appearance);
        image=null;
    }
}
