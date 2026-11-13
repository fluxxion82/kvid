"""Tests of check_measure_lines.py: a valid record passes, and each counterexample found in review fails.

Run: python3 -I -m unittest discover -s kvid-embed-onnx/scripts -p 'test_*.py'  (scripts/check-local.sh's python part)

The records are written by hand, with percentiles worked out by hand (nearest rank: p50 of four values is the
second smallest, p95 the largest), so a valid record does not depend on the checker's own arithmetic.
"""
import contextlib
import io
import os
import tempfile
import unittest

import check_measure_lines as checker

EPOCH = "1794505237596"
MEM_INDEX = (
    "mem_kind=pss mem_peak=sampled-100ms+readings-from-before-open mem_before_kb=100 mem_after_open_kb=200 "
    "mem_peak_kb=400 mem_after_index_kb=380 mem_after_close_kb=150 mem_open_peak_kb=210 mem_index_peak_kb=400 "
    "mem_pause_peak_kb=390 mem_queries_peak_kb=395 mem_edits_peak_kb=410 mem_close_peak_kb=150 "
    "mem_lifecycle_peak_kb=410 mem_samples=40 mem_boundary_samples=1"
)
QUERY_FIELDS = (
    "queries=4 query_store_notes=8 query_order=williams4 query_embed_p50_ms=2.0 query_embed_p95_ms=4.0 "
    "query_lexical_p50_ms=0.3 query_lexical_p95_ms=0.5 query_similar_p50_ms=6.0 query_similar_p95_ms=8.0 "
    "query_hybrid_p50_ms=10.0 query_hybrid_p95_ms=12.0"
)
# Four edits of 10, 70, 130 and 300 tokens: one per length bucket.
EDIT_FIELDS = (
    "edits=4 edit_write_p50_ms=3.0 edit_write_p95_ms=5.0 "
    "edit_save_p50_ms=17.0 edit_save_p95_ms=35.0 edit_save_n_le64=1 edit_save_le64_p50_ms=8.0 edit_save_le64_p95_ms=8.0 "
    "edit_save_n_le128=1 edit_save_le128_p50_ms=17.0 edit_save_le128_p95_ms=17.0 edit_save_n_le256=1 "
    "edit_save_le256_p50_ms=26.0 edit_save_le256_p95_ms=26.0 edit_save_n_gt256=1 edit_save_gt256_p50_ms=35.0 "
    "edit_save_gt256_p95_ms=35.0 "
    "edit_total_p50_ms=20.0 edit_total_p95_ms=40.0 edit_total_n_le64=1 edit_total_le64_p50_ms=10.0 "
    "edit_total_le64_p95_ms=10.0 edit_total_n_le128=1 edit_total_le128_p50_ms=20.0 edit_total_le128_p95_ms=20.0 "
    "edit_total_n_le256=1 edit_total_le256_p50_ms=30.0 edit_total_le256_p95_ms=30.0 edit_total_n_gt256=1 "
    "edit_total_gt256_p50_ms=40.0 edit_total_gt256_p95_ms=40.0"
)
SERIES = {
    "query_embed_ms": "1.0,2.0,3.0,4.0",
    "query_lexical_ms": "0.5,0.4,0.3,0.2",
    "query_similar_ms": "5.0,6.0,7.0,8.0",
    "query_hybrid_ms": "9.0,10.0,11.0,12.0",
    "edit_total_ms": "10.0,20.0,30.0,40.0",
    "edit_save_ms": "8.0,17.0,26.0,35.0",
    "edit_write_ms": "2.0,3.0,4.0,5.0",
    "edit_tokens": "10,70,130,300",
    "edit_kind": "update,put,update,put",
}


def index_line(config="corpus=mixed+notes=8+model=bge-fp32+edits=4+queries=4+pause=1",
               starts="open:0.0,index:1.0,pause:2.0,queries:3.0,edits:3.5,close:4.0,end:4.1",
               queries=QUERY_FIELDS, edits=EDIT_FIELDS, mem=MEM_INDEX):
    return (f"[kvid-index-measure] platform=test config={config} model=m variant=bge-fp32 max_input_tokens=512 "
            f"notes=8 open_epoch_ms={EPOCH} phase_start_s={starts} {queries} {edits} {mem}")


def samples_lines(series=None, epoch=EPOCH):
    lines = []
    for name, values in (SERIES if series is None else series).items():
        n = len(values.split(","))
        lines.append(f"[kvid-index-samples] open_epoch_ms={epoch} series={name} part=1/1 n={n} values={values}")
    return lines


TUNE_LINE = (
    f"[kvid-embedding-tune] platform=test config=corpus=mixed+notes=10+model=bge-fp32 model=m open_epoch_ms={EPOCH} "
    "open_ms=800.0 notes=10 phase_start_s=open:0.0,embed:1.0,close:2.0,idle:2.1,end:7.1 "
    "mem_kind=pss mem_peak=sampled-100ms+readings-from-before-open mem_before_kb=100 mem_after_open_kb=200 "
    "mem_peak_kb=300 mem_after_kb=300 mem_after_close_kb=120 mem_idle_5s_kb=110 mem_open_peak_kb=210 "
    "mem_embed_peak_kb=300 mem_close_peak_kb=120 mem_idle_peak_kb=125 mem_lifecycle_peak_kb=300 mem_samples=60 "
    "mem_boundary_samples=0"
)


class CheckerTest(unittest.TestCase):
    def run_checker(self, lines):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "raw.txt")
            with open(path, "w", encoding="utf-8") as out:
                out.write("\n".join(lines) + "\n")
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                code = checker.main([path])
        return code, printed.getvalue()

    def assertPasses(self, lines):
        code, out = self.run_checker(lines)
        self.assertEqual(0, code, out)

    def assertFails(self, lines, reason):
        code, out = self.run_checker(lines)
        self.assertEqual(1, code, out)
        self.assertIn(reason, out)

    def test_valid_records_pass(self):
        self.assertPasses([index_line()] + samples_lines() + [TUNE_LINE])

    def test_a_wrong_bucket_percentile_fails(self):  # Codex's second review, finding 1
        line = index_line().replace("edit_total_le64_p95_ms=10.0", "edit_total_le64_p95_ms=999.0")
        self.assertFails([line] + samples_lines(), "edit_total_le64_p95_ms=999.0 but the samples give 10.0")

    def test_a_wrong_bucket_count_fails(self):
        line = index_line().replace("edit_save_n_le128=1", "edit_save_n_le128=2")
        self.assertFails([line] + samples_lines(), "edit_save_n_le128=2 but the samples give 1")

    def test_token_counts_decide_the_buckets(self):
        series = dict(SERIES, edit_tokens="10,20,130,300")  # the second edit moves from le128 to le64
        self.assertFails([index_line()] + samples_lines(series), "edit_total_n_le64=1 but the samples give 2")

    def test_a_bucket_without_samples_prints_nothing(self):
        series = dict(SERIES, edit_tokens="10,70,300,300")  # the third edit moves to gt256, leaving le256 empty
        self.assertFails([index_line()] + samples_lines(series), "edit_total_n_le256=1 but the samples have no edit of that length")

    def test_token_counts_beyond_the_limit_fail(self):
        series = dict(SERIES, edit_tokens="10,70,130,600")
        self.assertFails([index_line()] + samples_lines(series), "edit_tokens: values outside 1 to max_input_tokens=512")

    def test_part_metadata_must_agree(self):  # finding 4
        lines = [line for line in samples_lines() if "series=edit_kind " not in line]
        lines += [f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=1/2 n=4 values=update,put",
                  f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=2/3 n=4 values=update,put"]
        self.assertFails([index_line()] + lines, "samples edit_kind: bad parts disagree on part count or n")

    def test_duplicate_part_numbers_fail(self):
        lines = [line for line in samples_lines() if "series=edit_kind " not in line]
        lines += [f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=1/2 n=4 values=update,put",
                  f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=1/2 n=4 values=update,put"]
        self.assertFails([index_line()] + lines, "duplicate part numbers")

    def test_a_short_part_before_the_last_fails(self):
        lines = [line for line in samples_lines() if "series=edit_kind " not in line]
        lines += [f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=1/2 n=4 values=update,put",
                  f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind part=2/2 n=4 values=update,put"]
        self.assertFails([index_line()] + lines, "part sizes [2, 2]")

    def test_a_nan_phase_time_fails(self):  # finding 5
        line = index_line(starts="open:0.0,index:nan,pause:2.0,queries:3.0,edits:3.5,close:4.0,end:4.1")
        self.assertFails([line] + samples_lines(), "not finite and non-negative: index:nan")

    def test_a_negative_phase_time_fails(self):
        line = index_line(starts="open:0.0,index:1.0,pause:2.0,queries:3.0,edits:-2.0,close:4.0,end:4.1")
        self.assertFails([line] + samples_lines(), "not finite and non-negative: edits:-2.0")

    def test_an_open_mark_other_than_zero_fails(self):  # finding 7, now fixed in the tests
        line = index_line(starts="open:0.1,index:1.0,pause:2.0,queries:3.0,edits:3.5,close:4.0,end:4.1")
        self.assertFails([line] + samples_lines(), "does not start at open:0.0")

    def test_an_odd_edit_count_passes(self):  # finding 6
        edits = (
            "edits=3 edit_write_p50_ms=3.0 edit_write_p95_ms=4.0 edit_save_p50_ms=17.0 edit_save_p95_ms=26.0 "
            "edit_save_n_le64=3 edit_save_le64_p50_ms=17.0 edit_save_le64_p95_ms=26.0 edit_total_p50_ms=20.0 "
            "edit_total_p95_ms=30.0 edit_total_n_le64=3 edit_total_le64_p50_ms=20.0 edit_total_le64_p95_ms=30.0"
        )
        series = dict(SERIES, edit_total_ms="10.0,20.0,30.0", edit_save_ms="8.0,17.0,26.0", edit_write_ms="2.0,3.0,4.0",
                      edit_tokens="10,20,30", edit_kind="update,put,update")
        line = index_line(config="corpus=mixed+notes=8+model=bge-fp32+edits=3+queries=4+pause=1", edits=edits)
        self.assertPasses([line] + samples_lines(series))

    def test_kinds_must_alternate_from_update(self):
        series = dict(SERIES, edit_kind="update,update,put,put")
        self.assertFails([index_line()] + samples_lines(series), "not alternating from update")

    def test_a_missing_series_fails(self):
        lines = [line for line in samples_lines() if "series=query_hybrid_ms " not in line]
        self.assertFails([index_line()] + lines, "samples query_hybrid_ms: missing")

    def test_samples_of_another_run_fail(self):
        lines = samples_lines() + samples_lines({"query_embed_ms": "1.0"}, epoch="1794505237999")
        self.assertFails([index_line()] + lines, "belong to no index line")

    def test_a_phase_that_ran_without_a_reading_fails(self):
        line = index_line(mem=MEM_INDEX.replace("mem_close_peak_kb=150", "mem_close_peak_kb=none"))
        self.assertFails([line] + samples_lines(), "mem_close_peak_kb=none for a phase that ran")

    def test_the_lifecycle_peak_must_be_the_highest_reading(self):
        line = index_line(mem=MEM_INDEX.replace("mem_after_index_kb=380", "mem_after_index_kb=420"))
        self.assertFails([line] + samples_lines(), "mem_after_index_kb=420 exceeds mem_lifecycle_peak_kb=410")

    def test_malformed_samples_lines_fail_without_a_crash(self):
        lines = samples_lines() + [f"[kvid-index-samples] open_epoch_ms={EPOCH} series=edit_kind values=put"]
        self.assertFails([index_line()] + lines, "malformed samples line")


if __name__ == "__main__":
    unittest.main()
