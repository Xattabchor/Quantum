#!/usr/bin/env python3
"""Read user-provided game assets without extracting or importing old classes.

Prepare a resource-only test classpath:
  python scripts/game_resources.py --archive 'q 92 20251201y.jar.zip'
Outputs stay under ignored build/ by default. No downloads or config edits.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def read_resources(archive):
    resources = {}
    size = 0
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            name = entry.filename
            parts = PurePosixPath(name).parts
            if (not parts or name.startswith('/') or '\\' in name
                    or ':' in name or any(p in ('.', '..') for p in name.split('/'))):
                raise ValueError('Unsafe resource path: ' + name)
            if entry.is_dir():
                continue
            # Never let an uploaded game's code, signatures or manifest replace
            # the current engine or optional APIs, even if this input is a JAR.
            if name.lower().endswith('.class') or parts[0].upper() == 'META-INF':
                continue
            if name in resources:
                raise ValueError('Duplicate resource: ' + name)
            size += entry.file_size
            if entry.file_size > 64 * 1024 * 1024 or size > 256 * 1024 * 1024:
                raise ValueError('Resource archive exceeds test size limit')
            resources[name] = source.read(entry)
    if not resources:
        raise ValueError('No game resources found')
    return resources


def inventory(resources):
    levels = {}
    png = {}
    for name, data in sorted(resources.items()):
        if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data) >= 24:
            w, h = struct.unpack('>II', data[16:24])
            png[name] = {'width': w, 'height': h,
                         'power_of_two': w > 0 and h > 0 and not (w & (w-1) or h & (h-1))}
        if re.fullmatch(r'level\d+\.txt', name):
            fields = {}
            for line in data.decode('cp1251').splitlines():
                key, sep, value = line.partition('=')
                key = key.strip()
                if sep and (key.startswith('SKYBOX') or key in
                            ('WORLD_MODEL', 'WORLD_TEXTURE', 'WORLD_SCALE', 'START',
                             'DRAW_MODES', 'RAIN', 'SNOW', 'LIGHTNING', 'M3G_BLEND_COLOR')):
                    fields.setdefault(key, value.strip())
            levels[name] = fields
    return {'resource_files': len(resources), 'resource_bytes': sum(map(len, resources.values())),
            'levels': levels, 'png': png,
            'files_sha256': {n: hashlib.sha256(d).hexdigest() for n, d in sorted(resources.items())},
            'runtime_tested': False,
            'note': 'Inventory and adapter contracts are not a visual emulator or a device FPS measurement.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=ROOT / 'q 92 20251201y.jar.zip')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/game-resources')
    args = parser.parse_args()
    resources = read_resources(args.archive)
    report = inventory(resources)
    report['archive_sha256'] = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    jar = args.output_dir / 'resources.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
        for name, data in sorted(resources.items()):
            target.writestr(name, data)
    report_path = args.output_dir / 'resource-report.json'
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Resources:', len(resources), '| levels:', len(report['levels']), '| PNG:', len(report['png']))
    print('Test classpath:', jar)
    print('Inventory:', report_path)


if __name__ == '__main__':
    main()
