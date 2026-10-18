#!/usr/bin/env python3
"""Test the production LCDUI line splitter without loading a bitmap font/phone UI."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
TEST=Path(__file__).resolve().parent
JAVA=os.environ.get('JAVA','java')
with tempfile.TemporaryDirectory(prefix='quantum-textview-') as temp:
    classes=Path(temp)/'classes';classes.mkdir()
    source=Path(os.environ.get('QUANTUM_TEXTVIEW_SOURCE',ROOT/'src/code/HUD/Base/TextView.java'))
    sources=[source,TEST/'TextViewTest.java']+sorted((TEST/'stubs').rglob('*.java'))
    if os.environ.get('ECJ_JAR'):
        compiler=[JAVA,'-jar',os.environ['ECJ_JAR'],'-8','-proc:none','-nowarn','-encoding','UTF-8']
    else:
        compiler=[os.environ.get('JAVAC','javac'),'-source','8','-target','8','-encoding','UTF-8']
    subprocess.run(compiler+['-d',str(classes)]+list(map(str,sources)),check=True)
    subprocess.run([JAVA,'-Xmx32m','-cp',str(classes),'TextViewTest']+sys.argv[1:],check=True,timeout=15)
