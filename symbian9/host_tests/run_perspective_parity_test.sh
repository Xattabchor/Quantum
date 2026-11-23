#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-perspective-parity
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra -Werror \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/perspective_parity_test.cpp" \
    "$repo_root/symbian9/src/core/software_perspective_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_affine_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_fast_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_texture16.cpp" \
    "$repo_root/symbian9/src/core/qfps.cpp" \
    -o "$build_dir/perspective_parity_test"

"$build_dir/perspective_parity_test"
