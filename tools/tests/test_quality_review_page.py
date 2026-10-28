"""Offline HTML generation: exact artifact linkage, resume safety and inert content."""
import contextlib
import copy
import io
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ai"))
import build_quality_review_page as page
import evaluate_agent as base
import quality_review as quality


def artifacts(answer="Observed answer [link omitted]"):
    dataset = {"version": "fixture-v1", "cases": [
        {"id": "answered", "number": 1, "category": "fixture", "sourceGroup": "fixture-one",
         "userRubric": "Check facts and constraints", "annotation": "Require evidence",
         "turns": [{"message": "Find an example", "useHistory": False}]},
        {"id": "failed", "number": 2, "category": "fixture", "sourceGroup": "fixture-two",
         "userRubric": "Check failure", "annotation": "No answer exists",
         "turns": [{"message": "A second example", "useHistory": False}]}]}
    report = {"reportSchemaVersion": 2, "datasetSha256": base.digest(dataset), "rows": [
        {"caseId": "answered", "repetition": 1, "turn": 1, "executed": True, "passed": True,
         "answerSha256": "a" * 64, "route": "model-queue"},
        {"caseId": "failed", "repetition": 1, "turn": 1, "executed": True, "passed": False,
         "failure": "WEBSITE_JOB_FAILED", "route": "model-queue"}]}
    observations = {"reportSha256": base.digest(report), "observations": [
        {"caseId": "answered", "repetition": 1, "turn": 1, "answerSha256": "a" * 64,
         "answer": answer, "movies": [{"id": "fixture-film", "titleCn": "Example"}],
         "catalog": {"filter": {"maxRuntime": 90}}}]}
    return report, dataset, observations


def write_inputs(root, bundle, resume=None):
    args = []
    for name, data in zip(("report", "dataset", "observations"), bundle):
        path = root / (name + ".json")
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        args.extend(("--" + name, str(path)))
    args.extend(("--output", str(root / "review.html")))
    if resume is not None:
        path = root / "resume.json"
        path.write_text(json.dumps(resume), encoding="utf-8")
        args.extend(("--review", str(path)))
    return args


def embedded_payload(html):
    match = re.search(r'<script id="data" type="application/json">(.*?)</script>', html, re.S)
    if not match:
        raise AssertionError("Generated page must contain its inert JSON payload")
    return json.loads(match.group(1))


class QualityReviewPageTests(unittest.TestCase):
    def test_payload_preserves_observations_without_inferred_scores(self):
        bundle = artifacts()
        original = copy.deepcopy(bundle)
        payload = page.build_payload(*bundle)
        self.assertEqual(original, bundle)
        self.assertEqual(bundle[2]["observations"], payload["observations"])
        self.assertEqual(2, len(payload["review"]["reviews"]))
        self.assertEqual("unassigned", payload["review"]["reviewer"]["kind"])
        self.assertEqual(1, payload["summary"]["pendingAnswers"])
        self.assertEqual(2, payload["summary"]["diagnosisPendingRows"])
        self.assertEqual(0, payload["summary"]["humanReviewedAnswers"])
        self.assertTrue(all(score is None for row in payload["review"]["reviews"] for score in row["scores"].values()))

    def test_changed_dataset_or_report_breaks_exact_linkage(self):
        for changed in ("dataset", "report", "observations"):
            with self.subTest(changed=changed):
                run, dataset, observations = artifacts()
                if changed == "dataset":
                    dataset["cases"][0]["annotation"] = "Changed rubric"
                elif changed == "report":
                    run["rows"][0]["route"] = "direct-catalog"
                else:
                    observations["reportSha256"] = "f" * 64
                with self.assertRaises(base.EvaluationError):
                    page.build_payload(run, dataset, observations)

    def test_missing_duplicate_unknown_and_wrong_answer_observations_rejected(self):
        for mode in ("missing", "duplicate", "unknown", "wrong-answer", "wrong-repetition", "non-text"):
            with self.subTest(mode=mode):
                run, dataset, observations = artifacts()
                if mode == "missing":
                    observations["observations"] = []
                elif mode == "duplicate":
                    observations["observations"].append(copy.deepcopy(observations["observations"][0]))
                elif mode == "unknown":
                    observations["observations"][0]["caseId"] = "not-in-report"
                elif mode == "wrong-answer":
                    observations["observations"][0]["answerSha256"] = "b" * 64
                elif mode == "wrong-repetition":
                    observations["observations"][0]["repetition"] = 2
                else:
                    observations["observations"][0]["answer"] = {"text": "answer"}
                with self.assertRaises(base.EvaluationError):
                    page.build_payload(run, dataset, observations)

    def test_observation_for_unanswered_failure_is_rejected(self):
        run, dataset, observations = artifacts()
        observations["observations"].append({"caseId": "failed", "repetition": 1, "turn": 1,
                                             "answerSha256": None, "answer": "Invented answer"})
        with self.assertRaises(base.EvaluationError):
            page.build_payload(run, dataset, observations)

    def test_all_unanswered_report_needs_no_invented_observations(self):
        run, dataset, observations = artifacts()
        run["rows"] = [run["rows"][1]]
        observations = {"reportSha256": base.digest(run), "observations": []}
        payload = page.build_payload(run, dataset, observations)
        self.assertEqual([], payload["observations"])
        self.assertEqual(0, payload["summary"]["answerCount"])
        self.assertEqual(0, payload["summary"]["pendingAnswers"])
        self.assertEqual(1, payload["summary"]["diagnosisPendingRows"])
        self.assertNotIn("answer", payload["review"]["reviews"][0])
        self.assertTrue(all(x is None for x in payload["review"]["reviews"][0]["scores"].values()))

    def test_mechanical_failure_with_observed_answer_still_available_for_review(self):
        run, dataset, observations = artifacts()
        run["rows"][0]["passed"] = False
        run["rows"][0]["failure"] = "TOOL_ALLOWLIST_CHECK"
        observations["reportSha256"] = base.digest(run)
        payload = page.build_payload(run, dataset, observations)
        self.assertEqual(1, payload["summary"]["scorableAnswerCount"])
        self.assertEqual(2, payload["summary"]["mechanicallyFailedTurns"])
        self.assertEqual(1, len(payload["observations"]))

    def test_missing_dataset_turn_and_duplicate_cases_rejected(self):
        for mode in ("missing-case", "missing-turn", "duplicate-case"):
            with self.subTest(mode=mode):
                run, dataset, observations = artifacts()
                if mode == "missing-case":
                    dataset["cases"].pop()
                elif mode == "missing-turn":
                    dataset["cases"][0]["turns"] = []
                else:
                    dataset["cases"].append(copy.deepcopy(dataset["cases"][0]))
                run["datasetSha256"] = base.digest(dataset)
                observations["reportSha256"] = base.digest(run)
                with self.assertRaises(base.EvaluationError):
                    page.build_payload(run, dataset, observations)

    def test_valid_resume_preserves_partial_scores_notes_and_attribution(self):
        bundle = artifacts()
        resume = quality.review_template(bundle[0])
        resume["reviewer"] = {"kind": "human", "name": "Reviewer"}
        resume["reviews"][0]["scores"]["factuality"] = 1
        resume["reviews"][0]["notes"] = "Need to verify one claimed date."
        payload = page.build_payload(*bundle, resume)
        self.assertEqual(resume, payload["review"])
        self.assertEqual(1, payload["summary"]["scoreDimensions"]["factuality"]["reviewed"])
        self.assertEqual(0, payload["summary"]["reviewedAnswers"])
        self.assertEqual("unassigned", payload["template"]["reviewer"]["kind"])
        self.assertIsNone(payload["template"]["reviews"][0]["scores"]["factuality"])

    def test_resume_wrong_binding_boolean_scores_missing_rows_and_false_human_attribution_rejected(self):
        for mode in ("report", "answer", "score", "missing", "attribution", "schema"):
            with self.subTest(mode=mode):
                bundle = artifacts()
                resume = quality.review_template(bundle[0])
                resume["reviewer"] = {"kind": "human", "name": "Reviewer"}
                if mode == "report":
                    resume["reportSha256"] = "f" * 64
                elif mode == "answer":
                    resume["reviews"][0]["answerSha256"] = "f" * 64
                elif mode == "score":
                    resume["reviews"][0]["scores"]["factuality"] = True
                elif mode == "missing":
                    resume["reviews"].pop()
                elif mode == "attribution":
                    resume["reviewer"]["name"] = ""
                else:
                    resume["schemaVersion"] = 1
                with self.assertRaises(base.EvaluationError):
                    page.build_payload(*bundle, resume)

    def test_generated_html_escapes_script_breakouts_and_retains_exact_text(self):
        attack = '</script><img src=x onerror=alert(1)><script>alert("x")</script>&'
        bundle = artifacts(attack)
        with tempfile.TemporaryDirectory() as directory, patch.object(base.AssistantClient, "call", side_effect=AssertionError("network")):
            root = Path(directory)
            args = write_inputs(root, bundle)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, page.main(args))
            html = (root / "review.html").read_text(encoding="utf-8")
            self.assertNotIn(attack, html)
            self.assertNotIn("</script><img", html)
            self.assertIn("\\u003c/script", html)
            self.assertIn("\\u0026", html)
            self.assertNotIn("__SCRIPT__", html)
            self.assertNotIn("__DATA__", html)
            self.assertEqual(attack, embedded_payload(html)["observations"][0]["answer"])
            self.assertIn("connect-src 'none'", html)

    def test_generator_validates_resume_before_writing_html(self):
        for valid in (True, False):
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                bundle = artifacts()
                resume = quality.review_template(bundle[0])
                resume["reviewer"] = {"kind": "model", "name": "Fixture model"}
                resume["reviews"][0]["scores"] = dict.fromkeys(quality.SCORE_NAMES, 1)
                if not valid:
                    resume["reportSha256"] = "f" * 64
                args = write_inputs(root, bundle, resume)
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0 if valid else 2, page.main(args))
                if valid:
                    payload = embedded_payload((root / "review.html").read_text(encoding="utf-8"))
                    self.assertEqual(resume, payload["review"])
                    self.assertEqual(0, payload["summary"]["humanReviewedAnswers"])
                    self.assertEqual(1, payload["summary"]["modelReviewedAnswers"])
                else:
                    self.assertFalse((root / "review.html").exists())

    def test_existing_output_refused_before_inputs_are_read_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "review.html"
            output.write_text("Existing reviewer work", encoding="utf-8")
            args = ["--report", "missing.json", "--dataset", "missing.json", "--observations", "missing.json", "--output", str(output)]
            with patch.object(page.comparable, "read_json", side_effect=AssertionError("must reject output first")), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, page.main(args))
            self.assertEqual("Existing reviewer work", output.read_text(encoding="utf-8"))

    def test_repository_output_and_path_traversal_into_repository_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "repository"
            for output in (repository / "review.html", root / "external" / ".." / "repository" / "review.html"):
                with self.subTest(output=output):
                    args = ["--report", "missing.json", "--dataset", "missing.json", "--observations", "missing.json", "--output", str(output)]
                    with patch.object(base, "ROOT", repository), patch.object(page.comparable, "read_json", side_effect=AssertionError("must reject repository output first")), contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(2, page.main(args))
                    self.assertFalse(output.exists())

    def test_cli_missing_answer_observation_creates_no_page(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run, dataset, observations = artifacts()
            observations["observations"] = []
            args = write_inputs(root, (run, dataset, observations))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, page.main(args))
            self.assertFalse((root / "review.html").exists())


if __name__ == "__main__":
    unittest.main()
