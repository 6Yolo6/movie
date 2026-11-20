"""Failure-oriented checks of website holdout without credentials or networking."""
import contextlib
import copy
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ai"))
import evaluate_agent as base
import evaluate_website as ev
import comparable_evaluation as comparable


def job(status="SUCCEEDED"):
    value = {"jobId": "fixture-job", "status": status}
    if status == "SUCCEEDED":
        value["result"] = {"answer": "fixture answer", "catalog": {"kind": "search"}, "evidence": [
            {"tool": "searchMovies", "queriedAt": "2026-10-23T00:00:00Z", "movies": [
                {"id": "fixture", "titleCn": "fixture", "year": 2014}]}], "trace": []}
    return value


def data(): return comparable.read_json(ev.DEFAULT_DATASET)


class WebsiteEvaluationTests(unittest.TestCase):
    def test_independent_questions_and_turns_preserved(self):
        d = ev.validate_dataset(data())
        self.assertEqual(30, len(d["cases"]))
        self.assertEqual(44, sum(len(c["turns"]) for c in d["cases"]))
        d["cases"][0]["turns"][0]["message"] = "changed"
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_cannot_enable_prompt_tuning_or_model_references(self):
        for field in ("promptTuningAllowed", "modelAnswersFrozenAsReference"):
            d = data(); d["policy"][field] = True
            with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_group_leakage_detected(self):
        d = data(); c = copy.deepcopy(d["cases"][0]); c["id"] = "duplicate"; c["sourceGroup"] = "different"
        d["cases"].append(c)
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_freeze_and_changes(self):
        d = data()
        with tempfile.TemporaryDirectory() as directory:
            manifest = ev.freeze(d, Path(directory) / "freeze.json")
            ev.verify_freeze(d, manifest)
            d["cases"][0]["annotation"] += " changed"
            with self.assertRaises(base.EvaluationError): ev.verify_freeze(d, manifest)

    def test_plan_has_no_network(self):
        with patch.object(ev.WebsiteClient, "call", side_effect=AssertionError("network")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, ev.main([]))

    def test_unapproved_endpoints_are_refused(self):
        client = ev.WebsiteClient("http://127.0.0.1:8880", "fixture-token")
        for path in ("/api/admin/users", "/api/internal/ai/claim", "/api/resources/admin/1/repair-invalid", "/api/auth/login"):
            with self.assertRaises(base.EvaluationError): client.call(path)

    def test_history_matches_browser_id_and_budget(self):
        h = ev.history_for([("question", job()["result"])], "new question")
        self.assertEqual(["user", "assistant"], [x["role"] for x in h])
        self.assertIn("片库 ID: fixture", h[1]["content"])
        self.assertEqual([], ev.history_for([("q" * 9000, job()["result"])], "x" * 1000))

    def test_queue_polling_submits_only_once_and_records_followup(self):
        calls = []
        class Client:
            token = "fixture-token"
            def call(self, path, body=None):
                calls.append((path, body))
                return job("PENDING") if path == "/api/ai/query" and len(calls) == 1 else job()
        d = data(); c = copy.deepcopy(d["cases"][16]); c["turns"] = c["turns"][:2]
        r, observations = ev.evaluate(Client(), d, [c], 1, sleep=lambda _: None, emit=lambda *a, **k: None, min_interval=0)
        self.assertTrue(r["completedAllTurns"])
        self.assertEqual(2, sum(path == "/api/ai/query" for path, _ in calls))
        self.assertEqual(2, len(calls[-1][1]["history"]))
        self.assertNotIn("requestKey", calls[0][1])
        self.assertNotIn("fixture answer", str(r))
        self.assertEqual(2, len(observations))

    def test_transport_failure_stops_all_without_submission_retry(self):
        class Client:
            calls = 0; token = "fixture-token"
            def call(self, path, body=None):
                self.calls += 1; raise base.EvaluationError("fixture failure")
        client = Client(); d = data()
        r, _ = ev.evaluate(client, d, [d["cases"][16]], 2, sleep=lambda _: None, emit=lambda *a, **k: None)
        self.assertEqual(1, client.calls)
        self.assertEqual(6, r["summary"]["notExecutedTurns"])
        self.assertFalse(r["completedAllTurns"])

    def test_failed_turn_skips_followups_but_runs_next_case(self):
        class Client:
            calls = 0; token = "fixture-token"
            def call(self, path, body=None):
                self.calls += 1; return job("FAILED") if self.calls == 1 else job()
        client = Client(); d = data()
        r, _ = ev.evaluate(client, d, [d["cases"][16], d["cases"][0]], 1, sleep=lambda _: None, emit=lambda *a, **k: None, min_interval=0)
        self.assertEqual(2, client.calls)
        self.assertEqual(2, r["summary"]["notExecutedTurns"])
        self.assertEqual(4, r["summary"]["plannedTurns"])

    def test_observed_answer_masks_credential_and_links(self):
        self.assertEqual("[session omitted] [link omitted]", ev.safe_answer("fixture-token https://private.example/path", "fixture-token"))

    def test_output_path_exists_prevents_session_loading(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(ev, "load_session", side_effect=AssertionError("credential")):
            p = Path(directory) / "r.json"; p.write_text("{}")
            with contextlib.redirect_stderr(io.StringIO()): self.assertEqual(2, ev.main(["--run", "--output", str(p)]))


if __name__ == "__main__": unittest.main()
