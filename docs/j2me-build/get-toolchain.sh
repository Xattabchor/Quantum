#!/bin/sh
# Загрузка тулчейна для сборки J2ME (CLDC-1.1 / MIDP-2.0) без WTK и без JDK.
#
# Нужны только: curl и python3 (JDK не нужен - компилятор ECJ и препроверка
# ProGuard сами написаны на Java и работают на JRE).
#
# Все файлы берутся из публичных репозиториев GitHub, где они лежат в дереве:
#   phd051199/MIDPlay          (ветка main)   lib/cldc_1.1.jar, midp_2.0.jar,
#                                             jsr234_1.0.jar, proguard-ant.jar
#   alanvoss/neo4j_openshift_v2 (ветка master) usr/lib/java/ecj.jar  (ECJ 3.8.2)
#   vsenger/things-api          (ветка master) apps/Things.Mobile/res/
#                                             jsr184_1.1.jar, jsr135_1.2.jar
#
# Запуск:  T=/home/user/tools/j2me sh $T/get-toolchain.sh
set -e

T=${T:-/home/user/tools/j2me}
JAVA=${JAVA:-java}
DL=$T/dl
mkdir -p "$T/lib" "$DL"

api_get() {   # api_get <путь API> <куда сохранить>  (учитывает GITHUB_TOKEN)
    if [ -n "$GITHUB_TOKEN" ]; then
        curl -sSL -H "Authorization: Bearer $GITHUB_TOKEN" \
             -H "Accept: application/vnd.github.raw+json" "$1" -o "$2"
    else
        curl -sSL -H "Accept: application/vnd.github.raw+json" "$1" -o "$2"
    fi
}

fetch() {   # fetch <owner/repo> <branch> <путь в репозитории> <имя в lib>
    repo=$1; branch=$2; path=$3; out=$4
    if [ -f "$T/lib/$out" ]; then
        echo "уже есть: $out"
        return 0
    fi
    echo "качаем $repo:$path -> $out"

    # 1) sha файла через contents API
    meta="$DL/$(basename "$path").json"
    api_get "https://api.github.com/repos/$repo/contents/$path?ref=$branch" "$meta"
    sha=$(python3 -c "import json,sys;print(json.load(open('$meta'))['sha'])" 2>/dev/null || echo "")

    # 2) сам файл через blob API (без ограничения contents API в 1 МиБ)
    if [ -n "$sha" ]; then
        api_get "https://api.github.com/repos/$repo/git/blobs/$sha" "$T/lib/$out"
    fi
    if python3 -c "
import sys
b = open('$T/lib/$out', 'rb').read(4)
sys.exit(0 if b[:2] == b'PK' else 1)" 2>/dev/null; then
        rm -f "$meta"
        echo "  -> $T/lib/$out ($(wc -c < "$T/lib/$out") байт)"
        return 0
    fi

    # 3) запасной путь: tarball всего репозитория
    echo "  blob API не сработал, качаем тарбол"
    rm -f "$T/lib/$out"
    tarball="$DL/$(echo "$repo" | tr '/' '_').tgz"
    if [ ! -f "$tarball" ]; then
        curl -sSL -o "$tarball" \
            "https://codeload.github.com/$repo/tar.gz/refs/heads/$branch"
    fi
    python3 - "$tarball" "$repo" "$path" "$T/lib/$out" <<'PY'
import sys, tarfile, shutil
tarball, repo, path, out = sys.argv[1:5]
prefix = repo.split('/')[1] + '/'
with tarfile.open(tarball, 'r:gz') as t:
    for m in t:
        if m.name == prefix + path or m.name.endswith('/' + path):
            with t.extractfile(m) as src, open(out, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            print('  ->', out)
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

# Заглушка JSR-75 (FileConnection) - в платформенных банках её нет, а
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
print('  ->', jar)
PY

echo
echo "Готово. Содержимое $T/lib:"
ls -la "$T/lib"
