package code.Rendering;

import code.utils.Main;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Regression: replacing mode 5 must remove native weapon-light allocation/draw. */
public final class WeaponOmniTest {
    public static void main(String[] args) throws Exception {
        Main.mipMapping=false;Main.persQ=4;Main.fogQ=2;
        DirectX7.configureM3GBlend("255,128,64");
        DirectX7 g=M3GTest.renderer();Texture t=M3GTest.texture(0xff8090a0);t.drawmode=5;
        for(String failure:new String[]{"lights.fail","lights.second.fail","omni.fail","material.fail","normals.fail"}) {
            System.setProperty(failure,"true");g.startWeaponLight();g.startMuzzleGlow();
            M3GTest.submit(g,M3GTest.triangle(),t,0);g.render();System.clearProperty(failure);
            Graphics3D.Draw draw=Graphics3D.history.lastElement();
            M3GTest.check(g.isM3GActive() && draw.function==Texture2D.FUNC_BLEND && !draw.lit
                    && draw.lights==0 && draw.normals==null && g.getMode5WeaponLight()==null,"removed OMNI/Material/normals still reached "+failure);
        }
        try {Class.forName("code.Rendering.M3GMode5Lighting");throw new AssertionError("native lighting adapter still packaged");}
        catch(ClassNotFoundException expected) { }
        int calls=Graphics3D.calls,uploads=Graphics3D.uploads;g.startWeaponLight();g.renderGameFrame(null,false,false,null,0,null);
        M3GTest.check(Graphics3D.calls==calls && Graphics3D.uploads==uploads,"empty shot added native work");
        g.destroy();DirectX7.configureM3GBlend(null);
        System.out.println("PASS old native ambient/directional/OMNI adapter absent / shot hooks no-op / failing lighting APIs never called / no Material or normal upload / empty shot no draw+upload");
    }
}
