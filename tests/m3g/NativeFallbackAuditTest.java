package code.Rendering;

/** The audit must detect a fallback, not mistake API barriers for one. */
public final class NativeFallbackAuditTest {
    public static void main(String[] args) {
        String audit=System.getProperty("quantum.nativeAudit"),strict=System.getProperty("quantum.requireNative");
        DirectX7 g=new DirectX7(4,4);
        RenderObject object=new SolidSky(0);
        try {
            System.setProperty("quantum.nativeAudit","true");System.setProperty("quantum.requireNative","true");
            NativeFallbackAudit.begin("negative-control");
            g.setRenderer(DirectX7.SOFTWARE);NativeFallbackAudit.record(g,object,null,false);
            M3GTest.check(NativeFallbackAudit.total()==0,"Software control must not count as native fallback");
            g.setRenderer(DirectX7.M3G);NativeFallbackAudit.record(g,object,null,false);
            M3GTest.check(NativeFallbackAudit.total()==1,"CPU dispatch was not detected");
            boolean failed=false;
            try {NativeFallbackAudit.end();}catch(AssertionError expected){failed=true;}
            M3GTest.check(failed,"native-only gate silently passed a CPU fallback");
            NativeFallbackAudit.begin("positive-control");NativeFallbackAudit.end();
            System.clearProperty("quantum.nativeAudit");NativeFallbackAudit.begin("disabled-control");
            NativeFallbackAudit.record(g,object,null,false);
            M3GTest.check(NativeFallbackAudit.total()==0,"disabled audit recorded dispatches");NativeFallbackAudit.end();
        } finally {
            if(audit==null)System.clearProperty("quantum.nativeAudit");else System.setProperty("quantum.nativeAudit",audit);
            if(strict==null)System.clearProperty("quantum.requireNative");else System.setProperty("quantum.requireNative",strict);
            g.destroy();
        }
        System.out.println("PASS native fallback audit positive/negative/disabled controls / strict gate rejects CPU dispatch");
    }
}
