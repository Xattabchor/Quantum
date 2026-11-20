#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-snddma-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra -Werror \
    "$repo_root/symbian9/host_tests/snddma_mixer_test.cpp" \
    "$repo_root/symbian9/level41_snddma_port/quantum_snddma_mixer.cpp" \
    -o "$build_dir/snddma_mixer_test"

"$build_dir/snddma_mixer_test"
