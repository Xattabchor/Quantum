#!/bin/sh
# Restores the ephemeral J2ME CLDC toolchain after a sandbox reset.
# Usage: sh tools/bootstrap_toolchain.sh
set -e
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
T=/tmp/quantum-toolchain
mkdir -p "$T" "$ROOT/build/tools" "$ROOT/build/sdk"
cd "$T"

# jdk4py runtime (Temurin 25) as the build JVM.
if [ ! -x "$ROOT/build/tools/jdk4py/jdk4py/java-runtime/bin/java" ]; then
    pip download jdk4py==25.0.2.1 -q -d "$T"
    unzip -q -o "$T"/jdk4py-25.0.2.1-*.whl "jdk4py/*" -d "$T/jv"
    mkdir -p "$ROOT/build/tools/jdk4py"
    cp -r "$T/jv/jdk4py" "$ROOT/build/tools/jdk4py/"
    chmod +x "$ROOT/build/tools/jdk4py/jdk4py/java-runtime/bin/java"
fi

# ECJ 3.16.0 (embedded in the coc-java npm bundle).
if [ ! -f "$ROOT/build/tools/package/server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar" ]; then
    [ -f coc-java-1.0.0.tgz ] || wget -q https://registry.npmjs.org/coc-java/-/coc-java-1.0.0.tgz
    mkdir -p "$ROOT/build/tools/package/server/plugins"
    tar xzf coc-java-1.0.0.tgz -C "$ROOT/build/tools" \
        package/server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar
fi

# CLDC/MIDP/JSR api jars + ProGuard (vipaoL/j2me-build-tools).
if [ ! -f "$ROOT/build/sdk/proguard/lib/proguard.jar" ]; then
    [ -f j2bt.zip ] || wget -q https://codeload.github.com/vipaoL/j2me-build-tools/zip/refs/heads/master -O j2bt.zip
    unzip -q -o j2bt.zip
    rm -rf "$ROOT/build/sdk/lib" "$ROOT/build/sdk/proguard"
    mv j2me-build-tools-master/lib "$ROOT/build/sdk/lib"
    mv j2me-build-tools-master/proguard "$ROOT/build/sdk/proguard"
fi
echo "toolchain OK"
