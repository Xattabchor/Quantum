package code.Rendering;

import javax.microedition.lcdui.Image;

/** Ordered Canvas foreground commands; no CPU rasterizer or JSR-184 dependency.
 * Coordinates and clips are absolute, untranslated Canvas pixels. */
public final class ScreenImages {
    public static final int IMAGE=0, RECT=1;
    private static final int STRIDE=12, MAX_COMMANDS=256;
    private Image[] images=new Image[16];
    private int[] data=new int[16*STRIDE];
    public int count,width,height;
    private int left,top,right,bottom,color=0xffffff;

    public void reset(int width,int height) {
        if(width<=0 || height<=0) throw new IllegalArgumentException("canvas size");
        clear();this.width=width;this.height=height;
        setClip(0,0,width,height);
    }
    public void clear() {
        for(int i=0;i<count;i++) images[i]=null;
        count=0;
    }
    public void setClip(int x,int y,int w,int h) {
        left=Math.max(0,Math.min(width,x));top=Math.max(0,Math.min(height,y));
        right=(int)Math.max(left,Math.min((long)width,(long)x+Math.max(0,w)));
        bottom=(int)Math.max(top,Math.min((long)height,(long)y+Math.max(0,h)));
    }
    public void setColor(int rgb) {color=rgb&0xffffff;}
    public Image image(int i) {return images[i];}
    public int get(int i,int field) {return data[i*STRIDE+field];}

    public void drawImage(Image image,int x,int y,int anchor) {
        if(image==null) throw new NullPointerException("foreground image");
        drawRegion(image,0,0,image.getWidth(),image.getHeight(),0,x,y,anchor);
    }
    public void drawRegion(Image image,int sx,int sy,int w,int h,int transform,int x,int y,int anchor) {
        if(image==null) throw new NullPointerException("foreground image");
        if(sx<0 || sy<0 || w<=0 || h<=0 || (long)sx+w>image.getWidth() || (long)sy+h>image.getHeight())
            throw new IllegalArgumentException("foreground source region");
        if(transform!=0 && transform!=2) throw new IllegalArgumentException("foreground transform");
        // MIDP drawImage anchor=0 is TOP|LEFT. Other supported anchors are exact.
        if(anchor==0) anchor=20;
        int horizontal=anchor&13,vertical=anchor&50;
        if((anchor&~63)!=0 || (horizontal!=1 && horizontal!=4 && horizontal!=8)
                || (vertical!=2 && vertical!=16 && vertical!=32))
            throw new IllegalArgumentException("foreground anchor");
        long dx=(long)x-(horizontal==1?w/2:horizontal==8?w:0);
        long dy=(long)y-(vertical==2?h/2:vertical==32?h:0);
        if(dx+w<=left || dy+h<=top || dx>=right || dy>=bottom) return;
        // An intersecting positive-sized image is necessarily in int range.
        add(image,IMAGE,(int)dx,(int)dy,w,h,sx,sy,transform);
    }
    public void fillRect(int x,int y,int w,int h) {
        fillClippedRect(x,y,w,h);
    }
    /** MIDP outlines include both end pixels, unlike fillRect's half-open box. */
    public void drawRect(int x,int y,int w,int h) {
        if(w<0 || h<0) return;
        fillClippedRect(x,y,(long)w+1,1);
        if(h>0) fillClippedRect(x,(long)y+h,(long)w+1,1);
        if(h>1) {
            fillClippedRect(x,(long)y+1,1,(long)h-1);
            if(w>0) fillClippedRect((long)x+w,(long)y+1,1,(long)h-1);
        }
    }
    private void fillClippedRect(long x,long y,long w,long h) {
        if(w<=0 || h<=0) return;
        int l=(int)Math.max(left,Math.min(right,x)),t=(int)Math.max(top,Math.min(bottom,y));
        int r=(int)Math.max(l,Math.min(right,x+w)),b=(int)Math.max(t,Math.min(bottom,y+h));
        if(l<r && t<b) add(null,RECT,l,t,r-l,b-t,0,0,color);
    }
    private void add(Image image,int kind,int x,int y,int w,int h,int sx,int sy,int extra) {
        if((long)x+w<=left || (long)y+h<=top || x>=right || y>=bottom || left==right || top==bottom) return;
        if(count==MAX_COMMANDS) throw new IllegalStateException("foreground command limit");
        if(count==images.length) {
            int n=Math.min(MAX_COMMANDS,images.length*2);
            Image[] next=new Image[n];int[] values=new int[n*STRIDE];
            System.arraycopy(images,0,next,0,count);System.arraycopy(data,0,values,0,count*STRIDE);
            images=next;data=values;
        }
        int p=count*STRIDE;images[count++]=image;
        data[p]=kind;data[p+1]=x;data[p+2]=y;data[p+3]=w;data[p+4]=h;
        data[p+5]=sx;data[p+6]=sy;data[p+7]=extra;
        data[p+8]=left;data[p+9]=top;data[p+10]=right;data[p+11]=bottom;
    }
}
