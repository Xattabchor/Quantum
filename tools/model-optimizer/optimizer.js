/* Quantum .3d static LOD tools. GPL-3.0 (repository LICENSE).
 * Native quad topology is ABD, DBC AFTER Room.loadMeshes reverses file corners.
 * No textures, renderer code, game configuration or original files are modified.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.QuantumModelOptimizer = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const LIMIT = 64 * 1024 * 1024;
  function fail(message) { throw new Error(message); }
  function bytes(value) {
    if (value instanceof Uint8Array) return value;
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    fail('Expected binary file bytes');
  }
  class Reader {
    constructor(input) { this.data = bytes(input); if (this.data.length > LIMIT) fail('File exceeds 64 MiB'); this.view = new DataView(this.data.buffer, this.data.byteOffset, this.data.byteLength); this.offset = 0; }
    take(n) { if (this.offset + n > this.data.length) fail('Truncated file at byte ' + this.offset); const p = this.offset; this.offset += n; return p; }
    i32() { return this.view.getInt32(this.take(4), false); }
    i16() { return this.view.getInt16(this.take(2), false); }
    u8() { return this.view.getUint8(this.take(1)); }
    count(label) { const n = this.i16(); if (n < 0) fail('Negative ' + label); return n; }
    end() { if (this.offset !== this.data.length) fail('Unexpected trailing bytes: ' + (this.data.length - this.offset)); }
  }
  class Writer {
    constructor() { this.parts = []; }
    u8(n) { if (!Number.isInteger(n) || n < 0 || n > 255) fail('Byte out of range'); this.parts.push(n); }
    i16(n) { if (!Number.isInteger(n) || n < -32768 || n > 32767) fail('Signed short out of range'); this.u8((n >>> 8) & 255); this.u8(n & 255); }
    i32(n) { for (let shift = 24; shift >= 0; shift -= 8) this.u8((n >>> shift) & 255); }
    finish() { return Uint8Array.from(this.parts); }
  }
  function canonicalFaces(mesh) { return mesh.faces.filter(f => f.corners.length === 3).concat(mesh.faces.filter(f => f.corners.length === 4)); }
  function read3d(input) {
    const r = new Reader(input), count = r.i32(), meshes = []; let triangleBudget = 0;
    if (count < 0 || count > 256) fail('Unsupported mesh count (0..256)');
    for (let m = 0; m < count; m++) {
      const vertices = [], faces = [], nv = r.count('vertex count');
      for (let i = 0; i < nv; i++) vertices.push([r.i16(), r.i16(), r.i16()]);
      for (const size of [3, 4]) {
        let material = 0; const nf = r.count('face count'); triangleBudget += nf * (size - 2);
        if (triangleBudget > 500000) fail('Model exceeds 500000 triangle safety limit');
        for (let f = 0; f < nf; f++) {
          let first = r.i16();
          if (first === -32768) { material = r.i16() & 255; first = r.i16(); }
          const ids = [first]; for (let i = 1; i < size; i++) ids.push(r.i16());
          for (const v of ids) if (v < 0 || v >= nv) fail('Vertex index outside mesh ' + m + ': ' + v);
          const corners = ids.map(v => ({ v, uv: [r.u8(), r.u8()], light: null })).reverse();
          faces.push({ material, corners });
        }
      }
      meshes.push({ vertices, faces });
    }
    r.end(); return { meshes, originalBytes: bytes(input).slice(), lightmapFormat: null, originalLightmapBytes: null };
  }
  function validate(model) {
    if (!model || !Array.isArray(model.meshes) || model.meshes.length > 256) fail('Invalid model');
    for (const mesh of model.meshes) {
      if (mesh.vertices.length > 32767) fail('Too many .3d vertices');
      for (const p of mesh.vertices) if (p.length !== 3 || p.some(v => !Number.isInteger(v) || v < -32768 || v > 32767)) fail('Position outside int16');
      const counts = [0, 0];
      for (const f of mesh.faces) {
        if (!Number.isInteger(f.material) || f.material < 0 || f.material > 255) fail('Material outside 0..255');
        if (f.corners.length !== 3 && f.corners.length !== 4) fail('Expected triangle or quad');
        counts[f.corners.length - 3]++;
        for (const c of f.corners) {
          if (!Number.isInteger(c.v) || c.v < 0 || c.v >= mesh.vertices.length) fail('Invalid corner vertex');
          if (!c.uv || c.uv.length !== 2 || c.uv.some(v => !Number.isInteger(v) || v < 0 || v > 255)) fail('Invalid UV');
        }
      }
      if (counts.some(n => n > 32767)) fail('Too many .3d faces in a section');
    }
  }
  function write3d(model) {
    validate(model); const w = new Writer(); w.i32(model.meshes.length);
    for (const mesh of model.meshes) {
      w.i16(mesh.vertices.length); for (const p of mesh.vertices) for (const v of p) w.i16(v);
      for (const size of [3, 4]) {
        const faces = mesh.faces.filter(f => f.corners.length === size); w.i16(faces.length); let material = 0;
        for (const face of faces) {
          if (face.material !== material) { w.i16(-32768); w.i16(face.material); material = face.material; }
          const fileCorners = face.corners.slice().reverse();
          for (const c of fileCorners) w.i16(c.v);
          for (const c of fileCorners) for (const uv of c.uv) w.u8(uv);
        }
      }
    }
    return w.finish();
  }
  function attachLightmap(model, input, format) {
    if (!['gray9', 'color13'].includes(format)) fail('Choose lightmap format gray9 or color13 explicitly');
    const r = new Reader(input), assignments = [];
    for (const mesh of model.meshes) for (const face of canonicalFaces(mesh)) {
      const reds = face.corners.map(() => r.u8() ^ 128), colors = reds.map(v => [v, v, v]);
      if (format === 'color13') {
        const flag = r.u8(); if (flag > 1) fail('Invalid RGB lightmap colored flag');
        if (flag) for (const color of colors) { color[1] = r.u8() ^ 128; color[2] = r.u8() ^ 128; }
      }
      assignments.push([face, colors]);
    }
    r.end(); // Do not partly attach a truncated/wrong-format map.
    for (const [face, colors] of assignments) face.corners.forEach((c, i) => { c.light = colors[i]; });
    model.lightmapFormat = format; model.originalLightmapBytes = bytes(input).slice(); return model;
  }
  function writeLightmap(model) {
    if (!model.lightmapFormat) return null; const w = new Writer();
    for (const mesh of model.meshes) for (const face of canonicalFaces(mesh)) {
      const colors = face.corners.map(c => c.light);
      if (colors.some(c => !c || c.length !== 3 || c.some(v => !Number.isInteger(v) || v < 0 || v > 255))) fail('Missing/invalid lightmap corner');
      for (const c of colors) w.u8(c[0] ^ 128);
      if (model.lightmapFormat === 'color13') {
        const colored = colors.some(c => c[0] !== c[1] || c[0] !== c[2]); w.u8(colored ? 1 : 0);
        if (colored) for (const c of colors) { w.u8(c[1] ^ 128); w.u8(c[2] ^ 128); }
      } else if (colors.some(c => c[0] !== c[1] || c[0] !== c[2])) fail('RGB data cannot be saved as gray9');
    }
    return w.finish();
  }
  function triangles(face) {
    const c = face.corners;
    return c.length === 3 ? [[c[0], c[1], c[2]]] : [[c[0], c[1], c[3]], [c[3], c[1], c[2]]];
  }
  function bounds(vertices) {
    if (!vertices.length) return null;
    const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    for (const p of vertices) for (let k = 0; k < 3; k++) { min[k] = Math.min(min[k], p[k]); max[k] = Math.max(max[k], p[k]); }
    return { min, max };
  }
  function stats(model) {
    let vertices = 0, polygons = 0, triangleCount = 0, corners = 0; const materials = {};
    const meshes = model.meshes.map((mesh, index) => {
      let t = 0, c = 0;
      for (const f of mesh.faces) { t += f.corners.length - 2; c += f.corners.length; const s = materials[f.material] || (materials[f.material] = { polygons: 0, triangles: 0 }); s.polygons++; s.triangles += f.corners.length - 2; }
      vertices += mesh.vertices.length; polygons += mesh.faces.length; triangleCount += t; corners += c;
      return { index, vertices: mesh.vertices.length, polygons: mesh.faces.length, triangles: t, expandedCorners: c, bounds: bounds(mesh.vertices) };
    });
    return { meshes, vertices, polygons, triangles: triangleCount, expandedCorners: corners, materials, lightmapFormat: model.lightmapFormat };
  }
  function copyCorner(c) { return { v: c.v, uv: c.uv.slice(), light: c.light ? c.light.slice() : null }; }
  function copyFace(f) { return { material: f.material, corners: f.corners.map(copyCorner) }; }
  function parseMaterials(value) {
    const list = Array.isArray(value) ? value : String(value == null ? '4' : value).split(/[ ,;]+/).filter(Boolean).map(Number);
    if (list.some(n => !Number.isInteger(n) || n < 0 || n > 255)) fail('Protected material IDs must be 0..255');
    return Array.from(new Set(list)).sort((a, b) => a - b);
  }
  function options(input) {
    const o = Object.assign({ ratio: .75, maxError: 5, mode: 'safe', uvWeight: 1, lightWeight: 1, protectedMaterials: [4], staticModel: false, withoutLightmap: false, allowSeamChanges: false }, input);
    for (const key of ['ratio', 'maxError', 'uvWeight', 'lightWeight']) if (!Number.isFinite(o[key])) fail('Non-finite ' + key);
    if (o.ratio < .05 || o.ratio > 1) fail('Ratio must be between .05 and 1');
    if (o.maxError < 0 || o.maxError > 65535) fail('Error must be 0..65535 source coordinate units');
    if (o.uvWeight <= 0 || o.lightWeight <= 0 || o.uvWeight > 100 || o.lightWeight > 100) fail('Attribute weights must be >0 and <=100');
    if (!['safe', 'balanced', 'experimental'].includes(o.mode)) fail('Mode must be safe, balanced or experimental');
    if (o.mode === 'experimental' && !o.allowSeamChanges) fail('Experimental mode requires explicit allowSeamChanges: texture/light seams may change');
    if (!o.staticModel) fail('Acknowledge static-model use; not for animation/morph frames or collision replacement');
    o.protectedMaterials = parseMaterials(o.protectedMaterials); return o;
  }
  function positionKey(p) { return p.join(','); }
  function planarQuadPair(a, b, records) {
    const sub = (x, y) => x.map((v, i) => v - y[i]);
    const cross = (x, y) => [x[1]*y[2]-x[2]*y[1], x[2]*y[0]-x[0]*y[2], x[0]*y[1]-x[1]*y[0]];
    const dot = (x, y) => x.reduce((s, v, i) => s + v*y[i], 0);
    for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) {
      const u = a[i], v = a[(i+1)%3], w = a[(i+2)%3];
      if (b[j] !== w || b[(j+1)%3] !== v) continue;
      const ids = [u, v, b[(j+2)%3], w]; if (new Set(ids).size !== 4) continue;
      const p = ids.map(v => records[v].position), normal = cross(sub(p[1],p[0]),sub(p[3],p[0]));
      if (!dot(normal,normal) || dot(normal,sub(p[2],p[0])) !== 0) continue;
      if (p.some((v,k) => dot(cross(sub(p[(k+1)%4],v),sub(p[(k+2)%4],p[(k+1)%4])),normal) <= 0)) continue;
      // Cyclic rotations only: exactly the same two oriented triangles, with
      // the same attributes/diagonal. No triangle deletion or new surface.
      return ids.map(v => copyCorner(records[v].corner));
    }
    return null;
  }
  function simplifyGroup(mesh, faces, material, config, simplifier, materialPositions, hasLightmap) {
    const sourceTriangles = faces.flatMap(triangles), originalCount = sourceTriangles.length;
    if (config.protectedMaterials.includes(material) || originalCount < 8 || config.ratio === 1) return { faces: faces.map(copyFace), detail: { material, before: originalCount, after: originalCount, reason: 'protected/small/ratio1' } };
    const cornerMap = new Map(), records = [], indices = [], quadPairs = new Map(), uvAtPosition = new Map();
    const add = c => {
      const p = mesh.vertices[c.v], pk = positionKey(p), key = pk + '/' + c.uv.join(',') + '/' + (hasLightmap ? c.light.join(',') : '');
      if (!uvAtPosition.has(pk)) uvAtPosition.set(pk, new Set()); uvAtPosition.get(pk).add(c.uv.join(','));
      if (!cornerMap.has(key)) { cornerMap.set(key, records.length); records.push({ corner: c, position: p, pk }); }
      return cornerMap.get(key);
    };
    for (const face of faces) {
      const start = indices.length; for (const tri of triangles(face)) for (const c of tri) indices.push(add(c));
      if (face.corners.length === 4) quadPairs.set(indices.slice(start, start + 6).join(','), face);
    }
    const positions = new Float32Array(records.length * 3), stride = hasLightmap ? 5 : 2;
    const attributes = new Float32Array(records.length * stride), locks = new Uint8Array(records.length);
    records.forEach((r, i) => {
      positions.set(r.position, i * 3); attributes.set(r.corner.uv, i * stride);
      if (hasLightmap) attributes.set(r.corner.light, i * stride + 2);
      // Material boundaries stay fixed across independently simplified groups.
      // In balanced mode, tag2 allows collapsing ALONG, not ACROSS, UV seams.
      locks[i] = materialPositions.get(r.pk).size > 1 ? 1 : uvAtPosition.get(r.pk).size > 1 && config.mode !== 'experimental' ? 2 : 0;
    });
    // Anchor a source triangle per positional connected component. No Prune,
    // Sloppy or blanket dropping of small/disconnected furniture components.
    const parents = new Map();
    function find(x) { if (!parents.has(x)) parents.set(x, x); let y = x; while (parents.get(y) !== y) y = parents.get(y); while (parents.get(x) !== x) { const n = parents.get(x); parents.set(x, y); x = n; } return y; }
    for (let i = 0; i < indices.length; i += 3) {
      const a = find(records[indices[i]].pk);
      parents.set(find(records[indices[i + 1]].pk), a); parents.set(find(records[indices[i + 2]].pk), a);
    }
    const anchored = new Set(), anchorPositions = new Set();
    for (let i = 0; i < indices.length; i += 3) {
      const root = find(records[indices[i]].pk); if (anchored.has(root)) continue; anchored.add(root);
      for (let j = 0; j < 3; j++) anchorPositions.add(records[indices[i + j]].pk);
    }
    records.forEach((r, i) => { if (anchorPositions.has(r.pk)) locks[i] = 1; });
    const flags = ['LockBorder', 'ErrorAbsolute', 'Sparse', 'PreserveFolds']; if (config.mode !== 'safe') flags.push('Permissive');
    const target = Math.max(1, Math.ceil(originalCount * config.ratio));
    const weights = [config.uvWeight / 256, config.uvWeight / 256];
    if (hasLightmap) weights.push(config.lightWeight / 255, config.lightWeight / 255, config.lightWeight / 255);
    const [output, resultError] = simplifier.simplifyWithAttributes(Uint32Array.from(indices), positions, 3, attributes, stride, weights, locks, target * 3, config.maxError, flags);
    if (output.length % 3 || output.length > indices.length || !Number.isFinite(resultError)) fail('Invalid simplifier result');
    if (output.length === indices.length) return { faces: faces.map(copyFace), detail: { material, before: originalCount, after: originalCount, reason: 'seams/topology/error limit', estimatedError: resultError } };
    const outFaces = []; let keptQuads = 0;
    for (let i = 0; i < output.length;) {
      for (let j = 0; j < Math.min(6, output.length - i); j++) if (output[i + j] >= records.length) fail('Simplifier index outside buffer');
      const pair = i + 6 <= output.length ? quadPairs.get(Array.from(output.subarray(i, i + 6)).join(',')) : null;
      const packed = !pair && i + 6 <= output.length ? planarQuadPair(Array.from(output.subarray(i,i+3)),Array.from(output.subarray(i+3,i+6)),records) : null;
      if (pair || packed) { outFaces.push(pair ? copyFace(pair) : { material, corners: packed }); i += 6; keptQuads++; }
      else { outFaces.push({ material, corners: Array.from(output.subarray(i, i + 3), v => copyCorner(records[v].corner)) }); i += 3; }
    }
    return { faces: outFaces, detail: { material, before: originalCount, after: output.length / 3, target, estimatedError: resultError, preservedQuadPairs: keptQuads, positionalComponentsAnchored: anchored.size } };
  }
  async function optimize(source, inputOptions, simplifier) {
    validate(source); const config = options(inputOptions);
    if (!source.lightmapFormat && !config.withoutLightmap) fail('Supply the original lightmap or explicitly choose withoutLightmap (old lightmap will not fit a changed model)');
    if (!simplifier || !simplifier.supported) fail('MeshoptSimplifier / WebAssembly unavailable'); await simplifier.ready;
    const before = stats(source), details = [], meshes = [];
    for (const mesh of source.meshes) {
      const groups = new Map(), materialPositions = new Map();
      for (const face of mesh.faces) {
        if (!groups.has(face.material)) groups.set(face.material, []); groups.get(face.material).push(face);
        for (const c of face.corners) { const key = positionKey(mesh.vertices[c.v]); if (!materialPositions.has(key)) materialPositions.set(key, new Set()); materialPositions.get(key).add(face.material); }
      }
      const faces = [], groupsReport = [];
      for (const [material, group] of groups) { const result = simplifyGroup(mesh, group, material, config, simplifier, materialPositions, !!source.lightmapFormat); faces.push(...result.faces); groupsReport.push(result.detail); }
      const ordered = faces.filter(f => f.corners.length === 3).concat(faces.filter(f => f.corners.length === 4));
      const used = Array.from(new Set(ordered.flatMap(f => f.corners.map(c => c.v)))).sort((a, b) => a - b), remap = new Map(used.map((v, i) => [v, i]));
      const candidate = { vertices: used.map(v => mesh.vertices[v].slice()), faces: ordered.map(f => ({ material: f.material, corners: f.corners.map(c => Object.assign(copyCorner(c), { v: remap.get(c.v) })) })) };
      const a = stats({ meshes: [mesh] }), b = stats({ meshes: [candidate] });
      // Triangle-only output can EXPAND the cached renderer's per-face vertex
      // streams. Reject such a LOD by default instead of advertising file size.
      const accept = b.triangles < a.triangles && b.expandedCorners < a.expandedCorners;
      meshes.push(accept ? candidate : { vertices: mesh.vertices.map(p => p.slice()), faces: mesh.faces.map(copyFace) });
      details.push({ accepted: accept, candidate: { triangles: b.triangles, expandedCorners: b.expandedCorners }, reason: accept ? 'fewer triangles AND expanded corners' : 'no reduction or expanded native corner stream; original mesh retained', groups: groupsReport });
    }
    const model = { meshes, lightmapFormat: source.lightmapFormat }, after = stats(model), changed = after.triangles < before.triangles;
    const modelBytes = changed ? write3d(model) : (source.originalBytes ? source.originalBytes.slice() : write3d(source));
    const lightmapBytes = changed ? writeLightmap(model) : (source.originalLightmapBytes ? source.originalLightmapBytes.slice() : writeLightmap(source));
    const reparsed = read3d(modelBytes); if (lightmapBytes) attachLightmap(reparsed, lightmapBytes, source.lightmapFormat);
    validate(reparsed);
    const targetTriangles = Math.ceil(before.triangles * config.ratio), warnings = [
      'Lossy static visual LOD; inspect textures, lighting, silhouettes, doors/windows and collision separately.',
      'No FPS gain measured. Native corner counts and triangle counts are not GPU timing.',
      'Changing topology requires this matching lightmap; never use the old baked file with the changed model.',
      'Material blending is NOT stored in .3d: explicitly protect all water/alpha/animated material IDs.',
      'Meshopt error is an approximate combined position/attribute metric, not a certified Hausdorff or pixel bound.',
      'No guarantee of collision/navigation/portal equivalence; no automatic installation into a game.'
    ];
    if (after.triangles > targetTriangles) warnings.push('Target not reached: protected seams, boundaries, components, error limit or runtime-corner guard stopped simplification.');
    if (!changed) warnings.push('No safe runtime reduction found; exact original payloads returned.');
    return { model: reparsed, modelBytes, lightmapBytes, report: {
      tool: 'Quantum Model Optimizer 1.0', backend: 'meshoptimizer 1.3.0 (MIT), endpoint-only simplifyWithAttributes',
      options: config, changed, targetTriangles, targetReached: after.triangles <= targetTriangles,
      before, after, trianglesRemoved: before.triangles - after.triangles,
      triangleReductionPercent: before.triangles ? +(100 * (1 - after.triangles / before.triangles)).toFixed(2) : 0,
      modelBytes: modelBytes.length, lightmapBytes: lightmapBytes ? lightmapBytes.length : 0,
      details, warnings, handsetTested: false, fpsMeasured: false, gameResourcesModified: false
    } };
  }
  return { read3d, write3d, attachLightmap, writeLightmap, triangles, stats, validate, optimize, parseMaterials };
});
