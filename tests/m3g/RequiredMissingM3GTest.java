package code.Rendering;

/** Run only with JSR-184 physically absent from the runtime classpath. */
public final class RequiredMissingM3GTest {
    public static void main(String[] args) {
        DirectX7 g=new DirectX7(32,32);g.requireM3G();
        M3GTest.Marker marker=new M3GTest.Marker(false);
        M3GTest.submit(g,marker,null,0);
        boolean failed=false;
        try {g.render();}catch(IllegalStateException expected){failed=true;}
        M3GTest.check(failed && marker.calls==0 && g.getRendererError()!=null,
                "missing JSR-184 silently ran Software");
        M3GTest.check(g.getRenderer()==DirectX7.M3G && g.isM3GRequired(),"missing API changed policy");
        g.retryM3G();failed=false;
        // Even an empty frame must diagnose unavailable required capabilities.
        try {g.render();}catch(IllegalStateException expected){failed=true;}
        M3GTest.check(failed && g.getRendererError()!=null,"empty required frame ignored missing API");
        g.destroy();
        System.out.println("PASS missing JSR-184 -> explicit required-M3G failure / zero CPU object calls / empty frame / explicit retry");
    }
}
