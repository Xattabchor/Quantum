package code.Rendering;

import javax.microedition.m3g.*;

/** Two independent untextured gradient strips in one native call. */
final class M3GStormFlash {
    private final M3GSceneRenderer owner;
    private final Graphics3D graphics;
    private final VertexBuffer buffer=new VertexBuffer();
    private final VertexArray positions=new VertexArray(8,3,2);
    private final VertexArray colors=new VertexArray(8,4,1);
    private final short[] packed=new short[24];
    private final byte[] rgb=new byte[32];
    private final TriangleStripArray indices=new TriangleStripArray(
            new int[]{0,1,2,3,4,5,6,7},new int[]{4,4});
    private final Appearance appearance=new Appearance();

    M3GStormFlash(M3GSceneRenderer owner,Graphics3D graphics) {
        this.owner=owner;this.graphics=graphics;
        PolygonMode polygon=new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);polygon.setShading(PolygonMode.SHADE_SMOOTH);
        appearance.setPolygonMode(polygon);
        CompositingMode composite=new CompositingMode();
        composite.setDepthTestEnable(false);composite.setDepthWriteEnable(false);
        composite.setBlending(CompositingMode.ALPHA_ADD);composite.setAlphaThreshold(0);
        appearance.setCompositingMode(composite);appearance.setFog(null);
        buffer.setColors(colors);
    }

    boolean draw(DirectX7 g,StormFlash flash) {
        if(flash.size<=0) return false;
        if(flash.top>=g.height) return true;
        int bound=Math.max(g.width,g.height)+Math.max(Math.abs(g.centreX),Math.abs(g.centreY))+8;
        int precision=Math.min(16,30000/Math.max(1,bound));
        if(precision<1) return false;
        // Rise from the horizon, then stop at the original 100/255 brightness.
        float knee=flash.horizon+flash.size*(100.0f/255);
        float middle=Math.max(flash.top,Math.min(g.height,knee));
        int first=amount(flash.top,flash),last=middle>=knee?100:amount(middle,flash);
        strip(g,0,flash.top,middle,first,last,precision);
        strip(g,4,middle,g.height,last,100,precision);
        positions.set(0,8,packed);colors.set(0,8,rgb);
        buffer.setPositions(positions,1.0f/precision,null);
        owner.bind(g);owner.restoreCamera(g);
        graphics.render(buffer,indices,appearance,null);
        return true;
    }

    private static int amount(float y,StormFlash flash) {
        return Math.min(100,Math.max(0,(int)((y-flash.horizon)*255/flash.size))) & 0xfe;
    }
    private void strip(DirectX7 g,int start,float top,float bottom,int a,int b,int precision) {
        for(int v=0;v<4;v++) {
            int p=(start+v)*3,c=(start+v)*4;
            packed[p]=(short)(((v%2==0?0:g.width)-g.centreX)*precision);
            packed[p+1]=round((g.centreY-(v<2?top:bottom))*precision);
            packed[p+2]=(short)-precision;
            byte color=(byte)(v<2?a:b);
            rgb[c]=rgb[c+1]=rgb[c+2]=color;rgb[c+3]=(byte)255;
        }
    }
    private static short round(float value) {
        return (short)(int)(value+(value<0?-0.5f:0.5f));
    }
}
