#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-level41-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O2 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/fast_rasterizer_test.cpp" \
    "$repo_root/symbian9/src/core/software_texture16.cpp" \
    "$repo_root/symbian9/src/core/software_fast_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_qct_texture_decoder.cpp" \
    "$repo_root/symbian9/src/core/software_affine_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_perspective_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/render_mode_policy.cpp" \
    "$repo_root/symbian9/src/core/matrix.cpp" \
    "$repo_root/symbian9/src/core/vector3d.cpp" \
    "$repo_root/symbian9/src/core/math_utils.cpp" \
    "$repo_root/symbian9/src/core/math_utils2.cpp" \
    "$repo_root/symbian9/src/core/quantum_math.cpp" \
    "$repo_root/symbian9/src/core/qfps.cpp" \
    -o "$build_dir/fast_rasterizer_test"

"$build_dir/fast_rasterizer_test" \
    "$repo_root/symbian9/level41_device_test/assets/sym9-level1"
