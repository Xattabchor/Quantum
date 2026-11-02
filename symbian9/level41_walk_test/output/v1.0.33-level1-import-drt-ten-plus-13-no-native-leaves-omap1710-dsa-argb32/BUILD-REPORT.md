# v1.0.33 — v1.0.28 baseline + 13 DRT imports, no helper calls

## Status

The clean ARMv5T/EKA2 cross-build is complete. The final E32 import table and the unsigned 19-payload SIS passed the local audits. **No Nokia E65 test has been performed for v1.0.33; the device result is pending.** The SIS is ready for a controlled device test, but local validation does not establish that the E65 loader accepts these imports.

## Test objective and scope

This is a launch-only continuation of the user-reported-launched v1.0.28 control. It adds the 13 DRT ordinals present in the v1.0.32 configuration while excluding v1.0.32's native-leave/personality support and its EUser additions. It does not change audio APIs or request playback.

The complete `drtaeabi.dll` E32 import set is the v1.0.28 ten ordinals plus:

| Added ordinal | Export |
|---:|---|
| 123 | `_ZTVN10__cxxabiv117__class_type_infoE` |
| 158 | `__aeabi_unwind_cpp_pr0` |
| 159 | `__aeabi_unwind_cpp_pr1` |
| 160 | `__aeabi_unwind_cpp_pr2` |
| 180 | `__cxa_begin_catch` |
| 181 | `__cxa_begin_cleanup` |
| 183 | `__cxa_end_catch` |
| 184 | `__cxa_end_cleanup` |
| 204 | `_ZN23TCppRTExceptionsGlobalsC1Ev` |
| 213 | `_ZSt9terminatev` |
| 217 | `__cxa_type_match` |
| 218 | `_Unwind_VRS_Get` |
| 220 | `_Unwind_VRS_Set` |

The final DRT block contains **23 unique ordinals in 23 slots**, each with multiplicity one. Ordinals 222 and 223 are absent. EUser remains byte-for-byte identical as an import block to v1.0.28: 64 slots / 64 unique ordinals and no additions `[2060, 2061, 2082]`. Every other non-DRT DLL import block also matches v1.0.28 by slot multiplicity.

## Added helpers: static imports without callsites

The 12 added helper-function ordinals are retained as **data-only GOT imports**, not call relocations. The linker-retained address-probe function is not called by startup or other runtime code. I decoded the final shipped E32 bytes—not just the generated assembly—with Capstone 5.0.9: the 128-byte Thumb instruction body at VMA `0x00029e44` contains 64 decoded instructions, zero `BL`, zero `BLX`, and ends with `BX LR` followed by one alignment NOP. The following 48 bytes are the 12 `R_ARM_GOT_PREL` literal words, not executable instructions. The scan is bound to shipped E32 SHA-256 `0d23b4b408167a83870f1c2d577dd9d51a5f1155b51f7a461ea056e7e72fb2b4`; the function starts at file offset `0x21ee0`. See `scan_e32_helper_instructions.py` and its recorded output `final-e32-helper-instruction-scan.json`. The whole-image section-aware relocation audit independently finds zero `R_ARM_CALL`, `R_ARM_THM_CALL`, `R_ARM_JUMP24`, or `R_ARM_PLT32` relocations to any of the 12 added helper symbols. Each helper maps to one `R_ARM_GLOB_DAT` GOT slot. See `helper-address-probe.s`, `helper-callsite-audit.txt`, and the per-slot records in `e32-import-audit.json`.

To make those address-only slots representable by the SDK's ELF-to-E32 converter, the **temporary link-time-only DRT proxy** declares the 12 function ordinals as `DATA 4`. The proxy DSO is not a SIS payload. The final E32 stores the DLL, ordinal, and GOT fixup offset; it does not carry the proxy's function/data type annotation. No imported address is dereferenced or invoked on the launch path. As with all static-import controls, only the E65 test can determine how the device loader handles this exact import table.

The separate class-type-info vtable import is also behind the default-false volatile gate `quantumDrtAdditional13ImportProbeEnabled`, initialized to zero and never written. The gate's local probe does not call a DRT helper.

## Disabled features and limits

- SDK native-leave/personality support is off; no native-leave/personality object is linked, and the E32 exception-descriptor offset is zero.
- MDA, MIDI, gameplay audio, and playback are off. No MDA or MIDI DLL import appears in the E32.
- The three audio files remain in the SIS only to keep payloads identical to v1.0.28; no audio path or resource-to-weapon mapping is inferred.
- The v1.0.32 E65 popup is not used to infer a specific unsupported export, ROM state, or leave propagation.
- This static audit is not evidence of E65 loader acceptance, ROM export availability, helper execution, codec support, DSP access, audio playback, or performance.

## Build and package validation

The candidate was clean-built with Symbian SDK 0.3.0, Clang 23.1.2, CMake 3.30.5, ARMv5T/EKA2, and GnuPoc MakeSIS 4.0. The DSA ARGB32 / OMAP1710 Level 1 path is retained. The CMake import probe option for the additional 13 DRT ordinals is enabled; native leaves, MDA, DRT dynamic loading, Symbian audio, and MIDI are disabled. The scalar PDiv ASM and Mode-9 MUL-order experiments are not enabled. The completed E32/import audit records **22/22 passed checks**, including the final E32 instruction scan.

The shipped E32 is **190,916 bytes**, SHA-256 `0d23b4b408167a83870f1c2d577dd9d51a5f1155b51f7a461ea056e7e72fb2b4`. It has UID3/SID `0xe000a020`, zero capabilities, and heap bounds **4,096..2,097,152 bytes**. Its header CRC is valid. The SDK inspector requires a disposable copy capped at 1 MiB to read E32 metadata/imports; that copy was not used for instruction decoding, and the shipped 2 MiB heap remains unchanged. The instruction scan above reads the original shipped E32 bytes.

The unsigned SIS is **741,128 bytes**, SHA-256 `002e698f84393f7304108716daa755904da8c33bacc7967d1c481293712ade8a`, MD5 `2dc6ebf815d5f17c0f04cd1fe3de5f3a`. All 19 payload hashes and staged bytes match; SIS header UID/CRC, controller and data checksums, package target list, and application registration checks passed. All 18 non-E32 payloads match v1.0.28 byte-for-byte.

The unsigned SIS has been uploaded to [Google Drive](https://drive.google.com/file/d/1L-TVV6T-v5CSrJ0Ll_HwZRsIOf0Jz8Fu/view?usp=drivesdk); Drive reports the same 741,128-byte size and MD5 as the local artifact. The E65 result remains pending. The exact source patch, clean configure/build logs, link-time proxy, detailed E32 slot audit, helper-callsite audit, payload manifest, and SIS audit are retained beside this report.

Host tests were not rerun: the changes are limited to the Symbian import probe, link-time proxy metadata, and package. Host tests would not establish E65 import-loader acceptance.
