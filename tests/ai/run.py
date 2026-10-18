#!/usr/bin/env python3
"""Exact AI references + work counters, NOT phone FPS or real AI wall time.
Build CLDC/API first. All instrumentation/LegacyNPC sources are temporary.
"""
import ast
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
JAVA = os.environ.get('JAVA', 'java')
APP = ROOT / 'build/cldc/Quantum-unverified.jar'
API = ROOT / 'build/cldc/api.jar'


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('AI instrumentation anchor changed: ' + old)
    return text.replace(old, new)


# Reuse only the existing non-rendering desktop boot stubs, without executing
# the M3G runner. No fake AI/physics implementations enter the fixture.
tree = ast.parse((ROOT / 'tests/m3g/run.py').read_text())
stubs = next(ast.literal_eval(node.value) for node in tree.body
             if isinstance(node, ast.Assign) and any(
                 isinstance(t, ast.Name) and t.id == 'STUBS' for t in node.targets))
with tempfile.TemporaryDirectory(prefix='quantum-ai-') as directory:
    temp = Path(directory)
    classes = temp / 'classes'
    classes.mkdir()
    sources = []

    def save(name, text):
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(path)

    for name in ('javax/microedition/media/Manager.java',
                 'javax/microedition/lcdui/Image.java', 'javax/microedition/lcdui/Graphics.java'):
        save(name, stubs[name])
    sources += sorted(TEST.glob('*.java'))
    npc = (ROOT / 'src/code/AI/NPC.java').read_text()
    legacy = once(npc, 'public final class NPC extends Bot', 'public final class LegacyNPC extends Bot')
    legacy = once(legacy, 'public NPC(', 'public LegacyNPC(')
    start = legacy.index('    protected final void action(Scene scene) {')
    end = legacy.index('    protected final void drop(Scene scene)', start)
    legacy = legacy[:start] + (TEST / 'reference-action.txt').read_text() + legacy[end:]
    legacy = once(legacy, '    protected final void action(Scene scene) {', '''
    protected GameObject findBot(Vector objs, GameObject ignore, int[] fractions) {
        return AIReference.findBot(this, objs, ignore, fractions);
    }
    protected final void action(Scene scene) {''')
    save('code/AI/LegacyNPC.java', legacy.replace('System.currentTimeMillis()', 'AIStats.now()'))
    # Freeze only decision/damage clocks; do not edit src/ or fake game logic.
    npc = npc.replace('System.currentTimeMillis()', 'AIStats.now()')
    npc = once(npc, 'private void findTargets(Vector objs) {',
               'private void findTargets(Vector objs) {AIStats.scans++;')
    save('code/AI/NPC.java', npc)
    bot = (ROOT / 'src/code/AI/Bot.java').read_text()
    bot = once(bot, 'protected GameObject findBot(Vector objs, GameObject ignore, int[] fractions) {',
               'protected GameObject findBot(Vector objs, GameObject ignore, int[] fractions) {AIStats.scans++;')
    bot = once(bot, 'return !ray.isCollision();', 'return AIStats.visibility(!ray.isCollision());')
    save('code/AI/Bot.java', bot.replace('Math.sqrt(', 'AIStats.sqrt('))
    vector = (ROOT / 'src/code/Math/Vector3D.java').read_text()
    for header in ('public Vector3D() {', 'public Vector3D(int x, int y, int z) {', 'public Vector3D(int[] ps) {'):
        vector = once(vector, header, header + 'code.AI.AIStats.vectors++;')
    vector = once(vector, 'public final void setLength(int len) {',
                  'public final void setLength(int len) {code.AI.AIStats.normalizations++;')
    save('code/Math/Vector3D.java', vector.replace('Math.sqrt(', 'code.AI.AIStats.sqrt('))
    character = (ROOT / 'src/code/Gameplay/Map/Character.java').read_text()
    character = once(character, 'public final long distance(Character character) {',
                     'public final long distance(Character character) {code.AI.AIStats.distances++;')
    save('code/Gameplay/Map/Character.java', character)
    house = (ROOT / 'src/code/Gameplay/Map/House.java').read_text()
    house = once(house, 'public final void rayCast(int part, Ray ray, boolean rayIsFromWeapons) {',
                 'public final void rayCast(int part, Ray ray, boolean rayIsFromWeapons) {code.AI.AIStats.rays++;')
    save('code/Gameplay/Map/House.java', house)
    scene = (ROOT / 'src/code/Gameplay/Map/Scene.java').read_text()
    scene = once(scene, 'public int getNext(int start, int finish) {',
                 'public int getNext(int start, int finish) {code.AI.AIStats.routes++;')
    save('code/Gameplay/Map/Scene.java', scene)
    if os.environ.get('ECJ_JAR'):
        compiler = [JAVA, '-jar', os.environ['ECJ_JAR'], '-8', '-proc:none', '-nowarn']
    else:
        compiler = [os.environ.get('JAVAC', 'javac'), '-source', '8', '-target', '8']
    subprocess.run(compiler + ['-classpath', os.pathsep.join(map(str, (APP, API))),
                              '-d', str(classes)] + list(map(str, sources)), check=True)
    subprocess.run([JAVA, '-cp', os.pathsep.join(map(str, (classes, APP, API))),
                    'code.AI.AITest'], check=True)
