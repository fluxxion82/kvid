"""Prepares BEIR datasets for RetrievalEvalTest (the mobile indexing study).

Usage:
  python -I prepare_retrieval_eval.py OUT_ROOT [--datasets scifact,nfcorpus,fiqa] [--cache DIR]
      [--max-queries N] [--max-docs N] [--seed S]

Downloads each dataset's corpus, queries and test relevance judgments from Hugging Face at the revisions
and SHA-256 digests in retrieval-datasets.json (BEIR, CC-BY-SA-4.0: measure with them, never commit them)
into --cache (default ~/.cache/kvid/datasets) and writes OUT_ROOT/<dataset>/ with:
  corpus.jsonl   {"id", "title", "text"} per document
  queries.jsonl  {"id", "text"} per test query that has at least one relevant document
  qrels.tsv      query id, document id, grade (> 0 only)
  source.json    revisions, digests, counts, the subset rule and the license

Subsets: --max-queries keeps a seeded sample of the test queries; --max-docs keeps every document judged
for the kept queries and fills up with seeded random others. By default SciFact and NFCorpus are whole
and FiQA keeps all test queries and 20,000 documents. Small values (for example --max-queries 10
--max-docs 300) smoke-test the tooling. Requires pyarrow (requirements-study.txt).
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import os
import random
import sys

import pyarrow.parquet as pq

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MAX_DOCS = {"fiqa": 20000}


def load_downloader():
    """download_model.py's verified, retrying download (scripts run with -I, so this directory is not on sys.path)."""
    spec = importlib.util.spec_from_file_location("download_model", os.path.join(HERE, "download_model.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(downloader, repository, revision, remote, expected, cache):
    target = os.path.join(cache, repository.replace("/", "_"), revision, remote)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if os.path.exists(target):
        actual = sha256_file(target)
        if actual != expected:
            sys.exit(f"{target}: does not match the pin\n  expected {expected}\n  got      {actual}\n  delete it to download it again")
        return target
    url = f"https://huggingface.co/datasets/{repository}/resolve/{revision}/{remote}"
    print(f"downloading {url}", flush=True)
    downloader.download(url, target, expected)
    return target


def write_jsonl(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=True) + "\n")


def prepare(name, pin, args, downloader):
    corpus_path = fetch(downloader, pin["repository"], pin["revision"], pin["corpus"]["remote"], pin["corpus"]["sha256"], args.cache)
    queries_path = fetch(downloader, pin["repository"], pin["revision"], pin["queries"]["remote"], pin["queries"]["sha256"], args.cache)
    qrels_pin = pin["qrels"]
    qrels_path = fetch(downloader, qrels_pin["repository"], qrels_pin["revision"], qrels_pin["remote"], qrels_pin["sha256"], args.cache)

    corpus = {row["_id"]: row for row in pq.read_table(corpus_path, columns=["_id", "title", "text"]).to_pylist()}
    queries = {row["_id"]: row["text"] for row in pq.read_table(queries_path, columns=["_id", "text"]).to_pylist()}
    qrels = {}
    with open(qrels_path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            grade = int(row["score"])
            if grade > 0 and row["corpus-id"] in corpus:
                qrels.setdefault(row["query-id"], {})[row["corpus-id"]] = grade
    judged = sorted(q for q in qrels if q in queries)

    rng = random.Random(args.seed)
    kept_queries = judged
    if args.max_queries is not None and args.max_queries < len(judged):
        kept_queries = sorted(rng.sample(judged, args.max_queries))
    relevant = sorted({doc for q in kept_queries for doc in qrels[q]})
    max_docs = args.max_docs if args.max_docs is not None else DEFAULT_MAX_DOCS.get(name)
    if max_docs is None or max_docs >= len(corpus):
        kept_docs = sorted(corpus)
    else:
        if max_docs < len(relevant):
            sys.exit(f"{name}: --max-docs {max_docs} is below the {len(relevant)} documents judged for the kept queries")
        others = sorted(set(corpus) - set(relevant))
        kept_docs = sorted(relevant + rng.sample(others, max_docs - len(relevant)))

    out = os.path.join(args.out, name)
    os.makedirs(out, exist_ok=True)
    write_jsonl(os.path.join(out, "corpus.jsonl"),
                ({"id": d, "title": corpus[d]["title"] or "", "text": corpus[d]["text"] or ""} for d in kept_docs))
    write_jsonl(os.path.join(out, "queries.jsonl"), ({"id": q, "text": queries[q]} for q in kept_queries))
    with open(os.path.join(out, "qrels.tsv"), "w", encoding="utf-8") as f:
        f.write("query-id\tcorpus-id\tscore\n")
        for q in kept_queries:
            for doc, grade in sorted(qrels[q].items()):
                f.write(f"{q}\t{doc}\t{grade}\n")
    source = {
        "dataset": name, "repository": pin["repository"], "revision": pin["revision"], "qrels_revision": qrels_pin["revision"],
        "sha256": {"corpus": pin["corpus"]["sha256"], "queries": pin["queries"]["sha256"], "qrels": qrels_pin["sha256"]},
        "license": "CC-BY-SA-4.0", "split": "test",
        "documents": len(kept_docs), "documents_in_corpus": len(corpus), "queries": len(kept_queries),
        "judged_queries": len(judged), "relevant_pairs": sum(len(qrels[q]) for q in kept_queries),
        "subset": {"max_queries": args.max_queries, "max_docs": max_docs, "seed": args.seed,
                   "rule": "every document judged for a kept query, then seeded random others"},
    }
    with open(os.path.join(out, "source.json"), "w", encoding="utf-8") as f:
        json.dump(source, f, indent=2)
        f.write("\n")
    print(json.dumps(source), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--datasets", default="scifact,nfcorpus,fiqa")
    ap.add_argument("--cache", default=os.path.join(os.path.expanduser("~"), ".cache", "kvid", "datasets"))
    ap.add_argument("--max-queries", type=int)
    ap.add_argument("--max-docs", type=int)
    ap.add_argument("--seed", type=int, default=20261025)
    args = ap.parse_args()
    with open(os.path.join(HERE, "retrieval-datasets.json"), encoding="utf-8") as f:
        pins = json.load(f)["datasets"]
    names = [n.strip() for n in args.datasets.split(",") if n.strip()]
    unknown = [n for n in names if n not in pins]
    if unknown:
        sys.exit(f"unknown datasets {unknown}; pinned: {list(pins)}")
    downloader = load_downloader()
    for name in names:
        prepare(name, pins[name], args, downloader)


if __name__ == "__main__":
    main()
