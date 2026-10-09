#!/usr/bin/env python3
"""Cut Kitten nano 0.8 (kitten.onnx) into the streaming pair the app ships.

    planner.onnx   input_ids, style, speed -> x, s128, har, duration
                   Text model, durations, pitch/energy, the decoder's frame-rate
                   front (exact InstanceNorm over the whole chunk) and the
                   harmonic source (its phase is a cumsum over the chunk and it
                   holds the model's only random draws). Runs once per chunk.
    gen.onnx       x, s128, har, gn -> waveform
                   The iSTFTNet generator, ~98% of the decoder's time. Each of
                   its 36 InstanceNorms is replaced by y = x * a + b with
                   per-channel (a, b) read from one input `gn` float32 [2, S],
                   which makes it purely convolutional: frame slices rendered
                   with 4 frames of context each side join exactly.
    genstats/<voice>.bin
                   float32 [2, S] little-endian per voice: a = scale/sqrt(var+eps),
                   b = bias - mean*a, from the population statistics of the
                   real generator's activations over corpus.json (17 chunks of
                   the B1 sentence set, phone-exact token ids).

Nothing is retrained: both graphs are node subsets of kitten.onnx with its
weights unchanged (the norm rewrite only re-wires the 36 norm layers), so
the Apache-2.0 model licence carries over as-is. Each graph's doc_string
states that it was modified and how (Apache-2.0 section 4(b)).

Usage (onnx 1.23.1, onnxruntime 1.30.0, numpy):
    split.py graphs <kitten.onnx> <bundle>   writes planner.onnx + gen.onnx
    split.py stats  <kitten.onnx> <bundle>   writes genstats/*.bin (reads voices/)
    split.py check  <bundle>                 compares the bundle with its SHA256SUMS
<bundle> is app/src/main/assets/engines-seed/kitten-direct-v0_8. kitten.onnx is
the file that directory held up to issue #10 (git show 993bf52:<bundle>/kitten.onnx),
also in the v22 kitten-direct-v0_8.tar.gz engine bundle.

`graphs` is pure protobuf surgery and byte-deterministic. `stats` seeds the
harmonic source's two random nodes and runs single-threaded, so it reproduces
the committed tables on the same onnxruntime version; float kernels can differ
across CPUs and versions in the last bits, which is why the tables are
committed data, not build outputs. The bundle's SHA256SUMS (every file but
itself) doubles as the seed's content fingerprint: EngineInstaller re-copies an
installed Kitten whose copy of it differs, so it must change with any file. Issue #10 (graph cut from issue #3).
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

HERE = Path(__file__).parent
KITTEN_SHA256 = "320564d2615f235de972ca27a7f39551c94185cfa24ca85b07a29084135f1e5e"

# Graph-boundary tensors (KittenDirectEngine's GEN_* constants).
X = "/decoder/decode.3/Mul_output_0"        # [1, 256, 2T] decoder-front output
S128 = "/Unsqueeze_12_output_0"             # [1, 128] decoder half of the style row
HAR = "/decoder/generator/m_source/l_tanh/Tanh_output_0"  # [1, 600T, 1] source at 24 kHz

VOICES = ["bella", "jasper", "luna", "bruno", "rosie", "hugo", "kiki", "leo"]
# KittenDirectEngine.SPEED_PRIORS: the model speed every app render uses.
SPEED_PRIORS = {"bella": 1.24, "jasper": 0.84, "luna": 0.92, "bruno": 0.88,
                "rosie": 0.96, "hugo": 0.84, "kiki": 0.84, "leo": 1.25}
STATS_SEED = 10.0


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def subgraph_refs(node):
    """Names a node's subgraphs (If/Loop bodies) read from the enclosing scope."""
    refs = set()
    for a in node.attribute:
        for g in ([a.g] if a.type == onnx.AttributeProto.GRAPH else list(a.graphs)):
            local = {i.name for i in g.input} | {i.name for i in g.initializer}
            for n in g.node:
                local |= set(n.output)
            for n in g.node:
                refs |= {i for i in n.input if i and i not in local}
                refs |= subgraph_refs(n) - local
    return refs


def cut(model, inputs, outputs):
    """The nodes computing [outputs] from [inputs]. onnx.utils.Extractor drops
    outer-scope references inside Loop/If bodies, so this walks them by hand."""
    g = model.graph
    prod = {o: n for n in g.node for o in n.output}
    need, keep, stack = set(), set(), list(outputs)
    while stack:
        t = stack.pop()
        if t in need or t in inputs:
            continue
        need.add(t)
        n = prod.get(t)
        if n is None or id(n) in keep:
            continue
        keep.add(id(n))
        stack += [i for i in n.input if i] + list(subgraph_refs(n))
    nodes = [n for n in g.node if id(n) in keep]
    inits = [i for i in g.initializer if i.name in need]
    vi = {v.name: v for v in list(g.value_info) + list(g.input) + list(g.output)}

    def info(name):
        return vi[name] if name in vi else helper.make_tensor_value_info(name, TensorProto.FLOAT, None)

    graph = helper.make_graph(nodes, g.name, [info(i) for i in inputs], [info(o) for o in outputs], inits,
                              value_info=[v for v in g.value_info if v.name in need])
    m = helper.make_model(graph, opset_imports=model.opset_import)
    m.ir_version = model.ir_version
    return m


def fixed_norms(gen):
    """Every InstanceNormalization -> x * a + b, (a, b) sliced from input `gn`.
    Returns the rewritten model, the layer table
    [(name, input, C, eps, offset, scale, bias)] and S, the total channel count."""
    g = gen.graph
    inits = {i.name: i for i in g.initializer}
    layers, nodes, off = [], [], 0
    for n in g.node:
        if n.op_type != "InstanceNormalization":
            nodes.append(n)
            continue
        x, scale, bias = n.input
        eps = next((a.f for a in n.attribute if a.name == "epsilon"), 1e-5)
        sc, bi = numpy_helper.to_array(inits[scale]), numpy_helper.to_array(inits[bias])
        C = sc.shape[0]
        p = f"gn{len(layers)}_"
        g.initializer.extend([numpy_helper.from_array(np.array([0, off], np.int64), p + "s0"),
                              numpy_helper.from_array(np.array([2, off + C], np.int64), p + "s1"),
                              numpy_helper.from_array(np.array([2, 1, C, 1], np.int64), p + "shape"),
                              numpy_helper.from_array(np.array([0], np.int64), p + "i0"),
                              numpy_helper.from_array(np.array([1], np.int64), p + "i1")])
        nodes += [helper.make_node("Slice", ["gn", p + "s0", p + "s1"], [p + "ab"], name=p + "slice"),
                  helper.make_node("Reshape", [p + "ab", p + "shape"], [p + "ab4"], name=p + "reshape"),
                  helper.make_node("Gather", [p + "ab4", p + "i0"], [p + "a"], name=p + "ga", axis=0),
                  helper.make_node("Gather", [p + "ab4", p + "i1"], [p + "b"], name=p + "gb", axis=0),
                  # Gather with a [1] index keeps a leading 1: [1,1,C,1] -> [1,C,1]
                  helper.make_node("Squeeze", [p + "a", p + "i0"], [p + "a3"], name=p + "sqa"),
                  helper.make_node("Squeeze", [p + "b", p + "i0"], [p + "b3"], name=p + "sqb"),
                  helper.make_node("Mul", [x, p + "a3"], [p + "xa"], name=p + "mul"),
                  helper.make_node("Add", [p + "xa", p + "b3"], list(n.output), name=p + "add")]
        layers.append((n.name, x, C, eps, off, sc, bi))
        off += C
    del g.node[:]
    g.node.extend(nodes)
    g.input.append(helper.make_tensor_value_info("gn", TensorProto.FLOAT, [2, off]))
    used = {i for n in g.node for i in n.input}
    keep = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(keep)
    return gen, layers, off


NOTICE = ("Derived from KittenML kitten-tts-nano-0.8 (kitten.onnx, Apache-2.0, "
          "Copyright (c) KittenML contributors). Modified by Marmalade TTS: {}. "
          "Weights unchanged; made by scripts/kitten-stream/split.py.")


def with_notice(model, change):
    model.doc_string = NOTICE.format(change)
    return model


def load_kitten(path):
    if sha256(path) != KITTEN_SHA256:
        sys.exit(f"{path}: not the shipped Kitten nano 0.8 (sha256 {KITTEN_SHA256})")
    return onnx.shape_inference.infer_shapes(onnx.load(path))


def graphs(kitten, out):
    out.mkdir(parents=True, exist_ok=True)
    m = load_kitten(kitten)
    planner = cut(m, ["input_ids", "style", "speed"], [X, S128, HAR, "duration"])
    onnx.save(with_notice(planner, "the text model, durations, pitch, decoder front and "
                                   "harmonic source cut out as a separate graph"), out / "planner.onnx")
    gen, layers, S = fixed_norms(cut(m, [X, S128, HAR], ["waveform"]))
    onnx.save(with_notice(gen, "the iSTFTNet generator cut out as a separate graph, its "
                               "InstanceNormalization layers replaced by a per-channel scale "
                               "and shift read from the input gn"), out / "gen.onnx")
    print(f"planner.onnx {sha256(out / 'planner.onnx')}\ngen.onnx {sha256(out / 'gen.onnx')}  "
          f"({len(layers)} norm layers, S={S})")


def session(model, threads=1):
    import onnxruntime as ort
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.inter_op_num_threads = 1
    o.log_severity_level = 3
    return ort.InferenceSession(model.SerializeToString() if isinstance(model, onnx.ModelProto) else str(model),
                                o, providers=["CPUExecutionProvider"])


def seeded(model, seed=STATS_SEED):
    """A copy of [model] with a fixed seed on its random nodes (the harmonic source's noise)."""
    out = onnx.ModelProto()
    out.CopyFrom(model)
    for n in out.graph.node:
        if n.op_type.startswith("Random"):
            kept = [a for a in n.attribute if a.name != "seed"]
            del n.attribute[:]
            n.attribute.extend(kept + [helper.make_attribute("seed", float(seed))])
    return out


def style_row(voices_dir, voice, row_text):
    t = np.fromfile(voices_dir / f"{voice}.bin", dtype="<f4").reshape(-1, 256)
    return t[min(len(row_text), len(t) - 1)][None, :]


def stats(kitten, out):
    """Population mean/var of each generator norm's input, pooled over the corpus
    weighted by frame count, per voice -> genstats/<voice>.bin."""
    m = load_kitten(kitten)
    voices_dir = out / "voices"
    planner = seeded(cut(m, ["input_ids", "style", "speed"], [X, S128, HAR]))
    gen = cut(m, [X, S128, HAR], ["waveform"])
    _, layers, S = fixed_norms(cut(m, [X, S128, HAR], ["waveform"]))
    have = {o.name for o in gen.graph.output}
    for _, x, *_ in layers:
        if x not in have:
            gen.graph.output.append(helper.make_tensor_value_info(x, TensorProto.FLOAT, None))
    names = [x for _, x, *_ in layers]
    corpus = json.loads((HERE / "corpus.json").read_text())
    (out / "genstats").mkdir(parents=True, exist_ok=True)
    G = session(gen)
    for voice in VOICES:
        P = session(planner)  # fresh session per voice: the seeded draws restart
        sums = [None] * len(layers)
        total = 0
        for case in corpus:
            ids = np.array([case["ids"]], np.int64)
            x, s, har = P.run(None, {"input_ids": ids, "style": style_row(voices_dir, voice, case["row_text"]),
                                     "speed": np.array([SPEED_PRIORS[voice]], np.float32)})
            acts = G.run(names, {X: x, S128: s, HAR: har})
            T = x.shape[2] // 2
            for k, a in enumerate(acts):
                a = a.astype(np.float64)
                mu = a.mean(axis=2).reshape(-1)
                var = a.var(axis=2).reshape(-1)
                if sums[k] is None:
                    sums[k] = [np.zeros_like(mu), np.zeros_like(mu), []]
                sums[k][0] += T * mu
                sums[k][2].append((T, mu, var))
            total += T
        ab = np.zeros((2, S), np.float32)
        for k, (_, _, C, eps, off, sc, bi) in enumerate(layers):
            mean = sums[k][0] / total
            var = sum(T * (v + (mu - mean) ** 2) for T, mu, v in sums[k][2]) / total
            a = sc / np.sqrt(var + eps)
            ab[0, off:off + C] = a
            ab[1, off:off + C] = bi - mean * a
        path = out / "genstats" / f"{voice}.bin"
        ab.astype("<f4").tofile(path)
        print(f"genstats/{voice}.bin {sha256(path)}", flush=True)


def check(d):
    bad = 0
    for line in (d / "SHA256SUMS").read_text().splitlines():
        want, name = line.split()
        got = sha256(d / name) if (d / name).is_file() else "missing"
        if got != want:
            bad += 1
            print(f"MISMATCH {name}: {got}")
    print("all match" if not bad else f"{bad} mismatched")
    return 1 if bad else 0


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "graphs" and len(args) == 2:
        graphs(Path(args[0]), Path(args[1]))
    elif cmd == "stats" and len(args) == 2:
        stats(Path(args[0]), Path(args[1]))
    elif cmd == "check" and len(args) == 1:
        sys.exit(check(Path(args[0])))
    else:
        sys.exit(__doc__)
