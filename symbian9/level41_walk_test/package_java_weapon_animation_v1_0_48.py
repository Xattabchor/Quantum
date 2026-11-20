#!/usr/bin/env python3
"""Package a separate v1.0.48 MDA candidate with the Java weapon animation port."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import struct
import tempfile
from pathlib import Path
from zipfile import ZipFile

CACHE = Path.home() / "quantum-sdk-cache"
PROJECT = Path(__file__).resolve().parent
WRITER_PROFILE_PATCH = PROJECT / "local_sis_writer_2m_heap.patch"
ROOT = PROJECT.parent.parent
OUTPUT_DIR = PROJECT / "output/v1.0.48-level41-mda-java-weapon-animation"
BASE_DIR = OUTPUT_DIR / "package-inputs"
SOURCE_ARCHIVE = ROOT / "google_drive/quantum last.zip"
JAVA_WEAPONS_ARCHIVE = ROOT / "google_drive/sym9.zip"
MDA_ROLLBACK_SISX = ROOT / "google_drive/quantum_level41_walk_test-v1.0.43-level1-walk-discrete-roof-rain-argb32-e65-candidate.sisx"
MDA_EMBEDDED_DIR = CACHE / "mda-v1.0.43-embedded"
SOURCE_ARCHIVE_SHA256 = "90683d18db5e281647835506942ab44784e744b35f695ccd496d4cb7e6e544a7"
JAVA_WEAPONS_ARCHIVE_SHA256 = "eb81090003338a3333aa2d5b1c25c586cdabff0c446ed9664da132ca82e7b60b"
MDA_ROLLBACK_SISX_SHA256 = "c01410a5fc9883b4fb95c1438a83b12dab60a70d08455648d3ebc571b1a9d3da"
PACKAGE_UID = 0xE000A021
EXECUTABLE_UID3 = 0xE000A020
PACKAGE_NAME = "Quantum Level 41 Walk"
VENDOR = "Quantum native port"
EXECUTABLE_NAME = "quantum_level41_walk_test.exe"
VERSION = (1, 0, 48)
EXPECTED_DATA_SHA256 = {
    "L3D_new.3d": "00cf889f495aa5b8fe6bc058c022ef16f49a8761c2d30b7d084fa43eec088ad9",
    "lightmap.vla": "611e4f67c28d1bd8de778340eef72ad96e59821cdb00c044adbdba65a78162f1",
    "skybox8.3d": "15213cafe7535b1f90276b7d7ad807192c9dfa164e6985d93b7687d00ef695c1",
    "forest.qtx": "0b2963f5ff7371cb698be871ff37775d9cd82c42c3ad73bef7d89c274e98450e",
    "map.qtx": "319a39401f971387172807a6f9b8832ba50e45fe8a6e066c540fbad6d140b697",
    "river.qtx": "879f3ff189d631275a4747a0d5d82759ec391ec6ecdc6f173b11ec3ca7d4eb86",
    "sky.qtx": "c89af7f2a11d405ab00bf23ce613b6a1a2cc07bf71f404b6cd7f700a0db8788e",
    "weather.txt": "a5e63bfbd7b958fa97202021a37ca02b8211081c98b06f0a2577a2a6e96911d5",
    "weapon.qtx": "b1d3f2ad6c7cb058e37f88d2b2e9c33c925483eb362abb6b965f161631dcf87d",
    "weapon-fire.qtx": "4266609c689254079829cc85b8446c5b368c6efc1cfb0e6b919d34f14e8bf91f",
    "weapon-sight-weapon.qtx": "6869c5b17a2765e1af7ac758701f39ddebf677fef7c4aa3781bd15a7f5fbe6e4",
    "weapon-gameplay.qwg": "e688da7aaad5de5e9518370a1b978577444a77edbc70b6e2de558b5ba6ba8859",
    "weapon-catalog.qwc": "3c8fe379775a8cba6fdb702f7bba3ef885fc9301334a5d1d6c1a96953d9d7ee2",
    "audio/music/wth.mp3": "7f441cdca71ef026915056ab60cd95c06d2d6ab0a8640ed8e9bd9e3f396b91d6",
    "audio/sounds/wpn/fn2000.wav": "99ef9989a3b17949a2bd4cfbadfa6b2f16d84f33b207a01b91b42d72c0e25d3b",
    "audio/sounds/wpn/r.wav": "9292944115644ac4dbd358079987af3235bf6f4678fabde5c185ac1d4023ea06",
}
EXPECTED_RESOURCE_SHA256 = {
    "quantum_level41_walk_test_reg.rsc": "e4b35f648dc2c06e4c3e66f76de6510aff00da337793b9f00d0f65d5d40a4b1c",
    "quantum_level41_walk_test_loc.rsc": "9df5beb4624cd761b4cd75d7872c6b73d304e2e8d48158ff8ad4ec5c4709d6eb",
}
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


LEVEL41_SOURCE_MEMBERS = {
    "L3D_new.3d": "quantum-level41-walk-source-assets-java-reference-2026-10-16/symbian9/level41_device_test/assets/level41/L3D_new.3d",
    "lightmap.vla": "quantum-level41-walk-source-assets-java-reference-2026-10-16/symbian9/level41_device_test/assets/level41/lightmap.vla",
    "skybox8.3d": "quantum-level41-walk-source-assets-java-reference-2026-10-16/symbian9/level41_device_test/assets/level41/skybox8.3d",
    "forest.qtx": "quantum-level41-walk-source-assets-java-reference-2026-10-16/build-assets/level41-qtx/forest.qtx",
    "map.qtx": "quantum-level41-walk-source-assets-java-reference-2026-10-16/build-assets/level41-qtx/map.qtx",
    "river.qtx": "quantum-level41-walk-source-assets-java-reference-2026-10-16/build-assets/level41-qtx/river.qtx",
    "sky.qtx": "quantum-level41-walk-source-assets-java-reference-2026-10-16/build-assets/level41-qtx/sky.qtx",
}
MDA_EMBEDDED_FILES = {
    "weather.txt": "weather.txt",
    "weapon.qtx": "weapon.qtx",
    "weapon-fire.qtx": "weapon-fire.qtx",
    "weapon-sight-weapon.qtx": "weapon-sight-weapon.qtx",
    "weapon-catalog.qwc": "weapon-catalog.qwc",
    "audio/music/wth.mp3": "q41_wth.mp3",
    "audio/sounds/wpn/fn2000.wav": "q41_fn2000.wav",
    "audio/sounds/wpn/r.wav": "q41_r.wav",
}


def java_group_zero_weapon_config() -> dict[str, str]:
    """Read the real Java weapons.txt row used by WeaponCreator.createWeapon(0)."""
    if not JAVA_WEAPONS_ARCHIVE.is_file():
        raise SystemExit(f"Missing Java weapon-profile archive: {JAVA_WEAPONS_ARCHIVE}")
    with ZipFile(JAVA_WEAPONS_ARCHIVE) as archive:
        text = archive.read("weapons.txt").decode("latin-1")

    current_group: str | None = None
    group_zero: dict[str, str] = {}
    found_group_zero = False
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("//"):
            continue
        if line.startswith("[") and line.endswith("]"):
            current_group = line[1:-1].strip()
            if current_group == "0":
                found_group_zero = True
            continue
        if current_group == "0" and "=" in line:
            key, value = line.split("=", 1)
            group_zero[key.strip().upper()] = value.strip()
    if not found_group_zero:
        raise SystemExit("The Java weapons.txt archive has no [0] weapon group")
    if group_zero.get("NAME") != "FS2000":
        raise SystemExit(
            "The Java group-[0] reference changed; refusing to silently package "
            f"a different weapon: {group_zero.get('NAME')!r}"
        )
    return group_zero


def java_int(group: dict[str, str], key: str, default: int | None = None) -> int:
    value = group.get(key)
    if value is None:
        if default is None:
            raise SystemExit(f"Java weapon group [0] is missing {key}")
        return default
    try:
        parsed = int(value, 10)
    except ValueError as exc:
        raise SystemExit(f"Invalid Java weapon integer {key}={value!r}") from exc
    if not -32768 <= parsed <= 32767:
        raise SystemExit(f"Java weapon value {key}={parsed} does not fit a short")
    return parsed


def java_boolean(group: dict[str, str], key: str, default: bool) -> bool:
    value = group.get(key)
    return default if value is None else value == "1"


def java_float(group: dict[str, str], key: str, default: float) -> float:
    value = group.get(key)
    try:
        parsed = default if value is None else float(value)
    except ValueError as exc:
        raise SystemExit(f"Invalid Java weapon float {key}={value!r}") from exc
    if not (-64.0 <= parsed <= 64.0):
        raise SystemExit(f"Java weapon value {key}={parsed} is outside the QWG3 range")
    return parsed


def build_qwg3_from_java_group_zero() -> bytes:
    """Serialize the Java profile, including WeaponCreator's X_POS and melee rule."""
    group = java_group_zero_weapon_config()
    zoom = group.get("ZOOM")
    zoom_values = re.split(r"[;,]", zoom) if zoom is not None else []
    has_zoom = len(zoom_values) >= 3 and zoom_values[-1].strip() == "1"
    melee_animation = group.get("MELEEANIM") == "1"
    shoot_light = java_boolean(group, "SHOOT_LIGHT", True)

    flags = 0
    flags |= int(java_boolean(group, "TWOHANDS", False)) << 0
    flags |= int(java_boolean(group, "PATRONBUY", True)) << 1
    flags |= int(java_boolean(group, "CANSHOOT", True)) << 2
    flags |= int(java_boolean(group, "NEWFIREANIM", False)) << 3
    flags |= int(melee_animation) << 4
    flags |= int(java_boolean(group, "LEFT_HAND", False)) << 5
    flags |= int(has_zoom) << 6
    flags |= int(java_boolean(group, "IGNORE_SIGHT_IMAGE", False)) << 7
    flags |= int("X_POS" in group) << 8
    flags |= int(java_boolean(group, "CENTRE_ALIGHT", False)) << 9
    flags |= int(not shoot_light) << 10

    attack_intensity_q8 = 5 * 256 if melee_animation else 256
    custom_position_x = java_float(group, "X_POS", 1.0)
    payload = b"QWG3" + struct.pack(
        "<hhhhhHHfff",
        java_int(group, "CAPACITY", 1),
        java_int(group, "DELAY"),
        java_int(group, "SHOTTIME"),
        java_int(group, "RELOADTIME"),
        java_int(group, "DAMAGE"),
        flags,
        attack_intensity_q8,
        java_float(group, "KW", 1.0),
        java_float(group, "KH", 1.0),
        custom_position_x,
    )
    if sha256(payload) != EXPECTED_DATA_SHA256["weapon-gameplay.qwg"]:
        raise SystemExit(
            "The Java group-[0] QWG3 bytes changed: "
            f"{sha256(payload)}"
        )
    return payload


def stage_package_inputs() -> None:
    """Assemble the candidate payload without altering either SISX source."""
    for source, expected_hash, label in (
        (SOURCE_ARCHIVE, SOURCE_ARCHIVE_SHA256, "Level 41 source/assets archive"),
        (JAVA_WEAPONS_ARCHIVE, JAVA_WEAPONS_ARCHIVE_SHA256, "Java Level 1 weapon-profile archive"),
        (MDA_ROLLBACK_SISX, MDA_ROLLBACK_SISX_SHA256, "preserved MDA rollback SISX"),
    ):
        if not source.is_file():
            raise SystemExit(f"Missing {label}: {source}")
        if sha256(source.read_bytes()) != expected_hash:
            raise SystemExit(f"{label} hash changed; refusing to stage: {source}")

    payloads: dict[str, bytes] = {}
    with ZipFile(SOURCE_ARCHIVE) as archive:
        members = set(archive.namelist())
        for filename, member in LEVEL41_SOURCE_MEMBERS.items():
            if member not in members:
                raise SystemExit(f"The source/assets archive is missing {member}")
            payloads[filename] = archive.read(member)

    for filename, embedded_name in MDA_EMBEDDED_FILES.items():
        source = MDA_EMBEDDED_DIR / embedded_name
        if not source.is_file():
            raise SystemExit(
                "The read-only v1.0.43 MDA extraction is missing "
                f"{source}; restore it from {MDA_ROLLBACK_SISX.name} before packaging"
            )
        payloads[filename] = source.read_bytes()

    # The old QWG2 record omitted weapons.txt's X_POS=1.09. Rebuild only this
    # gameplay profile as QWG3 from Java source; preserve all other MDA payloads.
    payloads["weapon-gameplay.qwg"] = build_qwg3_from_java_group_zero()

    for filename, payload in payloads.items():
        expected_hash = EXPECTED_DATA_SHA256[filename]
        if sha256(payload) != expected_hash:
            raise SystemExit(
                f"Refusing to stage changed payload {filename}: "
                f"expected={expected_hash} actual={sha256(payload)}"
            )
        target = BASE_DIR / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)

    for resource_name in EXPECTED_RESOURCE_SHA256:
        source = MDA_EMBEDDED_DIR / resource_name
        if not source.is_file():
            raise SystemExit(f"The MDA extraction is missing application resource {source}")
        payload = source.read_bytes()
        if sha256(payload) != EXPECTED_RESOURCE_SHA256[resource_name]:
            raise SystemExit(f"Application resource differs from the MDA reference: {source}")
        (BASE_DIR / resource_name).write_bytes(payload)


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
        "QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY": "OFF",
        "QUANTUM_LEVEL41_TURN_INERTIA": "ON",
        "QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP": "ON",
        "QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS": "OFF",
        "QUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER": "OFF",
        "QUANTUM_LEVEL41_SNDDMA_AUDIO": "OFF",
    }
    mismatched = {
        key: (expected, cache_values.get(key))
        for key, expected in required_profile.items()
        if cache_values.get(key) != expected
    }
    if mismatched:
        raise SystemExit(f"Refusing to package an E32 without the complete E65 profile: {mismatched}")
    generated_ninja = build_root / "build.ninja"
    generated_ninja_text = generated_ninja.read_text(encoding="utf-8") if generated_ninja.is_file() else ""
    if "-DQUANTUM_LEVEL41_DSA_ARGB32_REQUIRED=1" not in generated_ninja_text:
        raise SystemExit("The generated build does not require DSA ARGB32 output mode")
    if "-DQUANTUM_LEVEL41_TURN_INERTIA=1" not in generated_ninja_text:
        raise SystemExit("The generated build does not compile with turn inertia enabled")
    if "-DQUANTUM_LEVEL41_DISABLE_WEAPON_SWAY=1" in generated_ninja_text:
        raise SystemExit("The Java weapon-animation candidate must retain movement-triggered sway")

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
    weapon_controller_source = PROJECT.parent / "src/core/software_weapon_controller.cpp"
    weapon_controller_text = weapon_controller_source.read_text(encoding="utf-8")
    if (
        "#if defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)" not in weapon_controller_text
        or "state.shakePending = true;" not in weapon_controller_text
        or "recoilOffset(state.dx, state.widthShift" not in weapon_controller_text
        or "state.frame += 1.0f * elapsedMs / 50.0f;" not in weapon_controller_text
        or "state.frame += 1.0f * elapsedMs / 16.6f;" not in weapon_controller_text
        or "static_cast<float>(currentOffset) +" not in weapon_controller_text
    ):
        raise SystemExit("The build must retain the Java float-frame/recoil port and movement-sway A/B path")
    if (
        "requestWalkSwayForInput" not in main_text
        or "gWalkState.turnVelocityDegreesPerSecondQ16 != 0)" in main_text
    ):
        raise SystemExit("Turn-only input must not enqueue Java Player.walk weapon sway")
    if "SetPosition(TTimeIntervalMicroSeconds(0))" not in audio_text:
        raise SystemExit("Effect playback must rewind the already-open player before replay")
    if (
        "void CQuantumLevel41AudioWorker::OnPlayComplete(" not in audio_text
        or "void CQuantumLevel41AudioWorker::ProcessPendingPlayCompletions()" not in audio_text
        or "iWorker.ProcessPendingPlayCompletions();" not in audio_text
    ):
        raise SystemExit("MDA play completion must be deferred to the audio poller")
    if (
        "bool updateLevel41GameplayFrame()" not in main_text
        or "SoftwareWalkController::update(" not in main_text
        or "gLevel41FrameElapsedMs" not in main_text
        or "gWalkForwardHeld" not in main_text
        or "gWalkLeftHeld" not in main_text
        or "setLevel41WalkKeyState(" not in main_text
    ):
        raise SystemExit("The frame-timed movement and held turning-input paths are missing")
    if "applyLevel41WalkEventStep" in main_text:
        raise SystemExit("The old discrete-key movement path must not replace frame-timed walking")
    if (
        "keyEvent.iCode == EKeyEscape || keyEvent.iScanCode == EStdKeyEscape" not in main_text
        or "return isLevel41ArrowKey(scanCode) ? scanCode : keyEvent.iCode;" not in main_text
        or "event.Type() == EEventKeyDown || event.Type() == EEventKeyUp" not in main_text
    ):
        raise SystemExit("The keypad scan-code/Escape dispatch contract is missing")
    for dsa_callback in ("void AbortNow(", "void TLevel41DsaObserver::Restart("):
        callback_start = main_text.find(dsa_callback)
        callback_brace = main_text.find("{", callback_start)
        callback_end = main_text.find("\n    }", callback_brace)
        if (
            callback_start < 0
            or callback_brace < 0
            or callback_end < 0
            or "clearLevel41WalkInput();" not in main_text[callback_brace:callback_end]
        ):
            raise SystemExit(
                f"Held walk input must be cleared in the DSA callback {dsa_callback}"
            )
    mesh_source = PROJECT.parent / "src/core/software_mesh_renderer.cpp"
    mesh_text = mesh_source.read_text(encoding="utf-8")
    if (
        "const Vector3D& worldPosition = mesh.vertices[vertexIndex];" not in mesh_text
        or "getSingleVertexLight(brightness, polygon, worldPosition," not in mesh_text
        or "options.shootIntensity > 0" not in mesh_text
    ):
        raise SystemExit("The mesh renderer is missing source/world-space shot-light evaluation")
    phase_setup = main_text.find("SoftwareMeshRenderInstrumentation instrumentation;")
    phase_guard = main_text.rfind(
        "#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)", 0, phase_setup
    )
    phase_end = main_text.find("#endif", phase_setup)
    if (
        phase_setup < 0
        or phase_guard < 0
        or phase_end < phase_setup
        or "options.instrumentation = &instrumentation;" not in main_text[phase_setup:phase_end]
    ):
        raise SystemExit("Render phase timing is not contained by the opt-in profile guard")
    walk_source = PROJECT.parent / "src/core/software_walk_controller.cpp"
    walk_header = PROJECT.parent / "inc/software_walk_controller.h"
    walk_text = walk_source.read_text(encoding="utf-8")
    walk_header_text = walk_header.read_text(encoding="utf-8")
    if (
        ": walkSpeed(300), turnStepDegrees(10)," not in walk_text
        or "frameScaledDisplacement(state.velocityX, elapsedMs)" not in walk_text
        or "frameScaledDisplacement(state.velocityZ, elapsedMs)" not in walk_text
        or "const QInt32 KWalkFrameTimeBaseMs = 50;" not in walk_text
        or "updateTurnInertia(input.turn, elapsedMs, settings, state);" not in walk_text
        or "turnAccelerationDegreesPerSecondSquared(240)" not in walk_text
        or "turnDecelerationDegreesPerSecondSquared(360)" not in walk_text
        or "underRoof = height.underRoof;" not in walk_text
        or "per-frame acceleration, not a per-key step" not in walk_header_text
        or "Turn-inertia builds accelerate/decelerate" not in walk_header_text
    ):
        raise SystemExit("The walkSpeed=300 frame-timed walk or smooth-turn controller is missing")
    regression_source = PROJECT.parent / "host_tests/level41_walk_weapon_regression_test.cpp"
    regression_text = regression_source.read_text(encoding="utf-8")
    if (
        "settings.walkSpeed == 300" not in regression_text
        or "state.z == -300" not in regression_text
        or "state.z == -1038" not in regression_text
        or "held turning ramps up angular velocity instead of stepping yaw" not in regression_text
        or "50 ms walking sway matches Weapon.update's width/height shift formula" not in regression_text
        or "void checkJavaWeaponAnimationPort()" not in regression_text
        or "frameIndex < 2500; ++frameIndex" not in regression_text
        or "Java look/turn input alone does not call Player.walk" not in regression_text
        or "QWG3 preserves Java group-[0] muzzle ratios and X_POS=1.09" not in regression_text
    ):
        raise SystemExit("The Java weapon-animation differential/QWG3 and walk/turn regressions are missing")
    rain_start = main_text.find("void drawLevel41Rain(")
    rain_end = main_text.find("#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)", rain_start)
    rain_function = main_text[rain_start:rain_end] if rain_start >= 0 and rain_end >= 0 else ""
    if (
        "if (gWalkState.underRoof)" not in rain_function
        or "gLevel41RainPausedUnderRoof = true;" not in rain_function
        or "gLevel41RainParticles[particle].y = 0;" not in rain_function
        or "drawStreak(" not in rain_function
    ):
        raise SystemExit("Rain must be hidden under the roof and reseeded on exit")


    if not writer.is_file():
        raise SystemExit(f"Local SIS writer not found: {writer}")
    if not WRITER_PROFILE_PATCH.is_file():
        raise SystemExit(f"Local 2 MiB heap compatibility patch is missing: {WRITER_PROFILE_PATCH}")
    writer_patch_sha256 = sha256(WRITER_PROFILE_PATCH.read_bytes())
    if not certificate.is_file() or not private_key.is_file():
        raise SystemExit("Certificate or temporary private key is missing")
    if output.exists() and not args.force:
        raise SystemExit(f"Refusing to overwrite existing candidate: {output} (use --force explicitly)")
    stage_package_inputs()

    input_files: list[tuple[Path, str]] = []
    for name, target in DATA_FILES:
        path = BASE_DIR / name
        if not path.is_file():
            raise SystemExit(f"Missing staged Level41/MDA payload: {path}")
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
    for path, target in input_files:
        relative_name = next(name for name, candidate in DATA_FILES if candidate == target)
        actual_hash = sha256(path.read_bytes())
        expected_hash = EXPECTED_DATA_SHA256.get(relative_name)
        if expected_hash is None or actual_hash != expected_hash:
            raise SystemExit(
                f"Staged payload hash does not match its verified source: {relative_name} "
                f"expected={expected_hash} actual={actual_hash}"
            )

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

    registration = BASE_DIR / "quantum_level41_walk_test_reg.rsc"
    caption = BASE_DIR / "quantum_level41_walk_test_loc.rsc"
    if not registration.is_file() or not caption.is_file():
        raise SystemExit("The staged application registration resources are missing")
    for resource in (registration, caption):
        expected_hash = EXPECTED_RESOURCE_SHA256[resource.name]
        if sha256(resource.read_bytes()) != expected_hash:
            raise SystemExit(f"Registration resource hash differs from the MDA reference: {resource}")

    certificate_info = check_certificate(certificate, private_key)
    original_executable_bytes = artifact.read_bytes()
    executable_bytes, heap_patch = patch_e32_heap(original_executable_bytes)
    output.parent.mkdir(parents=True, exist_ok=True)
    writer_succeeded = False
    try:
        with tempfile.TemporaryDirectory(prefix="level41-java-weapon-animation-mda-sis-") as temporary:
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
            source_archive_bytes = SOURCE_ARCHIVE.read_bytes()
            java_weapons_archive_bytes = JAVA_WEAPONS_ARCHIVE.read_bytes()
            mda_rollback_bytes = MDA_ROLLBACK_SISX.read_bytes()
            if sha256(source_archive_bytes) != SOURCE_ARCHIVE_SHA256:
                raise SystemExit("The source/assets archive changed during packaging")
            if sha256(java_weapons_archive_bytes) != JAVA_WEAPONS_ARCHIVE_SHA256:
                raise SystemExit("The Java weapon-profile archive changed during packaging")
            if sha256(mda_rollback_bytes) != MDA_ROLLBACK_SISX_SHA256:
                raise SystemExit("The MDA rollback SISX changed during packaging")
            source_paths = [
                PROJECT.parent / "CMakeLists.txt",
                PROJECT.parent / "CMakePresets.json",
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
                PROJECT.parent / "src/core/collision_height_computer.cpp",
                PROJECT.parent / "src/core/software_weapon_controller.cpp",
                PROJECT.parent / "inc/collision_height_computer.h",
                PROJECT.parent / "inc/software_mesh_renderer.h",
                PROJECT.parent / "inc/software_walk_controller.h",
                PROJECT.parent / "inc/software_weapon_controller.h",
                PROJECT.parent / "inc/omap1710_effects.h",
                PROJECT.parent / "inc/software_rain_renderer.h",
                PROJECT.parent / "host_tests/level41_walk_weapon_regression_test.cpp",
                PROJECT.parent / "host_tests/run_level41_walk_weapon_regression_test.sh",
                PROJECT / "package_java_weapon_animation_v1_0_48.py",
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
                    "location": "Level 41 interactive walk on E65/OMAP1710",
                    "movement": {
                        "model": "Java-style frame-timed velocity with grounded friction; held input adds walkSpeed per update and displacement scales by elapsedMs/50",
                        "walk_speed_before": 150,
                        "walk_speed_candidate": 300,
                        "scalar_increase_percent": 100,
                        "is_per_key_event_step": False,
                        "body_radius_world_units": 400,
                        "maximum_displacement_world_units_per_frame": 320,
                        "device_status": "compiled and host-regression tested; effective E65 walking speed is unverified",
                    },
                    "movement_triggered_weapon_sway": {
                        "status": "enabled for this candidate",
                        "mechanism": "Only nonzero Player.walk right/forward input enqueues shake; look/turn inertia alone does not; Java frameTime scales sway and damping in Weapon.update",
                        "firing_recoil": "retained as a separate path; host regression verifies non-zero recoil and shot-light state",
                        "device_status": "not-tested-on-E65",
                    },
                    "weapon_animation": {
                        "status": "ported from the supplied Java reference; host differential tests passed; E65 runtime not tested",
                        "java_reference": "src/code/Gameplay/Weapon.java Weapon.update()/draw()/createSprite() and src/code/utils/WeaponCreator.java",
                        "frame_model": "IEEE-754 binary32 with the Java per-update 50 ms shot step and 16.6 ms post-shot recovery, preserving statement order",
                        "offset_model": "Java integer division before float attackIntensity multiplication, byte-narrowed walk offsets, integer damping, short wrap and dx/dy bounds",
                        "walk_trigger": "only Player.walk(right, forward) with a nonzero walk axis; turn/look by itself does not sway",
                        "java_profile": "sym9.zip weapons.txt group [0]: FS2000, KW=0.84, KH=0.60, X_POS=1.09, no MELEEANIM",
                        "config_format": "QWG3 adds the Java X_POS value; QWG1/QWG2 remain supported",
                    },
                    "turn_inertia": {
                        "status": "enabled for this candidate",
                        "input": "held left/right axes sampled each gameplay frame; EEventKey-only fallback adds a 60-degree-per-second velocity impulse",
                        "response": "240 degrees/second-squared acceleration, 360 degrees/second-squared release deceleration, capped at 120 degrees/second; yaw is accumulated in Q16",
                        "device_status": "compiled and host-regression tested; turn feel on E65 is unverified",
                    },
                    "weapon_shot_illumination": "source/world-space shot-light evaluation is covered by host regression tests; E65 appearance remains unverified",
                    "fps": "no controlled E65 A/B measurement is available; no FPS improvement is claimed",
                    "reported_audio_issues": {
                        "post_shot_crash": "reported on earlier MDA builds; cause remains unconfirmed and this candidate has not been run on an E65",
                        "delayed_sound": "reported previously; MDA playback timing remains unverified on device",
                    },
                    "dsa_output_mode": "compile-time DSA ARGB32 required; actual E65 display mode unverified",
                    "keyboard_input": {
                        "arrow_dispatch": "prefer arrow scan codes and fall back to key codes",
                        "escape_exit": "recognize EKeyEscape by key code or EStdKeyEscape scan code",
                        "movement": "held key state is sampled by each gameplay frame; EEventKey is a one-frame pulse fallback",
                        "turning": "held left/right input accelerates yaw up to 120 degrees/second; release decelerates smoothly; EEventKey-only fallback uses an angular impulse",
                        "dsa_transitions": "clear held movement and turn inertia in AbortNow and Restart",
                        "device_status": "compiled; E65 keypad delivery and responsiveness unverified",
                    },
                    "audio_backend": "ordinary Symbian MDA; SNDDMA adapter and SNDDMA audio are OFF; MIDI is disabled",
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
                    "audio_player_safety": "separate music, shot, and reload players; effects are pre-opened and completion processing is deferred to the audio poller; E65 behavior is unverified",
                    "play_completion_handling": {
                        "callback": "MDA MapcPlayComplete only latches the completion error/event",
                        "worker_polling": "CQuantumLevel41AudioPoller::RunL processes pending completions after the callback unwinds",
                        "device_status": "compiled; E65 behavior unverified; not claimed as a crash fix",
                    },
                    "rain_effect": {
                        "level1_config": "WEATHER=RAIN, count=8, size=7",
                        "under_roof": "not simulated or drawn while the cached collision-height result is underRoof; drops reseed at the top on exit",
                        "effects_asm_callsite": True,
                        "device_status": "host roof-detection regression passed; E65 behavior unverified",
                    },
                    "midi": False,
                    "audible_playback_verified": False,
                },
                "source_provenance": {
                    "source_archive": {
                        "file": SOURCE_ARCHIVE.name,
                        "drive_id": "1lUuQ-BUSA0ABKCjWoCLELBVagUBGYJkr",
                        "size_bytes": len(source_archive_bytes),
                        "sha256": SOURCE_ARCHIVE_SHA256,
                        "role": "Java reference and Level 41 asset source; not a release label",
                    },
                    "java_weapon_profile_source": {
                        "file": JAVA_WEAPONS_ARCHIVE.name,
                        "drive_id": "1elEgbqUS3KhYfKuusXIwS1LaVF_o6bki",
                        "size_bytes": len(java_weapons_archive_bytes),
                        "sha256": JAVA_WEAPONS_ARCHIVE_SHA256,
                        "role": "source for group-[0] Java WeaponCreator settings, including X_POS=1.09",
                    },
                    "mda_audio_gameplay_source": {
                        "file": MDA_ROLLBACK_SISX.name,
                        "drive_id": "1uCqoNjgXSYLAryO1r7n2c7MydWtlYy3h",
                        "size_bytes": len(mda_rollback_bytes),
                        "sha256": MDA_ROLLBACK_SISX_SHA256,
                        "role": "read-only MDA source for weather, weapon art/catalog, audio, and registration; its QWG2 is not reused",
                    },
                    "candidate_source": "active Level 41 source with walkSpeed=300; Weapon.update was rewritten against the supplied Java float/integer formulas; turn-only input no longer triggers weapon sway; group-[0] X_POS is carried in a Java-derived QWG3; held turning inertia and the required MDA/ASM/rain/3D renderer profile are retained",
                    "source_asset_hashes": {
                        name: EXPECTED_DATA_SHA256[name]
                        for name, _ in DATA_FILES
                    },
                    "preserved_mda_rollback_unchanged": True,
                    "comparison_with_latest_compiled_asset_sisx": "not-performed; no claim that current archive assets match that SISX",
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
                "mda_rollback_v1_0_43": {
                    "file": MDA_ROLLBACK_SISX.name,
                    "size_bytes": len(mda_rollback_bytes),
                    "sha256": MDA_ROLLBACK_SISX_SHA256,
                    "left_unchanged": True,
                },
                "level41_environment_assets": {
                    "mesh_source": "2026-10-16 Java/source-assets ZIP",
                    "level41_assets_sha256": {
                        name: EXPECTED_DATA_SHA256[name]
                        for name, _ in DATA_FILES[:7]
                    },
                    "source_archive_payload_hashes_valid": True,
                    "comparison_with_latest_compiled_asset_sisx": "not-performed",
                },
                "audio_and_weapon_payloads": {
                    "source": "weather, weapon art/catalog and audio retained from v1.0.43 MDA SISX; weapon-gameplay.qwg generated as QWG3 from sym9.zip Java weapons.txt group [0]",
                    "sha256": {
                        name: EXPECTED_DATA_SHA256[name]
                        for name, _ in DATA_FILES[7:]
                    },
                    "mda_backend": True,
                    "midi": False,
                },
                "builder": {
                    "method": "local Symbian SDK 0.3.0 ARMv5T/EKA2 cross-build from the active working tree, then local SIS build/sign/audit; no GitHub Actions",
                    "sdk_version": "0.3.0",
                    "target": "ARMv5T/EKA2, OMAP1710, DSA ARGB32 required (fail-closed), Java-style frame-timed walkSpeed=300, movement weapon sway enabled, frame-timed turning inertia enabled, firing recoil retained, standard MDA audio, SNDDMA OFF, MIDI off, roof-aware rain, unchanged 3D mesh renderer, source/world-space shot light, requested OMAP1710 ASM paths",
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
                    "Local cross-build, host regressions, and the adapted signed-package/payload audit do not establish E65 installation, launch, or runtime behavior.",
                    "The candidate includes the ordinary MDA backend and the looping music/fire/reload payloads, but audible playback and codec support have not been verified on the E65.",
                    "The earlier MDA post-shot crash report remains unresolved; this candidate does not claim to fix it. SNDDMA is not used.",
                    "walkSpeed=300 doubles the per-frame acceleration scalar from 150; it is not a 300-unit key-event step. Actual movement remains affected by frame time, friction, collision, and the 320-unit displacement cap, and requires E65 testing.",
                    "The seven Level 41 environment payloads are sourced from the supplied 2026-10-16 ZIP and hash-checked against the package inputs; no comparison with the latest compiled asset SISX was performed.",
                    "The separate v1.0.43 MDA SISX remains unchanged; this candidate retains its weather, weapon art/catalog, audio, and registration payloads, but creates a new Java-derived QWG3 gameplay profile from sym9.zip.",
                    "Weapon animation now uses the Java float frame and integer/float offset order; host differential tests pass, but appearance, animation feel, and input behavior on an E65 remain untested and unaccepted.",
                    "Turn-only input no longer enqueues weapon sway; only Player.walk nonzero right/forward input does. The candidate otherwise retains the selected frame-timed turn-inertia profile.",
                    "DSA ARGB32 is required at compile time, but the E65 runtime mode, keypad delivery, roof edges, rain appearance, weapon illumination, FPS, and walking responsiveness remain unverified.",
                    "The local SIS writer uses a scoped 2 MiB E32 parser compatibility patch; this is not stock SDK package acceptance or E65 compatibility evidence.",
                ],
            }
            audit_output.parent.mkdir(parents=True, exist_ok=True)
            audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    finally:
        if writer_succeeded:
            private_key.unlink(missing_ok=True)

    return audit


def main() -> None:
    default_output = OUTPUT_DIR / (
        "quantum_level41_walk_test-v1.0.48-level41-mda-java-weapon-animation-e65-candidate.sisx"
    )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        type=Path,
        default=PROJECT.parent / "build/e65-level41-walk-turn-inertia-asm/level41_device_test/e32/quantum_level41_walk_test.exe",
    )
    parser.add_argument(
        "--sis-writer",
        type=Path,
        default=CACHE / "stage3-sis-writer/build/stage3_sis_writer",
    )
    parser.add_argument(
        "--certificate",
        type=Path,
        default=CACHE / "level41-mda-java-weapon-animation-v1_0_48/cert.pem",
    )
    parser.add_argument(
        "--private-key",
        type=Path,
        default=CACHE / "level41-mda-java-weapon-animation-v1_0_48/key.pem",
        help="Temporary test-signing key; deleted immediately after a successful signature",
    )
    parser.add_argument("--output", type=Path, default=default_output)
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--force", action="store_true", help="Replace only this explicitly named candidate and audit")
    print(json.dumps(build(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
