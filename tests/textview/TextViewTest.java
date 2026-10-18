import code.HUD.Base.Font;
import code.HUD.Base.TextView;
import java.util.Vector;

/** Bounds the negative control too: never exhaust heap on a splitter loop. */
public final class TextViewTest {
    static final class BoundedLines extends Vector {
        private final int limit;
        BoundedLines(int n){limit=n+1;}
        public void addElement(Object line) {
            if(size()>=limit)throw new AssertionError("line splitter made no progress");
            super.addElement(line);
        }
    }
    static Vector split(String text,int width) {
        Vector lines=new BoundedLines(text.length());Font font=new Font();
        TextView.createLines(text,lines,font,width);
        StringBuffer joined=new StringBuffer();
        for(int i=0;i<lines.size();i++) {
            String line=(String)lines.elementAt(i);joined.append(line);
            if(font.widthOf(line)>width && line.length()>1)throw new AssertionError("oversized non-atomic line");
        }
        if(!joined.toString().replace(" ","").equals(text.replace(" ","").replace("*","")))
            throw new AssertionError("splitter lost/duplicated characters");
        return lines;
    }
    static void golden(String text,int width,String[] expected) {
        Vector got=split(text,width);
        if(got.size()!=expected.length)throw new AssertionError("line count: "+text+" "+got);
        for(int i=0;i<expected.length;i++)if(!expected[i].equals(got.elementAt(i)))throw new AssertionError("line changed: "+got);
    }
    public static void main(String[] args) {
        if(args.length!=0 && args[0].equals("oversized")) {split("WW",1);System.out.println("PASS oversized glyph progress");return;}
        golden("one two",32,new String[]{"one","two"});
        golden("abcdefgh",32,new String[]{"abcd","efgh"});
        golden("prefix abcdefghijkl",32,new String[]{"pref","ix","abcd","efgh","ijkl"});
        golden("a**b",32,new String[]{"a","","b"});golden("",32,new String[]{});
        golden("WW",1,new String[]{"W","W"});
        String error="M3G required*java.lang.IllegalStateException: M3G required: unsupported object code.Rendering.Meshes.Sprite material=none*Software fallback is disabled.";
        for(int width:new int[]{-1,0,1,8,32,80,112,176,224}) {
            split(error,width);split("word abcdefghijklmnopqrstuvwxyz word abcdefghijklmnopqrstuvwxyz",width);
            split(" *  ** ",width);split("\u041e\u0448\u0438\u0431\u043a\u0430 WWWWWWWWWWWWWWWWWWWWWWWWWWWWW",width);
        }
        System.out.println("PASS TextView bounded progress / diagnostic long tokens / tiny + nonpositive widths / oversized glyph / spaces + explicit breaks / normal wrapping / no lost characters");
    }
}
