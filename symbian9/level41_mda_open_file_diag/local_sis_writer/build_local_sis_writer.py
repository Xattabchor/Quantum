#!/usr/bin/env python3
"""Build the small stage-3 SIS/SISX helper against local SDK source pins."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
from pathlib import Path

UPSTREAM_COMMIT = "b105f752f84d1b5640052f64c4a703017810d7b8"
ABSEIL_COMMIT = "5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a"
CACHE = Path.home() / "quantum-sdk-cache"
PATCH_FILE = (
    Path(__file__).resolve().parents[2]
    / "level41_walk_test/local_sis_writer_2m_heap.patch"
)
E32_1M_INSPECT_CHECK = """      Read32(bytes, 128) != uid3 || Read32(bytes, 24) != 0x00010000 ||
      Read32(bytes, 56) != 0x1000 || Read32(bytes, 60) != 0x100000 ||
      Read32(bytes, 64) != 0x10000) {"""
E32_1M_OR_2M_INSPECT_CHECK = """      Read32(bytes, 128) != uid3 || Read32(bytes, 24) != 0x00010000 ||
      Read32(bytes, 56) != 0x1000 ||
      (Read32(bytes, 60) != 0x100000 && Read32(bytes, 60) != 0x200000) ||
      Read32(bytes, 64) != 0x10000) {"""


def prepare_e32_parser(source: Path, destination: Path) -> tuple[str, str]:
    """Copy the pinned parser and allow the separate 2 MiB candidate heap."""
    if not PATCH_FILE.is_file():
        raise SystemExit(f"The local 2 MiB writer patch is missing: {PATCH_FILE}")
    original = source.read_text(encoding="utf-8")
    if original.count(E32_1M_INSPECT_CHECK) == 1:
        patched = original.replace(E32_1M_INSPECT_CHECK, E32_1M_OR_2M_INSPECT_CHECK)
    elif original.count(E32_1M_OR_2M_INSPECT_CHECK) == 1:
        patched = original
    else:
        raise SystemExit(
            "The pinned E32 parser layout changed; refusing to apply an ambiguous "
            "2 MiB heap compatibility patch"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(patched, encoding="utf-8")
    return (
        hashlib.sha256(patched.encode("utf-8")).hexdigest(),
        hashlib.sha256(PATCH_FILE.read_bytes()).hexdigest(),
    )


def revision(source: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()


def host_library(name: str) -> Path:
    suffixes = ("x86_64-linux-gnu", "aarch64-linux-gnu", "", "lib64")
    for prefix in (Path("/usr/lib"), Path("/lib")):
        for suffix in suffixes:
            directory = prefix / suffix if suffix else prefix
            for versioned in (f"lib{name}.so.3", f"lib{name}.so.1"):
                candidate = directory / versioned
                if candidate.is_file():
                    return candidate
    raise SystemExit(f"Could not find a system shared library for {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, default=CACHE / "symbian-sdk")
    parser.add_argument("--upstream-source", type=Path, default=CACHE / "upstream-v030")
    parser.add_argument("--abseil-source", type=Path, default=CACHE / "abseil-cpp")
    parser.add_argument("--build-dir", type=Path, default=CACHE / "stage3-sis-writer/build")
    parser.add_argument("--cmake", type=Path, help="Host CMake override")
    parser.add_argument("--ninja", type=Path, help="Host Ninja override")
    parser.add_argument("--output", type=Path, default=CACHE / "stage3-sis-writer/build/stage3_sis_writer")
    parser.add_argument("--openssl-include", type=Path)
    parser.add_argument("--openssl-crypto", type=Path)
    parser.add_argument("--zlib-include", type=Path)
    parser.add_argument("--zlib-library", type=Path)
    args = parser.parse_args()

    sdk = args.sdk_root.resolve()
    upstream = args.upstream_source.resolve()
    abseil = args.abseil_source.resolve()
    build = args.build_dir.resolve()
    output = args.output.resolve()
    version_file = sdk / "host/share/symbian/VERSION"
    if not version_file.is_file() or version_file.read_text().strip() != "0.3.0":
        raise SystemExit(f"Expected Symbian SDK 0.3.0 at {sdk}")
    if revision(upstream) != UPSTREAM_COMMIT:
        raise SystemExit(f"Unexpected upstream source revision in {upstream}")
    if revision(abseil) != ABSEIL_COMMIT:
        raise SystemExit(f"Unexpected Abseil source revision in {abseil}")

    patched_e32_source = build / "patched-source/e32.cc"
    patched_e32_sha256, patch_sha256 = prepare_e32_parser(
        upstream / "cpp/symbian/e32/e32.cc", patched_e32_source
    )

    cmake = (args.cmake or sdk / "bin/cmake").resolve()
    ninja = (args.ninja or sdk / "bin/ninja").resolve()
    compiler = shutil.which("g++")
    if not cmake.is_file() or not ninja.is_file() or compiler is None:
        raise SystemExit("CMake, Ninja, and a host g++ compiler are required")
    openssl_include = (args.openssl_include or sdk / "host/include").resolve()
    zlib_include = (args.zlib_include or sdk / "include/portable/zlib").resolve()
    openssl_crypto = (args.openssl_crypto or host_library("crypto")).resolve()
    zlib_library = (args.zlib_library or host_library("z")).resolve()
    if not (openssl_include / "openssl/evp.h").is_file():
        raise SystemExit(f"OpenSSL headers not found under {openssl_include}")
    if not (zlib_include / "zlib.h").is_file():
        raise SystemExit(f"zlib headers not found under {zlib_include}")

    source = Path(__file__).resolve().parent
    configure = [
        str(cmake),
        "--fresh",
        "-S",
        str(source),
        "-B",
        str(build),
        "-G",
        "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DCMAKE_CXX_COMPILER={compiler}",
        f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DSYMBIAN_UPSTREAM_SOURCE_DIR={upstream}",
        f"-DSYMBIAN_E32_SOURCE={patched_e32_source}",
        f"-DABSEIL_SOURCE_DIR={abseil}",
        f"-DOPENSSL_INCLUDE_DIR={openssl_include}",
        f"-DOPENSSL_CRYPTO_LIBRARY={openssl_crypto}",
        f"-DZLIB_INCLUDE_DIR={zlib_include}",
        f"-DZLIB_LIBRARY={zlib_library}",
    ]
    subprocess.run(configure, check=True)
    subprocess.run(
        [str(cmake), "--build", str(build), "--target", "stage3_sis_writer", "-j2"],
        check=True,
    )
    built = build / "stage3_sis_writer"
    if not built.is_file():
        raise SystemExit(f"Build did not produce {built}")
    if output != built:
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built, output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"Built local SIS writer: {output}")
    print(f"SHA-256: {digest}")
    print(f"SDK: 0.3.0; upstream: {UPSTREAM_COMMIT}; Abseil: {ABSEIL_COMMIT}")
    print("E32 inspector accepts the pinned 1 MiB profile and separate 2 MiB candidate profile")
    print(f"Patched E32 source SHA-256: {patched_e32_sha256}")
    print(f"Local writer patch SHA-256: {patch_sha256}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
