# Level 41 QTX1 textures

These four QTX1 files were selectively restored from the Drive source/assets
archive `quantum last.zip` (Drive ID `1lUuQ-BUSA0ABKCjWoCLELBVagUBGYJkr`,
SHA-256 `90683d18db5e281647835506942ab44784e744b35f695ccd496d4cb7e6e544a7`).
They were compared against the active source inputs and regenerated locally with
`symbian9/tools/prepare_level41_device_assets.py`; each regenerated file is
byte-identical to its archive counterpart.

| QTX1 | Source image | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `forest.qtx` | `forestOrig.png` | 131,080 | `0b2963f5ff7371cb698be871ff37775d9cd82c42c3ad73bef7d89c274e98450e` |
| `map.qtx` | `map-selo.png` | 262,152 | `319a39401f971387172807a6f9b8832ba50e45fe8a6e066c540fbad6d140b697` |
| `river.qtx` | `river0.png` | 4,104 | `879f3ff189d631275a4747a0d5d82759ec391ec6ecdc6f173b11ec3ca7d4eb86` |
| `sky.qtx` | `skybox5-2.jpg` | 262,152 | `c89af7f2a11d405ab00bf23ce613b6a1a2cc07bf71f404b6cd7f700a0db8788e` |

Regenerate into this directory with:

```sh
python3 symbian9/tools/prepare_level41_device_assets.py \
  --assets symbian9/level41_device_test/assets/level41 \
  --output build-assets/level41-qtx
```

The source archive is a supplementary source/assets snapshot, not a compiled SIS
or E32. These checks establish equality with that archive and the checked-in
source images only; they do not establish that these files match assets embedded
in any compiled SIS/SISX.
