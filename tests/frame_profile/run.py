#!/usr/bin/env python3
"""Test the diagnostic profiler with a fake clock and the real MIDP API jar.
Requires the existing CLDC build/API and JAVA + ECJ_JAR (or JAVAC).
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = os.environ.get('JAVA', 'java')
API = ROOT / 'build/cldc/api.jar'
with tempfile.TemporaryDirectory(prefix='quantum-frame-profile-') as temp:
    temp = Path(temp)
    helper = temp / 'FrameProfile.java'
    source = (ROOT / 'scripts/diagnostics/FrameProfile.java').read_text()
    assert source.count('return System.currentTimeMillis();') == 1
    helper.write_text(source.replace('return System.currentTimeMillis();', 'return FrameProfileTest.now();'))
    classes = temp / 'classes'
    classes.mkdir()
    if os.environ.get('ECJ_JAR'):
        compiler = [JAVA, '-jar', os.environ['ECJ_JAR'], '-8', '-proc:none', '-nowarn']
    else:
        compiler = [os.environ.get('JAVAC', 'javac'), '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-classpath', str(API), '-d', str(classes), str(helper),
                              str(Path(__file__).with_name('FrameProfileTest.java'))], check=True)
    subprocess.run([JAVA, '-cp', os.pathsep.join(map(str, [classes, API])),
                    'code.Rendering.FrameProfileTest'], check=True)
