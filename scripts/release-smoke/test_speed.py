"""Tests for the speed stage's pure logic: python3 -m unittest discover scripts/release-smoke"""
import json
import tempfile
import unittest
import wave
from pathlib import Path

import speed

REPO = Path(__file__).resolve().parent.parent.parent
MODEL = speed.WarningModel(anchor_probe_rtf=0.2, costs={"fast": 1.0, "slow": 4.5}, threshold=0.8)


def take(stall_ms=0.0, first=400, synth=3000, duration=8000, wait_ms=0.0, mode=None, path=None, pieces=None):
    """[pieces]: the app's piece starts; None = no pieces line (a build from before #54)."""
    return speed.Take(first_sound_ms=first, synth_ms=synth, duration_ms=duration,
                      stalls=[(1000.0, stall_ms)] if stall_ms else [],
                      waits=[(2000.0, wait_ms)] if wait_ms else [],
                      piece_starts=pieces, mode=mode, path=path)


def chunk(stall_ms=0.0, wait_ms=0.0, **kw):
    return take(stall_ms, wait_ms=wait_ms, mode="chunk", path="sliced", pieces=[0, 2000], **kw)


def stream(stall_ms=0.0, wait_ms=0.0, **kw):
    return take(stall_ms, wait_ms=wait_ms, mode="stream", path="sliced", pieces=[0, 2000], **kw)


def row(engine="fast", spd=1.0, takes=(), base=None):
    r = speed.SpeedRow(engine=engine, voice=f"{engine}:v", speed=spd, takes=list(takes))
    speed.judge(r, MODEL, 8000 if spd != 1.0 else None, base)
    return r


BASE = {"first_sound_ms": 400, "render_rtf": 0.4, "longest_stall_ms": 0}  # a pre-#54 row
NEW_BASE = {**BASE, "longest_wait_ms": 0}


class ReplayTest(unittest.TestCase):
    SR = 1000  # 2 bytes per ms of audio

    def test_chunks_that_arrive_in_time_never_stall(self):
        first, waits, stalls = speed.replay([(100, 2000), (900, 2000), (1500, 2000)], self.SR)
        self.assertEqual(first, 100)
        self.assertEqual((waits, stalls), ([], []))

    def test_late_chunk_stalls_for_the_wait(self):
        # 1 s of audio from 100 ms runs out at 1100; the next chunk lands at 1600.
        _, _, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR)
        self.assertEqual(stalls, [(1000.0, 500.0)])

    def test_jitter_under_the_floor_is_not_a_stall(self):
        _, waits, stalls = speed.replay([(0, 2000), (1000 + speed.STALL_MIN_MS - 1, 2000)], self.SR, [0, 1000])
        self.assertEqual((waits, stalls), ([], []))

    def test_a_stall_moves_the_clock(self):
        # After waiting for chunk 2, chunk 3 is judged against the new clock.
        _, _, stalls = speed.replay([(0, 2000), (1500, 2000), (2400, 2000)], self.SR)
        self.assertEqual(stalls, [(1000.0, 500.0)])

    def test_a_gap_where_a_piece_starts_is_a_wait(self):
        # Piece 2 starts at sample 1000 = byte 2000, the second chunk.
        _, waits, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR, [0, 1000])
        self.assertEqual((waits, stalls), ([(1000.0, 500.0)], []))

    def test_a_gap_inside_a_piece_is_a_stall(self):
        # One piece split over three chunks; then piece 2 at byte 6000 arrives late too.
        chunks = [(0, 2000), (1500, 2000), (2400, 2000), (3900, 2000)]
        _, waits, stalls = speed.replay(chunks, self.SR, [0, 3000])
        self.assertEqual(stalls, [(1000.0, 500.0)])
        self.assertEqual(waits, [(3000.0, 400.0)])

    def test_a_piece_start_mid_chunk_is_no_boundary(self):
        # Offsets are compared exactly: a start that isn't a chunk's first byte proves nothing.
        _, waits, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR, [0, 1001])
        self.assertEqual((waits, stalls), ([], [(1000.0, 500.0)]))

    def test_without_piece_starts_every_gap_is_a_stall(self):
        _, waits, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR, None)
        self.assertEqual((waits, stalls), ([], [(1000.0, 500.0)]))
        _, waits, stalls = speed.replay([(100, 2000), (1600, 2000)], self.SR, [])  # empty render
        self.assertEqual((waits, stalls), ([], [(1000.0, 500.0)]))

    def test_cold_wait_covers_the_rest_of_playback(self):
        # Sound from 400 ms, 8 s long, held up 500 ms by a stall: playback ends at
        # 8900 ms; rendering finished at 3000, so 5.9 s more, plus the margin.
        self.assertEqual(speed.cold_wait_ms(400, 8000, [], [(1000.0, 500.0)], 3000), 5900 + speed.COLD_MARGIN_MS)

    def test_cold_wait_counts_waits_and_stalls(self):
        # Held up 300 ms between pieces and 200 ms inside one: the same 8900 ms end.
        self.assertEqual(speed.cold_wait_ms(400, 8000, [(4000.0, 300.0)], [(1000.0, 200.0)], 3000),
                         5900 + speed.COLD_MARGIN_MS)

    def test_cold_wait_is_the_margin_when_rendering_outlasted_playback(self):
        self.assertEqual(speed.cold_wait_ms(400, 1000, [], [], 3000), speed.COLD_MARGIN_MS)

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

    def test_unknown_piece_boundaries_are_noted(self):
        r = row(takes=[take()], base=BASE)
        self.assertEqual(r.result, "PASS")
        self.assertIn("piece boundaries unknown", r.notes[0])
        self.assertFalse(row(takes=[stream()], base=NEW_BASE).notes)


class ChunkModeTest(unittest.TestCase):
    """#54: in chunk mode playback may wait between pieces, never stall inside one."""

    def test_a_stall_inside_a_piece_in_chunk_mode_fails(self):
        r = row(takes=[chunk(stall_ms=180)], base={**NEW_BASE, "longest_stall_ms": 180})
        self.assertEqual(r.result, "FAIL")
        self.assertEqual(r.reasons, ["stutters 180 ms inside a sentence or clause in chunk mode"])

    def test_even_where_the_app_warns(self):
        r = row(engine="slow", takes=[chunk(stall_ms=900)], base={**NEW_BASE, "longest_stall_ms": 900})
        self.assertEqual(r.result, "FAIL")
        self.assertIn("in chunk mode", r.reasons[0])

    def test_whole_pieces_count_as_chunk_mode(self):
        t = take(stall_ms=120, mode="stream", path="whole", pieces=[0, 2000])
        r = row(takes=[t], base={**NEW_BASE, "longest_stall_ms": 120})
        self.assertEqual(r.reasons, ["stutters 120 ms inside a sentence or clause with whole pieces"])
        self.assertEqual(r.mode_label, "whole")

    def test_chunk_mode_waiting_between_pieces_passes(self):
        r = row(takes=[chunk(wait_ms=250)], base={**NEW_BASE, "longest_wait_ms": 250})
        self.assertEqual(r.result, "PASS")

    def test_judged_on_the_best_take(self):
        r = row(takes=[chunk(stall_ms=180), chunk()], base=NEW_BASE)
        self.assertEqual(r.result, "PASS")

    def test_a_short_stall_while_streaming_passes(self):
        r = row(takes=[stream(stall_ms=180)], base={**NEW_BASE, "longest_stall_ms": 180})
        self.assertEqual(r.result, "PASS")

    def test_unknown_boundaries_never_trip_the_chunk_rule(self):
        t = take(stall_ms=180, mode="chunk", path="sliced", pieces=None)
        self.assertEqual(row(takes=[t], base={**BASE, "longest_stall_ms": 180}).result, "PASS")

    def test_streaming_stall_where_the_app_warns_is_warned(self):
        r = row(engine="slow", takes=[stream(stall_ms=400)], base={**NEW_BASE, "longest_stall_ms": 400})
        self.assertEqual(r.result, "WARNED")
        self.assertEqual(r.notes, ["stalls 400 ms inside a piece (stream mode); the app warns at this speed"])

    def test_long_waits_where_the_app_warns_are_warned(self):
        r = row(engine="slow", takes=[chunk(wait_ms=600)], base={**NEW_BASE, "longest_wait_ms": 600})
        self.assertEqual(r.result, "WARNED")
        self.assertIn("waits 600 ms between pieces (chunk mode)", r.notes[0])

    def test_long_waits_without_a_warning_fail(self):
        r = row(takes=[chunk(wait_ms=600)], base={**NEW_BASE, "longest_wait_ms": 600})
        self.assertEqual(r.result, "FAIL")
        self.assertEqual(r.reasons, ["waits 600 ms between pieces at a speed the app doesn't warn about"])

    def test_mode_label_shows_a_change_between_takes(self):
        r = speed.SpeedRow(engine="fast", voice="fast:v", speed=1.0, takes=[stream(), chunk(), chunk()])
        self.assertEqual(r.mode_label, "stream/chunk")
        self.assertEqual(speed.SpeedRow(engine="v", voice="v:v", speed=1.0, takes=[take()]).mode_label, "-")


class OutputTest(unittest.TestCase):
    def test_table_shows_mode_waits_and_stalls(self):
        r = row(engine="slow", takes=[chunk(wait_ms=600)], base={**NEW_BASE, "longest_wait_ms": 600})
        cells = dict(zip(speed.TABLE_COLS, speed.table([r])[0]))
        self.assertEqual((cells["mode"], cells["longest wait (between pieces)"],
                          cells["longest stall (inside a piece)"]), ("chunk", "600 ms", "none"))

    def test_lab_renders_both_kinds(self):
        r = row(engine="slow", takes=[chunk(wait_ms=600)], base={**NEW_BASE, "longest_wait_ms": 600})
        r.takes[0].wav, r.takes[0].rtf, r.takes[0].source = "speed/x.wav", 0.9, "predicted"
        with tempfile.TemporaryDirectory() as d:
            html = speed.write_lab(Path(d), [r], {"build": "b", "date": "d", "phone": "p", "screen": "s",
                                                   "takes": 1, "summary": "s", "threshold": 0.8},
                                   {"en": "text", "de": "text"}).read_text()
        self.assertIn("chunk <small>RTF 0.90 (predicted)</small>", html)
        self.assertIn("600 ms at 2.0 s", html)
        self.assertIn("x-as-heard.wav", html)


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
    def test_legacy_row_compares_the_longest_gap_of_either_kind(self):
        # A pre-#54 longest_stall_ms recorded every gap: a wait now counts against it too.
        ok = row(engine="slow", takes=[stream(wait_ms=400)], base={**BASE, "longest_stall_ms": 400})
        self.assertEqual(ok.result, "WARNED")
        bad = row(engine="slow", takes=[stream(wait_ms=900)], base={**BASE, "longest_stall_ms": 400})
        self.assertEqual(bad.reasons, ["longest stall regressed: 900 ms vs baseline 400 ms"])

    def test_new_row_compares_waits_and_stalls_apart(self):
        # 900 ms of waits against a 400 ms stall baseline is fine when the wait baseline allows it.
        base = {**BASE, "longest_stall_ms": 400, "longest_wait_ms": 900}
        self.assertEqual(row(engine="slow", takes=[stream(wait_ms=900)], base=base).result, "WARNED")
        r = row(engine="slow", takes=[stream(stall_ms=900)], base=base)
        self.assertEqual(r.reasons, ["longest stall (inside a piece) regressed: 900 ms vs baseline 400 ms"])
        r = row(engine="slow", takes=[stream(wait_ms=900)], base={**NEW_BASE, "longest_stall_ms": 900})
        self.assertEqual(r.reasons, ["longest wait (between pieces) regressed: 900 ms vs baseline 0 ms"])

    def test_entry_writes_waits_and_stalls(self):
        r = speed.SpeedRow(engine="fast", voice="fast:v", speed=1.0,
                           takes=[stream(stall_ms=120, wait_ms=500), stream(stall_ms=80, wait_ms=700)])
        e = speed.baseline_entry(r, "build", "2026-10-10")
        self.assertEqual((e["longest_stall_ms"], e["longest_wait_ms"]), (80, 500))

    def test_entry_without_piece_boundaries_keeps_the_legacy_form(self):
        r = speed.SpeedRow(engine="fast", voice="fast:v", speed=1.0, takes=[take(stall_ms=120, wait_ms=500)])
        e = speed.baseline_entry(r, "build", "2026-10-10")
        self.assertEqual(e["longest_stall_ms"], 500)
        self.assertNotIn("longest_wait_ms", e)

    def test_the_checked_in_baseline_still_loads(self):
        rows = speed.load_baseline(Path(__file__).resolve().parent / "speed-baseline.json")["rows"]
        self.assertTrue(rows)
        for b in rows.values():
            r = row(takes=[stream()], base=b)
            self.assertFalse([x for x in r.reasons if "longest" in x])

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
