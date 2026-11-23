#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=${TMPDIR:-/tmp}/quantum-pcm-one-audio-host-tests
compiler=${CXX:-g++}
mkdir -p "$build_dir"

"$compiler" -std=c++20 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -Wall -Wextra -Werror \
    "$repo_root/symbian9/host_tests/pcm_one_audio_test.cpp" \
    "$repo_root/symbian9/level41_pcm_audio/quantum_level41_pcm_core.cpp" \
    -o "$build_dir/pcm_one_audio_test"

# Feed it the real packaged effects when a build tree is present. They are
# the exact files the SIS installs, so this catches format drift.
assets=""
for candidate in \
    "$repo_root/symbian9/level41_device_test/assets" \
    "$repo_root/symbian9/level41_walk_test/audio"; do
    if [ -f "$candidate/sounds/wpn/fn2000.wav" ] && \
       [ -f "$candidate/sounds/wpn/r.wav" ]; then
        assets="$candidate/sounds/wpn/fn2000.wav $candidate/sounds/wpn/r.wav"
        break
    fi
done

if [ -z "$assets" ]; then
    assets=$(find "$repo_root/symbian9/level41_walk_test/output" \
        -name 'fn2000.wav' 2>/dev/null | head -1)
    other=$(find "$repo_root/symbian9/level41_walk_test/output" \
        -name 'r.wav' 2>/dev/null | head -1)
    if [ -n "$assets" ] && [ -n "$other" ]; then
        assets="$assets $other"
    else
        assets=""
    fi
fi

# shellcheck disable=SC2086
"$build_dir/pcm_one_audio_test" $assets
