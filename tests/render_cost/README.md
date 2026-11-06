# Draw-call cost harness

Counts native submissions per frame for a real level, so a batching change can be
judged by numbers instead of by eye.

The M3G runtime is replaced by recording doubles (`stubs/`): every
`Graphics3D.render()` is appended to a history with the state it was submitted
with. Nothing is rasterized, so the numbers are *draw calls*, never FPS.

## Run

```sh
sh tools/bootstrap_toolchain.sh                 # JDK 8 + SDK, if build/ is absent
python3 tests/render_cost/run.py                  # the default level
python3 tests/render_cost/run.py /level41.txt     # a level from the game archive
```

Options are passed through with `-D`, for example:

```sh
python3 tests/render_cost/run.py -Dprobe.chunkSize=5000 -Dprobe.fogDist=80000
```

| Property | Default | Meaning |
| --- | --- | --- |
| `probe.chunkSize` | level `CHUNK_SIZE` | World chunk size; `0` renders the whole mesh |
| `probe.fogDist` | level `FOGD` | Materials only use native Fog when this is `> 1` |
| `probe.sprites` | `0` | Billboards queued per frame |
| `probe.spriteTextures` | all | Distinct icons those billboards draw with |
| `probe.monotone` | `false` | Hand sprites out in icon order instead of queue order |
| `probe.sameDepth` | `false` | Give every billboard the same eye depth |
| `probe.spriteFog` | `false` | Let billboards use native Fog |

## What the numbers mean

`avgDraws` / `worstDraws` are `DirectX7.getM3GDrawCalls()` for one frame of the
real level, averaged over two positions, four pitches and eight yaws.

Alongside them the harness reports `billboardCalls` (calls whose draw had no
material and at most four vertices), `drawMix` (blend mode, lighting and
perspective-correction of the calls in the worst frame) and the number of
distinct textures the worst frame touched.

## Baseline (level41, trees and river)

| Scenario | avgDraws | worstDraws |
| --- | --- | --- |
| Level as shipped (`CHUNK_SIZE=78000`) | 8 | 50 |
| `probe.chunkSize=5000` | 1 | 2 |
| + 16 billboards, 2 icons | 17 | 18 |
| + 64 billboards, 64 distinct icons | 65 | 66 |
| + 64 billboards, 1 icon | 3 | 4 |

## Kordosh asset bundle

The `Kordosh` bundle (`Downloads/Quantum_Kordosh_assets.jar` in the working
copy) ships its own `level1.txt` and drops the `CHUNK_SIZE` line the older
`Kordos_Full` level had. Run it with:

    RENDER_COST_RESOURCES=downloads/Quantum_Kordosh_assets.jar \
        python3 tests/render_cost/run.py /level1.txt

| Bundle state | avgDraws | worstDraws | triangles avg/worst | queued |
| --- | --- | --- | --- | --- |
| as shipped (no `CHUNK_SIZE`) | 8 | 50 | 209 / 1632 | 904 |
| `CHUNK_SIZE=20000` in the level | 2 | 14 | 56 / 97 | 103 |
| `CHUNK_SIZE=20000` + `map-selo1.png,6` | 2 | 3 | 56 / 75 | 103 |

**The shipped fix is the middle row only** (`Quantum_Kordosh_optimized.jar`).
The `,6` row is measured for reference and is *not* applied: it changes what
the level looks like. Drawmode 6 sends those polygons through
`M3GLitRenderer`, which shades with hardware lights, and `setColors` only
replays the baked lightmap for modes 9 and 13 - so a mode 6 polygon loses its
lightmap and comes out lit by `M3G_LIT_*` alone. That trade is the level
author's call, not an optimisation. `DMODE=9` stays untouched either way.

Two independent changes account for that drop:

* **`CHUNK_SIZE`** - without it `Room` builds `fullMesh` and the whole 2397
  polygon world is submitted every frame. With it the level is cut into cells
  and only the ring around the camera is drawn.
* **`,6` on a world texture** - drawmode 6 opts that material into
  `M3GLitRenderer`, the true-3D hardware-lit pass. It batches the whole world
  into one native submission per texture while keeping lighting, so the world
  stops costing one call per material split. In the run above the lit pass
  consumed 102 polygons with 0 failures.

`M3G_LIT` in `setting.txt` is a dead key (the engine reads `M3G_LIT_*` only);
lighting per texture is enabled by the drawmode field, nothing else.

Caveat when adding `CHUNK_SIZE`: `Room.render` visits only a fixed +/-1 cell
ring, so the cells must be at least as large as the level `DIST` or distant
terrain stops being drawn. For `DIST=30500` that means `CHUNK_SIZE >= 20000`;
smaller values render fewer triangles than the whole-mesh path, which is
clipping, not culling.

## Where the calls go (level41 as shipped)

A worst-case frame queues 925 objects; 1521 packets reach the submitter, so the
world itself is only about eight calls. The splits are:

| Reason | Splits | Notes |
| --- | --- | --- |
| Texture change | 987 | 154 distinct textures in the frame |
| Vertex capacity (`MAX_VERTICES=384`) | 270 | Was 192; 256 → 44 calls, 384 → 39 |
| Fog scale | 0 | Already rebased (`rebase`) |

## Why texture merging does not help (`level1`)

Measured, not assumed. The level names three world materials and 2340 of its
2397 polygons sit on a single image, so the world is already almost one texture.
Pointing every material at one image collapses the frame to 1 call average / 3
worst, but that is a lighting/terrain change, not an atlas change.

Every single-polygon submission in the batch is a **texture change**; the other
thirteen state fields always match. Counted at the batch, `size==0` flushes:
79 of 79 on `level1`, 507 of 507 on `level41`.

It is the *interleaving* that costs, not the number of textures. `level1` maps
three materials onto only two distinct images - terrain and `forestOrig` share
`map-selo1.png` - and the terrain and water quads alternate, so the batch flips
image, flips back, and each flip is a call. Giving the river its own mode 9
image (`river0.bmp,9`) measures the same 2/14; giving it the land image at mode
9 measures **2/6**, because the two then bucket together.

## All three applied (Quantum_Kordosh_all3.jar)

`CHUNK_SIZE=20000` + `MAX_VERTICES=384` + `M3G_LIT_WORLD9=1`, each measured in
isolation and then together, level1 worst frame:

| bundle | avg | worst |
| --- | --- | --- |
| assets as shipped | 7 | 39 |
| + `CHUNK_SIZE` | 2 | 14 |
| + `M3G_LIT_WORLD9` (with 384 already in the build) | 2 | **3** |

level41 goes 7/39 -> 2/4 with the flag.

Pass order is fixed: `flushBatch` now emits the sorted, depth-tested world
buckets **first**, then the packed batch, then sprites. Before that the world was
emitted last, so it painted over the water, sky and sprites that stay on the
depth-free path. The probe reports the invariant directly:
`worldAfterSprites: N violations in M frames that contain both` is 0/5 on
level41 and 0/2 on level1.

## CHUNK_SIZE is not a free win on this level

`Room.render` draws only the 3x3 block of chunks around the camera, so a chunk
size smaller than the level's view distance drops world the camera can still
see. On the Kordosh level (`DIST=30500`, `M3G_LIT_FAR=30000`) the numbers are:

| CONFIG | worst draws | tris avg/worst |
| --- | --- | --- |
| M3G_LIT_WORLD9=1, chunk off | 2/3 | 54/75 |
| M3G_LIT_WORLD9=1, chunk 20000 | 2/3 | 52/75 |
| M3G_LIT_WORLD9=1, chunk 10000 | 1/3 | 32/28 |

Chunking buys **no draw calls at all** once the sorted world pass is on (off:
3 worst, 20000: 3 worst) and it silently removes visible triangles. The sorted
pass already collapses the whole world into one bucket, so chunking has nothing
left to merge. Earlier readings of "7/39 -> 2/3 from chunking" were taken with
`drDist=30000000` (the harness bug above), where distance culling was off.

Conclusion: leave `CHUNK_SIZE` out of the shipped bundle for this level. It is
not an optimisation here, it is a cropping knob.

## Geometry parity between the two paths

The sorted pass must draw the same world the painter path draws, or it is a
regression hiding behind a smaller call count. Two real mismatches were found
and fixed:

* `M3G_LIT_DIST` was a fixed rung (30000) while the packed path drops at the
  level's `DIST` (30500), so the sorted pass silently drew **less** world.
  The lit pass now uses `DirectX7.drDist`, exactly like the packed path.
* The harness applied level scalars *before* `HouseCreator.create()`. `DirectX7`
  is a singleton and its constructor calls `resize()`, which resets `drDist`
  and `standartDrawmode` to defaults - so every earlier measurement ran with
  `drDist=30000000`, i.e. with all distance culling disabled. `LevelLoader` sets
  these keys before constructing `Scene` and they survive; the harness now
  re-applies them per sample.

After both fixes, identical view pins:

| view | sorted pass | painter path |
| --- | --- | --- |
| 0@0 | 80 | 80 |
| 90@60 | 24 | 24 |
| 270@85 | 27 | 27 |
| 45@30 | 117 | 116 |
| 135@0 | 75 | 77 |
| 225@30 | 53 | 51 |
| 315@60 | 34 | 33 |
| 180@30 | 50 | 49 |

3 of 8 views are identical, the rest differ by 1-2 triangles out of ~50-117.
`litRejects=0` and the far/behind/subpixel cull counters are 0, so the residual
is not a cull: it is near-plane clipping, which the packed path does
per-polygon in screen space and the sorted pass leaves to the engine.

## M3G_LIT_BAKED (lighting parity)

The sorted pass lit with `M3G_LIT_*` lamps on top of the baked lightmap, so the
image could not match. `M3G_LIT_BAKED=1` removes that difference: with the
lightmap already in the vertex colours, the only combination that leaves them
untouched under the JSR-184 equation

    emissive + ambient*materialAmbient + sum(diffuse*materialDiffuse)

is a single white AMBIENT light at full intensity with the material's AMBIENT
already fully open. Directional, omni and the ambient pulse stay out of the
recipe. A live muzzle flash is real lighting, so it always plays regardless.

The probe prints the light recipe actually submitted, so this is checked, not
assumed:

| bundle | lights submitted |
| --- | --- |
| before | `ambient(i=0.25) sun=false` |
| `M3G_LIT_BAKED=1` | `neutralAmbient(i=1.0,c=ffffff)` |

`M3G_LIT_BAKED` only takes effect together with `M3G_LIT_WORLD9`; on the legacy
painter path the lit renderer is not used for the world at all.

Note where `M3G_LIT_WORLD9` must go: `setting.txt` keys are read from the root
table, so the line has to sit **before the first `[group]`**. Appending it to
the end of the file drops it inside `[CAR]` and it is silently ignored.

## Which pass each optimisation belongs to

Two different subsystems, easy to mix up because both show up as "mode" numbers:

| Optimisation | Applies to | Why |
| --- | --- | --- |
| `CHUNK_SIZE` | both 6 and 9 | culls geometry before any pass; fewer polygons reach either |
| `MAX_VERTICES=384` | **mode 9 only** | sizes `M3GPolygonBatch`; mode 6 never reaches it |
| texture re-ordering | **mode 9 only** | the batch's per-texture splits |
| `M3G_LIT_WORLD9=1` | **mode 9 only** | lets a mode 9 material use the mode 6 pass |
| `,6` on a texture | switches that material from 9 to 6 | opt-in to `M3GLitRenderer` |

`M3GSceneRenderer.draw` returns from `litRenderer.draw` before `batch.add` is
ever reached, so mode 6 polygons are not batched, not counted against
`MAX_VERTICES` and not subject to the batch's texture splits. Measured: with a
`,6` world, `MAX_VERTICES` 192 and 384 both give the same 2/3 worst frame; with
mode 9 they give 14. A `,6` world stalls *every* material in its bucket, because
the lit renderer returns `frameFail` for mode 7 (river) - so its water falls back
to the packed path and the sorted pass stops being sorted.

## M3G_LIT_WORLD9 (sorted world pass)

`M3G_LIT_WORLD9=1` (setting.txt or `-D`) routes drawmode 9 through
`M3GLitRenderer`: polygons go in as view-space geometry with a real perspective
camera, are bucketed per texture, and the frame is flushed with `DEPTH_TEST`
on. Mode 9 keeps its baked lightmap (`setColors` replays it for 9 and 13).

Measured, worst frame:

| level | painter order | `M3G_LIT_WORLD9=1` |
| --- | --- | --- |
| level1 | 14 | **3** |
| level41 | 39 | **4** |

**It is off by default, and not only because it changes the pipeline.** Two
things a device user would notice:

* The world is submitted at `flushBatch`, i.e. **after** the water, sky and
  sprites, which stay on the depth-test-free packed path. Those lose to the
  world regardless of depth - the river disappears behind terrain it should be
  drawn over, and sprites (no depth written) are painted first.
* The lit pass shades with its own material: `M3G_LIT_DIR=150`, a directional
  sun and `M3G_LIT_AMBIENT=32`. A mode 9 polygon keeps its baked lightmap as
  vertex colour but now also receives those lights, so the result is not the
  same image unless those keys are tuned per level.

Both are solvable - chain the passes so the sorted world flushes first, and
drive the lit material from the level's lighting - but neither is a data edit.

Ceiling, and it is out of reach without the above: the world pass runs with depth test off,
so painter order decides what a pixel shows. Reordering polygons across a
texture change changes the image, so the batch may only merge *runs*. A
hypothetical texture-sorted pass would cost 2 world calls on `level1` (14 -> 3
in total) and 7 on `level41` (39 -> 26). Getting there needs a sorted world pass
with depth buffering, which changes what the hardware draws.

`MAX_VERTICES` is now 384 (was 192). On level41 that moves the worst frame from
50 to 39 calls, and it keeps 9 of the 11 calls the split accounting would
otherwise charge. It saturates: 512 measures the same 7/39 and 768 buys one call,
so 384 is the last size worth its memory. The cost is one batch's worth of
arrays - 384 vertices needs ~2.7 KB more than 192 and there is a single batch. Texture changes cannot be merged into one submission — a batch
carries exactly one `Texture2D` — and the world pass runs with depth test off, so
re-ordering polygons across a texture change also changes what wins a pixel.

Level `CHUNK_SIZE` decides the rest. A level that sets it renders only the chunks
around the camera and drops to one or two calls; a level without it submits the
whole world mesh, and the count then follows the material and capacity splits
above.

Billboards cost one call each whenever the icon changes between two queued
sprites, because that transition flushes the sprite batch. Sixty-four billboards
with sixty-four icons therefore cost sixty-four calls, while the same sixty-four
billboards sharing one icon cost three.

The texture cache is not a factor: it holds one entry per material, evicts
nothing, and every upload happens while the level loads rather than mid-frame.
