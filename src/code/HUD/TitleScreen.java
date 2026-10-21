package code.HUD;

import code.HUD.Base.TextView;
import code.utils.ImageResize;
import code.utils.Main;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class TitleScreen extends GUIScreen {

    Main main;
    Menu menu;
    TextView text;

    private static final long TITLE_FADE_DURATION = 4000L;
    private static final long TITLE_FADE_OUT_DURATION = 4500L;

    private Image title;
    private int[] titlePixels;
    private boolean titleFadeFinished;

    int pixelsPerSec;
    int w, h;
    long start;

    public TitleScreen(Main main, Menu menu) {
        this.main = main;
        this.menu = menu;
        w = getWidth();
        h = getHeight();

        setFont(Main.getFont());
        setSoftKeysNames(null, null);

        createText();

        pixelsPerSec = Main.settings.getInt("TITLE_SCREEN_SPEED", 10);
        if(pixelsPerSec <= 0) pixelsPerSec = 10;

        String music = Main.settings.get("TITLE_SCREEN_MUSIC");
        if(Main.isMusic && music != null) {
            Main.musicPlayer.loadFile(music);
            Main.musicPlayer.setVolume(Main.music);
            Main.musicPlayer.start();
        }

        loadTitleImage();
        start = System.currentTimeMillis();
    }

    private void createText() {
        text = new TextView(Main.getGameText().get("TITLE_SCREEN_TEXT"), w, h, Main.getFont());
        text.setCenter(true);
    }

    private void loadTitleImage() {
        title = null;
        titlePixels = null;
        titleFadeFinished = false;
        if(Main.title_image != null) title = ImageResize.createImage(Main.title_image, w, h);
    }

    private int getFadeAlpha(long elapsed, long duration) {
        if(elapsed <= 0) return 0;
        if(elapsed >= duration) return 255;

        int progress = (int) (elapsed * 255L / duration);
        return progress * progress * (765 - 2 * progress) / 65025;
    }

    private void drawTitle(Graphics g, long elapsed) {
        if(title == null) return;

        if(elapsed >= TITLE_FADE_DURATION) {
            if(!titleFadeFinished) {
                titlePixels = null;
                titleFadeFinished = true;
            }
            g.drawImage(title, 0, 0, 0);
            return;
        }

        int fade = getFadeAlpha(elapsed, TITLE_FADE_DURATION);
        int pixelCount = w * h;
        if(titlePixels == null || titlePixels.length != pixelCount) {
            titlePixels = new int[pixelCount];
        }

        title.getRGB(titlePixels, 0, w, 0, 0, w, h);
        for(int i = 0; i < pixelCount; i++) {
            int pixel = titlePixels[i];
            int alpha = ((pixel >>> 24) & 0xff) * fade / 255;
            titlePixels[i] = (alpha << 24) | (pixel & 0x00ffffff);
        }
        g.drawRGB(titlePixels, 0, w, 0, 0, w, h, true);
    }

    private void drawFadeOut(Graphics g, long elapsed, long scrollDuration) {
        long fadeDuration = scrollDuration < TITLE_FADE_OUT_DURATION ?
            scrollDuration : TITLE_FADE_OUT_DURATION;
        if(fadeDuration <= 0) return;

        long fadeStart = scrollDuration - fadeDuration;
        if(elapsed <= fadeStart) return;

        int alpha = getFadeAlpha(elapsed - fadeStart, fadeDuration);
        if(alpha <= 0) return;

        int pixelCount = w * h;
        // Reuse the title buffer to keep the CLDC heap footprint low.
        if(titlePixels == null || titlePixels.length != pixelCount) {
            titlePixels = new int[pixelCount];
        }

        int black = alpha << 24;
        for(int i = 0; i < pixelCount; i++) {
            titlePixels[i] = black;
        }
        g.drawRGB(titlePixels, 0, w, 0, 0, w, h, true);
    }

    protected final void paint(Graphics g) {
        long elapsed = System.currentTimeMillis() - start;
        g.setColor(0);
        g.fillRect(0, 0, w, h);
        g.setColor(0xffffff);

        drawTitle(g, elapsed);

        int textHeight = text.getTextHeight();
        int y = h - (int) (elapsed * pixelsPerSec / 1000L);
        text.setY(y);
        text.paint(g, 0, 0);

        long scrollDuration = (((long) h + textHeight) * 1000L + pixelsPerSec - 1) / pixelsPerSec;
        drawFadeOut(g, elapsed, scrollDuration);

        if(y <= -textHeight) {
            onRightSoftKey();
            return;
        }

        try {
            Thread.sleep(5L);
        } catch (Exception e) {
        }

        repaint();
    }

    protected final void onRightSoftKey() {
        if(Main.musicPlayer.hasPlayer()) Main.musicPlayer.stop();

        if(menu == null) menu = new Menu(main);
        Main.setCurrent(menu);
    }

    public static boolean hasTitleScreen() {
        return Main.getGameText().get("TITLE_SCREEN_TEXT") != null;
    }

    public final void destroy() {
        super.destroy();
        title = null;
        titlePixels = null;
        text = null;
    }

    public void sizeChanged(int w, int h) {
        this.w = w;
        this.h = h;
        loadTitleImage();
        createText();
    }
}
