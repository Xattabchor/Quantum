/* GPL-3.0. Local File API + in-memory WASM only. No network requests. */
'use strict';
(function () {
  const core = QuantumModelOptimizer, $ = id => document.getElementById(id);
  const numbers = new Intl.NumberFormat('ru-RU');
  let source = null, result = null, busy = false;
  const camera = { yaw: -.45, pitch: -.7, zoom: 1 }, view = { center: [0, 0, 0], extent: 1 };
  function status(text, kind) { $('status').textContent = text; $('status').className = kind || ''; }
  function setBusy(value) {
    busy = value; for (const el of document.querySelectorAll('#form input,#form select,#form button')) el.disabled = value;
    $('run').textContent = value ? 'Обработка локально…' : 'Создать оптимизированную копию';
  }
  function showStats(model, prefix) {
    const s = model ? core.stats(model) : null;
    for (const key of ['Triangles', 'Corners', 'Polygons', 'Vertices']) {
      const property = { Triangles: 'triangles', Corners: 'expandedCorners', Polygons: 'polygons', Vertices: 'vertices' }[key];
      $(prefix + key).textContent = s ? numbers.format(s[property]) : '—';
    }
  }
  function clearResult() {
    result = null; showStats(null, 'after'); $('reduction').textContent = '—'; $('summary').textContent = 'Сокращение треугольников';
    for (const id of ['downloadModel', 'downloadMap', 'downloadReport']) $(id).disabled = true;
    $('report').textContent = 'После обработки здесь появятся настройки, ограничения и счётчики.'; draw();
  }
  function frame(model) {
    const boxes = core.stats(model).meshes.map(m => m.bounds).filter(Boolean);
    if (!boxes.length) { view.center = [0, 0, 0]; view.extent = 1; return; }
    const min = [0, 1, 2].map(k => Math.min(...boxes.map(b => b.min[k]))), max = [0, 1, 2].map(k => Math.max(...boxes.map(b => b.max[k])));
    view.center = min.map((v, k) => (v + max[k]) / 2); view.extent = Math.max(1, ...max.map((v, k) => v - min[k])); camera.zoom = 1;
  }
  async function load() {
    const modelFile = $('modelFile').files[0]; if (!modelFile) throw Error('Выберите файл .3d');
    const model = core.read3d(await modelFile.arrayBuffer()), mapFile = $('mapFile').files[0];
    if (mapFile) core.attachLightmap(model, await mapFile.arrayBuffer(), $('format').value);
    return model;
  }
  async function inspect() {
    if (busy) return; clearResult(); source = null; showStats(null, 'before'); draw();
    if (!$('modelFile').files.length) { status('Ожидание файла .3d'); return; }
    setBusy(true);
    try { source = await load(); showStats(source, 'before'); frame(source); draw(); status('Формат модели проверен. ' + (source.lightmapFormat ? 'Lightmap прочитана.' : 'Lightmap не выбрана; если она используется, обязательно добавьте её.')); }
    catch (error) { status(error.message, 'error'); }
    finally { setBusy(false); }
  }
  async function sha(data) {
    if (!data || !globalThis.crypto || !crypto.subtle) return null;
    const digest = await crypto.subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(digest), v => v.toString(16).padStart(2, '0')).join('');
  }
  $('form').addEventListener('submit', async event => {
    event.preventDefault(); if (busy) return; clearResult(); setBusy(true); status('Чтение модели и запуск WASM…');
    try {
      source = await load(); showStats(source, 'before'); frame(source); draw();
      if ($('mapFile').files.length && $('noMap').checked) throw Error('Выберите lightmap ИЛИ режим без lightmap, не оба.');
      await new Promise(resolve => setTimeout(resolve, 25));
      const next = await core.optimize(source, {
        ratio: Number($('ratio').value), maxError: Number($('error').value), mode: $('mode').value,
        protectedMaterials: core.parseMaterials($('protect').value), staticModel: $('staticAck').checked,
        withoutLightmap: $('noMap').checked, allowSeamChanges: $('seamAck').checked
      }, MeshoptSimplifier);
      Object.assign(next.report, {
        inputSha256: await sha(source.originalBytes), outputSha256: await sha(next.modelBytes),
        inputLightmapSha256: await sha(source.originalLightmapBytes), outputLightmapSha256: await sha(next.lightmapBytes)
      });
      result = next; showStats(next.model, 'after');
      $('reduction').textContent = '−' + numbers.format(next.report.triangleReductionPercent) + '%';
      $('summary').textContent = next.report.changed ? 'Треугольников меньше. ' + (next.report.targetReached ? 'Цель достигнута.' : 'До цели не дошли: сработали ограничения; точные причины — в отчёте.') : 'Сокращения без роста native-потока не найдено. Возвращаются исходные байты.';
      $('report').textContent = JSON.stringify(next.report, null, 2);
      $('downloadModel').disabled = false; $('downloadMap').disabled = !next.lightmapBytes; $('downloadReport').disabled = false;
      status(next.report.changed ? 'Готово. Скачайте модель вместе с подходящей lightmap и сохраните оригиналы для отката.' : 'Готово без изменения геометрии. Можно попробовать другой допуск; экспериментальный режим может испортить швы.', 'success'); draw();
    } catch (error) { result = null; status(error.message, 'error'); }
    finally { setBusy(false); }
  });
  $('clearMap').onclick = () => { $('mapFile').value = ''; inspect(); };
  for (const id of ['modelFile', 'mapFile', 'format']) $(id).addEventListener('change', inspect);
  for (const id of ['ratio', 'error', 'mode', 'protect', 'noMap', 'seamAck', 'staticAck']) $(id).addEventListener('change', () => {
    clearResult(); $('seamAckRow').hidden = $('mode').value !== 'experimental';
    if ($('mode').value !== 'experimental') $('seamAck').checked = false;
    status('Настройки изменены. Создайте новую копию.');
  });
  function save(data, name, type) {
    const url = URL.createObjectURL(new Blob([data], { type })), a = document.createElement('a'); a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  const name = (original, extension) => original.replace(/\.[^.]*$/, '') + '_lod.' + extension;
  $('downloadModel').onclick = () => { if (result) save(result.modelBytes, name($('modelFile').files[0].name, '3d'), 'application/octet-stream'); };
  $('downloadMap').onclick = () => { if (result && result.lightmapBytes) save(result.lightmapBytes, name($('mapFile').files[0].name, 'vla'), 'application/octet-stream'); };
  $('downloadReport').onclick = () => { if (result) save(JSON.stringify(result.report, null, 2), name($('modelFile').files[0].name, 'report.json'), 'application/json'); };
  function render(canvas, model) {
    const ratio = Math.min(window.devicePixelRatio || 1, 2), width = Math.max(1, canvas.clientWidth), height = Math.max(1, canvas.clientHeight);
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    const ctx = canvas.getContext('2d'); if (!ctx) return; ctx.scale(ratio, ratio); ctx.clearRect(0, 0, width, height); if (!model) return;
    const cy = Math.cos(camera.yaw), sy = Math.sin(camera.yaw), cp = Math.cos(camera.pitch), sp = Math.sin(camera.pitch), scale = Math.min(width, height) * .75 * camera.zoom / view.extent;
    ctx.strokeStyle = '#9fcf79'; ctx.globalAlpha = .35; ctx.lineWidth = .6; ctx.beginPath();
    for (const mesh of model.meshes) {
      const projected = mesh.vertices.map(p => {
        const x = p[0] - view.center[0], y = p[1] - view.center[1], z = p[2] - view.center[2];
        const rx = x * cy + z * sy, rz = -x * sy + z * cy;
        return [width / 2 + rx * scale, height / 2 - (y * cp - rz * sp) * scale];
      });
      for (const f of mesh.faces) for (const tri of core.triangles(f)) {
        const a = projected[tri[0].v], b = projected[tri[1].v], c = projected[tri[2].v];
        ctx.moveTo(...a); ctx.lineTo(...b); ctx.lineTo(...c); ctx.lineTo(...a);
      }
    }
    ctx.stroke();
  }
  function draw() { render($('beforeCanvas'), source); render($('afterCanvas'), result ? result.model : null); }
  for (const id of ['beforeCanvas', 'afterCanvas']) {
    const canvas = $(id); let pointer = null;
    canvas.addEventListener('pointerdown', e => { pointer = [e.clientX, e.clientY]; canvas.setPointerCapture(e.pointerId); });
    canvas.addEventListener('pointermove', e => { if (!pointer) return; camera.yaw += (e.clientX - pointer[0]) * .008; camera.pitch = Math.max(-1.55, Math.min(1.55, camera.pitch + (e.clientY - pointer[1]) * .008)); pointer = [e.clientX, e.clientY]; draw(); });
    for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) canvas.addEventListener(type, () => { pointer = null; });
    canvas.addEventListener('wheel', e => { e.preventDefault(); camera.zoom = Math.max(.1, Math.min(20, camera.zoom * Math.exp(-e.deltaY * .001))); draw(); }, { passive: false });
  }
  window.addEventListener('resize', draw); draw();
})();
