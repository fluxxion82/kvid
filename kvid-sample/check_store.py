"""Reads a kvid store file copied from a device and checks its clean-close marker and document count.

Usage: check_store.py STORE EXPECTED_CLEAN_CLOSE MIN_DOCUMENTS
"""
import sqlite3
import sys

path, expected_clean, min_docs = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=10)
clean = conn.execute("SELECT clean_close FROM kvid_meta WHERE id = 1").fetchone()[0]
docs = conn.execute("SELECT count(*) FROM current").fetchone()[0]
integrity = conn.execute("PRAGMA quick_check").fetchone()[0]
print(f"{path}: clean_close={clean} live_documents={docs} quick_check={integrity}")
problems = []
if clean != expected_clean:
    problems.append(f"clean_close is {clean}, expected {expected_clean}")
if docs < min_docs:
    problems.append(f"{docs} live documents, expected at least {min_docs}")
if integrity != "ok":
    problems.append(f"quick_check: {integrity}")
if problems:
    sys.exit("; ".join(problems))
