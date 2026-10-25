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
   // TITLE_IMAGE: своя картинка-фон титульного экрана (растягивается на
   // весь экран). null = прежний чёрный фон бит-в-бит: ключа нет либо файл
   // не загрузился (ImageResize.createImage при ошибке возвращает null).
   Image background;
   
   int pixelsPerSec;
   int w, h;
   long start;

   public TitleScreen(Main main, Menu menu) {
      this.main = main;
      this.menu = menu;
      w = getWidth(); h = getHeight();
      
      setFont(Main.getFont());
      setSoftKeysNames(null, null);
      
      
      text = new TextView(Main.getGameText().get("TITLE_SCREEN_TEXT"), w, h, Main.getFont());
      text.setCenter(true);
      
      pixelsPerSec = Main.settings.getInt("TITLE_SCREEN_SPEED", 10);
      
      String titleImage = Main.settings.getNoLang("TITLE_IMAGE");
      if(titleImage != null) background = ImageResize.createImage(titleImage, w, h);
      
      String music = Main.settings.get("TITLE_SCREEN_MUSIC");
      if(Main.isMusic && music != null) {
          Main.musicPlayer.loadFile(music);
          Main.musicPlayer.setVolume(Main.music);
          Main.musicPlayer.start();
      }
      
      start = System.currentTimeMillis();
   }

    protected final void paint(Graphics g) {
        if(background != null) {
            g.drawImage(background, 0, 0, 0);
        } else {
            g.setColor(0);
            g.fillRect(0,0,w,h);
        }
        g.setColor(0xffffff);
        
        int y = h - (int) ((System.currentTimeMillis() - start) * pixelsPerSec / 1000);
        text.setY(y);
        text.paint(g, 0, 0);
        
        if(y == -text.getTextHeight()) {
            onRightSoftKey();
            return;
        }

        try {
            Thread.sleep(5l);
        } catch (Exception e) {}
        
        repaint();
    }

    protected final void onRightSoftKey() {
        background = null; // освободить heap до создания Menu
        if(Main.musicPlayer.hasPlayer()) Main.musicPlayer.stop();
        
        if(menu == null) menu = new Menu(main);
        Main.setCurrent(menu);
    }
    
    public static boolean hasTitleScreen() {
        return Main.getGameText().get("TITLE_SCREEN_TEXT") != null;
    }
    
}
