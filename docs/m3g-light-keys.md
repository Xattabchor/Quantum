# M3G light keys

Reference for the `M3G_*` settings the lit pass reads. These keys were moved out
of `setting.txt`: the shipped config now carries only the baked-lightmap keys,
because the rest tune a **hardware-lamp** pipeline that mode 9 no longer uses
once the lightmap lives in the vertex colours.

All keys live in the **root table** of `setting.txt` — before the first
`[GROUP]` line. A key placed after a group header is read as `null` and silently
falls back to its default.

Reads happen in `code.utils.Main.init()`; consumers are in
`code.Rendering.M3GLitRenderer`. Defaults below are what you get when the key is
absent, which is now the normal case.

## Baked mode (keys kept in setting.txt)

Mode 9 stores the baked lightmap in the vertex colours. These three are the only
keys that describe how that lightmap is presented.

| key | default | meaning |
| --- | --- | --- |
| `M3G_LIT_WORLD9` | `0` | Routes the world through the sorted, depth-tested lit pass. Required by both bake modes. |
| `M3G_LIT_BAKED` | `0` | Submits a single white `AMBIENT` light at intensity 1.0 so the material does not multiply the lightmap away. Needs `WORLD9`. |
| `M3G_LIT_NO_LIGHTS` | `0` | Submits **no** light at all and moves the lightmap into material `EMISSIVE`. Needs `WORLD9`. Unverified on hardware. |

Resulting recipes, as reported by the render probe's `lights:` line:

| config | light |
| --- | --- |
| none | `ambient(i=0.2509804) sun=false` |
| `M3G_LIT_BAKED=1` | `neutralAmbient(i=1.0,c=ffffff)` |
| `M3G_LIT_NO_LIGHTS=1` | `none(emissive)` |

## Lamps (removed from setting.txt — documented here)

These only matter when at least one real light is submitted, i.e. **not** in
baked or no-lights mode. Setting them while a bake mode is on has no effect.

### Ambient

| key | default | meaning |
| --- | --- | --- |
| `M3G_LIT_AMBIENT` | `32` | Ambient lamp strength, 0..255. Multiplies the whole world. |
| `M3G_LIT_AMBCOL` | `0xffffff` | Ambient RGB, written as `R,G,B`. |
| `M3G_LIT_AMBPULSE` | `64` | Extra ambient added during a muzzle flash, 0..255. `0` disables the global "whole forest lights up" effect. |

### Directional (sun)

| key | default | meaning |
| --- | --- | --- |
| `M3G_LIT_DIR` | `150` | Directional lamp strength, 0..255. `0` removes the sun node entirely. |
| `M3G_LIT_DIRCOL` | `0xffffff` | Sun RGB, written as `R,G,B`. |

The sun transform is fixed (30° X, -35° Y in view space) and not configurable.

### Omni (muzzle flash / flashlight)

| key | default | meaning |
| --- | --- | --- |
| `M3G_OMNI_BASE` | `0` | Constant omni contribution, 0..255. |
| `M3G_OMNI_FLASH` | `204` | Omni boost at full flash, 0..255. |
| `M3G_OMNI_HALFX` | `4000` | Half-distance of the attenuation curve, in percent of `DirectX7.distX`. |
| `M3G_OMNI_COL` | `0xffffff` | Omni RGB, written as `R,G,B`. |
| `M3G_OMNI_X` | `8` | Lamp offset from the camera, screen units. |
| `M3G_OMNI_Y` | `-6` | Same, vertical. |
| `M3G_OMNI_Z` | `-45` | Same, forward. |
| `M3G_OMNI_CULL` | `0` | While a flash is live, cap the distance cull at this view depth. `0` disables. Two live lamps double per-vertex lighting cost on software m3g, which is why this exists. |

Attenuation is `1 / (0.15 + 0.85 * d^2 / half^2)`, so the lamp is at 50% at
`half` distance and never falls below ~6.7× peak.

### Self-test

| key | default | meaning |
| --- | --- | --- |
| `M3G_LIT_SELFTEST` | `0` | Runs a full flash for N seconds after level load, weapon-independent. Proves the light path on a real handset. Resets itself after firing once. |

## Pass tuning (removed from setting.txt — documented here)

These shape the lit pass itself, independent of lighting.

| key | default | meaning |
| --- | --- | --- |
| `M3G_LIT_FAR` | `600000` | Camera far plane for the lit pass. The shipped kordos config used `40000`. |
| `M3G_LIT_NEAR` | `-1` | Camera near plane. `<= 0` derives it from `distX`. |
| `M3G_LIT_DIST` | `0` | Static view-depth cull. **Currently inert**: the lit pass mirrors the packed path and culls at `DirectX7.drDist` (the level's `DIST`), so a tighter rung here would draw less world than the pass it replaces. |
| `M3G_LIT_DYN` | `0` | Dynamic culling: shrink the cutoff toward `M3G_LIT_FPS` instead of using a static one. |
| `M3G_LIT_FPS` | `20` | Target frame rate for `M3G_LIT_DYN`. |
| `M3G_LIT_MINPX` | `0` | Drop polys whose whole screen bbox fits in `MINPX`×`MINPX`. Off by default: visible popping. |
| `M3G_LIT_CULL` | `1` | **Kept in `setting.txt`** (`0`) — it changes what is drawn, not how it is lit. `1` = `CULL_BACK`, `0` = `CULL_NONE` (double-sided, depth-sorted). |
| `M3G_LIT_FLIP` | `0` | `1` flips the winding: fronts are treated as CCW in NDC. |
| `M3G_LIT_FLAT` | `0` | `1` = `SHADE_FLAT`, reads cheaper on software m3g rasterisers. |
| `M3G_LIT_MIP` | `0` | Enable mipmapped textures on the lit path. |
| `M3G_LIT_STATIC` | `0` | Cache per-polygon attributes (uv/colours/normals) for world geometry that never changes between frames. |
| `M3G_LIT_NOVCOL` | `0` | Force vertex colours to white so only hardware lamps shade. Incompatible with baked mode — it erases the lightmap. |
| `M3G_LIT_NORMALVIZ` | `0` | Debug: paint vertices by geometric normal. |
| `M3G_LIT_DEBUG` | `0` | Debug: draw white silhouettes through the immediate path. |

## Unrelated to lighting, still in setting.txt

| key | default | meaning |
| --- | --- | --- |
| `M3G_SCREEN_SCALE` | `100` | World pass renders into a smaller viewport, presented centred without upscaling. `< 100` forces the CPU readback path. |
| `M3G_LAZY_READBACK` | `0` | `1` skips the per-frame framebuffer copy into `int[]`; the image is drawn directly. Only applied at `SCREEN_SCALE=100`. |
| `M3G_PERSPECTIVE` | `1` | Hardware perspective correction. |
