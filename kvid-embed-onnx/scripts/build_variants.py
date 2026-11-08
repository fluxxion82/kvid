"""Builds the model variants of the mobile indexing study, each with its own parity fixture.

Usage:
  python -I build_variants.py OUT_ROOT [--variants bge-fp32,bge-int8,...] [--cache DIR] [--random N]

Every OUT_ROOT/<variant> directory is an embedding fixture in make_embedding_fixture.py's format
(model.onnx, vocab.txt, embeddings.jsonl, source.json with the variant's pooling and input limit), made by
Python ONNX Runtime running that variant's own file. OnnxEmbedderParityTest checks a variant when
KVID_EMBEDDING_FIXTURE_DIR points at its directory; OnnxEmbedderTuningTest (model=<variant>) and
RetrievalEvalTest find the variants through KVID_MODEL_VARIANTS_DIR=OUT_ROOT.

Variants (planning branch, docs/plans/mobile-indexing.md, as amended by Codex's review):
  bge-fp32         BAAI/bge-small-en-v1.5 onnx/model.onnx (model-pins.json); CLS pooling, 512 tokens
  bge-fp32-opt     bge-fp32 through ONNX Runtime's basic graph optimizations (saved by an InferenceSession on
                   the CPU provider), then the transformer optimizer's fusions (model_type bert, no GELU
                   approximation): fused attention and layer norms, still fp32. This is what the optimizer's
                   opt_level 1 does, without its import of PyTorch
  bge-int8         bge-fp32 quantized here, conservatively: quantize_dynamic on MatMul only, constant weights
                   only, per-channel QInt8 weights, no reduce_range. Embeddings, attention products, softmax
                   and normalization stay float
  bge-int8-all     bge-fp32 with quantize_dynamic's defaults (every operator it supports, the embedding
                   table's Gather included; per-tensor QInt8): the smaller, riskier recipe, kept separate
  minilm-fp32      sentence-transformers/all-MiniLM-L6-v2 onnx/model.onnx (model-pins-minilm.json); mean
                   pooling, 256 tokens (its sentence-transformers limit)
  minilm-fp32-opt  minilm-fp32 through the same optimizer
  minilm-int8      its upstream onnx/model_qint8_arm64.onnx (model-pins-minilm-int8.json; recipe undocumented)

Derived variants are named "<base model>#kvid-<recipe>" in source.json and in the spec, so they never pass
for a file the model's authors published; "base_model" keeps the original name. Their digests are checked
against model-pins-<variant>.json when that file exists, so other onnx or onnxruntime versions cannot change
them unnoticed; a missing pin prints the digest to pin.

Dynamically quantized variants get "max_batch_size": 1 in source.json: their DynamicQuantizeLinear nodes
take one scale over the whole activation tensor, padding and other texts included, so a batched text's
vector depends on its batch (cosine as low as 0.98 against the same text alone, October 25). Tests and
measurements of those variants embed one text at a time.

Every variant's source.json and OUT_ROOT/variants.json record its operator counts, initializer bytes by
data type and opsets, and for a variant of another fp32 variant, its cosine with that variant on the
fixture texts (percentiles and the worst cases). Drift is a diagnostic, not a quality gate: retrieval
decides. Downloads go through download_model.py into --cache (default ~/.cache/kvid/models/study), which
verifies every pinned digest. Requires requirements-study.txt.
"""
import argparse
import collections
import hashlib
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

RECIPES = {
    "int8-matmul": {"method": "onnxruntime.quantization.quantize_dynamic", "weight_type": "QInt8", "per_channel": True,
                    "reduce_range": False, "op_types_to_quantize": ["MatMul"], "extra_options": {"MatMulConstBOnly": True}},
    "int8-all": {"method": "onnxruntime.quantization.quantize_dynamic", "weight_type": "QInt8", "per_channel": False,
                 "reduce_range": False, "op_types_to_quantize": None, "extra_options": {"MatMulConstBOnly": True}},
    "opt": {"method": "ORT_ENABLE_BASIC session output, then onnxruntime.transformers.optimizer.optimize_model",
            "model_type": "bert", "opt_level": 0, "use_gpu": False, "enable_gelu_approximation": False},
}

# Model geometry for the optimizer: both models have 12 attention heads and a hidden size of 384.
VARIANTS = {
    "bge-fp32": {"pins": "model-pins.json", "pooling": "cls", "max_tokens": 512, "heads": 12, "hidden": 384},
    "bge-fp32-opt": {"base": "bge-fp32", "recipe": "opt"},
    "bge-int8": {"base": "bge-fp32", "recipe": "int8-matmul", "max_batch_size": 1},
    "bge-int8-all": {"base": "bge-fp32", "recipe": "int8-all", "max_batch_size": 1},
    "minilm-fp32": {"pins": "model-pins-minilm.json", "pooling": "mean", "max_tokens": 256, "heads": 12, "hidden": 384},
    "minilm-fp32-opt": {"base": "minilm-fp32", "recipe": "opt"},
    "minilm-int8": {"pins": "model-pins-minilm-int8.json", "pooling": "mean", "max_tokens": 256, "max_batch_size": 1,
                    "drift_from": "minilm-fp32"},
}


def spec_of(variant):
    """The variant's settings, with pooling, input limit and geometry inherited from its base."""
    spec = dict(VARIANTS[variant])
    if "base" in spec:
        base = VARIANTS[spec["base"]]
        for key in ("pooling", "max_tokens", "heads", "hidden"):
            spec.setdefault(key, base[key])
        spec.setdefault("drift_from", spec["base"])
        spec.setdefault("pins", f"model-pins-{variant}.json")
    return spec


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def script(name, *args):
    subprocess.run([sys.executable, "-I", os.path.join(HERE, name), *args], check=True)


def download(variant, cache):
    model_dir = os.path.join(cache, variant)
    pins = os.path.join(HERE, spec_of(variant)["pins"])
    script("download_model.py", model_dir, "--pins", pins)
    return model_dir, pins


def derive(variant, base_dir, cache):
    """Writes the derived model.onnx with the variant's recipe, then provenance.json and pins.json beside it."""
    import onnx
    import onnxruntime

    spec = spec_of(variant)
    recipe = RECIPES[spec["recipe"]]
    model_dir = os.path.join(cache, variant)
    os.makedirs(model_dir, exist_ok=True)
    source, target = os.path.join(base_dir, "model.onnx"), os.path.join(model_dir, "model.onnx")
    partial = os.path.join(model_dir, "model.partial.onnx")
    if spec["recipe"] == "opt":
        from onnxruntime.transformers.fusion_options import FusionOptions
        from onnxruntime.transformers.optimizer import optimize_model

        basic = os.path.join(model_dir, "model.basic.onnx")
        session_options = onnxruntime.SessionOptions()
        session_options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_BASIC
        session_options.optimized_model_filepath = basic
        onnxruntime.InferenceSession(source, session_options, providers=["CPUExecutionProvider"])
        options = FusionOptions("bert")
        options.enable_gelu_approximation = recipe["enable_gelu_approximation"]
        optimized = optimize_model(basic, model_type=recipe["model_type"], num_heads=spec["heads"], hidden_size=spec["hidden"],
                                   optimization_options=options, opt_level=recipe["opt_level"], use_gpu=recipe["use_gpu"])
        optimized.save_model_to_file(partial)
        os.remove(basic)
    else:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(source, partial, op_types_to_quantize=recipe["op_types_to_quantize"], per_channel=recipe["per_channel"],
                         reduce_range=recipe["reduce_range"], weight_type=QuantType.QInt8, extra_options=dict(recipe["extra_options"]))
    os.replace(partial, target)
    for name in ("vocab.txt", "tokenizer.json"):
        shutil.copyfile(os.path.join(base_dir, name), os.path.join(model_dir, name))

    with open(os.path.join(base_dir, "provenance.json"), encoding="utf-8") as f:
        base = json.load(f)
    files = {name: {"remote": base["files"][name]["remote"] if name != "model.onnx" else f"(derived: {spec['recipe']})",
                    "sha256": sha256_file(os.path.join(model_dir, name)), "bytes": os.path.getsize(os.path.join(model_dir, name))}
             for name in ("model.onnx", "vocab.txt", "tokenizer.json")}
    tools = {"onnxruntime": onnxruntime.__version__, "onnx": onnx.__version__, "python": sys.version.split()[0]}
    pinned_path = os.path.join(HERE, spec["pins"])
    if os.path.exists(pinned_path):
        with open(pinned_path, encoding="utf-8") as f:
            pinned = json.load(f)
        expected = pinned["files"]["model.onnx"]["sha256"]
        if files["model.onnx"]["sha256"] != expected:
            sys.exit(f"{variant}: derived model digest {files['model.onnx']['sha256']} differs from {pinned_path} ({expected}); "
                     f"tools now {tools}, pinned with {pinned.get('tools')}")
    else:
        print(f"{variant}: no {os.path.basename(pinned_path)} yet; the derived model.onnx is {files['model.onnx']['sha256']}", flush=True)
    provenance = {"model": base["model"], "revision": base["revision"], "source": base["source"], "files": files,
                  "derived": {"from": base["files"]["model.onnx"], "recipe": spec["recipe"], "settings": recipe, "tools": tools}}
    for name, content in (("provenance.json", provenance), ("pins.json", {"model": base["model"], "revision": base["revision"], "files": files})):
        with open(os.path.join(model_dir, name), "w", encoding="utf-8") as f:
            json.dump(content, f, indent=2)
            f.write("\n")
    return model_dir, os.path.join(model_dir, "pins.json")


def graph_summary(path):
    """Operator counts, initializer bytes by data type and opsets of the ONNX file at path."""
    import onnx
    from onnx import numpy_helper

    model = onnx.load(path)
    ops = collections.Counter(node.op_type for node in model.graph.node)
    weights = collections.Counter()
    for tensor in model.graph.initializer:
        weights[onnx.TensorProto.DataType.Name(tensor.data_type)] += int(numpy_helper.to_array(tensor).nbytes)
    return {"operators": dict(sorted(ops.items())), "initializer_bytes": dict(sorted(weights.items())),
            "opsets": {entry.domain or "ai.onnx": entry.version for entry in model.opset_import}}


def vectors(fixture_dir):
    with open(os.path.join(fixture_dir, "embeddings.jsonl"), encoding="utf-8") as f:
        return [(case["text"], case["vector"]) for case in map(json.loads, f)]


def drift(reference_dir, variant_dir):
    """Cosine between two variants of one model on the same fixture texts: percentiles and the worst cases."""
    import numpy as np

    a, b = vectors(reference_dir), vectors(variant_dir)
    if [t for t, _ in a] != [t for t, _ in b]:
        sys.exit(f"{reference_dir} and {variant_dir} have different fixture texts")
    scored = []
    for (text, x), (_, y) in zip(a, b):
        x, y = np.asarray(x, np.float64), np.asarray(y, np.float64)
        scored.append((float(x @ y / (np.linalg.norm(x) * np.linalg.norm(y))), text))
    cos = np.array(sorted(c for c, _ in scored))
    worst = sorted(scored)[:5]
    return {"cases": len(cos), "min": float(cos[0]), "p1": float(np.percentile(cos, 1)), "p5": float(np.percentile(cos, 5)),
            "median": float(np.median(cos)), "mean": float(cos.mean()),
            "worst": [{"cosine": c, "text": t[:80]} for c, t in worst]}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--variants", default=",".join(VARIANTS))
    ap.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "kvid", "models", "study"))
    ap.add_argument("--random", type=int, default=100, help="seeded random fixture texts per variant")
    args = ap.parse_args()
    wanted = [v.strip() for v in args.variants.split(",") if v.strip()]
    unknown = [v for v in wanted if v not in VARIANTS]
    if unknown:
        sys.exit(f"unknown variants {unknown}; known: {list(VARIANTS)}")
    # A derived variant needs its base (also the reference for its drift), built first.
    for variant in list(wanted):
        reference = spec_of(variant).get("drift_from")
        if reference and reference not in wanted:
            wanted.insert(0, reference)
    wanted.sort(key=lambda v: "base" in VARIANTS[v])

    summary_path = os.path.join(args.out, "variants.json")
    summary = {}
    if os.path.exists(summary_path):  # a partial rebuild keeps the other variants' entries
        with open(summary_path, encoding="utf-8") as f:
            summary = json.load(f)
    model_dirs = {}
    for variant in wanted:
        spec = spec_of(variant)
        print(f"==== {variant}", flush=True)
        if "base" in spec:
            model_dir, pins = derive(variant, model_dirs[spec["base"]], args.cache)
        else:
            model_dir, pins = download(variant, args.cache)
        model_dirs[variant] = model_dir
        out = os.path.join(args.out, variant)
        if os.path.isdir(out):
            shutil.rmtree(out)
        script("make_embedding_fixture.py", out, "--model-dir", model_dir, "--pins", pins,
               "--pooling", spec["pooling"], "--max-tokens", str(spec["max_tokens"]), "--random", str(args.random))
        with open(os.path.join(out, "source.json"), encoding="utf-8") as f:
            source = json.load(f)
        source["variant"] = variant
        source["base_model"] = source["model"]
        if "recipe" in spec:
            source["model"] = f"{source['base_model']}#kvid-{spec['recipe']}"
            source["recipe"] = {"name": spec["recipe"], **RECIPES[spec["recipe"]]}
        if "max_batch_size" in spec:
            source["max_batch_size"] = spec["max_batch_size"]
            source["max_batch_size_reason"] = "dynamic quantization: activation scales span the whole batch, padding included"
        source["graph"] = graph_summary(os.path.join(model_dir, "model.onnx"))
        with open(os.path.join(out, "source.json"), "w", encoding="utf-8") as f:
            json.dump(source, f, indent=2)
            f.write("\n")
        summary[variant] = {"model": source["model"], "base_model": source["base_model"], "revision": source["revision"],
                            "pooling": spec["pooling"], "max_tokens": spec["max_tokens"],
                            "model_bytes": os.path.getsize(os.path.join(model_dir, "model.onnx")),
                            "model_sha256": source["sha256"]["model.onnx"], "recipe": source.get("recipe"),
                            "max_batch_size": spec.get("max_batch_size"), "graph": source["graph"], "fixture": out}
    for variant in wanted:
        reference = spec_of(variant).get("drift_from")
        if reference:
            summary[variant]["drift_from"] = reference
            summary[variant]["drift"] = drift(os.path.join(args.out, reference), os.path.join(args.out, variant))
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")
    print(json.dumps({v: {k: summary[v].get(k) for k in ("model", "model_bytes", "max_batch_size")} for v in wanted}, indent=2))


if __name__ == "__main__":
    main()
