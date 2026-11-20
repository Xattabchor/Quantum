#!/usr/bin/env python3
"""Package and audit the isolated public-API E65 DevSound probe."""

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
from pathlib import Path

K_UID1_APP_REGISTRATION = 0x101F4A6B
K_UID2_APP_REGISTRATION = 0x101F8021


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inspect_e32_header(image: bytes, expected_uid3: int) -> dict[str, object]:
    """Read the identity and both capability words from an E32ImageHeader."""
    if len(image) < 144 or image[16:20] != b"EPOC":
        raise SystemExit("Artifact is not a complete E32 image header")
    uid1, uid2, uid3 = struct.unpack_from("<3I", image, 0)
    capabilities_low, capabilities_high = struct.unpack_from("<2I", image, 136)
    if uid1 != 0x1000007A or uid3 != expected_uid3:
        raise SystemExit(
            f"Unexpected executable E32 identity: UID1={uid1:#010x}, UID3={uid3:#010x}"
        )
    if capabilities_low != 0 or capabilities_high != 0:
        raise SystemExit(
            "The public-API diagnostic must remain zero-capability; got "
            f"{capabilities_low:#010x}:{capabilities_high:#010x}"
        )
    return {
        "uid1": f"0x{uid1:08X}",
        "uid2": f"0x{uid2:08X}",
        "uid3": f"0x{uid3:08X}",
        "capabilities_low": f"0x{capabilities_low:08X}",
        "capabilities_high": f"0x{capabilities_high:08X}",
        "zero_capabilities": True,
    }


def run(
    command: list[str],
    *,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
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


def sdk_tool_environment(sdk: Path, existing: dict[str, str]) -> dict[str, str]:
    """Use the restored host runtime only for the SDK's host executables."""
    env = existing.copy()
    runtime = sdk / "host-glibc-2.38/host-glibc-2.38/lib"
    paths = []
    if runtime.is_dir():
        paths.append(str(runtime))
    host_libs = sdk / "libexec/host"
    if host_libs.is_dir():
        paths.append(str(host_libs))
    for system_libs in (Path("/usr/lib/x86_64-linux-gnu"), Path("/lib/x86_64-linux-gnu")):
        if system_libs.is_dir():
            paths.append(str(system_libs))
    if env.get("LD_LIBRARY_PATH"):
        paths.append(env["LD_LIBRARY_PATH"])
    env["LD_LIBRARY_PATH"] = ":".join(paths)
    env["PATH"] = str(sdk / "bin") + os.pathsep + env.get("PATH", "")
    return env


def certificate_audit(certificate: Path, private_key: Path) -> dict[str, object]:
    summary = run([
        "openssl", "x509", "-in", str(certificate), "-noout",
        "-subject", "-issuer", "-dates",
    ]).stdout
    details = run([
        "openssl", "x509", "-in", str(certificate), "-noout", "-text",
    ]).stdout
    cert_public_key = run([
        "openssl", "x509", "-in", str(certificate), "-pubkey", "-noout",
    ]).stdout.strip()
    key_public_key = run([
        "openssl", "pkey", "-in", str(private_key), "-pubout",
    ]).stdout.strip()
    key_check = run([
        "openssl", "pkey", "-in", str(private_key), "-check", "-noout",
    ]).stdout
    verify = run([
        "openssl", "verify", "-CAfile", str(certificate), str(certificate),
    ]).stdout

    lines = dict(line.split("=", 1) for line in summary.splitlines() if "=" in line)
    subject = lines.get("subject", "")
    issuer = lines.get("issuer", "")
    not_before = lines.get("notBefore", "")
    not_after = lines.get("notAfter", "")
    public_key = re.search(r"Public-Key: \((\d+) bit\)", details)
    signature = re.search(r"Signature Algorithm: ([^\n]+)", details)
    bits = int(public_key.group(1)) if public_key else 0
    algorithm = signature.group(1).strip() if signature else ""
    if (
        not subject
        or subject != issuer
        or bits != 1024
        or algorithm != "sha1WithRSAEncryption"
        or cert_public_key != key_public_key
        or "Key is valid" not in key_check
        or not verify.strip().endswith(": OK")
    ):
        raise SystemExit(
            "Signing material must be a matching, self-signed RSA-1024/SHA-1 pair.\n"
            f"Certificate summary:\n{summary}\n"
            f"Signature/key: {algorithm}, {bits} bit\n"
            f"Key check: {key_check}\nCertificate check: {verify}"
        )

    return {
        "subject": subject,
        "issuer": issuer,
        "self_signed": True,
        "public_key_algorithm": "RSA",
        "public_key_bits": bits,
        "signature_algorithm": algorithm,
        "not_before": not_before,
        "not_after": not_after,
        "private_key_matches_certificate": True,
        "certificate_verifies_with_own_public_key": True,
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
    uid3: int | None = None,
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
    uid1, uid2, resource_uid3, uid_checksum = struct.unpack("<4I", resource[:16])
    if uid3 is not None and (
        uid1 != K_UID1_APP_REGISTRATION
        or uid2 != K_UID2_APP_REGISTRATION
        or resource_uid3 != uid3
    ):
        raise SystemExit(
            "Registration resource UID mismatch: "
            f"{uid1:#010x}/{uid2:#010x}/{resource_uid3:#010x}"
        )
    return resource, {
        "source": source_path.name,
        "size_bytes": len(resource),
        "sha256": sha256(resource),
        "uid_header": {
            "uid1": f"0x{uid1:08X}",
            "uid2": f"0x{uid2:08X}",
            "uid3": f"0x{resource_uid3:08X}",
            "uid_checksum": f"0x{uid_checksum:08X}",
        },
    }


def parse_writer_report(text: str) -> tuple[dict[str, str], list[dict[str, object]]]:
    fields: dict[str, str] = {}
    files: list[dict[str, object]] = []
    for line in text.splitlines():
        if "\t" in line:
            target, size, sha1 = line.split("\t")
            files.append({"target": target, "size_bytes": int(size), "sha1": sha1})
        elif "=" in line:
            key, value = line.split("=", 1)
            fields[key] = value
    return fields, files


def check_sdk_mda_stream_export(sdk: Path) -> dict[str, object]:
    relative_path = Path("share/symbian/native/defs/mediaclientaudiostream.dll.def")
    definition = sdk / relative_path
    if not definition.is_file():
        raise SystemExit(f"Missing SDK MDA stream export definition: {definition}")
    text = definition.read_text(encoding="utf-8")
    symbol = "_ZN21CMdaAudioOutputStream4NewLER29MMdaAudioOutputStreamCallbackP10CMdaServer"
    expected = re.compile(rf"^\s*{re.escape(symbol)}\s+@\s+3\s+NONAME\s*$", re.M)
    if not expected.search(text):
        raise SystemExit(
            "SDK does not map CMdaAudioOutputStream::NewL(observer, CMdaServer*) "
            "to ordinal 3"
        )
    return {
        "library": "mediaclientaudiostream.dll",
        "definition_file": relative_path.as_posix(),
        "symbol": symbol,
        "ordinal": 3,
        "verified": True,
    }


def check_probe_source(project: Path) -> None:
    source = (project / "devsound_caps_probe.cc").read_text(encoding="utf-8")
    cmake = (project / "CMakeLists.txt").read_text(encoding="utf-8")
    if "CMMFDevSound::NewL" not in source or "->Capabilities()" not in source or "->Config()" not in source:
        raise SystemExit("Probe must construct DevSound and read Capabilities()/Config()")
    if "iStreamLibrary.Load" not in source or "Lookup(KOutputStreamNewLOrdinal)" not in source:
        raise SystemExit("The optional MDA stream API must be loaded/looked up dynamically")
    if "KOutputStreamNewLOrdinal = 3" not in source:
        raise SystemExit("Unexpected MDA output stream NewL ordinal")
    if "Symbian::DevSound" not in cmake or "Symbian::AudioStream" in cmake:
        raise SystemExit("Link direct DevSound only; keep the optional stream DLL runtime-loaded")

    # Strip comments so explanatory safety notes do not count as API calls.
    executable_source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    forbidden_calls = re.compile(
        r"(?:->|\.)\s*(?:WriteL|PlayData|PlayInitL|SetConfigL|"
        r"RecordInitL|PlayToneL|PlayToneSequenceL|PlayDTMFStringL)\s*\("
    )
    if forbidden_calls.search(executable_source):
        raise SystemExit("Probe must not write PCM, start playback, record, or change DevSound config")
    if re.search(r"#\s*include\s*[<\"][^>\"]*midi|\b(?:CMidi|MMidi|TMidi)\w*\b", executable_source, re.I):
        raise SystemExit("Probe must not include or call MIDI APIs")
    if "EMMFSampleRate8000Hz" not in source or "EMMFSampleRate11025Hz" not in source or "EMMFSoundEncoding16BitPCM" not in source:
        raise SystemExit("Open tests must remain gated by reported PCM16 rate/channel capabilities")
    if "iStream->Open(&iSettings)" not in source:
        raise SystemExit("Expected the MDA stream-open smoke test")


def build(args: argparse.Namespace) -> dict[str, object]:
    project = Path(__file__).resolve().parent
    sdk = args.sdk_root.resolve()
    config = tomllib.loads((project / "symbian.toml").read_text(encoding="utf-8"))
    project_config = config["project"]
    package = config["package"]
    application = config["application"]
    stem = Path(package["executable_name"]).stem
    uid3 = int(project_config["uid3"])
    package_uid = int(package["uid"])

    artifact = args.artifact.resolve()
    writer = args.sis_writer.resolve()
    certificate = args.certificate.resolve()
    private_key = args.private_key.resolve()
    preprocessor = args.preprocessor.resolve() if args.preprocessor else sdk / "bin/clang"
    rcomp = args.rcomp.resolve() if args.rcomp else sdk / "bin/rcomp"
    uidcrc = rcomp.with_name("uidcrc")
    for required in (artifact, writer, certificate, private_key, preprocessor, rcomp, uidcrc):
        if not required.is_file():
            raise SystemExit(f"Required build/signing input is missing: {required}")
    if (sdk / "VERSION").read_text(encoding="utf-8").strip() != "0.3.0":
        raise SystemExit(f"Expected Symbian SDK 0.3.0 at {sdk}")
    artifact_bytes = artifact.read_bytes()
    if not artifact_bytes:
        raise SystemExit("E32 executable is empty")

    check_probe_source(project)
    e32_header = inspect_e32_header(artifact_bytes, uid3)
    sdk_mda_stream_export = check_sdk_mda_stream_export(sdk)
    certificate_info = certificate_audit(certificate, private_key)
    output = args.output.resolve()
    audit_output = args.audit_output.resolve() if args.audit_output else output.with_name(
        output.stem + "-audit.json"
    )
    tool_env = sdk_tool_environment(sdk, os.environ)

    with tempfile.TemporaryDirectory(prefix="e65-devsound-probe-") as temporary_name:
        temporary = Path(temporary_name)
        registration_source = f'''CHARACTER_SET UTF8
#include <AppInfo.rh>
UID2 KUidAppRegistrationResourceFile
UID3 0x{uid3:08x}
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
            registration_source, "registration", temporary, sdk,
            preprocessor, rcomp, tool_env, uid3=uid3,
        )
        caption, caption_info = compile_resource(
            caption_source, "caption", temporary, sdk,
            preprocessor, rcomp, tool_env,
        )
        (temporary / "registration.rsc").write_bytes(registration)
        (temporary / "caption.rsc").write_bytes(caption)

        registration_target = rf"!:\private\10003a3f\import\apps\{stem}_reg.rsc"
        caption_target = rf"!:\resource\apps\{stem}_loc.rsc"
        unsigned_path = temporary / "candidate-unsigned.sis"
        output.parent.mkdir(parents=True, exist_ok=True)
        writer_result = run([
            str(writer),
            "--exe", str(artifact),
            "--registration", str(temporary / "registration.rsc"),
            "--registration-target", registration_target,
            "--caption", str(temporary / "caption.rsc"),
            "--caption-target", caption_target,
            "--package-uid", hex(package_uid),
            "--package-name", package["name"],
            "--vendor", package["vendor"],
            "--executable-name", package["executable_name"],
            "--version", ".".join(str(value) for value in package["version"]),
            "--certificate", str(certificate),
            "--private-key", str(private_key),
            "--unsigned-output", str(unsigned_path),
            "--output", str(output),
        ])

    fields, files = parse_writer_report(writer_result.stdout)
    imports = [value for value in fields.get("e32_imports", "").split(",") if value]
    imported_dlls = sorted({value.split(":", 1)[0].lower() for value in imports})
    if "mmfdevsound.dll" not in imported_dlls:
        raise SystemExit(f"The E32 image did not retain the public DevSound import: {imports}")
    forbidden_dlls = {"mediaclientaudiostream.dll", "mediaclientaudio.dll", "midiclient.dll"}
    if forbidden_dlls.intersection(imported_dlls) or any("midi" in dll for dll in imported_dlls):
        raise SystemExit(f"Unexpected static MDA-stream/MDA-player/MIDI import: {imports}")
    if fields.get("midi_imports", ""):
        raise SystemExit(f"Unexpected MIDI imports: {fields['midi_imports']}")
    if fields.get("e32_uid3") != str(uid3) or fields.get("executable_uid") != str(uid3):
        raise SystemExit(f"E32 UID3 mismatch: {fields}")
    if fields.get("package_uid") != str(package_uid):
        raise SystemExit(f"Package UID mismatch: {fields.get('package_uid')}")
    if fields.get("signed") != "true" or fields.get("registered") != "true":
        raise SystemExit(f"Signed SISX/registration audit failed: {fields}")
    if fields.get("files") != "3" or len(files) != 3:
        raise SystemExit(f"Expected exactly the E32 executable and two registration resources: {files}")

    output_bytes = output.read_bytes()
    if not output_bytes:
        raise SystemExit("SISX writer emitted an empty candidate")
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
            "package_uid": f"0x{package_uid:08X}",
            "executable_uid3": f"0x{uid3:08X}",
            "version": package["version"],
            "signed": True,
            "registered": True,
            "embedded_files": files,
        },
        "e32": {
            "size_bytes": artifact.stat().st_size,
            "sha256": sha256(artifact.read_bytes()),
            "architecture": "ARMv5T/EKA2",
            "header": e32_header,
            "static_imports": imports,
            "static_imported_dlls": imported_dlls,
            "static_mda_stream_import": False,
            "static_mda_player_import": False,
            "midi_imports": [],
        },
        "runtime_scope": {
            "devsound_calls": [
                "CMMFDevSound::NewL",
                "InitializeL(observer, EMMFStatePlaying)",
                "Capabilities()",
                "Config()",
            ],
            "optional_mda_stream": {
                "dll": "mediaclientaudiostream.dll",
                "loaded_at_runtime": True,
                "newl_ordinal": 3,
                "sdk_definition": sdk_mda_stream_export,
                "static_import": False,
                "tests": [
                    "8000 Hz mono PCM16 if all three values are advertised",
                    "11025 Hz stereo PCM16 if all three values are advertised",
                ],
                "operation": "Open only; no PCM descriptor is written",
            },
            "play_init_or_play_data_called": False,
            "set_config_called": False,
            "tone_or_record_called": False,
            "midi_or_driver_dsp_dma_path": False,
            "audible_output_tested": False,
            "output_route_tested": False,
            "physical_dac_rate_verified": False,
            "device_test_status": "open-only E65 results recorded in RUNTIME_RESULTS.md; audible output and route untested",
            "runtime_results_file": "RUNTIME_RESULTS.md",
            "callback_timeout_seconds": 15,
        },
        "registration_resource": registration_info,
        "caption_resource": caption_info,
        "signing_certificate": certificate_info,
        "signing_private_key_removed_after_signing": key_removed,
        "build": {
            "sdk_version": "0.3.0",
            "local_sis_writer_sha256": sha256(writer.read_bytes()),
            "rcomp_sha256": sha256(rcomp.read_bytes()),
            "uidcrc_sha256": sha256(uidcrc.read_bytes()),
            "host_runtime": "restored GLIBC 2.38 for SDK preprocessor/resource compiler",
        },
        "writer_report": fields,
    }
    audit_output.parent.mkdir(parents=True, exist_ok=True)
    audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(audit, indent=2))
    return audit


def main() -> None:
    project = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--sis-writer", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--preprocessor", type=Path)
    parser.add_argument("--rcomp", type=Path)
    parser.add_argument("--output", type=Path, default=project / "quantum_level41_devsound_caps_diag-v1.0.0-e65-candidate.sisx")
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--remove-private-key-after-signing", action="store_true")
    build(parser.parse_args())


if __name__ == "__main__":
    main()
