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
    private final short[] xyz=new short[12],uv=new short[8];
    private final byte[] rgba=new byte[16];

    M3GSpriteRenderer(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);appearance.setCompositingMode(composite);
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        buffer.setTexCoords(0,coords,1.0f/4096,null);buffer.setColors(colors);
    }

    boolean draw(DirectX7 g,Sprite sprite) {
        // MUL uses a custom saturating brightness/channel operator, not ordinary
        // destination*source. Keep it (and unknown modes) on the exact CPU path.
        if(sprite.mode!=0 && sprite.mode!=1 && sprite.mode!=3) return false;
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
        float maxU=(float)raw.w/M3GSceneRenderer.spriteDimension(raw.w);
        float maxV=(float)raw.h/M3GSceneRenderer.spriteDimension(raw.h);
        float u1=((float)x1-left)/((float)right-left),u2=((float)x2-left)/((float)right-left);
        float v1=((float)y1-top)/((float)bottom-top),v2=((float)y2-top)/((float)bottom-top);
        if(sprite.mirX) {u1=1-u1;u2=1-u2;}
        if(sprite.mirY) {v1=1-v1;v2=1-v2;}
        vertex(0,x1,y1,u1*maxU,v1*maxV,g,precision);
        vertex(1,x2,y1,u2*maxU,v1*maxV,g,precision);
        vertex(2,x2,y2,u2*maxU,v2*maxV,g,precision);
        vertex(3,x1,y2,u1*maxU,v2*maxV,g,precision);
        boolean alpha=sprite.mode==3 && raw.alphaMixing;
        int kind=sprite.mode==1?M3GSceneRenderer.SPRITE_ADD:
                alpha?M3GSceneRenderer.SPRITE_ALPHA:M3GSceneRenderer.SPRITE_CUTOUT;
        int power=sprite.fog && sprite.mode!=1 && DirectX7.fDist!=0?
                (int)Math.max(0L,Math.min(255L,-(long)sprite.pos.rz*255/DirectX7.fDist)):0;
        int fogColor=DirectX7.standartDrawmode==6?0:DirectX7.fogc&0xffffff;
        // Acquire both cached textures before submitting either pass. Uploads
        // are never performed with a bound native target.
        Texture2D image=owner.texture(g,raw,kind);
        Texture2D mask=power>0 && fogColor!=0?owner.texture(g,raw,
                alpha?M3GSceneRenderer.SPRITE_ALPHA_MASK:M3GSceneRenderer.SPRITE_MASK):null;
        positions.set(0,4,xyz);coords.set(0,4,uv);
        buffer.setPositions(positions,1.0f/precision,null);
        appearance.setFog(null);appearance.setTexture(0,image);
        image.setBlending(Texture2D.FUNC_MODULATE);
        composite.setBlending(sprite.mode==1?CompositingMode.ALPHA_ADD:
                alpha?CompositingMode.ALPHA:CompositingMode.REPLACE);
        composite.setAlphaThreshold(alpha || sprite.mode==1?0:0.5f);
        int visible=255-power;
        color((visible<<16)|(visible<<8)|visible,255);
        render(g);
        if(mask!=null) {
            // Source fog precedes alpha blending. Add the fog contribution only
            // through the same alpha mask, without attenuating destination twice.
            appearance.setTexture(0,mask);mask.setBlending(Texture2D.FUNC_MODULATE);
            composite.setBlending(CompositingMode.ALPHA_ADD);composite.setAlphaThreshold(0);
            color(fogColor,power);render(g);
        }
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
    private void render(DirectX7 g) {
        owner.bind(g);owner.restoreCamera(g);graphics.render(buffer,indices,appearance,null);
    }
}
