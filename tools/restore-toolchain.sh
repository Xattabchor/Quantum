#!/bin/sh
# Restores the untracked CLDC build toolchain after a sandbox wipe:
#   build/tools/jdk4py (java-runtime), build/tools/package (ECJ 3.16.0),
#   build/sdk (j2me-build-tools: api jars + proguard)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK=/tmp/quantum-toolchain
mkdir -p "$WORK"
cd "$WORK"

if [ ! -f jdk4py.whl ]; then
    pip3 download jdk4py --no-deps -d "$WORK" -q
    mv "$WORK"/jdk4py-*.whl jdk4py.whl
fi
if [ ! -d jdk4py_x/jdk4py/java-runtime ]; then
    mkdir -p jdk4py_x
    unzip -q -o jdk4py.whl -d jdk4py_x
fi

if [ ! -f coc-java.tgz ]; then
    curl -sL -o coc-java.tgz https://registry.npmjs.org/coc-java/-/coc-java-1.0.0.tgz
fi

if [ ! -d j2me-build-tools/lib ]; then
    git clone -q --depth 1 https://github.com/vipaoL/j2me-build-tools
fi

mkdir -p "$ROOT/build/tools/jdk4py"
rm -rf "$ROOT/build/tools/jdk4py/jdk4py"
cp -r jdk4py_x/jdk4py "$ROOT/build/tools/jdk4py/"
tar xzf coc-java.tgz -C "$ROOT/build/tools/"
rm -rf "$ROOT/build/sdk"
cp -r j2me-build-tools "$ROOT/build/sdk"

ls "$ROOT/build/tools/jdk4py/jdk4py/java-runtime/bin/java" \
   "$ROOT/build/tools/package/server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar" \
   "$ROOT/build/sdk/lib/cldc11.jar" "$ROOT/build/sdk/proguard/lib/proguard.jar"
echo OK
