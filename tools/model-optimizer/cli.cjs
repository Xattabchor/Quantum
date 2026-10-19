#!/usr/bin/env node
/* GPL-3.0. Node.js 18+; no npm install or network at runtime. */
'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const core = require('./optimizer.js'), simplifier = require('./vendor/meshopt_simplifier.js');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function help() {
  console.log(`Quantum Model Optimizer — custom big-endian Quantum .3d, not generic .3ds

node cli.cjs inspect INPUT.3d [--lightmap FILE.vla --format gray9|color13]
node cli.cjs optimize INPUT.3d --output NEW.3d --static --ratio 0.75 --error 5
  --lightmap OLD.vla --lightmap-output NEW.vla [--format gray9|color13]
  [--mode safe|balanced|experimental] [--allow-seam-changes] [--protect-materials 4,7] [--uv-weight 1] [--light-weight 1]
  [--report NEW.json]

Without a baked map: explicitly pass --without-lightmap instead.
--ratio is the FRACTION TO KEEP, not the fraction to delete.
--error is meshoptimizer's approximate combined error, in unscaled source units.
The tool never overwrites existing files. Protected IDs are texture/material
indices, NOT draw modes. Default protected ID4 is the current sym9 river.
Static visual LOD only: do not replace morph frames, collision or portals blindly.
For a local browser interface, open index.html from the same folder.`);
}
function parse(argv) {
  const command = argv.shift(), input = argv.shift(), flags = {};
  const boolean = new Set(['static', 'without-lightmap', 'allow-seam-changes']);
  const known = new Set(['output', 'lightmap', 'lightmap-output', 'format', 'ratio', 'error', 'mode', 'protect-materials', 'uv-weight', 'light-weight', 'report', ...boolean]);
  while (argv.length) {
    const key = argv.shift().replace(/^--/, '');
    if (!known.has(key) || Object.hasOwn(flags, key)) throw Error('Unknown/duplicate option: ' + key);
    if (boolean.has(key)) flags[key] = true;
    else { if (!argv.length || argv[0].startsWith('--')) throw Error('Missing value: ' + key); flags[key] = argv.shift(); }
  }
  if (!['inspect', 'optimize'].includes(command) || !input || input.startsWith('--')) throw Error('Expected inspect|optimize INPUT.3d (use --help)');
  return { command, input, flags };
}
function safeOutputs(outputs, inputs) {
  const resolved = outputs.map(p => path.resolve(p));
  if (new Set(resolved).size !== resolved.length) throw Error('Output paths must be distinct');
  for (const file of resolved) {
    if (inputs.includes(file) || fs.existsSync(file)) throw Error('Refusing to overwrite input/existing path: ' + file);
    if (!fs.existsSync(path.dirname(file))) throw Error('Output directory does not exist: ' + path.dirname(file));
  }
}
async function main() {
  if (process.argv.includes('--help') || process.argv.length === 2) { help(); return; }
  const { command, input, flags: f } = parse(process.argv.slice(2));
  if (f.lightmap && f['without-lightmap']) throw Error('Choose a lightmap OR --without-lightmap');
  if (f['lightmap-output'] && !f.lightmap) throw Error('--lightmap-output requires --lightmap');
  const inputBytes = fs.readFileSync(input), model = core.read3d(inputBytes);
  const mapBytes = f.lightmap ? fs.readFileSync(f.lightmap) : null;
  if (mapBytes) core.attachLightmap(model, mapBytes, f.format || 'gray9');
  if (command === 'inspect') { console.log(JSON.stringify({ ...core.stats(model), inputSha256: hash(inputBytes), lightmapSha256: mapBytes ? hash(mapBytes) : null }, null, 2)); return; }
  if (!f.output) throw Error('--output is required');
  if (mapBytes && !f['lightmap-output']) throw Error('--lightmap-output is required with --lightmap');
  const reportPath = f.report || f.output + '.report.json';
  const inputs = [input, ...(f.lightmap ? [f.lightmap] : [])].map(p => fs.realpathSync(p));
  const outputs = [f.output, ...(mapBytes ? [f['lightmap-output']] : []), reportPath]; safeOutputs(outputs, inputs);
  const result = await core.optimize(model, {
    ratio: f.ratio == null ? .75 : Number(f.ratio), maxError: f.error == null ? 5 : Number(f.error), mode: f.mode || 'safe',
    protectedMaterials: core.parseMaterials(f['protect-materials']), uvWeight: f['uv-weight'] == null ? 1 : Number(f['uv-weight']), lightWeight: f['light-weight'] == null ? 1 : Number(f['light-weight']),
    staticModel: !!f.static, withoutLightmap: !!f['without-lightmap'], allowSeamChanges: !!f['allow-seam-changes']
  }, simplifier);
  Object.assign(result.report, { inputSha256: hash(inputBytes), outputSha256: hash(result.modelBytes), inputLightmapSha256: mapBytes ? hash(mapBytes) : null, outputLightmapSha256: result.lightmapBytes ? hash(result.lightmapBytes) : null });
  // Exclusive creates also reject symlinks and races. Roll back only files
  // this invocation successfully created, never inputs or pre-existing paths.
  const created = [];
  try {
    for (const [file, data] of [[f.output, result.modelBytes], ...(result.lightmapBytes ? [[f['lightmap-output'], result.lightmapBytes]] : []), [reportPath, JSON.stringify(result.report, null, 2) + '\n']]) {
      const fd = fs.openSync(file, 'wx'); created.push(file);
      try { fs.writeFileSync(fd, data); } finally { fs.closeSync(fd); }
    }
  } catch (error) { for (const file of created) fs.unlinkSync(file); throw error; }
  console.log(JSON.stringify({ changed: result.report.changed, before: result.report.before.triangles, after: result.report.after.triangles, beforeCorners: result.report.before.expandedCorners, afterCorners: result.report.after.expandedCorners, targetReached: result.report.targetReached, output: f.output, lightmap: f['lightmap-output'] || null, report: reportPath }, null, 2));
}
main().catch(error => { console.error('ERROR:', error.message); process.exitCode = 1; });
