#!/usr/bin/env python3
"""Build and locally audit the isolated stage-3b E65 SISX candidate."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import tempfile
import tomllib
import wave
from pathlib import Path

CACHE = Path.home() / "quantum-sdk-cache"
WAV_TARGET = r"!:\data\mda_open_file_probe.wav"
UPSTREAM_COMMIT = "b105f752f84d1b5640052f64c4a703017810d7b8"
ABSEIL_COMMIT = "5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a"
RCOMP_COMMIT = "d3c2eadd3ff7826bdf9e1d92f447c357571af18b"
K_UID1 = 0x101F4A6B


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(command: list[str], *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, capture_output=True, text=True, check=False, env=env)
    if result.returncode != 0:
        raise SystemExit(
            "Command failed.\n"
            f"Command: {' '.join(command)}\n"
            f"Exit code: {result.returncode}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}"
        )
    return result


def read_wav(path: Path) -> tuple[bytes, dict[str, object]]:
    data = path.read_bytes()
    with wave.open(str(path), "rb") as source:
        details: dict[str, object] = {
            "channels": source.getnchannels(),
            "sample_width_bytes": source.getsampwidth(),
            "sample_rate_hz": source.getframerate(),
            "sample_frames": source.getnframes(),
            "duration_ms": round(1000 * source.getnframes() / source.getframerate()),
            "compression": source.getcomptype(),
            "samples_silent": not any(source.readframes(source.getnframes())),
        }
    if details != {
        "channels": 1,
        "sample_width_bytes": 2,
        "sample_rate_hz": 8000,
        "sample_frames": 800,
        "duration_ms": 100,
        "compression": "NONE",
        "samples_silent": True,
    } or len(data) != 1644:
        raise SystemExit(f"Unexpected stage-3b WAV format/size: {details}, {len(data)} bytes")
    return data, details


def certificate_audit(certificate: Path, private_key: Path) -> dict[str, object]:
    summary = run([
        "openssl", "x509", "-in", str(certificate), "-noout", "-subject", "-issuer", "-dates"
    ]).stdout
    details = run(["openssl", "x509", "-in", str(certificate), "-noout", "-text"]).stdout
    der = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-outform", "DER"],
        capture_output=True,
        check=False,
    )
    if der.returncode:
        raise SystemExit(f"Could not DER-encode certificate: {der.stderr.decode(errors='replace')}")
    key_check = subprocess.run(
        ["openssl", "rsa", "-in", str(private_key), "-check", "-noout"],
        capture_output=True,
        text=True,
        check=False,
    )
    public_key = re.search(r"Public-Key: \((\d+) bit\)", details)
    signature = re.search(r"Signature Algorithm: ([^\n]+)", details)
    lines = dict(line.split("=", 1) for line in summary.splitlines() if "=" in line)
    subject = lines.get("subject", "")
    issuer = lines.get("issuer", "")
    not_before = lines.get("notBefore", "")
    not_after = lines.get("notAfter", "")
    bits = int(public_key.group(1)) if public_key else 0
    algorithm = signature.group(1).strip() if signature else ""
    verified = subprocess.run(
        ["openssl", "verify", "-CAfile", str(certificate), str(certificate)],
        capture_output=True,
        text=True,
        check=False,
    )
    if (
        key_check.returncode != 0
        or verified.returncode != 0
        or not subject
        or subject != issuer
        or bits != 1024
        or algorithm != "sha1WithRSAEncryption"
        or not_before != "Jan  1 00:00:00 2000 GMT"
        or not_after != "Dec 31 23:59:59 2045 GMT"
    ):
        raise SystemExit(
            "Signing material does not match the previously E65-tested self-signed "
            f"RSA-1024/SHA-1, 2000–2045 profile:\n{summary}\n{details[:700]}\n"
            f"key check: {key_check.stderr}\ncertificate verify: {verified.stderr}"
        )
    return {
        "subject": subject,
        "issuer": issuer,
        "self_signed": True,
        "signature_algorithm": algorithm,
        "public_key_algorithm": "RSA",
        "public_key_bits": bits,
        "not_before": not_before,
        "not_after": not_after,
        "certificate_der_sha256": sha256(der.stdout),
        "certificate_verified_with_own_public_key": True,
        "private_key_valid_before_signing": True,
        "private_key_embedded": False,
    }


def compile_resource(
    source: str,
    stem: str,
    directory: Path,
    sdk: Path,
    preprocessor: Path,
    rcomp: Path,
    env: dict[str, str],
) -> tuple[bytes, dict[str, object]]:
    source_path = directory / f"{stem}.rss"
    preprocessed_path = directory / f"{stem}.rpp"
    resource_path = directory / f"{stem}.rsc"
    header_path = directory / f"{stem}.rsg"
    source_path.write_text(source, encoding="utf-8")
    run([
        str(preprocessor), "-E", "-P", "-x", "c",
        "-I", str(sdk / "include/platform"), str(source_path),
        "-o", str(preprocessed_path),
    ], env=env)
    run([
        str(rcomp), "-u", f"-s{preprocessed_path}",
        f"-o{resource_path}", f"-h{header_path}",
    ], env=env)
    resource = resource_path.read_bytes()
    if len(resource) < 16:
        raise SystemExit(f"Resource compiler emitted a truncated {stem}.rsc")
    uid1, uid2, uid3, uid_checksum = struct.unpack("<4I", resource[:16])
    return resource, {
        "target_source": f"{stem}.rss",
        "preprocessed_size": preprocessed_path.stat().st_size,
        "size": len(resource),
        "sha256": sha256(resource),
        "uid_header": {
            "uid1": f"0x{uid1:08X}",
            "uid2": f"0x{uid2:08X}",
            "uid3": f"0x{uid3:08X}",
            "uid_checksum": f"0x{uid_checksum:08X}",
        },
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


def build(args: argparse.Namespace) -> dict[str, object]:
    project = Path(__file__).resolve().parent
    sdk = args.sdk_root.resolve()
    config = tomllib.loads((project / "symbian.toml").read_text(encoding="utf-8"))
    project_config = config["project"]
    package = config["package"]
    application = config["application"]
    stem = Path(package["executable_name"]).stem

    artifact_path = args.artifact.resolve()
    writer = args.sis_writer.resolve()
    certificate = args.certificate.resolve()
    private_key = args.private_key.resolve()
    preprocessor = args.preprocessor.resolve() if args.preprocessor else sdk / "bin/clang"
    rcomp = args.rcomp.resolve()
    uidcrc = rcomp.with_name("uidcrc")
    for required in (artifact_path, writer, certificate, private_key, preprocessor, rcomp, uidcrc):
        if not required.is_file():
            raise SystemExit(f"Required build/signing tool or input is missing: {required}")
    version = (sdk / "host/share/symbian/VERSION").read_text(encoding="utf-8").strip()
    if version != "0.3.0":
        raise SystemExit(f"Expected Symbian SDK 0.3.0, found {version!r}")

    source_code = (project / "mda_player_open_file_stage3b.cc").read_text(encoding="utf-8")
    if "CMdaAudioPlayerUtility::NewL" not in source_code:
        raise SystemExit("Stage-3b must construct the MDA player with NewL")
    if "KOpenFileLOrdinal = 42" not in source_code or "iMdaLibrary.Lookup(KOpenFileLOrdinal)" not in source_code:
        raise SystemExit("Stage-3b must dynamically look up MDA ordinal 42")
    if not re.search(r"iOpenFileFunction\s*\(\s*iPlayer\s*,", source_code):
        raise SystemExit("Stage-3b must call only the dynamically resolved OpenFileL function")
    if re.search(r"(?:->|\.)\s*OpenFileL\s*\(", source_code):
        raise SystemExit("Stage-3b must not contain a static OpenFileL method call")
    if re.search(r"\bPlay\s*\(", source_code):
        raise SystemExit("Stage-3b must not call Play")
    if re.search(r"#\s*include\s*[<\"][^>\"]*midi|\b(?:CMidi|MMidi|TMidi)\w*\b", source_code, re.I):
        raise SystemExit("Stage-3b must not add MIDI paths")

    wav_path = project / "assets/mda_open_file_probe.wav"
    wav_data, wav_format = read_wav(wav_path)
    certificate_info = certificate_audit(certificate, private_key)
    output = args.output.resolve()
    audit_output = args.audit_output.resolve() if args.audit_output else output.with_name(
        output.stem + "-audit.json"
    )
    artifact = artifact_path.read_bytes()
    if not artifact:
        raise SystemExit("E32 artifact is empty")

    # RCOMP is rebuilt from the SDK's EPL source pin for the host's older glibc;
    # use its adjacent uidcrc companion to generate the standard 16-byte header.
    env = os.environ.copy()
    if args.ld_preload and "LD_PRELOAD" not in env:
        env["LD_PRELOAD"] = str(args.ld_preload)
    with tempfile.TemporaryDirectory(prefix="mda-openfile-stage3b-") as directory_name:
        temporary = Path(directory_name)
        registration_source = f'''CHARACTER_SET UTF8
#include <AppInfo.rh>
UID2 KUidAppRegistrationResourceFile
UID3 0x{project_config["uid3"]:08x}
RESOURCE APP_REGISTRATION_INFO
{{
  app_file = "{stem}";
  localisable_resource_file = "\\\\resource\\\\apps\\\\{stem}_loc";
}}
'''
        caption_source = f'''CHARACTER_SET UTF8
#include <AppInfo.rh>
RESOURCE LOCALISABLE_APP_INFO
{{
  short_caption = "{application["short_caption"]}";
  caption_and_icon = CAPTION_AND_ICON_INFO
  {{
    caption = "{application["caption"]}";
    number_of_icons = 0;
  }};
}}
'''
        registration, registration_info = compile_resource(
            registration_source, "registration", temporary, sdk, preprocessor, rcomp, env
        )
        caption, caption_info = compile_resource(
            caption_source, "caption", temporary, sdk, preprocessor, rcomp, env
        )
        registration_header = registration_info["uid_header"]
        if registration_header["uid1"] != f"0x{K_UID1:08X}" or registration_header["uid3"] != f"0x{project_config['uid3']:08X}":
            raise SystemExit(f"Registration resource has unexpected UID header: {registration_header}")
        registration_target = rf"!:\private\10003a3f\import\apps\{stem}_reg.rsc"
        caption_target = rf"!:\resource\apps\{stem}_loc.rsc"
        unsigned_path = temporary / "stage3b-unsigned.sis"
        writer_command = [
            str(writer),
            "--exe", str(artifact_path),
            "--registration", str(temporary / "registration.rsc"),
            "--registration-target", registration_target,
            "--caption", str(temporary / "caption.rsc"),
            "--caption-target", caption_target,
            "--data-file", str(wav_path),
            "--data-target", WAV_TARGET,
            "--package-uid", hex(package["uid"]),
            "--package-name", package["name"],
            "--vendor", package["vendor"],
            "--executable-name", package["executable_name"],
            "--version", ".".join(map(str, package["version"])),
            "--certificate", str(certificate),
            "--private-key", str(private_key),
            "--unsigned-output", str(unsigned_path),
            "--output", str(output),
        ]
        writer_result = run(writer_command, env=env)
        fields, files = parse_writer_report(writer_result.stdout)

    mda_imports = [
        int(ordinal)
        for ordinal in fields.get("mda_import_ordinals", "").split(",")
        if ordinal
    ]
    if mda_imports != [35]:
        raise SystemExit(
            "Expected the sole static MDA import to be NewL ordinal 35; "
            f"OpenFileL ordinal 42 must be runtime-only. Found: {mda_imports}"
        )
    if fields.get("midi_imports", ""):
        raise SystemExit(f"Stage-3b E32 unexpectedly imports MIDI: {fields['midi_imports']}")
    if fields.get("e32_uid3") != str(project_config["uid3"]):
        raise SystemExit(f"E32 UID3 mismatch in writer report: {fields.get('e32_uid3')}")
    if fields.get("executable_uid") != str(project_config["uid3"]):
        raise SystemExit(f"SIS executable UID3 mismatch: {fields.get('executable_uid')}")
    if fields.get("package_uid") != str(package["uid"]):
        raise SystemExit(f"SIS package UID mismatch: {fields.get('package_uid')}")
    if fields.get("signed") != "true" or fields.get("registered") != "true":
        raise SystemExit(f"SIS signer/registration audit failed: {fields}")
    if fields.get("files") != "4" or len(files) != 4:
        raise SystemExit(f"Expected E32, two resources, and the WAV in the SIS: {files}")
    wav_records = [record for record in files if record["target"] == WAV_TARGET]
    if len(wav_records) != 1 or wav_records[0]["size"] != len(wav_data) or wav_records[0]["sha1"] != hashlib.sha1(wav_data).hexdigest():
        raise SystemExit(f"Packaged WAV record does not match the input: {wav_records}")
    if fields.get("writer") != "stage3-mda-openfile-v1" or fields.get("upstream_commit") != UPSTREAM_COMMIT:
        raise SystemExit(f"Unexpected local SIS writer: {fields}")

    output_bytes = output.read_bytes()
    if not output_bytes:
        raise SystemExit("SISX writer emitted an empty candidate")
    # The only signing key is an explicit temporary input. Remove it now that
    # the signed package has been re-parsed and verified by the local inspector.
    key_removed = False
    if args.remove_private_key_after_signing:
        private_key.unlink()
        key_removed = True

    audit: dict[str, object] = {
        "candidate": output.name,
        "package": {
            "format": "SISX",
            "size_bytes": len(output_bytes),
            "sha256": sha256(output_bytes),
            "package_uid": f"0x{package['uid']:08X}",
            "executable_uid3": f"0x{project_config['uid3']:08X}",
            "version": package["version"],
            "signed": True,
            "registered": True,
            "files": files,
        },
        "e32": {
            "size_bytes": len(artifact),
            "sha256": sha256(artifact),
            "architecture": "ARMv5T/EKA2",
            "capabilities": 0,
            "all_static_imports": fields.get("e32_imports", ""),
            "static_mda_import_ordinals": mda_imports,
            "static_open_file_ordinal_42": False,
            "dynamic_open_file_ordinal": 42,
            "dynamic_lookup_api": "RLibrary::Load + RLibrary::Lookup",
            "midi_imports": [],
        },
        "runtime_scope": {
            "call_order": ["CMdaAudioPlayerUtility::NewL", "RLibrary::Load", "RLibrary::Lookup(42)"],
            "ordinal_42_missing": "report and do not call OpenFileL",
            "ordinal_42_found": "call only OpenFileL on C:\\data\\mda_open_file_probe.wav",
            "callback_wait_timeout_seconds": 15,
            "play_called": False,
            "midi_or_other_audio_paths": False,
            "device_test_status": "not yet tested on E65",
        },
        "wav": {
            "target": WAV_TARGET,
            "size_bytes": len(wav_data),
            "sha256": sha256(wav_data),
            **wav_format,
        },
        "registration_resource": registration_info,
        "caption_resource": caption_info,
        "signing_certificate": certificate_info,
        "signing_private_key_removed_after_signing": key_removed,
        "build": {
            "sdk_version": version,
            "sdk_root": str(sdk),
            "sis_writer_sha256": sha256(writer.read_bytes()),
            "upstream_symbian_platform_commit": UPSTREAM_COMMIT,
            "abseil_commit": ABSEIL_COMMIT,
            "rcomp_source_commit": RCOMP_COMMIT,
            "local_rcomp_sha256": sha256(rcomp.read_bytes()),
            "local_uidcrc_sha256": sha256(uidcrc.read_bytes()),
        },
    }
    audit_output.parent.mkdir(parents=True, exist_ok=True)
    audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, indent=2))
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, default=CACHE / "symbian-sdk")
    parser.add_argument("--artifact", type=Path, default=CACHE / "stage3b-build/e32/quantum_level41_mda_open_file_stage3b.exe")
    parser.add_argument("--sis-writer", type=Path, default=CACHE / "stage3-sis-writer/build/stage3_sis_writer")
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--rcomp", type=Path, default=CACHE / "host-tools/bin/rcomp")
    parser.add_argument("--preprocessor", type=Path)
    parser.add_argument("--ld-preload", type=Path, default=Path("/usr/lib/x86_64-linux-gnu/libstdc++.so.6"))
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "quantum_level41_mda_open_file_stage3b-v1.0.0-e65-candidate.sisx")
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--remove-private-key-after-signing", action="store_true")
    args = parser.parse_args()
    build(args)


if __name__ == "__main__":
    main()
