"""Offline behavioral tests for grading, transport restrictions and multi-turn evidence."""
import contextlib
import copy
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError

MODULE_PATH = Path(__file__).resolve().parents[1] / "ai/evaluate_agent.py"
SPEC = importlib.util.spec_from_file_location("agent_evaluation", MODULE_PATH)
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


def result(title="星际穿越", year=2014, tool="searchMovies"):
    data = {"queriedAt": "2026-10-22T00:00:00Z", "total": 1,
            "movies": [{"id": "film1", "titleCn": title, "year": year}]}
    return {"scope": "gying", "status": "completed", "answer": "片库返回的影片",
            "requestId": "fixture-request", "trace": [
                {"kind": "model", "name": "fixture-model", "status": "success", "durationMs": 1},
                {"kind": "tool", "name": tool, "status": "success", "durationMs": 1,
                 "arguments": {"private": "do-not-export"}, "result": {"ok": True, "data": data}}]}


class AgentEvaluationTests(unittest.TestCase):
    def test_plan_performs_no_requests_or_report_write(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with patch.object(evaluation.AssistantClient, "call", side_effect=AssertionError("network")):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, evaluation.main(["--output", str(output)]))
            self.assertFalse(output.exists())

    def test_only_explicit_loopback_origins_are_accepted(self):
        for origin in ("http://127.0.0.1:5000", "http://[::1]:5000/"):
            self.assertTrue(evaluation.checked_origin(origin))
        for origin in ("https://example.com:5000", "http://localhost:5000", "http://127.0.0.1",
                       "http://127.0.0.1:5000/api", "http://user:pass@127.0.0.1:5000",
                       "http://127.0.0.1:5000?secret=1", "http://127.0.0.1:5000#x", "http://127.0.0.1:x"):
            with self.subTest(origin=origin), self.assertRaises(evaluation.EvaluationError):
                evaluation.checked_origin(origin)

    def test_wrong_movie_year_and_failed_tool_do_not_pass(self):
        expected = {"requiredTools": ["searchMovies"], "movieTitle": "星际穿越", "year": 2014}
        self.assertTrue(all(evaluation.grade(result(), expected, 200).values()))
        self.assertFalse(evaluation.grade(result(title="另一部影片"), expected, 200)["expectedMovieEvidence"])
        self.assertFalse(evaluation.grade(result(year=2020), expected, 200)["expectedYearEvidence"])
        failed = result()
        failed["trace"][1]["result"] = {"ok": False, "error": {"code": "GYING_TIMEOUT"}}
        checks = evaluation.grade(failed, expected, 200)
        self.assertFalse(checks["allToolsSucceeded"])
        self.assertFalse(checks["queryTimestamps"])

    def test_successful_prose_without_tool_evidence_fails(self):
        output = result()
        output["trace"] = []
        self.assertFalse(all(evaluation.grade(output, {"requiredTools": ["searchMovies"]}, 200).values()))
        self.assertFalse(evaluation.grade(result(), {}, 500)["businessSuccess"])

    def test_budget_and_tool_allowlist_are_enforced(self):
        self.assertFalse(evaluation.grade(result(tool="executeCommand"), {}, 200)["toolAllowlist"])
        output = result()
        output["trace"].extend([copy.deepcopy(output["trace"][1])] * 4)
        self.assertFalse(evaluation.grade(output, {"maxToolCalls": 4}, 200)["toolBudget"])

    def test_error_expectations_require_no_answer_tools_or_model(self):
        # Invalid requests can fail validation before a scope is assigned.
        output = {"status": "error", "answer": "", "trace": [], "error": {"code": "INVALID_REQUEST"}}
        expected = {"errorCode": "INVALID_REQUEST", "noModel": True, "maxToolCalls": 0}
        self.assertTrue(all(evaluation.grade(output, expected, 400).values()))
        output["trace"] = result()["trace"]
        checks = evaluation.grade(output, expected, 400)
        self.assertFalse(checks["noToolOnRejection"])
        self.assertFalse(checks["noModelOnRejection"])

    def test_java_nanosecond_timestamps_work_on_python_310(self):
        for value in ("2026-10-22T15:00:00.123456789Z", "2026-10-22T23:00:00.123456789+08:00",
                      "2026-10-22T15:00:00Z", "2026-10-22T15:00:00.123Z"):
            self.assertTrue(evaluation.valid_timestamp(value), value)
        for value in ("not-a-date", "2026-10-22T15:00:00", None):
            self.assertFalse(evaluation.valid_timestamp(value))

    def test_web_checks_require_actual_excerpt_and_source_in_answer(self):
        output = result(tool="searchMovieWeb")
        data = output["trace"][1]["result"]["data"]
        data["webResults"] = [{"snippet": "实际摘录", "url": "https://zh.wikipedia.org/wiki/Test"}]
        self.assertFalse(evaluation.grade(output, {"webCitations": True}, 200)["webExcerptAndUrl"])
        output["answer"] = "实际摘录 https://zh.wikipedia.org/wiki/Test"
        self.assertTrue(evaluation.grade(output, {"webCitations": True}, 200)["webExcerptAndUrl"])
        data["webResults"][0]["url"] = "https://example.com/forged"
        self.assertFalse(evaluation.grade(output, {"webCitations": True}, 200)["webExcerptAndUrl"])

    def test_safe_export_excludes_prompt_parameters_sources_and_answer(self):
        output = result()
        output["trace"][1]["result"]["data"]["source"] = "http://private-source/secret"
        exported = json.dumps(evaluation.safe_steps(output))
        self.assertNotIn("do-not-export", exported)
        self.assertNotIn("private-source", exported)
        self.assertNotIn(output["answer"], exported)

    def test_multi_turn_uses_prior_tool_id_and_completed_history(self):
        bodies = []
        class Client:
            def call(self, path, body):
                bodies.append(body)
                return {"code": 200, "data": result()}
        dataset = {"version": "fixture", "provenance": "agent-authored-regression"}
        cases = [{"id": "followup", "turns": [
            {"request": {"scope": "gying", "message": "find movie"}, "expect": {}},
            {"request": {"scope": "gying", "message": "details {movieId}"}, "useHistory": True, "expect": {}}]}]
        with contextlib.redirect_stdout(io.StringIO()):
            report = evaluation.evaluate(Client(), dataset, cases, 1)
        self.assertTrue(report["completedAllTurns"])
        self.assertEqual("details film1", bodies[1]["message"])
        self.assertEqual(["user", "assistant"], [item["role"] for item in bodies[1]["history"]])
        self.assertEqual(2, report["summary"]["manualReviewRequired"])
        self.assertNotIn("find movie", json.dumps(report))

    def test_transport_failure_stops_without_replay(self):
        class Client:
            calls = 0
            def call(self, path, body):
                self.calls += 1
                raise evaluation.EvaluationError("transport failure")
        client = Client()
        dataset = {"version": "fixture", "provenance": "agent-authored-regression"}
        case = {"id": "fail", "turns": [{"request": {"scope": "gying", "message": "search"}, "expect": {}}]}
        report = evaluation.evaluate(client, dataset, [case, dict(case, id="another")], 1)
        self.assertEqual(1, client.calls)
        self.assertFalse(report["completedAllTurns"])

    def test_network_failures_do_not_expose_urls_or_response_body(self):
        client = evaluation.AssistantClient("http://127.0.0.1:5000")
        with patch.object(client.opener, "open", side_effect=URLError("secret-token-and-url")):
            with self.assertRaises(evaluation.EvaluationError) as error:
                client.call("/api/assistant", {"message": "search"})
        self.assertNotIn("secret-token", str(error.exception))

    def test_redirect_and_unapproved_endpoint_are_refused(self):
        self.assertIsNone(evaluation.NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.com"))
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.AssistantClient("http://127.0.0.1:5000").call("/api/mcp/execute")

    def test_dataset_cannot_claim_independent_evaluation_or_unsafe_tool(self):
        dataset = evaluation.load_dataset(evaluation.DEFAULT_DATASET)
        for mutation in ("provenance", "tool", "duplicate", "scope"):
            changed = copy.deepcopy(dataset)
            if mutation == "provenance":
                changed["provenance"] = "independent-human-holdout"
            elif mutation == "tool":
                changed["cases"][0]["turns"][0]["expect"]["requiredTools"] = ["executeCommand"]
            elif mutation == "scope":
                changed["cases"][0]["turns"][0]["request"]["scope"] = "local"
            else:
                changed["cases"].append(changed["cases"][0])
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "cases.json"
                path.write_text(json.dumps(changed), encoding="utf-8")
                with self.subTest(mutation=mutation), self.assertRaises(evaluation.EvaluationError):
                    evaluation.load_dataset(path)

    def test_tool_discovery_fails_before_inference_if_write_tool_is_offered(self):
        def call(client, path, body=None):
            if path == "/api/health":
                return {"code": 200}
            if path == "/api/assistant/tools?scope=gying":
                return {"code": 200, "data": [{"name": "executeCommand"}]}
            self.fail("Inference should not execute")
        with patch.object(evaluation.AssistantClient, "call", call):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, evaluation.main(["--run"]))

    def test_summary_keeps_mechanical_checks_distinct_from_human_quality(self):
        summary = evaluation.summarize([{"passed": True, "durationMs": 10, "manualReviewRequired": True}, {"passed": False, "durationMs": 20}])
        self.assertEqual(1, summary["mechanicalPasses"])
        self.assertEqual(1, summary["mechanicalFailures"])
        self.assertEqual(1, summary["manualReviewRequired"])
        self.assertEqual(20, summary["p95Ms"])
        self.assertIn("not independent", summary["qualityScope"])

    def test_invalid_response_is_not_silently_treated_as_success(self):
        for response in ({"data": None}, {"code": 200, "data": None},
                         {"code": 200, "data": {"trace": None}}, {"code": 200, "data": {"trace": ["bad"]}}):
            with self.assertRaises(evaluation.EvaluationError):
                evaluation.unwrap(response)

    def test_malformed_tool_evidence_stops_as_contract_failure(self):
        for key, value in (("movies", None), ("movies", ["bad"]), ("webResults", {})):
            output = result()
            output["trace"][1]["result"]["data"][key] = value
            with self.assertRaises(evaluation.EvaluationError):
                evaluation.unwrap({"code": 200, "data": output})
        output = result()
        output["trace"][1]["result"]["ok"] = "true"
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.unwrap({"code": 200, "data": output})


if __name__ == "__main__":
    unittest.main()
