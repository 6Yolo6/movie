"""Offline quality review with explicit reviewer attribution and immutable report binding.

This protocol is independent of the frozen comparable-evaluation protocol. No
scores, causes or relevance labels are inferred from an HTTP or mechanical pass.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
from pathlib import Path

import comparable_evaluation as comparable
import evaluate_agent as base

SCORE_NAMES = ("factuality", "grounding", "usefulness")
FAILURE_CATEGORIES = ("routing", "conditions", "candidates", "facts", "clarification", "multiTurn")
HARD_CONSTRAINTS = ("pending", "satisfied", "violated", "unknown", "notApplicable")
OUTCOMES = ("pending", "exact", "usefulApproximation", "clarification", "unhelpful")
REVIEWER_KINDS = ("unassigned", "human", "model")
ENTRY_FIELDS = {"caseId", "repetition", "turn", "answerSha256", "scores", "failureCategories",
                "hardConstraints", "outcome", "notes"}
SHA256 = re.compile(r"[a-f0-9]{64}")


def fail(message):
    raise base.EvaluationError(message)


def row_key(row):
    if (not isinstance(row, dict) or not isinstance(row.get("caseId"), str)
            or not row["caseId"] or len(row["caseId"]) > 128
            or type(row.get("repetition")) is not int or row["repetition"] < 1
            or type(row.get("turn")) is not int or row["turn"] < 1):
        fail("Invalid case/repetition/turn identity")
    return row["caseId"], row["repetition"], row["turn"]


def report_rows(report):
    if not isinstance(report, dict) or not isinstance(report.get("rows"), list) or not report["rows"]:
        fail("Report requires at least one planned row")
    indexed = {}
    for row in report["rows"]:
        key = row_key(row)
        if key in indexed:
            fail("Report contains duplicate row identities")
        if type(row.get("executed")) is not bool or type(row.get("passed")) is not bool:
            fail("Report rows require explicit executed/passed booleans")
        answer_hash = row.get("answerSha256")
        if answer_hash is not None and (not isinstance(answer_hash, str) or not SHA256.fullmatch(answer_hash)):
            fail("Invalid report answer hash")
        if not row["executed"] and (row["passed"] or answer_hash is not None):
            fail("Unexecuted row cannot contain a pass or answer")
        indexed[key] = row
    return indexed


def can_score(row):
    return row["executed"] and row.get("answerSha256") is not None


def review_template(report):
    rows = report_rows(report)
    return {"schemaVersion": 2, "reportSha256": base.digest(report),
            "reviewer": {"kind": "unassigned", "name": ""},
            "reviews": [{"caseId": row["caseId"], "repetition": row["repetition"], "turn": row["turn"],
                         "answerSha256": row.get("answerSha256"),
                         "scores": {name: None for name in SCORE_NAMES},
                         "failureCategories": [], "hardConstraints": "pending",
                         "outcome": "pending", "notes": ""} for row in rows.values()]}


def diagnosis_complete(item):
    """A documented cause, or a documented outcome plus constraint check, closes diagnosis."""
    return bool(item["notes"].strip()) and (bool(item["failureCategories"])
        or (item["outcome"] != "pending" and item["hardConstraints"] != "pending"))


def apply_review(report, review):
    rows = report_rows(report)
    if (not isinstance(review, dict) or set(review) != {"schemaVersion", "reportSha256", "reviewer", "reviews"}
            or type(review.get("schemaVersion")) is not int or review["schemaVersion"] != 2
            or review.get("reportSha256") != base.digest(report) or not isinstance(review.get("reviews"), list)):
        fail("Quality review must use schemaVersion=2 and bind to the exact report")
    reviewer = review["reviewer"]
    if (not isinstance(reviewer, dict) or set(reviewer) != {"kind", "name"}
            or reviewer.get("kind") not in REVIEWER_KINDS or not isinstance(reviewer.get("name"), str)
            or len(reviewer["name"]) > 120):
        fail("Invalid reviewer attribution")
    if reviewer["kind"] == "unassigned":
        if reviewer["name"]:
            fail("Unassigned reviewer must have an empty name")
    elif not reviewer["name"].strip():
        fail("Human or model review requires an explicit reviewer name")
    seen = set()
    dimensions = {name: [] for name in SCORE_NAMES}
    failures = collections.Counter({name: 0 for name in FAILURE_CATEGORIES})
    constraints = collections.Counter({name: 0 for name in HARD_CONSTRAINTS})
    outcomes = collections.Counter({name: 0 for name in OUTCOMES})
    reviewed = diagnosed = 0
    for item in review["reviews"]:
        key = row_key(item)
        if (set(item) != ENTRY_FIELDS or key not in rows or key in seen
                or item["answerSha256"] != rows[key].get("answerSha256")):
            fail("Review has missing fields, duplicate identity, or a different report answer")
        seen.add(key)
        scores = item["scores"]
        if not isinstance(scores, dict) or set(scores) != set(SCORE_NAMES):
            fail("Review requires all three score dimensions")
        for score in scores.values():
            if score is not None and (type(score) is not int or not 0 <= score <= 2):
                fail("Quality scores must be null or integer 0-2")
        categories = item["failureCategories"]
        if (not isinstance(categories, list) or any(not isinstance(x, str) or x not in FAILURE_CATEGORIES for x in categories)
                or len(categories) != len(set(categories))):
            fail("Failure categories must be unique known categories")
        if item["hardConstraints"] not in HARD_CONSTRAINTS or item["outcome"] not in OUTCOMES:
            fail("Invalid hard-constraint status or outcome")
        if not isinstance(item["notes"], str) or len(item["notes"]) > 5000:
            fail("Review notes must be text up to 5000 characters")
        diagnosis_started = bool(categories) or item["hardConstraints"] != "pending" or item["outcome"] != "pending"
        any_scores = any(value is not None for value in scores.values())
        if (diagnosis_started or any_scores or item["notes"].strip()) and reviewer["kind"] == "unassigned":
            fail("Scores or diagnosis require explicit human/model reviewer attribution")
        if diagnosis_started and not item["notes"].strip():
            fail("Diagnosis requires notes describing the observed evidence or uncertainty")
        if not can_score(rows[key]):
            if any_scores or item["hardConstraints"] != "pending" or item["outcome"] != "pending":
                fail("Unexecuted or unanswered rows allow cause notes only, not answer scores/outcomes")
        if item["outcome"] == "exact" and item["hardConstraints"] not in {"satisfied", "notApplicable"}:
            fail("Exact satisfaction requires verified hard constraints or no applicable constraints")
        if item["outcome"] == "usefulApproximation" and item["hardConstraints"] == "violated":
            fail("Useful approximation cannot silently relax an explicit hard constraint")
        if item["hardConstraints"] == "violated" and scores["usefulness"] == 2:
            fail("A violated hard constraint cannot receive full usefulness")
        if can_score(rows[key]):
            for name, score in scores.items():
                if score is not None:
                    dimensions[name].append(score)
            reviewed += all(value is not None for value in scores.values())
        diagnosed += diagnosis_complete(item)
        failures.update(categories)
        constraints[item["hardConstraints"]] += 1
        outcomes[item["outcome"]] += 1
    if seen != set(rows):
        fail("Review must retain every planned report row, including failures and unexecuted turns")
    eligible = sum(can_score(row) for row in rows.values())
    execution_failed = [row for row in rows.values() if row["executed"] and not row["passed"]]
    summary = report.get("summary", {})
    work = summary.get("work", {}) if isinstance(summary, dict) else {}
    if not isinstance(work, dict):
        work = {}
    source = reviewer["kind"]
    return {"schemaVersion": 2, "reportSha256": base.digest(report), "reviewer": dict(reviewer),
            "plannedTurns": len(rows), "executedTurns": sum(row["executed"] for row in rows.values()),
            "notExecutedTurns": sum(not row["executed"] for row in rows.values()),
            "mechanicallyPassedTurns": sum(row["executed"] and row["passed"] for row in rows.values()),
            "mechanicallyFailedTurns": len(execution_failed),
            "answerCount": sum(row.get("answerSha256") is not None for row in rows.values()),
            "scorableAnswerCount": eligible, "reviewedAnswers": reviewed, "pendingAnswers": eligible - reviewed,
            "humanReviewedAnswers": reviewed if source == "human" else 0,
            "humanPendingAnswers": eligible - reviewed if source == "human" else eligible,
            "modelReviewedAnswers": reviewed if source == "model" else 0,
            "scoreDimensions": {name: {"reviewed": len(values), "pending": eligible - len(values),
                                       "mean": sum(values) / len(values) if values else None}
                                for name, values in dimensions.items()},
            "diagnosisReviewedRows": diagnosed, "diagnosisPendingRows": len(rows) - diagnosed,
            "failureCategoryCounts": dict(failures), "hardConstraintCounts": dict(constraints),
            "outcomeCounts": dict(outcomes),
            "failedTurns": [{"caseId": row["caseId"], "repetition": row["repetition"], "turn": row["turn"],
                             "failure": row.get("failure")} for row in execution_failed],
            "tokens": summary.get("tokens", work.get("tokens")) if isinstance(summary, dict) else None,
            "monetaryCost": summary.get("monetaryCost", work.get("monetaryCost")) if isinstance(summary, dict) else None,
            "scope": "Offline review; reviewer attribution is self-declared, not identity-verified. Model reviews do not count as human review. Per-dimension means include partial reviews. Diagnosis closes with documented categories or a documented outcome and constraint check; categories can overlap. Mechanical pass/failure does not determine score eligibility; any executed answer remains reviewable. Execution failures are not inferred quality causes. Usage is copied from the report only; missing values remain null. Scores are not production accuracy."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.output.exists():
            fail("Output already exists; use a new file to preserve review history")
        report = comparable.read_json(args.report)
        result = apply_review(report, comparable.read_json(args.review)) if args.review else review_template(report)
        comparable.write_new(args.output, result)
        print("Quality review summary written" if args.review else "Quality review template written; no scores inferred")
        return 0
    except (base.EvaluationError, OSError, ValueError, KeyError, TypeError) as exc:
        print(str(exc) if isinstance(exc, base.EvaluationError) else "Quality review input invalid: " + type(exc).__name__)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
