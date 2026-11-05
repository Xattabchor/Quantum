package code.utils;

import java.io.InputStream;
import java.util.Vector;

public class StringTools {

    /**
     * Packaged text resource, or null when it is absent.
     *
     * A null stream used to become a NullPointerException inside IniFile during
     * startup, so the lookup itself is guarded and tries both the MIDlet's own
     * loader and the bootstrap loader. CLDC 1.1 has no Class.getClassLoader, so
     * these two calls are the whole lookup surface on a handset.
     */
    public static InputStream openResource(String file) {
        if(file == null) return null;
        try {
            // The harness decides where resources live. "stub.isolate" means the
            // jar on the classpath must NOT leak assets in: that is the whole
            // point of the missing-resource scenarios.
            if(System.getProperty("stub.isolate") == null) {
                InputStream is = StringTools.class.getResourceAsStream(file);
                if(is != null) return is;
                if(file.startsWith("/")) return StringTools.class.getResourceAsStream(file.substring(1));
                is = StringTools.class.getResourceAsStream("/" + file);
                if(is != null) return is;
            }
            // Unpacked assets in a directory. A device has no java.io.File,
            // which is why this lives in the harness copy only.
            String dir = System.getProperty("quantum.resource.dir");
            if(dir == null) return null;
            java.io.File f = new java.io.File(dir, file.startsWith("/") ? file.substring(1) : file);
            return f.isFile() ? new java.io.FileInputStream(f) : null;
        } catch(Throwable ignored) {
            return null;
        }
    }

    public static String getStringFromResource(String file) {
        InputStream is = null;

        try {
            is = openResource(file);
            if(is == null) return null;
            StringBuffer str = new StringBuffer();

            while(true) {
                int ch = is.read();
                if(ch == -1) break;
                if(ch == '\r') continue;
                
                if(ch >= 192 && ch <= 255) ch = ch + 848; //windows 1251 to utf8
                
                str.append((char) ch);
            }

            is.close();
            return str.toString();
        } catch (Exception exc) {
            System.out.println("Reading txt error: " + exc);
            if(is != null) {
                try {
                    is.close();
                } catch (Exception ex) {}
            }

            return null;
        }
    }
    
    private static Vector fragments(String text, char divider) {
        Vector fragments = new Vector();
      
        int start = 0;
        while(start < text.length()) {
            int end = text.indexOf(divider, start);
            if(end == -1) end = text.length();
            
            if(start < end) fragments.addElement(text.substring(start,end));
            
            start = end+1;
        }

        return fragments;
    }

    public static String[] cutOnStrings(String str, char d) {
        if(str == null) return null;
        
        Vector fragments = fragments(str, d);
        String[] out = new String[fragments.size()];
        fragments.copyInto(out);
        return out;
    }
    
    public static int[] cutOnInts(String str, char d) {
        if(str == null) return null;
        
        Vector fragments = fragments(str, d);
        int[] out = new int[fragments.size()];
        
        for(int i=0; i<out.length; i++) {
            out[i] = parseInt((String) fragments.elementAt(i));
        }
        
        return out;
    }
    //#if DefaultConfiguration
    public static float[] cutOnFloats(String str, char d) {
        if(str == null) return null;
        
        Vector fragments = fragments(str, d);
        float[] out = new float[fragments.size()];
        
        for(int i=0; i<out.length; i++) {
            out[i] = parseFloat((String) fragments.elementAt(i));
        }
        
        return out;
    }
    
    public static float parseFloat(String val) {
        if(val == null) return 0.0f;
        try {
            return Float.parseFloat(val.trim());
        } catch(NumberFormatException exc) {
            return 0.0f;
        }
    }
    //#endif
    public static int parseInt(String val) {
        if(val == null) return 0;
        try {
            return Integer.parseInt(val.trim());
        } catch(NumberFormatException exc) {
            return 0;
        }
    }
    
    public static int cleverParseInt(String val) {
        if(val == null) return 0;
        val = val.trim();
        int ind = val.indexOf('.');
        if(ind > -1) val = val.substring(0, ind); //floating point -> integer
        return Integer.parseInt(val);
    }
    
    public static byte parseByte(String val) {
        if(val == null) return 0;
        try {
            return Byte.parseByte(val.trim());
        } catch(NumberFormatException exc) {
            return 0;
        }
    }
    
    public static long parseLong(String val) {
        if(val == null) return 0L;
        try {
            return Long.parseLong(val.trim());
        } catch(NumberFormatException exc) {
            return 0L;
        }
    }
    
    public static boolean isNumeric(String s) {
        if(s == null) return false;
        s = s.trim();
        
        for(int i=0; i<s.length(); i++) {
            char ch = s.charAt(i);
            if(ch != '-' && !Character.isDigit(ch)) return false;
        }
        
        return true;
    }
    
    public static String deleteNonNumeric(String s) {
        while(s.length() > 0 && !Character.isDigit(s.charAt(0)) && s.charAt(0) != '-') {
            s = s.substring(1, s.length());
        }
        
        while(s.length() > 0 && !Character.isDigit(s.charAt(s.length()-1))) {
            s = s.substring(0, s.length()-1);
        }
        
        return s;
    }
    
    public static int getRGB(String rgbs, char div) {
        int[] rgb = cutOnInts(rgbs, div);
        return (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
    }
    
    public static int parseXMLColor(String argb) {
        int col = 0;
        argb = argb.toUpperCase();
        
        for(int i=1; i<argb.length(); i++) {
            char ch = argb.charAt(i);
            int val;
            
            if(Character.isDigit(ch)) val = ch-'0';
            else val = ch-'A'+10;
            
            col |= val << ((6-i)*4);
        }
        
        return col;
    }

}
