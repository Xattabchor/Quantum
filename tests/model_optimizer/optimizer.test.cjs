/* node --test tests/model_optimizer/optimizer.test.cjs */
'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), cp = require('node:child_process'), vm = require('node:vm');
const core = require('../../tools/model-optimizer/optimizer.js');
const simplifier = require('../../tools/model-optimizer/vendor/meshopt_simplifier.js');
const opts = { ratio: .5, maxError: 10, mode: 'safe', protectedMaterials: [4], staticModel: true };
const corner = (v, uv = [0, 0], light = [140, 140, 140]) => ({ v, uv, light });
function grid(n = 20, rgb = false) {
  const vertices = [], faces = [];
  for (let z = 0; z <= n; z++) for (let x = 0; x <= n; x++) vertices.push([x * 100, 0, z * 100]);
  const c = (x, z) => corner(z * (n + 1) + x, [Math.round(x * 255 / n), Math.round(z * 255 / n)], rgb ? [60 + x, 90 + z, 100 + x + z] : [140, 140, 140]);
  for (let z = 0; z < n; z++) for (let x = 0; x < n; x++) faces.push({ material: 1, corners: [c(x, z), c(x + 1, z), c(x + 1, z + 1), c(x, z + 1)] });
  return { meshes: [{ vertices, faces }], lightmapFormat: rgb ? 'color13' : 'gray9' };
}
function reload(model) { const read = core.read3d(core.write3d(model)); if (model.lightmapFormat) core.attachLightmap(read, core.writeLightmap(model), model.lightmapFormat); return read; }
function point(c, mesh) { return mesh.vertices[c.v].join(',') + '/' + c.uv.join(',') + '/' + (c.light || []).join(','); }
function triangleKey(points) { return points.map((_, i) => points.slice(i).concat(points.slice(0, i)).join('|')).sort()[0]; }
function orientedTriangles(model) { return model.meshes.flatMap(m => m.faces.flatMap(f => core.triangles(f).map(t => f.material + ':' + triangleKey(t.map(c => point(c, m)))))).sort(); }
function golden() {
  // Independent byte construction in FILE order. A material marker in BOTH
  // sections proves the per-section reset and reversed UV/corner mapping.
  const parts = [], short = n => { const b = Buffer.alloc(2); b.writeInt16BE(n); parts.push(b); }, byte = n => parts.push(Buffer.from([n]));
  const count = Buffer.alloc(4); count.writeInt32BE(2); parts.push(count);
  short(4); for (const p of [[-32768, 0, 32767], [11, -12, 13], [21, 22, 23], [31, 32, 33]]) for (const v of p) short(v);
  short(1); short(-32768); short(7); for (const v of [0, 1, 2]) short(v); for (const uv of [0, 255, 128, 127, 33, 66]) byte(uv);
  short(1); short(-32768); short(7); for (const v of [0, 1, 2, 3]) short(v); for (const uv of [1, 2, 3, 4, 5, 6, 7, 8]) byte(uv);
  short(0); short(0); short(0); // second, empty mesh
  return Buffer.concat(parts);
}
test('binary golden: BE signed positions, marker reset, unsigned UV, reverse corners, multiple meshes', () => {
  const input = golden(), m = core.read3d(input), [t, q] = m.meshes[0].faces;
  assert.equal(m.meshes.length, 2); assert.deepEqual(t.corners.map(c => c.v), [2, 1, 0]);
  assert.deepEqual(t.corners.map(c => c.uv), [[33, 66], [128, 127], [0, 255]]);
  assert.deepEqual(q.corners.map(c => c.v), [3, 2, 1, 0]); assert.equal(q.material, 7);
  assert.deepEqual(Buffer.from(core.write3d(m)), input);
  assert.deepEqual(core.triangles(q).map(t => t.map(c => c.v)), [[3, 2, 0], [0, 2, 1]]);
});
test('gray9 XOR128, engine-corner ordering and exact output', () => {
  const m = core.read3d(golden()), light = Uint8Array.from([0, 127, 128, 255, 23, 42, 177]);
  core.attachLightmap(m, light, 'gray9'); assert.deepEqual(m.meshes[0].faces[0].corners.map(c => c.light[0]), [128, 255, 0]);
  assert.deepEqual(core.writeLightmap(m), light);
});
test('color13 independent golden: reds first, bool, then per-corner G/B; achromatic face omits G/B', () => {
  const m = core.read3d(golden());
  const rgb = Uint8Array.from([1^128, 2^128, 3^128, 1, 4^128, 5^128, 6^128, 7^128, 8^128, 9^128, 10^128, 11^128, 12^128, 13^128, 0]);
  core.attachLightmap(m, rgb, 'color13');
  assert.deepEqual(m.meshes[0].faces[0].corners.map(c => c.light), [[1,4,5],[2,6,7],[3,8,9]]);
  assert.deepEqual(m.meshes[0].faces[1].corners.map(c => c.light), [[10,10,10],[11,11,11],[12,12,12],[13,13,13]]);
  assert.deepEqual(core.writeLightmap(m), rgb);
});
test('truncation at every byte and trailing bytes rejected; attachment is transactional', () => {
  const data = golden(); for (let i = 0; i < data.length; i++) assert.throws(() => core.read3d(data.subarray(0, i)));
  assert.throws(() => core.read3d(Buffer.concat([data, Buffer.from([1])])), /trailing/);
  const badCount = Buffer.from(data); badCount.writeInt16BE(-1, 4); assert.throws(() => core.read3d(badCount), /Negative/);
  const badIndex = Buffer.from(data); badIndex.writeInt16BE(4, 36); assert.throws(() => core.read3d(badIndex), /index/);
  const model = core.read3d(data); assert.throws(() => core.attachLightmap(model, new Uint8Array(6), 'gray9'), /Truncated/);
  assert.equal(model.meshes[0].faces[0].corners[0].light, null);
  assert.throws(() => core.attachLightmap(model, new Uint8Array(8), 'gray9'), /trailing/);
  assert.throws(() => core.attachLightmap(model, Uint8Array.from([0,0,0,2]), 'color13'), /flag/);
  assert.throws(() => core.attachLightmap(model, new Uint8Array(7), 'auto'), /explicitly/);
});
for (const format of ['gray9', 'color13']) for (const mode of ['safe', 'balanced', 'experimental']) {
  test(`${format}/${mode}: real WASM reduction, endpoint UV/light provenance, remap, exact oriented triangle equivalence after quad packing`, async () => {
    const input = reload(grid(20, format === 'color13')), original = Buffer.from(input.originalBytes), map = Buffer.from(input.originalLightmapBytes);
    const wasmTriangles = [];
    const spy = { supported: simplifier.supported, ready: simplifier.ready, simplifyWithAttributes(...args) {
      const [indices, positions, ps, attrs, stride] = args; assert.equal(ps, 3);
      const out = simplifier.simplifyWithAttributes(...args);
      for (let i = 0; i < out[0].length; i += 3) {
        const points = Array.from(out[0].subarray(i, i + 3), v => Array.from(positions.subarray(v*3,v*3+3)).join(',') + '/' + Array.from(attrs.subarray(v*stride,v*stride+2)).join(',') + '/' + Array.from(attrs.subarray(v*stride+2,v*stride+5)).join(','));
        wasmTriangles.push('1:' + triangleKey(points));
      }
      return out;
    } };
    const output = await core.optimize(input, { ...opts, mode, allowSeamChanges: mode === 'experimental' }, spy);
    assert(output.report.changed); assert(output.report.after.triangles < output.report.before.triangles * .8);
    assert(output.report.after.expandedCorners < output.report.before.expandedCorners);
    assert.deepEqual(orientedTriangles(output.model), wasmTriangles.sort());
    const available = new Set(input.meshes.flatMap(m => m.faces.flatMap(f => f.corners.map(c => f.material + ':' + point(c, m)))));
    for (const m of output.model.meshes) for (const f of m.faces) for (const c of f.corners) assert(available.has(f.material + ':' + point(c, m)));
    assert.deepEqual(Buffer.from(input.originalBytes), original); assert.deepEqual(Buffer.from(input.originalLightmapBytes), map);
    const reread = core.read3d(output.modelBytes); core.attachLightmap(reread, output.lightmapBytes, format);
    assert.deepEqual(core.stats(reread), core.stats(output.model)); assert.equal(output.report.fpsMeasured, false);
  });
}
test('protected material and ratio1 produce original BYTE payloads, not blind triangulation', async () => {
  const input = reload(grid());
  for (const extra of [{ protectedMaterials: [1] }, { ratio: 1 }]) {
    const out = await core.optimize(input, { ...opts, ...extra }, simplifier);
    assert.equal(out.report.changed, false); assert.deepEqual(out.modelBytes, input.originalBytes); assert.deepEqual(out.lightmapBytes, input.originalLightmapBytes);
    assert.equal(out.report.before.expandedCorners, out.report.after.expandedCorners);
  }
});
test('material boundary positions fixed on BOTH independently simplified sides', async () => {
  const input = grid(20); input.meshes[0].faces.forEach((f,i) => { f.material = i % 20 < 10 ? 1 : 2; });
  const out = await core.optimize(reload(input), opts, simplifier); assert(out.report.changed);
  for (let z = 0; z <= 20; z++) for (const material of [1, 2]) {
    const key = [1000,0,z*100].join(',');
    assert(out.model.meshes[0].faces.some(f => f.material === material && f.corners.some(c => out.model.meshes[0].vertices[c.v].join(',') === key)));
  }
});
test('separate meshes and disconnected component anchors survive; material4 geometry/light is unchanged', async () => {
  const input = grid(), mesh = input.meshes[0], offset = mesh.vertices.length;
  mesh.vertices.push([10000,0,0],[10100,0,0],[10000,100,0],[10000,0,100]);
  for (const tri of [[0,2,1],[0,1,3],[0,3,2],[1,2,3]]) mesh.faces.push({ material: 1, corners: tri.map(v => corner(offset+v)) });
  const protectedMesh = grid(2).meshes[0]; protectedMesh.faces.forEach(f => { f.material=4; }); input.meshes.push(protectedMesh);
  const source = reload(input), out = await core.optimize(source, { ...opts, maxError: 1000 }, simplifier);
  assert.equal(out.model.meshes.length, 2); assert.deepEqual(out.model.meshes[1], source.meshes[1]);
  assert(out.model.meshes[0].faces.some(f => f.corners.every(c => out.model.meshes[0].vertices[c.v][0] >= 10000)));
  assert.equal(out.report.details[0].groups[0].positionalComponentsAnchored, 2);
});
test('native-corner regression guard refuses a smaller-triangle but larger-corner result', async () => {
  const input = reload(grid(3)), fake = { supported: true, ready: Promise.resolve(), simplifyWithAttributes(indices) {
    const first = [], second = [];
    for (let i = 0; i < indices.length; i += 6) { first.push(...indices.slice(i,i+3)); second.push(...indices.slice(i+3,i+6)); }
    return [Uint32Array.from(first.slice(3).concat(second)), 0];
  } };
  const out = await core.optimize(input, opts, fake);
  assert(out.report.details[0].candidate.triangles < out.report.before.triangles);
  assert(out.report.details[0].candidate.expandedCorners > out.report.before.expandedCorners);
  assert.equal(out.report.changed, false); assert.deepEqual(out.modelBytes, input.originalBytes);
});
test('explicit static/no-map/seam consent, strict settings and serialization limits', async () => {
  const source = reload(grid());
  await assert.rejects(core.optimize(source, { ...opts, staticModel: false }, simplifier), /static/);
  const noMap = core.read3d(source.originalBytes);
  await assert.rejects(core.optimize(noMap, opts, simplifier), /lightmap/);
  assert((await core.optimize(noMap, { ...opts, withoutLightmap: true }, simplifier)).report.changed);
  await assert.rejects(core.optimize(source, { ...opts, mode: 'experimental' }, simplifier), /allowSeamChanges/);
  for (const extra of [{ ratio: NaN }, { ratio: .01 }, { maxError: -1 }, { lightWeight: 0 }, { protectedMaterials: [-1] }, { mode: 'sloppy' }]) await assert.rejects(core.optimize(source, { ...opts, ...extra }, simplifier));
  const invalid = grid(2); invalid.meshes[0].vertices[0][0] = 32768; assert.throws(() => core.write3d(invalid), /int16/);
  invalid.meshes[0].vertices[0][0] = 0; invalid.meshes[0].faces[0].corners[0].uv[0] = 256; assert.throws(() => core.write3d(invalid), /UV/);
});
test('CLI: offline run, exclusive output, no overwrites, bad argument creates nothing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'quantum-lod-')), cli = path.resolve(__dirname, '../../tools/model-optimizer/cli.cjs');
  try {
    const input = path.join(dir,'model.3d'), output = path.join(dir,'lod.3d'), map = path.join(dir,'map.vla'), mapOut = path.join(dir,'lod.vla'), m = grid();
    fs.writeFileSync(input,core.write3d(m)); fs.writeFileSync(map,core.writeLightmap(m)); const original = fs.readFileSync(input);
    const args = [cli,'optimize',input,'--output',output,'--static','--ratio','.5','--error','10','--lightmap',map,'--lightmap-output',mapOut];
    let run = cp.spawnSync(process.execPath,args,{encoding:'utf8'}); assert.equal(run.status,0,run.stderr); assert(JSON.parse(run.stdout).changed);
    const saved = fs.readFileSync(output); run = cp.spawnSync(process.execPath,args,{encoding:'utf8'}); assert.equal(run.status,1); assert.match(run.stderr,/overwrite/);
    assert.deepEqual(fs.readFileSync(output),saved); assert.deepEqual(fs.readFileSync(input),original);
    const bad = path.join(dir,'bad.3d'); run = cp.spawnSync(process.execPath,[cli,'optimize',input,'--output',bad,'--without-lightmap','--static','--error','NaN'],{encoding:'utf8'}); assert.equal(run.status,1); assert(!fs.existsSync(bad));
    run = cp.spawnSync(process.execPath,[cli,'inspect',input,'--lightmap',map],{encoding:'utf8'}); assert.equal(run.status,0,run.stderr); assert.equal(JSON.parse(run.stdout).triangles,800);
  } finally { fs.rmSync(dir,{recursive:true,force:true}); }
});
test('vendored WASM and core load as classic file:// scripts without modules/fetch or npm', async () => {
  const context = vm.createContext({ WebAssembly, Uint8Array, Uint32Array, Float32Array, Int32Array, ArrayBuffer, DataView, Promise, console, TextDecoder, TextEncoder, setTimeout });
  for (const name of ['vendor/meshopt_simplifier.js','optimizer.js']) vm.runInContext(fs.readFileSync(path.join(__dirname,'../../tools/model-optimizer',name),'utf8'),context);
  assert(context.MeshoptSimplifier.supported); await context.MeshoptSimplifier.ready;
  assert.equal(typeof context.QuantumModelOptimizer.optimize,'function');
  assert.equal(typeof context.fetch,'undefined');
});
