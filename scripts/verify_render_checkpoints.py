#!/usr/bin/env python3
"""Read-only rollback integrity check. Never resets Git or overwrites work."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    data=json.loads((ROOT/'configs/render-checkpoints.json').read_text())
    for key in ('stable_game','native_room_v1','native_room_v2'):
        checkpoint=data[key]
        for file in checkpoint['files']:
            path=ROOT/file['path']
            if path.stat().st_size!=file['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest()!=file['sha256']:
                raise SystemExit('CHECKPOINT CHANGED: '+file['path'])
        print('PASS',checkpoint['name'],checkpoint['commit'])
        print('https://github.com/Xattabchor/Quantum/raw/'+checkpoint['commit']+'/'+checkpoint['files'][0]['path'])
if __name__=='__main__':main()
