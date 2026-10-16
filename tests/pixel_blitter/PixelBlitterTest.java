import code.Rendering.PixelBlitter;
import javax.microedition.lcdui.Graphics;

public final class PixelBlitterTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        boolean nativeExpected = args[0].equals("direct");
        if (args[0].equals("oom")) {
            Graphics g = new Graphics();
            try {
                PixelBlitter.drawRGB(g, new int[4], 0, 2, 0, 0, 2, 2, false);
                throw new AssertionError("OutOfMemoryError was swallowed");
            } catch (OutOfMemoryError expected) {
                check(g.midpCalls == 0, "OOM must not trigger a fallback draw");
            }
            System.out.println("PASS oom propagation");
            return;
        }

        int[] pixels = {0, 0xff123456, 0x80ff0000, 0x0000ff00,
                0xffffffff, 0xffaabbcc, 0x400000ff, 0xff00ff00,
                0x00ffffff, 0xff010203, 0xffabcdef, 0};
        int[] original = (int[]) pixels.clone();
        for (int pass = 0; pass < 4; pass++) {
            // New context each time, including a different translation/clip.
            Graphics actual = new Graphics();
            Graphics reference = new Graphics();
            actual.translateX = reference.translateX = pass - 1;
            actual.clipY = reference.clipY = pass;
            boolean alpha = (pass & 1) != 0;
            int stride = pass < 2 ? 4 : -4;
            int offset = pass < 2 ? 1 : 5;
            PixelBlitter.drawRGB(actual, pixels, offset, stride, 1, 0, 3, 2, alpha);
            reference.drawRGB(pixels, offset, stride, 1, 0, 3, 2, alpha);
            for (int i = 0; i < actual.surface.length; i++) {
                check(actual.surface[i] == reference.surface[i], "Pixel mismatch " + i);
            }
            check(actual.directCalls == (nativeExpected ? 1 : 0), "Wrong native call count");
            check(actual.midpCalls == (nativeExpected ? 0 : 1), "Wrong MIDP call count");
            check(actual.lastPixels == pixels, "Buffer was copied");
            check(actual.lastOffset == offset && actual.lastStride == stride, "Buffer layout changed");
            check(actual.lastX == 1 && actual.lastY == 0, "Coordinates changed");
            check(actual.lastWidth == 3 && actual.lastHeight == 2, "Size changed");
            check(actual.lastAlpha == alpha, "Alpha flag changed");
        }
        for (int i = 0; i < pixels.length; i++) check(pixels[i] == original[i], "Input mutated");

        if (nativeExpected) {
            Graphics unsupported = new Graphics();
            unsupported.supportsDirect = false;
            PixelBlitter.drawRGB(unsupported, pixels, 0, 4, 0, 0, 4, 3, false);
            check(unsupported.midpCalls == 1, "Null wrapper must fall back");
            Graphics next = new Graphics();
            PixelBlitter.drawRGB(next, pixels, 0, 4, 0, 0, 4, 3, false);
            check(next.directCalls == 1, "Unsupported context disabled other contexts");
        }
        System.out.println("PASS " + args[0]);
    }
}
