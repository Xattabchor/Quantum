package code.Rendering;

import javax.microedition.m3g.*;

/** One textured panorama band and up to two untextured sky/ground rectangles. */
final class M3GPanoramaRenderer {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final VertexBuffer buffer=new VertexBuffer();
    private final VertexArray positions=new VertexArray(4,3,2),coords=new VertexArray(4,2,2);
    private final VertexArray colors=new VertexArray(4,4,1);
    private final TriangleStripArray indices=new TriangleStripArray(
            new int[]{0,1,3,1,2,3},new int[]{3,3});
    private final Appearance appearance=new Appearance();
    private final short[] xyz=new short[12],uv=new short[8];
    private final byte[] rgba=new byte[16];
    private float u1,u2,v1,v2;

    M3GPanoramaRenderer(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setBlending(CompositingMode.REPLACE);composite.setAlphaThreshold(0);
        appearance.setCompositingMode(composite);appearance.setFog(null);
        buffer.setColors(colors);
    }

    boolean draw(DirectX7 g,PanoramaSky sky) {
        if(sky.x2<=sky.x1 || sky.y2<=sky.y1) return true;
        if(sky.x1<0 || sky.y1<0 || sky.x2>g.width || sky.y2>g.height) return false;
        int top=Math.max(sky.y1,Math.min(sky.y2,sky.startY));
        int bottom=Math.max(sky.y1,Math.min(sky.y2,sky.endY));
        if(bottom<top) return false;
        int bound=Math.max(Math.max(Math.abs(g.centreX),Math.abs(g.width-g.centreX)),
                Math.max(Math.abs(g.centreY),Math.abs(g.height-g.centreY)));
        int precision=Math.min(16,30000/Math.max(1,bound));
        if(precision<1) return false;
        Texture2D image=null;int units=16384;
        if(bottom>top) {
            RawImage raw=sky.texture.rImg;
            // Keep original resolution: unsupported images use the old sampler,
            // never the sprite downsampling path or a silently reduced panorama.
            if(!owner.supportsTexture(raw) || raw.img==null || (long)raw.w*raw.h>raw.img.length) return false;
            // At pixel centers these UVs match the original 12-bit fixed-point
            // origin/step. Remove whole repeats BEFORE float conversion, so yaw
            // outside 0..360 remains stable and fits the short UV arrays.
            u1=phase(2L*sky.uStart+(2L*sky.x1-1)*sky.du,raw.w);
            u2=u1+(float)((long)sky.du*(sky.x2-sky.x1))/(raw.w*4096.0f);
            v1=phase((2L*top-2L*sky.startY-1)*sky.dv,raw.h);
            v2=v1+(float)((long)sky.dv*(bottom-top))/(raw.h*4096.0f);
            float max=Math.max(1,Math.max(Math.max(Math.abs(u1),Math.abs(u2)),Math.max(Math.abs(v1),Math.abs(v2))));
            units=Math.min(16384,(int)(30000/max));
            // Do not replace extreme repeat ranges with visibly unstable UVs.
            if(units<Math.max(raw.w,raw.h)*4) return false;
            image=owner.texture(g,raw,M3GSceneRenderer.PANORAMA_TEXTURE);
            image.setBlending(Texture2D.FUNC_MODULATE);
        }
        // A full opaque sky overwrites all old pixels; partial portal/crop
        // rectangles must preserve everything outside their original viewport.
        owner.bind(g,!(sky.x1==0 && sky.y1==0 && sky.x2==g.width && sky.y2==g.height));
        owner.restoreCamera(g);
        buffer.setTexCoords(0,coords,1.0f/units,null);
        if(top>sky.y1) quad(g,sky.x1,sky.y1,sky.x2,top,precision,null,sky.color,units);
        if(bottom>top) quad(g,sky.x1,top,sky.x2,bottom,precision,image,0xffffff,units);
        if(sky.y2>bottom) quad(g,sky.x1,bottom,sky.x2,sky.y2,precision,null,sky.groundColor,units);
        return true;
    }

    private static float phase(long value,int size) {
        long period=(long)size*8192;
        long result=value%period;if(result<0) result+=period;
        return (float)result/period;
    }
    private void quad(DirectX7 g,int x1,int y1,int x2,int y2,int precision,
            Texture2D image,int color,int units) {
        vertex(0,x1,y1,g,precision);vertex(1,x2,y1,g,precision);
        vertex(2,x2,y2,g,precision);vertex(3,x1,y2,g,precision);
        for(int i=0;i<4;i++) {
            rgba[i*4]=(byte)(color>>16);rgba[i*4+1]=(byte)(color>>8);
            rgba[i*4+2]=(byte)color;rgba[i*4+3]=(byte)255;
        }
        if(image!=null) {
            uv[0]=uv[6]=pack(u1,units);uv[2]=uv[4]=pack(u2,units);
            uv[1]=uv[3]=pack(v1,units);uv[5]=uv[7]=pack(v2,units);
        }
        positions.set(0,4,xyz);coords.set(0,4,uv);colors.set(0,4,rgba);
        buffer.setPositions(positions,1.0f/precision,null);appearance.setTexture(0,image);
        graphics.render(buffer,indices,appearance,null);
    }
    private static short pack(float value,int units) {
        float v=value*units;return (short)(v<0?v-0.5f:v+0.5f);
    }
    private void vertex(int i,int x,int y,DirectX7 g,int precision) {
        xyz[i*3]=(short)((x-g.centreX)*precision);xyz[i*3+1]=(short)((g.centreY-y)*precision);
        xyz[i*3+2]=(short)-precision;
    }
}
