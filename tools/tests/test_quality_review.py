"""Offline review integrity, missing evidence, attribution and diagnosis regressions."""
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
import evaluate_agent as base
import quality_review as review


def report():
    return {"reportSchemaVersion": 2, "summary": {"tokens": None, "monetaryCost": None}, "rows": [
        {"caseId": "answered", "repetition": 1, "turn": 1, "executed": True, "passed": True,
         "answerSha256": "a" * 64},
        {"caseId": "answered", "repetition": 2, "turn": 1, "executed": True, "passed": True,
         "answerSha256": "b" * 64},
        {"caseId": "failed", "repetition": 1, "turn": 1, "executed": True, "passed": False,
         "failure": "WEBSITE_JOB_FAILED"},
        {"caseId": "skipped", "repetition": 1, "turn": 1, "executed": False, "passed": False,
         "failure": "NOT_EXECUTED"}]}


def attributed(kind="human"):
    result = review.review_template(report())
    result["reviewer"] = {"kind": kind, "name": "fixture reviewer"}
    return result


class QualityReviewTests(unittest.TestCase):
    def test_template_keeps_all_rows_pending_and_does_not_infer_failure_causes(self):
        original = report()
        saved = copy.deepcopy(original)
        template = review.review_template(original)
        summary = review.apply_review(original, template)
        self.assertEqual(saved, original)
        self.assertEqual(4, len(template["reviews"]))
        self.assertIsNone(template["reviews"][2]["answerSha256"])
        self.assertEqual((4, 3, 1, 1, 2), tuple(summary[name] for name in
                         ("plannedTurns", "executedTurns", "notExecutedTurns", "mechanicallyFailedTurns", "answerCount")))
        self.assertEqual(0, summary["reviewedAnswers"])
        self.assertEqual(2, summary["pendingAnswers"])
        self.assertEqual(4, summary["diagnosisPendingRows"])
        self.assertTrue(all(value == 0 for value in summary["failureCategoryCounts"].values()))
        self.assertIsNone(summary["tokens"])
        self.assertIsNone(summary["monetaryCost"])
        self.assertIsNone(summary["scoreDimensions"]["usefulness"]["mean"])

    def test_partial_scores_have_separate_coverage_and_do_not_complete_answer(self):
        template = attributed()
        template["reviews"][0]["scores"]["factuality"] = 2
        summary = review.apply_review(report(), template)
        self.assertEqual({"reviewed": 1, "pending": 1, "mean": 2}, summary["scoreDimensions"]["factuality"])
        self.assertEqual({"reviewed": 0, "pending": 2, "mean": None}, summary["scoreDimensions"]["grounding"])
        self.assertEqual(0, summary["reviewedAnswers"])
        self.assertEqual(2, summary["pendingAnswers"])

    def test_complete_human_review_and_documented_approximation_are_separate(self):
        template = attributed()
        template["reviews"][0].update(scores={"factuality": 2, "grounding": 1, "usefulness": 1},
                                      outcome="usefulApproximation", hardConstraints="satisfied",
                                      notes="Hard conditions supported; a soft preference remains unmet.")
        summary = review.apply_review(report(), template)
        self.assertEqual(1, summary["humanReviewedAnswers"])
        self.assertEqual(1, summary["humanPendingAnswers"])
        self.assertEqual(0, summary["modelReviewedAnswers"])
        self.assertEqual(1, summary["outcomeCounts"]["usefulApproximation"])
        self.assertEqual(0, summary["outcomeCounts"]["exact"])
        self.assertEqual(1, summary["diagnosisReviewedRows"])

    def test_model_scores_never_count_as_human_review(self):
        template = attributed("model")
        template["reviews"][0]["scores"] = dict.fromkeys(review.SCORE_NAMES, 2)
        summary = review.apply_review(report(), template)
        self.assertEqual(1, summary["reviewedAnswers"])
        self.assertEqual(1, summary["modelReviewedAnswers"])
        self.assertEqual(0, summary["humanReviewedAnswers"])
        self.assertEqual(2, summary["humanPendingAnswers"])

    def test_wrong_report_and_wrong_answer_binding_rejected(self):
        for location in ("report", "answer"):
            with self.subTest(location=location):
                template = attributed()
                if location == "report":
                    template["reportSha256"] = "f" * 64
                else:
                    template["reviews"][0]["answerSha256"] = "f" * 64
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_missing_duplicate_and_unknown_rows_rejected(self):
        for mode in ("missing", "duplicate", "unknown"):
            with self.subTest(mode=mode):
                template = attributed()
                if mode == "missing":
                    template["reviews"].pop()
                elif mode == "duplicate":
                    template["reviews"].append(copy.deepcopy(template["reviews"][0]))
                else:
                    template["reviews"][0]["caseId"] = "unknown"
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_boolean_out_of_range_and_noninteger_scores_rejected(self):
        for value in (True, False, -1, 3, 1.0, "2", [], {}):
            with self.subTest(value=value):
                template = attributed()
                template["reviews"][0]["scores"]["factuality"] = value
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_all_score_dimensions_required(self):
        template = attributed()
        del template["reviews"][0]["scores"]["grounding"]
        with self.assertRaises(base.EvaluationError):
            review.apply_review(report(), template)

    def test_unassigned_source_cannot_record_scores_notes_or_diagnoses(self):
        for changes in ({"scores": dict.fromkeys(review.SCORE_NAMES, 1)}, {"notes": "observed"},
                        {"failureCategories": ["conditions"], "notes": "wrong filter"}):
            with self.subTest(changes=changes):
                template = review.review_template(report())
                template["reviews"][0].update(changes)
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_reviewer_requires_valid_kind_and_explicit_name(self):
        for reviewer in ({"kind": "human", "name": " "}, {"kind": "model", "name": ""},
                         {"kind": "automation", "name": "x"}, {"kind": "unassigned", "name": "human"}):
            with self.subTest(reviewer=reviewer):
                template = attributed()
                template["reviewer"] = reviewer
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_technical_failure_can_be_diagnosed_without_answer_scores(self):
        template = attributed()
        template["reviews"][2].update(failureCategories=["routing"], notes="Trace shows task selected the wrong route.")
        summary = review.apply_review(report(), template)
        self.assertEqual(1, summary["failureCategoryCounts"]["routing"])
        self.assertEqual(1, summary["diagnosisReviewedRows"])
        self.assertEqual(0, summary["reviewedAnswers"])
        self.assertEqual("WEBSITE_JOB_FAILED", summary["failedTurns"][0]["failure"])

    def test_failed_and_unexecuted_rows_cannot_receive_quality_scores_or_outcomes(self):
        for index in (2, 3):
            for changes in ({"scores": dict.fromkeys(review.SCORE_NAMES, 0)},
                            {"outcome": "unhelpful", "notes": "No successful answer"},
                            {"hardConstraints": "unknown", "notes": "No answer evidence"}):
                with self.subTest(index=index, changes=changes):
                    template = attributed()
                    template["reviews"][index].update(changes)
                    with self.assertRaises(base.EvaluationError):
                        review.apply_review(report(), template)

    def test_failed_checks_with_answer_hash_remain_in_quality_denominator(self):
        run = report()
        run["rows"][0]["passed"] = False
        template = review.review_template(run)
        template["reviewer"] = {"kind": "human", "name": "fixture"}
        summary = review.apply_review(run, template)
        self.assertEqual(2, summary["answerCount"])
        self.assertEqual(2, summary["scorableAnswerCount"])
        self.assertEqual(2, summary["mechanicallyFailedTurns"])
        template["reviews"][0]["scores"] = {"factuality": 2, "grounding": 1, "usefulness": 0}
        summary = review.apply_review(run, template)
        self.assertEqual(1, summary["humanReviewedAnswers"])
        self.assertEqual(1, summary["pendingAnswers"])
        self.assertEqual(0, summary["scoreDimensions"]["usefulness"]["mean"])

    def test_explicit_hard_constraint_violation_cannot_be_called_useful_approximation(self):
        template = attributed()
        template["reviews"][0].update(outcome="usefulApproximation", hardConstraints="violated",
                                      notes="Returned an explicitly excluded director.")
        with self.assertRaises(base.EvaluationError):
            review.apply_review(report(), template)

    def test_hard_violation_cannot_get_full_usefulness(self):
        template = attributed()
        template["reviews"][0].update(hardConstraints="violated", notes="Wrong year.")
        template["reviews"][0]["scores"]["usefulness"] = 2
        with self.assertRaises(base.EvaluationError):
            review.apply_review(report(), template)

    def test_exact_requires_verified_or_inapplicable_hard_conditions(self):
        for constraint in ("pending", "unknown", "violated", "satisfied", "notApplicable"):
            with self.subTest(constraint=constraint):
                template = attributed()
                template["reviews"][0].update(outcome="exact", hardConstraints=constraint, notes="Evidence checked.")
                if constraint in {"satisfied", "notApplicable"}:
                    self.assertEqual(1, review.apply_review(report(), template)["outcomeCounts"]["exact"])
                else:
                    with self.assertRaises(base.EvaluationError):
                        review.apply_review(report(), template)

    def test_diagnosis_requires_explanatory_notes(self):
        for changes in ({"failureCategories": ["facts"]}, {"hardConstraints": "unknown"}, {"outcome": "clarification"}):
            with self.subTest(changes=changes):
                template = attributed()
                template["reviews"][0].update(changes)
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_documented_unknown_cause_remains_pending(self):
        template = attributed()
        template["reviews"][2]["notes"] = "Error source is not established by the available trace."
        summary = review.apply_review(report(), template)
        self.assertEqual(4, summary["diagnosisPendingRows"])
        self.assertEqual(0, sum(summary["failureCategoryCounts"].values()))

    def test_categories_are_multilabel_and_diagnosis_denominator_counts_rows(self):
        template = attributed()
        template["reviews"][0].update(failureCategories=["conditions", "multiTurn"], notes="Previous exclusions lost.")
        summary = review.apply_review(report(), template)
        self.assertEqual(2, sum(summary["failureCategoryCounts"].values()))
        self.assertEqual(1, summary["diagnosisReviewedRows"])

    def test_illegal_enums_duplicate_categories_and_extra_fields_rejected(self):
        changes_list = ({"hardConstraints": "maybe"}, {"outcome": "correct"}, {"failureCategories": ["accuracy"]},
                        {"failureCategories": ["facts", "facts"]}, {"failureCategories": "facts"}, {"extra": True})
        for changes in changes_list:
            with self.subTest(changes=changes):
                template = attributed()
                template["reviews"][0].update(changes, notes="fixture")
                with self.assertRaises(base.EvaluationError):
                    review.apply_review(report(), template)

    def test_boolean_identity_and_malformed_report_rows_rejected(self):
        for changes in ({"repetition": True}, {"turn": 0}, {"executed": "yes"}, {"passed": 1},
                        {"answerSha256": "unbound"}, {"executed": False}):
            with self.subTest(changes=changes):
                run = report()
                run["rows"][0].update(changes)
                with self.assertRaises(base.EvaluationError):
                    review.review_template(run)
        run = report()
        run["rows"].append(copy.deepcopy(run["rows"][0]))
        with self.assertRaises(base.EvaluationError):
            review.review_template(run)

    def test_empty_report_rejected(self):
        with self.assertRaises(base.EvaluationError):
            review.review_template({"rows": []})

    def test_usage_only_copies_recorded_values(self):
        run = report()
        run["summary"] = {"work": {"tokens": 123, "monetaryCost": 0.02}}
        summary = review.apply_review(run, review.review_template(run))
        self.assertEqual(123, summary["tokens"])
        self.assertEqual(0.02, summary["monetaryCost"])
        run["summary"] = {"tokens": None, "work": {"tokens": 123}}
        self.assertIsNone(review.apply_review(run, review.review_template(run))["tokens"])

    def test_cli_creates_template_summary_and_refuses_overwrite_without_network(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(base.AssistantClient, "call", side_effect=AssertionError("network")):
            root = Path(directory)
            report_path, review_path, summary_path = (root / name for name in ("report.json", "review.json", "summary.json"))
            report_path.write_text(json.dumps(report()), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, review.main(["--report", str(report_path), "--output", str(review_path)]))
                self.assertEqual(0, review.main(["--report", str(report_path), "--review", str(review_path), "--output", str(summary_path)]))
                original = review_path.read_bytes()
                self.assertEqual(2, review.main(["--report", str(report_path), "--output", str(review_path)]))
            self.assertEqual(original, review_path.read_bytes())
            self.assertEqual(2, json.loads(summary_path.read_text(encoding="utf-8"))["pendingAnswers"])

    def test_cli_invalid_review_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = report()
            template = attributed()
            template["reviews"].pop()
            (root / "report.json").write_text(json.dumps(run), encoding="utf-8")
            (root / "review.json").write_text(json.dumps(template), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, review.main(["--report", str(root / "report.json"), "--review", str(root / "review.json"),
                                                "--output", str(root / "summary.json")]))
            self.assertFalse((root / "summary.json").exists())

    def test_frozen_website_report_stays_84_pending_and_four_mechanical_failures(self):
        path = base.ROOT / "docs/evaluation/human-holdout-v1/website-report.json"
        run = json.loads(path.read_text(encoding="utf-8"))
        summary = review.apply_review(run, review.review_template(run))
        self.assertEqual(88, summary["plannedTurns"])
        self.assertEqual(84, summary["answerCount"])
        self.assertEqual(84, summary["humanPendingAnswers"])
        self.assertEqual(0, summary["humanReviewedAnswers"])
        self.assertEqual(4, summary["mechanicallyFailedTurns"])
        self.assertEqual(88, summary["diagnosisPendingRows"])


if __name__ == "__main__":
    unittest.main()
