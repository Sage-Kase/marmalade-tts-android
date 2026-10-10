"""Tests for the speed stage's pure logic: python3 -m unittest discover scripts/release-smoke"""
import json
import tempfile
import unittest
import wave
from pathlib import Path

import speed

REPO = Path(__file__).resolve().parent.parent.parent
MODEL = speed.WarningModel(anchor_probe_rtf=0.2, costs={"fast": 1.0, "slow": 4.5}, threshold=0.8)


def take(stall_ms=0.0, first=400, synth=3000, duration=8000):
    return speed.Take(first_sound_ms=first, synth_ms=synth, duration_ms=duration,
                      stalls=[(1000.0, stall_ms)] if stall_ms else [])


def row(engine="fast", spd=1.0, takes=(), base=None):
    r = speed.SpeedRow(engine=engine, voice=f"{engine}:v", speed=spd, takes=list(takes))
    speed.judge(r, MODEL, 8000 if spd != 1.0 else None, base)
    return r


BASE = {"first_sound_ms": 400, "render_rtf": 0.4, "longest_stall_ms": 0}


class ReplayTest(unittest.TestCase):
    SR = 1000  # 2 bytes per ms of audio

    def test_chunks_that_arrive_in_time_never_stall(self):
        first, stalls = speed.replay([(100, 2000), (900, 2000), (1500, 2000)], self.SR)
        self.assertEqual(first, 100)
        self.assertEqual(stalls, [])

    def test_late_chunk_stalls_for_the_wait(self):
        # 1 s of audio from 100 ms runs out at 1100; the next chunk lands at 1600.
        _, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR)
        self.assertEqual(stalls, [(1000.0, 500.0)])

    def test_jitter_under_the_floor_is_not_a_stall(self):
        _, stalls = speed.replay([(0, 2000), (1000 + speed.STALL_MIN_MS - 1, 2000)], self.SR)
        self.assertEqual(stalls, [])

    def test_a_stall_moves_the_clock(self):
        # After waiting for chunk 2, chunk 3 is judged against the new clock.
        _, stalls = speed.replay([(0, 2000), (1500, 2000), (2400, 2000)], self.SR)
        self.assertEqual(stalls, [(1000.0, 500.0)])

    def test_cold_wait_covers_the_rest_of_playback(self):
        # Sound from 400 ms, 8 s long, held up 500 ms by a stall: playback ends at
        # 8900 ms; rendering finished at 3000, so 5.9 s more, plus the margin.
        self.assertEqual(speed.cold_wait_ms(400, 8000, [(1000.0, 500.0)], 3000), 5900 + speed.COLD_MARGIN_MS)

    def test_cold_wait_is_the_margin_when_rendering_outlasted_playback(self):
        self.assertEqual(speed.cold_wait_ms(400, 1000, [], 3000), speed.COLD_MARGIN_MS)

    def test_as_heard_inserts_silence_at_the_stall(self):
        with tempfile.TemporaryDirectory() as d:
            src, dest = Path(d) / "a.wav", Path(d) / "b.wav"
            with wave.open(str(src), "wb") as w:
                w.setnchannels(1), w.setsampwidth(2), w.setframerate(1000)
                w.writeframes(b"\1\0" * 2000)
            speed.write_as_heard(src, [(1000.0, 500.0)], dest)
            with wave.open(str(dest), "rb") as w:
                frames = w.readframes(w.getnframes())
            self.assertEqual(len(frames), 2 * 2500)
            self.assertEqual(frames[2000:3000], b"\0" * 1000)
            self.assertEqual(frames[3000:3002], b"\1\0")


class JudgeTest(unittest.TestCase):
    def test_keeps_up_passes(self):
        self.assertEqual(row(takes=[take()], base=BASE).result, "PASS")

    def test_stall_without_a_warning_fails(self):
        r = row(takes=[take(stall_ms=900)], base={**BASE, "longest_stall_ms": 900})
        self.assertEqual(r.result, "FAIL")
        self.assertIn("doesn't warn", r.reasons[0])

    def test_stall_where_the_app_warns_is_reported_not_failed(self):
        # slow: 0.2 x 4.5 = 0.9 > 0.8 at 1.0x
        r = row(engine="slow", takes=[take(stall_ms=900)], base={**BASE, "longest_stall_ms": 900})
        self.assertEqual(r.result, "WARNED")

    def test_short_seam_stall_still_keeps_up(self):
        self.assertEqual(row(takes=[take(stall_ms=speed.KEEPUP_MAX_STALL_MS - 1)],
                             base={**BASE, "longest_stall_ms": 200}).result, "PASS")

    def test_judged_on_the_best_take(self):
        self.assertEqual(row(takes=[take(stall_ms=900), take()], base=BASE).result, "PASS")

    def test_regression_against_the_baseline_fails_even_when_warned(self):
        r = row(engine="slow", takes=[take(first=2000)], base=BASE)
        self.assertEqual(r.result, "FAIL")
        self.assertIn("first sound regressed", r.reasons[0])

    def test_render_rtf_regression_fails(self):
        r = row(takes=[take(synth=5000)], base=BASE)  # 0.625 > 0.4 x 1.25 + 0.05
        self.assertIn("render RTF regressed", r.reasons[0])

    def test_missing_baseline_fails(self):
        self.assertEqual(row(takes=[take()]).result, "FAIL")

    def test_speed_must_shorten_the_audio(self):
        r = row(spd=2.0, takes=[take(duration=8000)], base=BASE)
        self.assertIn("speed not applied", r.reasons[0])
        self.assertEqual(row(spd=2.0, takes=[take(duration=4300, synth=1700)], base=BASE).result, "PASS")

    def test_no_takes_fails(self):
        self.assertEqual(row(takes=[]).result, "FAIL")


class AppConstantsTest(unittest.TestCase):
    """The gate reads the warning's constants from the Kotlin; check it still can."""

    def test_reads_the_warning_model(self):
        m = speed.read_warning_model(REPO)
        self.assertEqual(m.threshold, 0.8)
        self.assertTrue(0.1 < m.anchor_probe_rtf < 1)
        for engine in speed.SPEED_VOICES:
            self.assertIn(engine, m.costs)
        self.assertIn("vits-marmalade-v1", m.costs)

    def test_kitten_never_warns_on_the_reference_phone(self):
        m = speed.read_warning_model(REPO)
        self.assertFalse(m.warns("kitten-direct-v0_8", 2.0))

    def test_released_vits_packs(self):
        self.assertIn("en-jenny_dioco-medium", speed.released_vits_packs(REPO))
        self.assertNotIn("uk-lada-x_low", speed.released_vits_packs(REPO))


class BaselineTest(unittest.TestCase):
    def test_merge_replaces_only_measured_rows(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "b.json"
            path.write_text(json.dumps({"rows": {"other:v@1": {"first_sound_ms": 1}}}))
            r = speed.SpeedRow(engine="fast", voice="fast:v", speed=1.25, takes=[take(), take(first=300)])
            self.assertEqual(speed.merge_baseline(path, [r], "build", "2026-10-10", "Pixel 6a"), 1)
            rows = json.loads(path.read_text())["rows"]
            self.assertEqual(rows["other:v@1"], {"first_sound_ms": 1})
            self.assertEqual(rows["fast:v@1.25"]["first_sound_ms"], 300)

    def test_word_match(self):
        self.assertEqual(speed.word_match("The sun is out.", "the sun is out"), 1.0)
        self.assertEqual(speed.word_match("a b c d", "a b"), 0.5)


if __name__ == "__main__":
    unittest.main()
