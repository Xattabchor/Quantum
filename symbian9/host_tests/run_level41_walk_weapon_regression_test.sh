#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-level41-host-tests
compiler=${CXX:-g++}
case ${1:-} in
    "")
        set -- -DQUANTUM_LEVEL41_TURN_INERTIA=1
        variant=turn-inertia
        ;;
    --disable-weapon-sway)
        set -- -DQUANTUM_LEVEL41_TURN_INERTIA=1 \
            -DQUANTUM_LEVEL41_DISABLE_WEAPON_SWAY=1
        variant=turn-inertia-no-sway
        ;;
    --sis33-baseline)
        set --
        variant=sis33-baseline
        ;;
    --sis33-baseline-no-sway)
        set -- -DQUANTUM_LEVEL41_DISABLE_WEAPON_SWAY=1
        variant=sis33-baseline-no-sway
        ;;
    *)
        echo "usage: $0 [--disable-weapon-sway | --sis33-baseline | --sis33-baseline-no-sway]" >&2
        exit 2
        ;;
esac
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra \
    "$@" \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/level41_walk_weapon_regression_test.cpp" \
    "$repo_root/symbian9/src/core/software_walk_controller.cpp" \
    "$repo_root/symbian9/src/core/software_weapon_controller.cpp" \
    "$repo_root/symbian9/src/core/software_weapon_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_rain_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_3d_parser.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_world_sprite_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_mesh_animation.cpp" \
    "$repo_root/symbian9/src/core/software_triangle_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_texture16.cpp" \
    "$repo_root/symbian9/src/core/software_fast_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_polygon_renderer.cpp" \
    "$repo_root/symbian9/src/core/software_perspective_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/software_vertex_pipeline.cpp" \
    "$repo_root/symbian9/src/core/software_affine_rasterizer.cpp" \
    "$repo_root/symbian9/src/core/render_mode_policy.cpp" \
    "$repo_root/symbian9/src/core/collision_height_computer.cpp" \
    "$repo_root/symbian9/src/core/sphere_cast.cpp" \
    "$repo_root/symbian9/src/core/matrix.cpp" \
    "$repo_root/symbian9/src/core/vector3d.cpp" \
    "$repo_root/symbian9/src/core/math_utils.cpp" \
    "$repo_root/symbian9/src/core/math_utils2.cpp" \
    "$repo_root/symbian9/src/core/quantum_math.cpp" \
    "$repo_root/symbian9/src/core/qfps.cpp" \
    -o "$build_dir/level41_walk_weapon_regression_test-$variant"

"$build_dir/level41_walk_weapon_regression_test-$variant"
"$repo_root/symbian9/host_tests/run_world_objects_rendering_regression_test.sh"
