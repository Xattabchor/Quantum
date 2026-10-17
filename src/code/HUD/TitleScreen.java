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

    private static final long TITLE_FADE_DURATION = 1000L;

    private Image title;
    private int[] titlePixels;

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
        if(Main.title_image != null) title = ImageResize.createImage(Main.title_image, w, h);
    }

    private void drawTitle(Graphics g) {
        if(title == null) return;

        long elapsed = System.currentTimeMillis() - start;
        if(elapsed >= TITLE_FADE_DURATION) {
            titlePixels = null;
            g.drawImage(title, 0, 0, 0);
            return;
        }
        if(elapsed < 0) elapsed = 0;

        int fade = (int) (elapsed * 255 / TITLE_FADE_DURATION);
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

    protected final void paint(Graphics g) {
        g.setColor(0);
        g.fillRect(0, 0, w, h);
        g.setColor(0xffffff);

        drawTitle(g);

        int y = h - (int) ((System.currentTimeMillis() - start) * pixelsPerSec / 1000);
        text.setY(y);
        text.paint(g, 0, 0);

        if(y <= -text.getTextHeight()) {
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
