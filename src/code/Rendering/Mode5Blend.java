package code.Rendering;

/** API-free configuration and allocation-free CPU equivalent of FUNC_BLEND.
 * White primary RGB: out = 255 - tex + tex * constant / 255. */
final class Mode5Blend {
    private static int color;

    static int color() {return color;}

    /** Called once per level, including levels without the optional key. */
    static void configure(String value) {
        color=0;
        if(value==null) return;
        try {
            int first=value.indexOf(','),last=value.lastIndexOf(',');
            if(first<0 || first==last) return;
            int r=Integer.parseInt(value.substring(0,first).trim());
            int g=Integer.parseInt(value.substring(first+1,last).trim());
            int b=Integer.parseInt(value.substring(last+1).trim());
            if(r<0 || r>255 || g<0 || g>255 || b<0 || b>255) return;
            color=(r<<16)|(g<<8)|b;
        } catch(RuntimeException invalid) {color=0;}
    }

    /** Call only for nonzero texels; zero remains the original cutout key. */
    static int pixel(int source,int constant) {
        int r=(source>>>16)&255,g=(source>>>8)&255,b=source&255;
        r=255-r+r*((constant>>>16)&255)/255;
        g=255-g+g*((constant>>>8)&255)/255;
        b=255-b+b*(constant&255)/255;
        return 0xff000000|(r<<16)|(g<<8)|b;
    }
}
