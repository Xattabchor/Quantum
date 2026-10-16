import code.Rendering.DirectX7;
import code.HUD.DeveloperMenu;
import javax.microedition.lcdui.Graphics;

/** Tests the actual DirectX7.flush against the original MIDP drawRGB contract. */
public final class FramebufferTest {
    private static void check(boolean ok, String message) {
        if(!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        DirectX7 g3d = new DirectX7(3,2);
        check(g3d.getRenderer() == DirectX7.SOFTWARE, "Software must remain the default");
        int[] pixels = {0,0xff123456,0x80ff0000,0x0000ff00,0xffffffff,0x400000ff};
        int[] original = (int[])pixels.clone();
        System.arraycopy(pixels,0,g3d.display,0,pixels.length);
        int[] buffer = g3d.getDisplay();
        for(int pass = 0; pass < 4; pass++) {
            Graphics actual = new Graphics(), reference = new Graphics();
            actual.translateX = reference.translateX = pass - 1;
            actual.clipY = reference.clipY = pass;
            g3d.flushAlpha = (pass & 1) != 0;
            g3d.flush(actual,1,0);
            reference.drawRGB(pixels,0,3,1,0,3,2,g3d.flushAlpha);
            check(actual.midpCalls == 1,"Must call MIDP drawRGB exactly once");
            check(actual.lastPixels == buffer,"Buffer was copied");
            check(actual.lastOffset == 0 && actual.lastStride == 3,"Buffer layout changed");
            check(actual.lastWidth == 3 && actual.lastHeight == 2,"Size changed");
            check(actual.lastX == 1 && actual.lastY == 0,"Coordinates changed");
            check(actual.lastAlpha == g3d.flushAlpha,"Alpha flag changed");
            for(int i=0;i<actual.surface.length;i++) check(actual.surface[i]==reference.surface[i],"Pixel mismatch "+i);
            for(int i=0;i<pixels.length;i++) check(buffer[i]==original[i],"Framebuffer was modified");
        }
        check(g3d.getDisplay()==buffer,"getDisplay no longer returns the original array");
        g3d.resize(5,3);
        Graphics resized = new Graphics();
        g3d.flush(resized,-1,1);
        check(resized.lastPixels==g3d.getDisplay() && resized.lastStride==5
                && resized.lastWidth==5 && resized.lastHeight==3,"Resize flush");
        g3d.resize(3,2);
        for(int i=0;i<6;i++)g3d.display[i]=i;
        DeveloperMenu.renderPolygonsOverwrite=true;
        try {
            Graphics debug = new Graphics();
            g3d.flush(debug,0,0);
            int[] expected = {0xffffff,0x00ff00,0xffff00,0xff8800,0xff0000,0x880000};
            for(int i=0;i<6;i++)check(g3d.display[i]==expected[i],"Original overwrite visualization");
            check(debug.midpCalls==1,"Debug flush bypassed drawRGB");
        } finally {DeveloperMenu.renderPolygonsOverwrite=false;}
        g3d.destroy();
        System.out.println("PASS original MIDP flush / RGB+ARGB / translation+clip / buffer identity / resize / debug");
    }
}
