"""Compares an embedding fixture with the published model run through sentence-transformers.

Usage:
  python -I check_reference_model.py FIXTURE_DIR [--pins PATH] [--threshold 0.9999]

Manual check, not part of CI or check-local.sh. It confirms that the ONNX graph behind the fixture
(make_embedding_fixture.py) is the published model: every fixture text is embedded one at a time by
SentenceTransformer(model, revision) with normalization, and the cosine with the fixture vector is
compared. Prints the minimum cosine, the case it came from and the environment; exits non-zero when
the minimum is below the threshold or any value is not finite.

Needs a separate virtual environment, because torch is large and CI does not use it:
  python3 -m venv build/check-local/venv-reference
  build/check-local/venv-reference/bin/pip install sentence-transformers torch numpy
  build/check-local/venv-reference/bin/python -I kvid-embed-onnx/scripts/check_reference_model.py DIR
"""
import argparse
import json
import os
import platform
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("fixture")
    ap.add_argument("--pins", default=os.path.join(HERE, "model-pins.json"))
    ap.add_argument("--threshold", type=float, default=0.9999)
    args = ap.parse_args()

    import sentence_transformers
    import torch
    from sentence_transformers import SentenceTransformer

    with open(args.pins, encoding="utf-8") as f:
        pins = json.load(f)
    with open(os.path.join(args.fixture, "source.json"), encoding="utf-8") as f:
        source = json.load(f)
    if (source["model"], source["revision"]) != (pins["model"], pins["revision"]):
        sys.exit(f"the fixture is for {source['model']}@{source['revision']}, the pins are {pins['model']}@{pins['revision']}")
    cases = []
    with open(os.path.join(args.fixture, "embeddings.jsonl"), encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line))
    if len(cases) != source["cases"]:
        sys.exit(f"embeddings.jsonl has {len(cases)} cases, source.json says {source['cases']}")

    model = SentenceTransformer(pins["model"], revision=pins["revision"], device="cpu")
    model.max_seq_length = source["max_tokens"]
    print(f"modules: {[type(m).__name__ for m in model]}", flush=True)

    worst = (2.0, -1)
    bad = 0
    for index, case in enumerate(cases):
        reference = model.encode([case["text"]], batch_size=1, normalize_embeddings=True, convert_to_numpy=True)[0].astype(np.float64)
        vector = np.array(case["vector"], dtype=np.float64)
        if not np.isfinite(reference).all() or not np.isfinite(vector).all():
            bad += 1
            print(f"non-finite value in case {index}: {case['text'][:60]!r}")
            continue
        norms = np.linalg.norm(reference) * np.linalg.norm(vector)
        cosine = float(reference @ vector / norms) if norms > 0 else float("nan")
        if not np.isfinite(cosine):  # a zero vector has no direction; NaN would pass every comparison
            bad += 1
            print(f"undefined cosine (zero vector) in case {index}: {case['text'][:60]!r}")
            continue
        if cosine < worst[0]:
            worst = (cosine, index)
    print(f"cases={len(cases)} non_finite={bad} min_cosine={worst[0]:.9f} one_minus_min={1.0 - worst[0]:.3e} at_case={worst[1]} "
          f"text={cases[worst[1]]['text'][:60]!r}")
    print(f"environment: python {platform.python_version()}, sentence-transformers {sentence_transformers.__version__}, "
          f"torch {torch.__version__}, numpy {np.__version__}, fixture onnxruntime {source['onnxruntime']}, "
          f"model {pins['model']}@{pins['revision']}")
    if bad or worst[0] < args.threshold:
        sys.exit(f"FAILED: minimum cosine {worst[0]:.9f} is below {args.threshold} or {bad} case(s) are not finite or zero")
    print(f"OK: minimum cosine {worst[0]:.9f} >= {args.threshold}")


if __name__ == "__main__":
    main()
