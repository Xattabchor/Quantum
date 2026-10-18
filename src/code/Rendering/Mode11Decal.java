package code.Rendering;

/** CPU recovery of native DECAL over the mode's opaque white vertex color.
 * No JSR-184 linkage, source mutation or per-polygon copies on a cache hit.
 * The bounded cache follows the renderer's immutable-source/release convention.
 */
final class Mode11Decal {
    private final RawImage[] originals=new RawImage[4],copies=new RawImage[4];
    private final int[][] pixels=new int[4][];
    private final Texture result=new Texture();
    private int next;

    static int pixel(int argb) {
        int a=argb>>>24,b=255-a;
        return 0xff000000 | ((((argb>>>16)&255)*a+255*b)/255<<16)
                | ((((argb>>>8)&255)*a+255*b)/255<<8) | (((argb&255)*a+255*b)/255);
    }
    private RawImage copy(RawImage source) {
        if(source==null || source.img==null) return source;
        for(int i=0;i<4;i++) if(originals[i]==source && pixels[i]==source.img
                && copies[i].w==source.w && copies[i].h==source.h && copies[i].scale==source.scale) return copies[i];
        int[] data=new int[source.img.length];
        for(int i=0;i<data.length;i++) data[i]=pixel(source.img[i]);
        RawImage image=new RawImage(data,source.w,source.h);image.scale=source.scale;
        originals[next]=source;pixels[next]=source.img;copies[next]=image;next=(next+1)%4;
        return image;
    }
    Texture texture(Texture source) {
        result.rImg=copy(source.rImg);result.drawmode=0;
        result.perspectiveCorrection=source.perspectiveCorrection;result.addsz=source.addsz;
        result.castShadow=source.castShadow;result.collision=source.collision;
        if(source.mip==null) result.mip=null;
        else {
            int count=Math.min(3,source.mip.length);
            if(result.mip==null || result.mip.length!=count) result.mip=new RawImage[count];
            for(int i=0;i<count;i++) result.mip[i]=copy(source.mip[i]);
        }
        return result;
    }
}
