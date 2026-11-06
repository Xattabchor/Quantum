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

## M3G_LIT_WORLD9: the Material is the switch

JSR-184 (class `Material`) is explicit: "Lighting is disabled for a submesh if
it has a null Material, and enabled otherwise. If lighting is disabled, the
final vertex colour is taken from the associated VertexBuffer as such. If
lighting is enabled, the final colour is computed ... Finally, if vertex colour
tracking is enabled, the AMBIENT and DIFFUSE material colours are replaced with
the per-vertex colours."

Mode 9 carries the whole baked lightmap in the vertex colours, so the only way
to draw it unchanged is to give the submesh **no Material at all**. Vertex
colour tracking is the one hook that feeds buffer colours into the lighting
equation, it defaults to *false*, and this codebase never enabled it - so with
a Material present the lightmap was discarded and every vertex came out as the
material's ambient term (flat white world, plus a full per-vertex lighting
equation paid on every frame).

The lit pass therefore submits mode 9 with a null Material on a dedicated
appearance. Mode 6 (hardware-lit opt-in) keeps its Material and its lights.
`M3G_LIT_BAKED` and `M3G_LIT_NO_LIGHTS` only matter on the mode-6 path now.
Measured: mode 9 alone produces 29 distinct grey levels from `kordod.vla`, while
the packed path leaves only 0/255 on the same level, so the two are not
interchangeable.

### M3G_LIT_NO_LIGHTS semantics

The flag was meant to put the lightmap into material EMISSIVE. It still exists
for mode 6, but it is no longer needed anywhere: the null-Material path reaches
the same result without depending on a runtime honouring EMISSIVE, and it also
removes the normals work, since nothing reads normals when lighting is off.

### M3G_LIT_MIP: one texture, one chain

`HW mip` in the menu is `M3G_LIT_MIP`. It used to be a serious bug, not a
trade-off. The lit pass kept its OWN texture store (`litTextures`) and built a
second, chain-only copy of every world texture. Nothing evicted that store and it
was never counted against `CACHE_PIXELS`, so with the flag on every world
texture was resident twice: over the rasteriser's texture budget, thrashing, and
measured at 5 fps on hardware against 20 without it.

There is now exactly one entry per texture, in the shared cache. When the flag is
on that single entry is uploaded as a mip chain of the loader's levels and both
paths draw from it; that includes the lit pass, which no longer owns textures at
all.

Two details decide whether the chain is actually sampled:

- `Texture2D.setFiltering(levelFilter, imageFilter)` with `FILTER_BASE_LEVEL`
  means *use the base level only* per JSR-184 - it switches mipmapping off. A
  chain uploaded that way is resident and never read. The level filter must be
  `FILTER_NEAREST` for a chain (with `FILTER_NEAREST` as the image filter, so
  sampling stays point-sampled and nothing becomes bilinear).
- The packed polygon batch used to force `FILTER_BASE_LEVEL` on every flush,
  which re-disabled mipmapping on every texture it touched. It now carries each
  texture's own level filter.

Measured on `level1`: with the flag on, 85 textures upload as chains and the draw
count is **identical** to the flag off (2 avg / 6 worst). Before the fix the same
scene needed 44 draws and 987 triangles in its worst frame.

The cache is also frame-aware now: eviction skips entries the current frame has
already used. Replacing a live texture mid-frame hands the next polygon a new
native object, which splits batches and re-uploads the same pixels.

### The mip chain was never a valid chain

`M3G_LIT_MIP` crashed the game with
`M3G required: java.lang.IllegalArgumentException` (no message) the moment a
world texture went through the chain upload, typically while turning. The cause
was in the loader, not in the renderer, and it predates the shared-cache rework:

```java
RawImage rmImg1 = RawImage.createMipRawImage(rImg);   // 256 -> 128
RawImage rmImg2 = RawImage.createMipRawImage(rImg);   // 256 -> 128 again (!)
```

Level 2 halved the BASE again instead of halving level 1, so every texture larger
than 2x2 produced a chain like `[256,128,128]`. JSR-184 requires each level to be
exactly half the previous one, and a runtime that enforces it throws
`IllegalArgumentException` with an empty message from
`Image2D(int format, Image[] levels)` - which is why the error screen showed a
bare `IllegalArgumentException` instead of a diagnostic. The fallback also padded
short chains by duplicating level 1 (`[2,1,1]`), the same defect at the small end.

Both are fixed: level 2 is built from level 1, and a base too small for a second
reduction ships a two-level chain instead of a padded one. Every texture in the
bundle now produces a valid chain.

### The crash on texture filtering

`M3G required: java.lang.IllegalArgumentException`, with no message, on camera
turns and right after a level load. Both the packed batch, the lit pass and the
sprite renderer called

```java
texture.setFiltering(levelFilter, imageFilter);
```

on **every flush**, for every texture. Both values are legal (208 and 210), and
both legal per JSR-184. But it was a pure no-op: a cache entry gets its filter
once, in `createTexture`, and never changes it, and every caller passed exactly
that same value back - so the call re-asserted state the texture already had,
hundreds of times a second.

The Nokia m3gcore runtime that J2ME-Loader embeds raises `M3G_INVALID_VALUE` on
it. Its JNI layer converts that to `IllegalArgumentException` **with an empty
message** (`errorHandler` -> `SetErrorCode` -> `jsr184Exception`, whose default
branch is a bare `java/lang/IllegalArgumentException`), which is why the error
screen could only show the class name. Since the error code is cleared in
`Lock()` before every call, the exception belongs to the call that raised it, not
to a stale one.

The call is gone from all three hot paths. Filtering is set where a texture is
created and nowhere else. Nothing about the look changes: the value being
re-asserted was always the value already stored.

### Buckets that outgrew their normal array

A level whose lit bucket held more vertices than the previous one took the
whole frame down with `M3G required: java.lang.IllegalArgumentException: byte
values too short`. The bucket already grew `xyz`, `uva`, `cola` and the native
`VertexArray`s on demand - but the normal source, `bk.nrma`, was grown **only**
in the unlit branch. A lit bucket was handed whatever length the last unlit one
left behind: 294 bytes for the 648 a 216-vertex bucket needs.

Both branches now grow it before the upload. Two neighbours had the same
defect and are fixed too: the immediate-path scratch arrays (`xyz`, `uv`,
`rgba`, `nrm`) were fixed at four corners and the `VertexArray`s behind them at
four vertices, so any polygon wider than a quad overran both.

This is not resource-dependent: it needs a level in which some bucket grows
past a previously seen width. It stayed hidden because the decimated Kordos
world produced the same widths on every run. A resource set with different
geometry exposes it immediately.

### Measuring a frame

`M3G_PROFILE=1` splits a frame into its passes and prints each one's share
every two seconds:

```
PROFILE 17f world=49%/1020ms sprites=8%/170ms sky=16%/340ms finish=20%/425ms
        setup=4%/102ms | measured=2057ms of 17 frames
```

The same numbers appear on the perf HUD line, so it works without a console.

Two things to keep in mind when reading it. CLDC has only
`currentTimeMillis`, so a pass shorter than one clock tick contributes nothing;
the figures are accumulated over the window and are a share of the MEASURED
time, not of wall clock, and they need a multi-second run to mean anything.
Second, the passes are serialised on one target, so a timestamp between two of
them costs one clock read and is charged to the pass being closed.

Ship builds leave `M3G_PROFILE` at 0: the hooks are one boolean test each.

### The fill benchmark

`Run M3G SpeedTest` in the developer menu times the same quad through six
distinct fill states - opaque, decal, alpha cutout, cutout with fog, modulate,
additive - and shows the table as `fill ms: opaque=.. decal=..` on the perf line.

It used to be dishonest: the loop stepped a mode counter 0..13 that was never
passed to the draw call, so all fourteen "modes" measured one REPLACE+MODULATE
quad, and every exception inside the loop was swallowed, so a total failure
still reported success with plausible numbers. The draw now takes the fill state
as arguments and a failing state is recorded instead of hidden.

`downloads/Quantum_FillTest.jar` is that build with `M3G_PROFILE=1` and the
developer menu reachable, so the tables can be read straight away.
