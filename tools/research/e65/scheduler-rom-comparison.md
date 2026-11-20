# E65 ROM / reference scheduler comparison

## Input and method

ROM `romdumpplus_090549.dmp`, SHA-256 `3b4ba70bab1a6b17f214ae1c91a279520fe82254f62e39cfa4cb8cb7ae3724fa`, mapped base `0xF8000000`. It is 0xDE000 bytes shorter than the ROM header's declared size. EUser.dll and its export table are within the captured bytes. `rom_extract.py` found `/Sys/Bin/EUser.dll`, 2184 exports; ordinals below were identified using the **later** reference DEF `SymbianSource/oss.FCL.sf.os.kernelhwsrv`, commit `0c3208650587ac0230aed8a74e9bddb5288023eb`, `kernel/eka/eabi/euseru.def`. That identification is supported by the machine-code behavior shown below; it does not independently establish every ordinal/name for this ROM.

Static ARM disassembly with Capstone 5: addresses below are ROM VAs; export addresses have ARM state bit clear. This is not a complete ROM boot or a running audio session.

## Direct comparisons

- Candidate `CActiveScheduler::Start`, ordinal 426 at `0xF8123FEC`, branches to the scheduler loop setup at `0xF8123E9C`, which calls `0xF811FF88`.
- At `0xF811FFA0–0xF811FFB0`, the latter checks a loop flag and calls a scheduler virtual function; `0xF811FFB8–0xF811FFD4` traverses the queue, comparing the request value to `0x80000001` (`KRequestPending`) and testing bit 0 of the adjacent flags. On an eligible object, `0xF811FFDC–0xF811FFF0` clears bits 0 and 1, stores the current object, and invokes a virtual function. On reaching the queue sentinel without an eligible object, it branches at `0xF811FFC8` to `0xF81234D0`, which loads `0x2E` (46) and branches to the common panic routine. This matches the essential no-ready-object path of the later `CActiveScheduler::DoRunL` in `ub_act.cpp`. The interpretation of the virtual wait and RunL is consistent with the referenced source; instruction matching alone does not prove all implementation details or source-version identity.
- Candidate `CActive::SetActive`, ordinal 1090 at `0xF81236CC`, checks bit 0 of `[object+8]`, checks `[object+0xC]`, then sets bit 0 of `[object+8]`. Candidate `CActive::Cancel`, ordinal 1088 at `0xF812352C`, tests the same flag, invokes a virtual method, waits on `[object+4]` and clears bits 0/1 at `[object+8]`. This agrees structurally with the later reference implementation using `iStatus` at +4 and flags at +8 on this ARM binary. It does not by itself validate the probe's compiled class layout or every SDK inline.
- Candidate `User::WaitForRequest(TRequestStatus&)`, ordinal 604 at `0xF812EFD8`, loops on a wait call while `[status] == 0x80000001`; if more than one wait was required, it passes the surplus count to another routine before returning. This matches the structure of the later reference `us_exec.cpp` implementation; identification of the called helpers is based on source comparison, not independent execution here.

## Limitations and next step

The ROM thus supports the previously described **scheduler mechanism** for panic 46 on this particular firmware; it does not show which request issued the extra signal during playback, nor establish that the multimedia player source is identical. The separate A/B E65 test remains necessary. Further useful ROM work: verify the actual compiled probe CActive layout and player-related ordinal mapping against this ROM, and investigate the playback event chain. The source in `oss.FCL.sf.os.mmaudio` has not yet been compared function-by-function with the E65 `mediaclientaudio.dll`.

Reproduce inventory with `python3 tools/research/e65/rom_extract.py --rom google_drive/romdumpplus_090549.dmp --out <output-dir> --extract-glob '*euser.dll'`; inspect `e32-exports.json` and disassemble the addresses above from the dump using ARM little-endian mode. Reference source is not part of the ROM dump and is used only as a comparison.
