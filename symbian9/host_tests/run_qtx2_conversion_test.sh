#!/bin/sh
# QTX2 conformance: decode the build-time converted assets with the same C++
# reader the device uses, and prove the move from start-up conversion to
# build-time conversion did not change the picture.
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-level41-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O2 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/qtx2_conversion_test.cpp" \
    "$repo_root/symbian9/src/core/software_qtx2.cpp" \
    "$repo_root/symbian9/src/core/software_texture16.cpp" \
    "$repo_root/symbian9/src/core/quantum_math.cpp" \
    -o "$build_dir/qtx2_conversion_test"

qtx2_dir=${QUANTUM_QTX2_DIR:-"$repo_root/build-assets/level41-qtx2"}
# The shipped Level 41 payload is assembled from two places, so collect the
# exact 13 QTX1 sources the package carries into one directory. Point
# QUANTUM_QTX1_DIR at your own set to compare against something else.
qtx1_dir=${QUANTUM_QTX1_DIR:-"$build_dir/qtx1-sources"}
if [ -z "${QUANTUM_QTX1_DIR:-}" ]; then
    base="$repo_root/symbian9/level41_walk_test/output/v1.0.28-level1-import-drt-ten-found-omap1710-dsa-argb32"
    sym9="$repo_root/symbian9/level41_device_test/assets/sym9-level1"
    rm -rf "$qtx1_dir"
    mkdir -p "$qtx1_dir"
    for name in map.qtx river.qtx sky.qtx weapon.qtx weapon-fire.qtx \
                weapon-sight-weapon.qtx; do
        cp "$base/$name" "$qtx1_dir/$name"
    done
    for name in forest.qtx river0.qtx river1.qtx river2.qtx river3.qtx; do
        cp "$sym9/qtx/$name" "$qtx1_dir/$name"
    done
    cp "$sym9/prop/mdy.qtx" "$sym9/prop/skl.qtx" "$qtx1_dir/"
fi

if [ ! -d "$qtx2_dir" ]; then
    echo "QTX2 assets missing: $qtx2_dir" >&2
    echo "Run symbian9/tools/convert_qtx1_to_qtx2.py first." >&2
    exit 1
fi

"$build_dir/qtx2_conversion_test" "$qtx2_dir" "$qtx1_dir"
