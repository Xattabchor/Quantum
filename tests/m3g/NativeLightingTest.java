package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Rendering.Meshes.*;
import code.utils.Main;
import java.util.Arrays;
import javax.microedition.m3g.*;

/** Native state and lighting-equation contracts, NOT a GPU rasterizer. */
public final class NativeLightingTest {
    private static void check(boolean b,String why) {M3GTest.check(b,why);}
    private static Graphics3D.Draw last() {return Graphics3D.history.lastElement();}
    static RenderObject polygon(int kind) {
        Vertex a=M3GTest.vertex(2,2,-100),b=M3GTest.vertex(25,2,-200),
                c=M3GTest.vertex(2,25,-300),d=M3GTest.vertex(25,25,-200);
        if(kind==0) return new Polygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==1) return new Polygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==2) return new LightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        if(kind==3) return new LightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
        if(kind==4) return new ColorLightedPolygon3V(a,b,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)0,(byte)128);
        return new ColorLightedPolygon4V(a,b,d,c,(byte)0,(byte)0,(byte)128,(byte)0,(byte)128,(byte)128,(byte)0,(byte)128);
    }
    // Retain the shared six-polygon fixture; old lighting is explicitly removed.
    static void contracts() {Mode5BlendTest.nativeContracts();}
    public static void main(String[] args) {contracts();}
}
