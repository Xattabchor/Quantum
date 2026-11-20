# Stage-3b E65 candidate — local audit

## Candidate and distribution

| Item | Result |
|---|---|
| SISX | `quantum_level41_mda_open_file_stage3b-v1.0.0-e65-candidate.sisx` |
| Size | 51,152 bytes |
| SHA-256 | `8e12ed10263d8708d27ede7d5e0281fd08bf8f36ead135a3e8de2f67ef0a836e` |
| MD5 | `d79b7e423943a660569acc5b8fe0e21a` |
| Package UID / version | `0xE000A02F` / `1.0.0` |
| Executable UID3 | `0xE000A02E` |
| Drive file ID | `1JiaxS0tybOnZ8w36iMwv0DeaZsWA_lx6` |
| Direct download | <https://drive.google.com/uc?id=1JiaxS0tybOnZ8w36iMwv0DeaZsWA_lx6&export=download> |
| Drive upload check | 51,152 bytes; MD5 matches local file |
| Sharing | Not public; use an account with access to the existing candidate folder |

## Runtime behavior encoded in the candidate

- Calls MDA `NewL`, then loads `mediaclientaudio.dll` and dynamically calls
  `RLibrary::Lookup(42)`.
- If `Lookup(42)` returns null, displays `OpenFileL ordinal 42 not found` and
  makes no file-open call.
- If the lookup succeeds, calls only the dynamically resolved `OpenFileL` on
  `C:\\data\\mda_open_file_probe.wav`; it reports an immediate leave,
  `MapcInitComplete`, worker panic, or 15-second timeout.
- E32 size: 47,012 bytes; SHA-256:
  `b8d9452f05384d693376dfd6f07a99e4b57247bdd5ef0a200691bc7311824055`.
- E32 is ARMv5T/EKA2, zero capabilities. Its only static MDA import is
  `mediaclientaudio.dll` ordinal **35** (`NewL`); static ordinal **42** is
  absent. No MIDI DLL is imported.
- Packaged data: silent 100 ms mono 8 kHz/16-bit PCM WAV, 1,644 bytes, installed
  as `!:\\data\\mda_open_file_probe.wav` (SHA-256
  `c726d333dd159a31423f3480dbb1c5c4a9dfcd30efe1f7e12ade390dc92e8908`).
- The SISX contains four verified records: the E32 executable, app registration
  resource, localization resource, and WAV. The local inspector confirms the
  package is signed and registered and verifies the signer signature.
- Certificate: self-signed RSA-1024/SHA-1, valid 2000-01-01 through
  2045-12-31. The temporary private key was removed after the local SISX audit;
  it is neither embedded nor present in the project.

## Local build details

- Symbian SDK: **0.3.0**, restored from Drive; its `host/share/symbian/VERSION`
  was verified.
- Target compiler: SDK Clang 23.1.2, ARMv5T/EKA2; CMake 3.31.6 and Ninja
  1.11.1.1 host wheels used because SDK-bundled host utilities require newer
  GLIBC/GLIBCXX versions than this host.
- SDK Clang and `symbian-native` were run with the system
  `/usr/lib/x86_64-linux-gnu/libstdc++.so.6` preloaded.
- The Drive host-helper archive was extracted. Its `gawk`, `patchelf`, and
  `bison-version-check` files were inspected.
- `rcomp` was rebuilt from SymbianRevive `symbian-build` commit
  `d3c2eadd3ff7826bdf9e1d92f447c357571af18b` with the pinned modern-host patch;
  `uidcrc` was built from `symbian-platform` v0.3.0 commit
  `b105f752f84d1b5640052f64c4a703017810d7b8`.
- The local SIS writer is built from `symbian-platform` v0.3.0 commit
  `b105f752f84d1b5640052f64c4a703017810d7b8` and Abseil commit
  `5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a`, using host g++ 12.2 and the
  compatible system OpenSSL/zlib libraries.
- The full machine-readable report is
  `quantum_level41_mda_open_file_stage3b-v1.0.0-e65-candidate-audit.json`.

## E65 runtime result

**User-reported result (normalized):** `MapcInitComplete: success`, duration
`100 ms`, `Packaged 100 ms PCM WAV`, `C:\\data\\mda_open_file_probe.wav`, and
`No Play; MIDI / other audio paths OFF`.

This confirms that the app launched, ordinal 42 was available through the
dynamic lookup, and the dynamically dispatched `OpenFileL` call produced a
successful initialization callback for the packaged WAV. The 100 ms callback
duration matches the resource. This verifies file-open/initialization on this
E65, not playback or speaker output.

The earlier stage-3 static-import candidate's reported launch failure remains
an independent historical result. Since stage 3b resolved and called ordinal 42
successfully, a missing ordinal is ruled out for this E65; the earlier static
candidate's failure cause is still not proven. Stage-3b remains separate from
the unchanged stage-3 candidate and the verified stage-1/2 probe binaries.
