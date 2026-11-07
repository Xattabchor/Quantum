#!/usr/bin/env python3
"""Apply the parts of utilstext-config.patch that still matter, by hand.

tools/patches/utilstext-config.patch is written against an older UtilsText and
has failed to apply on every recovery, on both files it touches. These are the
hunks the port actually needs: a guarded resource lookup, and parsers that
return a type default instead of throwing during MIDlet init.
"""
import re
import sys

ROOT = "UtilsText/src/code/utils/"


def patch(path, pairs):
    with open(ROOT + path, encoding="cp1251") as fh:
        src = fh.read()
    if pairs[0][1] in src:
        print("  %s already patched" % path)
        return
    for old, new in pairs:
        if old not in src:
            sys.exit("anchor missing in %s: %r" % (path, old[:60]))
        src = src.replace(old, new, 1)
    with open(ROOT + path, "w", encoding="cp1251") as fh:
        fh.write(src)
    print("  %s patched" % path)


patch("StringTools.java", [
    ("public class StringTools {\n\n    public static String getStringFromResource",
     "public class StringTools {\n\n"
     "    /** Packaged text resource, or null when it is absent. A null stream\n"
     "     * used to become a NullPointerException inside IniFile during startup,\n"
     "     * so the lookup is guarded and tries both the MIDlet's own loader and\n"
     "     * the bootstrap loader. CLDC 1.1 has no Class.getClassLoader, so these\n"
     "     * two calls are the whole lookup surface on a handset. */\n"
     "    public static InputStream openResource(String file) {\n"
     "        if(file == null) return null;\n"
     "        try {\n"
     "            InputStream is = StringTools.class.getResourceAsStream(file);\n"
     "            if(is != null) return is;\n"
     "            if(file.startsWith(\"/\")) return StringTools.class.getResourceAsStream(file.substring(1));\n"
     "            return StringTools.class.getResourceAsStream(\"/\" + file);\n"
     "        } catch(Throwable ignored) {\n"
     "            return null;\n"
     "        }\n"
     "    }\n\n"
     "    public static String getStringFromResource"),
    ("is = (new Object()).getClass().getResourceAsStream(file);",
     "is = openResource(file);\n            if(is == null) return null;"),
    ("        return Float.parseFloat(val.trim());",
     "        if(val == null) return 0.0f;\n        try {\n            return Float.parseFloat(val.trim());\n        } catch(NumberFormatException exc) {\n            return 0.0f;\n        }"),
    ("        return Integer.parseInt(val.trim());",
     "        if(val == null) return 0;\n        try {\n            return Integer.parseInt(val.trim());\n        } catch(NumberFormatException exc) {\n            return 0;\n        }"),
    ("        return Byte.parseByte(val.trim());",
     "        if(val == null) return 0;\n        try {\n            return Byte.parseByte(val.trim());\n        } catch(NumberFormatException exc) {\n            return 0;\n        }"),
    ("        return Long.parseLong(val.trim());",
     "        if(val == null) return 0L;\n        try {\n            return Long.parseLong(val.trim());\n        } catch(NumberFormatException exc) {\n            return 0L;\n        }"),
    ("    public static int cleverParseInt(String val) {\n        val = val.trim();",
     "    public static int cleverParseInt(String val) {\n        if(val == null) return 0;\n        val = val.trim();"),
])

patch("IniFile.java", [
    ("    public static Object[] createGroups(String file) {\n"
     "        file = StringTools.getStringFromResource(file);\n"
     "        String[] lines = StringTools.cutOnStrings(file, '\\n');\n",
     "    public static Object[] createGroups(String file) {\n"
     "        String text = StringTools.getStringFromResource(file);\n"
     "        String[] lines = (text == null) ? new String[0] : StringTools.cutOnStrings(text, '\\n');\n"
     "        if(lines == null) lines = new String[0];\n"),
    ("    public IniFile(String str, boolean keys) {\n"
     "        hashtable = new Hashtable();\n"
     "        String[] lines = StringTools.cutOnStrings(str, '\\n');\n",
     "    public IniFile(String str, boolean keys) {\n"
     "        hashtable = new Hashtable();\n"
     "        // A missing or unreadable config resource used to be a NullPointerException\n"
     "        // inside cutOnStrings. An empty map is the correct \"no settings\" state.\n"
     "        String[] lines = (str == null) ? new String[0] : StringTools.cutOnStrings(str, '\\n');\n"
     "        if(lines == null) lines = new String[0];\n"),
])
