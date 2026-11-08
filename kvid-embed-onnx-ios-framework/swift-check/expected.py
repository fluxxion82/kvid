"""Prints the reference embedding of TEXT for check.sh: Python ONNX Runtime on the same model files.

Usage: python -I expected.py MODEL_ONNX VOCAB_TXT TEXT

Tokenizes with Hugging Face tokenizers' BERT WordPiece (uncased, from vocab.txt, no padding, truncation
to 512), runs onnxruntime's CPU provider with all graph optimizations (as make_embedding_fixture.py
does), CLS-pools and L2-normalizes in float32. Prints one line: ids=... dims=N first=v0,v1,... (8 values).
Requires the packages in kvid-embed-onnx/scripts/requirements.txt (check-local's venv).
"""
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import BertWordPieceTokenizer


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    model_path, vocab_path, text = sys.argv[1:]
    tok = BertWordPieceTokenizer(vocab_path, lowercase=True)
    tok.enable_truncation(max_length=512)
    ids = tok.encode(text).ids
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(model_path, sess_options=options, providers=["CPUExecutionProvider"])
    feed = {"input_ids": np.array([ids], dtype=np.int64), "attention_mask": np.ones((1, len(ids)), dtype=np.int64)}
    if "token_type_ids" in {i.name for i in session.get_inputs()}:
        feed["token_type_ids"] = np.zeros((1, len(ids)), dtype=np.int64)
    hidden = session.run(["last_hidden_state"], feed)[0]
    cls = hidden[0, 0, :].astype(np.float32)
    vector = cls / np.linalg.norm(cls)
    print(f"ids={','.join(map(str, ids))} dims={vector.shape[0]} first={','.join(f'{v:.8f}' for v in vector[:8])} ort={ort.__version__}")


if __name__ == "__main__":
    main()
