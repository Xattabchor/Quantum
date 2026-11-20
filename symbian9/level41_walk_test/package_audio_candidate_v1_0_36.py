#!/usr/bin/env python3
"""Package the v1.0.36 Level 1 walk + audio candidate with the required 2 MiB E32 heap."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import struct
import tempfile
from pathlib import Path

CACHE = Path.home() / "quantum-sdk-cache"
PROJECT = Path(__file__).resolve().parent
BASE_DIR = PROJECT / "output/v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32"
BASE_SIS = BASE_DIR / "quantum_level41_walk_test-v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32-armv5t.sis"
PACKAGE_UID = 0xE000A021
EXECUTABLE_UID3 = 0xE000A020
PACKAGE_NAME = "Quantum Level 41 Walk"
VENDOR = "Quantum native port"
EXECUTABLE_NAME = "quantum_level41_walk_test.exe"
VERSION = (1, 0, 36)
LEVEL1_SKYBOX_SHA1 = "c0df6cc5063fee145643a7862f7fd8e177045956"
LEVEL1_SKY_TEXTURE_SHA1 = "b6177d4d8812bb13514f66a84885015dcec9c072"
UPSTREAM_COMMIT = "b105f752f84d1b5640052f64c4a703017810d7b8"
ABSEIL_COMMIT = "5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a"
RCOMP_COMMIT = "d3c2eadd3ff7826bdf9e1d92f447c357571af18b"

REGISTRATION_TARGET = r"!:\private\10003a3f\import\apps\quantum_level41_walk_test_reg.rsc"
CAPTION_TARGET = r"!:\resource\apps\quantum_level41_walk_test_loc.rsc"
DATA_FILES = (
    ("L3D_new.3d", r"!:\private\e000a020\level41\L3D_new.3d"),
    ("lightmap.vla", r"!:\private\e000a020\level41\lightmap.vla"),
    ("skybox8.3d", r"!:\private\e000a020\level41\skybox8.3d"),
    ("forest.qtx", r"!:\private\e000a020\level41\forest.qtx"),
    ("map.qtx", r"!:\private\e000a020\level41\map.qtx"),
    ("river.qtx", r"!:\private\e000a020\level41\river.qtx"),
    ("sky.qtx", r"!:\private\e000a020\level41\sky.qtx"),
    ("weather.txt", r"!:\private\e000a020\level41\weather.txt"),
    ("weapon.qtx", r"!:\private\e000a020\level41\weapon.qtx"),
    ("weapon-fire.qtx", r"!:\private\e000a020\level41\weapon-fire.qtx"),
    ("weapon-sight-weapon.qtx", r"!:\private\e000a020\level41\weapon-sight-weapon.qtx"),
    ("weapon-gameplay.qwg", r"!:\private\e000a020\level41\weapon-gameplay.qwg"),
    ("weapon-catalog.qwc", r"!:\private\e000a020\level41\weapon-catalog.qwc"),
    ("audio/music/wth.mp3", r"!:\private\e000a020\audio\music\wth.mp3"),
    ("audio/sounds/wpn/fn2000.wav", r"!:\private\e000a020\audio\sounds\wpn\fn2000.wav"),
    ("audio/sounds/wpn/r.wav", r"!:\private\e000a020\audio\sounds\wpn\r.wav"),
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


E32_HEAP_MIN = 4096
E32_HEAP_MAX = 2 * 1024 * 1024
E32_HEAP_MAX_V1_0_35 = 1024 * 1024


def e32_header_crc(data: bytes) -> int:
    header_size = struct.unpack_from("<I", data, 100)[0]
    if header_size < 124 or header_size > len(data):
        raise SystemExit(f"Invalid E32 header size: {header_size}")
    crc = 0
    for index in range(header_size):
        byte = (
            (0xC90FDAA2 >> ((index - 20) * 8)) & 0xFF
            if 20 <= index < 24
            else data[index]
        )
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0xEDB88320 if crc & 1 else 0)
    return crc


def patch_e32_heap(data: bytes) -> tuple[bytes, dict[str, int | str]]:
    """Raise this E32 image's maximum heap to the walk target's 2 MiB budget."""
    if len(data) < 156 or data[16:20] != b"EPOC":
        raise SystemExit("The executable is not a supported E32 V image")
    header_size = struct.unpack_from("<I", data, 100)[0]
    if header_size < 156 or header_size > len(data):
        raise SystemExit(f"Invalid E32 header size: {header_size}")
    old_min = struct.unpack_from("<I", data, 56)[0]
    old_max = struct.unpack_from("<I", data, 60)[0]
    old_crc = struct.unpack_from("<I", data, 20)[0]
    if old_min != E32_HEAP_MIN or old_max not in (
        E32_HEAP_MAX_V1_0_35, E32_HEAP_MAX
    ):
        raise SystemExit(
            f"Unexpected E32 heap bounds {old_min}..{old_max}; refusing to patch"
        )
    if e32_header_crc(data) != old_crc:
        raise SystemExit("Input E32 header CRC is invalid")
    patched = bytearray(data)
    struct.pack_into("<I", patched, 60, E32_HEAP_MAX)
    new_crc = e32_header_crc(patched)
    struct.pack_into("<I", patched, 20, new_crc)
    result = bytes(patched)
    if e32_header_crc(result) != struct.unpack_from("<I", result, 20)[0]:
        raise SystemExit("Patched E32 header CRC did not validate")
    return result, {
        "heap_min": E32_HEAP_MIN,
        "heap_max_before": old_max,
        "heap_max": E32_HEAP_MAX,
        "header_size": header_size,
        "header_crc_before": f"0x{old_crc:08x}",
        "header_crc_after": f"0x{new_crc:08x}",
    }


def parse_writer_report(text: str) -> tuple[dict[str, str], list[dict[str, object]]]:
    fields: dict[str, str] = {}
    files: list[dict[str, object]] = []
    for line in text.splitlines():
        if "\t" in line:
            target, size, sha1 = line.split("\t")
            files.append({"target": target, "size": int(size), "sha1": sha1})
        elif "=" in line:
            key, value = line.split("=", 1)
            fields[key] = value
    return fields, files


def check_certificate(certificate: Path, private_key: Path) -> dict[str, object]:
    dates_and_names = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-noout", "-subject", "-issuer", "-dates"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    detail = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-noout", "-text"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    der = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-outform", "DER"],
        capture_output=True,
        check=True,
    ).stdout
    key_check = subprocess.run(
        ["openssl", "rsa", "-in", str(private_key), "-check", "-noout"],
        capture_output=True,
        text=True,
        check=False,
    )
    bits_match = re.search(r"Public-Key: \((\d+) bit\)", detail)
    sig_match = re.search(r"Signature Algorithm: ([^\n]+)", detail)
    fields = dict(line.split("=", 1) for line in dates_and_names.splitlines() if "=" in line)
    subject = fields.get("subject", "")
    issuer = fields.get("issuer", "")
    not_before = fields.get("notBefore", "")
    not_after = fields.get("notAfter", "")
    bits = int(bits_match.group(1)) if bits_match else 0
    signature_algorithm = sig_match.group(1).strip() if sig_match else ""
    if (
        key_check.returncode != 0
        or not subject
        or subject != issuer
        or bits != 1024
        or signature_algorithm != "sha1WithRSAEncryption"
        or not not_before.endswith("2000 GMT")
        or not not_after.endswith("2045 GMT")
    ):
        raise SystemExit(
            "Signing material must match the E65 test profile: self-signed RSA-1024/SHA-1, "
            f"2000-2045.\n{dates_and_names}\n{detail[:500]}\n{key_check.stderr}"
        )
    return {
        "subject": subject,
        "issuer": issuer,
        "self_signed": True,
        "not_before": not_before,
        "not_after": not_after,
        "public_key_algorithm": "RSA",
        "public_key_bits": bits,
        "certificate_signature_algorithm": signature_algorithm,
        "certificate_der_sha256": sha256(der),
        "private_key_embedded": False,
    }


def run_writer(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise SystemExit(
            "Local SIS writer failed.\n"
            f"Command: {' '.join(command)}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result.stdout


def build(args: argparse.Namespace) -> dict[str, object]:
    artifact = args.artifact.resolve()
    writer = args.sis_writer.resolve()
    certificate = args.certificate.resolve()
    private_key = args.private_key.resolve()
    output = args.output.resolve()
    audit_output = args.audit_output.resolve() if args.audit_output else output.with_name(output.stem + "-audit.json")
    if not artifact.is_file():
        raise SystemExit(f"E32 executable not found: {artifact}")
    if not writer.is_file():
        raise SystemExit(f"Local SIS writer not found: {writer}")
    if not certificate.is_file() or not private_key.is_file():
        raise SystemExit("Certificate or temporary private key is missing")
    if not BASE_SIS.is_file():
        raise SystemExit(f"The preserved v1.0.33 baseline SIS is missing: {BASE_SIS}")
    if output.exists() and not args.force:
        raise SystemExit(f"Refusing to overwrite existing candidate: {output} (use --force explicitly)")

    input_files: list[tuple[Path, str]] = []
    for name, target in DATA_FILES:
        path = BASE_DIR / name
        if not path.is_file():
            raise SystemExit(f"Missing Level 1/audio payload: {path}")
        input_files.append((path, target))
    input_files.sort(key=lambda item: item[1].casefold())

    # Keep the exact Level 1 sky assets from the last working walk package.
    # skybox8.3d is the renamed archive skies/sky.3d mesh (25 vertices,
    # 20 polygons); sky.qtx is the Level 1 daytime texture derived from
    # skies/skuy.png. Never substitute the main Level 41 skybox/skybox5-2.jpg.
    level1_sky_mesh = (BASE_DIR / "skybox8.3d").read_bytes()
    level1_sky_texture = (BASE_DIR / "sky.qtx").read_bytes()
    if (
        hashlib.sha1(level1_sky_mesh).hexdigest() != LEVEL1_SKYBOX_SHA1
        or level1_sky_mesh[:6] != b"\x00\x00\x00\x01\x00\x19"
        or hashlib.sha1(level1_sky_texture).hexdigest() != LEVEL1_SKY_TEXTURE_SHA1
    ):
        raise SystemExit(
            "The preserved Level 1 sky.3d/sky.qtx payloads do not match the "
            "last working walk test; refusing to package the main sky assets."
        )

    registration = BASE_DIR / "quantum_level41_walk_test_reg.rsc"
    caption = BASE_DIR / "quantum_level41_walk_test_loc.rsc"
    if not registration.is_file() or not caption.is_file():
        raise SystemExit("The v1.0.33 application registration resources are missing")

    certificate_info = check_certificate(certificate, private_key)
    original_executable_bytes = artifact.read_bytes()
    executable_bytes, heap_patch = patch_e32_heap(original_executable_bytes)
    output.parent.mkdir(parents=True, exist_ok=True)
    writer_succeeded = False
    try:
        with tempfile.TemporaryDirectory(prefix="level1-audio-sis-") as temporary:
            temporary_dir = Path(temporary)
            unsigned_path = temporary_dir / "candidate.sis"
            signed_temp_path = temporary_dir / "candidate.sisx"
            patched_executable = temporary_dir / EXECUTABLE_NAME
            patched_executable.write_bytes(executable_bytes)
            command = [
                str(writer),
                "--exe", str(patched_executable),
                "--registration", str(registration),
                "--registration-target", REGISTRATION_TARGET,
                "--caption", str(caption),
                "--caption-target", CAPTION_TARGET,
                "--package-uid", hex(PACKAGE_UID),
                "--package-name", PACKAGE_NAME,
                "--vendor", VENDOR,
                "--executable-name", EXECUTABLE_NAME,
                "--version", ".".join(map(str, VERSION)),
                "--certificate", str(certificate),
                "--private-key", str(private_key),
                "--unsigned-output", str(unsigned_path),
                "--output", str(signed_temp_path),
            ]
            for path, target in input_files:
                command.extend(("--data-file", str(path), "--data-target", target))
            stdout = run_writer(command)
            writer_succeeded = True
            fields, embedded_files = parse_writer_report(stdout)
            unsigned_bytes = unsigned_path.read_bytes()
            signed_bytes = signed_temp_path.read_bytes()
            roundtrip_executable = temporary_dir / "roundtrip.exe"
            run_writer([
                str(writer), "--extract-exe", str(signed_temp_path),
                str(roundtrip_executable),
            ])
            if roundtrip_executable.read_bytes() != executable_bytes:
                raise SystemExit("Signed package E32 payload differs from the patched executable")

            imports = fields.get("e32_imports", "")
            mda_imports = []
            midi_imports = []
            all_imports = []
            for item in imports.split(","):
                if not item:
                    continue
                dll, ordinal_text = item.rsplit(":", 1)
                ordinal = int(ordinal_text)
                all_imports.append({"dll": dll, "ordinal": ordinal})
                if dll.lower() == "mediaclientaudio.dll":
                    mda_imports.append(ordinal)
                if "midi" in dll.lower():
                    midi_imports.append({"dll": dll, "ordinal": ordinal})

            expected_targets = [
                rf"!:\sys\bin\{EXECUTABLE_NAME}",
                REGISTRATION_TARGET,
                CAPTION_TARGET,
                *(target for _, target in input_files),
            ]
            expected_embedded = []
            for path, target in input_files:
                payload = path.read_bytes()
                expected_embedded.append(
                    {"target": target, "size": len(payload), "sha1": hashlib.sha1(payload).hexdigest()}
                )
            actual_data_files = embedded_files[3:]
            if (
                fields.get("writer") != "stage3-mda-openfile-v1"
                or fields.get("upstream_commit") != UPSTREAM_COMMIT
                or fields.get("signed") != "true"
                or fields.get("registered") != "true"
                or int(fields.get("package_uid", "0")) != PACKAGE_UID
                or fields.get("package_name") != PACKAGE_NAME
                or fields.get("vendor") != VENDOR
                or fields.get("executable_name") != EXECUTABLE_NAME
                or int(fields.get("executable_uid", "0")) != EXECUTABLE_UID3
                or fields.get("executable_sha1") != hashlib.sha1(executable_bytes).hexdigest()
                or fields.get("version") != ".".join(map(str, VERSION))
                or int(fields.get("files", "0")) != 3 + len(input_files)
                or [item["target"] for item in embedded_files] != expected_targets
                or actual_data_files != expected_embedded
                or mda_imports != [35]
                or midi_imports
            ):
                raise SystemExit(
                    "Signed SIS/package/E32 audit failed.\n"
                    f"MDA imports={mda_imports}, MIDI imports={midi_imports}\n"
                    f"writer fields={fields}\nembedded files={embedded_files}"
                )

            output.write_bytes(signed_bytes)
            baseline_bytes = BASE_SIS.read_bytes()
            source_paths = [
                PROJECT.parent / "level41_device_test/CMakeLists.txt",
                PROJECT.parent / "level41_device_test/level41_main.cc",
                PROJECT.parent / "level41_device_test/quantum_level41_audio.h",
                PROJECT.parent / "level41_device_test/quantum_level41_audio.cpp",
            ]
            source_hashes = {
                str(path.relative_to(PROJECT.parent.parent)): sha256(path.read_bytes())
                for path in source_paths if path.is_file()
            }
            audit = {
                "audit_status": "passed-local-signed-package-audit",
                "runtime_status": "not-tested-on-E65",
                "scope": {
                    "location": "Level 1 interactive walk on E65/OMAP1710",
                    "background_music": "wth.mp3 (looping)",
                    "weapon_fire_effect": "fn2000.wav",
                    "weapon_reload_effect": "r.wav",
                    "midi": False,
                    "audible_playback_verified": False,
                },
                "candidate": {
                    "file": output.name,
                    "size_bytes": len(signed_bytes),
                    "sha256": sha256(signed_bytes),
                    "signed_package": True,
                    "application_registered": True,
                    "package_uid": f"0x{PACKAGE_UID:08X}",
                    "executable_uid3": f"0x{EXECUTABLE_UID3:08X}",
                    "version": list(VERSION),
                    "package_name": PACKAGE_NAME,
                    "vendor": VENDOR,
                },
                "baseline_v1_0_33": {
                    "file": BASE_SIS.name,
                    "size_bytes": len(baseline_bytes),
                    "sha256": sha256(baseline_bytes),
                    "left_unchanged": True,
                },
                "level1_sky_assets": {
                    "mesh_source": "last working walk package skybox8.3d (archive skies/sky.3d)",
                    "mesh_sha1": hashlib.sha1(level1_sky_mesh).hexdigest(),
                    "mesh_vertex_count": 25,
                    "mesh_polygon_count": 20,
                    "texture_source": "last working walk package sky.qtx (archive skies/skuy.png)",
                    "texture_sha1": hashlib.sha1(level1_sky_texture).hexdigest(),
                    "main_skybox8_3d_used": False,
                    "main_skybox5_2_jpg_used": False,
                },
                "builder": {
                    "method": "local E32 heap-header correction and SIS rebuild from v1.0.35; no GitHub Actions",
                    "sdk_version": "0.3.0",
                    "target": "ARMv5T/EKA2, OMAP1710, EColor64K, DSA ARGB bitmap, interactive walk",
                    "cmake_version": "3.31.6",
                    "ninja_version": "1.11.1.1",
                    "rcomp_source_commit": RCOMP_COMMIT,
                    "symbian_platform_commit": UPSTREAM_COMMIT,
                    "abseil_commit": ABSEIL_COMMIT,
                    "sis_writer_sha256": sha256(writer.read_bytes()),
                    "source_sha256": source_hashes,
                },
                "signing_certificate": certificate_info,
                "e32": {
                    "file": EXECUTABLE_NAME,
                    "size_bytes": len(executable_bytes),
                    "input_e32_sha256": sha256(original_executable_bytes),
                    "heap_min": heap_patch["heap_min"],
                    "heap_max": heap_patch["heap_max"],
                    "heap_patch": heap_patch,
                    "sha256": sha256(executable_bytes),
                    "signed_package_roundtrip_sha256": sha256(roundtrip_executable.read_bytes()),
                    "uid3": f"0x{EXECUTABLE_UID3:08X}",
                    "static_mda_import_ordinals": mda_imports,
                    "open_file_ordinal_42": "resolved dynamically with RLibrary::Lookup; not a static import",
                    "midi_imports": midi_imports,
                    "all_static_imports": all_imports,
                },
                "unsigned_package": {
                    "size_bytes": len(unsigned_bytes),
                    "sha256": sha256(unsigned_bytes),
                },
                "embedded_files": embedded_files,
                "limitations": [
                    "The local inspector verified the signed SIS structure, application registration, package metadata, and all embedded payload hashes.",
                    "OpenFileL and Play were compiled, but this package audit does not prove E65 codec support or audible output.",
                    "Level 1 MP3 and weapon WAV playback still requires on-device verification.",
                    "The v1.0.36 package preserves the exact Level 1 sky mesh/texture and patches the E32 maximum heap from 1 MiB to 2 MiB, recomputing and validating the header CRC.",
                    "The user-reported v1.0.35 E65 screen reached walk-collision setup but failed allocation for 2,641 world polygons with zero collision descriptors; v1.0.36 addresses the known 1 MiB heap regression. E65 runtime verification is still required.",
                    "The main Level 41 skybox8.3d and skybox5-2.jpg were not used for the walk package.",
                    "The source backup is a separate walk-target source and is not asserted to be byte-identical to the v1.0.33 SIS source.",
                ],
            }
            audit_output.parent.mkdir(parents=True, exist_ok=True)
            audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    finally:
        if writer_succeeded:
            private_key.unlink(missing_ok=True)

    return audit


def main() -> None:
    default_output = PROJECT / "output/v1.0.36-level1-audio-sym9-sky-heap-fix" / (
        "quantum_level41_walk_test-v1.0.36-level1-audio-sym9-sky-heap-fix-e65-candidate.sisx"
    )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        type=Path,
        default=CACHE / "level41-v1.0.35-extracted.exe",
    )
    parser.add_argument(
        "--sis-writer",
        type=Path,
        default=CACHE / "stage3-sis-writer-2m/build/stage3_sis_writer",
    )
    parser.add_argument(
        "--certificate",
        type=Path,
        default=CACHE / "level1-audio-signing-v1_0_36/cert.pem",
    )
    parser.add_argument(
        "--private-key",
        type=Path,
        default=CACHE / "level1-audio-signing-v1_0_36/key.pem",
        help="Temporary test-signing key; deleted immediately after a successful signature",
    )
    parser.add_argument("--output", type=Path, default=default_output)
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--force", action="store_true", help="Replace only this explicitly named candidate and audit")
    print(json.dumps(build(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
