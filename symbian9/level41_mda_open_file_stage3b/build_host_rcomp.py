#!/usr/bin/env python3
"""Build the SDK-compatible host rcomp/uidcrc tools from pinned local sources."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import tempfile
from pathlib import Path

CACHE = Path.home() / "quantum-sdk-cache"
RCOMP_COMMIT = "d3c2eadd3ff7826bdf9e1d92f447c357571af18b"
SYMBIAN_COMMIT = "b105f752f84d1b5640052f64c4a703017810d7b8"
ABSEIL_COMMIT = "5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a"
RCOMP_SOURCES = [
    "main.cpp", "ARRAY.CPP", "ASTRING.CPP", "CCODES.CPP", "CTABLE.CPP",
    "DATATYPE.CPP", "ERRORHAN.CPP", "FILEACC.CPP", "FILELINE.CPP",
    "INDEXTAB.CPP", "LINKARRA.CPP", "LINKLIST.CPP", "MEM.CPP",
    "NAMEIDMA.CPP", "NUMVAL.CPP", "QUALIFAR.CPP", "RCBINSTR.CPP",
    "RCOSTRM.CPP", "RCSCAN.CPP", "RCSTACK.CPP", "RESOURCE.CPP",
    "STACK.CPP", "STRINGAR.CPP", "STRUCTST.CPP", "UNICODE_COMPRESSOR.CPP",
    "localise.cpp", "messages.cpp", "rcomp.cpp", "rcompl.cpp",
]


def command_output(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def revision(repository: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        raise SystemExit(f"Not a git checkout: {repository}\n{result.stderr}")
    return result.stdout.strip()


def apply_modern_host_patch(repository: Path, patch: Path) -> None:
    reverse = command_output(["git", "-C", str(repository), "apply", "--reverse", "--check", str(patch)])
    if reverse.returncode == 0:
        return
    forward = command_output(["git", "-C", str(repository), "apply", "--check", str(patch)])
    if forward.returncode:
        raise SystemExit(
            "The pinned rcomp source is neither clean nor already patched.\n"
            f"Patch check: {forward.stderr}"
        )
    applied = command_output(["git", "-C", str(repository), "apply", str(patch)])
    if applied.returncode:
        raise SystemExit(f"Could not apply the pinned modern-host patch:\n{applied.stderr}")


def build(args: argparse.Namespace) -> None:
    repository = args.rcomp_source.resolve()
    symbian = args.symbian_source.resolve()
    abseil = args.abseil_source.resolve()
    output = args.output.resolve()
    rcomp_source = repository / "bintools/rcomp"
    resource_source = symbian / "cpp/symbian/resource/uidcrc_main.cc"
    patch = symbian / "research/rcomp/modern-host.patch"
    compiler = shutil.which("g++")
    if compiler is None:
        raise SystemExit("A host g++ compiler is required")
    if revision(repository) != RCOMP_COMMIT:
        raise SystemExit(f"Unexpected rcomp checkout: expected {RCOMP_COMMIT}, got {revision(repository)}")
    if revision(symbian) != SYMBIAN_COMMIT:
        raise SystemExit(f"Unexpected Symbian source checkout: expected {SYMBIAN_COMMIT}, got {revision(symbian)}")
    if revision(abseil) != ABSEIL_COMMIT:
        raise SystemExit(f"Unexpected Abseil checkout: expected {ABSEIL_COMMIT}, got {revision(abseil)}")
    if not patch.is_file() or not resource_source.is_file():
        raise SystemExit("Pinned host patch or uidcrc source is missing")
    for source in RCOMP_SOURCES:
        if not (rcomp_source / "src" / source).is_file():
            raise SystemExit(f"Pinned rcomp source file is missing: {source}")
    apply_modern_host_patch(repository, patch)

    compat = symbian / "research/rcomp/compat"
    include = rcomp_source / "inc"
    source_dir = rcomp_source / "src"
    output.mkdir(parents=True, exist_ok=True)
    rcomp_output = output / "rcomp"
    uidcrc_output = output / "uidcrc"
    rcomp_command = [
        compiler, "-std=gnu++14", "-O2", "-w", "-fpermissive",
        "-D__LINUX__", "-D__TOOLS2__",
        f"-I{compat}", f"-I{include}", f"-I{source_dir}",
        *(str(source_dir / source) for source in RCOMP_SOURCES),
        "-o", str(rcomp_output),
    ]
    built_rcomp = subprocess.run(rcomp_command, check=False)
    if built_rcomp.returncode:
        raise SystemExit(f"rcomp build failed with exit code {built_rcomp.returncode}")

    uidcrc_command = [
        compiler, "-std=c++20", "-O2", f"-I{symbian / 'cpp'}", f"-I{abseil}",
        str(resource_source), "-o", str(uidcrc_output),
    ]
    built_uidcrc = subprocess.run(uidcrc_command, check=False)
    if built_uidcrc.returncode:
        raise SystemExit(f"uidcrc build failed with exit code {built_uidcrc.returncode}")

    version = command_output([str(rcomp_output)])
    version_output = version.stdout + version.stderr
    if "Resource compiler version 8.2" not in version_output:
        raise SystemExit(f"Built rcomp did not report version 8.2:\n{version_output}")
    with tempfile.TemporaryDirectory(prefix="uidcrc-smoke-") as temporary:
        result_path = Path(temporary) / "uid-header.bin"
        smoke = subprocess.run(
            [str(uidcrc_output), "0x101f4a6b", "0x101f8021", "0xe000a02e", str(result_path)],
            check=False,
        )
        if smoke.returncode or not result_path.is_file() or result_path.stat().st_size != 16:
            raise SystemExit("Built uidcrc failed its 16-byte resource-header smoke test")

    for binary in (rcomp_output, uidcrc_output):
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        print(f"{binary}: {digest}")
    print(f"rcomp source: {RCOMP_COMMIT}; symbian-platform/uidcrc: {SYMBIAN_COMMIT}; Abseil: {ABSEIL_COMMIT}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rcomp-source", type=Path, default=CACHE / "rcomp-src")
    parser.add_argument("--symbian-source", type=Path, default=CACHE / "upstream-v030")
    parser.add_argument("--abseil-source", type=Path, default=CACHE / "abseil-cpp")
    parser.add_argument("--output", type=Path, default=CACHE / "host-tools/bin")
    build(parser.parse_args())


if __name__ == "__main__":
    main()
