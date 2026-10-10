"""Tests for the installed-bundle check: python3 -m unittest discover scripts/release-smoke"""
import unittest

import bundles

LOG = """\
1760100000.123  4242  4300 I EngineBundle: kitten-direct-v0_8 installed=v22 catalog=v22 current sha=591e1e163f78
1760100000.124  4242  4300 I EngineBundle: kokoro-direct-v1_0 installed=v23 catalog=v26 OUTDATED sha=abababababab
1760100000.125  4242  4300 I EngineBundle: pocket-tts-en-v2026_04 installed=? catalog=v27 OUTDATED sha=?
1760100000.126  4242  4300 I EngineBundle: vits-marmalade-v1:en_US-jenny installed=v24 catalog=v24 current sha=0123456789ab
1760100000.127  4242  4300 I OtherTag: kokoro-de-v1_0 installed=v25 catalog=v25 current sha=0123456789ab
"""


class BundleCheckTest(unittest.TestCase):
    def test_parses_the_apps_lines_only(self):
        got = bundles.parse(LOG)
        self.assertEqual(set(got), {"kitten-direct-v0_8", "kokoro-direct-v1_0", "pocket-tts-en-v2026_04",
                                    "vits-marmalade-v1:en_US-jenny"})
        self.assertEqual(got["kokoro-direct-v1_0"], bundles.Bundle("kokoro-direct-v1_0", "v23", "v26", False))
        self.assertTrue(got["kitten-direct-v0_8"].current)

    def test_a_later_start_wins(self):
        got = bundles.parse(LOG + "1760100100.000  5000  5001 I EngineBundle: kokoro-direct-v1_0 "
                                  "installed=v26 catalog=v26 current sha=d7e2aff99226\n")
        self.assertTrue(got["kokoro-direct-v1_0"].current)

    def test_flags_each_engine_under_test_that_is_off_the_catalog(self):
        got = bundles.parse(LOG)
        wanted = ["kitten-direct-v0_8", "kokoro-direct-v1_0", "pocket-tts-en-v2026_04", "kokoro-de-v1_0"]
        self.assertEqual(bundles.problems(got, wanted), [
            "kokoro-direct-v1_0: installed from the v23 download, the catalog offers v26",
            "pocket-tts-en-v2026_04: installed from a download with no install record, the catalog offers v27",
        ])  # kokoro-de isn't on the phone: the stages report that, not this check
        self.assertEqual(bundles.problems(got, ["kitten-direct-v0_8"]), [])

    def test_the_table_shows_what_is_installed(self):
        got = bundles.parse(LOG)
        self.assertEqual(bundles.label(got, "kitten-direct-v0_8"), "v22")
        self.assertEqual(bundles.label(got, "kokoro-direct-v1_0"), "v23 (catalog v26)")
        self.assertEqual(bundles.label(got, "kokoro-de-v1_0"), "-")

    def test_a_vits_voice_speaks_from_its_pack(self):
        vits = "vits-marmalade-v1"
        self.assertEqual(bundles.key(vits, "vits-marmalade-v1:en_US-jenny#0", vits), "vits-marmalade-v1:en_US-jenny")
        self.assertEqual(bundles.key("kokoro-direct-v1_0", "kokoro-direct-v1_0:af_heart", vits), "kokoro-direct-v1_0")
        self.assertEqual(bundles.key(vits, None, vits), vits)


if __name__ == "__main__":
    unittest.main()
