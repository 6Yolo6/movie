"""Build an offline quality-review page without modifying frozen evaluation artifacts."""
import argparse
import json
from pathlib import Path

import comparable_evaluation as comparable
import evaluate_agent as base
import quality_review as quality


def build_payload(report, dataset, observations, review=None):
    template = quality.review_template(report)
    if report.get("datasetSha256") != base.digest(dataset):
        quality.fail("Dataset does not match the exact report")
    if not isinstance(observations, dict) or observations.get("reportSha256") != base.digest(report):
        quality.fail("Observations do not match the exact report")
    cases = dataset.get("cases")
    if not isinstance(cases, list) or any(not isinstance(c, dict) for c in cases):
        quality.fail("Dataset requires cases")
    by_case = {c.get("id"): c for c in cases}
    if len(by_case) != len(cases):
        quality.fail("Dataset contains duplicate cases")
    for row in report["rows"]:
        case = by_case.get(row["caseId"])
        if not case or not isinstance(case.get("turns"), list) or row["turn"] > len(case["turns"]):
            quality.fail("Report row has no matching dataset turn")
    expected = {quality.row_key(r): r["answerSha256"] for r in report["rows"] if r.get("answerSha256")}
    observed = observations.get("observations")
    if not isinstance(observed, list):
        quality.fail("Observations require a list")
    seen = set()
    for row in observed:
        key = quality.row_key(row)
        if key in seen or key not in expected or row.get("answerSha256") != expected[key] or not isinstance(row.get("answer"), str):
            quality.fail("Observation is duplicated or does not match a report answer")
        seen.add(key)
    if seen != set(expected):
        quality.fail("Review observations are incomplete")
    review = template if review is None else review
    summary = quality.apply_review(report, review)
    return {"report": report, "dataset": dataset, "observations": observed,
            "review": review, "template": template, "summary": summary}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("report", "dataset", "observations", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--review", type=Path, help="Resume a validated schemaVersion=2 review")
    args = parser.parse_args(argv)
    try:
        if args.output.exists() or args.output.resolve().is_relative_to(base.ROOT):
            quality.fail("Review HTML requires a new file outside the repository")
        payload = build_payload(*(comparable.read_json(p) for p in (args.report, args.dataset, args.observations)),
                                comparable.read_json(args.review) if args.review else None)
        folder = Path(__file__).resolve().parent
        serialized = json.dumps(payload, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        html = (folder / "quality_review.html").read_text(encoding="utf-8")
        script = (folder / "quality_review_ui.js").read_text(encoding="utf-8")
        html = html.replace("__SCRIPT__", script).replace("__DATA__", serialized)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(html)
        print("Offline quality review page created; scores were not inferred")
        return 0
    except (base.EvaluationError, OSError, ValueError, KeyError, TypeError) as exc:
        print(str(exc) if isinstance(exc, base.EvaluationError) else "Review setup invalid: " + type(exc).__name__)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
