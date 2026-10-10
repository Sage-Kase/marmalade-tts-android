# Kokoro Direct engine — third-party license notice

The Kokoro Direct engine (`kokoro-direct-v1_0`, the recommended default)
runs the Kokoro-82M v1.0 model on Microsoft `onnxruntime-android`
directly (no sherpa-onnx). Its model + phonemizer assets are downloaded
on opt-in from `marmalade-tts-android-engines` into
`${filesDir}/engines/kokoro-direct-v1_0/`; the default APK bundles no
model files.

## Components in the downloaded bundle

| Component | Role | License |
|---|---|---|
| **Kokoro-82M v1.0** (`voices.bin`; up to bundle v23 `model.onnx`; from v26 `planner-rest.ort`, `textenc.ort`, `gen.ort`, `genstats/`) | Neural voice model, 53 voices / 9 languages | **Apache-2.0** (hexgrad/Kokoro-82M) |
| **Marmalade English text encoder** (`textenc-en.ort`, bundle v26 and later) | Stands in for Kokoro's ALBERT text encoder on English text | **Apache-2.0** (Copyright (c) 2026 marmalade-tts contributors) |
| **espeak-ng data** (`phonemizer/espeak-ng-data`, bundles up to v23 only; ≤v18 bundles also carry a now-unused `libttsespeak.so`) | Grapheme→phoneme data for English + European languages | **GPL-3.0-or-later** |
| **Open JTalk dictionary** (NAIST/`open_jtalk_dic`) | Japanese text-analysis dictionary | **Modified BSD** |
| **lexicon-zh** (`lexicon-zh.txt`) | Mandarin Han→IPA lexicon | **CC-BY-SA-4.0** (pinyin data is CC-CEDICT-derived via pypinyin — see note) |

The Japanese **Open JTalk / MeCab** frontend itself is compiled into the
APK from vendored BSD-3 source (`app/src/main/cpp/openjtalk/`) — see
[`open-jtalk.md`](open-jtalk.md) — not downloaded.

## espeak-ng (GPL) and the combined work

This engine depends on **espeak-ng (GPL-3.0-or-later)** for
phonemization. The library (`libespeak-ng.so`) is compiled from source
into the APK from the pinned `third_party/espeak-ng` submodule (tag
commit 96f0dbfb: 1.52.0 plus determinism fix espeak-ng#2071) and `dlopen()`d at runtime by the JNI shim
(`app/src/main/cpp/espeak_jni.c`). The full `espeak-ng-data` tree is
generated from the same source at build time and ships in the APK; that
is the copy the app reads. Bundles up to v23 carry their own copy, left
unused; from v26 the bundle carries no espeak-ng files.

Because espeak-ng ships inside it, the distributed APK is a
GPL-3.0-or-later combined work; Marmalade's own source files remain MIT.
See [`../NOTICE.md`](../NOTICE.md) for the full licensing posture and
the espeak corresponding-source pointer.

## Notices

- **Kokoro-82M** — Copyright (c) hexgrad. Apache License, Version 2.0.
  Model card: https://huggingface.co/hexgrad/Kokoro-82M. The shipped
  `model.onnx` is Marmalade's selectively int8-quantized build of the
  ONNX export from https://github.com/thewh1teagle/kokoro-onnx
  (`model-files` release), as packaged for multilingual use by
  k2-fsa/sherpa-onnx (`kokoro-multi-lang-v1_0`).
- **Modifications (bundle v26 and later, issues #13, #16 and #25):** the
  same int8 model is cut into graphs so it can stream, in ONNX Runtime's
  own `.ort` format. `gen.ort` holds the iSTFTNet vocoder, with its
  InstanceNormalization layers reading a per-channel scale and shift from
  an input; `genstats/<voice>.bin` holds a small per-voice model of those
  values, fitted to the vocoder's own activations on sample text.
  `planner-rest.ort` holds the durations, pitch, the vocoder's front and
  the harmonic source. Its input is the text encoder's output, from one of:
  - `textenc.ort`: Kokoro's own text encoder (ALBERT + `bert_encoder`)
    with most of its matrix weights stored as 8-bit integers (the
    feed-forward expansion stays 32-bit floating point). Nothing was
    retrained. Used for every language but English.
  - `textenc-en.ort`: a 4-layer encoder (3.5M weights; ALBERT +
    `bert_encoder` is 6.1M) that Marmalade trained to produce the same
    output from the same phoneme token ids. It is used for English text
    only. Its training data was Kokoro's own ALBERT outputs on
    public-domain text (PG-19, books published before 1919) and CC0
    sentences (Common Voice sentence collector, release v1.60.0). That
    text was turned into token ids by espeak-ng run as a separate
    program. No audio and no other model was used. The encoder only
    works together with Kokoro's own duration, pitch and vocoder weights.
    Copyright (c) 2026 marmalade-tts contributors; distributed under
    Apache-2.0 as part of the modified model.
- **espeak-ng** — Copyright (C) 2005-2015 Jonathan Duddington;
  Copyright (C) 2012-2021 Reece H. Dunn; Copyright (C) 2018-2022 Juho
  Hiltunen; speechPlayer: Copyright 2014 NV Access Limited; and the other
  espeak-ng contributors (see the source file headers). GPL-3.0-or-later.
  Corresponding source: https://github.com/espeak-ng/espeak-ng (the
  exact version is pinned in [`../NOTICE.md`](../NOTICE.md)).
- **Open JTalk dictionary** — Nagoya Institute of Technology; NAIST
  Japanese Dictionary. Modified BSD. See [`open-jtalk.md`](open-jtalk.md).
- **pypinyin** — MIT. https://github.com/mozillazg/python-pinyin
- **CC-CEDICT** — `lexicon-zh.txt` is sherpa-onnx's pre-baked Mandarin
  Han→IPA table, generated offline from misaki + pypinyin. pypinyin's
  per-character pinyin data derives in part from **CC-CEDICT** (© MDBG,
  https://cc-cedict.org), licensed **CC-BY-SA-4.0**, so the bundled
  `lexicon-zh.txt` is attributed to CC-CEDICT/MDBG and treated as
  CC-BY-SA-4.0. CC-CEDICT is one of pypinyin's several character-pinyin
  sources (alongside Unihan and ZDIC); the values are transformed
  pinyin→IPA.
