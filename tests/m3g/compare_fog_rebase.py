#!/usr/bin/env python3
"""Validate packet-boundary-independent level13 witnesses; no FPS assertions."""
import argparse
import csv
from pathlib import Path
import re

TOKEN = re.compile(r'(?:^| )([A-Za-z][A-Za-z0-9]*)=(\{[^}]*\}|[^ ]+)')
IDENTITY = ('part', 'fog', 'sample', 'phase', 'room', 'pos', 'yaw', 'pitch')
INVARIANTS = ('projected', 'queued', 'triangles', 'B', 'T', 'readbacks', 'copies',
              'clipAttempts', 'clipRecovered', 'clipTriangles', 'rejects',
              'packRejects', 'stream', 'sky', 'sprites')


def read(path):
    rows = [dict(TOKEN.findall(line)) for line in Path(path).read_text().splitlines()
            if line.startswith('L13 part=')]
    if len(rows) != 320 or any(row['fog'] != 'on' for row in rows):
        raise ValueError('Expected 320 level13 cold/warm samples with original world Fog ON')
    if len({tuple(row[key] for key in IDENTITY) for row in rows}) != 320:
        raise ValueError('Duplicate samples')
    return rows


def compare(reference, optimized):
    result = []
    for old, new in zip(reference, optimized):
        for key in IDENTITY + INVARIANTS:
            if old[key] != new[key]:
                raise ValueError(f'{tuple(old[k] for k in IDENTITY)}: {key}: '
                                 f'{old[key]} != {new[key]}')
        if int(new['D']) > int(old['D']):
            raise ValueError('Native draw count increased: ' + str(new))
        result.append({**{key: new[key] for key in IDENTITY},
                       'referenceD': old['D'], 'optimizedD': new['D'],
                       'savedD': int(old['D']) - int(new['D']),
                       'referenceWorld': old['world'], 'optimizedWorld': new['world'],
                       'referenceMesh': old['mesh'], 'optimizedMesh': new['mesh'],
                       'sky': new['sky'], 'sprites': new['sprites'],
                       'referenceScaleSplits': old['scaleSplits'],
                       'optimizedScaleSplits': new['scaleSplits'],
                       'B': new['B'], 'T': new['T'], 'triangles': new['triangles'],
                       'stream': new['stream']})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--optimized', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = compare(read(args.reference), read(args.optimized))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    warm = [row for row in rows if row['phase'] == 'warm' and row['part'] == 'objects']
    old = sum(int(row['referenceD']) for row in warm)
    new = sum(int(row['optimizedD']) for row in warm)
    print(f'PASS {len(rows)} ordered native triangle witnesses and invariant counters equal')
    print(f'80 warm object samples: {old} -> {new} native draws; '
          f'{sum(row["savedD"] > 0 for row in warm)} improved; none increased (not FPS)')


if __name__ == '__main__':
    main()
