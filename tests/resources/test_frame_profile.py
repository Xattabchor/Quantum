#!/usr/bin/env python3
"""Offline tests of diagnostic source generation; never modify src/."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from frame_profile import prepare_sources, replace_once, wrap_method


class FrameProfileSourcesTest(unittest.TestCase):
    def test_temporary_copies_preserve_original_sources_and_calls(self):
        sources = [ROOT / 'src' / p for p in (
            'code/Gameplay/GameScreen.java', 'code/Rendering/DirectX7.java',
            'code/Rendering/M3GSceneRenderer.java', 'code/Rendering/ProjectedGeometry.java')]
        original = {p: p.read_bytes() for p in sources}
        with tempfile.TemporaryDirectory() as temp:
            copies = prepare_sources(ROOT, sources, Path(temp))
            self.assertEqual(len(copies), 5)
            for source, generated in zip(sources, copies):
                self.assertEqual(source.read_bytes(), original[source])
                self.assertNotEqual(source, generated)
                self.assertIn('FrameProfile', generated.read_text())
                self.assertNotIn('FrameProfile', source.read_text())
                for call in ('Thread.sleep(', 'scene.render(', 'scene.update(',
                             'graphics.render(', 'graphics.bindTarget(', 'graphics.releaseTarget(',
                             '.getRGB(', 'System.arraycopy(', 'scene.flush('):
                    self.assertEqual(source.read_text().count(call), generated.read_text().count(call), call)
            game = copies[0].read_text()
            self.assertEqual(game.count('FrameProfile.begin(levelNumber)'), 1)
            self.assertEqual(game.count('FrameProfile.end()'), 1)
            self.assertEqual(game.count('FrameProfile.WAIT'), 2)
            self.assertIn('FrameProfile.INTERACT', game)
            self.assertIn('FrameProfile.UPDATE', game)
            self.assertIn('FrameProfile.VERTEX', copies[1].read_text())
            self.assertIn('FrameProfile.SORT', copies[1].read_text())
            self.assertIn('FrameProfile.PACK', copies[3].read_text())

    def test_fail_closed_if_anchor_changes(self):
        with self.assertRaises(ValueError):
            replace_once('old old', 'old', 'new')
        with self.assertRaises(ValueError):
            replace_once('something else', 'old', 'new')
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                prepare_sources(ROOT, [], temp)

    def test_method_boundary_handles_literals_comments_nested_returns(self):
        body = '''public int target() {
            String text="} escaped \\\" {";
            char brace='}'; // }
            /* { */
            try { return 1; } finally { cleanup(); }
        }
        public int untouched() {return 2;}
        '''
        result = wrap_method(body, 'public int target()', 'API')
        self.assertIn('public int untouched() {return 2;}', result)
        self.assertEqual(result.count('FrameProfile.push('), 1)
        self.assertEqual(result.count('FrameProfile.pop()'), 1)
        self.assertIn('finally { cleanup(); }', result)
        self.assertIn('finally { code.Rendering.FrameProfile.pop(); } }\n}', result)


if __name__ == '__main__':
    unittest.main()
