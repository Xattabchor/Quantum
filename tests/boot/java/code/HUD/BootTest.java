package code.HUD;

import code.utils.Main;
import code.utils.canvas.MainCanvas;
import code.utils.canvas.MyCanvas;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Runs the real MIDlet startup path (Main.startApp -> init -> splash -> menu ->
 * settings) on a desktop JVM with LCDUI/MMAPI/RMS doubles, and asserts what a
 * handset would do. Scenarios are chosen by tests/boot/run.py.
 *
 * This is not hardware emulation: it reproduces the startup code path and the
 * failure modes that leave a phone on a black screen.
 */
public final class BootTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    private static String name(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static Displayable current() {
        return Display.getDisplay(null).getCurrent();
    }

    /** The game screen shown inside the MIDlet's single Canvas. */
    private static Object screen() {
        return ((MainCanvas) current()).getScreen();
    }

    /** Paint an arbitrary Canvas (the error screen is not a game screen). */
    private static void paintRaw(Displayable d, String label) {
        check(d instanceof Canvas, label + ": display is a Canvas, got " + d);
        Image image = Image.createImage(240, 320);
        Graphics g = image.getGraphics();
        try {
            ((Canvas) d).paintTo(g);
        } catch (Throwable t) {
            throw new AssertionError(label + ": paint threw " + t, t);
        }
        check(Graphics.strings > 0, label + ": error text was drawn");
    }

    /** Paint through the real Canvas -> MainCanvas -> MyCanvas path. */
    private static void paint(String label) {
        check(current() instanceof Canvas, label + ": display is a Canvas, got " + current());
        check(screen() instanceof MyCanvas, label + ": a game screen is set");
        Image image = Image.createImage(240, 320);
        Graphics g = image.getGraphics();
        int before = Graphics.fills + Graphics.regions + Graphics.images + Graphics.strings;
        try {
            ((Canvas) current()).paintTo(g);
        } catch (Throwable t) {
            throw new AssertionError(label + ": paint threw " + t, t);
        }
        check(Graphics.fills + Graphics.regions + Graphics.images + Graphics.strings > before,
                label + ": screen actually drew something");
    }

    /** Screens keep their key handlers protected; drive them like the Canvas. */
    private static void fire(Object screen, String handler) {
        try {
            java.lang.reflect.Method m = screen.getClass().getDeclaredMethod(handler);
            m.setAccessible(true);
            m.invoke(screen);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new AssertionError(handler + " threw " + cause, cause);
        } catch (Exception e) {
            throw new AssertionError("cannot call " + handler + ": " + e);
        }
    }

    /**
     * Reads a public static field/method by name. The pre-fix JAR has no
     * diagnostics API at all, and comparing old against new must still compile.
     */
    private static Object probe(String member) {
        try {
            java.lang.reflect.Field f = Main.class.getField(member);
            return f.get(null);
        } catch (NoSuchFieldException e) {
            try {
                return Main.class.getMethod(member).invoke(null);
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean recordedFailure() {
        Object v = probe("startupFailure");
        return v != null;
    }

    private static boolean settingsLoaded() {
        Object v = probe("settingsOk");
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "boot";
        Main midlet = new Main();

        fire(midlet, "startApp");

        // Broken packaging must never be a black screen: these scenarios assert
        // that the MIDlet survives, explains itself and still repaints.
        boolean brokenPackaging = "missing".equals(scenario)
                || "nolangs".equals(scenario) || "nofont".equals(scenario);
        if (brokenPackaging) {
            check(name(current()).endsWith("StartupErrorScreen"),
                    scenario + ": explanatory LCDUI screen, got " + current());
            if (!"orig".equals(System.getProperty("stub.build"))) {
                check(recordedFailure(), scenario + ": failure recorded for diagnostics");
            }
            Image shot = Image.createImage(240, 320);
            ((Canvas) current()).paintTo(shot.getGraphics());
            check(Graphics.strings > 0, scenario + ": error text is drawn");
            if ("missing".equals(scenario)) {
                if (!"orig".equals(System.getProperty("stub.build"))) {
                    check(!settingsLoaded(), "settingsOk reports the missing config");
                }
            }
            report(scenario);
            return;
        }

        // No scenario reaches here if startup threw: Main.startApp() would have
        // swapped in StartupErrorScreen. That is the central assertion.
        check(!recordedFailure(), "no startup failure, got: " + probe("startupFailure"));
        check(screen() instanceof SplashScreen, "splash screen is current, got " + screen());

        if (!"missing".equals(scenario) && !"raw".equals(scenario)) {
            check(settingsLoaded(), "/setting.txt was read");
            check(Main.langs != null && Main.langs.length > 0, "language list is non-empty");
            check(Main.lang >= -1 && Main.lang < Main.langs.length, "language index in range");
            check(probe("getMusicPlayer") != null, "music player handle is always usable");
        }

        paint("splash");

        // The splash auto-advances after 3.5 s; the fire key skips it, exactly
        // as a user does on the phone.
        // splash -> [language] -> audio -> menu, one fire key per step.
        for (int step = 0; step < 5 && !(screen() instanceof Menu); step++) {
            ((Canvas) current()).press(Canvas.KEY_NUM5);
            paint("splash step " + step + " (" + screen().getClass().getName() + ")");
        }
        check(screen() instanceof Menu, "menu after splash, got " + screen());
        paint("menu");

        Menu menu = (Menu) screen();
        String[] items = menu.getItems();
        int optionsRow = -1;
        for (int i = 0; i < items.length; i++) {

            check(items[i] != null, "menu row " + i + " is filled");
        }
        // The options label is localised; Main.getGameText() knows the real one.
        String optionsLabel = Main.getGameText().get("OPTIONS");
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(optionsLabel)) optionsRow = i;
        }
        check(optionsRow >= 0, "menu offers an options row (" + optionsLabel + ")");
        menu.setItemIndex(optionsRow);
        fire(menu, "onKey5");
        check(screen() instanceof Setting, "settings screen opened, got " + screen());
        paint("settings");

        Setting s = (Setting) screen();
        int rows = s.getItems().length;

        check(rows >= 18, "settings has every row, got " + rows);
        for (int i = 0; i < rows; i++) {
            check(s.getItems()[i] != null, "settings row " + i + " is filled");
        }

        // Walk every row in both directions: a stale index or a row that the
        // dispatcher forgot throws right here, on the device it is a dead app.
        for (int i = 0; i < rows; i++) {
            s.setItemIndex(i);
            fire(s, "onKey4");
            fire(s, "onKey6");
            s.setItemIndex(i);
            fire(s, "onKey5");
        }
        paint("settings after toggling every row");
        fire(s, "onRightSoftKey");
        check(screen() instanceof Menu, "back from settings returns to the menu, got " + screen());
        paint("menu after settings");

        report(scenario);
    }

    private static void report(String scenario) {
        String media = System.getProperty("stub.media");
        String platform = System.getProperty("microedition.platform");
        System.out.println("[ok] boot/" + scenario
                + " checks=" + checks
                + " fills=" + Graphics.fills
                + " regions=" + Graphics.regions
                + " images=" + Graphics.images
                + " strings=" + Graphics.strings
                + " media=" + (media == null ? "present" : media)
                + " platform=" + (platform == null ? "<unset>" : "'" + platform + "'"));
    }
}
