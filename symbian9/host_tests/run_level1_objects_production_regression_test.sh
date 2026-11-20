#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-level41-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/level1_objects_production_regression_test.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_animation.cpp" \
    "$repo_root/symbian9/src/core/software_level_objects.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_3d_parser.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_world_sprite_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_triangle_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_polygon_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_perspective_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_vertex_pipeline.cpp" \
    "$repo_root/symbian9/src/core/software_affine_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/render_mode_policy.cpp" \
    "$repo_root/symbian9/src/core/matrix.cpp" \
    "$repo_root/symbian9/src/core/vector3d.cpp" \
    "$repo_root/symbian9/src/core/math_utils.cpp" \
    "$repo_root/symbian9/src/core/math_utils2.cpp" \
    "$repo_root/symbian9/src/core/quantum_math.cpp" \
    "$repo_root/symbian9/src/core/qfps.cpp" \
    -o "$build_dir/level1_objects_production_regression_test"

"$build_dir/level1_objects_production_regression_test" \
    "$repo_root/symbian9/level41_device_test/assets/sym9-level1"
