# SciFact and NFCorpus (Phase A screening) with full FiQA, for the baseline and the retained candidates, raw queries.
import json, os, sys
S = sys.argv[1]
runs = {"lexical", "bge-fp32@512", "bge-fp32@128", "minilm-fp32@128", "minilm-fp32@256"}
out = os.path.join(S, "retained-out"); os.makedirs(out, exist_ok=True)
for name in ("summary.jsonl", "queries.jsonl"):
    kept = 0
    with open(os.path.join(out, name), "w") as w:
        for line in open(os.path.join(S, "eval-out", name)):
            r = json.loads(line)
            if r["dataset"] in ("scifact", "nfcorpus") and r["run"] in runs and r["instruction"] in ("off", "-"):
                w.write(line); kept += 1
        for source, wanted in (("eval-full-out", {"lexical", "bge-fp32@512", "minilm-fp32@256"}), ("eval-full-128-out", {"bge-fp32@128", "minilm-fp32@128"})):
            for line in open(os.path.join(S, source, name)):
                r = json.loads(line)
                if r["run"] in wanted:
                    w.write(line); kept += 1
    print(name, kept)
