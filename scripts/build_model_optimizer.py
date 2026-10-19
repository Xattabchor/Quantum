#!/usr/bin/env python3
"""Bundle the offline tool. No npm/build server/network required."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'tools/model-optimizer'
HTML = ROOT / 'downloads/Quantum_Model_Optimizer.html'
ZIP = ROOT / 'downloads/Quantum_Model_Optimizer.zip'
SCRIPTS = ['vendor/meshopt_simplifier.js', 'optimizer.js', 'app.js']


def main():
    provenance = json.loads((TOOL / 'vendor/PROVENANCE.json').read_text())
    actual = hashlib.sha256((TOOL / SCRIPTS[0]).read_bytes()).hexdigest()
    if actual != provenance['local_js_sha256']:
        raise SystemExit('Vendored meshoptimizer hash changed; review provenance first')
    html = (TOOL / 'index.html').read_text()
    for script in SCRIPTS:
        source = (TOOL / script).read_text()
        if '</script' in source.lower():
            raise SystemExit('Unsafe inline script terminator in ' + script)
        tag = '<script src="' + script + '"></script>'
        if html.count(tag) != 1:
            raise SystemExit('Expected exactly one script tag for ' + script)
        html = html.replace(tag, '<script>\n' + source + '\n</script>')
    licenses = '\n\n'.join([
        'SINGLE-FILE OFFLINE SOURCE BUNDLE. No external dependencies at runtime.',
        'Quantum tool source: GPL-3.0. All browser source is inline and readable.',
        'Vendored source provenance:\n' + (TOOL / 'vendor/PROVENANCE.json').read_text(),
        'MESHOPTIMIZER LICENSE:\n' + (TOOL / 'vendor/LICENSE.meshoptimizer.txt').read_text(),
        'QUANTUM LICENSE:\n' + (ROOT / 'LICENSE').read_text(),
    ])
    if '-->' in licenses:
        raise SystemExit('Unsafe license comment')
    html = html.replace('</head>', '<!--\n' + licenses + '\n-->\n</head>')
    HTML.parent.mkdir(parents=True, exist_ok=True)
    HTML.write_bytes(html.encode('utf-8'))
    # Fixed timestamps/modes, sorted paths: reproducible byte-for-byte package.
    entries = {'Quantum_Model_Optimizer.html': HTML.read_bytes(),
               'LICENSE': (ROOT / 'LICENSE').read_bytes()}
    for file in sorted(TOOL.rglob('*')):
        if file.is_file():
            entries['model-optimizer/' + file.relative_to(TOOL).as_posix()] = file.read_bytes()
    with zipfile.ZipFile(ZIP, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 19, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    for file in [HTML, ZIP]:
        print(hashlib.sha256(file.read_bytes()).hexdigest(), file.stat().st_size, file.relative_to(ROOT))


if __name__ == '__main__':
    main()
