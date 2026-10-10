#!/usr/bin/env python3
"""Precompute a Pocket bundle's voice embeddings (issue #38).

Writes `voice_cache/<voice>.emb` for every voice in bundle.json's
`predefined_voices`, from `voices/<voice>.wav` and the SAME bundle's
`mimi_encoder.onnx`, so no phone has to encode a built-in voice on first use
(that encode peaks at +1.1 GB on a Pixel 6a for a 10 s reference and +2.65 GB
for Jenny's 16.8 s one). PocketEngine reads `voice_cache/<voice>.emb` before it
would encode, and falls back to the WAV when the file is missing or the wrong
size, so the WAVs stay in the bundle.

The preprocessing mirrors the app's encode path exactly (PocketAudio.readWav,
PocketAudio.resample, PocketAudio.normalizeIfClipping and PocketEngine.encodePcm's
30 s cap), in float32 with the same operation order. The .emb format is
PocketEngine.writeEmbCache's: int32 LE frame count, then frames x 1024 float32 LE.

Checks (the script fails if one doesn't hold):
  - two encodes of each voice are bit-identical (one ORT thread, deterministic);
  - each embedding is [frames, conditioning_dim] with frames = ceil(samples / samples_per_frame);
  - with --compare DIR: each DIR/<voice>.emb (written by the app's own encode
    path, e.g. pulled from a device's voice_cache) is reported as max abs diff,
    and must stay under --tolerance.

usage: make_voice_cache.py <bundle-dir> [--compare DIR] [--tolerance 1e-4]
needs: numpy, onnxruntime
"""
import argparse, json, math, struct, sys
from pathlib import Path

import numpy as np
import onnxruntime as ort


def read_wav(path):
    """PocketAudio.readWav: 16-bit PCM, mono or stereo (channel average), / 32768."""
    b = path.read_bytes()
    assert b[:4] == b"RIFF" and b[8:12] == b"WAVE", f"{path}: not a RIFF/WAVE file"
    pos, rate, ch, bits, fmt, data = 12, 0, 0, 0, 0, None
    while pos + 8 <= len(b):
        cid, size = b[pos:pos + 4], struct.unpack_from("<i", b, pos + 4)[0]
        body = pos + 8
        if cid == b"fmt ":
            fmt, ch, rate = struct.unpack_from("<HHi", b, body)
            bits = struct.unpack_from("<H", b, body + 14)[0]
        elif cid == b"data":
            data = b[body:body + size]
            break
        pos = body + size + (size & 1)
    assert fmt in (1, 0xFFFE) and bits == 16 and ch in (1, 2), f"{path}: fmt={fmt} bits={bits} ch={ch}"
    pcm = np.frombuffer(data[: len(data) // 2 * 2], dtype="<i2")
    if ch == 1:
        return pcm.astype(np.float32) / np.float32(32768), rate
    frames = pcm[: len(pcm) // 2 * 2].reshape(-1, 2).astype(np.int32)
    # Kotlin: ((l + r) / 2f) / 32768f, in float32
    return ((frames[:, 0] + frames[:, 1]).astype(np.float32) / np.float32(2)) / np.float32(32768), rate


def resample(pcm, src, dst):
    """PocketAudio.resample: identity, 2:1 averaging, else linear interpolation."""
    if src == dst:
        return pcm
    if src == 2 * dst:
        n = len(pcm) // 2
        return (pcm[0:2 * n:2] + pcm[1:2 * n:2]) * np.float32(0.5)
    ratio = src / dst                                   # Kotlin Double
    n = int(len(pcm) / ratio)
    pos = np.arange(n, dtype=np.float64) * ratio        # i * ratio, Double
    i0 = pos.astype(np.int64)                           # srcPos.toInt()
    i1 = np.minimum(i0 + 1, len(pcm) - 1)
    frac = (pos - i0).astype(np.float32)                # (srcPos - src0).toFloat()
    return pcm[i0] * (np.float32(1) - frac) + pcm[i1] * frac


def normalize_if_clipping(pcm):
    """PocketAudio.normalizeIfClipping: divide by the peak only when it exceeds 1."""
    m = np.float32(np.abs(pcm).max()) if len(pcm) else np.float32(0)
    return pcm if m <= 1 else pcm * (np.float32(1) / m)


def preprocess(wav, spec):
    pcm, rate = read_wav(wav)
    pcm = normalize_if_clipping(resample(pcm, rate, spec["sample_rate"]))
    return pcm[: 30 * spec["sample_rate"]]              # encodePcm's 30 s cap


def encode(session, pcm):
    out = session.run(["latents"], {"audio": pcm.reshape(1, 1, -1).astype(np.float32)})[0]
    return np.ascontiguousarray(out[0], dtype=np.float32)


def write_emb(path, emb):
    path.write_bytes(struct.pack("<i", emb.shape[0]) + emb.astype("<f4").tobytes())


def read_emb(path, dim):
    b = path.read_bytes()
    frames = struct.unpack_from("<i", b)[0]
    assert len(b) == 4 + frames * dim * 4, f"{path}: size mismatch"
    return np.frombuffer(b, dtype="<f4", offset=4).reshape(frames, dim)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle", type=Path)
    ap.add_argument("--compare", type=Path)
    ap.add_argument("--tolerance", type=float, default=1e-4)
    a = ap.parse_args()
    spec = json.loads((a.bundle / "bundle.json").read_text())
    dim, spf = spec["conditioning_dim"], spec["samples_per_frame"]
    so = ort.SessionOptions()
    so.intra_op_num_threads = so.inter_op_num_threads = 1
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(a.bundle / spec["onnx_files"]["mimi_encoder"]), so,
                                   providers=["CPUExecutionProvider"])
    out_dir = a.bundle / "voice_cache"
    out_dir.mkdir(exist_ok=True)
    ok = True
    for voice in spec["predefined_voices"]:
        pcm = preprocess(a.bundle / "voices" / f"{voice}.wav", spec)
        emb, again = encode(session, pcm), encode(session, pcm)
        frames = math.ceil(len(pcm) / spf)
        assert emb.shape == (frames, dim), f"{voice}: shape {emb.shape}, expected ({frames}, {dim})"
        assert np.array_equal(emb, again), f"{voice}: two encodes differ (not deterministic)"
        write_emb(out_dir / f"{voice}.emb", emb)
        assert np.array_equal(read_emb(out_dir / f"{voice}.emb", dim), emb)
        line = f"{voice:10} {len(pcm) / spec['sample_rate']:5.2f} s  {frames:4d} frames  deterministic"
        if a.compare:
            ref = a.compare / f"{voice}.emb"
            if ref.is_file():
                app = read_emb(ref, dim)
                if app.shape != emb.shape:
                    line += f"  app {app.shape}: SHAPE MISMATCH"; ok = False
                else:
                    diff = float(np.abs(app - emb).max())
                    line += f"  vs app: max abs diff {diff:.3g} (embedding max {float(np.abs(emb).max()):.3g})"
                    ok &= diff <= a.tolerance
            else:
                line += "  (no app .emb to compare)"
        print(line, flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
