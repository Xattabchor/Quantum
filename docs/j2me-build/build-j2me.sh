#!/bin/sh
# Сборка Quantum (J2ME MIDP-2.0 / CLDC-1.1) без NetBeans и без WTK.
#
# Цепочка та же, что в NetBeans:
#   ECJ (-source/-target 1.4)  ->  build/<config>/compiled
#   ProGuard -microedition     ->  build/<config>/preverified   (препроверка)
#   упаковка                   ->  dist/QuantumOptimize.jar + dist/QuantumOptimize.jad
#
# Что нужно в $T/lib (см. get-toolchain.sh):
#   ecj.jar           - Eclipse Compiler for Java (чистая Java, работает на JRE)
#   proguard-ant.jar  - ProGuard 7.x: -microedition делает препроверку (preverify)
#   cldc_1.1.jar, midp_2.0.jar, jsr184_1.1.jar - API платформы (bootclasspath)
#   jsr075_stub.jar   - заглушка FileConnection (только для компилятора)
#
# Запуск:
#   T=~/tools/j2me SRC=. JAVA=java sh docs/j2me-build/build-j2me.sh
set -e

T=${T:-/home/user/tools/j2me}
SRC=${SRC:-/home/user/Quantum}
JAVA=${JAVA:-java}
CONFIG=${CONFIG:-DefaultColorPhone}
NAME=QuantumOptimize
# Имя приложения в меню телефона (MIDlet-Name и подпись MIDlet-1).
# Переписывается скриптом сборки, оригинальный nbproject не трогается.
APPNAME='Quantum Optimize'

BUILD="$SRC/build/$CONFIG"
CLASSES="$BUILD/compiled"
PREVER="$BUILD/preverified"
OUT="$SRC/dist"
BOOT="$T/lib/cldc_1.1.jar:$T/lib/midp_2.0.jar:$T/lib/jsr184_1.1.jar:$T/lib/jsr075_stub.jar"

rm -rf "$CLASSES" "$PREVER" "$OUT"
mkdir -p "$CLASSES" "$PREVER" "$OUT"

# 1. Список исходников: движок + три библиотеки (git-подмодули Utils*)
find "$SRC/src" "$SRC/Utils/src" "$SRC/UtilsFPS/src" "$SRC/UtilsText/src" \
     -name '*.java' > "$BUILD/sources.txt"
echo "исходников: $(wc -l < "$BUILD/sources.txt")"

# 2. Компиляция (target 1.4 = class file version 48, как ждёт KVM)
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -g:lines,source -encoding UTF-8 -warn:none \
    -bootclasspath "$BOOT" -d "$CLASSES" "@$BUILD/sources.txt"
echo "скомпилировано классов: $(find "$CLASSES" -name '*.class' | wc -l)"

# 3. Препроверка (preverify) + shrink: ProGuard добавляет StackMap-атрибуты
#    для CLDC (без них KVM отказывается загружать классы) и вырезает мёртвый
#    код. Оптимизация байткода ВЫКЛЮЧЕНА (-dontoptimize, этап 23): строгий
#    верификатор IBM J9 (Nokia E65 и др. S60) отвергает оптимизированный
#    байткод - MIDlet вылетает сразу при запуске, хотя эмуляторы его
#    принимают. Обфускация выключена - имена классов нужны для crash-логов
#    и MIDlet-1 в JAD; входная точка закреплена keep-правилом.
cat > "$BUILD/proguard.pro" <<EOF
-injars  $CLASSES
-outjars $PREVER
-libraryjars $T/lib/cldc_1.1.jar
-libraryjars $T/lib/midp_2.0.jar
-libraryjars $T/lib/jsr184_1.1.jar
-libraryjars $T/lib/jsr075_stub.jar
# shrink включён: рефлексии в коде нет (проверено grep по Class.forName/
# newInstance), имя MIDlet-класса закреплено keep-правилом. -dontoptimize -
# навсегда: оптимизации ProGuard несовместимы с IBM J9 на реальных S60
# (см. docs/ARM9.md §23, диагностика - artifacts/diag/).
# Обфускация по-прежнему выключена: имена классов нужны для crash-логов.
-keep public class code.utils.Main { public *; }
-keepattributes SourceFile,LineNumberTable
-dontoptimize
-dontobfuscate
-microedition
-target 1.4
-ignorewarnings
EOF
"$JAVA" -cp "$T/lib/proguard-ant.jar" proguard.ProGuard "@$BUILD/proguard.pro"
echo "классов после preverify: $(find "$PREVER" -name '*.class' | wc -l)"

# 4. JAR + JAD (манифест берётся из nbproject/project.properties)
python3 - "$SRC" "$PREVER" "$OUT" "$NAME" "$APPNAME" <<'PYEOF'
import os, sys, zipfile

SRC, PREVER, OUT, NAME, APPNAME = sys.argv[1:6]
props = {}
with open(os.path.join(SRC, 'nbproject', 'project.properties'), encoding='utf-8') as f:
    for line in f:
        if '=' in line and not line.startswith('#'):
            k, v = line.split('=', 1)
            props[k.strip()] = v.strip()

def unescape(v):
    return v.replace('\\n', '\n').replace('\\:', ':').replace('\\=', '=')

mf = ['Manifest-Version: 1.0']
seen = set(['manifest-version'])

def add(line):
    """Добавляет атрибут, если ключ ещё не встречался (без дублей)."""
    line = line.strip()
    if not line or ':' not in line:
        return
    key = line.split(':', 1)[0].strip().lower()
    if key in seen:
        return
    seen.add(key)
    mf.append(line)

# MIDlet-1 и дополнительные атрибуты берутся из настроек проекта
for line in unescape(props.get('manifest.midlets', '')).split('\n'):
    add(line)
for line in unescape(props.get('manifest.others', '')).split('\n'):
    add(line)
# обязательные для MIDP атрибуты, которых в project.properties нет
add('MIDlet-Name: %s' % APPNAME)
add('MIDlet-Version: 1.0')
add('MIDlet-Vendor: AE-MODS.RU')
add('MicroEdition-Configuration: CLDC-1.1')
add('MicroEdition-Profile: MIDP-2.0')
add('MIDlet-Jar-URL: %s.jar' % NAME)

# Имя сборки: переписываем MIDlet-Name и подпись MIDlet-1 на APPNAME
# («Quantum Optimize»), не меняя nbproject/project.properties.
for i, line in enumerate(mf):
    if line.startswith('MIDlet-Name:'):
        mf[i] = 'MIDlet-Name: %s' % APPNAME
    elif line.startswith('MIDlet-1:'):
        parts = line.split(':', 1)[1].split(',')
        parts[0] = ' %s' % APPNAME
        mf[i] = 'MIDlet-1:' + ','.join(parts)

manifest = '\r\n'.join(mf) + '\r\n\r\n'

jar = os.path.join(OUT, '%s.jar' % NAME)
z = zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED)
z.writestr('META-INF/MANIFEST.MF', manifest)          # манифест - первым

# ресурсы: содержимое res/ кладётся в корень JAR (как в сборке NetBeans)
res = os.path.join(SRC, 'res')
nres = 0
for root, dirs, files in os.walk(res):
    for f in files:
        p = os.path.join(root, f)
        z.write(p, os.path.relpath(p, res).replace(os.sep, '/'))
        nres += 1

# препроверенные классы
ncls = 0
for root, dirs, files in os.walk(PREVER):
    for f in files:
        if not f.endswith('.class'):
            continue
        p = os.path.join(root, f)
        z.write(p, os.path.relpath(p, PREVER).replace(os.sep, '/'))
        ncls += 1
z.close()
size = os.path.getsize(jar)

jad = manifest.replace('MIDlet-Jar-URL: %s.jar' % NAME,
                       'MIDlet-Jar-URL: %s.jar\r\nMIDlet-Jar-Size: %d' % (NAME, size))
with open(os.path.join(OUT, '%s.jad' % NAME), 'w', encoding='utf-8', newline='') as f:
    f.write(jad)

print('ресурсов в JAR: %d, классов: %d' % (nres, ncls))

# --- самопроверка собранного JAR ---
z = zipfile.ZipFile(jar)
names = z.namelist()
problems = []
if names[0] != 'META-INF/MANIFEST.MF':
    problems.append('манифест не первой записью')
cls = [n for n in names if n.endswith('.class')]
if not cls:
    problems.append('в JAR нет классов')
if 'code/utils/Main.class' not in cls:
    problems.append('нет класса MIDlet (code/utils/Main.class)')
badver = [n for n in cls if (lambda b: b[6] * 256 + b[7])(z.read(n)) != 48]
if badver:
    problems.append('class version != 48 у %d классов' % len(badver))
smt = [n for n in cls if b'StackMapTable' in z.read(n)]
if smt:
    problems.append('StackMapTable (атрибут Java 6) у %d классов' % len(smt))
sm = len([n for n in cls if b'StackMap' in z.read(n)])
if os.path.getsize(jar) != size:
    problems.append('размер в JAD не совпадает')
print('классов с атрибутом StackMap: %d из %d (прочие без ветвлений)' % (sm, len(cls)))
print('ЗАПИСЕЙ: %d, РАЗМЕР: %d байт' % (len(names), size))
if problems:
    print('ПРОБЛЕМЫ:', problems)
    raise SystemExit(1)
print('самопроверка JAR: OK')
PYEOF

# 5. Копия в artifacts/ - именно оттуда JAR скачивается по ссылке из GitHub
#    (build/ и dist/ в .gitignore; artifacts/QuantumOptimize.jar и .jad - нет)
ART="$SRC/artifacts"
mkdir -p "$ART"
cp "$OUT/$NAME.jar" "$OUT/$NAME.jad" "$ART/"
python3 - "$PREVER" "$ART/preverified-classes.zip" <<'PYEOF'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, src).replace(os.sep, '/'))
print('ZIP препроверенных классов: %d байт' % os.path.getsize(out))
PYEOF

# 6. Глубокая проверка: полный разбор class-файлов (не только поиск подстрок)
CHECK="$(dirname "$0")/check-jar.py"
if [ -f "$CHECK" ]; then
    LIBS=""
    for j in "$T/lib/cldc_1.1.jar" "$T/lib/midp_2.0.jar" "$T/lib/jsr184_1.1.jar" \
             "$T/lib/jsr075_stub.jar"; do
        if [ -f "$j" ]; then
            LIBS="$LIBS --lib $j"
        fi
    done
    python3 "$CHECK" "$OUT/$NAME.jar" $LIBS
fi

echo "готово: $OUT и $ART"
ls -la "$OUT" "$ART"
