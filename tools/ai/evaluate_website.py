"""Evaluate the authenticated website API; no model replay or prompt changes. Plan by default."""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, request

import evaluate_agent as base
import comparable_evaluation as comparable

DEFAULT_DATASET = base.ROOT / "docs/evaluation/human-holdout-v1/cases.json"
TOOLS = base.ALLOWED_TOOLS | {"naturalSearchMovies", "recommendMovies"}
ID = re.compile(r"[A-Za-z0-9_-]{1,64}")


def validate_dataset(data):
    if (not isinstance(data, dict) or data.get("schemaVersion") != 1
            or data.get("evaluationTarget") != "website-business"
            or data.get("provenance") != "independent-human-holdout"
            or data.get("authorshipAttestation") != "questions-written-by-user-before-model-results"
            or data.get("policy", {}).get("promptTuningAllowed") is not False
            or data.get("policy", {}).get("modelAnswersFrozenAsReference") is not False):
        comparable.fail("Expected independent website holdout without prompt tuning or model references")
    cases = data.get("cases")
    if not isinstance(cases, list) or not 1 <= len(cases) <= 150:
        comparable.fail("Invalid holdout case count")
    seen = set()
    questions = {}
    for case in cases:
        if (not isinstance(case, dict) or not ID.fullmatch(str(case.get("id", "")))
                or case["id"] in seen or case.get("split") != "holdout"
                or not ID.fullmatch(str(case.get("sourceGroup", "")))
                or not isinstance(case.get("annotation"), str) or not case["annotation"]):
            comparable.fail("Invalid or duplicated holdout case")
        seen.add(case["id"])
        turns = case.get("turns")
        if not isinstance(turns, list) or not 1 <= len(turns) <= 4:
            comparable.fail("Invalid holdout turns")
        original = case.get("originalQuestion")
        if not isinstance(original, str) or [s.strip() for s in original.split("→")] != [t.get("message") for t in turns]:
            comparable.fail("Original question was changed while splitting turns")
        for index, turn in enumerate(turns):
            message = turn.get("message")
            if (not isinstance(message, str) or not 1 <= len(message) <= 1000
                    or turn.get("useHistory") is not (index > 0)):
                comparable.fail("Invalid message/history policy")
            key = re.sub(r"[\W_]+", "", message.casefold())
            if key in questions and questions[key] != case["sourceGroup"]:
                comparable.fail("Duplicate questions must share a source group")
            questions[key] = case["sourceGroup"]
    return data


def freeze(data, manifest_path):
    validate_dataset(data)
    manifest = {"schemaVersion": 1, "datasetSha256": base.digest(data), "datasetVersion": data["version"],
                "caseCount": len(data["cases"]), "turnCount": sum(len(c["turns"]) for c in data["cases"]),
                "frozenAt": datetime.now(timezone.utc).isoformat(),
                "scope": "Question text, groups and rubrics only; no model answers or corpus frozen"}
    comparable.write_new(manifest_path, manifest)
    return manifest


def verify_freeze(data, manifest):
    validate_dataset(data)
    if (manifest.get("schemaVersion") != 1 or manifest.get("datasetSha256") != base.digest(data)
            or manifest.get("datasetVersion") != data["version"] or manifest.get("caseCount") != len(data["cases"])
            or manifest.get("turnCount") != sum(len(c["turns"]) for c in data["cases"])
            or not base.valid_timestamp(manifest.get("frozenAt"))):
        comparable.fail("Holdout changed after freeze; create another version")


def load_session(path=None, stdin=False):
    if stdin:
        token = json.loads(sys.stdin.read(16384)).get("token")
    else:
        text = path.read_text(encoding="utf-8-sig").strip()
        if text.startswith("{"):
            data = json.loads(text)
            tokens = set()
            if isinstance(data.get("token"), str):
                tokens.add(data["token"])
            for origin in data.get("origins", []):
                for entry in origin.get("localStorage", []):
                    if entry.get("name") == "auth-storage":
                        value = json.loads(entry["value"]).get("state", {}).get("token")
                        if isinstance(value, str): tokens.add(value)
            if len(tokens) != 1: comparable.fail("Session must contain exactly one website token")
            token = tokens.pop()
        else:
            token = text.removeprefix("Bearer ")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{20,8192}", token):
        comparable.fail("Invalid session credential")
    return token


class WebsiteClient:
    def __init__(self, origin, token, timeout=15):
        self.origin = base.checked_origin(origin)
        self.token = token
        self.timeout = timeout
        self.opener = request.build_opener(request.ProxyHandler({}), base.NoRedirect())

    def call(self, path, body=None):
        allowed = path in {"/api/auth/me", "/api/ai/status", "/api/ai/query"} or re.fullmatch(r"/api/ai/jobs/[A-Za-z0-9_-]{1,64}", path)
        if not allowed or body is not None and path != "/api/ai/query":
            comparable.fail("Only website identity/status/query/job endpoints are allowed")
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = request.Request(self.origin + path, data=payload,
                              headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                                       "User-Agent": "GYing-Holdout-Evaluation/1.0"})
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read(base.MAX_RESPONSE_BYTES + 1)
        except error.HTTPError as exc:
            comparable.fail("Website HTTP failure: " + str(exc.code))
        except (error.URLError, TimeoutError, OSError):
            comparable.fail("Website transport failed; submission was not replayed")
        if len(raw) > base.MAX_RESPONSE_BYTES: comparable.fail("Website response exceeds 1 MiB")
        data = json.loads(raw)
        if not isinstance(data, dict): comparable.fail("Website response is not an object")
        return data


def unwrap_job(job):
    if (job.get("status") not in {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"}
            or not ID.fullmatch(str(job.get("jobId", "")))):
        comparable.fail("Invalid website job contract")
    if job["status"] == "SUCCEEDED":
        result = job.get("result")
        if (not isinstance(result, dict) or not isinstance(result.get("answer"), str)
                or not isinstance(result.get("evidence"), list)
                or any(not isinstance(e, dict) for e in result["evidence"])):
            comparable.fail("Invalid website answer/evidence contract")
    return job


def history_for(turns, message):
    history, remaining = [], 10000 - len(message)
    for question, result in reversed(turns[-4:]):
        movies = [m for e in result["evidence"] for m in e.get("movies", [e["movie"]] if "movie" in e else [])]
        references = "\n".join(f"{i}. {m.get('titleCn') or m.get('titleEn')} ({m.get('year') or ''}) 片库 ID: {m.get('id')}" for i, m in enumerate(movies, 1))
        answer = "\n".join(s for s in (result["answer"], references) if s)[:6000]
        if len(question) + len(answer) > remaining: break
        history[0:0] = [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
        remaining -= len(question) + len(answer)
    return history


def safe_answer(text, token):
    text = text.replace(token, "[session omitted]")
    return re.sub(r"(?:https?://|magnet:)[^\s<>]+", "[link omitted]", text)


def sanitize_observation(value, token):
    if isinstance(value, str): return safe_answer(value, token)
    if isinstance(value, list): return [sanitize_observation(x, token) for x in value]
    if isinstance(value, dict): return {k: sanitize_observation(v, token) for k, v in value.items()}
    return value


def evaluate(client, data, selected, repeat, *, emit=print, sleep=time.sleep, min_interval=11):
    rows, observations, stopped = [], [], False
    planned = [(rep, case, i, turn) for rep in range(1, repeat + 1) for case in selected for i, turn in enumerate(case["turns"], 1)]
    prior_key, prior, last_submit = None, [], None
    for rep, case, index, turn in planned:
        row = {"caseId": case["id"], "sourceGroup": case["sourceGroup"], "split": "holdout", "repetition": rep,
               "turn": index, "executed": False, "passed": False, "manualReviewRequired": False}
        rows.append(row)
        if stopped:
            row["failure"] = "NOT_EXECUTED_AFTER_TRANSPORT_FAILURE"
            continue
        if prior_key != (rep, case["id"]): prior, prior_key = [], (rep, case["id"])
        if index > 1 and len(prior) != index - 1:
            row["failure"] = "NOT_EXECUTED_AFTER_PREVIOUS_TURN_FAILURE"
            continue
        if last_submit is not None:
            sleep(max(0, min_interval - (time.monotonic() - last_submit)))
        message = turn["message"]
        body = {"message": message, "history": history_for(prior, message) if turn["useHistory"] else []}
        row["historyEntries"] = len(body["history"])
        started = last_submit = time.monotonic()
        try:
            # Deliberately no requestKey: this must use exactly the current browser's submission shape.
            job = unwrap_job(client.call("/api/ai/query", body))
            deadline = time.monotonic() + 250
            while job["status"] in {"PENDING", "RUNNING"}:
                if time.monotonic() >= deadline: comparable.fail("Website job timed out; query was not resubmitted")
                sleep(2.5)
                job = unwrap_job(client.call("/api/ai/jobs/" + job["jobId"]))
            row["executed"] = True
            row["jobStatus"] = job["status"]
            row["route"] = "direct-catalog" if "catalog" in job.get("result", {}) else "model-queue"
            if job["status"] == "FAILED":
                row["checks"] = {"businessSuccess": False}
                row["failure"] = "WEBSITE_JOB_FAILED"
            else:
                result = job["result"]
                tools = [e.get("tool") for e in result["evidence"]]
                row["checks"] = {"businessSuccess": True, "nonEmptyAnswer": bool(result["answer"].strip()),
                                 "toolAllowlist": bool(tools) and all(t in TOOLS for t in tools),
                                 "queryTimestamps": bool(tools) and all(base.valid_timestamp(e.get("queriedAt")) for e in result["evidence"])}
                row["answerSha256"] = base.digest(result["answer"])
                row["answerChars"] = len(result["answer"])
                row["evidenceSha256"] = base.digest(result["evidence"])
                row["manualReviewRequired"] = True
                row["trace"] = base.safe_steps({"trace": result.get("trace", [])})
                row["passed"] = all(row["checks"].values())
                movies = [{k: m[k] for k in ("id", "titleCn", "titleEn", "year", "category", "rating", "summary", "reasons") if k in m}
                          for e in result["evidence"] for m in e.get("movies", [e["movie"]] if "movie" in e else [])]
                observations.append({"caseId": case["id"], "repetition": rep, "turn": index, "answerSha256": row["answerSha256"],
                                     "answer": safe_answer(result["answer"], client.token), "movies": movies,
                                     "catalog": result.get("catalog"), "purpose": "Observed answer for review only; never a reference answer"})
                prior.append((message, result))
        except (base.EvaluationError, ValueError, TypeError, KeyError) as exc:
            row["failure"] = str(exc) if isinstance(exc, base.EvaluationError) else "INVALID_WEBSITE_CONTRACT"
            stopped = True
        row["durationMs"] = round((time.monotonic() - started) * 1000, 2)
        emit(json.dumps({k: row[k] for k in ("caseId", "repetition", "turn", "passed", "durationMs")}), flush=True)
    result = base.summarize(rows)
    result.update({"plannedTurns": len(rows), "executedTurns": sum(r["executed"] for r in rows),
                   "notExecutedTurns": sum(not r["executed"] for r in rows),
                   "routeCounts": dict(collections.Counter(r.get("route") for r in rows if r["executed"])),
                   "qualityScope": "HTTP/job/tool evidence checks only; independent questions, human quality scores pending",
                   "failedTurns": [{k: r.get(k) for k in ("caseId", "repetition", "turn", "failure")} for r in rows if not r["passed"]],
                   "tokens": None, "monetaryCost": None})
    report = {"reportSchemaVersion": 1, "datasetSha256": base.digest(data), "datasetVersion": data["version"],
              "provenance": data["provenance"], "evaluatedAt": datetime.now(timezone.utc).isoformat(), "repeat": repeat,
              "transport": "authenticated-website-api", "completedAllTurns": all(r["executed"] for r in rows),
              "selectionSha256": base.digest([c["id"] for c in selected]), "summary": result, "rows": rows}
    return report, sanitize_observation(observations, client.token)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_DATASET.with_name("freeze.json"))
    parser.add_argument("--freeze", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--origin", default="http://127.0.0.1:8880")
    parser.add_argument("--session-file", type=Path)
    parser.add_argument("--session-stdin", action="store_true")
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--repeat", type=int, default=2)
    parser.add_argument("--output", type=Path, default=base.ROOT / "tmp/website-holdout/report.json")
    parser.add_argument("--observations", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.freeze and args.run or args.session_file and args.session_stdin: comparable.fail("Choose one action/session source")
        data = validate_dataset(comparable.read_json(args.dataset))
        if args.freeze:
            freeze(data, args.manifest); print("Questions/rubrics frozen; no model answers frozen"); return 0
        manifest = comparable.read_json(args.manifest); verify_freeze(data, manifest)
        base.checked_origin(args.origin)
        if not 1 <= args.repeat <= 3: comparable.fail("Repeat must be 1-3")
        selected = [c for c in data["cases"] if not args.case or c["id"] in args.case]
        if not selected or set(args.case) - {c["id"] for c in selected}: comparable.fail("Unknown selection")
        if not args.run:
            print(json.dumps({"mode": "plan", "networkRequests": 0, "cases": len(selected),
                              "plannedTurns": sum(len(c["turns"]) for c in selected) * args.repeat}, indent=2)); return 0
        if args.output.exists(): comparable.fail("Output exists; inference was not started")
        if not args.observations or args.observations.exists() or args.observations.resolve().is_relative_to(base.ROOT):
            comparable.fail("Review observations need a new private file outside the repository")
        if not args.session_file and not args.session_stdin: comparable.fail("Website session is required")
        token = load_session(args.session_file, args.session_stdin)
        client = WebsiteClient(args.origin, token)
        me = client.call("/api/auth/me")
        if me.get("role") != "USER": comparable.fail("Dedicated ordinary USER session required")
        status = client.call("/api/ai/status")
        if not status.get("catalogAvailable"): comparable.fail("Website catalog is not available")
        report, observations = evaluate(client, data, selected, args.repeat)
        report.update({"freezeSha256": base.digest(manifest), "evaluatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       "websiteIdentityRole": "USER", "browserUiCovered": False})
        comparable.write_new(args.observations, {"reportSha256": base.digest(report), "observations": observations})
        comparable.write_new(args.output, report)
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
        return 0 if report["completedAllTurns"] and not report["summary"]["mechanicalFailures"] else 1
    except (base.EvaluationError, OSError, ValueError, TypeError, KeyError) as exc:
        print(str(exc) if isinstance(exc, base.EvaluationError) else "Website evaluation invalid: " + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__": raise SystemExit(main())
