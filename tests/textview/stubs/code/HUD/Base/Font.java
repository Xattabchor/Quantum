package code.HUD.Base;
import javax.microedition.lcdui.Graphics;
public final class Font {
    public int charWidth(char c) {return c==' '?4:c=='W'?12:8;}
    public int widthOf(String s) {int n=0;for(int i=0;i<s.length();i++)n+=charWidth(s.charAt(i));return n;}
    public int height() {return 8;}
    public void drawString(Graphics g,String text,int x,int y,int anchor) {}
}
