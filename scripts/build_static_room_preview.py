#!/usr/bin/env python3
"""Add an isolated room-test MIDlet to the EXACT published engine/resources.
Never rebuild/substitute the production engine from a different source state.
Only new code/Rendering/StaticRoom*.class and the MIDlet manifest may differ.
"""
import argparse, hashlib, json, os, subprocess, sys, zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from build_cldc import API_NAMES, audit_jar
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'downloads/Quantum_Sym9_DrawCalls.jar'
BASE_SHA='e07b8d98d2e8b74d2c6f3261b09f8684a0ec5e9da578662a693e680e56c2fcd1'

def main():
    p=argparse.ArgumentParser();p.add_argument('--java',default=os.environ.get('JAVA','java'))
    p.add_argument('--ecj-jar',default=os.environ.get('ECJ_JAR','build/tools/ecj.jar'))
    p.add_argument('--api-dir',default='build/sdk/lib');p.add_argument('--proguard-jar',default='build/sdk/proguard/lib/proguard.jar')
    p.add_argument('--output-dir',default='artifacts/static-room-preview');a=p.parse_args()
    assert hashlib.sha256(BASE.read_bytes()).hexdigest()==BASE_SHA,'wrong baseline'
    work=ROOT/'build/static-room';classes=work/'classes';classes.mkdir(parents=True,exist_ok=True)
    for stale in classes.rglob('*.class'):stale.unlink()
    api=[(ROOT/a.api_dir/n).resolve() for n in API_NAMES]
    sources=sorted((ROOT/'experiments/static-room/src').rglob('*.java'))
    subprocess.run([a.java,'-jar',a.ecj_jar,'-source','1.3','-target','1.3','-encoding','UTF-8','-proc:none','-nowarn',
                    '-bootclasspath',os.pathsep.join(map(str,api)),'-classpath',str(BASE),'-d',str(classes)]+list(map(str,sources)),check=True)
    raw=work/'unverified.jar';verified=work/'verified.jar'
    with zipfile.ZipFile(raw,'w',zipfile.ZIP_DEFLATED)as z:
        for path in sorted(classes.rglob('*.class')):z.write(path,path.relative_to(classes).as_posix())
    args=['-injars',str(raw),'-outjars',str(verified),'-libraryjars',str(BASE)]
    for jar in api:args+=['-libraryjars',str(jar)]
    subprocess.run([a.java,'-jar',a.proguard_jar]+args+['-microedition','-dontshrink','-dontoptimize','-dontobfuscate','-dontnote','-dontwarn'],check=True)
    out=ROOT/a.output_dir;out.mkdir(parents=True,exist_ok=True)
    manifest='\r\n'.join(['Manifest-Version: 1.0','MIDlet-Name: Quantum Static Room Test','MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments','MIDlet-1: Room A-B,,code.Rendering.StaticRoomPreview',
        'MicroEdition-Configuration: CLDC-1.1','MicroEdition-Profile: MIDP-2.0','',''])
    jar=out/'Quantum_StaticRoom_Test.jar'
    with zipfile.ZipFile(BASE)as base,zipfile.ZipFile(verified)as patch,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED)as result:
        added=[n for n in patch.namelist()if n.endswith('.class')]
        assert added and all(n.startswith('code/Rendering/StaticRoom')for n in added)
        assert not set(base.namelist()).intersection(added)
        for entry in base.infolist():
            if entry.filename!='META-INF/MANIFEST.MF':result.writestr(entry,base.read(entry))
        for n in added:result.writestr(n,patch.read(n))
        result.writestr('META-INF/MANIFEST.MF',manifest)
    with zipfile.ZipFile(BASE)as base,zipfile.ZipFile(jar)as result:
        assert result.testzip()is None
        assert all(base.read(n)==result.read(n)for n in base.namelist()if n!='META-INF/MANIFEST.MF')
    report=audit_jar(jar);report.update(base_jar_sha256=BASE_SHA,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        jar_bytes=jar.stat().st_size,added_classes=added,baseline_engine_and_resources_unchanged=True,
        purpose='Separate first-room A/B experiment. Not a gameplay replacement or pixel-equivalent renderer.',
        handset_tested=False,fps_measured=False)
    (out/'build-report.json').write_text(json.dumps(report,indent=2)+'\n')
    jad=manifest.replace('Manifest-Version: 1.0\r\n','').rstrip()+'\r\nMIDlet-Jar-URL: '+jar.name+'\r\nMIDlet-Jar-Size: '+str(jar.stat().st_size)+'\r\n'
    (out/'Quantum_StaticRoom_Test.jad').write_bytes(jad.encode('utf-8'))
    print(json.dumps(report,indent=2));print('Artifacts:',out)
if __name__=='__main__':main()
