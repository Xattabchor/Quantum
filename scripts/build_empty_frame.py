#!/usr/bin/env python3
"""Standalone map-free CLDC/MIDP/M3G frame-path benchmark. No game JAR is modified."""
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
STEM='Quantum_EmptyFrame_Test'
EXPECTED={'code/Diagnostics/'+n+'.class' for n in ('EmptyFramePreview','EmptyFramePreview$Viewer','EmptyFrameStats','EmptyFrameNative')}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java',default=os.environ.get('JAVA','java'))
    p.add_argument('--ecj-jar',default=os.environ.get('ECJ_JAR','build/tools/ecj.jar'))
    p.add_argument('--api-dir',type=Path,default=ROOT/'build/sdk/lib')
    p.add_argument('--proguard-jar',default=str(ROOT/'build/sdk/proguard/lib/proguard.jar'))
    p.add_argument('--output-dir',type=Path,default=ROOT/'artifacts/empty-frame')
    a=p.parse_args();work=ROOT/'build/empty-frame';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
    for path in classes.rglob('*.class'):path.unlink()
    sources=sorted((ROOT/'experiments/empty-frame/src').rglob('*.java'));api=[(a.api_dir/n).resolve() for n in API_NAMES]
    subprocess.run([a.java,'-jar',a.ecj_jar,'-source','1.3','-target','1.3','-encoding','UTF-8','-proc:none','-nowarn','-bootclasspath',os.pathsep.join(map(str,api)),'-d',str(classes)]+list(map(str,sources)),check=True)
    raw=work/'unverified.jar';verified=work/'verified.jar'
    with zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(classes.rglob('*.class')):z.write(path,path.relative_to(classes).as_posix())
    cmd=[a.java,'-jar',a.proguard_jar,'-injars',str(raw),'-outjars',str(verified)]
    for path in api:cmd+=['-libraryjars',str(path)]
    subprocess.run(cmd+['-microedition','-dontshrink','-dontoptimize','-dontobfuscate','-dontnote','-dontwarn'],check=True)
    manifest='\r\n'.join(['Manifest-Version: 1.0','MIDlet-Name: Quantum Empty Frame','MIDlet-Version: 1.0.0','MIDlet-Vendor: Quantum Experiments','MIDlet-1: Empty Frame,,code.Diagnostics.EmptyFramePreview','MicroEdition-Configuration: CLDC-1.1','MicroEdition-Profile: MIDP-2.0','',''])
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
        modes=['0 Canvas fill','1 cached Image copy','2 Image fill + copy','3 M3G bind/release + copy, depth attached, NO clear','4 M3G RGB clear + copy, depth attached','5 M3G RGB+Z clear + copy, depth attached','6 M3G RGB clear + copy, NO depth attachment','7 M3G directly on Canvas RGB+Z, optional support','8 Canvas idle, only HUD/marker'],
        controls=dict(key0='next',key2='previous',key1='Canvas fill',key3='Image copy',key5='M3G RGBZ + copy',star='SYNC serviceRepaints / QUEUED one-pending repaint',key7='FULL / MIN two-line HUD',key9='S1 sleep(1) / S0 no intentional sleep',pound='exit'),
        measurement=dict(warmup_ms=1000,window_ms=3000,F='MIDP fill',B='bindTarget',S='viewport/camera/resetLights/background flags',K='clear only',E='releaseTarget',C='drawImage',H='HUD+changing marker',Q='request timestamp to paint entry, requested samples only',
            I='paint start-to-start',P='previous measured paint body including HUD/state restoration',G='previous measured paint end to next paint start; includes statistics/return/loop/display/scheduling, NOT pure OS wait',
            identity='sumI == sumP + sumG exactly; displayed integer means rounded down independently',
            svc='LAST serviceRepaints wall duration; overlaps paint, never add to I/P/G',sleep='LAST actual sleep(1) wall duration; S0 skips Thread.sleep entirely, not sleep(0); clock is coarse',
            clock='System.currentTimeMillis, coarse wall time; displayed tenth-ms averages are NOT sub-ms clock resolution; 0.0 does NOT prove free; not GPU timestamps'),
        buffers='Image/native helper lazy cold allocation once; Image recreated only on resize; no geometry or texture uploads',
        pump='default same sleep(1) in both; key9 explicitly disables sleep for either pump; at most one outstanding request; monitor released before repaint/serviceRepaints; QUEUED waits for paint completion without flooding',
        unsupported='per-mode explicit failure, no silent target fallback; other modes remain selectable',
        handset_tested=False,handset_fps_measured=False,
        purpose='Locate the ~24ms outside existing native-path timer; no promised handset speedup.')
    (out/(STEM+'.build-report.json')).write_text(json.dumps(report,indent=2)+'\n')
    jad=manifest.replace('Manifest-Version: 1.0\r\n','').rstrip()+'\r\nMIDlet-Jar-URL: '+jar.name+'\r\nMIDlet-Jar-Size: '+str(jar.stat().st_size)+'\r\n'
    (out/(STEM+'.jad')).write_bytes(jad.encode());print(json.dumps(report,indent=2))
if __name__=='__main__':main()
