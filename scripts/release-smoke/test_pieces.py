"""Tests for the chunk-mode and piece lines: python3 -m unittest discover scripts/release-smoke"""
import unittest

import pieces

# Shaped like the app's lines (#54) as logcat -v epoch prints them.
CHUNK = ("1760109663.921  5084 10422 I RenderMode: kokoro-direct-v1_0 mode=chunk rtf=0.43 (predicted) "
         "speed=2.00 played=0.86 path=sliced")
STREAM_NONE = ("1760109663.921  5084 10422 I RenderMode: pocket-tts-en-v2026_04 mode=stream rtf=- (none) "
               "speed=1.00 played=- path=sliced")
WHOLE = ("1760109663.921  5084 10422 I RenderMode: kokoro-de-v1_0 mode=stream rtf=0,41 (measured) "
         "speed=1,25 played=0,51 path=whole")
PIECES = "1760109665.100  5084 10422 I MarmaladeTtsService: pieces: starts=0,52800,118080 samples=240000"
EMPTY = "1760109665.100  5084 10422 I MarmaladeTtsService: pieces: starts= samples=0"
REQUEST = ("1760109663.900  5084 10422 I MarmaladeTtsService: onSynthesizeText: asked=a voice=a engine=e "
           "speed=1.0 effectBlocks=0 chars=146")


class PiecesTest(unittest.TestCase):
    def test_reads_the_mode_line(self):
        (m,) = pieces.parse_modes(CHUNK)
        self.assertEqual(m, pieces.RenderMode("kokoro-direct-v1_0", "chunk", 0.43, "predicted", 2.0, "sliced"))
        self.assertEqual((m.label, m.chunked), ("chunk", True))

    def test_no_rtf_and_streaming(self):
        (m,) = pieces.parse_modes(STREAM_NONE)
        self.assertEqual((m.mode, m.rtf, m.source, m.label, m.chunked), ("stream", None, "none", "stream", False))

    def test_whole_pieces_and_a_decimal_comma(self):
        (m,) = pieces.parse_modes(WHOLE)
        self.assertEqual((m.rtf, m.speed, m.path, m.label, m.chunked), (0.41, 1.25, "whole", "whole", True))

    def test_reads_the_pieces_line(self):
        self.assertEqual(pieces.parse_pieces(PIECES), [pieces.Pieces((0, 52800, 118080), 240000)])

    def test_an_empty_render_has_no_starts(self):
        self.assertEqual(pieces.parse_pieces(EMPTY), [pieces.Pieces((), 0)])

    def test_latest_of_each_and_other_lines_ignored(self):
        mode, pcs = pieces.latest("\n".join([REQUEST, STREAM_NONE, CHUNK, EMPTY, PIECES]))
        self.assertEqual(mode.mode, "chunk")
        self.assertEqual(pcs.starts, (0, 52800, 118080))

    def test_missing_lines(self):
        self.assertEqual(pieces.latest(REQUEST), (None, None))
        self.assertEqual(pieces.latest(""), (None, None))
        self.assertEqual(pieces.latest(PIECES)[0], None)

    def test_another_tag_is_not_read(self):
        self.assertEqual(pieces.parse_modes(CHUNK.replace("RenderMode", "OtherTag")), [])
        self.assertEqual(pieces.parse_pieces(PIECES.replace("MarmaladeTtsService", "OtherTag")), [])


if __name__ == "__main__":
    unittest.main()
