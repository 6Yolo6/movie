"""Offline failure-oriented tests for grouped evaluation and result integrity."""
import contextlib
import copy
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ai"))
import comparable_evaluation as ev
import evaluate_agent as base


def dataset():
    return copy.deepcopy(ev.load_dataset(ev.DEFAULT_DATASET))


def result(ids=("a", "b"), year=2014):
    return {"scope": "gying", "status": "completed", "answer": "星际穿越 2014", "model": "fixture", "requestId": "fixture",
            "trace": [{"kind": "model", "name": "fixture", "status": "success"},
                      {"kind": "tool", "name": "searchMovies", "arguments": {"keyword": "星际穿越", "year": year},
                       "result": {"ok": True, "data": {"queriedAt": "2026-10-23T00:00:00Z", "total": len(ids),
                                    "movies": [{"id": x, "year": year, "category": "mv", "titleCn": "星际穿越"} for x in ids]}}}]}


def graded(output, expected=None):
    expected = expected or {}
    checks = base.grade(output, expected, 200)
    return checks, ev.grade(output, expected, {}, None, checks)


def report():
    d = dataset()
    c = copy.deepcopy(d["cases"][0]); c["turns"][0]["expect"] = {}
    class Client:
        def call(self, path, body):
            return {"code": 200, "data": result()}
    with contextlib.redirect_stdout(io.StringIO()):
        r = base.evaluate(Client(), d, [c], 2, grader=ev.grade)
    ev.complete_rows(r, [c], 2)
    r.update({"reportSchemaVersion": 2, "freezeSha256": "f" * 64,
              "runtimeToolSchemaSha256": "t" * 64, "selectionSha256": "s" * 64, "evaluatorSha256": "e" * 64, "baseEvaluatorSha256": "b" * 64})
    return r


class ComparableEvaluationTests(unittest.TestCase):
    def test_plan_no_network_or_output(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(base.AssistantClient, "call", side_effect=AssertionError("network")):
            path = Path(directory) / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, ev.main(["--output", str(path)]))
            self.assertFalse(path.exists())

    def test_source_group_cannot_cross_split(self):
        d = dataset(); d["cases"][1]["split"] = "development"
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_normalized_duplicates_cannot_evade_grouping(self):
        d = dataset(); c = copy.deepcopy(d["cases"][0]); c["id"] = "duplicate"; c["sourceGroup"] = "other"
        c["turns"][0]["request"]["message"] += "！"
        d["cases"].append(c)
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_agent_authored_cannot_be_holdout(self):
        d = dataset(); d["cases"][0]["split"] = "holdout"
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)

    def test_human_holdout_requires_attestation(self):
        d = dataset(); d["provenance"] = "independent-human-holdout"
        for c in d["cases"]: c["split"] = "holdout"
        with self.assertRaises(base.EvaluationError): ev.validate_dataset(d)
        d["authorshipAttestation"] = "questions-written-by-user-before-model-results"
        self.assertIs(d, ev.validate_dataset(d))

    def test_invalid_expectations_do_not_silently_skip_checks(self):
        for e in ({"relevantIds": []}, {"maxToolCalls": True}, {"hardFacts": {"year": "2014"}},
                  {"arguments": {"searchMovies": {"host": "example"}}}, {"unknown": True},
                  {"exactTools": ["executeCommand"]}, {"answerContains": "text"}):
            with self.subTest(e=e), self.assertRaises(base.EvaluationError): ev.validate_expect(e)

    def test_web_argument_labels_match_real_schema(self):
        ev.validate_expect({"arguments": {"searchMovieWeb": {"query": "fixture", "language": "zh"}}})
        for labels in ({"keyword": "fixture"}, {"limit": 1}, {"language": "fr"}):
            with self.assertRaises(base.EvaluationError): ev.validate_expect({"arguments": {"searchMovieWeb": labels}})

    def test_freeze_detects_question_and_label_changes(self):
        d = dataset()
        with tempfile.TemporaryDirectory() as directory:
            manifest = ev.freeze(d, Path(directory) / "freeze.json")
            ev.verify_freeze(d, manifest)
            d["cases"][0]["turns"][0]["expect"]["year"] = 2020
            with self.assertRaises(base.EvaluationError): ev.verify_freeze(d, manifest)

    def test_frozen_artifacts_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "freeze.json"; ev.freeze(dataset(), path)
            with self.assertRaises(FileExistsError): ev.freeze(dataset(), path)

    def test_arguments_checked_in_actual_trace(self):
        checks, extra = graded(result(), {"arguments": {"searchMovies": {"year": 1900}}})
        self.assertFalse(checks["arguments:searchMovies"])
        self.assertFalse(extra["dimensions"]["arguments"]["passed"])

    def test_hard_facts_check_every_returned_movie(self):
        output = result(); output["trace"][1]["result"]["data"]["movies"][1]["category"] = "tv"
        checks, extra = graded(output, {"hardFacts": {"category": "mv"}})
        self.assertFalse(checks["hardFact:category"])

    def test_recall_uses_first_search_page_not_retry_union(self):
        output = result(("a", "x")); second = copy.deepcopy(output["trace"][1])
        second["result"]["data"]["movies"] = [{"id": "b", "year": 2014}]; output["trace"].append(second)
        _, extra = graded(output, {"relevantIds": ["a", "b"], "k": 2})
        self.assertEqual(.5, extra["retrievalMetrics"]["recallAtK"])
        self.assertEqual(1, extra["retrievalMetrics"]["reciprocalRankAtK"])

    def test_missing_relevance_has_zero_metrics(self):
        _, extra = graded(result(("x",)), {"relevantIds": ["b"]})
        self.assertEqual(0, extra["retrievalMetrics"]["recallAtK"])
        self.assertEqual(0, extra["retrievalMetrics"]["reciprocalRankAtK"])

    def test_followup_requires_actual_prior_id_in_argument_and_result(self):
        prior = result(("a",)); output = result(); output["trace"][1]["name"] = "getMovieDetails"
        step = output["trace"][1]; step["arguments"] = {"id": "b"}; step["result"]["data"] = {"movie": {"id": "a"}}
        checks = {}; ev.grade(output, {"followupId": True}, {}, prior, checks)
        self.assertFalse(checks["followupId"])
        step["arguments"]["id"] = "a"; checks = {}; ev.grade(output, {"followupId": True}, {}, prior, checks)
        self.assertTrue(checks["followupId"])

    def test_transport_failure_counts_all_unexecuted_turns_without_replay(self):
        d = dataset(); c = copy.deepcopy(d["cases"][3])
        class Client:
            calls = 0
            def call(self, path, body):
                self.calls += 1; raise base.EvaluationError("fixture transport failure")
        client = Client(); r = base.evaluate(client, d, [c], 2, grader=ev.grade)
        ev.complete_rows(r, [c], 2)
        self.assertEqual(1, client.calls); self.assertEqual(4, r["summary"]["plannedTurns"])
        self.assertEqual(4, r["summary"]["notExecutedTurns"])
        self.assertEqual(1, r["summary"]["requestAttempts"])
        self.assertFalse(r["completedAllTurns"])

    def test_report_export_never_retains_prompts_arguments_or_evidence(self):
        r = report(); exported = json.dumps(r)
        self.assertNotIn('"keyword"', exported); self.assertNotIn('"movies"', exported)
        self.assertNotIn("星际穿越", exported)
        self.assertIsNone(r["summary"]["work"]["tokens"])
        self.assertIsNone(r["summary"]["work"]["monetaryCost"])

    def test_evidence_hash_ignores_clock_but_detects_movie_change(self):
        output = result(); before = ev.canonical_evidence(output)
        output["trace"][1]["result"]["data"]["queriedAt"] = "2027-01-01T00:00:00Z"
        self.assertEqual(before, ev.canonical_evidence(output))
        output["trace"][1]["result"]["data"]["movies"][0]["year"] = 2020
        self.assertNotEqual(before, ev.canonical_evidence(output))

    def test_comparison_checks_coverage_freeze_schema_and_evidence(self):
        first = report(); self.assertEqual(0, ev.compare(first, copy.deepcopy(first))["mechanicalPassDelta"])
        for field in ("datasetSha256", "freezeSha256", "runtimeToolSchemaSha256", "selectionSha256", "repeat"):
            other = copy.deepcopy(first); other[field] = "changed"
            with self.subTest(field=field), self.assertRaises(base.EvaluationError): ev.compare(first, other)
        other = copy.deepcopy(first); other["rows"][0]["evidenceSha256"] = "0" * 64
        with self.assertRaises(base.EvaluationError): ev.compare(first, other)

    def test_comparison_rejects_incomplete_duplicate_and_repetition_drift(self):
        first = report()
        other = copy.deepcopy(first); other["completedAllTurns"] = False
        with self.assertRaises(base.EvaluationError): ev.compare(first, other)
        other = copy.deepcopy(first); other["rows"].append(copy.deepcopy(other["rows"][0]))
        with self.assertRaises(base.EvaluationError): ev.compare(first, other)
        other = copy.deepcopy(first); other["rows"][1]["evidenceSha256"] = "0" * 64
        with self.assertRaises(base.EvaluationError): ev.compare(other, copy.deepcopy(other))

    def test_human_scores_bind_report_and_answer_and_allow_pending(self):
        r = report(); template = ev.review_template(r); pending = ev.apply_review(r, template)
        self.assertEqual(2, pending["pendingAnswers"]); self.assertEqual(0, pending["reviewedAnswers"])
        template["reviews"][0]["scores"] = {"factuality": 2, "grounding": 1, "usefulness": 2}
        self.assertEqual(1, ev.apply_review(r, template)["reviewedAnswers"])
        template["reviews"][0]["answerSha256"] = "wrong"
        with self.assertRaises(base.EvaluationError): ev.apply_review(r, template)

    def test_review_boolean_scores_and_duplicates_rejected(self):
        r = report(); review = ev.review_template(r)
        review["reviews"][0]["scores"]["factuality"] = True
        with self.assertRaises(base.EvaluationError): ev.apply_review(r, review)
        review = ev.review_template(r); review["reviews"].append(copy.deepcopy(review["reviews"][0]))
        with self.assertRaises(base.EvaluationError): ev.apply_review(r, review)

    def test_existing_output_fails_before_inference(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(base.AssistantClient, "call", side_effect=AssertionError("inference")):
            path = Path(directory) / "result.json"; path.write_text("{}")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, ev.main(["--run", "--output", str(path)]))


if __name__ == "__main__": unittest.main()
