"""Evaluate the existing loopback assistant; default invocation only prints a plan."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "docs/evaluation/agent-harness-20261022/cases.json"
ALLOWED_TOOLS = {"searchMovies", "getMovieDetails", "searchMovieWeb"}
MAX_RESPONSE_BYTES = 1024 * 1024


class EvaluationError(ValueError):
    pass


def digest(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def checked_origin(value):
    uri = parse.urlsplit(value)
    try:
        port = uri.port
    except ValueError as exc:
        raise EvaluationError("Invalid assistant port") from exc
    if (uri.scheme != "http" or uri.hostname not in {"127.0.0.1", "::1"}
            or not port or uri.username is not None or uri.password is not None
            or uri.path not in {"", "/"} or uri.query or uri.fragment):
        raise EvaluationError("Assistant must use an explicit HTTP loopback port, without credentials or a path")
    return value.rstrip("/")


def load_dataset(path):
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict) or data.get("provenance") != "agent-authored-regression":
        raise EvaluationError("Dataset must declare agent-authored-regression provenance")
    cases = data.get("cases")
    if not isinstance(cases, list) or not 1 <= len(cases) <= 50:
        raise EvaluationError("Dataset must contain 1-50 cases")
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or not re.fullmatch(r"[a-z0-9-]{1,64}", case.get("id", "")):
            raise EvaluationError("Invalid case id")
        if case["id"] in seen:
            raise EvaluationError("Duplicate case id")
        seen.add(case["id"])
        turns = case.get("turns")
        if not isinstance(turns, list) or not 1 <= len(turns) <= 4:
            raise EvaluationError("Each case must contain 1-4 turns")
        for turn in turns:
            if not isinstance(turn, dict):
                raise EvaluationError("Invalid turn")
            body, expected = turn.get("request"), turn.get("expect")
            if not isinstance(body, dict) or body.get("scope") != "gying":
                raise EvaluationError("Only the gying scope can be evaluated")
            if not isinstance(body.get("message"), str) or not 1 <= len(body["message"]) <= 1000:
                raise EvaluationError("Message must contain 1-1000 characters")
            if not isinstance(expected, dict) or not isinstance(expected.get("requiredTools", []), list):
                raise EvaluationError("Missing or invalid turn expectations")
            if not set(expected.get("requiredTools", [])).issubset(ALLOWED_TOOLS):
                raise EvaluationError("Expected tool is not read-only")
            if "errorCode" in expected and (not isinstance(expected["errorCode"], str)
                    or not re.fullmatch(r"[A-Z_]{1,80}", expected["errorCode"])):
                raise EvaluationError("Invalid expected error code")
            if not isinstance(expected.get("maxToolCalls", 4), int) or not 0 <= expected.get("maxToolCalls", 4) <= 8:
                raise EvaluationError("Invalid tool budget")
    return data


class AssistantClient:
    def __init__(self, origin, timeout=200):
        self.origin = checked_origin(origin)
        self.timeout = timeout
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, path, body=None):
        if path not in {"/api/health", "/api/assistant/tools?scope=gying", "/api/assistant"}:
            raise EvaluationError("Endpoint is not allowed")
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = request.Request(self.origin + path, data=payload, headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except error.HTTPError as exc:
            # Never retain response bodies or URL-bearing exception text.
            raise EvaluationError("Assistant HTTP failure: " + str(exc.code)) from None
        except (error.URLError, TimeoutError, OSError):
            # A timed out inference may still be running; do not replay it.
            raise EvaluationError("Assistant transport failed; inference was not retried") from None
        if len(raw) > MAX_RESPONSE_BYTES:
            raise EvaluationError("Assistant response exceeds 1 MiB")
        try:
            value = json.loads(raw)
        except (ValueError, UnicodeError):
            raise EvaluationError("Assistant returned invalid JSON") from None
        if not isinstance(value, dict):
            raise EvaluationError("Assistant returned a non-object response")
        return value


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def unwrap(response):
    if not isinstance(response.get("code"), int) or isinstance(response.get("code"), bool):
        raise EvaluationError("Assistant response has no numeric business code")
    value = response.get("data")
    if not isinstance(value, dict):
        raise EvaluationError("Assistant response has no result object")
    if not isinstance(value.get("trace"), list):
        raise EvaluationError("Assistant response has no trace array")
    if any(not isinstance(item, dict) for item in value["trace"]):
        raise EvaluationError("Assistant trace contains invalid steps")
    if not isinstance(value.get("answer"), str):
        raise EvaluationError("Assistant answer must be a string")
    if "error" in value and not isinstance(value["error"], dict):
        raise EvaluationError("Assistant error must be an object")
    for step in tool_steps(value):
        outcome = step.get("result")
        if not isinstance(outcome, dict) or not isinstance(outcome.get("ok"), bool):
            raise EvaluationError("Tool step has no boolean execution result")
        if outcome["ok"]:
            data = outcome.get("data")
            if not isinstance(data, dict):
                raise EvaluationError("Successful tool step has no data object")
            for name in ("movies", "webResults"):
                if name in data and (not isinstance(data[name], list) or any(not isinstance(item, dict) for item in data[name])):
                    raise EvaluationError("Tool evidence contains invalid records")
    return value


def tool_steps(result):
    return [step for step in result["trace"] if step.get("kind") == "tool"]


def movie_id_from(result):
    for step in tool_steps(result):
        outcome = step.get("result", {})
        if not isinstance(outcome, dict) or outcome.get("ok") is not True:
            continue
        data = outcome.get("data", {})
        if not isinstance(data, dict):
            continue
        movies = data.get("movies", [])
        for movie in movies if isinstance(movies, list) else []:
            candidate = movie.get("id") if isinstance(movie, dict) else None
            if isinstance(candidate, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", candidate):
                return candidate
    return None


def grade(result, expected, business_code):
    steps = tool_steps(result)
    names = [step.get("name") for step in steps]
    checks = {}
    checks["toolAllowlist"] = all(name in ALLOWED_TOOLS for name in names)
    checks["toolBudget"] = len(steps) <= expected.get("maxToolCalls", 4)
    wanted_error = expected.get("errorCode")
    if wanted_error:
        checks["expectedError"] = result.get("error", {}).get("code") == wanted_error and business_code != 200
        checks["noAnswerOnRejection"] = not result.get("answer")
        checks["noToolOnRejection"] = not steps
        if expected.get("noModel"):
            checks["noModelOnRejection"] = not any(s.get("kind") == "model" for s in result["trace"])
        return checks
    checks["scope"] = result.get("scope") == "gying"
    checks["businessSuccess"] = business_code == 200 and not result.get("error") and result.get("status") == "completed"
    checks["nonEmptyAnswer"] = isinstance(result.get("answer"), str) and bool(result["answer"].strip())
    checks["requiredTools"] = set(expected.get("requiredTools", [])).issubset(names)
    successful = [s for s in steps if isinstance(s.get("result"), dict) and s["result"].get("ok") is True]
    checks["allToolsSucceeded"] = bool(successful) and len(successful) == len(steps)
    evidence = [s["result"].get("data", {}) for s in successful]
    checks["queryTimestamps"] = bool(evidence) and all(isinstance(d, dict) and valid_timestamp(d.get("queriedAt")) for d in evidence)
    movies = []
    for data in evidence:
        if not isinstance(data, dict):
            continue
        movies.extend(m for m in data.get("movies", []) if isinstance(m, dict))
        if isinstance(data.get("movie"), dict):
            movies.append(data["movie"])
    if "movieTitle" in expected:
        checks["expectedMovieEvidence"] = any(expected["movieTitle"] in {m.get("titleCn"), m.get("titleEn")} for m in movies)
    if "year" in expected:
        checks["expectedYearEvidence"] = bool(movies) and all(m.get("year") == expected["year"] for m in movies)
    if expected.get("emptySearch"):
        searches = [s["result"].get("data", {}) for s in successful if s.get("name") == "searchMovies"]
        checks["emptySearchEvidence"] = bool(searches) and all(d.get("total") == 0 and d.get("movies") == [] for d in searches)
    if expected.get("webCitations"):
        sources = [source for d in evidence if isinstance(d, dict) for source in d.get("webResults", []) if isinstance(source, dict)]
        answer = result.get("answer", "")
        checks["webExcerptAndUrl"] = bool(sources) and all(
            isinstance(s.get("snippet"), str) and bool(s["snippet"]) and s["snippet"] in answer
            and isinstance(s.get("url"), str) and s["url"].startswith(("https://zh.wikipedia.org/", "https://en.wikipedia.org/"))
            and s["url"] in answer for s in sources)
    return checks


def valid_timestamp(value):
    if not isinstance(value, str):
        return False
    try:
        # Java Instant emits nanosecond precision; Python 3.10 only accepts microseconds.
        normalized = re.sub(r"(\.\d{6})\d+(?=Z|[+-]\d{2}:\d{2}$)", r"\1", value)
        return datetime.fromisoformat(normalized.replace("Z", "+00:00")).tzinfo is not None
    except ValueError:
        return False


def safe_steps(result):
    records = []
    for step in result["trace"][:40]:
        name = step.get("name", "")
        records.append({
            "kind": step.get("kind") if step.get("kind") in {"model", "tool", "error"} else "unknown",
            "name": name if isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9:._-]{1,80}", name) else "omitted",
            "status": step.get("status") if step.get("status") in {"success", "error", "limit"} else "unknown",
            "durationMs": max(0, step.get("durationMs", 0)) if isinstance(step.get("durationMs", 0), (int, float)) else 0,
        })
    return records


def summarize(rows):
    measured = [row["durationMs"] for row in rows if "durationMs" in row]
    measured.sort()
    passed = sum(row.get("passed") is True for row in rows)
    review = sum(row.get("manualReviewRequired") is True for row in rows)
    return {"turns": len(rows), "mechanicalPasses": passed, "mechanicalFailures": len(rows) - passed,
            "manualReviewRequired": review,
            "latencySampleCount": len(measured),
            "p50Ms": measured[math.ceil(len(measured) * .5) - 1] if measured else None,
            "p95Ms": measured[math.ceil(len(measured) * .95) - 1] if measured else None,
            "latencyScope": "Observed sequential requests; small sample, not a production SLA",
            "qualityScope": "Mechanical regression checks; not independent human evaluation or task success rate"}


def evaluate(client, dataset, selected, repeat, grader=None):
    rows = []
    stopped = False
    for repetition in range(1, repeat + 1):
        for case in selected:
            history, prior = [], None
            for turn_index, turn in enumerate(case["turns"], 1):
                row = {"caseId": case["id"], "repetition": repetition, "turn": turn_index,
                       "passed": False, "manualReviewRequired": "errorCode" not in turn["expect"]}
                body = json.loads(json.dumps(turn["request"]))
                if "{movieId}" in body["message"]:
                    movie_id = movie_id_from(prior) if prior else None
                    if not movie_id:
                        row["failure"] = "PREVIOUS_TURN_HAS_NO_MOVIE_ID"
                        rows.append(row)
                        break
                    body["message"] = body["message"].replace("{movieId}", movie_id)
                if turn.get("useHistory"):
                    body["history"] = list(history)
                started = time.monotonic()
                try:
                    response = client.call("/api/assistant", body)
                    result = unwrap(response)
                except EvaluationError as exc:
                    row["failure"] = str(exc)
                    row["durationMs"] = round((time.monotonic() - started) * 1000, 2)
                    rows.append(row)
                    stopped = True
                    break
                row["durationMs"] = round((time.monotonic() - started) * 1000, 2)
                row["checks"] = grade(result, turn["expect"], response.get("code"))
                if grader is not None:
                    row.update(grader(result, turn["expect"], body, prior, row["checks"]))
                row["passed"] = all(row["checks"].values())
                row["trace"] = safe_steps(result)
                row["answerSha256"] = digest(result.get("answer", ""))
                row["answerChars"] = len(result.get("answer", ""))
                row["requestId"] = result.get("requestId") if re.fullmatch(r"[A-Za-z0-9-]{1,64}", str(result.get("requestId", ""))) else None
                rows.append(row)
                print(json.dumps({k: row[k] for k in ("caseId", "turn", "passed", "durationMs")}, ensure_ascii=False), flush=True)
                if row["passed"] and not result.get("error"):
                    history.extend([{"role": "user", "content": body["message"]}, {"role": "assistant", "content": result["answer"]}])
                else:
                    break
                prior = result
            if stopped:
                break
        if stopped:
            break
    expected_turns = sum(len(case["turns"]) for case in selected) * repeat
    return {"datasetVersion": dataset["version"], "datasetSha256": digest(dataset),
            "provenance": dataset["provenance"], "evaluatedAt": datetime.now(timezone.utc).isoformat(),
            "transport": "existing-loopback-assistant", "repeat": repeat,
            "plannedTurns": expected_turns, "completedAllTurns": len(rows) == expected_turns and not stopped,
            "summary": summarize(rows), "rows": rows}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--origin", default="http://127.0.0.1:5000")
    parser.add_argument("--run", action="store_true", help="Run actual read-only model/tool requests")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--timeout", type=int, default=200)
    parser.add_argument("--output", type=Path, default=ROOT / "tmp/agent-evaluation/report.json")
    args = parser.parse_args(argv)
    try:
        checked_origin(args.origin)
        if not 1 <= args.repeat <= 3 or not 1 <= args.timeout <= 300:
            raise EvaluationError("Repeat must be 1-3 and timeout must be 1-300 seconds")
        dataset = load_dataset(args.dataset)
        selected = [case for case in dataset["cases"] if not args.case or case["id"] in args.case]
        if set(args.case) - {case["id"] for case in selected}:
            raise EvaluationError("Unknown case selection")
        if not args.run:
            print(json.dumps({"mode": "plan", "datasetVersion": dataset["version"],
                              "cases": [case["id"] for case in selected], "repeat": args.repeat,
                              "plannedTurns": sum(len(case["turns"]) for case in selected) * args.repeat,
                              "sideEffects": "No requests in plan mode; --run sends read-only assistant queries"}, ensure_ascii=False, indent=2))
            return 0
        client = AssistantClient(args.origin, args.timeout)
        if client.call("/api/health").get("code") != 200:
            raise EvaluationError("Assistant is not healthy")
        discovered = client.call("/api/assistant/tools?scope=gying")
        definitions = discovered.get("data")
        if discovered.get("code") != 200 or not isinstance(definitions, list) or not definitions:
            raise EvaluationError("Tool discovery failed")
        if any(not isinstance(tool, dict) for tool in definitions) or {tool.get("name") for tool in definitions} != ALLOWED_TOOLS:
            raise EvaluationError("Runtime tools differ from the approved read-only tool set")
        report = evaluate(client, dataset, selected, args.repeat)
        report["runtimeToolSchemaSha256"] = digest(definitions)
        report["toolNames"] = sorted(ALLOWED_TOOLS)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
        return 0 if report["completedAllTurns"] and report["summary"]["mechanicalFailures"] == 0 else 1
    except (EvaluationError, ValueError, OSError) as exc:
        # Dataset filesystem errors may contain private paths: only print the error class.
        print(str(exc) if isinstance(exc, EvaluationError) else "Evaluation setup failed: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
