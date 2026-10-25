"""Writes an embedding parity fixture: reference vectors from Python ONNX Runtime and the pinned model.

Usage:
  python -I make_embedding_fixture.py OUT_DIR --model-dir DIR [--pins PATH] [--random N]
      [--pooling cls|mean] [--max-tokens N] [--dimensions N]

DIR is a directory written by download_model.py (model.onnx, vocab.txt, tokenizer.json and
provenance.json). Its digests are verified against provenance.json and the pins before use.
OUT_DIR receives embeddings.jsonl (one {"text", "ids", "vector"} object per line), source.json
(provenance, library versions, provider, optimization level, case count) and hard links to model.onnx
and vocab.txt (copies when a hard link is impossible; never symlinks, which adb may push as links).

Each case is tokenized by Hugging Face tokenizers (truncation to --max-tokens, 512 by default, no
padding), run through onnxruntime one text at a time (so padding cannot influence the reference),
pooled (--pooling: CLS, the default for bge-small-en-v1.5, or the mean of all tokens, accumulated in
float64, for all-MiniLM-L6-v2) and L2-normalized in numpy float32. Cases are a curated list plus seeded random strings from
make_tokenizer_fixture.py's generator. Requires the packages in requirements.txt.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import platform
import random
import shutil
import sys

import numpy as np
import onnxruntime as ort
import tokenizers
from tokenizers import Tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
MAX_TOKENS = 512  # defaults for bge-small-en-v1.5; other models pass --max-tokens and --pooling
DIMENSIONS = 384
OUTPUT_NAME = "last_hidden_state"
SEED = 20261024

CURATED = [
    # Queries
    "budget review", "how do I reset my password", "meeting notes from Thursday", "recipe for sourdough bread",
    "flight to Lisbon in March", "what did Sam say about the contract?", "tax documents 2025", "gym schedule",
    "ideas for the team offsite", "bug: app crashes when the store is closed while syncing",
    "Where is the receipt for the new laptop?", "TODO", "a", "I", "?", "2026-10-24",
    "kvid", "Kotlin Multiplatform", "SELECT * FROM notes WHERE id = 1; -- injection?", "C++ vs Rust memory safety",
    # Notes
    "Budget review on Thursday; draft the plan for next quarter.",
    "Remember to call the dentist before Friday and ask about the invoice from March.",
    "Groceries: eggs, oat milk, spinach, two lemons, coffee beans, dishwasher tablets.",
    "The quick brown fox jumps over the lazy dog.",
    "Reading list: Designing Data-Intensive Applications, The Pragmatic Programmer, and a book on typography.",
    "Standup: finished the tokenizer parity fixture, blocked on the model download, next is the ONNX embedder.",
    "Trip packing list - passport, charger, adapter, rain jacket, notebook, headphones, two paperbacks.",
    "Idea: a note app whose search works offline, keeps every note on the device, and syncs only when asked.",
    "Dear Alex, thanks for the quick turnaround on the draft. I have a few comments on section three; "
    "the numbers in the table do not match the summary above it. Could we talk tomorrow morning?",
    "Lecture 4: gradient descent, learning rate schedules, momentum, and why the loss can plateau before it drops.",
    "Meeting with the landlord on Monday about the leaking tap in the kitchen and the broken window latch.",
    "Birthday gift ideas for Mum: a silk scarf, the new cookbook from the bakery on Elm Street, a spa voucher.",
    "Reminder: renew the car insurance by the 30th, compare at least three quotes first.",
    "Weekend plan: long run on Saturday morning, farmers market, then finish the shelf in the hallway.",
    "Quarterly goals\n\n1. Ship the embedder\n2. Cut CI time\n3. Write the migration guide\n",
    "Multi-paragraph note.\n\nFirst paragraph discusses the design of the vector store and why normalization happens before storage.\n\n"
    "Second paragraph lists open questions: chunking, query instructions, and whether int8 models are worth the accuracy loss.\n\n"
    "Third paragraph is a reminder to benchmark on a real phone, not only on the emulator.",
    "Line one\nLine two\r\nLine three\tTabbed\n\n\nLine six after blank lines",
    "   leading and trailing whitespace   ", "multiple     spaces     inside",
    # Case, accents, Unicode normalization
    "HELLO WORLD in capitals", "MiXeD cAsE tExT", "CAFÉ Café café cafe\u0301", "naïve façade coöperate résumé jalapeño",
    "İstanbul ıstanbul KELVIN \u212a ohm \u2126", "Straße STRASSE", "ﬁne ﬂow ① ＡＢＣ ０１２",
    "Zürich, Köln, Málaga, Kraków, Tromsø, Ålesund, Reykjavík",
    # Other scripts
    "Москва — столица России. Ёлка.", "ΣΊΣΥΦΟΣ σίσυφος Ὀδυσσεύς", "مرحبا بالعالم", "שלום עולם", "नमस्ते दुनिया किताब",
    "สวัสดีครับ ยินดีที่ได้รู้จัก", "한국어 텍스트 가각 안녕하세요",
    # CJK
    "中文分词测试：今天天气很好，我们去公园散步。", "漢字 日本語のテキスト ひらがな カタカナ", "東京で寿司を食べました。とても美味しかったです。",
    "搜索笔记", "豈 更 車 rare cjk \U00020000\U0002A700",
    # Emoji and symbols
    "emoji 😀 👍🏽 👨\u200d👩\u200d👧 🥰 🫠 ❤\ufe0f ✨ 🇫🇷", "Great job team 🎉🎉🎉", "😀", "🚀 launch day 🚀",
    "symbols € £ ¥ © ® ™ ° ± × ÷ → ⇒ ∑ ∞ ≈ ≠ ≤ ≥", "math \U0001D400\U0001D41A bold", "arrows ← ↑ → ↓ ↔ ⇄",
    # Control characters and odd whitespace
    "zero\u200bwidth soft\u00adhyphen bom\ufeffmark", "nbsp\u00a0em\u2003ideographic\u3000narrow\u202fline\u2028para\u2029end",
    "control\x00\x07\x1b\x7f\u0085chars", "private \ue000\U000f0000 use", "replacement \ufffd char",
    # Punctuation, markup, code
    "punctuation \u2014 \u2013 \u2026 \u00bf\u00a1 \u300c\u300d", "can't won't it's l'été O'Brien", "e.g. i.e. U.S.A. Ph.D.",
    "numbers 3.14 1,000,000 2026-10-23 12:30 -5 +7 50% $9.99", "email sam@example.com url https://example.com/a?b=c#d path C:\\Users\\sam",
    "#work @sam **bold** _it_ `code` [link](http://x.y)", "def f(x):\n    return x ** 2  # square",
    "fun main() { println(\"Hello, Kotlin\") }", "{\"key\": [1, 2, 3], \"nested\": {\"a\": null}}", "<html><body><p>Hi</p></body></html>",
    # Special token look-alikes
    "[CLS] [SEP] [PAD] [MASK] [UNK]", "hello[SEP]world", "[sep] [cls] lower",
    # Lengths around useful boundaries
    "a" * 99, "a" * 101, "x" * 150 + " short", "word " * 200, "supercalifragilisticexpialidocious " * 5,
    "unaffable unbelievable tokenization", "pneumonoultramicroscopicsilicovolcanoconiosis",
]

LONG_PARAGRAPH = (
    "The store keeps every note on the device and writes a vector next to it so that search can rank by meaning "
    "as well as by words. When the model changes, the vectors are stale and must be rebuilt, which is why each "
    "vector records the model digest, the tokenizer digest and the pooling that produced it. "
)


def load_tokenizer_fixture_module():
    """Imports make_tokenizer_fixture.py by path: scripts run with -I, which keeps this directory off sys.path."""
    path = os.path.join(HERE, "make_tokenizer_fixture.py")
    spec = importlib.util.spec_from_file_location("make_tokenizer_fixture", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify_model_dir(model_dir, pins_path):
    """Returns provenance after checking every file against it and against the pins; exits on any difference."""
    with open(pins_path, encoding="utf-8") as f:
        pins = json.load(f)
    provenance_path = os.path.join(model_dir, "provenance.json")
    if not os.path.exists(provenance_path):
        sys.exit(f"{provenance_path} not found; write the model directory with download_model.py")
    with open(provenance_path, encoding="utf-8") as f:
        provenance = json.load(f)
    if (provenance.get("model"), provenance.get("revision")) != (pins["model"], pins["revision"]):
        sys.exit(f"{provenance_path} is for {provenance.get('model')}@{provenance.get('revision')}, "
                 f"but the pins are {pins['model']}@{pins['revision']}")
    for name in ("model.onnx", "vocab.txt", "tokenizer.json"):
        path = os.path.join(model_dir, name)
        if not os.path.exists(path):
            sys.exit(f"{path} not found")
        expected = pins["files"][name]["sha256"]
        recorded = provenance.get("files", {}).get(name, {}).get("sha256")
        actual = sha256_file(path)
        if not (actual == expected == recorded):
            sys.exit(f"{name}: digest mismatch in {model_dir}\n  pinned     {expected}\n  provenance {recorded}\n  actual     {actual}")
    return provenance


def link_or_copy(source, target):
    if os.path.lexists(target):
        os.remove(target)
    try:
        os.link(source, target)
        return "hardlink"
    except OSError:
        shutil.copyfile(source, target)
        return "copy"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--pins", default=os.path.join(HERE, "model-pins.json"))
    ap.add_argument("--random", type=int, default=100, help="number of seeded random texts")
    ap.add_argument("--pooling", choices=["cls", "mean"], default="cls")
    ap.add_argument("--max-tokens", type=int, default=MAX_TOKENS)
    ap.add_argument("--dimensions", type=int, default=DIMENSIONS)
    args = ap.parse_args()
    max_tokens, dimensions = args.max_tokens, args.dimensions

    provenance = verify_model_dir(args.model_dir, args.pins)
    model_path = os.path.join(args.model_dir, "model.onnx")
    fixture = load_tokenizer_fixture_module()

    rng = random.Random(SEED)
    long_words = " ".join(rng.choice(fixture.WORDS) for _ in range(700))
    texts = list(CURATED) + ["", LONG_PARAGRAPH * 40, long_words]  # the last two exceed 512 tokens, so any limit
    curated_count = len(texts)
    texts += [fixture.random_text(rng) for _ in range(args.random)]

    tok = Tokenizer.from_file(os.path.join(args.model_dir, "tokenizer.json"))
    tok.no_padding()
    tok.enable_truncation(max_length=max_tokens)
    full = Tokenizer.from_file(os.path.join(args.model_dir, "tokenizer.json"))
    full.no_padding()
    full.no_truncation()
    truncated = sum(1 for t in texts if len(full.encode(t).ids) > max_tokens)
    if truncated < 2:
        sys.exit(f"expected at least two texts over {max_tokens} tokens, found {truncated}")

    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(model_path, sess_options=options, providers=["CPUExecutionProvider"])
    input_names = [i.name for i in session.get_inputs()]
    output_names = [o.name for o in session.get_outputs()]
    unexpected = set(input_names) - {"input_ids", "attention_mask", "token_type_ids"}
    if unexpected or not {"input_ids", "attention_mask"} <= set(input_names):
        sys.exit(f"unexpected model inputs: {input_names}")
    if OUTPUT_NAME not in output_names:
        sys.exit(f"model has no output {OUTPUT_NAME!r}: {output_names}")

    os.makedirs(args.out, exist_ok=True)
    count = 0
    min_norm = max_norm = None
    with open(os.path.join(args.out, "embeddings.jsonl"), "w", encoding="utf-8") as f:
        for text in texts:
            ids = tok.encode(text).ids
            feed = {
                "input_ids": np.array([ids], dtype=np.int64),
                "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
                "token_type_ids": np.zeros((1, len(ids)), dtype=np.int64),
            }
            feed = {name: value for name, value in feed.items() if name in input_names}
            hidden = session.run([OUTPUT_NAME], feed)[0]
            if hidden.shape != (1, len(ids), dimensions):
                sys.exit(f"unexpected output shape {hidden.shape} for {len(ids)} tokens")
            if args.pooling == "cls":
                vector = hidden[0, 0, :].astype(np.float32)
            else:  # mean over every token: one text, so no padding
                vector = hidden[0].astype(np.float64).mean(axis=0).astype(np.float32)
            norm = np.linalg.norm(vector)
            if not np.isfinite(vector).all() or not np.isfinite(norm) or norm == 0:
                sys.exit(f"non-finite or zero vector for text {text[:60]!r}")
            vector = (vector / norm).astype(np.float32)
            unit = float(np.linalg.norm(vector.astype(np.float64)))
            if not np.isfinite(vector).all() or abs(unit - 1.0) > 1e-5:
                sys.exit(f"bad normalization (norm {unit}) for text {text[:60]!r}")
            min_norm = unit if min_norm is None else min(min_norm, unit)
            max_norm = unit if max_norm is None else max(max_norm, unit)
            # json writes floats with repr, so every value round-trips exactly.
            f.write(json.dumps({"text": text, "ids": ids, "vector": [float(x) for x in vector]}, ensure_ascii=True) + "\n")
            count += 1

    modes = {name: link_or_copy(os.path.join(args.model_dir, name), os.path.join(args.out, name)) for name in ("model.onnx", "vocab.txt")}
    source = {
        "model": provenance["model"], "revision": provenance["revision"],
        "sha256": {name: entry["sha256"] for name, entry in sorted(provenance["files"].items())},
        "onnxruntime": ort.__version__, "numpy": np.__version__, "tokenizers": tokenizers.__version__,
        "python": platform.python_version(), "provider": "CPUExecutionProvider",
        "graph_optimization_level": "ORT_ENABLE_ALL",
        "input_names": input_names, "output_name": OUTPUT_NAME, "pooling": args.pooling, "normalize": True,
        "dimensions": dimensions, "max_tokens": max_tokens, "seed": SEED,
        "curated_cases": curated_count, "random_cases": args.random, "cases_over_max_tokens": truncated,
        "unit_norm_range": [min_norm, max_norm], "files": modes, "cases": count,
    }
    with open(os.path.join(args.out, "source.json"), "w", encoding="utf-8") as f:
        json.dump(source, f, indent=2)
        f.write("\n")
    print(json.dumps(source, indent=2))


if __name__ == "__main__":
    main()
