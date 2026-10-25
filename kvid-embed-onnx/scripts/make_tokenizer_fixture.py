"""Writes a tokenizer parity fixture: reference encodings from Hugging Face tokenizers.

Usage:
  python -I make_tokenizer_fixture.py OUT_DIR --model BAAI/bge-small-en-v1.5 --revision REV [--sha256 HEX ...]
  python -I make_tokenizer_fixture.py OUT_DIR --tokenizer-json PATH --vocab PATH

OUT_DIR receives vocab.txt, cases.jsonl (one {"text", "ids"} object per line, encoded with special
tokens and truncation to 512) and source.json (origin, file digests, tokenizers version). Cases are a
curated list plus seeded random strings across scripts, whitespace, controls, marks, emoji and symbols.
"""
import argparse
import hashlib
import json
import os
import random
import shutil
import sys
import urllib.request

import tokenizers
from tokenizers import Tokenizer

CURATED = [
    "", " ", "Hello, world!", "The quick brown fox jumps over the lazy dog.",
    "Budget review on Thursday; draft the plan for next quarter.",
    "unaffable unbelievable tokenization", "CAFÉ Café café cafe\u0301", "naïve façade coöperate résumé",
    "İstanbul ıstanbul KELVIN \u212a ohm \u2126 \u212b", "Straße STRASSE \u1e9e", "ΣΊΣΥΦΟΣ σίσυφος Ὀδυσσεύς",
    "Москва Ёлка", "中文分词测试 漢字 日本語のテキスト ひらがな カタカナ", "한국어 텍스트 가각", "豈 更 車",
    "สวัสดีครับ", "नमस्ते दुनिया किताब", "مرحبا بالعالم", "שלום עולם",
    "emoji 😀 👍🏽 👨\u200d👩\u200d👧 🥰 🫠 ❤\ufe0f ✨ 🇫🇷", "symbols € £ ¥ © ® ™ ° ± × ÷ → ⇒ ∑ ∞ ≈ ≠ ≤ ≥",
    "ﬁne ﬂow ① ℌ ℍ ＡＢＣ ０１２", "zero\u200bwidth soft\u00adhyphen bom\ufeffmark", "tab\tnew\nline\r\ncarriage",
    "nbsp\u00a0em\u2003ideographic\u3000narrow\u202fline\u2028para\u2029end", "control\x00\x07\x1b\x7f\u0085chars",
    "private \ue000\U000f0000 use", "math \U0001D400\U0001D41A bold", "rare cjk \U00020000\U0002A700",
    "replacement \ufffd char", "punctuation \u2014 \u2013 \u2026 \u00bf\u00a1 \u300c\u300d \u2e5d", "a" * 99, "a" * 100, "a" * 101,
    "x" * 150 + " short", "[CLS] [SEP] [PAD] [MASK] [UNK]", "hello[SEP]world", "[sep] [cls] lower", "[[SEP]] [SEP][SEP]",
    "email sam@example.com url https://example.com/a?b=c#d path C:\\Users\\sam", "#work @sam **bold** _it_ `code` [link](http://x.y)",
    "numbers 3.14 1,000,000 2026-10-23 12:30 -5 +7 50% $9.99", "can't won't it's l'été O'Brien", "e.g. i.e. U.S.A. Ph.D.",
    "combining e\u0301\u0302 a\u0308\u0304 marks", "devanagari virama \u0915\u094d\u0937", "thai marks \u0e01\u0e34\u0e48",
]

POOLS = [
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
    "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
    " \t\n\r\u00a0\u2003\u3000\u2028\u202f",
    "\x00\x07\x1b\x7f\u0085\u200b\u200c\u200d\u2060\ufeff\u00ad\ufffd",
    "àáâãäåæçèéêëìíîïñòóôõöøùúûüýÿĀāĂăĄąĆćČčĎďĐđĒēĘęĚěĞğĪīİıŁłŃńŇňŌōŐőŒœŘřŚśŞşŠšŢţŤťŪūŮůŰűŸŹźŻżŽžẞß",
    "\u0300\u0301\u0302\u0303\u0308\u030a\u0327\u0331\u20dd",
    "ΑΒΓΔΣσςαβγδΐΰάέήίόύώ", "АБВГДЁЖЗабвгдёжзЙй", "مرحباשלום", "नमस्तेकि", "สวัสดี",
    "中文漢字日本語ひらがなカタカナ한국어가각", "豈更ﬁﬂ①ℌℍＡＢ０１", "😀👍🏽🥰🫠❤✨\ufe0f\u200d🇫🇷",
    "€£¥©®™°±×÷→⇒∑∞≈≠≤≥\u2014\u2013\u2026\u300c\u300d", "\ue000\U0001D400\U00020000\u212a\u2126\u212b",
]
WORDS = ("the of and to in is that for it with as was on be at by this had not are but from or have an they which "
         "one you were her all she there would their we him been has when who will more no if out so said what up "
         "plan budget review note meeting search store document version token embedding model vector query").split()


def random_text(rng):
    parts = []
    for _ in range(rng.randint(1, 30)):
        roll = rng.random()
        if roll < 0.45:
            word = rng.choice(WORDS)
            parts.append(word.upper() if rng.random() < 0.1 else word.capitalize() if rng.random() < 0.2 else word)
        else:
            pool = rng.choice(POOLS)
            parts.append("".join(rng.choice(pool) for _ in range(rng.randint(1, 12))))
        parts.append(rng.choice([" ", " ", " ", "", "\n", ", ", ". ", "\t"]))
    return "".join(parts)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def resolve_revision(model, revision):
    """The commit id a branch or tag name points to, so the fixture records an exact revision."""
    with urllib.request.urlopen(f"https://huggingface.co/api/models/{model}/revision/{revision}", timeout=60) as response:
        return json.load(response)["sha"]


def download(model, revision, name, target):
    url = f"https://huggingface.co/{model}/resolve/{revision}/{name}"
    with urllib.request.urlopen(url, timeout=60) as response, open(target, "wb") as out:
        shutil.copyfileobj(response, out)
    return url


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--model")
    ap.add_argument("--revision", default="main")
    ap.add_argument("--sha256", nargs="*", default=[], help="expected digests of tokenizer.json and vocab.txt, in that order")
    ap.add_argument("--tokenizer-json")
    ap.add_argument("--vocab")
    ap.add_argument("--random", type=int, default=3000)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    tokenizer_json = os.path.join(args.out, "tokenizer.json")
    vocab = os.path.join(args.out, "vocab.txt")
    revision = args.revision
    if args.model:
        revision = resolve_revision(args.model, args.revision)
        origin = [download(args.model, revision, "tokenizer.json", tokenizer_json),
                  download(args.model, revision, "vocab.txt", vocab)]
    else:
        shutil.copyfile(args.tokenizer_json, tokenizer_json)
        shutil.copyfile(args.vocab, vocab)
        origin = [args.tokenizer_json, args.vocab]
    digests = [sha256(tokenizer_json), sha256(vocab)]
    if args.sha256 and args.sha256 != digests:
        sys.exit(f"digest mismatch: expected {args.sha256}, got {digests}")

    tok = Tokenizer.from_file(tokenizer_json)
    tok.no_padding()
    tok.enable_truncation(max_length=512)
    rng = random.Random(20261023)
    texts = CURATED + [random_text(rng) for _ in range(args.random)]
    texts.append(" ".join(rng.choice(WORDS) for _ in range(700)))  # exceeds 512 tokens
    with open(os.path.join(args.out, "cases.jsonl"), "w", encoding="utf-8") as f:
        for text in texts:
            f.write(json.dumps({"text": text, "ids": tok.encode(text).ids}, ensure_ascii=True) + "\n")
    source = {"origin": origin, "revision": revision, "requested_revision": args.revision, "sha256": dict(zip(["tokenizer.json", "vocab.txt"], digests)),
              "tokenizers": tokenizers.__version__, "cases": len(texts)}
    with open(os.path.join(args.out, "source.json"), "w") as f:
        json.dump(source, f, indent=2)
    print(json.dumps(source, indent=2))


if __name__ == "__main__":
    main()
