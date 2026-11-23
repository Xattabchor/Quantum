#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-level41-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra \
    -I"$repo_root/symbian9/inc" \
    "$repo_root/symbian9/host_tests/level41_weapon_selection_test.cpp" \
    -o "$build_dir/level41_weapon_selection_test"

"$build_dir/level41_weapon_selection_test"
