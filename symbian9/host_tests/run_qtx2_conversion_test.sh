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
qtx1_dir=${QUANTUM_QTX1_DIR:-"$repo_root/symbian9/level41_walk_test/output/v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32"}

if [ ! -d "$qtx2_dir" ]; then
    echo "QTX2 assets missing: $qtx2_dir" >&2
    echo "Run symbian9/tools/convert_qtx1_to_qtx2.py first." >&2
    exit 1
fi

"$build_dir/qtx2_conversion_test" "$qtx2_dir" "$qtx1_dir"
