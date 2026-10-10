# Pocket TTS model — third-party license notice

The Pocket TTS model is **not bundled in the APK**. It is downloaded
on-demand from `marmalade-tts-android-engines` (release `v27`,
`pocket-tts-en-v2026_09-mixed.tar.bz2`; v21 before it) into
`${filesDir}/engines/pocket-tts-en-v2026_04/` when the user opts in via
the onboarding wizard or Settings → Engines.

Three upstream projects contribute to the downloaded bundle. **None of
them are GPL** — this is the only engine in the catalog without GPL
contamination from the phonemizer path.

## 1. Acoustic model — kyutai/pocket-tts

The model has two licences: Kyutai's **code** is MIT, and the trained
**weights** are CC-BY-4.0. The bundle's ONNX graphs are the weights,
converted, so CC-BY-4.0 is the licence that governs them.

### Weights (CC-BY-4.0)

- **Files:** `flow_lm_main_int8_dyncache.onnx`, `flow_lm_flow.onnx`,
  `mimi_encoder.onnx`, `mimi_decoder_dyncache.onnx`,
  `text_conditioner.onnx`, `bos_before_voice.npy`
- **Model version:** `english_2026-09` (v21 carried `english_2026-04`).
- **Upstream:** https://huggingface.co/kyutai/pocket-tts (model card
  licence: `cc-by-4.0`, checked 2026-10-09). The 2026-09 weights were
  taken from Kyutai's ungated copy,
  https://huggingface.co/kyutai/pocket-tts-without-voice-cloning (same
  licence; it zeroes only the Mimi encoder). The Mimi encoder comes from
  the 2026-04 export at https://huggingface.co/KevinAHM/pocket-tts-onnx
  (`cc-by-4.0`, ungated; byte-identical to v21's `mimi_encoder.onnx`): the
  codec is the same in both versions, every Mimi tensor outside the
  encoder is bit-identical, so only the speaker projection was replaced.
- **Kyutai's prohibited-use statement:** both Kyutai repositories ask
  that the model not be used for voice impersonation or cloning without
  explicit and lawful consent, for deception, or for unlawful or harmful
  content. The licence itself is CC-BY-4.0; the app has no cloning
  feature enabled (see "Voice cloning" below).
- **License:** **CC-BY-4.0** — full text in
  [`full-texts/CC-BY-4.0.txt`](full-texts/CC-BY-4.0.txt).
- **Attribution:** "Pocket TTS" by Kyutai,
  https://huggingface.co/kyutai/pocket-tts, licensed CC-BY-4.0.
- **Changes made:** converted from the released checkpoint to five ONNX
  graphs (via the KevinAHM/pocket-tts-onnx export, below); the main
  transformer (`flow_lm_main`) is quantized to 8-bit integers, the other
  graphs stay 32-bit float; the 2026-09 speaker projection is placed in
  the 2026-04 Mimi encoder graph; the main transformer's and the
  decoder's cache length is made a variable dimension (`*_dyncache`), with
  the weights unchanged.

### Code (MIT)

- **Upstream:** https://github.com/kyutai-labs/pocket-tts
- **License:** **MIT License** (not Apache-2.0). It covers the
  inference design the ONNX graphs were traced from and the app's
  Kotlin Pocket pipeline follows; `bundle.json` (the graphs' state
  manifest) comes from the export tooling.
- **Notice:** Copyright (c) Kyutai. Released under the MIT License.

> The **voice prompts** carry their own per-voice data licenses, below;
> they govern the `voices/*.wav` files.

## 2. Predefined voices — per-voice data licenses

Pocket addresses voices by a reference WAV in `voices/<name>.wav`. Each
predefined voice's audio comes from a different source dataset, and the
**license is determined by that source** (authoritative mapping for the
upstream voices: Kyutai's own `pocket_tts/utils/utils.py`; folder→license
per https://huggingface.co/kyutai/tts-voices).

We ship **only commercial-safe voices**: 10 in v27. The upstream `cosette`
(Expresso) and `jean` (EARS) voices are **CC-BY-NC-4.0 (non-commercial
only)** and are **deliberately excluded** so the store build distributes
no non-commercial voice data. `marius` (`voice-donations/Selfie.wav`, CC0)
was in v21 but not offered by the app since 2026-10-09, and v27 drops it.

| Voice | Source | Dataset | License |
|---|---|---|---|
| `alba` | `alba-mackenna/casual.wav` | Alba Mackenna | **CC-BY-4.0** |
| `azelma` | `vctk/p303_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `eponine` | `vctk/p262_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `fantine` | `vctk/p244_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `javert` | `voice-donations/Butter.wav` | Unmute Voice Donation | **CC0** |
| `mary` | `voice-donations/3973.wav` | Unmute Voice Donation | **CC0** |
| `rita` | `voice-donations/spanish-limaperu.wav` | Unmute Voice Donation | **CC0** |
| `stephen` | `voice-donations/thepolishdane.wav` | Unmute Voice Donation | **CC0** |
| `ramone` | `voice-donations/floyd2026.wav` | Unmute Voice Donation | **CC0** |
| `jenny` | 26 s cut from jenny-tts-dataset recordings | Jenny (Dioco) | **Custom attribution** (below) |

The first five are Kyutai's predefined voices (`utils.py`). The other five
were added in v27 (issue #38):

- **`mary`, `rita`, `stephen`, `ramone`** are donors' clips from
  `kyutai/tts-voices` `voice-donations/` (README: CC0), shipped unchanged
  (byte-identical to the published files, checked against Hugging Face on
  2026-10-09; donors may ask Kyutai to remove a clip, so re-check before
  re-releasing a bundle). Donors accepted Kyutai's Unmute Voice Donation
  Terms of Use v1, §5: "anyone, individuals or organizations, may
  download, copy, modify, transform, and use your Voice Contribution for
  any purpose (commercial or non-commercial) without restriction,
  subject to our Acceptable Use Policy" (Kyutai doesn't link that policy
  anywhere we could find). `rita`'s clip is Spanish speech.
- **`jenny`** is a 26 s reference made from clips of the
  **jenny-tts-dataset**, recorded and released by Jenny (dioco-group) for
  TTS training (https://github.com/dioco-group/jenny-tts-dataset; taken
  from the `reach-vb/jenny_tts_dataset` mirror on Hugging Face):
  silence trimmed, joined with short gaps, resampled to 24 kHz mono and
  normalized. Licence, verbatim from the dataset README:

  > Attribution is required in software/websites/projects/interfaces
  > (including voice interfaces) that generate audio in response to user
  > action using this dataset. Atribution means: the voice must be
  > referred to as "Jenny", and where at all practical, "Jenny (Dioco)".
  > Attribution is not required when distributing the generated clips
  > (although welcome). Commercial use is permitted. Don't do unfair
  > things like claim the dataset is your own. No further restrictions
  > apply.

  The app names the voice "Jenny" and credits it "Jenny (Dioco)" under
  its name in the voice pickers.

### Attribution

The CC-BY-4.0 voices and `jenny` require attribution. The reference recordings are
derived from:

- **`alba`** — "Alba Mackenna", released by Kyutai under CC-BY-4.0
  (`kyutai/tts-voices`, `alba-mackenna/`).
- **`azelma`, `eponine`, `fantine`** — the **CSTR VCTK Corpus**
  (speakers p303, p262, p244), Centre for Speech Technology Research,
  University of Edinburgh, CC-BY-4.0. https://doi.org/10.7488/ds/2645
- **`javert`, `mary`, `rita`, `stephen`, `ramone`** (and `marius`, in
  v21 only) — Unmute Voice Donation Project contributors, released CC0
  (no attribution required; acknowledged with thanks).
- **`jenny`** — "Jenny (Dioco)", the jenny-tts-dataset's attribution
  (custom licence, quoted above).

This attribution is also recorded in [`../CREDITS.md`](../CREDITS.md).

## 3. Tokenizer — SentencePiece model file

- **File:** `tokenizer.model`
- **Upstream:** published with the weights at
  https://huggingface.co/kyutai/pocket-tts; trained alongside the model
- **License:** CC-BY-4.0, with the weights (section 1). Marmalade does **not**
  depend on the SentencePiece library — `PocketTokenizer` is a pure-Kotlin
  reimplementation of the SentencePiece Unigram decode. SentencePiece
  (Apache-2.0, https://github.com/google/sentencepiece) is referenced only
  for the `tokenizer.model` file format.

## ONNX export tooling — KevinAHM/pocket-tts-onnx

- The 5-graph ONNX layout (`flow_lm`, `mimi` encoder/decoder, text
  conditioner) and the `bundle.json` state-manifest format derive from
  the export pipeline at https://github.com/KevinAHM/pocket-tts-onnx
  (Apache-2.0).
- The v27 graphs were exported with KevinAHM's exporter,
  `pocket-tts-onnx-export` (MIT, derived from Kyutai's MIT code; local
  copy at commit `b25d4a7`). Run on the 2026-04 weights, it reproduces
  v21's graphs exactly. Both GitHub repositories returned 404 on
  2026-10-09; no tooling code is in the bundle or the app.

## Runtime

The engine drives the 5 ONNX graphs via Microsoft's `onnxruntime-android`
Maven artifact (MIT) — see the `dependencies` block in `app/build.gradle.kts`.

## Voice cloning

Pocket's architecture can clone a voice from a user-supplied reference
WAV via the `mimi_encoder`. **This is not enabled in the current
release** — there is no cloning UI, and the backend path is dormant
pending a review. No user audio or embedding leaves the device.
