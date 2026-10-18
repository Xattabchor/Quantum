package code.Rendering;

import java.util.Map;
import java.util.TreeMap;

/** Test-only migration gate: dispatches into the CPU rasterizer, not upload
 * barriers or CPU geometry preparation. No production hook is shipped.
 */
public final class NativeFallbackAudit {
    private static boolean active;
    private static String tag;
    private static int total;
    private static String reason;
    public static void attempt(){reason=null;}
    public static void rejected(String value){if(active)reason=value;}
    private static final Map<String,Integer> counts=new TreeMap<String,Integer>();

    public static void begin(String name) {
        active=Boolean.getBoolean("quantum.nativeAudit");tag=name;total=0;reason=null;counts.clear();
    }
    public static void record(DirectX7 g,RenderObject object,Texture texture,boolean fast) {
        if(!active || g.getRenderer()!=DirectX7.M3G)return;
        String key=object.getClass().getName()+":mode="+(texture==null?"none":String.valueOf(texture.drawmode))
            +":path="+(g.getRendererError()==null?"object":"frame-replay")+":reason="+(reason==null?"unspecified":reason);
        reason=null;
        Integer old=counts.get(key);counts.put(key,old==null?1:old+1);total++;
    }
    static int total(){return total;}
    public static void end() {
        if(!active)return;
        active=false;
        System.out.println("NATIVE_AUDIT tag="+tag+" cpuDispatches="+total+" kinds="+counts.size());
        for(Map.Entry<String,Integer> e:counts.entrySet())
            System.out.println("NATIVE_AUDIT_ITEM tag="+tag+" count="+e.getValue()+" key="+e.getKey());
        if(Boolean.getBoolean("quantum.requireNative") && total!=0)
            throw new AssertionError("M3G-only compatibility gate: "+tag+" still needs CPU rasterization: "+counts);
    }
}
