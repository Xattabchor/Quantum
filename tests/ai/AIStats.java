package code.AI;

/** Test-only work counters/fake clock; never packaged in the MIDlet. */
public final class AIStats {
    public static long scans,reads,distances,vectors,normalizations,sqrts,rays,routes,blocked;
    public static long now=1000000;
    public static long now(){return now;}
    public static boolean visibility(boolean visible){if(!visible)blocked++;return visible;}
    public static double sqrt(double n){sqrts++;return Math.sqrt(n);}
    public static void reset(){scans=reads=distances=vectors=normalizations=sqrts=rays=routes=blocked=0;}
    public static String json(){return "{\"scans\":"+scans+",\"reads\":"+reads+",\"distances\":"+distances
            +",\"vectors\":"+vectors+",\"vectorSetLength\":"+normalizations+",\"sqrts\":"+sqrts
            +",\"rays\":"+rays+",\"routes\":"+routes+"}";}
}
