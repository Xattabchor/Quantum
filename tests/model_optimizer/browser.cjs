/* Optional real-browser smoke test (dev only; no packages needed by the tool).
 * npm install --prefix build/model-optimizer-browser --ignore-scripts --no-audit --no-fund playwright-core@1.64.1 @sparticuz/chromium@153.0.0
 * python scripts/build_model_optimizer.py
 * NODE_PATH="$PWD/build/model-optimizer-browser/node_modules" node tests/model_optimizer/browser.cjs
 * Requires Linux + Node22+. Chromium is extracted to system temp; outputs stay ignored in build/.
 */
'use strict';
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright-core'), chromiumPackage = require('@sparticuz/chromium');
const binary = chromiumPackage.default || chromiumPackage;
const core = require('../../tools/model-optimizer/optimizer.js');
const root = path.resolve(__dirname,'../..'), build = path.join(root,'build/model-optimizer-browser-tests');
fs.mkdirSync(build,{recursive:true});
const temp = fs.mkdtempSync(path.join(build,'files-'));
async function main() {
  cp.execFileSync('python',['-c',`import zipfile,pathlib,sys
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile('downloads/Quantum_StaticRoom_Depth.jar') as z:
 p.joinpath('kordos.3d').write_bytes(z.read('maps/kordos/kordos.3d'))
 p.joinpath('kordod.vla').write_bytes(z.read('lightmap/kordod.vla'))`,temp],{cwd:root});
  // The slim sandbox may lack NSPR/NSS. Use the libraries already shipped
  // in this pinned Chromium npm package; no system install/network fetch.
  const libraryArchive = path.resolve(path.dirname(require.resolve('@sparticuz/chromium')),'../bin/al2023.tar.br');
  if (process.platform === 'linux' && fs.existsSync(libraryArchive)) {
    const libRoot = path.join(build,'al2023'); fs.mkdirSync(libRoot,{recursive:true});
    const tar = path.join(build,'al2023.tar');
    fs.writeFileSync(tar,require('node:zlib').brotliDecompressSync(fs.readFileSync(libraryArchive)));
    cp.execFileSync('tar',['-xf',tar,'-C',libRoot]);
    process.env.LD_LIBRARY_PATH = [path.join(libRoot,'lib'),process.env.LD_LIBRARY_PATH].filter(Boolean).join(':');
  }
  const browser = await chromium.launch({ executablePath: await binary.executablePath(), args: binary.args.filter(arg => !['--disable-web-security','--allow-running-insecure-content','--single-process'].includes(arg)), headless: true });
  try {
    for (const [index,entry] of ['tools/model-optimizer/index.html','downloads/Quantum_Model_Optimizer.html'].entries()) {
      const page = await browser.newPage({viewport:{width:1280,height:980},acceptDownloads:true});
      const errors = [], network = [];
      page.on('pageerror',error=>errors.push(error.message));
      page.on('request',request=>{ if (!request.url().startsWith('file:')) network.push(request.url()); });
      await page.goto(pathToFileURL(path.join(root,entry)).href);
      await page.waitForFunction(()=>typeof QuantumModelOptimizer==='object' && MeshoptSimplifier.supported);
      await page.locator('#modelFile').setInputFiles(path.join(temp,'kordos.3d'));
      await page.waitForFunction(()=>!document.querySelector('#run').disabled);
      assert.equal((await page.locator('#beforeTriangles').innerText()).replace(/\D/g,''),'4750');
      await page.locator('#mapFile').setInputFiles(path.join(temp,'kordod.vla'));
      await page.waitForFunction(()=>!document.querySelector('#run').disabled);
      await page.locator('#staticAck').check();
      await page.locator('#ratio').selectOption('.5');
      await page.locator('#mode').selectOption('experimental');
      assert(await page.locator('#seamAckRow').isVisible());
      await page.locator('#seamAck').check(); await page.locator('#error').fill('1000');
      await page.locator('#run').click();
      await page.waitForFunction(()=>!document.querySelector('#downloadModel').disabled);
      const report = JSON.parse(await page.locator('#report').textContent());
      assert.equal(report.after.triangles,2534); assert.equal(report.after.expandedCorners,6692);
      assert.equal(report.options.allowSeamChanges,true); assert.equal(report.fpsMeasured,false);
      for (const [id,name] of [['downloadModel','result.3d'],['downloadMap','result.vla'],['downloadReport','report.json']]) {
        const [download] = await Promise.all([page.waitForEvent('download'),page.locator('#'+id).click()]);
        await download.saveAs(path.join(temp,index+'-'+name));
      }
      const model = core.read3d(fs.readFileSync(path.join(temp,index+'-result.3d')));
      core.attachLightmap(model,fs.readFileSync(path.join(temp,index+'-result.vla')),'gray9');
      assert.equal(core.stats(model).triangles,2534);
      assert.deepEqual(JSON.parse(fs.readFileSync(path.join(temp,index+'-report.json'),'utf8')),report);
      await page.screenshot({path:path.join(build,`desktop-${index}.png`),fullPage:true});
      await page.setViewportSize({width:390,height:844});
      assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'Mobile horizontal overflow');
      await page.screenshot({path:path.join(build,`mobile-${index}.png`),fullPage:true});
      await page.locator('#clearMap').click();
      await page.waitForFunction(()=>!document.querySelector('#run').disabled);
      assert.equal(await page.locator('#mapFile').evaluate(el=>el.files.length),0);
      await page.locator('#ratio').selectOption('.75');
      assert(await page.locator('#downloadModel').isDisabled(),'Stale result not invalidated');
      await page.locator('#modelFile').setInputFiles({name:'broken.3d',mimeType:'application/octet-stream',buffer:Buffer.alloc(3)});
      await page.waitForFunction(()=>!document.querySelector('#run').disabled);
      assert.match(await page.locator('#status').innerText(),/Truncated/);
      assert(await page.locator('#downloadModel').isDisabled());
      assert.deepEqual(errors,[],'Uncaught browser error'); assert.deepEqual(network,[],'Unexpected network request');
      await page.close(); console.log('PASS file:// browser upload, WASM, remapped downloads, report, mobile layout, malformed input, zero network:',entry);
    }
  } finally { await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>fs.rmSync(temp,{recursive:true,force:true}));
