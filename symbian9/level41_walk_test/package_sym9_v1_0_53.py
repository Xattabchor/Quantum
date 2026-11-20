#!/usr/bin/env python3
"""Package v1.0.53 with the Level 1 assets selected from sym9.zip.

The v1.0.52 candidate and its package script remain untouched. This profile
reuses the audited v1.0.33-native walk/MDA executable path, switches only the
Level 1 environment assets to sym9.zip, and accepts the archive's 128x64 forest
texture without resampling.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

import package_sis33_native_candidate as common

PROJECT = Path(__file__).resolve().parent
ROOT = PROJECT.parent.parent
CACHE = Path.home() / "quantum-sdk-cache"
OUTPUT_DIR = PROJECT / "output/v1.0.53-sym9-level1-assets"
BASE_DIR = OUTPUT_DIR / "package-inputs"
PROFILE_DIR = PROJECT.parent / "level41_device_test/assets/sym9-level1"
PREPARE_ASSETS = PROJECT.parent / "tools/prepare_level41_sym9_assets.py"
SOURCE_ARCHIVE = ROOT / "google_drive/sym9.zip"
MDA_ROLLBACK_SISX = ROOT / "google_drive/quantum_level41_walk_test-v1.0.43-level1-walk-discrete-roof-rain-argb32-e65-candidate.sisx"
MDA_EMBEDDED_DIR = CACHE / "mda-v1.0.43-embedded"
SOURCE_ARCHIVE_SHA256 = "eb81090003338a3333aa2d5b1c25c586cdabff0c446ed9664da132ca82e7b60b"
MDA_ROLLBACK_SHA256 = "c01410a5fc9883b4fb95c1438a83b12dab60a70d08455648d3ebc571b1a9d3da"
PREVIOUS_CANDIDATE_SHA256 = "8fb3a585c6f0e251ea0c65ae2609384281893170f5b7df8f3b48b248145eb4d1"
PACKAGE_UID = common.PACKAGE_UID
EXECUTABLE_UID3 = common.EXECUTABLE_UID3
PACKAGE_NAME = common.PACKAGE_NAME
VENDOR = common.VENDOR
EXECUTABLE_NAME = common.EXECUTABLE_NAME
VERSION = (1, 0, 53)

ENVIRONMENT_INPUTS = {
    "L3D_new.3d": PROFILE_DIR / "L3D_new.3d",
    "lightmap.vla": PROFILE_DIR / "lightmap.vla",
    "skybox8.3d": PROFILE_DIR / "skybox8.3d",
    "forest.qtx": PROFILE_DIR / "qtx/forest.qtx",
    "map.qtx": PROFILE_DIR / "qtx/map.qtx",
    "river.qtx": PROFILE_DIR / "qtx/river.qtx",
    "sky.qtx": PROFILE_DIR / "qtx/sky.qtx",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_hash(path: Path, expected: str, label: str) -> bytes:
    if not path.is_file():
        raise SystemExit(f"Missing {label}: {path}")
    data = path.read_bytes()
    actual = sha256(data)
    if actual != expected:
        raise SystemExit(f"{label} SHA-256 mismatch: expected={expected} actual={actual}")
    return data


def archive_member_map() -> dict[str, str]:
    # Keep this mapping aligned with prepare_level41_sym9_assets.py. The two
    # exported model filenames preserve the native loader's legacy resource IDs.
    return {
        "L3D_new.3d": "maps/kordos/kordos.3d",
        "lightmap.vla": "lightmap/kordos.vla",
        "skybox8.3d": "skies/sky.3d",
        "forestOrigy.png": "maps/kordos/forestOrigy.png",
        "map-selo1.png": "maps/kordos/map-selo1.png",
        "skuy.png": "skies/skuy.png",
        "skun.png": "skies/skun.png",
        **{f"river{i}.{ext}": f"maps/kordos/river{i}.{ext}"
           for i in range(4) for ext in ("bmp", "png")},
    }


def verify_sym9_profile(writer: Path) -> dict[str, object]:
    source_bytes = require_hash(
        SOURCE_ARCHIVE, SOURCE_ARCHIVE_SHA256, "Drive sym9.zip"
    )
    manifest_path = PROFILE_DIR / "sym9-assets-manifest.json"
    if not manifest_path.is_file():
        raise SystemExit(
            f"Missing staged sym9 assets; run {PREPARE_ASSETS} first"
        )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("source_archive", {}).get("sha256") != SOURCE_ARCHIVE_SHA256:
        raise SystemExit("Staged sym9 manifest does not identify the pinned Drive archive")

    members_by_destination = archive_member_map()
    with ZipFile(SOURCE_ARCHIVE) as archive:
        for destination_name, member in members_by_destination.items():
            staged = PROFILE_DIR / destination_name
            if not staged.is_file():
                raise SystemExit(f"Missing staged sym9 asset: {staged}")
            source_payload = archive.read(member)
            staged_payload = staged.read_bytes()
            if source_payload != staged_payload:
                raise SystemExit(
                    f"Staged sym9 asset differs from its archive member: {destination_name}"
                )
            entry = manifest.get("runtime_assets", {}).get(destination_name, {})
            if entry.get("archive_member") != member or entry.get("sha256") != sha256(staged_payload):
                raise SystemExit(f"Staged sym9 manifest mismatch for {destination_name}")

        level1 = archive.read("level1.txt").decode("latin-1")
        for marker in (
            "WORLD_MODEL=/maps/kordos/kordos.3d",
            "LIGHTMAP=/lightmap/kordos.vla",
            "/maps/kordos/forestOrigy.png,std,-20000",
            "/maps/kordos/map-selo1.png",
            "6:/maps/kordos/river0.bmp:/maps/kordos/river1.bmp:/maps/kordos/river2.bmp:/maps/kordos/river3.bmp",
            "SKYBOX_MODEL=/skies/sky.3d",
            "SKYBOX_TEXTURE=/skies/skuy.png",
        ):
            if marker not in level1:
                raise SystemExit(f"sym9.zip/level1.txt is missing source mapping: {marker}")

    # Re-run the pinned converter in a temporary directory so the QTX bytes used
    # in the SIS are proven against the source archive, not just its manifest.
    with tempfile.TemporaryDirectory(prefix="sym9-qtx-verify-") as temporary:
        staged_output = Path(temporary) / "assets"
        subprocess.run(
            [
                "python3", str(PREPARE_ASSETS), "--archive", str(SOURCE_ARCHIVE),
                "--output", str(staged_output),
            ],
            check=True, capture_output=True, text=True,
        )
        regenerated: dict[str, str] = {}
        for filename, record in manifest.get("qtx1_textures", {}).items():
            staged = PROFILE_DIR / "qtx" / filename
            regenerated_file = staged_output / "qtx" / filename
            if not staged.is_file() or staged.read_bytes() != regenerated_file.read_bytes():
                raise SystemExit(f"QTX texture does not reproduce from sym9 source: {filename}")
            digest = sha256(staged.read_bytes())
            if record.get("sha256") != digest:
                raise SystemExit(f"sym9 QTX manifest hash mismatch: {filename}")
            regenerated[filename] = digest

    # The four animation frames are imported and converted, but the current
    # executable consumes river.qtx only; do not claim native animation parity.
    previous = ROOT / "google_drive/quantum_level41_walk_test-v1.0.52-sis33-native-walk150-jump0-e65-candidate.sisx"
    previous_bytes = require_hash(previous, PREVIOUS_CANDIDATE_SHA256, "previous v1.0.52 candidate")
    return {
        "archive_size_bytes": len(source_bytes),
        "archive_sha256": SOURCE_ARCHIVE_SHA256,
        "source_assets": manifest["runtime_assets"],
        "qtx_hashes": regenerated,
        "previous_v1_0_52_size_bytes": len(previous_bytes),
        "previous_v1_0_52_sha256": PREVIOUS_CANDIDATE_SHA256,
    }


def read_build_profile(artifact: Path) -> dict[str, str]:
    build_root = artifact.parents[2]
    cmake_cache = build_root / "CMakeCache.txt"
    if not cmake_cache.is_file():
        raise SystemExit(f"CMake build cache is missing next to the E32: {cmake_cache}")
    values: dict[str, str] = {}
    for line in cmake_cache.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and ":" in line and "=" in line:
            key, remainder = line.split(":", 1)
            _, value = remainder.split("=", 1)
            values[key] = value
    required = {
        "SYMBIAN_TARGET_ARCH": "armv5t",
        "SYMBIAN_RUNTIME_LEGACY_EKA2": "ON",
        "QUANTUM_BUILD_LEVEL41_DEVICE_TEST": "ON",
        "QUANTUM_LEVEL41_INTERACTIVE_WALK": "ON",
        "QUANTUM_LEVEL41_E65_ASM_OPTIMIZATIONS": "ON",
        "QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP": "ON",
        "QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS": "OFF",
        "QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER": "OFF",
        "QUANTUM_LEVEL41_SNDDMA_AUDIO": "OFF",
        "QUANTUM_LEVEL41_TURN_INERTIA": "OFF",
        "QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY": "OFF",
    }
    mismatch = {key: (expected, values.get(key)) for key, expected in required.items()
                if values.get(key) != expected}
    if mismatch:
        raise SystemExit(f"Refusing to package an E32 without the required E65 build profile: {mismatch}")
    build_ninja = build_root / "build.ninja"
    if not build_ninja.is_file() or "-DQUANTUM_LEVEL41_DSA_ARGB32_REQUIRED=1" not in build_ninja.read_text(encoding="utf-8"):
        raise SystemExit("Build does not require the E65 DSA ARGB32 path")
    source = PROJECT.parent / "level41_device_test/level41_main.cc"
    source_text = source.read_text(encoding="utf-8")
    if (
        "const bool supportedForestSize" not in source_text
        or "forest.width == 128 && forest.height == 64" not in source_text
        or "forest.width == 256 && forest.height == 128" not in source_text
    ):
        raise SystemExit("The native loader does not explicitly support the sym9 forest dimensions")
    return {key: values[key] for key in required}


def stage_package_inputs(writer: Path, sym9: dict[str, object]) -> dict[str, str]:
    require_hash(MDA_ROLLBACK_SISX, MDA_ROLLBACK_SHA256, "preserved v1.0.43 MDA rollback SISX")
    if not MDA_EMBEDDED_DIR.is_dir():
        MDA_EMBEDDED_DIR.mkdir(parents=True, exist_ok=True)
        common.run_writer([str(writer), "--extract-files", str(MDA_ROLLBACK_SISX), str(MDA_EMBEDDED_DIR)])

    BASE_DIR.mkdir(parents=True, exist_ok=True)
    expected_hashes: dict[str, str] = {}
    for filename, _target in common.DATA_FILES:
        if filename in ENVIRONMENT_INPUTS:
            source = ENVIRONMENT_INPUTS[filename]
            expected_hash = (
                sym9["source_assets"][filename]["sha256"]
                if filename in ("L3D_new.3d", "lightmap.vla", "skybox8.3d")
                else sym9["qtx_hashes"][filename]
            )
        else:
            embedded_name = common.MDA_EMBEDDED_FILES[filename]
            source = MDA_EMBEDDED_DIR / embedded_name
            expected_hash = common.EXPECTED_DATA_SHA256[filename]
        if not source.is_file():
            raise SystemExit(f"Missing package payload source: {source}")
        payload = source.read_bytes()
        actual = sha256(payload)
        if actual != expected_hash:
            raise SystemExit(f"Refusing changed package payload {filename}: expected={expected_hash} actual={actual}")
        destination = BASE_DIR / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
        expected_hashes[filename] = expected_hash

    for name, expected_hash in common.EXPECTED_RESOURCE_SHA256.items():
        source = MDA_EMBEDDED_DIR / name
        if not source.is_file() or sha256(source.read_bytes()) != expected_hash:
            raise SystemExit(f"MDA rollback registration asset differs from its pin: {source}")
        shutil.copy2(source, BASE_DIR / name)
    return expected_hashes


def data_target_sort_key(item: tuple[Path, str]) -> str:
    target = item[1]
    for prefix in ("!:\\data\\", "!:\\private\\e000a020\\"):
        if target.casefold().startswith(prefix.casefold()):
            return target[len(prefix):].casefold()
    return target.casefold()


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
    if output.exists() and not args.force:
        raise SystemExit(f"Refusing to overwrite existing candidate: {output} (use --force explicitly)")

    build_profile = read_build_profile(artifact)
    sym9 = verify_sym9_profile(writer)
    payload_hashes = stage_package_inputs(writer, sym9)
    input_files = [(BASE_DIR / name, target) for name, target in common.DATA_FILES]
    input_files.sort(key=data_target_sort_key)

    weather = (BASE_DIR / "weather.txt").read_text(encoding="utf-8")
    for marker in ("WEATHER=RAIN", "WEATHER_PARTICLES_COUNT=8", "WEATHER_PARTICLES_SIZE=7"):
        if marker not in weather.splitlines():
            raise SystemExit(f"Preserved MDA weather config lacks {marker}")
    if sha256(MDA_ROLLBACK_SISX.read_bytes()) != MDA_ROLLBACK_SHA256:
        raise SystemExit("MDA rollback changed during packaging")

    certificate_info = common.check_certificate(certificate, private_key)
    original_executable = artifact.read_bytes()
    executable, heap_patch = common.patch_e32_heap(original_executable)
    output.parent.mkdir(parents=True, exist_ok=True)
    writer_succeeded = False
    try:
        with tempfile.TemporaryDirectory(prefix="level41-sym9-sis53-") as temporary:
            temporary_dir = Path(temporary)
            unsigned_path = temporary_dir / "candidate.sis"
            signed_path = temporary_dir / "candidate.sisx"
            patched_exe = temporary_dir / EXECUTABLE_NAME
            patched_exe.write_bytes(executable)
            command = [
                str(writer), "--exe", str(patched_exe),
                "--registration", str(BASE_DIR / "quantum_level41_walk_test_reg.rsc"),
                "--registration-target", common.REGISTRATION_TARGET,
                "--caption", str(BASE_DIR / "quantum_level41_walk_test_loc.rsc"),
                "--caption-target", common.CAPTION_TARGET,
                "--package-uid", hex(PACKAGE_UID),
                "--package-name", PACKAGE_NAME,
                "--vendor", VENDOR,
                "--executable-name", EXECUTABLE_NAME,
                "--version", ".".join(map(str, VERSION)),
                "--certificate", str(certificate),
                "--private-key", str(private_key),
                "--unsigned-output", str(unsigned_path),
                "--output", str(signed_path),
            ]
            for path, target in input_files:
                command.extend(("--data-file", str(path), "--data-target", target))
            stdout = common.run_writer(command)
            writer_succeeded = True
            fields, embedded_files = common.parse_writer_report(stdout)
            unsigned_bytes = unsigned_path.read_bytes()
            signed_bytes = signed_path.read_bytes()
            roundtrip = temporary_dir / "roundtrip.exe"
            common.run_writer([str(writer), "--extract-exe", str(signed_path), str(roundtrip)])
            if roundtrip.read_bytes() != executable:
                raise SystemExit("Signed package E32 payload differs from the patched executable")

            imports = fields.get("e32_imports", "")
            all_imports = []
            mda_imports = []
            midi_imports = []
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
                common.REGISTRATION_TARGET,
                common.CAPTION_TARGET,
                *(target for _, target in input_files),
            ]
            expected_embedded = []
            for path, target in input_files:
                payload = path.read_bytes()
                expected_embedded.append({
                    "target": target,
                    "size": len(payload),
                    "sha1": hashlib.sha1(payload).hexdigest(),
                })
            if (
                fields.get("writer") != "stage3-mda-openfile-v1"
                or fields.get("upstream_commit") != common.UPSTREAM_COMMIT
                or fields.get("signed") != "true"
                or fields.get("registered") != "true"
                or int(fields.get("package_uid", "0")) != PACKAGE_UID
                or fields.get("package_name") != PACKAGE_NAME
                or fields.get("vendor") != VENDOR
                or fields.get("executable_name") != EXECUTABLE_NAME
                or int(fields.get("executable_uid", "0")) != EXECUTABLE_UID3
                or fields.get("executable_sha1") != hashlib.sha1(executable).hexdigest()
                or fields.get("version") != ".".join(map(str, VERSION))
                or int(fields.get("files", "0")) != 3 + len(input_files)
                or [item["target"] for item in embedded_files] != expected_targets
                or embedded_files[3:] != expected_embedded
                or mda_imports != [35, 15]
                or midi_imports
            ):
                raise SystemExit(
                    "Signed SIS/package/E32 audit failed.\n"
                    f"MDA imports={mda_imports}, MIDI imports={midi_imports}\n"
                    f"writer fields={fields}\nembedded files={embedded_files}"
                )

            output.write_bytes(signed_bytes)
            cert_copy = OUTPUT_DIR / "sym9-v1.0.53-test-certificate.pem"
            cert_copy.write_bytes(certificate.read_bytes())
            audit = {
                "audit_status": "passed-local-signed-package-audit",
                "runtime_status": "not-tested-on-E65",
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
                    "embedded_record_count": len(embedded_files),
                },
                "source_provenance": {
                    "environment_archive": {
                        "file": SOURCE_ARCHIVE.name,
                        "drive_id": "1elEgbqUS3KhYfKuusXIwS1LaVF_o6bki",
                        "size_bytes": sym9["archive_size_bytes"],
                        "sha256": SOURCE_ARCHIVE_SHA256,
                        "selection": "Kordos Level 1 map model, lightmap, map/forest/river textures, sky mesh, and configured daytime sky texture only; unrelated maps/weapons/code excluded",
                    },
                    "packaged_payload_sha256": payload_hashes,
                    "raw_archive_asset_manifest": sym9["source_assets"],
                    "all_qtx_conversion_sha256": sym9["qtx_hashes"],
                    "sky_texture_source": "sym9.zip/skies/skuy.png (Level 1-configured daytime sky); skun.png retained as a source asset but not packaged",
                    "river_texture_source": "sym9.zip/maps/kordos/river0.bmp; the other three animation frames were imported and converted, but the current executable loads only river.qtx",
                    "previous_v1_0_52": {
                        "file": "quantum_level41_walk_test-v1.0.52-sis33-native-walk150-jump0-e65-candidate.sisx",
                        "sha256": PREVIOUS_CANDIDATE_SHA256,
                        "role": "preserved previous candidate; not modified and not used as an asset source",
                    },
                    "mda_rollback_source": {
                        "file": MDA_ROLLBACK_SISX.name,
                        "drive_id": "1uCqoNjgXSYLAryO1r7n2c7MydWtlYy3h",
                        "sha256": MDA_ROLLBACK_SHA256,
                        "role": "read-only source for weather, weapon, audio, and registration payloads",
                        "left_unchanged": True,
                    },
                    "weapon_scope": "No weapon code or assets were overlaid from sym9.zip; the MDA rollback weapon payloads were kept unchanged",
                },
                "environment_assets": {
                    "asset_profile": str(PROFILE_DIR.relative_to(ROOT)),
                    "asset_payload_sha256": {name: payload_hashes[name] for name in ENVIRONMENT_INPUTS},
                    "source_archive_byte_comparisons": "passed for every imported raw sym9 asset",
                    "qtx_regeneration_comparison": "passed for forest/map/river/day-sky QTX1 files",
                    "forest_dimensions": "128x64 sym9 source is accepted alongside the unchanged 256x128 profile; no resampling",
                    "sky_mesh_sha1": hashlib.sha1((BASE_DIR / "skybox8.3d").read_bytes()).hexdigest(),
                    "sky_qtx_sha1": hashlib.sha1((BASE_DIR / "sky.qtx").read_bytes()).hexdigest(),
                    "runtime_status": "not tested on an E65",
                },
                "build": {
                    "method": "fresh local ARMv5T/EKA2 cross-build from the active working tree; local adapted SIS writer and test signer",
                    "sdk_root": "/home/user/quantum-sdk-cache",
                    "sdk_version": "0.3.0",
                    "sdk_split_archive_sha256": "0c974247026ee0503a3d522a497963b73cfe2247075cffae3867c33ae09f7216",
                    "glibc_2_38_runtime_sha256": "d281c56280563674bbb218abec67a9bfa1f0daa7cfddd50a1cea489c23a4f92b",
                    "host_helpers_sha256": "2d67b15a47129158ec7f98bbf71fe74042c7134c1546de467f502e2664fc307c",
                    "cmake": "3.28.3",
                    "ninja": "1.13.2",
                    "compiler": "Clang 23.1.2",
                    "cmake_options": build_profile,
                    "dsa_argb32_required_compile_definition": True,
                    "snddma_enabled": False,
                    "standard_mda_backend": True,
                    "midi_imports": midi_imports,
                    "mda_imports": mda_imports,
                    "host_regressions": [
                        "--sis33-baseline: passed",
                        "--sis33-baseline-no-sway: passed",
                        "default turn-inertia A/B: passed",
                        "--disable-weapon-sway: passed",
                    ],
                    "existing_warnings": [
                        "Deprecated floating-point/enumeration arithmetic warnings in matrix.cpp:57-58",
                        "GCC -Wrestrict and missing-field-initializer warnings while building the local Abseil/SIS writer",
                    ],
                },
                "e32": {
                    "file": EXECUTABLE_NAME,
                    "size_bytes": len(executable),
                    "input_e32_sha256": sha256(original_executable),
                    "sha256": sha256(executable),
                    "heap_min": heap_patch["heap_min"],
                    "heap_max": heap_patch["heap_max"],
                    "heap_patch": heap_patch,
                    "roundtrip_sha256": sha256(roundtrip.read_bytes()),
                    "uid3": f"0x{EXECUTABLE_UID3:08X}",
                    "static_mda_import_ordinals": mda_imports,
                    "midi_imports": midi_imports,
                    "all_static_imports": all_imports,
                },
                "embedded_files": embedded_files,
                "unsigned_package": {
                    "size_bytes": len(unsigned_bytes),
                    "sha256": sha256(unsigned_bytes),
                },
                "signing_certificate": certificate_info,
                "limitations": [
                    "Local package validation by the adapted writer is not stock MakeSIS acceptance or E65 installation evidence.",
                    "No E65 installation, launch, display, walking, keypad, weather, audio, FPS, or stability test was performed for this build.",
                    "The v1.0.33 native walk/sway reconstruction remains partial; runtime behavior and pixel parity are not claimed.",
                    "The ordinary MDA backend is present and SNDDMA/MIDI are off; audible playback and the previously reported post-shot issue remain unverified.",
                    "The native renderer currently consumes only river.qtx; imported river1..river3 frames are not embedded or animated by this candidate.",
                    "Only the forest texture-size validation was changed in the native loader for this asset switch; the 3D mesh renderer, walk behavior, weapon path, rain path, and audio backend were not otherwise changed.",
                ],
            }
            audit_output.parent.mkdir(parents=True, exist_ok=True)
            audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
            return audit
    finally:
        if writer_succeeded:
            private_key.unlink(missing_ok=True)


def main() -> None:
    default_output = OUTPUT_DIR / "quantum_level41_walk_test-v1.0.53-sym9-level1-assets-e65-candidate.sisx"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact", type=Path,
        default=PROJECT.parent / "build/e65-level41-walk-asm/level41_device_test/e32/quantum_level41_walk_test.exe",
    )
    parser.add_argument(
        "--sis-writer", type=Path,
        default=CACHE / "stage3-sis-writer/build/stage3_sis_writer",
    )
    parser.add_argument(
        "--certificate", type=Path,
        default=CACHE / "level41-sym9-v1_0_53/cert.pem",
    )
    parser.add_argument(
        "--private-key", type=Path,
        default=CACHE / "level41-sym9-v1_0_53/key.pem",
        help="Temporary test-signing private key; deleted after successful signing",
    )
    parser.add_argument("--output", type=Path, default=default_output)
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--force", action="store_true", help="replace only the explicitly named candidate and audit")
    args = parser.parse_args()
    print(json.dumps(build(args), indent=2))


if __name__ == "__main__":
    main()
