# Kitten Direct — third-party license notice

Covers **Kitten Direct** (nano, 15M params, `kitten-direct-v0_8`), which
runs the upstream KittenML acoustic model directly on
`com.microsoft.onnxruntime:onnxruntime-android`, with espeak-ng as the
phonemizer. espeak-ng (GPL-3.0-or-later) is compiled from source into
the APK, and the APK also ships the full `espeak-ng-data` dictionaries
(generated at build time from the pinned submodule).

The nano engine is **baked into the APK** as a seed
(`assets/engines-seed/kitten-direct-v0_8/`: model + 8 voices,
Apache-2.0) so the app speaks offline from first launch, with no
download. Since 1.2 the baked model is the upstream `kitten.onnx` cut
into two graphs so it can stream, and since issue #17 its text encoder
is a smaller one trained by Marmalade (see section 1); the downloadable
bundle still carries the unmodified `kitten.onnx`.

## License posture — APK vs bundle

| Layer | Contents | License |
|---|---|---|
| Marmalade APK | Kotlin engine code, ORT bindings, JNI shim, **libespeak-ng.so (compiled from source)**, full espeak-ng-data (build-time generated), baked nano model + voices | Source files MIT; model files Apache-2.0; **APK distributed under GPL-3.0-or-later** because of espeak-ng |

The JNI shim (`app/src/main/cpp/espeak_jni.c`) links no espeak code at
build time — it `dlopen()`s the APK's own `libespeak-ng.so`, built from
the pinned `third_party/espeak-ng` submodule. See
[`../NOTICE.md`](../NOTICE.md).

## 1. Acoustic model — KittenML/kitten-tts-nano-0.8

- **Files:** baked into the APK: `planner.onnx`, `gen.onnx`,
  `genstats/<voice>.bin`, `voices/<voice>.bin` for the 8 voices (bella,
  jasper, luna, bruno, rosie, hugo, kiki, leo). The v22 engine bundle:
  `kitten.onnx` and `voices/<voice>.bin`.
- **Upstream:** https://github.com/KittenML/KittenTTS
- **Distribution:** https://huggingface.co/KittenML/kitten-tts-nano-0.8-fp32
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) KittenML contributors. Voices were extracted
  from upstream's `voices.npz` and saved as per-voice little-endian
  float32 binaries.
- **Modifications (APK, since 1.2):** `planner.onnx` and `gen.onnx` are
  upstream's `kitten.onnx` cut into two graphs: the planner holds the
  text model, durations, pitch, the decoder's front and the harmonic
  source; the generator holds the iSTFTNet vocoder, with its
  InstanceNormalization layers replaced by a per-channel scale and shift
  read from an input. `genstats/<voice>.bin` holds those scales and
  shifts per voice, computed from the generator's activations on 17 test
  sentences. Kitten's own weights are unchanged and none was retrained.
- **Replaced text encoder (APK, issue #17):** in `planner.onnx`, Kitten's
  text encoder (ALBERT + `bert_encoder`, 5.8M weights) is replaced by the
  "X2 student", a 4-layer encoder (3.4M weights) that Marmalade trained
  to produce the same output from the same phoneme token ids. Its
  training data was Kitten's own ALBERT outputs on public-domain text
  (PG-19, books published before 1919) and CC0 sentences (Common Voice
  sentence collector, release v1.60.0), turned into token ids by
  espeak-ng run as a separate program; no audio and no other model was
  used. The student only works together with Kitten's duration, pitch
  and vocoder weights. Copyright (c) 2026 marmalade-tts contributors;
  distributed under Apache-2.0 as part of the modified `planner.onnx`.
  `genstats/` was recomputed with the new planner.
- Each graph's `doc_string` states the change. Made by
  `scripts/kitten-stream/split.py`; the source models' SHA-256 sums are
  pinned in it, and the outputs' sums are in the seed's own `SHA256SUMS`.

## 2. Phonemizer — espeak-ng

- **Library:** `libespeak-ng.so` — in the **APK**, compiled from source
  (submodule `third_party/espeak-ng`, commit 96f0dbfb: the 1.52.0 release plus upstream determinism fix espeak-ng#2071) by
  `app/src/main/cpp/espeak-ng/CMakeLists.txt`.
- **Data:** the full `espeak-ng-data` tree is generated at build time
  from the same pinned submodule and ships in the **APK**, which is the
  copy the app reads. The bundle still carries its own
  `phonemizer/espeak-ng-data/`, built from that same commit since the v22
  bundle (earlier bundles derived it from Debian's
  `espeak-ng-data 1.51+dfsg-12build1`).
- **Legacy:** bundles ≤v16 also carried `phonemizer/<abi>/libttsespeak.so`
  (lifted from espeak-ng's official 1.52.0 Android APK). The app no
  longer loads it; it remains covered by the same GPL terms.
- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **License:** GNU General Public License v3.0 or later
- **Notice:** Copyright (C) 2005-2015 Jonathan Duddington;
  Copyright (C) 2012-2021 Reece H. Dunn; Copyright (C) 2018-2022 Juho
  Hiltunen; speechPlayer: Copyright 2014 NV Access Limited; and the other
  espeak-ng contributors (see the source file headers). Used in sentence
  mode (`espeak_TextToPhonemes` with `phonememode = IPA`).
- **Source availability:** Per GPL-3.0 §6, corresponding source for the
  APK's libespeak-ng.so is the pinned submodule in this repository
  (upstream commit 96f0dbfb: 1.52.0 plus determinism fix espeak-ng#2071).

## 3. JNI shim — Marmalade (this repo)

- **File:** `app/src/main/cpp/espeak_jni.c` (~150 lines)
- **License:** MIT (matches the rest of Marmalade)
- **Role:** Resolves espeak's C API at runtime via `dlopen`/`dlsym`.
  Never linked against espeak at build time; contains zero lines of
  espeak code.

## GPL-3.0 implications

The distributed APK compiles in espeak-ng, so the APK as a whole is a
GPL-3.0-or-later combined work. Every other piece of code and data in
the APK is under a GPL-3.0-compatible license (MIT, BSD, Apache-2.0,
MPL-2.0, or the Unicode license), the brand fonts are OFL-1.1, and
Marmalade's own source files remain MIT. The Kitten bundle adds GPL-licensed espeak-ng-data; the
install screen discloses this before download. See
[`../NOTICE.md`](../NOTICE.md).
