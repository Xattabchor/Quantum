# Local SIS writer adaptation

`cpp/symbian/sis/sis.cc` and `sis.h` are adapted from
[`hpnkv/symbian-platform` v0.3.0](https://github.com/hpnkv/symbian-platform/tree/v0.3.0/cpp/symbian/sis),
commit `b105f752f84d1b5640052f64c4a703017810d7b8`. The matching Apache-2.0
license is included as `LICENSE-APACHE`. The repository's normal license still
applies to the local helper and its changes.

The SIS-format extension adds a bounded `data_files` list. It accepts either
safe single-level ASCII filenames beneath `!:\data\` or safe relative paths
beneath the fixed app-private directory `!:\private\e000a020\` (the Level 1
walk executable's UID3). Private paths reject empty, `.` and `..` components,
forward slashes, unsupported characters, and targets longer than 192 bytes.
Targets must be unique and sorted case-insensitively. The writer emits these
files after the application registration/localization assets; the inspector
recognizes them and re-runs the canonical builder before accepting the
package. The existing RSA/SHA-1 signer and signature verifier are unchanged.
No MIDI path was added.

For the separate v1.0.37 walk candidate, the E32 inspector accepts either the
pinned 1 MiB maximum heap or the candidate's 2 MiB maximum. The builder copies
pinned `e32.cc` into its build directory and applies only this scoped check;
the upstream checkout remains untouched, and all other experimental E32
identity/layout checks remain unchanged. The source patch is
`level41_walk_test/local_sis_writer_2m_heap.patch`; its executable-payload
addition also enables signed-package E32 round-trip verification.

`src/main.cc` is a small local build/sign/audit executable. It writes the
unsigned package only when requested, signs it with the supplied certificate
and key, then re-inspects the signed bytes and reports each embedded target,
size and SHA-1. It does not retain or serialize the private key.

The helper is built locally from the pinned source; no CI workflow or remote
build is involved. See `build_local_sis_writer.py` and the parent project README
for the local SDK/dependency invocation and the native-writer equivalence test.
