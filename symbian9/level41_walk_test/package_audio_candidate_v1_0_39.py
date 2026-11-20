#!/usr/bin/env python3
"""Package a separate v1.0.39 E65 walk/audio/DSA-ARGB32 candidate with a 2 MiB E32 heap."""

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
WRITER_PROFILE_PATCH = PROJECT / "local_sis_writer_2m_heap.patch"
BASE_DIR = PROJECT / "output/v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32"
BASE_SIS = BASE_DIR / "quantum_level41_walk_test-v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32-armv5t.sis"
PACKAGE_UID = 0xE000A021
EXECUTABLE_UID3 = 0xE000A020
PACKAGE_NAME = "Quantum Level 41 Walk"
VENDOR = "Quantum native port"
EXECUTABLE_NAME = "quantum_level41_walk_test.exe"
VERSION = (1, 0, 39)
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
    ("audio/music/wth.mp3", r"!:\data\q41_wth.mp3"),
    ("audio/sounds/wpn/fn2000.wav", r"!:\data\q41_fn2000.wav"),
    ("audio/sounds/wpn/r.wav", r"!:\data\q41_r.wav"),
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

    build_root = artifact.parents[2]
    cmake_cache = build_root / "CMakeCache.txt"
    if not cmake_cache.is_file():
        raise SystemExit(f"CMake build cache is missing next to the E32 build: {cmake_cache}")
    cache_values: dict[str, str] = {}
    for line in cmake_cache.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and ":" in line and "=" in line:
            key, remainder = line.split(":", 1)
            _value_type, value = remainder.split("=", 1)
            cache_values[key] = value
    required_profile = {
        "SYMBIAN_TARGET_ARCH": "armv5t",
        "SYMBIAN_RUNTIME_LEGACY_EKA2": "ON",
        "QUANTUM_BUILD_LEVEL41_DEVICE_TEST": "ON",
        "QUANTUM_LEVEL41_INTERACTIVE_WALK": "ON",
        "QUANTUM_LEVEL41_E65_ASM_OPTIMIZATIONS": "ON",
        "QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP": "ON",
        "QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS": "OFF",
    }
    mismatched = {
        key: (expected, cache_values.get(key))
        for key, expected in required_profile.items()
        if cache_values.get(key) != expected
    }
    if mismatched:
        raise SystemExit(f"Refusing to package an E32 without the complete E65 profile: {mismatched}")
    generated_ninja = build_root / "build.ninja"
    if (not generated_ninja.is_file() or
            "-DQUANTUM_LEVEL41_DSA_ARGB32_REQUIRED=1" not in generated_ninja.read_text(encoding="utf-8")):
        raise SystemExit("The generated build does not require DSA ARGB32 output mode")

    audio_source = PROJECT.parent / "level41_device_test/quantum_level41_audio.cpp"
    audio_text = audio_source.read_text(encoding="utf-8")
    required_audio_paths = (
        r"\\data\\q41_wth.mp3",
        r"\\data\\q41_fn2000.wav",
        r"\\data\\q41_r.wav",
    )
    if any(path not in audio_text for path in required_audio_paths):
        raise SystemExit("Audio source paths do not match the namespaced public-data SIS payloads")
    main_source = PROJECT.parent / "level41_device_test/level41_main.cc"
    main_text = main_source.read_text(encoding="utf-8")
    if "drawLevel41Rain(surface)" not in main_text:
        raise SystemExit("The E65 effects build must contain the live rain-render callsite")
    if "SetPosition(TTimeIntervalMicroSeconds(0))" not in audio_text:
        raise SystemExit("Effect playback must rewind the already-open player before replay")
    if "alreadyHeld || applyLevel41WalkEventStep" not in main_text:
        raise SystemExit("The E65 walk/turn event fallback is missing from the device source")
    mesh_source = PROJECT.parent / "src/core/software_mesh_renderer.cpp"
    mesh_text = mesh_source.read_text(encoding="utf-8")
    if (
        "const Vector3D& worldPosition = mesh.vertices[vertexIndex];" not in mesh_text
        or "getSingleVertexLight(brightness, polygon, worldPosition," not in mesh_text
        or "options.shootIntensity > 0" not in mesh_text
    ):
        raise SystemExit("The mesh renderer is missing source/world-space shot-light evaluation")
    walk_source = PROJECT.parent / "src/core/software_walk_controller.cpp"
    walk_text = walk_source.read_text(encoding="utf-8")
    if "elapsedMs" not in walk_text:
        raise SystemExit("The elapsed-time walk controller change is missing")

    if not writer.is_file():
        raise SystemExit(f"Local SIS writer not found: {writer}")
    if not WRITER_PROFILE_PATCH.is_file():
        raise SystemExit(f"Local 2 MiB heap compatibility patch is missing: {WRITER_PROFILE_PATCH}")
    writer_patch_sha256 = sha256(WRITER_PROFILE_PATCH.read_bytes())
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

    def data_target_sort_key(item: tuple[Path, str]) -> str:
        target = item[1]
        for prefix in ("!:\\data\\", "!:\\private\\e000a020\\"):
            if target.casefold().startswith(prefix.casefold()):
                return target[len(prefix):].casefold()
        return target.casefold()

    # The local SIS writer sorts the relative path after either the public-data
    # or app-private prefix, not the full target string.
    input_files.sort(key=data_target_sort_key)

    weather_config = (BASE_DIR / "weather.txt").read_text(encoding="utf-8")
    for required_weather_setting in (
        "WEATHER=RAIN",
        "WEATHER_PARTICLES_COUNT=8",
        "WEATHER_PARTICLES_SIZE=7",
    ):
        if required_weather_setting not in weather_config.splitlines():
            raise SystemExit(
                f"The preserved Level 1 weather config is missing {required_weather_setting}"
            )

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
                or mda_imports != [35, 15]
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
                PROJECT.parent / "CMakeLists.txt",
                PROJECT.parent / "CMakePresets.json",
                PROJECT.parent / "level41_device_test/CMakeLists.txt",
                PROJECT.parent / "level41_device_test/level41_main.cc",
                PROJECT.parent / "level41_device_test/quantum_level41_audio.h",
                PROJECT.parent / "level41_device_test/quantum_level41_audio.cpp",
                PROJECT.parent / "src/core/omap1710_dsp.S",
                PROJECT.parent / "src/core/omap1710_effects.S",
                PROJECT.parent / "src/core/omap1710_screen_copy.S",
                PROJECT.parent / "src/core/software_perspective_rasterizer.cpp",
                PROJECT.parent / "src/core/software_rain_renderer.cpp",
                PROJECT.parent / "src/core/software_weapon_renderer.cpp",
                PROJECT.parent / "src/core/software_mesh_renderer.cpp",
                PROJECT.parent / "src/core/software_walk_controller.cpp",
                PROJECT.parent / "src/core/software_weapon_controller.cpp",
                PROJECT.parent / "inc/software_mesh_renderer.h",
                PROJECT.parent / "inc/software_walk_controller.h",
                PROJECT.parent / "inc/software_weapon_controller.h",
                PROJECT.parent / "inc/omap1710_effects.h",
                PROJECT.parent / "inc/software_rain_renderer.h",
                PROJECT.parent / "host_tests/level41_walk_weapon_regression_test.cpp",
                PROJECT.parent / "host_tests/run_level41_walk_weapon_regression_test.sh",
                PROJECT / "README.md",
                PROJECT / "package_audio_candidate_v1_0_39.py",
                WRITER_PROFILE_PATCH,
                PROJECT.parent / "level41_mda_open_file_diag/local_sis_writer/build_local_sis_writer.py",
                PROJECT.parent / "level41_mda_open_file_diag/local_sis_writer/CMakeLists.txt",
                PROJECT.parent / "level41_mda_open_file_diag/local_sis_writer/src/main.cc",
                PROJECT.parent / "level41_mda_open_file_diag/local_sis_writer/cpp/symbian/sis/sis.cc",
                PROJECT.parent / "level41_mda_open_file_diag/local_sis_writer/cpp/symbian/sis/sis.h",
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
                    "reported_regressions_vs_v1_0_33": {
                        "installed_candidate_version": "unspecified by the E65 report",
                        "walking_speed": "reported slower; elapsed-time movement and turn scaling is covered by host tests, but E65 behavior is unverified",
                        "weapon_shot_illumination": "reported missing/dim; shootLight defaults on and source/world-space lighting is covered by a mesh-renderer host test, but E65 behavior is unverified",
                        "fps": "reported lower; detailed per-pixel raster profiling is disabled in this candidate, but no E65 A/B measurement is available",
                    },
                    "reported_audio_issues": {
                        "post_shot_crash": "reported; root cause remains unconfirmed and this candidate has not been run on an E65",
                        "delayed_sound": "reported; effect pre-open and one-pending-trigger handling are included, but audio timing remains unverified",
                    },
                    "dsa_output_mode": "compile-time DSA ARGB32 required; actual E65 display mode unverified",
                    "background_music": "wth.mp3 (looping)",
                    "weapon_fire_effect": "fn2000.wav",
                    "weapon_reload_effect": "r.wav",
                    "audio_storage": {
                        "strategy": "namespaced, single-level public C:\\data files so the E65 MDA server can open them",
                        "music": "!:\\data\\q41_wth.mp3",
                        "fire": "!:\\data\\q41_fn2000.wav",
                        "reload": "!:\\data\\q41_r.wav",
                        "reason": "stage-3b confirmed OpenFileL initialization for a packaged WAV under C:\\data; private-SID access was not verified",
                    },
                    "audio_player_safety": "separate music, shot, and reload players; effects are pre-opened, at most one trigger is queued while opening, overlap while playing is dropped, and completed effects are rewound before replay; E65 behavior is unverified",
                    "raster_detail_profiling": "disabled by default for this candidate; an opt-in profile preset is retained",
                    "rain_effect": {
                        "level1_config": "WEATHER=RAIN, count=8, size=7",
                        "active_streaks_at_80_percent_viewport": 6,
                        "effects_asm_callsite": True,
                    },
                    "midi": False,
                    "audible_playback_verified": False,
                },
                "source_provenance": {
                    "v1_0_33_sis_used_as_read_only_resource_baseline": True,
                    "original_v1_0_33_walk_source_archive_found": False,
                    "candidate_source": "active v1.0.39 workspace source with elapsed-time walking, source/world-space shot lighting, raster profiling disabled by default, and the latest audio changes; hash-listed below",
                    "byte_identity_to_v1_0_33_source_asserted": False,
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
                    "method": "local Symbian SDK 0.3.0 ARMv5T/EKA2 cross-build from the active working tree, then local SIS build/sign/audit; no GitHub Actions",
                    "sdk_version": "0.3.0",
                    "target": "ARMv5T/EKA2, OMAP1710, DSA ARGB32 required (fail-closed), elapsed-time interactive walk, source/world-space shot light, raster-detail profiling OFF, effects/rain ASM, scalar PDiv ASM, mode-9 MUL-order experiment, RGB565 copy ASM",
                    "cmake_version": "3.28.3",
                    "ninja_version": "1.13.2",
                    "cross_compile_status": "passed",
                    "cmake_options": {key: cache_values[key] for key in required_profile},
                    "dsa_argb32_required_compile_definition": True,
                    "assembly_experiments": {
                        "weapon_row_asm": True,
                        "rain_streak_asm_and_live_callsite": True,
                        "scalar_unsigned_pdiv_asm": True,
                        "mode9_mul_operand_order_experiment": True,
                        "omap1710_dsp_mode9_span_kernels": True,
                        "rgb565_screen_copy_asm": True,
                        "mode9_mul_order_device_timing": "not-tested-on-E65",
                    },
                    "rcomp_source_commit": RCOMP_COMMIT,
                    "symbian_platform_commit": UPSTREAM_COMMIT,
                    "abseil_commit": ABSEIL_COMMIT,
                    "sis_writer_sha256": sha256(writer.read_bytes()),
                    "local_writer_patch": {
                        "file": str(WRITER_PROFILE_PATCH.relative_to(PROJECT.parent.parent)),
                        "sha256": writer_patch_sha256,
                        "accepted_e32_heap_max_bytes": [E32_HEAP_MAX_V1_0_35, E32_HEAP_MAX],
                        "other_e32_layout_and_identity_checks": "unchanged",
                        "verified_executable_payload_roundtrip": True,
                    },
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
                    "set_position_ordinal_15": "used to rewind a completed effect player before replay; E65 call behavior unverified",
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
                    "The v0.3.0 local writer uses a scoped parser patch accepting 1 MiB or 2 MiB E32 maximum heap values; every other experimental E32 layout and identity check is unchanged.",
                    "OpenFileL, Play, and SetPosition were compiled, but this package audit does not prove E65 codec support, audible output, or safe repeated playback.",
                    "The reported post-shot crash and delayed sound have not been reproduced or confirmed fixed on E65; the crash root cause and audio timing remain unknown.",
                    "The reported walk-speed regression is targeted by elapsed-time movement scaling; the host regression test passes, but actual speed on E65 remains unverified.",
                    "The reported missing/dim weapon illumination is targeted by restoring source/world-space shot-light vectors; the renderer host test passes, but visual behavior on E65 remains unverified.",
                    "The reported FPS regression has no numeric E65 baseline or A/B result; disabling detailed raster profiling removes one known diagnostic overhead but does not establish an FPS fix.",
                    "DSA ARGB32 is required at compile time and fallback output modes are disabled; the actual E65 display mode has not been verified.",
                    "The public C:\\data audio location and cached-player state machine are still unverified on E65; earlier no-sound reports require a device playback test.",
                    "The older v1.0.37 E65 report (walk/turn inputs unresponsive; one fire sound followed by exit/crash) remains historical context; this candidate is not an E65-verified fix.",
                    "The Level 1 rain profile is active through the assembly callsite; this small deterministic six-streak overlay is not a complete Java WeatherGenerator port.",
                    "The mode-9 MUL operand-order experiment compiled, but its cycle benefit and device parity have not been measured on the E65.",
                    "This separate v1.0.39 package preserves the exact Level 1 sky mesh/texture and patches the E32 maximum heap to 2 MiB, recomputing and validating the header CRC.",
                    "The user-reported v1.0.35 E65 screen reached walk-collision setup but failed allocation for 2,641 world polygons with zero collision descriptors; the 2 MiB E32 heap remains in this separate candidate. E65 runtime verification is still required.",
                    "The main Level 41 skybox8.3d and skybox5-2.jpg were not used for the walk package.",
                    "The original v1.0.33 walk source archive was not found. This is a separate candidate built from the active working tree; source identity with v1.0.33 is not asserted.",
                ],
            }
            audit_output.parent.mkdir(parents=True, exist_ok=True)
            audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    finally:
        if writer_succeeded:
            private_key.unlink(missing_ok=True)

    return audit


def main() -> None:
    default_output = PROJECT / "output/v1.0.39-level1-walk-light-audio-perf-argb32" / (
        "quantum_level41_walk_test-v1.0.39-level1-walk-light-audio-perf-argb32-e65-candidate.sisx"
    )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        type=Path,
        default=PROJECT.parent / "build/e65-level41-walk-asm/level41_device_test/e32/quantum_level41_walk_test.exe",
    )
    parser.add_argument(
        "--sis-writer",
        type=Path,
        default=CACHE / "stage3-sis-writer/build/stage3_sis_writer",
    )
    parser.add_argument(
        "--certificate",
        type=Path,
        default=CACHE / "level1-audio-signing-v1_0_39/cert.pem",
    )
    parser.add_argument(
        "--private-key",
        type=Path,
        default=CACHE / "level1-audio-signing-v1_0_39/key.pem",
        help="Temporary test-signing key; deleted immediately after a successful signature",
    )
    parser.add_argument("--output", type=Path, default=default_output)
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--force", action="store_true", help="Replace only this explicitly named candidate and audit")
    print(json.dumps(build(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
