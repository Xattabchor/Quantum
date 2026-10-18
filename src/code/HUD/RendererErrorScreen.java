package code.HUD;

import code.Gameplay.GameScreen;
import code.HUD.Base.TextView;
import code.utils.Main;
import javax.microedition.lcdui.Graphics;

/** LCDUI diagnostic only: never draws or takes a screenshot of a failed scene. */
public final class RendererErrorScreen extends GUIScreen {
    private final Main main;
    private GameScreen game;
    private final TextView message;

    public RendererErrorScreen(Main main,GameScreen game,String error) {
        this.main=main;this.game=game;
        setFont(Main.getFont());
        setLeftSoft("Retry M3G");
        String menu=Main.getGameText().get("MENU");
        setRightSoft(menu==null?"Menu":menu);
        int line=getFont().height();
        message=new TextView("M3G required*"+error+"*Software fallback is disabled.",
                Math.max(1,getWidth()-16),Math.max(line,getHeight()-line*3),getFont());
    }

    protected void paint(Graphics g) {
        g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());
        message.paint(g,8,getFont().height());
        drawSoftKeys(g);
    }

    protected void onKey2() {message.move(getFont().height());repaint();}
    protected void onKey8() {message.move(-getFont().height());repaint();}
    protected void onKey5() {onLeftSoftKey();}

    protected void onLeftSoftKey() {
        if(game==null) return;
        GameScreen retry=game;game=null;
        retry.retryRenderer();
    }

    protected void onRightSoftKey() {
        if(game==null) return;
        // No implicit game-save writes on a renderer failure.
        game.destroy();game=null;
        Main.setCurrent(new Menu(main));
    }
}
