#!/bin/sh
# Сборка конвертера OBJ <-> 3D: отдельный MIDlet (в JAR игры не входит).
#
# Цепочка та же, что у игры и QctConverter: ECJ (-source/-target 1.4)
# -> ProGuard -microedition -> упаковка JAR/JAD. JSR-75 (FileConnection)
# приходит из compile-only заглушки stubsrc/ и в JAR не пакуется: на
# аппарате интерфейс даёт прошивка.
#
# ВНИМАНИЕ: -dontoptimize обязателен (docs/ARM9.md §23): IBM J9 (Nokia E65)
# молча отвергает оптимизированный байткоды - приложение вылетает при
# загрузке. Shrink безопасен (проверено NOOPT/превер-сборками на устройстве).
#
# Запуск:
#   T=~/tools/j2me SRC=. JAVA=java sh docs/j2me-build/build-objconv.sh
set -e

T=${T:-/home/user/tools/j2me}
SRC=${SRC:-/home/user/Quantum}
JAVA=${JAVA:-java}
NAME=ObjConverter
HERE="$(cd "$(dirname "$0")" && pwd)"

BUILD="$SRC/build/objconv"
STUB="$BUILD/stub"
CLASSES="$BUILD/compiled"
PREVER="$BUILD/preverified"
OUT="$SRC/dist"
BOOT="$T/lib/cldc_1.1.jar:$T/lib/midp_2.0.jar"

rm -rf "$BUILD"
mkdir -p "$STUB" "$CLASSES" "$PREVER" "$OUT"

# 1. Заглушки JSR-75 (только для компилятора; в JAR не пакуются)
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -bootclasspath "$BOOT" -nowarn \
    -d "$STUB" "$HERE"/stubsrc/javax/microedition/io/file/*.java

# 2. Конвертер (кодек + MIDlet)
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -bootclasspath "$BOOT" -nowarn \
    -classpath "$STUB" -d "$CLASSES" \
    "$SRC/tools/objconv/src/tools/objconv/Obj3d.java" \
    "$SRC/tools/objconv/src/tools/objconv/ObjConvMIDlet.java"
echo "классов конвертера: $(find "$CLASSES" -name '*.class' | wc -l)"

# 3. Препроверка (StackMap для CLDC)
cat > "$BUILD/proguard.pro" <<EOF
-injars $CLASSES
-outjars $PREVER
-libraryjars $BOOT
-libraryjars $STUB
# shrink безопасен (проверен на E65), optimize - НЕТ (§23): -dontoptimize.
# Рефлексии нет, MIDlet-класс закреплён keep-правилом.
-keep public class tools.objconv.ObjConvMIDlet { public *; }
-keepattributes SourceFile,LineNumberTable
-dontoptimize
-dontobfuscate
-microedition
-target 1.4
-ignorewarnings
EOF
"$JAVA" -cp "$T/lib/proguard-ant.jar" proguard.ProGuard "@$BUILD/proguard.pro"
echo "классов после preverify: $(find "$PREVER" -name '*.class' | wc -l)"

# 4. JAR + JAD (манифест первой записью)
python3 - "$PREVER" "$OUT" "$NAME" <<'PYEOF'
import os, sys, zipfile
PREVER, OUT, NAME = sys.argv[1:4]
mf = ['Manifest-Version: 1.0',
      'MIDlet-1: OBJ Converter,,tools.objconv.ObjConvMIDlet',
      'MIDlet-Name: OBJ Converter',
      'MIDlet-Version: 1.0',
      'MIDlet-Vendor: AE-MODS.RU',
      'MIDlet-Description: OBJ <-> Quantum 3D mesh converter',
      'MicroEdition-Configuration: CLDC-1.1',
      'MicroEdition-Profile: MIDP-2.0',
      'MIDlet-Jar-URL: %s.jar' % NAME]
manifest = '\r\n'.join(mf) + '\r\n\r\n'
jar = os.path.join(OUT, NAME + '.jar')
z = zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED)
z.writestr('META-INF/MANIFEST.MF', manifest)
n = 0
for root, dirs, files in os.walk(PREVER):
    dirs.sort()
    for f in sorted(files):
        full = os.path.join(root, f)
        z.write(full, os.path.relpath(full, PREVER).replace(os.sep, '/'))
        n += 1
z.close()
size = os.path.getsize(jar)
jad = os.path.join(OUT, NAME + '.jad')
with open(jad, 'w', encoding='utf-8') as fh:
    for line in mf:
        if line.startswith('MIDlet-Jar-URL'):
            continue
        fh.write(line + '\n')
    fh.write('MIDlet-Jar-Size: %d\n' % size)
print('записей: %d, размер: %d байт' % (n + 1, size))
PYEOF

# 5. Копия в artifacts/ и самопроверка тем же check-jar.py
ART="$SRC/artifacts"
mkdir -p "$ART"
cp "$OUT/$NAME.jar" "$OUT/$NAME.jad" "$ART/"
CHECK="$HERE/check-jar.py"
if [ -f "$CHECK" ]; then
    python3 "$CHECK" "$OUT/$NAME.jar" \
        --lib "$T/lib/cldc_1.1.jar" --lib "$T/lib/midp_2.0.jar" \
        --lib "$STUB"
fi

echo "готово: $OUT/$NAME.jar и $ART"
ls -la "$OUT/$NAME.jar" "$OUT/$NAME.jad"
