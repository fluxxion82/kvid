"""Downloads the pinned embedding model files and verifies their SHA-256 digests.

Usage:
  python -I download_model.py OUT_DIR [--pins PATH] [--files vocab.txt tokenizer.json ...]

Reads model-pins.json (next to this script unless --pins is given): the model, the exact revision and
the digest of every file. Files are fetched from https://huggingface.co/MODEL/resolve/REVISION/...
to a temporary name, verified while streaming, and renamed into OUT_DIR only when the digest matches.
A file that already exists in OUT_DIR is verified, not trusted: it is kept when it matches and the
script fails (naming the file and both digests) when it does not; delete it to download it again.
--files limits the run to some of the pinned files (local names such as vocab.txt). OUT_DIR also
receives provenance.json (model, revision, remote path, digest, size). Standard library only.
"""
import argparse
import hashlib
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request

ATTEMPTS = 4
BLOCK = 1 << 20


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(BLOCK), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, target):
    """Streams url to target and returns (sha256 hex, size). Raises on network errors."""
    request = urllib.request.Request(url, headers={"User-Agent": "kvid-download-model"})
    h = hashlib.sha256()
    size = 0
    next_report = 32 << 20
    with urllib.request.urlopen(request, timeout=60) as response, open(target, "wb") as out:
        length = response.headers.get("Content-Length")
        for block in iter(lambda: response.read(BLOCK), b""):
            out.write(block)
            h.update(block)
            size += len(block)
            if size >= next_report:
                print(f"  {size >> 20} MiB", flush=True)
                next_report += 32 << 20
    if length is not None and size != int(length):
        # A truncated body is a transport failure to retry, not a digest mismatch.
        raise http.client.IncompleteRead(b"", int(length) - size)
    return h.hexdigest(), size


def download(url, target, expected):
    """Downloads with retries (network errors only); a digest mismatch is final and removes the partial file."""
    partial = target + ".part"
    for attempt in range(1, ATTEMPTS + 1):
        try:
            digest, size = fetch(url, partial)
        except (urllib.error.URLError, http.client.HTTPException, OSError, TimeoutError) as error:  # HTTPError is a URLError; IncompleteRead is an HTTPException
            if os.path.exists(partial):
                os.remove(partial)
            permanent = isinstance(error, urllib.error.HTTPError) and 400 <= error.code < 500 and error.code not in (408, 429)
            if permanent or attempt == ATTEMPTS:
                sys.exit(f"{os.path.basename(target)}: download failed (attempt {attempt} of {ATTEMPTS}): {error}\n  {url}")
            delay = 2 ** attempt
            print(f"  attempt {attempt} failed ({error}); retrying in {delay}s", flush=True)
            time.sleep(delay)
            continue
        if digest != expected:
            os.remove(partial)
            sys.exit(f"{os.path.basename(target)}: digest mismatch for {url}\n  expected {expected}\n  got      {digest}")
        os.replace(partial, target)
        return size


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--pins", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "model-pins.json"))
    ap.add_argument("--files", nargs="+", metavar="NAME", help="local names to fetch (default: every pinned file)")
    args = ap.parse_args()
    with open(args.pins, encoding="utf-8") as f:
        pins = json.load(f)
    model, revision, pinned = pins["model"], pins["revision"], pins["files"]
    names = args.files or list(pinned)
    unknown = [n for n in names if n not in pinned]
    if unknown:
        sys.exit(f"not in {args.pins}: {', '.join(unknown)} (pinned: {', '.join(pinned)})")
    os.makedirs(args.out, exist_ok=True)

    provenance_path = os.path.join(args.out, "provenance.json")
    files = {}
    if os.path.exists(provenance_path):
        try:
            with open(provenance_path, encoding="utf-8") as f:
                previous = json.load(f)
            if previous.get("model") == model and previous.get("revision") == revision:
                files = {n: e for n, e in previous.get("files", {}).items() if n in pinned and os.path.exists(os.path.join(args.out, n))}
        except (OSError, ValueError):
            pass  # rewritten below

    for name in names:
        expected = pinned[name]["sha256"]
        target = os.path.join(args.out, name)
        url = f"https://huggingface.co/{model}/resolve/{revision}/{pinned[name]['remote']}"
        stale = target + ".part"
        if os.path.exists(stale):
            os.remove(stale)
        if os.path.exists(target):
            actual = sha256_file(target)
            if actual != expected:
                sys.exit(f"{name}: existing file does not match the pin ({target})\n  expected {expected}\n  got      {actual}\n"
                         "  delete it to download it again")
            print(f"{name}: verified {actual} (already present, not downloaded)", flush=True)
        else:
            print(f"{name}: downloading {url}", flush=True)
            download(url, target, expected)
            print(f"{name}: downloaded and verified {expected}", flush=True)
        files[name] = {"remote": pinned[name]["remote"], "sha256": expected, "bytes": os.path.getsize(target)}

    with open(provenance_path + ".tmp", "w", encoding="utf-8") as f:
        json.dump({"model": model, "revision": revision, "source": f"https://huggingface.co/{model}/resolve/{revision}/",
                   "files": dict(sorted(files.items()))}, f, indent=2)
        f.write("\n")
    os.replace(provenance_path + ".tmp", provenance_path)


if __name__ == "__main__":
    main()
