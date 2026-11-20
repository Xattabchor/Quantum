#!/usr/bin/env python3
"""Build and audit the isolated stage-3 OpenFileL SISX candidate locally."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import tomllib
import wave
from pathlib import Path

from symbian.native import require_native
from symbian.packaging.registration import compile_registration

CACHE = Path.home() / "quantum-sdk-cache"
WAV_TARGET = r"!:\data\mda_open_file_probe.wav"
UPSTREAM_COMMIT = "b105f752f84d1b5640052f64c4a703017810d7b8"
ABSEIL_COMMIT = "5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a"
MDA_ORDINALS = {35, 42}  # CMdaAudioPlayerUtility::NewL / OpenFileL


def read_wav(path: Path) -> tuple[bytes, dict[str, object]]:
    data = path.read_bytes()
    with wave.open(str(path), "rb") as source:
        details = {
            "channels": source.getnchannels(),
            "sample_width_bytes": source.getsampwidth(),
            "sample_rate_hz": source.getframerate(),
            "sample_frames": source.getnframes(),
            "compression": source.getcomptype(),
            "samples_silent": not any(source.readframes(source.getnframes())),
        }
    if details != {
        "channels": 1,
        "sample_width_bytes": 2,
        "sample_rate_hz": 8000,
        "sample_frames": 800,
        "compression": "NONE",
        "samples_silent": True,
    } or len(data) != 1644:
        raise SystemExit(f"Unexpected stage-3 WAV format/size: {details}, {len(data)} bytes")
    return data, details


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


def certificate_audit(certificate: Path, private_key: Path) -> dict[str, object]:
    text = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-noout", "-subject", "-issuer", "-dates"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    details = subprocess.run(
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
    public_key = re.search(r"Public-Key: \((\d+) bit\)", details)
    signature_algorithm = re.search(r"Signature Algorithm: ([^\n]+)", details)
    lines = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
    subject = lines.get("subject", "")
    issuer = lines.get("issuer", "")
    not_before = lines.get("notBefore", "")
    not_after = lines.get("notAfter", "")
    algorithm = signature_algorithm.group(1).strip() if signature_algorithm else ""
    bits = int(public_key.group(1)) if public_key else 0
    if (
        key_check.returncode != 0
        or not subject
        or subject != issuer
        or bits != 1024
        or algorithm != "sha1WithRSAEncryption"
        or not not_before.endswith("2000 GMT")
        or not not_after.endswith("2045 GMT")
    ):
        raise SystemExit(
            "Signing material does not match the E65-tested self-signed RSA-1024/SHA-1, "
            f"2000–2045 profile: {text.strip()}\n{details[:500]}\n{key_check.stderr}"
        )
    return {
        "subject": subject,
        "issuer": issuer,
        "self_signed": True,
        "not_before": not_before,
        "not_after": not_after,
        "public_key_algorithm": "RSA",
        "public_key_bits": bits,
        "certificate_signature_algorithm": algorithm,
        "certificate_der_sha256": hashlib.sha256(der).hexdigest(),
        "private_key_valid": True,
        "private_key_embedded": False,
    }


def build(args: argparse.Namespace) -> dict[str, object]:
    project = Path(__file__).resolve().parent
    sdk_manifest = args.sdk_manifest.resolve()
    if not sdk_manifest.is_file():
        raise SystemExit(f"Symbian SDK manifest not found: {sdk_manifest}")
    os.environ["SYMBIAN_SDK_MANIFEST"] = str(sdk_manifest)

    config = tomllib.loads((project / "symbian.toml").read_text(encoding="utf-8"))
    project_config = config["project"]
    package = config["package"]
    application = config["application"]

    executable_path = args.artifact.resolve()
    if not executable_path.is_file():
        raise SystemExit(f"E32 artifact not found: {executable_path}")
    executable = executable_path.read_bytes()
    native = require_native()
    image = native.inspect_e32(executable)
    if image.dll or image.uid3 != project_config["uid3"]:
        raise SystemExit("E32 artifact is not the stage-3 executable/UID3")
    mda_imports = sorted(
        slot.ordinal
        for block in image.imports
        if block.dll.lower() == "mediaclientaudio.dll"
        for slot in block.slots
    )
    if set(mda_imports) != MDA_ORDINALS or len(mda_imports) != len(MDA_ORDINALS):
        raise SystemExit(f"Unexpected MDA imports: {mda_imports}; expected exactly [35, 42]")
    midi_dlls = sorted(
        block.dll for block in image.imports if "midi" in block.dll.lower()
    )
    if midi_dlls:
        raise SystemExit(f"Stage-3 E32 unexpectedly imports MIDI: {midi_dlls}")

    source_code = (project / "mda_player_open_file_probe.cc").read_text(encoding="utf-8")
    if "CMdaAudioPlayerUtility::NewL" not in source_code or "OpenFileL(" not in source_code:
        raise SystemExit("Stage-3 source must contain NewL and OpenFileL")
    if re.search(r"\bPlay\s*\(", source_code):
        raise SystemExit("Stage-3 source must not call Play")
    if re.search(r"#\s*include\s*[<\"][^>\"]*midi|\b(?:CMidi|MMidi|TMidi)\w*\b", source_code, re.I):
        raise SystemExit("Stage-3 source must not add MIDI paths")

    wav_path = project / "assets/mda_open_file_probe.wav"
    wav_data, wav_format = read_wav(wav_path)
    resources, icon_hashes = compile_registration(
        project,
        application,
        package["executable_name"],
        image.uid3,
    )
    if len(resources) != 2:
        raise SystemExit(f"Expected the standard registration and caption resources, found {len(resources)}")
    registration_target, registration = resources[0]
    caption_target, caption = resources[1]
    stem = Path(package["executable_name"]).stem
    expected_targets = [
        rf"!:\private\10003a3f\import\apps\{stem}_reg.rsc",
        rf"!:\resource\apps\{stem}_loc.rsc",
    ]
    if [registration_target, caption_target] != expected_targets:
        raise SystemExit(f"Unexpected registration resource targets: {[registration_target, caption_target]}")

    writer = args.sis_writer.resolve()
    certificate = args.certificate.resolve()
    private_key = args.private_key.resolve()
    if not writer.is_file() or not certificate.is_file() or not private_key.is_file():
        raise SystemExit("Local SIS writer, certificate, or private key is missing")
    certificate_info = certificate_audit(certificate, private_key)
    output = args.output.resolve()
    audit_output = (
        args.audit_output.resolve()
        if args.audit_output is not None
        else output.with_name(output.stem + "-audit.json")
    )

    with tempfile.TemporaryDirectory(prefix="mda-openfile-sis-") as directory:
        temporary = Path(directory)
        registration_path = temporary / "registration.rsc"
        caption_path = temporary / "caption.rsc"
        registration_path.write_bytes(registration)
        caption_path.write_bytes(caption)

        def command(unsigned_path: Path, signed_path: Path) -> list[str]:
            return [
                str(writer),
                "--exe", str(executable_path),
                "--registration", str(registration_path),
                "--registration-target", registration_target,
                "--caption", str(caption_path),
                "--caption-target", caption_target,
                "--package-uid", hex(package["uid"]),
                "--package-name", package["name"],
                "--vendor", package["vendor"],
                "--executable-name", package["executable_name"],
                "--version", ".".join(map(str, package["version"])),
                "--certificate", str(certificate),
                "--private-key", str(private_key),
                "--unsigned-output", str(unsigned_path),
                "--output", str(signed_path),
            ]

        # First prove the local extension preserves the SDK's existing package
        # profile exactly when no additional data file is present.
        native_unsigned = native.build_application_sis(executable, resources, **package)
        native_signed = native.sign_sis(
            native_unsigned, certificate.read_bytes(), private_key.read_bytes()
        )
        baseline_unsigned = temporary / "baseline-native-compatible.sis"
        baseline_signed = temporary / "baseline-native-compatible.sisx"
        baseline_stdout = run_writer(command(baseline_unsigned, baseline_signed))
        baseline_fields, _ = parse_writer_report(baseline_stdout)
        baseline_info = native.inspect_sis(native_signed)
        baseline_unsigned_bytes = baseline_unsigned.read_bytes()
        baseline_signed_bytes = baseline_signed.read_bytes()
        if (
            baseline_unsigned_bytes != native_unsigned
            or baseline_signed_bytes != native_signed
            or not baseline_info.signed_package
            or baseline_info.options.uid != package["uid"]
            or baseline_info.options.version != package["version"]
            or baseline_fields.get("writer") != "stage3-mda-openfile-v1"
            or baseline_fields.get("upstream_commit") != UPSTREAM_COMMIT
            or baseline_fields.get("signed") != "true"
            or baseline_fields.get("files") != "3"
        ):
            raise SystemExit("No-WAV local SIS/SISX bytes do not match the native SDK builder/signer")

        # The local extension then builds and signs the one bounded !:\\data\\ WAV.
        candidate_unsigned = temporary / "candidate.sis"
        candidate_staged = temporary / "candidate.sisx"
        candidate_command = command(candidate_unsigned, candidate_staged)
        candidate_command.extend(("--data-file", str(wav_path), "--data-target", WAV_TARGET))
        candidate_stdout = run_writer(candidate_command)
        fields, embedded_files = parse_writer_report(candidate_stdout)
        expected_file_targets = [
            rf"!:\sys\bin\{package['executable_name']}",
            registration_target,
            caption_target,
            WAV_TARGET,
        ]
        embedded_by_target = {item["target"]: item for item in embedded_files}
        wave_info = embedded_by_target.get(WAV_TARGET)
        expected_sha1 = hashlib.sha1(wav_data).hexdigest()
        if (
            fields.get("writer") != "stage3-mda-openfile-v1"
            or fields.get("upstream_commit") != UPSTREAM_COMMIT
            or fields.get("signed") != "true"
            or fields.get("registered") != "true"
            or fields.get("package_uid") != str(package["uid"])
            or fields.get("package_name") != package["name"]
            or fields.get("vendor") != package["vendor"]
            or fields.get("executable_name") != package["executable_name"]
            or fields.get("executable_uid") != str(project_config["uid3"])
            or fields.get("executable_sha1") != hashlib.sha1(executable).hexdigest()
            or fields.get("version") != ".".join(map(str, package["version"]))
            or fields.get("files") != "4"
            or [item["target"] for item in embedded_files] != expected_file_targets
            or wave_info is None
            or wave_info["size"] != len(wav_data)
            or wave_info["sha1"] != expected_sha1
        ):
            raise SystemExit("Signed stage-3 SISX metadata, signature, order, or WAV payload audit failed")

        signed = candidate_staged.read_bytes()
        unsigned = candidate_unsigned.read_bytes()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(signed)

    audit = {
        "audit_status": "passed-local-package-audit",
        "runtime_status": "not-assessed-by-local-package-build",
        "scope": {
            "tests_newl": True,
            "tests_openfilel_on_packaged_wav": True,
            "calls_play": False,
            "imports_or_calls_midi": False,
            "claims_audible_playback": False,
        },
        "candidate": {
            "file": output.name,
            "size_bytes": len(signed),
            "sha256": hashlib.sha256(signed).hexdigest(),
            "signed_package": True,
            "application_registered": True,
            "package_uid": f"0x{package['uid']:08X}",
            "executable_uid3": f"0x{image.uid3:08X}",
            "version": package["version"],
            "package_name": package["name"],
            "vendor": package["vendor"],
        },
        "local_builder": {
            "source": "local C++ helper; not GitHub Actions",
            "helper_path": str(writer),
            "helper_sha256": hashlib.sha256(writer.read_bytes()).hexdigest(),
            "sdk_version": "0.3.0",
            "sis_writer_upstream_commit": UPSTREAM_COMMIT,
            "abseil_commit": ABSEIL_COMMIT,
            "additional_target_profile": "one safe filename under !:\\data\\",
        },
        "native_profile_equivalence_without_wav": {
            "unsigned_byte_identical": True,
            "signed_byte_identical": True,
            "unsigned_size_bytes": len(native_unsigned),
            "unsigned_sha256": hashlib.sha256(native_unsigned).hexdigest(),
            "signed_size_bytes": len(native_signed),
            "signed_sha256": hashlib.sha256(native_signed).hexdigest(),
        },
        "signing_certificate": certificate_info,
        "e32": {
            "file": executable_path.name,
            "size_bytes": len(executable),
            "sha256": hashlib.sha256(executable).hexdigest(),
            "uid3": f"0x{image.uid3:08X}",
            "architecture": image.architecture,
            "kernel": image.kernel,
            "capabilities": f"0x{image.capabilities:08X}",
            "mediaclientaudio_ordinals": mda_imports,
            "midi_imports": midi_dlls,
            "source_sha256": hashlib.sha256(source_code.encode()).hexdigest(),
        },
        "wav": {
            "target": WAV_TARGET,
            "size_bytes": len(wav_data),
            "sha256": hashlib.sha256(wav_data).hexdigest(),
            "sha1": expected_sha1,
            **wav_format,
        },
        "embedded_files": embedded_files,
        "registration_icon_source_sha256": icon_hashes,
        "unsigned_with_wav": {
            "size_bytes": len(unsigned),
            "sha256": hashlib.sha256(unsigned).hexdigest(),
        },
        "limitations": [
            "Local package structure and RSA/SHA-1 signature were verified by the adapted v0.3.0 inspector.",
            "The no-WAV baseline is byte-identical to the installed native builder and signer.",
            "E65 installation and runtime behavior are not assessed by this local package builder.",
            "OpenFileL initialization success does not establish playback or audible output.",
        ],
    }
    audit_output.parent.mkdir(parents=True, exist_ok=True)
    audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    return audit


def main() -> None:
    project = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        type=Path,
        default=CACHE / "level41-mda-open-file-build/quantum_level41_mda_open_file_diag.exe",
        help="Locally built stage-3 E32 .exe",
    )
    parser.add_argument(
        "--sis-writer",
        type=Path,
        default=CACHE / "stage3-sis-writer/build/stage3_sis_writer",
        help="Locally built helper from local_sis_writer/",
    )
    parser.add_argument(
        "--sdk-manifest",
        type=Path,
        default=CACHE / "symbian-sdk/sdk.json",
        help="SDK 0.3.0 sdk.json used to compile registration resources",
    )
    parser.add_argument(
        "--certificate",
        type=Path,
        default=CACHE / "mda-open-file-signing/cert.pem",
    )
    parser.add_argument(
        "--private-key",
        type=Path,
        default=CACHE / "mda-open-file-signing/key.pem",
        help="Temporary signing key; never written into this repository or SISX",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project / "quantum_level41_mda_open_file_diag-v1.0.0-e65-candidate.sisx",
    )
    parser.add_argument("--audit-output", type=Path)
    print(json.dumps(build(parser.parse_args()), indent=2))


if __name__ == "__main__":
    main()
