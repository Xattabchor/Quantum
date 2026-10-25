#!/bin/sh
# Сборка конвертера VLA 13<->9: отдельный MIDlet (в JAR игры не входит).
# Цепочка та же что у игры/Qct/Obj: ECJ (-source/-target 1.4) -> ProGuard -microedition -dontoptimize -> JAR/JAD.
# JSR-75 (FileConnection) - compile-only заглушки, в JAR не пакуются (на аппарате из прошивки).
# -dontoptimize обязателен (ARM9.md §23): IBM J9 (Nokia E65) отвергает оптимизированный байткод.
set -e
T=${T:-/home/user/tools/j2me}
SRC=${SRC:-/home/user/Quantum}
JAVA=${JAVA:-java}
NAME=VlaConverter
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$SRC/build/vlaconv"
STUB="$BUILD/stub"
CLASSES="$BUILD/compiled"
PREVER="$BUILD/preverified"
OUT="$SRC/dist"
BOOT="$T/lib/cldc_1.1.jar:$T/lib/midp_2.0.jar"
rm -rf "$BUILD"
mkdir -p "$STUB" "$CLASSES" "$PREVER" "$OUT"
# 1. заглушки JSR-75 (только для компилятора)
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -bootclasspath "$BOOT" -nowarn \
    -d "$STUB" "$HERE"/stubsrc/javax/microedition/io/file/*.java
# 2. кодек + MIDlet
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -bootclasspath "$BOOT" -nowarn \
    -classpath "$STUB" -d "$CLASSES" \
    "$SRC/tools/vlaconv/src/tools/vlaconv/VlaCodec.java" \
    "$SRC/tools/vlaconv/src/tools/vlaconv/VlaConvMIDlet.java"
echo "классов конвертера: $(find "$CLASSES" -name '*.class' | wc -l)"
# 3. препроверка
cat > "$BUILD/proguard.pro" <<EOF
-injars $CLASSES
-outjars $PREVER
-libraryjars $BOOT
-libraryjars $STUB
-keep public class tools.vlaconv.VlaConvMIDlet { public *; }
-keepattributes SourceFile,LineNumberTable
-dontoptimize
-dontobfuscate
-microedition
-target 1.4
-ignorewarnings
EOF
"$JAVA" -cp "$T/lib/proguard-ant.jar" proguard.ProGuard "@$BUILD/proguard.pro"
echo "классов после preverify: $(find "$PREVER" -name '*.class' | wc -l)"
# 4. JAR+JAD (манифест первой записью)
python3 - "$PREVER" "$OUT" "$NAME" <<'PYEOF'
import os, sys, zipfile
PREVER, OUT, NAME = sys.argv[1:4]
mf = ['Manifest-Version: 1.0',
      'MIDlet-1: VLA Converter,,tools.vlaconv.VlaConvMIDlet',
      'MIDlet-Name: VLA Converter',
      'MIDlet-Version: 1.0',
      'MIDlet-Vendor: AE-MODS.RU',
      'MIDlet-Description: VLA 13<->9 color to gray lightmap converter',
      'MicroEdition-Configuration: CLDC-1.1',
      'MicroEdition-Profile: MIDP-2.0',
      'MIDlet-Jar-URL: %s.jar' % NAME]
manifest='\r\n'.join(mf)+'\r\n\r\n'
jar=os.path.join(OUT, NAME+'.jar')
z=zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED); z.writestr('META-INF/MANIFEST.MF', manifest)
n=0
for root,dirs,files in os.walk(PREVER):
    dirs.sort()
    for f in sorted(files):
        full=os.path.join(root,f); z.write(full, os.path.relpath(full, PREVER).replace(os.sep,'/')); n+=1
z.close()
size=os.path.getsize(jar)
jad=os.path.join(OUT, NAME+'.jad')
with open(jad,'w',encoding='utf-8') as fh:
    for line in mf:
        if line.startswith('MIDlet-Jar-URL'): continue
        fh.write(line+'\n')
    fh.write('MIDlet-Jar-Size: %d\n' % size)
print('записей: %d, размер: %d байт' % (n+1, size))
PYEOF
# 5. копия в artifacts/ и check-jar
ART="$SRC/artifacts"; mkdir -p "$ART"
cp "$OUT/$NAME.jar" "$OUT/$NAME.jad" "$ART/"
CHECK="$HERE/check-jar.py"
if [ -f "$CHECK" ]; then
    python3 "$CHECK" "$OUT/$NAME.jar" --lib "$T/lib/cldc_1.1.jar" --lib "$T/lib/midp_2.0.jar" --lib "$STUB"
fi
echo "готово: $OUT/$NAME.jar и $ART"
ls -la "$OUT/$NAME.jar" "$OUT/$NAME.jad"
