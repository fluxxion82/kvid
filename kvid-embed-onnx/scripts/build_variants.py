"""Builds the model variants of the mobile indexing study, each with its own parity fixture.

Usage:
  python -I build_variants.py OUT_ROOT [--variants bge-fp32,bge-int8,minilm-fp32,minilm-int8]
      [--cache DIR] [--random N]

Every OUT_ROOT/<variant> directory is an embedding fixture in make_embedding_fixture.py's format
(model.onnx, vocab.txt, embeddings.jsonl, source.json with the variant's pooling and input limit), made by
Python ONNX Runtime running that variant's own file. OnnxEmbedderParityTest checks a variant when
KVID_EMBEDDING_FIXTURE_DIR points at its directory; OnnxEmbedderTuningTest (model=<variant>) and
RetrievalEvalTest find the variants through KVID_MODEL_VARIANTS_DIR=OUT_ROOT.

Variants:
  bge-fp32     BAAI/bge-small-en-v1.5 onnx/model.onnx (model-pins.json); CLS pooling, 512 tokens
  bge-int8     bge-fp32 quantized here with onnxruntime.quantization.quantize_dynamic (QInt8 weights,
               per tensor, no reduce_range); the result is checked against model-pins-bge-int8.json when
               that file exists, so other onnx or onnxruntime versions cannot change it unnoticed
  minilm-fp32  sentence-transformers/all-MiniLM-L6-v2 onnx/model.onnx (model-pins-minilm.json); mean
               pooling, 256 tokens (its sentence-transformers limit)
  minilm-int8  its onnx/model_qint8_arm64.onnx (model-pins-minilm-int8.json)

Dynamically quantized variants (both int8 ones) get "max_batch_size": 1 in source.json: their
DynamicQuantizeLinear nodes take one scale over the whole activation tensor, padding and other texts
included, so a batched text's vector depends on its batch (cosine as low as 0.98 against the same text
alone, October 25). Tests and measurements of those variants embed one text at a time.

Downloads go through download_model.py into --cache (default ~/.cache/kvid/models/study), which verifies
every pinned digest. OUT_ROOT/variants.json records each variant's model, pooling, input limit, file size
and digest, the quantization settings, and for bge-int8 its cosine with bge-fp32 on the fixture texts.
Requires requirements-study.txt.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

QUANTIZATION = {"method": "onnxruntime.quantization.quantize_dynamic", "weight_type": "QInt8", "per_channel": False, "reduce_range": False}

VARIANTS = {
    "bge-fp32": {"pins": "model-pins.json", "pooling": "cls", "max_tokens": 512},
    "bge-int8": {"base": "bge-fp32", "pooling": "cls", "max_tokens": 512, "quantization": QUANTIZATION,
                 "pins": "model-pins-bge-int8.json", "max_batch_size": 1},
    "minilm-fp32": {"pins": "model-pins-minilm.json", "pooling": "mean", "max_tokens": 256},
    "minilm-int8": {"pins": "model-pins-minilm-int8.json", "pooling": "mean", "max_tokens": 256, "max_batch_size": 1},
}


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
    script("download_model.py", model_dir, "--pins", os.path.join(HERE, VARIANTS[variant]["pins"]))
    return model_dir, os.path.join(HERE, VARIANTS[variant]["pins"])


def quantize(variant, base_dir, cache):
    """Quantizes base_dir/model.onnx; writes provenance.json and pins.json (the produced digests) beside it."""
    from onnxruntime.quantization import QuantType, quantize_dynamic
    import onnx
    import onnxruntime

    model_dir = os.path.join(cache, variant)
    os.makedirs(model_dir, exist_ok=True)
    target = os.path.join(model_dir, "model.onnx")
    partial = os.path.join(model_dir, "model.partial.onnx")
    quantize_dynamic(os.path.join(base_dir, "model.onnx"), partial,
                     weight_type=QuantType.QInt8, per_channel=QUANTIZATION["per_channel"], reduce_range=QUANTIZATION["reduce_range"])
    os.replace(partial, target)
    for name in ("vocab.txt", "tokenizer.json"):
        shutil.copyfile(os.path.join(base_dir, name), os.path.join(model_dir, name))

    with open(os.path.join(base_dir, "provenance.json"), encoding="utf-8") as f:
        base = json.load(f)
    files = {name: {"remote": base["files"][name]["remote"] if name != "model.onnx" else "(quantized from onnx/model.onnx)",
                    "sha256": sha256_file(os.path.join(model_dir, name)), "bytes": os.path.getsize(os.path.join(model_dir, name))}
             for name in ("model.onnx", "vocab.txt", "tokenizer.json")}
    tools = {"onnxruntime": onnxruntime.__version__, "onnx": onnx.__version__}
    pinned_path = os.path.join(HERE, VARIANTS[variant]["pins"])
    if os.path.exists(pinned_path):
        with open(pinned_path, encoding="utf-8") as f:
            pinned = json.load(f)
        expected = pinned["files"]["model.onnx"]["sha256"]
        if files["model.onnx"]["sha256"] != expected:
            sys.exit(f"{variant}: quantized model digest {files['model.onnx']['sha256']} differs from {pinned_path} ({expected}); "
                     f"tools now {tools}, pinned with {pinned.get('tools')}")
    else:
        print(f"{variant}: no {os.path.basename(pinned_path)} yet; the quantized model.onnx is {files['model.onnx']['sha256']}", flush=True)
    provenance = {"model": base["model"], "revision": base["revision"], "source": base["source"], "files": files,
                  "derived": {"from": base["files"]["model.onnx"], "quantization": QUANTIZATION, "tools": tools}}
    for name, content in (("provenance.json", provenance), ("pins.json", {"model": base["model"], "revision": base["revision"], "files": files})):
        with open(os.path.join(model_dir, name), "w", encoding="utf-8") as f:
            json.dump(content, f, indent=2)
            f.write("\n")
    return model_dir, os.path.join(model_dir, "pins.json")


def vectors(fixture_dir):
    with open(os.path.join(fixture_dir, "embeddings.jsonl"), encoding="utf-8") as f:
        return [(case["text"], case["vector"]) for case in map(json.loads, f)]


def cosine_summary(a_dir, b_dir):
    import numpy as np

    a, b = vectors(a_dir), vectors(b_dir)
    if [t for t, _ in a] != [t for t, _ in b]:
        sys.exit(f"{a_dir} and {b_dir} have different fixture texts")
    cos = sorted(float(np.dot(np.asarray(x, np.float64), np.asarray(y, np.float64)) /
                       (np.linalg.norm(np.asarray(x, np.float64)) * np.linalg.norm(np.asarray(y, np.float64))))
                 for (_, x), (_, y) in zip(a, b))
    return {"cases": len(cos), "min": cos[0], "median": cos[len(cos) // 2], "mean": sum(cos) / len(cos)}


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
    if "bge-int8" in wanted and "bge-fp32" not in wanted:
        wanted.insert(0, "bge-fp32")  # the base of the quantized model and the reference for its drift

    summary_path = os.path.join(args.out, "variants.json")
    summary = {}
    if os.path.exists(summary_path):  # a partial rebuild keeps the other variants' entries
        with open(summary_path, encoding="utf-8") as f:
            summary = json.load(f)
    model_dirs = {}
    for variant in wanted:
        spec = VARIANTS[variant]
        print(f"==== {variant}", flush=True)
        if "base" in spec:
            model_dir, pins = quantize(variant, model_dirs[spec["base"]], args.cache)
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
        if "quantization" in spec:
            source["quantization"] = spec["quantization"]
        if "max_batch_size" in spec:
            source["max_batch_size"] = spec["max_batch_size"]
            source["max_batch_size_reason"] = "dynamic quantization: activation scales span the whole batch, padding included"

        with open(os.path.join(out, "source.json"), "w", encoding="utf-8") as f:
            json.dump(source, f, indent=2)
            f.write("\n")
        summary[variant] = {"model": source["model"], "revision": source["revision"], "pooling": spec["pooling"],
                            "max_tokens": spec["max_tokens"], "model_bytes": os.path.getsize(os.path.join(model_dir, "model.onnx")),
                            "model_sha256": source["sha256"]["model.onnx"], "quantization": spec.get("quantization"),
                            "max_batch_size": spec.get("max_batch_size"),
                            "fixture": out}
    if "bge-int8" in summary:
        summary["bge-int8"]["cosine_with_bge_fp32"] = cosine_summary(os.path.join(args.out, "bge-fp32"), os.path.join(args.out, "bge-int8"))
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
