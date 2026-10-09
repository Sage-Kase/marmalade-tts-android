# Baked default engine — seed assets

These files are baked into the APK so the default engine (Kitten Nano) works
instantly and offline on first run (see `EngineInstaller.seedFromAssets`).

**Only permissively-licensed files live here.** Everything in
`kitten-direct-v0_8/` is **Apache-2.0**: `planner.onnx` + `gen.onnx` (the
upstream `kitten.onnx` cut in two so it can stream, weights unchanged),
`genstats/*.bin` and `voices/*.bin`. They are committed data, not build
outputs: `scripts/kitten-stream/split.py` re-derives them and checks them
against `kitten-direct-v0_8/SHA256SUMS`. That file is also the seed's
content fingerprint: an installed Kitten whose copy of it differs is
re-copied once at app start (`EngineInstaller.seedFromAssets`), so
**regenerate it whenever any file here changes** (`EngineInstallerSeedTest`
fails until you do).

**Never commit `phonemizer/espeak-ng-data/` here.** That data is
**GPL-3.0-or-later** and is generated at build time from the pinned
`third_party/espeak-ng` submodule by `tools/espeak-hostgen` (see the
`generateEspeakData` task in `app/build.gradle.kts`). It lands in
`build/generated/espeakAssets/` (gitignored) and is merged into the APK
assets — exactly the way `libespeak-ng.so` is compiled-in but never committed.
Keeping espeak out of the repo is what keeps the source tree espeak-free and
MIT. See `NOTICE.md`.
