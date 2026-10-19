#!/usr/bin/env python3
"""Resource-only repack of the pinned, preverified Cull MIDlet; no recompilation.

Keep ALL class bytes/CLDC StackMaps for lazy verifier/linker compatibility.
Only resource dependencies of StaticRoomPreview.load are followed: the complete
settings and level1 configs, WORLD_MODEL, LIGHTMAP and every WTEX animation frame.
Do NOT follow gameplay/sky/sound paths in those configs: no game loader is run.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_cldc import audit_jar

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'downloads/Quantum_StaticRoom_Cull.jar'
SOURCE_SHA = 'bf3bf49f24bb672af7ed2fb5274c88bdc1ad82522a0604b87a226670537d45c6'
STEM = 'Quantum_StaticRoom_Cull_Lite'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def dependencies(source):
    """Mirror only the resource reads in the pinned preview's load() method."""
    needed = {'setting.txt', 'level1.txt'}
    # Paths and keys are ASCII; preserve the original CP1251 config bytes.
    text = source.read('level1.txt').decode('cp1251')
    match = re.search(r'\[WTEX_BEGIN\](.*?)\[WTEX_END\]', text, re.S)
    if match is None:
        raise ValueError('WTEX block missing')
    config = text[:match.start()] + text[match.end():]
    paths = []
    for key in ('WORLD_MODEL', 'LIGHTMAP'):
        value = re.search(r'^' + key + r'\s*=\s*([^\r\n]+)', config, re.M)
        if value is None:
            raise ValueError(key + ' missing')
        paths.append(value.group(1).strip())
    for line in match.group(1).splitlines():
        if not line.strip():
            continue
        # MultyTexture uses comma/semicolon-separated material options;
        # the first field is a path or speed:path:path:... animation.
        animation = re.split(r'[,;]', line.strip())[0].split(':')
        if len(animation) > 1:
            float(animation.pop(0))
        paths.extend(path.strip() for path in animation)
    for path in paths:
        if not path.startswith('/') or '\\' in path or '..' in PurePosixPath(path).parts:
            raise ValueError('Unsafe/nonabsolute resource path: ' + path)
        needed.add(path[1:])
    for name in sorted(needed):
        if name not in source.namelist():
            raise ValueError('Required resource missing: ' + name)
    return needed


def build(source_path=SOURCE, output_dir=ROOT / 'artifacts/static-room-cull-lite'):
    source_path, output_dir = Path(source_path), Path(output_dir)
    if digest(source_path.read_bytes()) != SOURCE_SHA:
        raise ValueError('Expected the immutable fcb5141 Cull JAR; refusing another engine')
    output_dir.mkdir(parents=True, exist_ok=True)
    jar = output_dir / (STEM + '.jar')
    if jar.resolve() == source_path.resolve():
        raise ValueError('Never overwrite the source JAR')
    with zipfile.ZipFile(source_path) as source:
        if source.testzip() is not None or len(set(source.namelist())) != len(source.namelist()):
            raise ValueError('Corrupt/duplicate source entries')
        resources = dependencies(source)
        classes = {name for name in source.namelist() if name.endswith('.class')}
        keep = classes | resources
        manifest = source.read('META-INF/MANIFEST.MF').decode('utf-8')
        manifest = manifest.replace('MIDlet-Name: Quantum Static Room Cull\r\n',
                                    'MIDlet-Name: Quantum Static Room Cull Lite\r\n')
        if 'MIDlet-Name: Quantum Static Room Cull Lite\r\n' not in manifest:
            raise ValueError('Unexpected source manifest')
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as target:
            for name in sorted(keep):
                info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, source.read(name), compresslevel=9)
            info = zipfile.ZipInfo('META-INF/MANIFEST.MF', (2026, 10, 19, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, manifest.encode('utf-8'), compresslevel=9)
        with zipfile.ZipFile(jar) as result:
            assert result.testzip() is None
            assert set(result.namelist()) == keep | {'META-INF/MANIFEST.MF'}
            assert all(result.read(name) == source.read(name) for name in keep)
        removed = sorted(set(source.namelist()) - keep - {'META-INF/MANIFEST.MF'})
        report = audit_jar(jar)
        report.update(
            source_jar=source_path.name, source_commit='fcb51419fdfdf08a773cdc5f047f800bd96ca9e5',
            source_sha256=SOURCE_SHA, source_bytes=source_path.stat().st_size,
            jar_bytes=jar.stat().st_size, jar_sha256=digest(jar.read_bytes()),
            saved_bytes=source_path.stat().st_size - jar.stat().st_size,
            saved_percent=round(100 * (1 - jar.stat().st_size / source_path.stat().st_size), 2),
            all_class_bytes_and_cldc_stackmaps_unchanged=True,
            retained_resources={name: {'bytes': len(source.read(name)), 'sha256': digest(source.read(name))}
                                for name in sorted(resources)},
            removed_resources=removed,
            purpose='Same cached cull / cached whole room preview; resource-only Lite repack, not a full game.',
            class_policy='All 162 original preverified classes retained, not dead-code elimination.',
            handset_lite_tested=False, handset_fps_improvement_claimed=False)
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (output_dir / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    (output_dir / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/static-room-cull-lite')
    args = parser.parse_args()
    print(json.dumps(build(output_dir=args.output_dir), indent=2))


if __name__ == '__main__':
    main()
