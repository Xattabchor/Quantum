package code.Rendering;

import code.Gameplay.Map.LightMapper;
import code.Math.MathUtils;
import code.Rendering.Meshes.*;
import code.utils.Main;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Instrumented M3G contract/equation tests, not rendered-image comparisons. */
public final class FogTest {
    private static void check(boolean ok, String message) { M3GTest.check(ok, message); }
    private static Graphics3D.Draw last() { return Graphics3D.history.lastElement(); }
    private static Graphics3D.Draw draw(int index) { return Graphics3D.history.elementAt(index); }
    private static DirectX7 start() {
        Main.fogQ = 2; Main.persQ = 4; Main.mipMapping = false; Main.forceLQFog = false;
        LightMapper.cameraVectorLight = false;
        DirectX7 g = M3GTest.renderer();
        DirectX7.fDist = 1000; DirectX7.fogc = 0x326496;
        return g;
    }
    private static void submit(DirectX7 g, RenderObject p, Texture t) {
        M3GTest.submit(g, p, t, 0); g.render();
        check(g.isM3GActive(), "Fog unexpectedly disabled native backend: " + g.getRendererError());
    }
    private static Texture texture(int mode, int rgb) {
        Texture t = M3GTest.texture(rgb); t.drawmode = (byte)mode; return t;
    }
    private static Polygon3V flat(int depth) {
        Polygon3V p = M3GTest.triangle(); p.a.rz = p.b.rz = p.c.rz = -depth; return p;
    }
    private static int mix(int rgb, int fog, int amount) {
        int out = 0;
        for(int shift = 0; shift <= 16; shift += 8)
            out |= ((((rgb >> shift) & 255) * (255 - amount) + ((fog >> shift) & 255) * amount) / 255) << shift;
        return out;
    }
    private static void colorNear(int actual, int expected, int tolerance, String message) {
        for(int shift = 0; shift <= 16; shift += 8)
            check(Math.abs(((actual >> shift) & 255) - ((expected >> shift) & 255)) <= tolerance,
                    message + " channel " + shift + ": " + Integer.toHexString(actual) + " != " + Integer.toHexString(expected));
    }
    private static Polygon4V quad() {
        return new Polygon4V(M3GTest.vertex(2,2,-100), M3GTest.vertex(10,2,-200),
                M3GTest.vertex(10,10,-900), M3GTest.vertex(2,10,-400),
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    }

    public static void main(String[] args) {
        DirectX7 g = start();
        Texture t = texture(3, 0xffd06020);
        for(int depth : new int[]{0,100,500,1000,2000}) {
            int calls = Graphics3D.calls;
            submit(g, flat(depth), t);
            check(Graphics3D.calls == calls + 1, "HQ perspective fog should be one native pass");
            check(last().fog && last().fogColor == DirectX7.fogc, "M3G Fog not attached");
            check(last().near == 1 && Math.abs(last().far - (1 + 1000.0f / TexturingPers.move)) < 0.001f,
                    "Fog distance uses raw rather than reconstructed camera units");
            colorNear(g.display[0], mix(0xd06020, DirectX7.fogc, Math.min(255, depth * 255 / 1000)), 2,
                    "Native fog progression");
        }
        g.updateFov(40);
        submit(g, flat(500), t);
        colorNear(g.display[0], mix(0xd06020, DirectX7.fogc, 127), 2, "Zoom changed fog amount");
        DirectX7.fDist = 200; DirectX7.fogc = 0x002244;
        submit(g, flat(100), t);
        check(last().fogColor == 0x002244, "Level fog color not refreshed");
        colorNear(g.display[0], mix(0xd06020, 0x002244, 127), 2, "Level fog distance not refreshed");
        int calls = Graphics3D.calls;
        Main.fogQ = 0;
        submit(g, flat(100), t);
        check(!last().fog && Graphics3D.calls == calls + 1, "Fog OFF must be unmodified single pass");
        colorNear(g.display[0], 0xd06020, 0, "Fog OFF tint leaked");
        g.destroy();
        System.out.println("PASS native linear Fog / distance / color / zoom / OFF");

        g = start(); Main.persQ = 0;
        int first = Graphics3D.history.size();
        t = texture(3, 0xffd06020);
        submit(g, M3GTest.triangle(), t);
        check(Graphics3D.history.size() == first + 2, "Affine HQ colored fog must use two M3G passes");
        check(!draw(first).fog && !last().fog, "Artificial affine Z must not be used by Fog");
        check((draw(first).colors[0] & 255) == 230 && (draw(first).colors[8] & 255) == 179,
                "HQ per-vertex visibility");
        check(last().blend == CompositingMode.ALPHA_ADD && (last().colors[3] & 255) == 25
                && (last().colors[11] & 255) == 76, "Fog alpha/additive blend");
        colorNear(g.display[0], mix(0xd06020, DirectX7.fogc, 25), 2, "Fog applied texture attenuation twice");
        // Fog mask must remain white/binary and independent of the current fog RGB.
        int uploads = Graphics3D.uploads;
        DirectX7.fogc = 0x123456; submit(g, M3GTest.triangle(), t);
        check(Graphics3D.uploads == uploads, "Fog color change rebuilt texture pixels");
        check((last().colors[0] & 255) == 0x12 && (last().colors[1] & 255) == 0x34,
                "Overlay fog color not refreshed");
        for(int mode : new int[]{0,4,9}) {
            submit(g, M3GTest.triangle(), texture(mode, 0xff445566));
            check(!last().fog && last().blend == CompositingMode.REPLACE
                    && last().function == Texture2D.FUNC_MODULATE && (last().colors[3] & 255) == 255,
                    "Fog state leaked into mode " + mode);
        }
        t = texture(3, 0x80445566); submit(g, M3GTest.triangle(), t);
        t.drawmode = 4; submit(g, M3GTest.triangle(), t);
        check(!last().fog && last().blend == CompositingMode.ALPHA && last().textureAlpha == 128,
                "Fog mask/opaque texture cache leaked into alpha material");
        g.destroy();
        System.out.println("PASS affine HQ M3G fog / blend equation / mask caching / state reset");

        for(boolean perspective : new boolean[]{false,true}) {
            g = start(); if(!perspective) Main.persQ = 0;
            for(int mode : new int[]{1,3,6}) {
                g.clearDisplay(0xffabcdef);
                submit(g, flat(500), texture(mode, 0));
                check(g.display[0] == 0xffabcdef, "Fog filled a transparent texture hole in mode " + mode);
            }
            g.clearDisplay(0xffabcdef);
            submit(g, flat(500), texture(3, 0x80d06020));
            colorNear(g.display[0], mix(0xd06020, DirectX7.fogc, 127), 2,
                    "Mode 3 must preserve legacy nonzero/opaque texture semantics");
            g.destroy();
        }
        System.out.println("PASS texture cutouts / no full-screen fog veil / legacy fractional alpha");

        g = start(); Main.fogQ = 1; DirectX7.fDist = 2550;
        first = Graphics3D.history.size(); submit(g, quad(), texture(3, 0xffd06020));
        check(Graphics3D.history.size() == first + 4, "LQ quad needs per-triangle fog passes");
        check((draw(first).colors[0] & 255) == 215 && (draw(first+1).colors[3] & 255) == 40,
                "ABD flat fog must use depth 400 / legacy divisor 10");
        check((draw(first+2).colors[0] & 255) == 165 && (draw(first+3).colors[3] & 255) == 90,
                "BCD flat fog must use depth 900 / legacy divisor 10");
        int[] abd = {0,1,3}, bcd = {1,2,3};
        for(int i=0;i<3;i++) check(draw(first).indices[i] == abd[i] && draw(first+2).indices[i] == bcd[i], "LQ quad diagonal");
        Polygon4V alternate=quad(); alternate.b.rz=-900;alternate.c.rz=-200;
        first=Graphics3D.history.size(); submit(g,alternate,texture(3,0xffd06020));
        check(Graphics3D.history.size()==first+4,"alternate LQ quad pass count");
        int[] abc={0,1,2},acd={0,2,3};
        for(int i=0;i<3;i++) check(draw(first).indices[i]==abc[i] && draw(first+2).indices[i]==acd[i],"ABC/ACD fog diagonal");
        check((draw(first).colors[0]&255)==165 && (draw(first+2).colors[0]&255)==215,"alternate diagonal fog depths");
        // Ordinary materials and subclasses retain ABD/BCD even for these depths.
        submit(g,alternate,texture(0,0xffd06020));
        int[] baseIndices={0,1,3,1,2,3};
        for(int i=0;i<6;i++) check(last().indices[i]==baseIndices[i],"alternate diagonal leaked to plain material");
        LightedPolygon4V litQuad=new LightedPolygon4V(alternate.a,alternate.b,alternate.c,alternate.d,
                (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
        first=Graphics3D.history.size();submit(g,litQuad,texture(3,0xffd06020));
        for(int i=0;i<3;i++) check(draw(first).indices[i]==abd[i] && draw(first+2).indices[i]==bcd[i],"lit subclass diagonal changed");
        Main.fogQ=2;
        for(int mode:new int[]{3,6}) {
            submit(g,alternate,texture(mode,0xffd06020));
            int[] altIndices={0,1,2,0,2,3};
            check(last().fog,"alternate HQ diagonal lost native fog");
            for(int i=0;i<6;i++) check(last().indices[i]==altIndices[i],"HQ fog alternate diagonal");
        }
        Main.fogQ = 2; Main.forceLQFog = true;
        first = Graphics3D.history.size(); submit(g, quad(), texture(3, 0xffd06020));
        check(Graphics3D.history.size() == first + 4 && !last().fog, "LQFOG flag ignored");
        Main.fogQ = 1; DirectX7.fDist = 100;
        submit(g, M3GTest.triangle(), texture(3, 0xffd06020));
        check((last().colors[3] & 255) == 0, "Legacy zero integer divisor must not become division by zero");
        g.destroy();
        System.out.println("PASS LQ fog / separate quad depths / LQFOG / small positive distances");

        g = start(); t = texture(6, 0xffd06020);
        submit(g, flat(500), t);
        check(last().fog && last().fogColor == 0, "Black distance fog must not use colored FOGC");
        colorNear(g.display[0], mix(0xd06020,0,127),2,"Black native fog");
        Main.persQ = 0; submit(g, M3GTest.triangle(), t);
        check(!last().fog && (last().colors[0] & 255) == 230 && (last().colors[8] & 255) == 179,
                "Affine black fog vertex visibility");
        Main.forceLQFog = true; submit(g, M3GTest.triangle(), t);
        check((last().colors[0] & 255) == 230, "Affine mode 6 should retain its HQ behavior");
        Main.forceLQFog = false; Main.persQ = 4; LightMapper.cameraVectorLight = true;
        Polygon3V p = M3GTest.triangle(); submit(g, p, t);
        int expected = 230 * MathUtils.calcLight(p.nx,p.ny,p.nz,
                p.a.x-g.camera.m03,p.a.y-g.camera.m13,p.a.z-g.camera.m23) / 255;
        check(!last().fog && (last().colors[0] & 255) == Math.max(0,Math.min(255,expected)),
                "Camera-vector black fog must not discard lighting");
        LightMapper.cameraVectorLight = false; Main.fogQ = 1; DirectX7.fDist = 2550;
        first = Graphics3D.history.size(); submit(g, quad(), t);
        check(Graphics3D.history.size() == first + 2 && (draw(first).colors[0]&255) == 215
                && (last().colors[0]&255) == 165, "LQ black fog quad factors");
        g.destroy();
        System.out.println("PASS black fog / affine / camera-vector light / LQ quad");

        g = start(); DirectX7.fDist = 2550; DirectX7.fogc = 0xe0a0ff;
        t = texture(1, 0xfff030f0);
        submit(g, flat(2000), t);
        check(!last().fog && last().function == Texture2D.FUNC_ADD, "ADD fog is not conventional Fog.LINEAR");
        check((last().colors[0]&255) == 168 && (last().colors[1]&255) == 104
                && (last().colors[2]&255) == 200, "Legacy saturated ADD fog amount");
        colorNear(g.display[0],0xff98ff,0,"Saturated native ADD fog");
        t.drawmode = 0; submit(g, flat(2000), t);
        check(last().function == Texture2D.FUNC_MODULATE, "Cached texture ADD function leaked");
        colorNear(g.display[0],0xf030f0,0,"ADD tint leaked");
        Main.fogQ = 0; t.drawmode = 1; submit(g, flat(2000), t);
        check(last().function == Texture2D.FUNC_MODULATE && !last().fog,"ADD fog OFF");
        g.destroy();
        System.out.println("PASS legacy ADD fog / saturation / texture-function reset");

        // Two-pass fog participates in the existing transactional frame replay.
        g = start(); Main.persQ = 0;
        DirectX7 cpu = new DirectX7(32,32); cpu.clearDisplay(0x123456);
        Texture cpuTexture = texture(3,0xffd06020);
        M3GTest.submit(cpu,M3GTest.triangle(),cpuTexture,0); cpu.render();
        Graphics3D.failAt = Graphics3D.calls + 2;
        M3GTest.submit(g,M3GTest.triangle(),texture(3,0xffd06020),0);g.render();Graphics3D.failAt = 0;
        check(!g.isM3GActive() && Graphics3D.target == null,"Fog second-pass failure did not release target");
        for(int i=0;i<g.display.length;i++) check(g.display[i] == cpu.display[i],"Fog rollback mismatch " + i);
        g.destroy();
        g = start(); Main.persQ = 0;
        Main.mipMapping = false;
        p = M3GTest.triangle(); t = texture(3,0xffd06020);
        M3GSceneRenderer backend = new M3GSceneRenderer();
        for(int distance : new int[]{-1,0,1}) {
            DirectX7.fDist = distance;
            check(!backend.draw(g,p,t,false),"Degenerate fog range must use CPU");
        }
        backend.dispose();g.destroy();
        System.out.println("PASS fog overlay failure / full frame rollback / degenerate range fallback");
    }
}
