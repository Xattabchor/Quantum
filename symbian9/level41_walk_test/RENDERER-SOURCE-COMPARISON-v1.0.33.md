# Renderer source comparison against the Drive v1.0.33 bundle

## Reference source

Drive contains [`quantum-level41-dsa-v1.0.33-source.zip`](https://drive.google.com/file/d/1N5BTWJ9Pfm7XmzctKzD7AifbOQI5q8qy/view?usp=drivesdk), SHA-256 `e6221bbc1a6a6e973ad1a52abf9544164c841df1d61a993c25c675504c8c210c`.

Its `DSA_SOURCE_CONTEXT.md` identifies the archive as the published Level 41 v1.0.33 DSA source bundle. It specifically says the included `src/core/software_perspective_rasterizer.cpp` is the exact pre-optimization renderer source used in the E65-tested build, identified there as the v1.0.28 renderer baseline. The same context records 9.1 FPS, 71/72 ms mesh timing, DSA bitmap blit, and phase averages `0/7/8/0/55 ms`; those are archived measurements, not a fresh test performed here.

## Is the active renderer byte-for-byte identical?

**No.** SHA-256 and size comparisons against the archive show:

| File | v1.0.33 bundle | Active source | Result |
|---|---:|---:|---|
| `src/core/software_perspective_rasterizer.cpp` | 61,302 B · `de74a6463dfe5e71f0b76438144332e8f0956534cbdc7d000a18d61db3fe7a25` | 76,083 B · `4095abf57eb1a5eee989686d1f20b0757c01ba858cab7ec21917741085fa1ce5` | Different; current source adds OMAP1710 DSP/mode-9 and scalar-PDiv paths plus raster diagnostics. |
| `src/core/software_mesh_renderer.cpp` | 39,501 B · `2f90abbcd7cd196a9d5f2087efd58e3b4d18c4325bb4a344b9b80fe2b3b5ca4a` | 39,508 B · `cd0e33419dfe15814ca97ed848fb74263edcaf3d476c1365d67eaf89dd678326` | Different; current source evaluates camera/shot-vector lighting from source/world-space vertices instead of the transformed camera-space workspace vertex. |
| `inc/software_perspective_rasterizer.h` | `a0d57c53bdb419397e9947ba7e4f3e6c0fca70d60d43f720e63312e134db97d2` | `1b23cdc32057765bc5bcb9806738683d6d9473bd15cbb116669b64c4a8191c1b` | Different. |
| `inc/software_mesh_renderer.h` | `3f90968c8fe690e7b1e0d403c2202729460d909a2fb3e0d548001fd7521319b8` | `6475d5dbe55d847e204819312d64f17192f3f47567a739e7f3378f9e7a9d5e67` | Different; camera-world lighting contract was updated. |
| `level41_device_test/level41_main.cc` | 59,005 B · `7dedfab178ad17e12d9fa12fb95818d95ff79858c0cf4908aaba1b5c9c9a77c8` | 138,030 B · `f3da6af5bbb53e27633381d8218f7ecd66cbd431744a953bcc5bce26be67a8b2` | Different; current source adds later walk, input, audio, DSA, and diagnostic paths. |

The following renderer implementation files are byte-identical to the bundle: `software_affine_rasterizer.cpp`, `software_mesh_3d_parser.cpp`, `render_mode_policy.cpp`, `software_polygon_renderer.cpp`, `software_triangle_renderer.cpp`, and `software_vertex_pipeline.cpp`.

## Profiling caveat

The v1.0.33 `level41_main.cc` in the archive unconditionally sets `SoftwareMeshRenderInstrumentation` and records the five phase averages. Therefore phase timing was already active in the v1.0.33 FPS reference. The normal v1.0.40 build now compiles those phase callbacks out; it is intentionally **not instrumentation-identical** to v1.0.33. Its reported archived 9.1 FPS is the reference measurement, but a current same-device/same-scene A/B result is still required.

This is a source-level comparison only. It does not prove pixel-for-pixel equivalence or quantify the E65 speed impact of the differing ASM, lighting, or profiling paths. The active v1.0.40 candidate remains untested on E65.
