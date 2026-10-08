"""No writes or raw payloads: verify real progress, not just SUCCEEDED labels."""
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("p2p_progress", ROOT / "tools/inspect_p2p_progress.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
SINCE = "2026-10-08T17:28:00+08:00"
MARKER = "sensitive-fixture-material"


def batch(**overrides):
    return dict({"kind": "metadata", "id": 101, "source": "CSCORE_MOVIE", "status": "SUCCEEDED",
                 "page": 5, "offset": 40, "start_page": 1, "end_page": 15, "page_size": 48,
                 "attempted": 8, "failed": 0, "deferred": 0, "next_page": 6, "next_offset": 0,
                 "retry_ids": [], "created_at": "2026-10-08 19:01:25", "finished_at": "2026-10-08 19:02:44"}, **overrides)


def records(*extra):
    return [{"kind": "connection", "non_root": 1, "read_only": 1, "utc_offset_seconds": 28800,
             "observed_at": "2026-10-08 19:10:00"},
            {"kind": "identity", "collation": "utf8mb4_bin"}, *extra]


class CursorTest(unittest.TestCase):
    def evidence(self, row, retries=None):
        return tool.cursor_evidence(row, retries or {})

    def test_last_items_advance_to_next_page(self):
        result = self.evidence(batch())
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["from"], {"page": 5, "offset": 40})
        self.assertEqual(result["to"], {"page": 6, "offset": 0})

    def test_same_page_partial_batch_advances_offset(self):
        row = batch(page=1, offset=0, attempted=20, next_page=1, next_offset=20)
        self.assertEqual(self.evidence(row)["status"], "PASS")

    def test_success_without_saved_next_cursor_is_not_accepted(self):
        row = batch()
        del row["next_page"]
        self.assertEqual(self.evidence(row)["reason"], "succeeded_without_next_cursor")

    def test_old_partial_success_cannot_hide_an_undeferred_failure(self):
        row = batch(failed=1, deferred=None, next_page=None, next_offset=None)
        self.assertEqual(self.evidence(row)["status"], "FAIL")
        self.assertEqual(self.evidence(row)["reason"], "failed_items_not_durably_deferred")

    def test_wrong_cursor_or_no_work_is_not_progress(self):
        for row in (batch(next_page=8), batch(page=1, offset=0, attempted=0, next_page=1, next_offset=0)):
            self.assertEqual(self.evidence(row)["status"], "FAIL")

    def test_single_page_and_empty_page_cycles_are_valid(self):
        for row in (batch(page=1, start_page=1, end_page=1, next_page=1),
                    batch(page=1, offset=0, page_size=0, attempted=0, next_page=1)):
            result = self.evidence(row)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["reason"], "range_cycle_completed")

    def test_pending_and_absent_batches_are_unknown(self):
        self.assertEqual(self.evidence(batch(status="RUNNING"))["status"], "UNKNOWN")
        report = tool.build_report(records(batch()), SINCE)
        missing = next(c for c in report["cursors"] if c["source"] == "CSCORE_TV")
        self.assertEqual(missing["status"], "UNKNOWN")

    def test_malformed_numbers_and_bounds_are_unknown(self):
        for row in (batch(page=True), batch(page=501), batch(attempted=21), batch(failed=9), batch(page_size=MARKER)):
            self.assertEqual(self.evidence(row)["status"], "UNKNOWN")

    def test_attempted_work_cannot_exceed_observed_page(self):
        for row in (batch(attempted=9), batch(page_size=0, attempted=1), batch(offset=50, attempted=1)):
            self.assertEqual(self.evidence(row)["status"], "UNKNOWN")

    def test_malformed_empty_retry_references_are_not_treated_as_absent(self):
        for value in (0, False, {}, ""):
            self.assertEqual(self.evidence(batch(retry_ids=value))["status"], "UNKNOWN")

    def test_isolated_failure_requires_matching_durable_checkpoint(self):
        row = batch(failed=1, deferred=1, retry_ids=[501])
        self.assertEqual(self.evidence(row)["status"], "UNKNOWN")
        result = self.evidence(row, {501: {"status": "PENDING"}})
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["retry_states"], ["PENDING"])

    def test_duplicate_or_malformed_retry_references_are_unknown(self):
        for ids in ([501, 501], [501, MARKER], "501,502"):
            self.assertEqual(self.evidence(batch(failed=2, deferred=2, retry_ids=ids))["status"], "UNKNOWN")

    def test_raw_credentials_errors_titles_and_urls_are_not_reported(self):
        row = batch(last_error=MARKER, title=MARKER, url=MARKER, payload=MARKER)
        self.assertNotIn(MARKER, json.dumps(self.evidence(row)))


class ReportTest(unittest.TestCase):
    def test_no_natural_failure_keeps_live_isolation_unverified(self):
        report = tool.build_report(records(batch()), SINCE)
        check = next(c for c in report["checks"] if c["check"] == "live_failure_isolation")
        self.assertEqual(check["status"], "UNKNOWN")

    def test_real_isolation_fixture_does_not_mean_retry_succeeded(self):
        data = records(batch(failed=1, deferred=1, retry_ids=[501]),
                       {"kind": "retry", "id": 501, "status": "PENDING", "attempts": 0, "max_attempts": 3})
        report = tool.build_report(data, SINCE, ("CSCORE_MOVIE",))
        check = next(c for c in report["checks"] if c["check"] == "live_failure_isolation")
        self.assertEqual(check["status"], "PASS")
        self.assertEqual(report["cursors"][0]["retry_states"], ["PENDING"])

    def test_duplicate_retry_evidence_never_passes(self):
        retry = {"kind": "retry", "id": 501, "status": "PENDING", "attempts": 0, "max_attempts": 3}
        report = tool.build_report(records(batch(failed=1, deferred=1, retry_ids=[501]), retry, retry), SINCE)
        self.assertEqual(report["cursors"][0]["status"], "UNKNOWN")

    def test_bad_connection_and_case_insensitive_identity_are_not_accepted(self):
        for field, value in (("non_root", 0), ("read_only", 0), ("utc_offset_seconds", 0)):
            data = records()
            data[0][field] = value
            self.assertEqual(tool.build_report(data, SINCE)["checks"][0]["status"], "FAIL")
        data = records()
        data[1]["collation"] = "utf8mb4_unicode_ci"
        self.assertEqual(tool.build_report(data, SINCE)["checks"][1]["status"], "FAIL")

    def test_reports_only_bounded_aggregate_fields(self):
        data = records({"kind": "archive_summary", "provider": "XUNLEI", "status": "FAILED", "count": 54,
                        "new_count": 0, "historical_exhausted": 54, "cookie": MARKER})
        report = tool.build_report(data, SINCE)
        self.assertEqual(report["archive_summary"][0]["historical_exhausted"], 54)
        self.assertNotIn(MARKER, json.dumps(report))


class DatabaseBoundaryTest(unittest.TestCase):
    def test_query_is_fixed_allowlisted_and_read_only(self):
        sql = tool.snapshot_sql(SINCE, tool.SOURCES[:3])
        self.assertTrue(sql.startswith("SET SESSION TRANSACTION READ ONLY;"))
        self.assertIn("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;", sql)
        self.assertTrue(sql.endswith("ROLLBACK;"))
        for word in ("UPDATE ", "DELETE ", "INSERT ", "ALTER ", "DROP ", "CALL ", "INTO OUTFILE", "last_error", "password"):
            self.assertNotIn(word.lower(), sql.lower())
        self.assertIn("position_rank<=3", sql)
        self.assertIn("LIMIT 1000", sql)

    def test_injected_source_since_or_database_is_rejected(self):
        with self.assertRaises(ValueError):
            tool.snapshot_sql(SINCE, ("CSCORE_MOVIE'; DELETE FROM x",))
        with self.assertRaises(ValueError):
            tool.snapshot_sql("2026-10-08'; DELETE FROM x", tool.SOURCES[:3])
        with self.assertRaises(ValueError):
            tool.read_snapshot("mysql", "missing", "gying;DROP", "localhost", 3306, "SELECT 1")

    def test_mysql_client_receives_no_password_and_disables_reconnect(self):
        with tempfile.TemporaryDirectory() as tmp:
            defaults = Path(tmp) / "client.cnf"
            defaults.write_text("[client]\nuser=fixture\n", encoding="utf-8")
            response = subprocess.CompletedProcess([], 0, json.dumps(records()[0]), "")
            with patch.dict(os.environ, {"MYSQL_PWD": MARKER}), patch.object(tool.subprocess, "run", return_value=response) as run:
                tool.read_snapshot("mysql", defaults, "gying", "127.0.0.1", 3306, tool.snapshot_sql(SINCE, tool.SOURCES[:3]))
            command = run.call_args.args[0]
            self.assertTrue(command[1].startswith("--defaults-file="))
            self.assertIn("--skip-reconnect", command)
            self.assertIn("--skip-force", command)
            self.assertIn("--init-command=SET SESSION TRANSACTION READ ONLY", command)
            self.assertNotIn(MARKER, " ".join(command))
            self.assertNotIn("MYSQL_PWD", run.call_args.kwargs["env"])

    def test_failure_diagnostics_and_partial_outputs_never_escape_cli(self):
        for error in (ValueError(MARKER), subprocess.TimeoutExpired(MARKER, 1, output=MARKER)):
            output = io.StringIO()
            with patch.object(tool, "read_snapshot", side_effect=error), contextlib.redirect_stdout(output):
                code = tool.main(["--defaults-file", "fixture.cnf", "--since", SINCE])
            self.assertEqual(code, 1)
            self.assertNotIn(MARKER, output.getvalue())
            self.assertEqual(json.loads(output.getvalue())["summary"]["UNKNOWN"], 1)

    def test_time_window_uses_client_timezone(self):
        self.assertEqual(tool.parse_since("2026-10-08T09:28:00Z").isoformat(), SINCE)
        self.assertEqual(tool.parse_since("2026-10-08T17:28:00").isoformat(), SINCE)


if __name__ == "__main__":
    unittest.main()
