"""Versioned, grouped evaluation of the existing read-only agent. Plan by default.

No model judge, inference replay, credential loading or production service startup.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import evaluate_agent as base

ROOT = base.ROOT
DEFAULT_DATASET = ROOT / "docs/evaluation/comparable-agent-v1/cases.json"
DIMENSIONS = ("routing", "arguments", "retrieval", "answer", "rejection", "multiTurn")
PROVENANCE = {"agent-authored-regression", "independent-human-holdout"}
ID = re.compile(r"[a-z0-9-]{1,64}")
SHA = re.compile(r"[a-f0-9]{64}")
ARGUMENT_KEYS = {"keyword", "year", "category", "limit", "id"}
FACT_KEYS = {"id", "titleCn", "titleEn", "year", "category"}
CHECK_DIMENSION = {
    "toolAllowlist": "routing", "requiredTools": "routing", "toolBudget": "routing",
    "scope": "routing", "businessSuccess": "routing", "allToolsSucceeded": "routing",
    "queryTimestamps": "retrieval", "expectedMovieEvidence": "retrieval",
    "expectedYearEvidence": "retrieval", "emptySearchEvidence": "retrieval",
    "nonEmptyAnswer": "answer", "webExcerptAndUrl": "answer",
    "expectedError": "rejection", "noAnswerOnRejection": "rejection",
    "noToolOnRejection": "rejection", "noModelOnRejection": "rejection",
}


def fail(message):
    raise base.EvaluationError(message)


def read_json(path):
    if path.stat().st_size > 8 * 1024 * 1024:
        fail("Artifact exceeds 8 MiB")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    # A frozen artifact or historical result must never be silently overwritten.
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


def validate_expect(expected):
    if not isinstance(expected, dict):
        fail("Missing expectations")
    allowed = {"requiredTools", "maxToolCalls", "errorCode", "noModel", "movieTitle", "year",
               "emptySearch", "webCitations", "exactTools", "arguments", "relevantIds",
               "k", "hardFacts", "answerContains", "answerExcludes", "followupId"}
    if set(expected) - allowed:
        fail("Unknown expectation field")
    for field in ("requiredTools", "exactTools"):
        values = expected.get(field, [])
        if (not isinstance(values, list) or any(not isinstance(x, str) for x in values)
                or not set(values).issubset(base.ALLOWED_TOOLS)):
            fail("Expected tools must be read-only")
    budget = expected.get("maxToolCalls", 4)
    if type(budget) is not int or not 0 <= budget <= 8:
        fail("Invalid tool budget")
    for field in ("noModel", "emptySearch", "webCitations", "followupId"):
        if field in expected and type(expected[field]) is not bool:
            fail("Expected flag must be boolean")
    if "errorCode" in expected and (not isinstance(expected["errorCode"], str)
            or not re.fullmatch(r"[A-Z_]{1,80}", expected["errorCode"])):
        fail("Invalid expected error code")
    if "movieTitle" in expected and (not isinstance(expected["movieTitle"], str) or not expected["movieTitle"]):
        fail("Invalid expected title")
    if "year" in expected and (type(expected["year"]) is not int or not 1900 <= expected["year"] <= 2100):
        fail("Invalid expected year")
    for field in ("answerContains", "answerExcludes"):
        values = expected.get(field, [])
        if not isinstance(values, list) or any(not isinstance(x, str) or not 1 <= len(x) <= 200 for x in values):
            fail("Invalid answer phrase labels")
    args = expected.get("arguments", {})
    if not isinstance(args, dict) or set(args) - base.ALLOWED_TOOLS:
        fail("Invalid argument labels")
    for tool, labels in args.items():
        keys = {"id"} if tool == "getMovieDetails" else ({"query", "language"} if tool == "searchMovieWeb" else ARGUMENT_KEYS - {"id"})
        if not isinstance(labels, dict) or not labels or set(labels) - keys:
            fail("Unknown labelled tool argument")
        for key, value in labels.items():
            if key in {"limit", "year"}:
                if type(value) is not int or not (1900 <= value <= 2100 if key == "year" else 1 <= value <= 3):
                    fail("Invalid integer argument label")
            elif not isinstance(value, str) or not 1 <= len(value) <= (120 if key == "query" else 64):
                fail("Invalid textual argument label")
            if key == "category" and value not in {"mv", "tv", "ac"}:
                fail("Invalid category label")
            if key == "language" and value not in {"zh", "en"}:
                fail("Invalid web language label")
    relevant = expected.get("relevantIds", [])
    if not isinstance(relevant, list) or len(relevant) != len(set(str(x) for x in relevant)) or any(
            not isinstance(x, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", x) for x in relevant):
        fail("Invalid relevant movie IDs")
    if "relevantIds" in expected and not relevant:
        fail("Recall/MRR requires nonempty relevance labels; use emptySearch for no answer")
    if type(expected.get("k", 3)) is not int or not 1 <= expected.get("k", 3) <= 3:
        fail("k must be within the tool's three-result limit")
    facts = expected.get("hardFacts", {})
    if not isinstance(facts, dict) or set(facts) - FACT_KEYS:
        fail("Invalid hard fact labels")
    for key, value in facts.items():
        if key == "year":
            if type(value) is not int or not 1900 <= value <= 2100:
                fail("Invalid fact year")
        elif not isinstance(value, str) or not 1 <= len(value) <= 100:
            fail("Invalid textual fact")


def validate_dataset(data):
    if not isinstance(data, dict) or data.get("schemaVersion") != 2 or data.get("provenance") not in PROVENANCE:
        fail("Expected schemaVersion=2 and explicit provenance")
    if not isinstance(data.get("version"), str) or not ID.fullmatch(data["version"]):
        fail("Invalid dataset version")
    if data["provenance"] == "independent-human-holdout" and data.get("authorshipAttestation") != "questions-written-by-user-before-model-results":
        fail("Independent holdout requires explicit human authorship attestation")
    cases = data.get("cases")
    if not isinstance(cases, list) or not 1 <= len(cases) <= 150:
        fail("Dataset must contain 1-150 cases")
    ids, groups, questions = set(), {}, {}
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str) or not ID.fullmatch(case["id"]) or case["id"] in ids:
            fail("Invalid or duplicate case ID")
        ids.add(case["id"])
        group, split = case.get("sourceGroup"), case.get("split")
        if not isinstance(group, str) or not ID.fullmatch(group) or split not in {"development", "regression", "holdout"}:
            fail("Each case needs a sourceGroup and split")
        if group in groups and groups[group] != split:
            fail("Source-group leakage across splits")
        groups[group] = split
        if (data["provenance"] == "agent-authored-regression" and split == "holdout") or (data["provenance"] == "independent-human-holdout" and split != "holdout"):
            fail("Provenance and split disagree")
        turns = case.get("turns")
        if not isinstance(turns, list) or not 1 <= len(turns) <= 4:
            fail("Each case needs 1-4 turns")
        for index, turn in enumerate(turns):
            if not isinstance(turn, dict) or type(turn.get("useHistory", False)) is not bool:
                fail("Invalid turn")
            body = turn.get("request")
            if (not isinstance(body, dict) or body.get("scope") != "gying"
                    or not isinstance(body.get("message"), str) or not 1 <= len(body["message"].strip()) <= 1000):
                fail("Only nonempty gying requests are allowed")
            # Invalid-role / unknown-field rejection cases are intentional and go through the real validator.
            normalized = re.sub(r"[\W_]+", "", body["message"].casefold())
            if normalized in questions and questions[normalized] != group:
                fail("Exact normalized question duplicated across source groups")
            questions[normalized] = group
            validate_expect(turn.get("expect"))
            if (turn.get("useHistory") or "{movieId}" in body["message"] or turn["expect"].get("followupId")) and index == 0:
                fail("Follow-up requires a preceding turn")
    return data


def load_dataset(path):
    return validate_dataset(read_json(path))


def freeze(dataset, output):
    validate_dataset(dataset)
    manifest = {"schemaVersion": 1, "datasetSha256": base.digest(dataset),
                "datasetVersion": dataset["version"], "provenance": dataset["provenance"],
                "frozenAt": datetime.now(timezone.utc).isoformat(),
                "caseCount": len(dataset["cases"]),
                "groupSplits": {c["sourceGroup"]: c["split"] for c in dataset["cases"]},
                "dataScope": "live-read-only-catalog; no frozen corpus or independent quality claim"}
    write_new(output, manifest)
    return manifest


def verify_freeze(dataset, manifest):
    if (not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1
            or manifest.get("datasetSha256") != base.digest(dataset)
            or manifest.get("datasetVersion") != dataset["version"]
            or manifest.get("provenance") != dataset["provenance"]
            or manifest.get("groupSplits") != {c["sourceGroup"]: c["split"] for c in dataset["cases"]}
            or manifest.get("caseCount") != len(dataset["cases"]) or not base.valid_timestamp(manifest.get("frozenAt"))):
        fail("Dataset or labels changed after freeze; create a new version")


def evidence(result):
    data = [s["result"]["data"] for s in base.tool_steps(result) if s.get("result", {}).get("ok") is True]
    movies = []
    for item in data:
        movies.extend(item.get("movies", []))
        if isinstance(item.get("movie"), dict):
            movies.append(item["movie"])
    return data, movies


def canonical_evidence(result):
    # Exclude request time, URLs and resource payloads from exported artifacts.
    # Hash complete sanitized tool content in memory to detect data drift, including web excerpts.
    def strip_times(value):
        if isinstance(value, dict):
            return {k: strip_times(v) for k, v in value.items() if k not in {"queriedAt", "timestamp", "source"}}
        if isinstance(value, list):
            return [strip_times(v) for v in value]
        return value
    return base.digest([{"tool": s.get("name"), "outcome": strip_times(s.get("result"))} for s in base.tool_steps(result)])


def grade(result, expected, body, prior, checks):
    steps = base.tool_steps(result)
    data, movies = evidence(result)
    if "exactTools" in expected:
        checks["exactTools"] = [s.get("name") for s in steps] == expected["exactTools"]
    for tool, labels in expected.get("arguments", {}).items():
        matches = [s for s in steps if s.get("name") == tool]
        def matches_labels(step):
            args = step.get("arguments")
            if not isinstance(args, dict):
                return False
            for key, wanted in labels.items():
                if wanted == "{movieId}":
                    wanted = base.movie_id_from(prior) if prior else None
                if key not in args or type(args[key]) is not type(wanted) or args[key] != wanted:
                    return False
            return True
        checks["arguments:" + tool] = bool(matches) and all(matches_labels(s) for s in matches)
    for key, value in expected.get("hardFacts", {}).items():
        checks["hardFact:" + key] = bool(movies) and all(type(m.get(key)) is type(value) and m.get(key) == value for m in movies)
    answer = result["answer"]
    for field in ("answerContains", "answerExcludes"):
        if field in expected:
            checks[field] = all((phrase in answer) == (field == "answerContains") for phrase in expected[field])
    if expected.get("followupId"):
        wanted = base.movie_id_from(prior) if prior else None
        details = [s for s in steps if s.get("name") == "getMovieDetails"]
        checks["followupId"] = bool(wanted) and bool(details) and all(
            s.get("arguments", {}).get("id") == wanted and s.get("result", {}).get("data", {}).get("movie", {}).get("id") == wanted for s in details)
    metrics = {}
    if "relevantIds" in expected:
        # First actual search page only; combining retries would inflate Recall@k.
        first = next((s["result"]["data"] for s in steps if s.get("name") == "searchMovies" and s.get("result", {}).get("ok") is True), {})
        ranked = list(dict.fromkeys(m.get("id") for m in first.get("movies", []) if isinstance(m.get("id"), str)))[:expected.get("k", 3)]
        relevant = set(expected["relevantIds"])
        hits = relevant.intersection(ranked)
        metrics = {"k": expected.get("k", 3), "recallAtK": len(hits) / len(relevant),
                   "reciprocalRankAtK": next((1 / i for i, x in enumerate(ranked, 1) if x in relevant), 0),
                   "labelledRelevant": len(relevant), "returnedAtK": len(ranked)}
        checks["relevantResult"] = bool(hits)
    dimensions = {name: {} for name in DIMENSIONS}
    for name, passed in checks.items():
        if name.startswith("arguments:"):
            dimension = "arguments"
        elif name.startswith("hardFact:") or name == "relevantResult":
            dimension = "retrieval"
        elif name in {"answerContains", "answerExcludes"}:
            dimension = "answer"
        elif name == "followupId":
            dimension = "multiTurn"
        else:
            dimension = CHECK_DIMENSION.get(name, "routing")
        dimensions[dimension][name] = passed
    return {"dimensions": {k: {"checks": v, "passed": all(v.values())} for k, v in dimensions.items() if v},
            "retrievalMetrics": metrics, "evidenceSha256": canonical_evidence(result),
            "modelSha256": base.digest(result.get("model")),
            "work": {"modelCalls": sum(s.get("kind") == "model" for s in result["trace"]), "toolCalls": len(steps),
                     "providerUsageAvailable": False, "tokens": None, "monetaryCost": None}}


def complete_rows(report, selected, repeat):
    existing = {(r["caseId"], r["repetition"], r["turn"]): r for r in report["rows"]}
    report["rows"] = []
    for repetition in range(1, repeat + 1):
        for case in selected:
            for index, turn in enumerate(case["turns"], 1):
                key = case["id"], repetition, index
                row = existing.get(key, {"caseId": case["id"], "repetition": repetition, "turn": index,
                                         "passed": False, "failure": "NOT_EXECUTED", "manualReviewRequired": False})
                row["sourceGroup"], row["split"] = case["sourceGroup"], case["split"]
                row["executed"] = "checks" in row
                report["rows"].append(row)
    report["summary"] = summary(report["rows"])
    return report


def summary(rows):
    measured = [r for r in rows if "durationMs" in r]
    result = base.summarize(rows)
    result.update({"plannedTurns": len(rows), "executedTurns": sum(r["executed"] for r in rows),
                   "notExecutedTurns": sum(not r["executed"] for r in rows),
                   "requestAttempts": len(measured), "dimensionResults": {}, "perCase": []})
    for name in DIMENSIONS:
        applicable = [r["dimensions"][name] for r in rows if name in r.get("dimensions", {})]
        result["dimensionResults"][name] = {"applicable": len(applicable), "passed": sum(x["passed"] for x in applicable)}
    metrics = [r["retrievalMetrics"] for r in rows if r.get("retrievalMetrics")]
    result["retrieval"] = {"labelledTurns": len(metrics), "meanRecallAtK": sum(x["recallAtK"] for x in metrics) / len(metrics) if metrics else None,
                           "mrrAtK": sum(x["reciprocalRankAtK"] for x in metrics) / len(metrics) if metrics else None,
                           "scope": "Macro mean over explicitly labelled first search pages, k<=3; no unlabeled relevance inferred"}
    cases = collections.defaultdict(list)
    for row in rows:
        cases[row["caseId"]].append(row)
    for case_id, case_rows in sorted(cases.items()):
        reps = collections.defaultdict(list)
        for row in case_rows:
            reps[row["repetition"]].append(row)
        outcomes = [all(r["executed"] and r["passed"] for r in group) for group in reps.values()]
        result["perCase"].append({"caseId": case_id, "repetitions": len(outcomes), "successfulRepetitions": sum(outcomes),
                                  "passRate": sum(outcomes) / len(outcomes), "flaky": any(outcomes) and not all(outcomes)})
    result["failedTurns"] = [{"caseId": r["caseId"], "repetition": r["repetition"], "turn": r["turn"],
                              "failedChecks": [k for k, v in r.get("checks", {}).items() if not v],
                              "failure": r.get("failure")} for r in rows if not r["passed"]]
    result["work"] = {"modelCalls": sum(r.get("work", {}).get("modelCalls", 0) for r in rows),
                      "toolCalls": sum(r.get("work", {}).get("toolCalls", 0) for r in rows),
                      "providerUsageAvailable": False, "tokens": None, "monetaryCost": None}
    return result


def review_template(report):
    return {"schemaVersion": 1, "reportSha256": base.digest(report), "reviews": [
        {"caseId": r["caseId"], "repetition": r["repetition"], "turn": r["turn"],
         "answerSha256": r["answerSha256"], "scores": {"factuality": None, "grounding": None, "usefulness": None}}
        for r in report["rows"] if r.get("manualReviewRequired") and r.get("answerSha256")]}


def apply_review(report, review):
    if review.get("schemaVersion") != 1 or review.get("reportSha256") != base.digest(report) or not isinstance(review.get("reviews"), list):
        fail("Review must bind to the exact report hash")
    expected = {(x["caseId"], x["repetition"], x["turn"]): x for x in review_template(report)["reviews"]}
    seen, totals = set(), collections.defaultdict(list)
    for item in review["reviews"]:
        if not isinstance(item, dict):
            fail("Invalid review entry")
        key = item.get("caseId"), item.get("repetition"), item.get("turn")
        if key not in expected or key in seen or item.get("answerSha256") != expected[key]["answerSha256"]:
            fail("Review duplicated or bound to another answer")
        seen.add(key)
        scores = item.get("scores")
        if not isinstance(scores, dict) or set(scores) != {"factuality", "grounding", "usefulness"}:
            fail("Review requires all three dimensions")
        for name, value in scores.items():
            if value is not None and (type(value) is not int or not 0 <= value <= 2):
                fail("Human scores must be null or integer 0-2")
        if all(v is not None for v in scores.values()):
            for name, value in scores.items():
                totals[name].append(value)
    reviewed = len(totals["factuality"])
    return {"reportSha256": base.digest(report), "reviewedAnswers": reviewed, "pendingAnswers": len(expected) - reviewed,
            "meanScores": {k: sum(totals[k]) / reviewed if reviewed else None for k in ("factuality", "grounding", "usefulness")},
            "scope": "Human review recorded separately from mechanical checks; scores are not production accuracy"}


def compare(left, right):
    for report in (left, right):
        if report.get("reportSchemaVersion") != 2 or not report.get("completedAllTurns"):
            fail("Comparison requires two completed comparable-evaluation reports")
    for key in ("datasetSha256", "freezeSha256", "runtimeToolSchemaSha256", "selectionSha256", "repeat", "evaluatorSha256", "baseEvaluatorSha256"):
        if left.get(key) != right.get(key) or left.get(key) is None:
            fail("Comparison dataset, freeze, tool contract, selection, repeats or evaluator differ")
    def row_map(report):
        rows = report.get("rows", [])
        output = {(r["caseId"], r["repetition"], r["turn"]): r for r in rows}
        if len(output) != len(rows):
            fail("Duplicate report row")
        return output
    first, second = row_map(left), row_map(right)
    if first.keys() != second.keys() or not first:
        fail("Comparison row coverage differs")
    for key in first:
        for row in (first[key], second[key]):
            if not row.get("executed") or not SHA.fullmatch(str(row.get("evidenceSha256", ""))):
                fail("Comparison contains missing evidence")
        if first[key]["evidenceSha256"] != second[key]["evidenceSha256"]:
            fail("Observed tool evidence changed; no controlled quality comparison")
    for report in (left, right):
        repeated = collections.defaultdict(set)
        for row in report["rows"]:
            repeated[(row["caseId"], row["turn"])].add(row["evidenceSha256"])
        if any(len(x) != 1 for x in repeated.values()):
            fail("Observed evidence drifted across repetitions")
    return {"baselineReportSha256": base.digest(left), "candidateReportSha256": base.digest(right),
            "mechanicalPassDelta": right["summary"]["mechanicalPasses"] - left["summary"]["mechanicalPasses"],
            "p50MsDelta": right["summary"]["p50Ms"] - left["summary"]["p50Ms"],
            "p95MsDelta": right["summary"]["p95Ms"] - left["summary"]["p95Ms"],
            "scope": "Paired agent regression with identical observed evidence; live corpus is not frozen, latency is descriptive, no independent quality claim"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_DATASET.with_name("freeze.json"))
    parser.add_argument("--freeze", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--origin", default="http://127.0.0.1:5000")
    parser.add_argument("--repeat", type=int, default=2)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--output", type=Path, default=ROOT / "tmp/comparable-agent/report.json")
    parser.add_argument("--review-report", type=Path)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--compare", type=Path, nargs=2, metavar=("BASELINE", "CANDIDATE"))
    args = parser.parse_args(argv)
    try:
        if sum((args.freeze, args.run, bool(args.review_report), bool(args.compare))) > 1 or args.review and not args.review_report:
            fail("Choose only one action")
        if args.compare:
            output = compare(*(read_json(p) for p in args.compare))
            write_new(args.output, output)
            return 0
        if args.review_report:
            report = read_json(args.review_report)
            output = apply_review(report, read_json(args.review)) if args.review else review_template(report)
            write_new(args.output, output)
            return 0
        base.checked_origin(args.origin)
        if type(args.repeat) is not int or not 1 <= args.repeat <= 3:
            fail("Repeat must be 1-3")
        dataset = load_dataset(args.dataset)
        selected = [c for c in dataset["cases"] if not args.case or c["id"] in args.case]
        if not selected or set(args.case) - {c["id"] for c in selected}:
            fail("Unknown or empty selection")
        if args.freeze:
            freeze(dataset, args.manifest)
            print("Dataset and labels frozen; live catalog is not a frozen corpus")
            return 0
        manifest = read_json(args.manifest)
        verify_freeze(dataset, manifest)
        if not args.run:
            print(json.dumps({"mode": "plan", "datasetVersion": dataset["version"], "provenance": dataset["provenance"],
                              "sourceGroups": len({c["sourceGroup"] for c in selected}), "cases": [c["id"] for c in selected],
                              "repeat": args.repeat, "plannedTurns": sum(len(c["turns"]) for c in selected) * args.repeat,
                              "dataScope": manifest["dataScope"], "networkRequests": 0}, ensure_ascii=False, indent=2))
            return 0
        if args.output.exists():
            fail("Output exists; choose a new report path before inference")
        client = base.AssistantClient(args.origin)
        if client.call("/api/health").get("code") != 200:
            fail("Agent is not healthy")
        discovered = client.call("/api/assistant/tools?scope=gying")
        definitions = discovered.get("data")
        if (discovered.get("code") != 200 or not isinstance(definitions, list)
                or len(definitions) != len(base.ALLOWED_TOOLS)
                or any(not isinstance(d, dict) or not isinstance(d.get("name"), str) for d in definitions)
                or {d["name"] for d in definitions} != base.ALLOWED_TOOLS):
            fail("Runtime tool contract is not the three approved read-only tools")
        report = base.evaluate(client, dataset, selected, args.repeat, grader=grade)
        complete_rows(report, selected, args.repeat)
        report.update({"reportSchemaVersion": 2, "freezeSha256": base.digest(manifest),
                       "selectionSha256": base.digest([c["id"] for c in selected]),
                       "runtimeToolSchemaSha256": base.digest(definitions), "dataScope": manifest["dataScope"],
                       "evaluatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       "baseEvaluatorSha256": hashlib.sha256(Path(base.__file__).read_bytes()).hexdigest()})
        write_new(args.output, report)
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
        return 0 if report["completedAllTurns"] and report["summary"]["mechanicalFailures"] == 0 else 1
    except (base.EvaluationError, ValueError, OSError, TypeError, KeyError) as exc:
        print(str(exc) if isinstance(exc, base.EvaluationError) else "Evaluation artifact/setup invalid: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
