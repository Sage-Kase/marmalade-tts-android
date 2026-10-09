#!/usr/bin/env python3
"""Check the shipped streaming pair against the model it was cut from.

    verify.py <kitten.onnx> <student.onnx> <bundle>

For every voice and every corpus.json chunk, from ONE planning pass (the
harmonic source's random draws seeded, so every render below shares them):

  split   kitten.onnx with the X2 student in ALBERT's place (split.with_student)
          vs planner.onnx + the generator with real InstanceNorm: max
          |difference| of the waveform. The cut itself must be exact.
  seams   the app's streamed render (KittenStreamSlicing's schedule: 0.5 s,
          1 s, doubling to 4 s, 4 frames of context, speech frames only) vs
          the same fixed-statistics generator run as one slice: SNR in dB.
          Slices must join exactly.
  stats   the streamed render vs the unsplit render with real InstanceNorm:
          mean |log-mel difference| in dB. This is the one approximation (fixed
          per-voice norm statistics); compare it with
  noise   two unsplit renders with different random draws, same measure:
          Kitten's own run-to-run variation.
  x2 dur  the planner's durations vs Kitten's own (ALBERT) planner: mean
          |difference| in frames (25 ms) per token. Issue #14's pass line: <= ~0.5.
  x2 F0   pitch with ALBERT's durations fed to both text models: mean
          |difference| in Hz over ALBERT's voiced frames. Pass line: <= ~10.

Prints one row per voice (means over the corpus; seams = worst chunk).
"""
import sys
from pathlib import Path

import numpy as np
import onnx

import split

FRAME = 600
FIRST, SIZE, MAX, CONTEXT = 20, 40, 160, 4   # KittenStreamSlicing
HEAD_KEEP, TAIL_KEEP, SPACE_TOKEN = 2, 3, 16  # KittenTrim, IpaTokenVocab


def speech_frames(ids, dur):
    start = max(int(dur[0]) - HEAD_KEEP, 0)
    tail = 0
    for i in reversed(range(len(ids))):
        if ids[i] <= SPACE_TOKEN:
            tail += int(dur[i])
        else:
            break
    return start, int(dur.sum()) - max(tail - TAIL_KEEP, 0)


def slices(a, end, total):
    out, later = [], SIZE
    while a < end:
        n = FIRST if not out else later
        if out:
            later = min(later * 2, MAX)
        b = min(end, a + n)
        out.append((a, b, max(0, a - CONTEXT), min(total, b + CONTEXT)))
        a = b
    return out


def mel_db(x, sr=24000, n_fft=1024, hop=256, n_mels=80):
    import scipy.signal
    _, _, Z = scipy.signal.stft(x, fs=sr, nperseg=n_fft, noverlap=n_fft - hop, boundary=None, padded=False)
    mel = lambda f: 2595 * np.log10(1 + f / 700)
    imel = lambda m: 700 * (10 ** (m / 2595) - 1)
    pts = imel(np.linspace(mel(0), mel(sr / 2), n_mels + 2))
    bins = np.fft.rfftfreq(n_fft, 1 / sr)
    fb = np.zeros((n_mels, len(bins)))
    for i in range(n_mels):
        l, c, r = pts[i:i + 3]
        fb[i] = np.clip(np.minimum((bins - l) / (c - l), (r - bins) / (r - c)), 0, None)
    return 10 * np.log10(fb @ (np.abs(Z) ** 2) + 1e-10)


def mel_diff(a, b):
    return float(np.abs(mel_db(a) - mel_db(b)).mean())


def main(kitten, student, bundle):
    m = split.load_kitten(kitten)
    seeded = lambda model, seed: split.session(split.seeded(model, seed), threads=4)
    whole = split.with_student(m, student)
    ins = ["input_ids", "style", "speed"]
    albert_dur = split.session(split.cut(m, ins, ["duration"]), threads=4)
    albert_f0 = split.session(split.cut(m, ins + ["duration"], [split.F0]), threads=4)
    student_f0 = split.session(split.cut(whole, ins + ["duration"], [split.F0]), threads=4)
    planner_model = onnx.load(bundle / "planner.onnx")
    true_gen = split.session(split.cut(m, [split.X, split.S128, split.HAR], ["waveform"]), threads=4)
    gen = split.session(bundle / "gen.onnx", threads=4)
    corpus = __import__("json").loads((split.HERE / "corpus.json").read_text())
    print("voice   split max|Δ|   seams min SNR   stats mel dB   noise mel dB   x2 dur   x2 F0 Hz")
    for voice in split.VOICES:
        W, W2, P = seeded(whole, 1.0), seeded(whole, 2.0), seeded(planner_model, 1.0)
        gn = np.fromfile(bundle / "genstats" / f"{voice}.bin", "<f4").reshape(2, -1)
        split_d, snrs, stats, noise, x2_dur, x2_f0 = [], [], [], [], [], []
        for case in corpus:
            ids = np.array([case["ids"]], np.int64)
            feed = {"input_ids": ids, "style": split.style_row(bundle / "voices", voice, case["row_text"]),
                    "speed": np.array([split.SPEED_PRIORS[voice]], np.float32)}
            ref = W.run(None, feed)[0].reshape(-1)
            other = W2.run(None, feed)[0].reshape(-1)
            x, s, har, dur = P.run(None, feed)
            dur = dur.reshape(-1).astype(np.int64)
            T = x.shape[2] // 2
            unsplit = true_gen.run(None, {split.X: x, split.S128: s, split.HAR: har})[0].reshape(-1)
            split_d.append(float(np.abs(unsplit - ref).max()))
            a, b = speech_frames(case["ids"], dur)
            whole_fixed = gen.run(None, {split.X: x, split.S128: s, split.HAR: har, "gn": gn})[0].reshape(-1)
            parts = []
            for ka, kb, ra, rb in slices(a, b, T):
                y = gen.run(None, {split.X: x[:, :, 2 * ra:2 * rb], split.S128: s,
                                   split.HAR: har[:, FRAME * ra:FRAME * rb, :], "gn": gn})[0].reshape(-1)
                parts.append(y[FRAME * (ka - ra):FRAME * (kb - ra)])
            streamed = np.concatenate(parts)
            want = whole_fixed[FRAME * a:FRAME * b]
            err = streamed - want
            snrs.append(10 * np.log10((want ** 2).sum() / max((err ** 2).sum(), 1e-30)))
            stats.append(mel_diff(streamed, unsplit[FRAME * a:FRAME * b]))
            noise.append(mel_diff(other[FRAME * a:FRAME * b], ref[FRAME * a:FRAME * b]))
            teacher = albert_dur.run(None, feed)[0]
            x2_dur.append(float(np.abs(dur - teacher.reshape(-1)).mean()))
            f0a = albert_f0.run(None, dict(feed, duration=teacher))[0].reshape(-1)
            f0s = student_f0.run(None, dict(feed, duration=teacher))[0].reshape(-1)
            x2_f0.append(float(np.abs(f0s - f0a)[f0a > 10].mean()))
        print(f"{voice:7} {max(split_d):12.2e} {min(snrs):14.1f} {np.mean(stats):14.2f} {np.mean(noise):14.2f}"
              f" {np.mean(x2_dur):8.3f} {np.mean(x2_f0):10.2f}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*map(Path, sys.argv[1:]))
