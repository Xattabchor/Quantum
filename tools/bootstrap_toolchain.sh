#!/bin/sh
# Restores the ephemeral J2ME CLDC toolchain after a sandbox reset.
# Usage: sh tools/bootstrap_toolchain.sh
set -e
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
T=/tmp/quantum-toolchain
mkdir -p "$T" "$ROOT/build/tools" "$ROOT/build/sdk"
cd "$T"

# JDK 8 (OpenJDK 8u131 for linux x86_64). Preferred build JVM: ECJ 3.16 and
# ProGuard 6.2.2 are Java 8 era tools, and this is the combination the classic
# J2ME toolchains were validated with. Output is byte-identical to the jdk4py
# JDK 25 build, so either JVM produces the same JAR.
if [ ! -x "$ROOT/build/tools/jdk8/bin/java" ]; then
    [ -f tools_linux_jdk8.tar.gz ] || wget -q https://codeload.github.com/klaxons1/tools_linux_jdk8/tar.gz/refs/heads/master -O tools_linux_jdk8.tar.gz
    mkdir -p "$ROOT/build/tools/jdk8"
    tar xzf tools_linux_jdk8.tar.gz -C "$T"
    cp -r "$T"/tools_linux_jdk8-master/* "$ROOT/build/tools/jdk8/"
    chmod +x "$ROOT/build/tools/jdk8/bin/"* "$ROOT/build/tools/jdk8/jre/bin/"* 2>/dev/null || true
fi

# jdk4py runtime (Temurin 25): fallback build JVM.
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
# Submodule fixes this repository depends on. The upstream repos are read-only
# for us, so the changes are applied (idempotently) after checkout.
apply_patch() {
    repo="$1"
    patch_file="$2"
    [ -d "$ROOT/$repo" ] || return 0
    [ -f "$ROOT/$patch_file" ] || return 0
    if git -C "$ROOT/$repo" apply --check --reverse "$ROOT/$patch_file" >/dev/null 2>&1; then
        : # already applied
    elif git -C "$ROOT/$repo" apply --check "$ROOT/$patch_file" >/dev/null 2>&1; then
        git -C "$ROOT/$repo" apply "$ROOT/$patch_file"
        echo "applied $patch_file"
    else
        echo "WARNING: cannot apply $patch_file (check submodule state)" >&2
    fi
}
apply_patch Utils tools/patches/utils-sound.patch
apply_patch UtilsText tools/patches/utilstext-config.patch

echo "toolchain OK"
