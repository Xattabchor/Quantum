#!/usr/bin/env python3
"""Generate diagnostic source copies. Never edit renderer/game sources in place."""
from pathlib import Path
import re

PROFILE = 'code.Rendering.FrameProfile'


def region(statement, category):
    return ('{ ' + PROFILE + '.push(' + PROFILE + '.' + category + '); try {\n'
            + statement + '\n} finally { ' + PROFILE + '.pop(); } }')


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Profile instrumentation anchor changed: ' + old[:120])
    return text.replace(old, new, 1)


def wrap_method(text, header, category):
    if text.count(header) != 1:
        raise ValueError('Profile method anchor changed: ' + header)
    start = text.index('{', text.index(header))
    # Mask comments/literals without changing offsets, so braces in diagnostic
    # messages or commented-out code cannot change the selected method boundary.
    masked = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda m: ' ' * len(m.group()), text, flags=re.S)
    depth = 1
    end = start + 1
    while depth:
        if masked[end] == '{':
            depth += 1
        elif masked[end] == '}':
            depth -= 1
        end += 1
    body = text[start + 1:end - 1]
    return text[:start + 1] + '\n' + region(body, category) + '\n' + text[end - 1:]


def instrument_game(text):
    for marker in ('public final void start() {', 'private void stop() {', 'public final void openPause() {'):
        text = replace_once(text, marker, marker + '\n        ' + PROFILE + '.reset();')
    text = replace_once(text, '        finishDraw = false;',
                        '        ' + PROFILE + '.begin(levelNumber);\n        finishDraw = false;')
    start = text.index('            if(!House.l2dRoomRendering) {')
    finish = '            } else scene.render(g, renderFrom, player);'
    end = text.index(finish, start) + len(finish)
    text = text[:start] + region(text[start:end], 'PREP') + text[end:]
    start = text.index('            if(g3d.getRenderer()==DirectX7.M3G)\n                nativeWeather=')
    end = text.index('            else g3d.render();', start) + len('            else g3d.render();')
    text = text[:start] + region(text[start:end], 'SUBMIT') + text[end:]
    text = replace_once(text, '            scene.flush(g, 0, screenY);',
                        region('scene.flush(g, 0, screenY);', 'PRESENT'))
    # Keep all original HUD/weapon/weather/overlay calls in their original order.
    start = text.index('        try {\n' + region('scene.flush(g, 0, screenY);', 'PRESENT'))
    end = text.index('        run(begintime);', start)
    body = text[start:end] + '\n' + 'if(g3d.getRenderer()==DirectX7.M3G) ' + PROFILE + '.draw(g,screenY+18,g3d.getWidth());\n'
    text = text[:start] + region(body, 'HUD') + '\n' + text[end:]
    text = replace_once(text, '        run(begintime);', region('run(begintime);', 'LOGIC'))
    text = replace_once(text, '        finishDraw = true;\n        QFPS.frame();',
                        '        ' + PROFILE + '.end();\n        finishDraw = true;\n        QFPS.frame();')
    for call in ('Thread.sleep(customMessageEndTime);', 'Thread.sleep(sleepTime);'):
        text = replace_once(text, call, region(call, 'WAIT'))
    for call, category in ((
        'scene.update(player, this);', 'UPDATE'), (
        'scene.activateObject(false, player, height / 2 - scene.getG3D().getHeight() / 2, this);', 'INTERACT')):
        text = replace_once(text, call, region(call, category))
    return text


def instrument_direct(text):
    text = wrap_method(text, 'public void drawSoftwareObject(', 'CPU')
    text = wrap_method(text, 'public void transformAndProjectVertices(Mesh ms, Matrix matrix)', 'VERTEX')
    text = wrap_method(text, 'public void transformAndProjectVertices(Vertex[] vertices, Matrix matrix)', 'VERTEX')
    for call, category in (
        ('frameImage.getRGB(display,0,width,0,0,width,height);', 'READ'),
        ('System.arraycopy(display, 0, frameBackup, 0, display.length);', 'BACKUP'),
        ('System.arraycopy(frameBackup, 0, display, 0, display.length);', 'BACKUP'),
        ('buffer.sort(0, buffer.getSize() - 1);', 'SORT'),
        ('buffer.sort(0,buffer.getSize()-1);', 'SORT'),
    ):
        text = replace_once(text, call, region(call, category))
    # Legacy and required gameplay each own an ordered background queue.
    call = 'backgroundBuffer.sort(0,backgroundBuffer.getSize()-1);'
    if text.count(call) != 2:
        raise ValueError('background sort anchors changed')
    text = text.replace(call, region(call, 'SORT'))
    return text


def instrument_native(text):
    text = wrap_method(text, 'void renderNative(', 'API')
    text = wrap_method(text, 'Image2D uploadImage(', 'API')
    for call, category in (
        ('surface.getGraphics().drawRGB(g.display, 0, g.width, 0, 0, g.width, g.height, false);', 'UPLOAD'),
        ('graphics.bindTarget(surface.getGraphics(), false, 0);', 'API'),
        ('graphics.bindTarget(target,false,0);', 'API'),
        ('graphics.clear(flashBackground);', 'API'),
        ('result.getRGB(g.display,0,g.width,0,0,g.width,g.height);', 'READ'),
    ):
        text = replace_once(text, call, region(call, category))
    # World finish, exceptional disposal and Canvas foreground release.
    call = 'graphics.releaseTarget();'
    if text.count(call) != 3:
        raise ValueError('releaseTarget anchors changed')
    text = text.replace(call, region(call, 'API'))
    return text


def prepare_sources(root, sources, output):
    root, output = Path(root), Path(output)
    transforms = {
        root / 'src/code/Gameplay/GameScreen.java': instrument_game,
        root / 'src/code/Rendering/DirectX7.java': instrument_direct,
        root / 'src/code/Rendering/M3GSceneRenderer.java': instrument_native,
        root / 'src/code/Rendering/ProjectedGeometry.java': lambda text: wrap_method(
            wrap_method(text, 'public static float packFog(', 'PACK'), 'public static float pack(', 'PACK'),
    }
    result = []
    seen = set()
    for source in sources:
        source = Path(source)
        if source in transforms:
            path = output / source.relative_to(root / 'src')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(transforms[source](source.read_text()), encoding='utf-8')
            result.append(path)
            seen.add(source)
        else:
            result.append(source)
    if seen != set(transforms):
        raise ValueError('Missing game/renderer source for profile instrumentation')
    helper = output / 'code/Rendering/FrameProfile.java'
    helper.parent.mkdir(parents=True, exist_ok=True)
    helper.write_text((root / 'scripts/diagnostics/FrameProfile.java').read_text(), encoding='utf-8')
    result.append(helper)
    return result
