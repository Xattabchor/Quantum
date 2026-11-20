# E65 shot probe: partial player ABI and ROM comparison

## Evidence and scope

ROM SHA-256 `3b4ba70bab1a6b17f214ae1c91a279520fe82254f62e39cfa4cb8cb7ae3724fa`. As noted in `scheduler-rom-comparison.md`, the dump is shorter than its declared ROM size, but the inspected player DLL, code and export table are within the captured portion. Static disassembly used Capstone in Thumb mode and the actual XIP virtual addresses. ROM image exported entry points come from `rom_extract.py`; symbol identities come from the later SymbianSource `oss.FCL.sf.os.mmaudio` commit `a39c075cce88671b23d13598fda2f7cbf008f3bb`, `mmlibs/mmfw/MMPFiles/eabi/MediaClientAudio.def`, supplemented by structural comparison. Names inferred from this DEF alone are not independent proof of identity.

The SISX used is the tested v1.0.1, SHA-256 `a705a07dea4b28ead28e9d1cf1842faf33028c7681df2add6596f8c0a2bf9603`. Its uncompressed E32 payload starts at SISX offset `0x78c`, length `0xc804` as indicated in the containing SIS field; SHA-256 `ec0d3818379bb919e2134d9868f61ff9f0d2ca7b346c283adee2b3002ac33d6d`, the prior verified extraction hash. Probe code VAs below use its E32 header code base `0x8000` and code offset `0x9c`.

## Player entry points and Play call

- The later DEF assigns player `SetPosition` to ordinal 15, `NewL` to 35, and `OpenFileL(const TDesC16&)` to 42. Corresponding ROM exports are `0xF8A1116F`, `0xF8A10C4F` and `0xF8A10F63` (bit 0 denotes Thumb). The probe's documented static imports are 15 and 35; source dynamically resolves 42. Presence and structural compatibility are not full semantic verification of all three functions.
- ROM ordinal 35 at `0xF8A10C4E` allocates a 12-byte object, invokes its constructor at `0xF8A16DAC`, and writes a returned properties pointer to object offset +8. The constructor stores `0xF8A17D88` as the primary vtable pointer; vtable slot +0xC contains `0xF8A10E05` (Thumb). That function at `0xF8A10E04` loads the object's +8 pointer and branches to `0xF8A10DD8`.
- The candidate internal function at `0xF8A10DD8` checks state against 3; on equality it invokes an asynchronous helper with -18; otherwise it cancels another object, resets a count and calls `0xF8A10CE8`. This is structurally consistent with the later reference `CMMFMdaAudioPlayerUtility::Play`: already-playing -> asynchronous `KErrNotReady`, otherwise cancel repeat timer, reset count, then `DoPlay`. The reference has additional repeat logic that this short E65 path does not establish as identical.
- In the exact v1.0.1 executable, the instrumented call between the two atomic trace updates is at code VA `0x8A70–0x8A82`: it loads the player vtable, loads slot +0xC, invokes it with the player as `this`, and then executes the next counter update. The E65 result `P:1/1` shows this call returned at least once on the device; one shot was audible. These independent observations support that the compiled shot Play call's vtable slot corresponds to the ROM player Play entry, rather than showing an obvious wrong-slot dispatch. They do not validate all player ABI, source-level behavior, memory integrity or callbacks.
- Ordinal 42 at `0xF8A10F62` similarly loads the properties pointer and branches to a routine constructing a descriptor-like source and passing it onward. This is consistent with the reference wrapper shape of `OpenFileL(const TDesC&)`; the successful init callback on E65 supports this particular use but does not prove every semantic detail.

## What is still unknown

The completion event chain and the probe's complete compiled `CActive`/timer ABI were not yet independently traced. The reference `HandleEvent` from the later source invokes `MapcPlayComplete` on playback events under specific state conditions; it has not been mapped to a verified ROM address in this analysis. No machine-code evidence here shows which request created the stray signal. Neither the A/B probe nor a fix was built or tested as part of this comparison, and full ROM boot/audio execution in Unicorn remains unavailable. The game source is unchanged.

## Reproduction notes

Inventory: `python3 tools/research/e65/rom_extract.py --rom google_drive/romdumpplus_090549.dmp --out /tmp/e65rom --extract-glob '*mediaclientaudio.dll'`. Extract the E32 payload using the offset and length above and confirm its hash before disassembly. ROM address to file offset is `address & ~1` minus `0xF8000000`. The ROM constructor literal at `0xF8A16DB8` is `0xF8A17D88`. Reference source is later than the E65 and not asserted to be the ROM's exact source.
