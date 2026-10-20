#!/usr/bin/env python3
"""Standalone Canvas worker/self-repaint and GameCanvas flush benchmark. No game JAR is modified."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parent))
from build_cldc import API_NAMES,audit_jar
ROOT=Path(__file__).resolve().parents[1]
STEM='Quantum_FramePump_Test'
EXPECTED={'code/Diagnostics/'+n+'.class' for n in ('FramePumpPreview','FramePumpPreview$PaintView','FramePumpPreview$FlushView','FramePumpStats')}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java',default=os.environ.get('JAVA','java'))
    p.add_argument('--ecj-jar',default=os.environ.get('ECJ_JAR','build/tools/ecj.jar'))
    p.add_argument('--api-dir',type=Path,default=ROOT/'build/sdk/lib')
    p.add_argument('--proguard-jar',default=str(ROOT/'build/sdk/proguard/lib/proguard.jar'))
    p.add_argument('--output-dir',type=Path,default=ROOT/'artifacts/frame-pump')
    a=p.parse_args();work=ROOT/'build/frame-pump';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
    for path in classes.rglob('*.class'):path.unlink()
    sources=sorted((ROOT/'experiments/frame-pump/src').rglob('*.java'));api=[(a.api_dir/n).resolve() for n in API_NAMES]
    subprocess.run([a.java,'-jar',a.ecj_jar,'-source','1.3','-target','1.3','-encoding','UTF-8','-proc:none','-nowarn','-bootclasspath',os.pathsep.join(map(str,api)),'-d',str(classes)]+list(map(str,sources)),check=True)
    raw=work/'unverified.jar';verified=work/'verified.jar'
    with zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(classes.rglob('*.class')):z.write(path,path.relative_to(classes).as_posix())
    cmd=[a.java,'-jar',a.proguard_jar,'-injars',str(raw),'-outjars',str(verified)]
    for path in api:cmd+=['-libraryjars',str(path)]
    subprocess.run(cmd+['-microedition','-dontshrink','-dontoptimize','-dontobfuscate','-dontnote','-dontwarn'],check=True)
    manifest='\r\n'.join(['Manifest-Version: 1.0','MIDlet-Name: Quantum Frame Pump','MIDlet-Version: 1.0.0','MIDlet-Vendor: Quantum Experiments','MIDlet-1: Frame Pump,,code.Diagnostics.FramePumpPreview','MicroEdition-Configuration: CLDC-1.1','MicroEdition-Profile: MIDP-2.0','',''])
    out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=True);jar=out/(STEM+'.jar')
    with zipfile.ZipFile(verified) as z,zipfile.ZipFile(jar,'w') as target:
        entries={n:z.read(n) for n in z.namelist() if n.endswith('.class')};assert set(entries)==EXPECTED
        entries['META-INF/MANIFEST.MF']=manifest.encode()
        for n,b in sorted(entries.items()):
            info=zipfile.ZipInfo(n,(2026,10,20,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;target.writestr(info,b,compresslevel=9)
    report=audit_jar(jar)
    report.update(jar_bytes=jar.stat().st_size,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        standalone=True,game_files_changed=False,geometry_render_calls=0,textures=0,world_assets=0,
        modes=['0 WORKER Canvas repaint + completion handshake', '1 SELF Canvas repaint at end of paint, worker parked', '2 single worker GameCanvas fill + flushGraphics'],
        controls=dict(key0='next',key1='worker Canvas',key3='self Canvas',key5='GameCanvas flush',key7='FULL/MIN HUD',pound='exit'),
        measurement=dict(warmup_ms=1000,window_ms=3000,I='start-to-start',P='previous paint body for Canvas; previous draw+flush for GameCanvas',G='I minus P; includes statistics, return and scheduling, not pure OS wait',F='fill',U='flush wall time, included in P, only GameCanvas',H='HUD and marker',Q='request-to-paint only Canvas, unknown samples excluded',clock='coarse currentTimeMillis wall time; tenth-ms averages not timer resolution; callback/flush FPS NOT proven LCD flips'),
        buffers='GameCanvas managed buffer, no Image or M3G objects; no per-frame test-side allocation except text once per window',
        pump='all S0: no sleep/yield/serviceRepaints; Canvas one pending request; SELF does not wake worker per frame; GameCanvas one drawing/flush thread; lifecycle and controls serialized against frames',
        unsupported='flush failure stops measurement and shows Alert, no silent Canvas fallback',
        handset_tested=False,handset_fps_measured=False,
        purpose='Compare worker handshake, self repaint and single-thread flush after ~30ms gaps on handset Canvas controls; no promised speedup.')
    (out/(STEM+'.build-report.json')).write_text(json.dumps(report,indent=2)+'\n')
    jad=manifest.replace('Manifest-Version: 1.0\r\n','').rstrip()+'\r\nMIDlet-Jar-URL: '+jar.name+'\r\nMIDlet-Jar-Size: '+str(jar.stat().st_size)+'\r\n'
    (out/(STEM+'.jad')).write_bytes(jad.encode());print(json.dumps(report,indent=2))
if __name__=='__main__':main()
