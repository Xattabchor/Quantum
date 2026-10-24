#!/bin/sh
# Загрузка тулчейна для сборки J2ME (CLDC-1.1 / MIDP-2.0) без WTK и без JDK.
#
# Нужны только curl, python3 и java (JRE достаточно: ECJ и ProGuard сами
# написаны на Java). JDK не нужен.
#
# Все банки берутся из публичных репозиториев GitHub, где они лежат в дереве:
#   phd051199/MIDPlay           (ветка main)   lib/cldc_1.1.jar, midp_2.0.jar,
#                                              jsr234_1.0.jar, proguard-ant.jar
#   alanvoss/neo4j_openshift_v2 (ветка master) usr/lib/java/ecj.jar  (ECJ 3.8.2)
#   vsenger/things-api          (ветка master) apps/Things.Mobile/res/
#                                              jsr184_1.1.jar, jsr135_1.2.jar
#
# Загрузка идёт через GitHub API: для файлов меньше 1 МиБ содержимое приходит
# сразу в ответе contents API (base64), для больших - через blob API с
# Accept: application/vnd.github.raw. Запасной путь - тарбол всего репозитория.
#
# Запуск:  T=~/tools/j2me JAVA=java sh get-toolchain.sh
set -e

T=${T:-/home/user/tools/j2me}
JAVA=${JAVA:-java}
DL=$T/dl
mkdir -p "$T/lib" "$DL"

fetch() {   # fetch <owner/repo> <branch> <путь в репозитории> <имя в lib>
    if [ -f "$T/lib/$4" ]; then
        echo "уже есть: $4"
        return 0
    fi
    echo "качаем $1:$3 -> $4"
    if python3 - "$1" "$2" "$3" "$T/lib/$4" <<'PY'
import base64, json, os, sys, urllib.request

repo, branch, path, out = sys.argv[1:5]
token = os.environ.get('GITHUB_TOKEN', '')
API = 'https://api.github.com'


def get(url, raw=False):
    req = urllib.request.Request(url)
    req.add_header('Accept', 'application/vnd.github.raw+json' if raw
                   else 'application/vnd.github+json')
    req.add_header('User-Agent', 'quantum-j2me-toolchain')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    return urllib.request.urlopen(req, timeout=120).read()


def ok(data):
    return len(data) > 100 and data[:2] == b'PK'


data = None
try:
    meta = json.loads(get('%s/repos/%s/contents/%s?ref=%s' % (API, repo, path, branch)))
    # файлы меньше 1 МиБ приходят сразу в base64
    if meta.get('encoding') == 'base64' and meta.get('content'):
        cand = base64.b64decode(meta['content'])
        if ok(cand):
            data = cand
    # иначе (или если содержимое не приложено) - blob API без ограничения в 1 МиБ
    if data is None and meta.get('sha'):
        cand = get('%s/repos/%s/git/blobs/%s' % (API, repo, meta['sha']), raw=True)
        if ok(cand):
            data = cand
except Exception as e:
    print('  GitHub API: %s' % e)

if data is None:
    sys.exit(3)          # вызывающий скрипт скачает тарбол

open(out, 'wb').write(data)
print('  -> %s (%d байт)' % (out, len(data)))
PY
    then
        return 0
    fi

    # запасной путь: тарбол всего репозитория
    echo "  API не отдал файл, качаем тарбол"
    tarball="$DL/$(echo "$1" | tr '/' '_').tgz"
    if [ ! -f "$tarball" ]; then
        curl -sSL -o "$tarball" "https://codeload.github.com/$1/tar.gz/refs/heads/$2"
    fi
    python3 - "$tarball" "$1" "$3" "$T/lib/$4" <<'PY'
import os, sys, tarfile, shutil
tarball, repo, path, out = sys.argv[1:5]
prefix = repo.split('/')[1] + '/'
with tarfile.open(tarball, 'r:gz') as t:
    for m in t:
        if m.name == prefix + path or m.name.endswith('/' + path):
            with t.extractfile(m) as src, open(out, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            print('  -> %s (%d байт)' % (out, os.path.getsize(out)))
            break
    else:
        raise SystemExit('не найден %s в %s' % (path, tarball))
PY
}

fetch phd051199/MIDPlay           main   lib/cldc_1.1.jar        cldc_1.1.jar
fetch phd051199/MIDPlay           main   lib/midp_2.0.jar        midp_2.0.jar
fetch phd051199/MIDPlay           main   lib/jsr234_1.0.jar      jsr234_1.0.jar
fetch phd051199/MIDPlay           main   lib/proguard-ant.jar    proguard-ant.jar
fetch alanvoss/neo4j_openshift_v2 master usr/lib/java/ecj.jar    ecj.jar
fetch vsenger/things-api          master apps/Things.Mobile/res/jsr184_1.1.jar jsr184_1.1.jar
fetch vsenger/things-api          master apps/Things.Mobile/res/jsr135_1.2.jar jsr135_1.2.jar

# Заглушка JSR-75 (FileConnection): в платформенных банках её нет, а
# LightMapper.java её импортирует. В JAR не попадает, нужна только компилятору.
mkdir -p "$T/stubsrc/javax/microedition/io/file" "$T/stubclasses"
cat > "$T/stubsrc/javax/microedition/io/file/FileConnection.java" <<'EOF'
package javax.microedition.io.file;

import java.io.IOException;
import javax.microedition.io.StreamConnection;

/**
 * Compile-time stub of JSR-75 FileConnection (only the members used by
 * code.Gameplay.Map.LightMapper). The real JSR-75 implementation is provided
 * by the device at runtime; this class is never packaged into the JAR.
 */
public interface FileConnection extends StreamConnection {
    boolean exists();
    void create() throws IOException;
}
EOF

"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -warn:none \
    -bootclasspath "$T/lib/cldc_1.1.jar:$T/lib/midp_2.0.jar:$T/lib/jsr184_1.1.jar" \
    -d "$T/stubclasses" \
    "$T/stubsrc/javax/microedition/io/file/FileConnection.java"

python3 - "$T" <<'PY'
import os, sys, zipfile
T = sys.argv[1]
jar = os.path.join(T, 'lib', 'jsr075_stub.jar')
z = zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED)
root = os.path.join(T, 'stubclasses')
for dirpath, dirs, files in os.walk(root):
    for f in files:
        p = os.path.join(dirpath, f)
        z.write(p, os.path.relpath(p, root))
z.close()
print('  -> %s (%d байт)' % (jar, os.path.getsize(jar)))
PY

# Заглушка Nokia UI API (com.nokia.mid.ui): нужна только компилятору для
# code/Rendering/NokiaBlitter.java (§24). Исходники стаба - в репозитории
# (docs/j2me-build/stubsrc-nokia), значения констант сверены с официальным
# javadoc Nokia UI API 1.1 и двумя независимыми эмуляторами. В JAR не
# попадает (bootclasspath / -libraryjars).
SRC=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$T/stubclasses-nokia"
"$JAVA" -cp "$T/lib/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.4 -target 1.4 -encoding UTF-8 -warn:none \
    -bootclasspath "$T/lib/cldc_1.1.jar:$T/lib/midp_2.0.jar" \
    -d "$T/stubclasses-nokia" \
    "$SRC/docs/j2me-build/stubsrc-nokia/com/nokia/mid/ui/DirectGraphics.java" \
    "$SRC/docs/j2me-build/stubsrc-nokia/com/nokia/mid/ui/DirectUtils.java"

python3 - "$T" <<'PY'
import os, sys, zipfile
T = sys.argv[1]
jar = os.path.join(T, 'lib', 'nokia_ui_stub.jar')
z = zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED)
root = os.path.join(T, 'stubclasses-nokia')
for dirpath, dirs, files in os.walk(root):
    for f in files:
        p = os.path.join(dirpath, f)
        z.write(p, os.path.relpath(p, root))
z.close()
print('  -> %s (%d байт)' % (jar, os.path.getsize(jar)))
PY

echo
echo "Готово. Содержимое $T/lib:"
ls -la "$T/lib"
