"""Failure-oriented offline checks of the development website observer."""
import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ai"))
import build_quality_review_page as page
import comparable_evaluation as comparable
import evaluate_agent as base
import evaluate_development as ev
import quality_review as quality

JOB_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
JOB_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
JOB_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
STAMP = "2026-10-29T00:00:00Z"
TOKEN = "fixture-credential-1234567890"


def job(status="SUCCEEDED", job_id=JOB_A, answer="Observed answer"):
    value = {"jobId": job_id, "status": status}
    if status in {"SUCCEEDED", "FAILED"}:
        value["result"] = {"answer": answer, "queryPlan": {"intent": "search", "languages": ["en"]},
                           "trace": [{"kind": "planning", "name": "candidate-relevance", "status": "success", "durationMs": 1, "timestamp": STAMP},
                                     {"kind": "retrieval", "name": "bge-m3", "status": "success", "durationMs": 2, "timestamp": STAMP}],
                           "evidence": [{"tool": "queryCatalog", "queriedAt": STAMP,
                                         "movies": [{"id": "film-1", "titleCn": "Observed film", "year": 2014, "runtimeMinutes": 100, "ratingSource": "imdb", "doubanScore": 8.0, "imdbScore": None, "regions": ["CN"]}],
                                         "unverifiedConditions": ["ending"], "retrieval": {"mode": "rrf"}, "relevanceChecked": True}]}
    return value


class Clock:
    def __init__(self):
        self.now = 0
        self.sleeps = []
    def time(self):
        return self.now
    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class Client:
    token = TOKEN
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []
    def call(self, path, body=None):
        self.calls.append((path, copy.deepcopy(body)))
        result = self.replies.pop(0)
        if isinstance(result, Exception):
            raise result
        return copy.deepcopy(result)


class DevelopmentEvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = comparable.read_json(ev.DEFAULT_DATASET)
        cls.manifest = comparable.read_json(ev.DEFAULT_DATASET.with_name("freeze.json"))

    def run_case(self, replies, indices=(0,), **kwargs):
        client, clock = Client(replies), Clock()
        report, observations = ev.evaluate(client, self.dataset, [self.dataset["cases"][i] for i in indices],
                                            sleep=clock.sleep, monotonic=clock.time,
                                            emit=lambda *args, **kw: None, **kwargs)
        return report, observations, client, clock

    def test_frozen_development_data_is_accepted_without_legacy_runner(self):
        ev.validate_dataset(self.dataset)
        comparable.verify_freeze(self.dataset, self.manifest)
        self.assertEqual(15, sum(len(case["turns"]) for case in self.dataset["cases"]))

    def test_rubric_change_breaks_freeze(self):
        changed = copy.deepcopy(self.dataset)
        changed["cases"][0]["turns"][0]["reviewRubric"]["hardConditions"].append("changed")
        with self.assertRaises(base.EvaluationError):
            comparable.verify_freeze(changed, self.manifest)

    def test_non_development_and_automatic_labels_are_rejected(self):
        for field, value in (("split", "regression"), ("split", "holdout")):
            data = copy.deepcopy(self.dataset)
            data["cases"][0][field] = value
            with self.assertRaises(base.EvaluationError):
                ev.validate_dataset(data)
        data = copy.deepcopy(self.dataset)
        data["cases"][0]["turns"][0]["expect"] = {"requiredTools": ["searchMovies"]}
        with self.assertRaises(base.EvaluationError):
            ev.validate_dataset(data)

    def test_unknown_request_fields_and_wrong_history_policy_are_rejected(self):
        for mutate in (lambda turn: turn["request"].update({"history": []}), lambda turn: turn.update({"useHistory": True})):
            data = copy.deepcopy(self.dataset)
            mutate(data["cases"][0]["turns"][0])
            with self.assertRaises(base.EvaluationError):
                ev.validate_dataset(data)

    def test_message_limit_uses_utf16_units(self):
        data = copy.deepcopy(self.dataset)
        data["cases"][0]["turns"][0]["request"]["message"] = "🙂" * 501
        with self.assertRaises(base.EvaluationError):
            ev.validate_dataset(data)

    def test_plan_has_no_network_or_credential_loading(self):
        with patch.object(ev.website.WebsiteClient, "call", side_effect=AssertionError("network")), patch.object(ev.website, "load_session", side_effect=AssertionError("credential")), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, ev.main([]))
        self.assertEqual(15, json.loads(output.getvalue())["plannedTurns"])

    def test_browser_submission_and_new_trace_are_retained(self):
        report, observations, client, _ = self.run_case([job()])
        body = client.calls[0][1]
        self.assertEqual({"message", "history", "requestKey"}, set(body))
        self.assertRegex(body["requestKey"], r"^[a-f0-9-]{36}$")
        self.assertEqual([], body["history"])
        self.assertTrue(report["rows"][0]["passed"])
        self.assertEqual("structured-query", report["rows"][0]["route"])
        self.assertEqual(["planning", "retrieval"], [step["kind"] for step in report["rows"][0]["trace"]])
        self.assertEqual("search", observations[0]["queryPlan"]["intent"])
        self.assertEqual(8.0, observations[0]["movies"][0]["doubanScore"])
        self.assertIsNone(observations[0]["movies"][0]["imdbScore"])
        self.assertEqual(["CN"], observations[0]["movies"][0]["regions"])
        self.assertEqual(["ending"], observations[0]["evidence"][0]["unverifiedConditions"])
        self.assertIsNone(report["summary"]["tokens"])

    def test_direct_route_does_not_require_model_trace(self):
        value = job()
        value["result"].pop("queryPlan")
        value["result"]["catalog"] = {"kind": "search"}
        value["result"]["trace"] = []
        value["result"]["evidence"][0]["tool"] = "naturalSearchMovies"
        report, _, _, _ = self.run_case([value])
        self.assertEqual("direct-catalog", report["rows"][0]["route"])
        self.assertTrue(report["rows"][0]["passed"])

    def test_web_evidence_is_allowed_and_urls_are_redacted(self):
        value = job()
        value["result"]["evidence"].append({"tool": "searchMovieWeb", "queriedAt": STAMP,
                                            "webResults": [{"title": "Source", "url": "https://example.test/private?secret=1", "snippet": "Observed source"}]})
        report, observations, _, _ = self.run_case([value])
        self.assertTrue(report["rows"][0]["passed"])
        self.assertEqual("[link omitted]", observations[0]["evidence"][1]["webResults"][0]["url"])

    def test_polling_submits_once_and_preserves_job_identity(self):
        report, observations, client, _ = self.run_case([job("PENDING"), job("RUNNING"), job()])
        self.assertTrue(report["completedAllTurns"])
        self.assertEqual(1, sum(path == "/api/ai/query" for path, _ in client.calls))
        self.assertEqual(2, sum(path == "/api/ai/jobs/" + JOB_A for path, _ in client.calls))
        self.assertEqual(1, len(observations))

    def test_changed_job_identity_stops_without_resubmission(self):
        report, _, client, _ = self.run_case([job("PENDING"), job(job_id=JOB_B)], indices=(0, 1))
        self.assertFalse(report["completedAllTurns"])
        self.assertFalse(report["rows"][0]["executed"])
        self.assertFalse(report["rows"][1]["submissionAttempted"])
        self.assertEqual(2, len(client.calls))

    def test_followup_uses_prior_job_and_history_without_cross_case_leakage(self):
        report, _, client, clock = self.run_case([job(), job(job_id=JOB_B), job(job_id=JOB_C)], indices=(2, 0))
        submissions = [body for path, body in client.calls if path == "/api/ai/query"]
        self.assertEqual(JOB_A, submissions[1]["previousJobId"])
        self.assertEqual(2, len(submissions[1]["history"]))
        self.assertIn("片库 ID: film-1", submissions[1]["history"][1]["content"])
        self.assertNotIn("previousJobId", submissions[2])
        self.assertEqual([], submissions[2]["history"])
        self.assertEqual(3, len({body["requestKey"] for body in submissions}))
        self.assertEqual([11, 11], clock.sleeps)
        self.assertTrue(report["completedAllTurns"])

    def test_distinct_submissions_cannot_silently_reuse_same_job(self):
        report, observations, client, _ = self.run_case([job(), job()], indices=(2, 0))
        self.assertEqual(2, len(client.calls))
        self.assertEqual(1, len(observations))
        self.assertFalse(report["rows"][1]["executed"])
        self.assertFalse(report["rows"][2]["submissionAttempted"])
        self.assertEqual(1, report["summary"]["uncertainCompletionTurns"])

    def test_backend_failure_code_and_partial_status_remain_observable(self):
        value = job("FAILED")
        value["result"].update({"error": {"code": "MODEL_TIMEOUT"}, "partial": True})
        report, observations, _, _ = self.run_case([value])
        self.assertEqual("MODEL_TIMEOUT", report["rows"][0]["errorCode"])
        self.assertTrue(report["rows"][0]["partial"])
        self.assertTrue(observations[0]["partial"])

    def test_expired_previous_job_is_omitted_but_text_history_is_retained(self):
        _, _, client, _ = self.run_case([job(), job(job_id=JOB_B)], indices=(2,), min_interval=841)
        self.assertNotIn("previousJobId", client.calls[1][1])
        self.assertEqual(2, len(client.calls[1][1]["history"]))

    def test_history_uses_utf16_budget_and_does_not_create_lone_surrogates(self):
        result = job()["result"]
        result["answer"] = "a" + "🙂" * 4000
        history = ev.history_for([{"message": "q", "result": result}], "new")
        self.assertLessEqual(ev.utf16_length(history[1]["content"]), 6000)
        history[1]["content"].encode("utf-8")
        prior = [{"message": "q", "result": result} for _ in range(4)]
        history = ev.history_for(prior, "🙂" * 500)
        self.assertLessEqual(sum(ev.utf16_length(x["content"]) for x in history) + 1000, 10000)
        self.assertEqual(2, len(history))

    def test_failed_job_with_answer_remains_reviewable_and_next_case_runs(self):
        report, observations, client, _ = self.run_case([job("FAILED"), job(job_id=JOB_B)], indices=(2, 0))
        self.assertEqual(2, len(observations))
        self.assertEqual(1, report["summary"]["mechanicalFailures"])
        self.assertEqual(1, report["summary"]["notExecutedTurns"])
        self.assertEqual(2, len(client.calls))
        template = quality.review_template(report)
        summary = quality.apply_review(report, template)
        self.assertEqual(2, summary["humanPendingAnswers"])
        self.assertEqual(0, summary["humanReviewedAnswers"])
        self.assertTrue(quality.can_score(report["rows"][0]))
        payload = page.build_payload(report, self.dataset, {"reportSha256": base.digest(report), "observations": observations})
        self.assertEqual(2, len(payload["observations"]))

    def test_mechanical_timestamp_failure_does_not_remove_browser_success_history(self):
        value = job()
        value["result"]["evidence"][0]["queriedAt"] = "invalid"
        report, observations, client, _ = self.run_case([value, job(job_id=JOB_B)], indices=(2,))
        self.assertFalse(report["rows"][0]["passed"])
        self.assertTrue(report["rows"][1]["executed"])
        self.assertEqual(JOB_A, client.calls[1][1]["previousJobId"])
        self.assertEqual(client.calls[1][1]["history"], observations[1]["submittedHistory"])

    def test_malformed_trace_still_preserves_failed_answer_and_stops(self):
        value = job("FAILED")
        value["result"]["trace"][0]["kind"] = ["malformed"]
        report, observations, client, _ = self.run_case([value], indices=(0, 1))
        self.assertFalse(report["rows"][0]["passed"])
        self.assertEqual(1, len(observations))
        self.assertTrue(quality.can_score(report["rows"][0]))
        self.assertFalse(report["rows"][1]["submissionAttempted"])
        self.assertEqual(1, len(client.calls))

    def test_missing_private_directory_is_rejected_before_execution(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(base.EvaluationError):
                ev.output_paths(Path(folder) / "report.json", Path(folder) / "missing/private.json")

    def test_failed_job_without_result_has_no_scorable_answer(self):
        report, observations, _, _ = self.run_case([{"jobId": JOB_A, "status": "FAILED"}])
        self.assertEqual([], observations)
        self.assertTrue(report["rows"][0]["executed"])
        self.assertFalse(quality.can_score(report["rows"][0]))

    def test_empty_answer_is_not_given_a_quality_hash(self):
        report, observations, _, _ = self.run_case([job("FAILED", answer="  ")])
        self.assertEqual([], observations)
        self.assertNotIn("answerSha256", report["rows"][0])

    def test_success_with_missing_or_invalid_answer_stops_without_losing_report(self):
        for label, answer in (("missing", None), ("null", None), ("integer", 7),
                              ("object", {}), ("array", []), ("empty", ""), ("blank", "  ")):
            with self.subTest(answer=label):
                value = job(answer=answer)
                if label == "missing":
                    value["result"].pop("answer")
                report, observations, client, _ = self.run_case([value], indices=(2, 0))
                self.assertEqual(3, len(report["rows"]))
                first = report["rows"][0]
                self.assertTrue(first["executed"])
                self.assertTrue(first["submissionAttempted"])
                self.assertFalse(first["checks"]["answerContract"])
                self.assertEqual("WEBSITE_MECHANICAL_CHECK_FAILED", first["failure"])
                self.assertFalse(quality.can_score(first))
                self.assertEqual([], observations)
                self.assertEqual(1, len(client.calls))
                self.assertTrue(all(not row["submissionAttempted"] for row in report["rows"][1:]))
                self.assertEqual(1, report["summary"]["mechanicalFailures"])
                self.assertEqual(0, report["summary"]["uncertainCompletionTurns"])
                quality.apply_review(report, quality.review_template(report))

    def test_failed_empty_answer_skips_followup_but_keeps_next_case(self):
        report, observations, client, _ = self.run_case([job("FAILED", answer=""), job(job_id=JOB_B)], indices=(2, 0))
        self.assertTrue(report["rows"][0]["checks"]["answerContract"])
        self.assertFalse(report["rows"][1]["submissionAttempted"])
        self.assertTrue(report["rows"][2]["executed"])
        self.assertEqual(2, len(client.calls))
        self.assertEqual(1, len(observations))

    def test_history_preparation_failure_preserves_prior_observation_without_post(self):
        with patch.object(ev, "history_for", side_effect=[[], TypeError("malformed history " + TOKEN)]):
            report, observations, client, _ = self.run_case([job()], indices=(2, 0))
        self.assertEqual(3, len(report["rows"]))
        self.assertTrue(report["rows"][0]["executed"])
        failed = report["rows"][1]
        self.assertEqual("WEBSITE_REQUEST_PREPARATION_FAILED", failed["failure"])
        self.assertFalse(failed["submissionAttempted"])
        self.assertFalse(failed["executed"])
        self.assertFalse(report["rows"][2]["submissionAttempted"])
        self.assertEqual(1, len(client.calls))
        self.assertEqual(1, len(observations))
        self.assertEqual(0, report["summary"]["uncertainCompletionTurns"])
        self.assertNotIn(TOKEN, json.dumps(report))
        payload = page.build_payload(report, self.dataset, {"reportSha256": base.digest(report), "observations": observations})
        self.assertEqual(1, len(payload["observations"]))

    def test_submission_transport_failure_stops_all_turns_and_repetitions(self):
        report, observations, client, _ = self.run_case([base.EvaluationError("transport " + TOKEN)], indices=(2, 0), repeat=2)
        self.assertEqual(1, len(client.calls))
        self.assertEqual(6, report["summary"]["notExecutedTurns"])
        self.assertEqual(0, report["summary"]["mechanicalFailures"])
        self.assertEqual([], observations)
        self.assertNotIn(TOKEN, json.dumps(report))

    def test_poll_transport_failure_retains_attempt_and_stops(self):
        report, _, client, _ = self.run_case([job("PENDING"), OSError("broken")], indices=(0, 1))
        self.assertEqual(JOB_A, report["rows"][0]["jobId"])
        self.assertFalse(report["rows"][0]["executed"])
        self.assertEqual(2, len(client.calls))

    def test_poll_deadline_does_not_resubmit(self):
        report, _, client, clock = self.run_case([job("PENDING")], indices=(0, 1), job_timeout=2, poll_interval=2.5)
        self.assertEqual(1, len(client.calls))
        self.assertEqual([2], clock.sleeps)
        self.assertFalse(report["completedAllTurns"])

    def test_unknown_tool_is_mechanical_failure_not_quality_score(self):
        value = job()
        value["result"]["evidence"][0]["tool"] = "publishContent"
        report, observations, _, _ = self.run_case([value])
        self.assertFalse(report["rows"][0]["passed"])
        self.assertEqual(1, len(observations))
        self.assertIsNone(quality.review_template(report)["reviews"][0]["scores"]["usefulness"])

    def test_unknown_or_malformed_trace_tool_retains_answer_and_stops(self):
        for name in ("publishContent", None, ["queryCatalog"]):
            with self.subTest(name=name):
                value = job()
                value["result"]["trace"].append({"kind": "tool", "name": name, "status": "success"})
                report, observations, client, _ = self.run_case([value], indices=(2, 0))
                first = report["rows"][0]
                self.assertTrue(first["executed"])
                self.assertFalse(first["passed"])
                self.assertFalse(first["checks"]["toolAllowlist"])
                self.assertFalse(first["checks"]["traceContract"])
                self.assertTrue(quality.can_score(first))
                self.assertEqual(1, len(observations))
                self.assertEqual(1, len(client.calls))
                self.assertTrue(all(not row["submissionAttempted"] for row in report["rows"][1:]))
                self.assertIsNone(quality.review_template(report)["reviews"][0]["scores"]["usefulness"])

    def test_authorized_trace_tool_names_match_current_website_contract(self):
        value = job()
        value["result"]["trace"].extend({"kind": "tool", "name": name, "status": "success"} for name in sorted(ev.TOOLS))
        report, _, _, _ = self.run_case([value])
        self.assertTrue(report["rows"][0]["checks"]["toolAllowlist"])
        self.assertTrue(report["rows"][0]["checks"]["traceContract"])
        self.assertTrue(report["rows"][0]["passed"])

    def test_redaction_covers_answer_trace_plan_nested_evidence_and_sharing_fields(self):
        value = job(answer=TOKEN + " https://private.test/path")
        result = value["result"]
        result["trace"][0]["name"] = TOKEN
        result["queryPlan"]["description"] = TOKEN
        result["queryPlan"]["password"] = "private-password"
        result["evidence"][0]["movies"][0]["summary"] = TOKEN + " magnet:?private"
        result["evidence"][0]["resources"] = [{"url": "https://share.test/private", "code": "private-code"}]
        report, observations, _, _ = self.run_case([value])
        text = json.dumps([report, observations])
        for secret in (TOKEN, "private-password", "private-code", "https://", "magnet:"):
            self.assertNotIn(secret, text)
        self.assertNotIn("Observed answer", json.dumps(report))
        self.assertEqual(base.digest(result["answer"]), observations[0]["answerSha256"])
        self.assertEqual(base.digest(observations[0]["answer"]), observations[0]["sanitizedAnswerSha256"])

    def test_existing_or_alias_outputs_are_rejected_before_credentials(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(ev.website, "load_session", side_effect=AssertionError("credential")):
            report = Path(folder) / "report.json"
            report.write_text("{}")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, ev.main(["--run", "--output", str(report), "--observations", str(Path(folder) / "private.json"), "--session-stdin"]))
            with self.assertRaises(base.EvaluationError):
                ev.output_paths(Path(folder) / "same.json", Path(folder) / "sub/../same.json")

    def test_repository_observations_and_session_collision_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(base.EvaluationError):
                ev.output_paths(Path(folder) / "report.json", base.ROOT / "tmp/development-new-private.json")
            with self.assertRaises(base.EvaluationError):
                ev.output_paths(Path(folder) / "session.json", Path(folder) / "observations.json", Path(folder) / "session.json")

    def test_http_client_keeps_read_only_endpoint_allowlist(self):
        client = ev.website.WebsiteClient("http://127.0.0.1:8880", TOKEN)
        for path in ("/api/auth/login", "/api/admin/users", "/api/internal/ai/claim", "/api/resources/admin/1/repair-invalid"):
            with self.assertRaises(base.EvaluationError):
                client.call(path)

    def test_preflight_requires_existing_user_and_idle_connected_worker(self):
        replies = [({"role": "ADMIN"}, None), ({"role": "USER"}, {"catalogAvailable": True, "available": False, "busy": False}),
                   ({"role": "USER"}, {"catalogAvailable": True, "available": True, "busy": True})]
        for me, status in replies:
            with self.subTest(me=me, status=status), tempfile.TemporaryDirectory() as folder:
                client = Client([me, status])
                with patch.object(ev.website, "load_session", return_value=TOKEN), patch.object(ev.website, "WebsiteClient", return_value=client), patch.object(ev, "evaluate", side_effect=AssertionError("inference")), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, ev.main(["--run", "--session-stdin", "--output", str(Path(folder) / "report.json"), "--observations", str(Path(folder) / "private.json")]))

    def test_cli_writes_bound_outputs_and_no_inferred_scores(self):
        with tempfile.TemporaryDirectory() as folder:
            report_file, private = Path(folder) / "report.json", Path(folder) / "private.json"
            client = Client([{"role": "USER"}, {"catalogAvailable": True, "available": True, "busy": False}, job()])
            with patch.object(ev.website, "load_session", return_value=TOKEN), patch.object(ev.website, "WebsiteClient", return_value=client), contextlib.redirect_stdout(io.StringIO()):
                result = ev.main(["--run", "--case", "dev-v3-01", "--session-stdin", "--output", str(report_file), "--observations", str(private)])
            self.assertEqual(0, result)
            report, observations = comparable.read_json(report_file), comparable.read_json(private)
            self.assertEqual(base.digest(report), observations["reportSha256"])
            self.assertEqual({"evaluate_agent.py", "evaluate_website.py", "comparable_evaluation.py"}, set(report["dependencySha256"]))
            self.assertEqual(1, quality.apply_review(report, quality.review_template(report))["humanPendingAnswers"])


if __name__ == "__main__":
    unittest.main()
