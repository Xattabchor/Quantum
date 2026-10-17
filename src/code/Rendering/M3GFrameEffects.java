package code.Rendering;

import javax.microedition.m3g.*;

/** One cached LUMINANCE mask + one MODULATE quad for all selected-M3G effects. */
final class M3GFrameEffects {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final int limit;
    private final Appearance appearance=new Appearance();
    private final VertexBuffer buffer=new VertexBuffer();
    private final VertexArray positions=new VertexArray(4,3,2);
    private final TriangleStripArray indices=new TriangleStripArray(
            new int[]{0,1,3,1,2,3},new int[]{3,3});
    private final short[] xyz=new short[12];
    private Texture2D mask,activeTexture;
    private byte[] source;
    private int width,height,cx,cy,precision,lastTint=-1;
    private int maskWidth,maskHeight;

    M3GFrameEffects(M3GSceneRenderer owner,Graphics3D graphics,int maxTexture) {
        this.owner=owner;this.graphics=graphics;
        limit=Math.min(64,maxTexture);
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        owner.registerPolygonMode(polygon);
        appearance.setPolygonMode(polygon);appearance.setFog(null);
        CompositingMode composite=new CompositingMode();
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setAlphaThreshold(0);composite.setBlending(CompositingMode.MODULATE);
        appearance.setCompositingMode(composite);
        VertexArray coords=new VertexArray(4,2,2);
        coords.set(0,4,new short[]{0,0,1,0,1,1,0,1});
        buffer.setTexCoords(0,coords,1,null);
    }

    // Called before ANY scene draw/bind: even cold mask upload adds no readback.
    void prepare(DirectX7 g,FrameEffects effects) {
        if(effects.vignette==null) return; // Keep one warm mask when temporarily disabled.
        if(source==effects.vignette && maskWidth==g.width && maskHeight==g.height) return;
        Texture2D next=null;
        if(effects.vignette!=null && limit>0) {
            int w=M3GSceneRenderer.spriteDimension(Math.min(limit,g.width));
            int h=M3GSceneRenderer.spriteDimension(Math.min(limit,g.height));
            byte[] luminance=new byte[w*h];
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                int sx=(2*x+1)*g.width/(2*w),sy=(2*y+1)*g.height/(2*h);
                int value=effects.factor(sy*g.width+sx,g.display.length);
                // Map original d/256 to the nearest native byte/255.
                luminance[y*w+x]=(byte)((value*255+128)>>8);
            }
            next=new Texture2D(owner.uploadImage(g,Image2D.LUMINANCE,w,h,luminance));
            next.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_LINEAR);
            next.setWrapping(Texture2D.WRAP_CLAMP,Texture2D.WRAP_CLAMP);
            next.setBlending(Texture2D.FUNC_MODULATE);
        }
        mask=next;source=effects.vignette;maskWidth=g.width;maskHeight=g.height;
    }

    boolean draw(DirectX7 g,FrameEffects effects) {
        if(effects.vignette!=null && mask==null) return false;
        if(width!=g.width || height!=g.height || cx!=g.centreX || cy!=g.centreY) {
            int bound=Math.max(Math.max(Math.abs(g.centreX),Math.abs(g.width-g.centreX)),
                    Math.max(Math.abs(g.centreY),Math.abs(g.height-g.centreY)));
            int p=Math.min(16,30000/Math.max(1,bound));
            if(p<1) return false;
            width=g.width;height=g.height;cx=g.centreX;cy=g.centreY;precision=p;
            vertex(0,0,0);vertex(1,width,0);vertex(2,width,height);vertex(3,0,height);
            positions.set(0,4,xyz);buffer.setPositions(positions,1.0f/precision,null);
        }
        if(lastTint!=effects.tint) {
            buffer.setDefaultColor(0xff000000|effects.tint);lastTint=effects.tint;
        }
        Texture2D desired=effects.vignette==null?null:mask;
        if(activeTexture!=desired) {appearance.setTexture(0,desired);activeTexture=desired;}
        owner.bind(g);owner.restoreCamera(g);
        owner.renderNative(g,buffer,indices,appearance);
        return true;
    }
    private void vertex(int i,int x,int y) {
        xyz[i*3]=(short)((x-cx)*precision);xyz[i*3+1]=(short)((cy-y)*precision);
        xyz[i*3+2]=(short)-precision;
    }
}
