package code.HUD;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

/**
 * Plain LCDUI screen for a failed startup. Uses only java.lang + LCDUI so it
 * still works when resources, the custom font, M3G or a vendor API is absent.
 *
 * Why it exists: on a real handset an exception in MIDlet.startApp() leaves a
 * blank screen (or nothing at all), which cannot be diagnosed from a distance.
 * This screen prints the exception class/message and the first stack frames.
 */
public final class StartupErrorScreen extends Canvas {

    private final MIDlet midlet;
    private final String title;
    private final String[] lines;
    private int scroll;

    public StartupErrorScreen(MIDlet midlet, Throwable error, boolean resourcesOk) {
        this.midlet = midlet;
        title = resourcesOk
                ? "Quantum: startup failed"
                : "Quantum: game resources missing";
        lines = describe(error, resourcesOk);
        setFullScreenMode(false);
    }

    private static String[] describe(Throwable error, boolean resourcesOk) {
        String[] out = new String[16];
        int n = 0;
        if(!resourcesOk) {
            out[n++] = "This JAR has no /setting.txt.";
            out[n++] = "Build with the game assets";
            out[n++] = "(--resource-archive) or install";
            out[n++] = "the full game JAR.";
            out[n++] = "";
        }
        if(error != null) {
            // CLDC 1.1 has no StackTraceElement/getStackTrace(): only the
            // exception class and message can be shown on a phone.
            out[n++] = shortName(error.getClass().getName());
            String msg = safeMessage(error);
            if(msg != null && msg.length() > 0) out[n++] = msg;
            Throwable cause = safeCause(error);
            if(cause != null && cause != error) {
                out[n++] = "cause: " + shortName(cause.getClass().getName());
                String cm = safeMessage(cause);
                if(cm != null && cm.length() > 0) out[n++] = cm;
            }
        }
        String[] exact = new String[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    private static String safeMessage(Throwable t) {
        try {
            return t.getMessage();
        } catch(Throwable ignored) {
            return null;
        }
    }

    private static Throwable safeCause(Throwable t) {
        // CLDC 1.1 Throwable has no getCause(); toString() carries the detail.
        try {
            String all = t.toString();
            int at = all.indexOf(": ");
            if(at > 0) return new Throwable(all.substring(at + 2));
            return null;
        } catch(Throwable ignored) {
            return null;
        }
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0);
        g.fillRect(0, 0, w, h);
        g.setColor(0xffffff);

        Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        Font bold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        int lineHeight = small.getHeight() + 2;

        g.setFont(bold);
        g.drawString(title, w / 2, 4, Graphics.TOP | Graphics.HCENTER);

        g.setFont(small);
        int y = 4 + bold.getHeight() + 6;
        for(int i = scroll; i < lines.length; i++) {
            if(y + lineHeight > h - 16) break;
            g.drawString(lines[i], 3, y, Graphics.TOP | Graphics.LEFT);
            y += lineHeight;
        }

        g.setColor(0x888888);
        g.drawString("OK = close", w / 2, h - 14, Graphics.TOP | Graphics.HCENTER);
    }

    protected void onKey() {
        try {
            midlet.notifyDestroyed();
        } catch(Throwable ignored) {
        }
    }

    protected void keyPressed(int keyCode) {
        if(keyCode == Canvas.KEY_NUM2) {
            if(scroll > 0) scroll--;
            repaint();
        } else if(keyCode == Canvas.KEY_NUM8) {
            if(scroll < lines.length) scroll++;
            repaint();
        } else {
            onKey();
        }
    }
}
