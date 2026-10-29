"""Collect frozen development observations through the current website API.

Plan by default. One submission per turn, serial polling, no inference replay.
This does not grade reviewRubric or create human quality/relevance labels.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import comparable_evaluation as comparable
import evaluate_agent as base
import evaluate_website as website

DEFAULT_DATASET = base.ROOT / "docs/evaluation/development-v3/cases.json"
TOOLS = {"searchMovies", "getMovieDetails", "searchMovieWeb", "queryCatalog", "naturalSearchMovies", "recommendMovies"}
TRACE_KINDS = {"model", "tool", "error", "planning", "retrieval", "routing"}
TRACE_STATUSES = {"success", "error", "limit"}
PREVIOUS_MAX_AGE = 14 * 60
MOVIE_FIELDS = {"id", "titleCn", "titleEn", "year", "category", "rating", "doubanRating", "imdbRating",
                "ratingSource", "runtimeMinutes", "summary", "reasons", "genres", "directors", "actors",
                "languages", "countries", "regions", "resourceCount", "seriesName", "season", "aliases",
                "doubanScore", "imdbScore", "tmdbVoteAverage"}
EVIDENCE_FIELDS = {"tool", "queriedAt", "total", "returned", "truncated", "webResults",
                   "unverifiedConditions", "retrieval", "relevanceChecked", "recalledTotal", "unresolvedPeople"}
SENSITIVE_KEYS = {"token", "password", "secret", "cookie", "authorization", "code", "resources", "resourceurl"}


def utf16_length(value):
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def utf16_prefix(value, limit):
    # Do not create an unpaired surrogate when the browser's slice boundary falls
    # inside a supplementary character; this can differ by one code unit only.
    return value.encode("utf-16-le", errors="surrogatepass")[:limit * 2].decode("utf-16-le", errors="ignore")


def validate_dataset(data):
    comparable.validate_dataset(data)
    if data["provenance"] != "agent-authored-regression":
        comparable.fail("Development runner requires implementer-authored development data")
    for case in data["cases"]:
        if case["split"] != "development":
            comparable.fail("Development runner cannot execute holdout or regression splits")
        for index, turn in enumerate(case["turns"]):
            if (set(turn["request"]) != {"scope", "message"} or turn["useHistory"] is not (index > 0)
                    or turn["expect"] != {} or utf16_length(turn["request"]["message"]) > 1000):
                comparable.fail("Expected development message, sequential history and empty mechanical expectations")
            rubric = turn.get("reviewRubric")
            if not isinstance(rubric, dict) or set(rubric) != {"hardConditions", "softPreferences", "expectedBehavior", "usefulApproximationBoundary"}:
                comparable.fail("Development turn requires an explicit quality rubric")
            for field in ("hardConditions", "softPreferences", "expectedBehavior"):
                if (not isinstance(rubric[field], list) or any(not isinstance(x, str) or not x.strip() for x in rubric[field])
                        or field != "softPreferences" and not rubric[field]):
                    comparable.fail("Invalid quality rubric list")
            if not isinstance(rubric["usefulApproximationBoundary"], str) or not rubric["usefulApproximationBoundary"].strip():
                comparable.fail("Quality rubric requires an approximation boundary")
    return data


def movies_for(result):
    return [movie for item in result.get("evidence", []) if isinstance(item, dict)
            for movie in (item.get("movies") if isinstance(item.get("movies"), list)
                          else [item["movie"]] if isinstance(item.get("movie"), dict) else [])
            if isinstance(movie, dict)]


def history_for(prior, message):
    history, remaining = [], 10000 - utf16_length(message)
    for item in reversed(prior[-4:]):
        result, question = item["result"], item["message"]
        references = "\n".join(f"{i}. {m.get('titleCn') or m.get('titleEn') or 'undefined'} ({m.get('year') or ''}) 片库 ID: {m.get('id', 'undefined')}"
                               for i, m in enumerate(movies_for(result), 1))
        answer = utf16_prefix("\n".join(x for x in (result["answer"], references) if x), 6000)
        size = utf16_length(question) + utf16_length(answer)
        if size > remaining:
            break
        history[0:0] = [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
        remaining -= size
    return history


def sanitize(value, token):
    if isinstance(value, str):
        return website.safe_answer(value, token) if token else re.sub(r"(?:https?://|magnet:)[^\s<>]+", "[link omitted]", value)
    if isinstance(value, list):
        return [sanitize(x, token) for x in value]
    if isinstance(value, dict):
        return {sanitize(k, token): sanitize(v, token) for k, v in value.items()
                if k.casefold() not in SENSITIVE_KEYS}
    return value


def trace_for(result, token):
    output = []
    for step in result.get("trace", [])[:40]:
        if not isinstance(step, dict):
            continue
        name = step.get("name")
        duration = step.get("durationMs")
        output.append({"kind": step.get("kind") if isinstance(step.get("kind"), str) and step["kind"] in TRACE_KINDS else "unknown",
                       "name": sanitize(name, token) if isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9:._-]{1,100}", name) else "omitted",
                       "status": step.get("status") if isinstance(step.get("status"), str) and step["status"] in TRACE_STATUSES else "unknown",
                       "timestamp": step.get("timestamp") if base.valid_timestamp(step.get("timestamp")) else None,
                       "durationMs": duration if type(duration) in {int, float} and math.isfinite(duration) and duration >= 0 else None})
    return output


def result_checks(result, status):
    evidence = result.get("evidence")
    trace = result.get("trace", [])
    valid_evidence = isinstance(evidence, list) and all(isinstance(e, dict) for e in evidence)
    valid_trace = isinstance(trace, list) and all(isinstance(s, dict) for s in trace)
    valid_trace_tools = valid_trace and all(s.get("kind") != "tool" or
        isinstance(s.get("name"), str) and s["name"] in TOOLS for s in trace)
    nonempty_answer = isinstance(result.get("answer"), str) and bool(result["answer"].strip())
    return {"businessSuccess": status == "SUCCEEDED", "nonEmptyAnswer": nonempty_answer,
            "answerContract": isinstance(result.get("answer"), str) and (status != "SUCCEEDED" or nonempty_answer),
            "evidenceContract": valid_evidence,
            "toolAllowlist": valid_evidence and bool(evidence) and valid_trace_tools and all(isinstance(e.get("tool"), str) and e["tool"] in TOOLS for e in evidence),
            "queryTimestamps": valid_evidence and bool(evidence) and all(base.valid_timestamp(e.get("queriedAt")) for e in evidence),
            "traceContract": valid_trace_tools and all(isinstance(s.get("kind"), str) and s["kind"] in TRACE_KINDS for s in trace),
            "queryPlanContract": "queryPlan" not in result or isinstance(result["queryPlan"], dict)}


def observation_for(row, result, token, submitted_history=None):
    evidence = []
    for item in result.get("evidence", []) if isinstance(result.get("evidence"), list) else []:
        if not isinstance(item, dict):
            continue
        entry = {key: value for key, value in item.items() if key in EVIDENCE_FIELDS}
        for key in ("movies", "movie"):
            values = item.get(key)
            if isinstance(values, list):
                entry[key] = [{k: v for k, v in movie.items() if k in MOVIE_FIELDS} for movie in values if isinstance(movie, dict)]
            elif isinstance(values, dict):
                entry[key] = {k: v for k, v in values.items() if k in MOVIE_FIELDS}
        evidence.append(entry)
    answer = sanitize(result["answer"], token)
    return {"caseId": row["caseId"], "repetition": row["repetition"], "turn": row["turn"],
            "answerSha256": row["answerSha256"], "answer": answer, "sanitizedAnswerSha256": base.digest(answer),
            "observedAt": row["observedAt"], "movies": sanitize([{k: v for k, v in m.items() if k in MOVIE_FIELDS} for m in movies_for({"evidence": evidence})], token),
            "evidence": sanitize(evidence, token), "queryPlan": sanitize(result.get("queryPlan"), token),
            "catalog": sanitize(result.get("catalog"), token), "partial": result.get("partial") is True,
            "submittedHistory": sanitize(submitted_history or [], token),
            "purpose": "Observed development answer, never a reference answer; session, sharing fields and URLs omitted. Original hashes bind pre-redaction content."}


def unwrap_job(value, expected_id=None):
    if (not isinstance(value, dict) or not isinstance(value.get("status"), str)
            or value["status"] not in {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"}
            or not isinstance(value.get("jobId"), str) or not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", value["jobId"])
            or expected_id is not None and value["jobId"] != expected_id):
        comparable.fail("Invalid website job identity/status contract")
    return value


def evaluate(client, data, selected, repeat=1, *, emit=print, sleep=time.sleep, monotonic=time.monotonic,
             min_interval=11, poll_interval=2.5, job_timeout=250, make_key=lambda: str(uuid.uuid4())):
    rows, observations, stopped = [], [], False
    seen_jobs = set()
    last_submit = None
    for repetition in range(1, repeat + 1):
        for case in selected:
            prior = []
            for index, turn in enumerate(case["turns"], 1):
                row = {"caseId": case["id"], "sourceGroup": case["sourceGroup"], "split": "development",
                       "repetition": repetition, "turn": index, "executed": False, "passed": False,
                       "submissionAttempted": False, "manualReviewRequired": False}
                rows.append(row)
                if stopped or len(prior) != index - 1:
                    row["failure"] = "NOT_EXECUTED_AFTER_UNCERTAIN_REQUEST" if stopped else "NOT_EXECUTED_AFTER_PREVIOUS_TURN_FAILURE"
                    continue
                if last_submit is not None:
                    sleep(max(0, min_interval - (monotonic() - last_submit)))
                started = monotonic()
                try:
                    message = turn["request"]["message"]
                    body = {"message": message, "history": history_for(prior, message), "requestKey": make_key()}
                    if prior and monotonic() - prior[-1]["completedAt"] < PREVIOUS_MAX_AGE:
                        body["previousJobId"] = prior[-1]["jobId"]
                    row.update({"historyEntries": len(body["history"]), "historyTruncated": len(body["history"]) < 2 * len(prior),
                                "historySha256": base.digest(body["history"]), "previousJobReferenced": "previousJobId" in body})
                    started = last_submit = monotonic()
                    row["submissionAttempted"] = True
                    job = unwrap_job(client.call("/api/ai/query", body))
                    row["jobId"] = job["jobId"]
                    if job["jobId"] in seen_jobs:
                        comparable.fail("Website reused a job for a distinct submission")
                    seen_jobs.add(job["jobId"])
                    deadline = started + job_timeout
                    while job["status"] in {"PENDING", "RUNNING"}:
                        remaining = deadline - monotonic()
                        if remaining <= 0:
                            comparable.fail("Website job timed out; submission was not replayed")
                        sleep(min(poll_interval, remaining))
                        if monotonic() >= deadline:
                            comparable.fail("Website job timed out; submission was not replayed")
                        job = unwrap_job(client.call("/api/ai/jobs/" + job["jobId"]), row["jobId"])
                    row.update({"executed": True, "jobStatus": job["status"], "observedAt": datetime.now(timezone.utc).isoformat()})
                    result = job.get("result")
                    if not isinstance(result, dict):
                        if job["status"] == "SUCCEEDED" or result is not None:
                            comparable.fail("Invalid website terminal result contract")
                        row.update({"checks": {"businessSuccess": False}, "failure": "WEBSITE_JOB_FAILED"})
                    else:
                        row["route"] = "direct-catalog" if "catalog" in result else "structured-query" if "queryPlan" in result else "model-queue"
                        row["checks"] = result_checks(result, job["status"])
                        row["partial"] = result.get("partial") is True
                        code = result.get("error", {}).get("code") if isinstance(result.get("error"), dict) else None
                        if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,80}", code):
                            row["errorCode"] = code
                        row["trace"] = trace_for(result, client.token) if isinstance(result.get("trace", []), list) else []
                        if isinstance(result.get("answer"), str) and result["answer"].strip():
                            row.update({"answerSha256": base.digest(result["answer"]), "answerChars": utf16_length(result["answer"]),
                                        "evidenceSha256": base.digest(result.get("evidence")), "queryPlanSha256": base.digest(result.get("queryPlan")),
                                        "manualReviewRequired": True})
                            observations.append(observation_for(row, result, client.token, body["history"]))
                        row["passed"] = all(row["checks"].values())
                        if not row["passed"]:
                            row["failure"] = "WEBSITE_JOB_FAILED" if job["status"] == "FAILED" else "WEBSITE_MECHANICAL_CHECK_FAILED"
                        if not all(row["checks"][key] for key in ("answerContract", "evidenceContract", "traceContract", "queryPlanContract")):
                            stopped = True
                        if job["status"] == "SUCCEEDED" and not stopped:
                            prior.append({"message": message, "result": result, "jobId": job["jobId"], "completedAt": monotonic()})
                except (base.EvaluationError, OSError, ValueError, TypeError, KeyError) as exc:
                    reason = str(exc) if isinstance(exc, base.EvaluationError) else ""
                    row["failure"] = ("WEBSITE_REQUEST_PREPARATION_FAILED" if not row["submissionAttempted"]
                                      else "WEBSITE_JOB_TIMEOUT_UNCERTAIN" if reason.startswith("Website job timed out;")
                                      else "WEBSITE_HTTP_" + reason.rsplit(" ", 1)[-1] if re.fullmatch(r"Website HTTP failure: [0-9]{3}", reason)
                                      else "WEBSITE_REQUEST_OR_CONTRACT_UNCERTAIN")
                    stopped = True
                row["durationMs"] = round((monotonic() - started) * 1000, 2)
                emit(json.dumps({key: row[key] for key in ("caseId", "repetition", "turn", "executed", "passed", "durationMs")}), flush=True)
    summary = base.summarize(rows)
    executed = sum(r["executed"] for r in rows)
    summary.update({"plannedTurns": len(rows), "executedTurns": executed, "notExecutedTurns": len(rows) - executed,
                    "submissionAttempts": sum(r["submissionAttempted"] for r in rows),
                    "uncertainCompletionTurns": sum(r["submissionAttempted"] and not r["executed"] for r in rows),
                    "mechanicalFailures": sum(r["executed"] and not r["passed"] for r in rows),
                    "routeCounts": dict(collections.Counter(r["route"] for r in rows if "route" in r)),
                    "qualityScope": "Development observations and mechanical checks only; reviewRubric is ungraded, human quality scores pending. Not an independent holdout.",
                    "tokens": None, "monetaryCost": None})
    return {"reportSchemaVersion": 1, "datasetSha256": base.digest(data), "datasetVersion": data["version"],
            "provenance": data["provenance"], "evaluatedAt": datetime.now(timezone.utc).isoformat(), "repeat": repeat,
            "transport": "authenticated-website-api", "browserUiCovered": False, "completedAllTurns": all(r["executed"] for r in rows),
            "selectionSha256": base.digest([c["id"] for c in selected]), "summary": summary, "rows": rows}, observations


def output_paths(output, observations, session_file=None):
    if observations is None:
        comparable.fail("A new private observations file outside the repository is required")
    targets = [output.resolve(), observations.resolve()]
    if targets[0] == targets[1] or any(path.exists() for path in targets):
        comparable.fail("Output files must be distinct and new; inference was not started")
    if not targets[1].parent.is_dir():
        comparable.fail("Create and protect the observations directory before execution")
    if targets[1].is_relative_to(base.ROOT.resolve()):
        comparable.fail("Observations require a protected directory outside the repository")
    if session_file is not None and session_file.resolve() in targets:
        comparable.fail("Outputs cannot replace a session file")
    return targets


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--origin", default="http://127.0.0.1:8880")
    session = parser.add_mutually_exclusive_group()
    session.add_argument("--session-file", type=Path)
    session.add_argument("--session-stdin", action="store_true")
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--output", type=Path, default=base.ROOT / "tmp/development-v3/report.json")
    parser.add_argument("--observations", type=Path)
    args = parser.parse_args(argv)
    try:
        data = validate_dataset(comparable.read_json(args.dataset))
        manifest = comparable.read_json(args.manifest or args.dataset.with_name("freeze.json"))
        comparable.verify_freeze(data, manifest)
        base.checked_origin(args.origin)
        if not 1 <= args.repeat <= 3:
            comparable.fail("Repeat must be 1-3; each repetition is a separate recorded observation")
        selected = [case for case in data["cases"] if not args.case or case["id"] in args.case]
        if not selected or set(args.case) - {case["id"] for case in selected}:
            comparable.fail("Unknown development case selection")
        if not args.run:
            print(json.dumps({"mode": "plan", "networkRequests": 0, "cases": len(selected),
                              "plannedTurns": sum(len(case["turns"]) for case in selected) * args.repeat,
                              "qualityScores": "not inferred", "datasetSha256": base.digest(data)}, indent=2))
            return 0
        output, observation_path = output_paths(args.output, args.observations, args.session_file)
        if not args.session_file and not args.session_stdin:
            comparable.fail("An existing dedicated USER session is required; this runner does not log in or enable accounts")
        token = website.load_session(args.session_file, args.session_stdin)
        client = website.WebsiteClient(args.origin, token)
        if client.call("/api/auth/me").get("role") != "USER":
            comparable.fail("Dedicated ordinary USER session required")
        status = client.call("/api/ai/status")
        if status.get("catalogAvailable") is not True or status.get("available") is not True or status.get("busy") is not False:
            comparable.fail("Catalog and connected idle AI worker are required before development execution")
        report, observations = evaluate(client, data, selected, args.repeat)
        report.update({"freezeSha256": base.digest(manifest), "websiteIdentityRole": "USER",
                       "evaluatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       "dependencySha256": {Path(module.__file__).name: hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest()
                                            for module in (base, comparable, website)}})
        comparable.write_new(observation_path, {"reportSha256": base.digest(report), "observations": observations})
        comparable.write_new(output, report)
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
        return 0 if report["completedAllTurns"] and not report["summary"]["mechanicalFailures"] else 1
    except (base.EvaluationError, OSError, ValueError, TypeError, KeyError) as exc:
        print(str(exc) if isinstance(exc, base.EvaluationError) else "Development evaluation failed: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
