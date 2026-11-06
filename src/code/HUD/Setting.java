package code.HUD;

import code.HUD.Base.Selectable;
import code.Rendering.DirectX7;
import code.utils.IniFile;
import code.utils.Main;
import code.utils.canvas.MyCanvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import java.util.Vector;

public final class Setting extends Selectable {

    private Main main;
    private MyCanvas menu;
    private Object background;
    private int hei;
    private int h;
    private int pr7 = 0;
    private int pr9 = 0;

    public Setting(Main main, MyCanvas menu, Object background) {
        this.main = main;
        this.menu = menu;
        this.background = background;

        IniFile lang = Main.getGameText();

        // setItems() builds the exact row array; no placeholder sizing needed.
        setItems();

        list.left = true;
        list.scrollDown();

        hei = this.getHeight() / 2 - getHeight() * Main.getDisplaySize() / 200;
        h = this.getHeight() * Main.getDisplaySize() / 100;
    }
	
	private boolean hasLangSelect() {
		return !(menu instanceof PauseScreen) && Main.langs.length > 1;
	}
	
	private int currentLangIndex() {
		int index = Main.lang;
		if(index < 0 || index >= Main.langs.length) index = 0;
		return index;
	}

	private void applyLanguage() {
		String path = "/languages/" + Main.langs[currentLangIndex()].toLowerCase() + ".txt";
		main.setLanguage(path);
		
		if(menu instanceof Menu) ((Menu) menu).reloadText();
		setRightSoft(Main.getGameText().get("BACK"));
	}

    private static String langGetDef(IniFile lang, String key, String def) {
        String s = lang.get(key);
        return s == null || s.equals(key) ? def : s;
    }

    /**
     * Rows are built into a Vector and copied into the list in one go: the array
     * can never be shorter or longer than what was filled. The previous
     * fixed-size writer left an empty last row (and an array with a "spare" slot
     * that the dispatcher still accepted as a pressable row).
     */
    protected final void setItems() {
        IniFile lang = Main.getGameText();
        Vector rows = new Vector();
        Vector mids = new Vector();

        addRow(rows, mids, lang.get("AUDIO") + ":", true);
        addRow(rows, mids, lang.get("MUSIC_VOLUME") + ":" + lang.get("OFF"), false);
        if(Main.music > 0 && Main.isMusic) {
            rows.setElementAt(lang.get("MUSIC_VOLUME") + ":" + Main.music, rows.size() - 1);
        }
        addRow(rows, mids, lang.get("SOUNDS_VOLUME") + ":" + lang.get("OFF"), false);
        if(Main.sounds > 0 && Main.isSounds) {
            rows.setElementAt(lang.get("SOUNDS_VOLUME") + ":" + Main.sounds, rows.size() - 1);
        }

        addRow(rows, mids, lang.get("CONTROLS") + ":", true);
        addRow(rows, mids, lang.get("CAM_SPEED") + ":" + Main.mouseSpeed, false);
        addRow(rows, mids, lang.get("CHANGE_KEYS"), false);

        addRow(rows, mids, lang.get("PERF") + ":", true);
        addRow(rows, mids, langGetDef(lang, "M3G_VIEWPORT", "Viewport") + ":" + Main.m3gScreenScale + "%", false);
        addRow(rows, mids, langGetDef(lang, "M3G_HWMIP", "HW mip") + ":"
                + (Main.m3gLitMip == 1 ? lang.get("ON") : lang.get("OFF")), false);
        addRow(rows, mids, langGetDef(lang, "M3G_BAKED", "Baked light") + ":"
                + (Main.m3gLitNoVertexColors ? lang.get("OFF") : lang.get("ON")), false);
        addRow(rows, mids, lang.get("CORPSES") + ":"
                + (Main.isCorpses() ? lang.get("ON") : lang.get("OFF")), false);
        addRow(rows, mids, lang.get("BLOOD") + ":"
                + (this.main.isBlood() ? lang.get("ON") : lang.get("OFF")), false);
        addRow(rows, mids, lang.get("FRAMESKIP") + ":"
                + (Main.isFrameskip() ? lang.get("ON") : lang.get("OFF")), false);

        addRow(rows, mids, lang.get("HUD") + ":", true);
        if(hasLangSelect()) {
            String lng = Main.langs[currentLangIndex()];
            if(Main.settings.getNoLang(lng) != null) {
                lng = Main.settings.getNoLang(lng);
            } else if(lang.get(lng) != null) {
                lng = lang.get(lng);
            }
            addRow(rows, mids, lang.get("LANG") + ":" + lng, false);
        }
        addRow(rows, mids, lang.get("RESIZE_WEAPONS") + ":"
                + (Main.resizeWeapons ? lang.get("YES") : lang.get("NO")), false);
        addRow(rows, mids, lang.get("HIDEHUD") + ":"
                + (Main.hideHud ? lang.get("YES") : lang.get("NO")), false);
        addRow(rows, mids, lang.get("DEBUG") + ":"
                + (DeveloperMenu.debugMode ? lang.get("ON") : lang.get("OFF")), false);
        if(Main.renderer == DirectX7.M3G) {
            String label = lang.get("M3G_PERSPECTIVE");
            if(label == null) label = "HW perspective";
            addRow(rows, mids, label + ": " + lang.get(Main.m3gPerspective ? "ON" : "OFF"), false);
        }

        String[] items = new String[rows.size()];
        boolean[] midSel = new boolean[rows.size()];
        for(int i = 0; i < items.length; i++) {
            items[i] = (String) rows.elementAt(i);
            midSel[i] = ((Boolean) mids.elementAt(i)).booleanValue();
        }

        int index = this.list == null ? 0 : itemIndex();
        boolean left = this.list != null && list.left;
        set(Main.getFont(), items, null, lang.get("BACK"), midSel);
        list.left = left;
        setItemIndex(Math.min(index, items.length - 1));
    }

    private static void addRow(Vector rows, Vector mids, String text, boolean title) {
        rows.addElement(text == null ? "" : text);
        mids.addElement(title ? Boolean.TRUE : Boolean.FALSE);
    }

    protected final void paint(Graphics g) {
        g.setColor(0);
        g.fillRect(0, 0, getWidth(), getHeight());

        if(background instanceof Image) {
            g.drawImage((Image) background, 0, 0, 0);
        } else if(this.background instanceof int[]) {
            g.drawRGB((int[]) background, 0, getWidth(), 0, hei, getWidth(), h, false);
        }

        if(menu instanceof PauseScreen) {
            list.drawBck(g, getWidth() / 8, Main.getFont().height(), getWidth() * 6 / 8, getHeight() - Main.getFont().height() * 2);
            Main.drawBckDialog(g, getHeight() - Main.getFont().height(), getHeight());
        }
        list.draw(g, getWidth() / 8, Main.getFont().height(), getWidth() * 6 / 8, getHeight() - Main.getFont().height() * 2);
        drawSoftKeys(g);
    }

    protected final void onRightSoftKey() {
        main.saveSettingToStore();
        Main.setCurrent(this.menu);
    }

    protected final void onLeftSoftKey() {
        onKey5();
    }

    /** Index of the M3G perspective row, or -1 when the row is not shown. */
    private int perspectiveRow() {
        return Main.renderer == DirectX7.M3G ? getItems().length - 1 : -1;
    }

    protected final void onKey5() {
        int var1 = this.itemIndex();
        if(var1 == perspectiveRow()) {
            Main.m3gPerspective=!Main.m3gPerspective;
            setItems();repaint();return;
        }
        int id = 0;
        id++;//Audio
        id++;
        id++;
        id++;//Conrols
        id++;
        if(var1 == id) {
            Main.setCurrent(new KeysSettings(main, this, background, (menu instanceof PauseScreen)));
            return;
        }
        id++;//KEYS
        id++;//Perfomance
        id++;
        id++;
        id++;
        id++;
        id++;
        id++;
        id++;
        id++;
        id++;//Hud
        if(hasLangSelect()) id++;
        id++;//RESIZE_WEAPONS
        id++;//HIDEHUD
        id++;//DEBUG
    }

    protected final void onKey4() {
        int var1 = this.itemIndex();
        int id = 0;
        id++;//Audio
        if(var1 == id) {
            if(Main.isMusic) {
                Main.music -= 10;
                if(Main.music < 0) Main.music = 0;
                if(Main.music > 100) Main.music = 100;
                if(Main.music == 0) Main.isMusic = false;
            }
            Main.getMusicPlayer().setVolume(Main.isMusic ? Main.music : 0);
        }
        id++;

        if(var1 == id) {
            if(Main.isSounds) {
                Main.sounds -= 10;
                if(Main.sounds < 0) Main.sounds = 0;
                if(Main.sounds > 100) Main.sounds = 100;
                if(Main.sounds == 0) Main.isSounds = false;
            }
        }
        id++;

        id++;//Conrols
        if(var1 == id) Main.mouseSpeed -= 2;
        id++;
        id++;//KEYS
        id++;//Perfomance
        if(var1 == id) {
            Main.m3gScreenScale -= 25;
            if(Main.m3gScreenScale < 25) Main.m3gScreenScale = 25;
        }
        id++;
        if(var1 == id) Main.m3gLitMip = 0;
        id++;
        if(var1 == id) Main.m3gLitNoVertexColors = true;
        id++;
        if(var1 == id) Main.corpses = false;
        id++;
        if(var1 == id) Main.blood = false;
        id++;
        if(var1 == id) Main.frameskip = false;
        id++;
        id++;//Hud
		if(hasLangSelect()) {
			if(var1 == id) {
				Main.lang -= 1;
				if(Main.lang < 0) Main.lang = Main.langs.length - 1;
				applyLanguage();
			}
			id++;
		}
        if(var1 == id) Main.resizeWeapons = false;
        id++;
        if(var1 == id) Main.hideHud = false;
        id++;
        if(var1 == id) {
			DeveloperMenu.debugMode = false;
            if(menu instanceof Menu) ((Menu) menu).reloadText();
            if(menu instanceof PauseScreen) ((PauseScreen) menu).reloadText();
		}
        id++;
        if(Main.renderer==DirectX7.M3G && var1==id) Main.m3gPerspective=false;
        this.setItems();
        this.repaint();
    }

    protected final void onKey6() {
        int var1 = this.itemIndex();
        int id = 0;
        id++;//Audio
        if(var1 == id) {
            if(Main.isMusic) {
                Main.music += 10;
                if(Main.music < 0) Main.music = 0;
                if(Main.music > 100) Main.music = 100;
            }
            if(!Main.isMusic) Main.isMusic = true;
            Main.getMusicPlayer().setVolume(Main.isMusic ? Main.music : 0);
        }
        id++;

        if(var1 == id) {
            if(Main.isSounds) {
                Main.sounds += 10;
                if(Main.sounds < 0) Main.sounds = 0;
                if(Main.sounds > 100) Main.sounds = 100;
            }
            if(!Main.isSounds) Main.isSounds = true;
        }
        id++;

        id++;//Conrols
        if(var1 == id) Main.mouseSpeed += 2;
        id++;
        id++;//KEYS
        id++;//Perfomance
        if(var1 == id) {
            Main.m3gScreenScale += 25;
            if(Main.m3gScreenScale > 100) Main.m3gScreenScale = 100;
        }
        id++;
        if(var1 == id) Main.m3gLitMip = 1;
        id++;
        if(var1 == id) Main.m3gLitNoVertexColors = false;
        id++;
        if(var1 == id) Main.corpses = true;
        id++;
        if(var1 == id) Main.blood = true;
        id++;
        if(var1 == id) Main.frameskip = true;
        id++;
        id++;//Hud
		if(hasLangSelect()) {
			if(var1 == id) {
				Main.lang += 1;
				if(Main.lang >= Main.langs.length) Main.lang = 0;
				applyLanguage();
			}
			id++;
		}
        if(var1 == id) Main.resizeWeapons = true;
        id++;
        if(var1 == id) Main.hideHud = true;
        id++;
        if(var1 == id) {
            DeveloperMenu.debugMode = true;
            if(menu instanceof Menu) ((Menu) menu).reloadText();
            if(menu instanceof PauseScreen) ((PauseScreen) menu).reloadText();
        }
        id++;
        if(Main.renderer==DirectX7.M3G && var1==id) Main.m3gPerspective=true;
        this.setItems();
        this.repaint();
    }
}
