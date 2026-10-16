package code.Rendering;

/** Builds a four-vertex strip; no per-segment allocation or pixel rasterization. */
public final class ScreenLineGeometry {
    public final float[] xy = new float[8];
    private float ax,ay,bx,by;

    public boolean build(ScreenLines lines,int i,int width,int height) {
        ax=lines.get(i,0); ay=lines.get(i,1); bx=lines.get(i,2); by=lines.get(i,3);
        int kind=lines.get(i,6);
        if(kind == ScreenLines.RECT) {
            ax=Math.max(0,ax); ay=Math.max(0,ay); bx=Math.min(width,bx); by=Math.min(height,by);
            if(bx<=ax || by<=ay) return false;
            set(ax,ay,bx,ay,ax,by,bx,by); return true;
        }
        float fat=Math.max(1,lines.get(i,4));
        if(kind == ScreenLines.LEGACY_LINE) {
            // The legacy lightning thickness is horizontal, not perpendicular.
            fat=Math.max(1,2*(lines.get(i,4)/2));
            if(ay==by) return false; // Original drawLine does not draw horizontal strokes.
            if(!clip(width,height,fat)) return false;
            set(ax-fat/2,ay,ax+fat/2,ay,bx-fat/2,by,bx+fat/2,by); return true;
        }
        if(!clip(width,height,fat+1)) return false;
        float dx=bx-ax,dy=by-ay;
        float length=(float)Math.sqrt(dx*dx+dy*dy);
        if(length<0.00001f) {
            set(ax,ay,ax+fat,ay,ax,ay+fat,ax+fat,ay+fat);return true;
        }
        // LCDUI rain endpoints refer to pixel centers, including the last pixel.
        float ux=dx/length,uy=dy/length,nx=-uy*fat/2,ny=ux*fat/2;
        ax+=0.5f-ux/2; ay+=0.5f-uy/2; bx+=0.5f+ux/2; by+=0.5f+uy/2;
        set(ax+nx,ay+ny,ax-nx,ay-ny,bx+nx,by+ny,bx-nx,by-ny);return true;
    }
    private boolean clip(int width,int height,float pad) {
        // Cohen-Sutherland; clipped centers leave enough room for the strip.
        for(int step=0;step<8;step++) {
            int a=code(ax,ay,width,height,pad),b=code(bx,by,width,height,pad);
            if((a|b)==0) return true;
            if((a&b)!=0) return false;
            int c=a!=0?a:b;float x,y;
            if((c&8)!=0) { y=height+pad;x=ax+(bx-ax)*(y-ay)/(by-ay); }
            else if((c&4)!=0) { y=-pad;x=ax+(bx-ax)*(y-ay)/(by-ay); }
            else if((c&2)!=0) { x=width+pad;y=ay+(by-ay)*(x-ax)/(bx-ax); }
            else { x=-pad;y=ay+(by-ay)*(x-ax)/(bx-ax); }
            if(c==a) {ax=x;ay=y;} else {bx=x;by=y;}
        }
        return false;
    }
    private static int code(float x,float y,int w,int h,float pad) {
        return (x < -pad?1:0)|(x > w+pad?2:0)|(y < -pad?4:0)|(y > h+pad?8:0);
    }
    private void set(float a,float b,float c,float d,float e,float f,float g,float h) {
        xy[0]=a;xy[1]=b;xy[2]=c;xy[3]=d;xy[4]=e;xy[5]=f;xy[6]=g;xy[7]=h;
    }
}
