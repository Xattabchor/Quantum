# Quantum  
J2ME 3D Game engine based on Iceberg 3D.  

Uses software rendering and zone/portal-based culling.  
Supports some kind of scripting (should be replaced with lua!), has its own lightmapper.  
I wrote it when I was just learning Java and OOP, so the code quality is really low, but maybe this engine can be useful to someone.  

## ARM9 optimization (rendering, physics, AI)  
Rendering hot paths are optimized for ARM9-class CPUs (no FPU, no hardware divider, small heap): exact integer math instead of `double`/`Math.sqrt`, no 64-bit division per scanline, unrolled pixel loops and zero per-frame allocations.  
Per-character per-frame work is optimized too, because that is what actually costs frames when NPCs are enabled: integer collision math instead of `float`/`Math.sqrt`, cheap edge rejection before the 64-bit-division `distanceToLine`, a polygon `kind` tag instead of `instanceof`, per-frame caching of near-object lists, O(N^2) instead of O(N^3) pair tests, allocation-free AI target checks and cached animation periods.  
File formats (levels, meshes, textures, `setting.txt`, RMS records) are unchanged, and every approximate speedup is opt-in through the device profile.  
See [docs/ARM9.md](docs/ARM9.md) for the full list of changes, the `setting.txt` keys and the measured lighting accuracy.
