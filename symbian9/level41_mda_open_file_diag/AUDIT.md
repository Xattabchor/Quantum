# Stage-3 package audit

## Candidate

| Item | Result |
|---|---|
| File | `quantum_level41_mda_open_file_diag-v1.0.0-e65-candidate.sisx` |
| Size | 116,060 bytes |
| SHA-256 | `38301845f6068c1eeff9b9f0f7b6d300ec1d3095b3f38068f4c8cbc9023e1a51` |
| Package UID / version | `0xE000A02D` / `1.0.0` |
| Executable UID3 | `0xE000A02C` |
| Locally inspected signed package | Yes; RSA/SHA-1 signature verified by the adapted SIS inspector |
| E65 launch attempt | **Failed** — user reports `Menu: Feature not supported` |
| `OpenFileL` callback/result | **Not reported; not confirmed** |

## Embedded payload and executable

- E32: `quantum_level41_mda_open_file_diag.exe`, 111,964 bytes,
  SHA-256 `7bb04886ac972f254a347827821c0dec2d717697f3e6ab6c6cdcb2ae4d46f90f`.
- E32 target profile: ARMv5T / EKA2; zero capabilities.
- `mediaclientaudio.dll` imports exactly ordinals 35 (`NewL`) and 42
  (`OpenFileL`); no MIDI DLL is imported.
- The WAV is installed at `!:\data\mda_open_file_probe.wav`: 1,644 bytes,
  SHA-256 `c726d333dd159a31423f3480dbb1c5c4a9dfcd30efe1f7e12ade390dc92e8908`,
  SHA-1 `8a170ab6f01319d84876c789afaaba42203942a0`. It is 800 frames of silent,
  mono, 8 kHz, 16-bit, uncompressed PCM.
- The SIS inspector reported the executable, registration RSC, localization
  RSC, and WAV targets in canonical order. The WAV's inspected size and SHA-1
  match the source asset.

## Writer, signature, and compatibility checks

- Built locally with the installed Symbian SDK 0.3.0, upstream SIS sources at
  `b105f752f84d1b5640052f64c4a703017810d7b8`, pinned Abseil at
  `5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a`, and the host C++ compiler.
- The helper was compiled with g++ 12.2, SDK-bundled OpenSSL/zlib headers, and
  system OpenSSL 3.0.20 / zlib 1.2.13 libraries. The SDK prebuilt host archive
  was not linked because its C++ ABI requires a newer host glibc; the pinned
  Abseil/E32/SIS sources needed here were rebuilt locally instead.
- The local change is limited to bounded, single-level `!:\data\` files and
  their canonical inspection. The existing upstream RSA/SHA-1 signing path is
  retained.
- A no-WAV baseline from the adapted helper was byte-identical to the native
  SDK builder and signer:
  - unsigned SIS: 113,440 bytes, SHA-256
    `31d6dedfc039a4d7d26acc4749a3aab7e8d281fca0d1513acbd6da2e95d2402f`;
  - signed SISX: 114,240 bytes, SHA-256
    `a70aad2245a6228b890db2e0f833611d6d695929699b211915c861cc99c392c9`.
- Candidate certificate: self-signed RSA-1024, `sha1WithRSAEncryption`, valid
  2000-01-01 through 2045-12-31. The matching private key was temporary and
  has been removed after signing; it was not embedded in the SISX.

## Scope and limits

This audit establishes local E32 import metadata, package structure, embedded
file integrity, and signature verification. On this static-import candidate the
user reported `Menu: Feature not supported` and no app screen; therefore this
run yielded no `NewL`/`OpenFileL` callback or leave. A separate stage-3b
candidate has since produced a user-reported `MapcInitComplete: success` with a
100 ms duration on the same E65. Stage 3b resolves ordinal 42 dynamically, so
this confirms that the export exists and is callable on that device; a missing
export is ruled out there. The reason the stage-3 static-import candidate did
not launch remains unproven. Both candidates call no `Play`, use no MIDI, and
test no audible output.

The full machine-readable report, including per-file hashes and certificate
metadata, is `quantum_level41_mda_open_file_diag-v1.0.0-e65-candidate-audit.json`.

A separate stage-3b candidate checks ordinal 42 dynamically, with no static
`OpenFileL` import, and is documented in
[`level41_mda_open_file_stage3b`](../level41_mda_open_file_stage3b/README.md).
Its E65 `MapcInitComplete: success` / 100 ms result is recorded there and does
not overwrite or replace the stage-3 SISX above.
