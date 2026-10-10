"""Tests for the request-routing check: python3 -m unittest discover scripts/release-smoke"""
import unittest

import routing

# What the rc logged for the gate's old Kitten row (2026-10-10), reshaped to #50's line.
POCKET_FOR_BELLA = ("1760109663.921  5084 10422 I MarmaladeTtsService: onSynthesizeText: "
                    "asked=kitten-direct-v0_8:Bella voice=pocket-tts-en-v2026_04:alba "
                    "engine=pocket-tts-en-v2026_04 speed=1.0 effectBlocks=0 chars=146")
LEO = ("1760109700.000  5084 10422 I MarmaladeTtsService: onSynthesizeText: "
       "asked=kitten-direct-v0_8:Leo voice=kitten-direct-v0_8:Leo engine=kitten-direct-v0_8 "
       "speed=1.5 effectBlocks=0 chars=146")


class RoutingTest(unittest.TestCase):
    def test_parse_reads_every_field(self):
        (r,) = routing.parse(POCKET_FOR_BELLA)
        self.assertEqual((r.asked, r.voice, r.engine, r.speed, r.effects),
                         ("kitten-direct-v0_8:Bella", "pocket-tts-en-v2026_04:alba",
                          "pocket-tts-en-v2026_04", 1.0, 0))

    def test_the_asked_voice_at_the_asked_rate_passes(self):
        self.assertIsNone(routing.mismatch("kitten-direct-v0_8:Leo", 1.5, routing.parse(LEO)))

    def test_another_voice_fails(self):
        why = routing.mismatch("kitten-direct-v0_8:Bella", 1.0, routing.parse(POCKET_FOR_BELLA))
        self.assertIn("spoken by pocket-tts-en-v2026_04:alba", why)

    def test_an_alias_speed_fails(self):
        why = routing.mismatch("kitten-direct-v0_8:Leo", 1.0, routing.parse(LEO))
        self.assertIn("spoken at 1.5x, not 1x", why)

    def test_an_alias_effect_fails(self):
        why = routing.mismatch("kitten-direct-v0_8:Leo", 1.5,
                               routing.parse(LEO.replace("effectBlocks=0", "effectBlocks=2")))
        self.assertIn("effect", why)

    def test_no_line_fails(self):
        self.assertIn("no request line", routing.mismatch("kitten-direct-v0_8:Leo", 1.0, []))


if __name__ == "__main__":
    unittest.main()
