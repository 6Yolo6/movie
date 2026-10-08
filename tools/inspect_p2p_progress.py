"""Read-only GYING cursor/P2P verification. No retries, uploads, or publishing.

Uses an existing protected MySQL defaults file; credentials are neither loaded by
Python nor passed on the command line. Only allowlisted evidence is selected.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
from pathlib import Path

LOCAL_TZ = dt.timezone(dt.timedelta(hours=8), "Asia/Shanghai")
SOURCES = ("CSCORE_MOVIE", "CSCORE_TV", "CSCORE_ANIME", "HITS_MOVIE", "HITS_TV", "HITS_ANIME")
STATES = ("PENDING", "RUNNING", "FAILED", "SUCCEEDED", "CANCELED")
MAX_RETRY_EVIDENCE = 1000


def parse_since(value):
    stamp = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=LOCAL_TZ)
    return stamp.astimezone(LOCAL_TZ)


def integer(value, low=0, high=2**63 - 1):
    return value if type(value) is int and low <= value <= high else None


def timestamp(value):
    if not isinstance(value, str):
        return None
    try:
        return dt.datetime.strptime(value, "%Y-%m-%d %H:%M:%S").replace(tzinfo=LOCAL_TZ).isoformat()
    except ValueError:
        return None


def snapshot_sql(since, sources):
    if not sources or any(source not in SOURCES for source in sources):
        raise ValueError("Unsupported source")
    boundary = parse_since(since).strftime("%Y-%m-%d %H:%M:%S")
    selected = ",".join("'" + source + "'" for source in sources)
    payload = "IF(JSON_VALID(payload),payload,'{}')"
    fields = {"page": "page", "offset": "crawl.offset", "start_page": "crawl.startPage",
              "end_page": "crawl.endPage", "page_size": "crawl.pageSize", "attempted": "crawl.attempted",
              "failed": "crawl.failed", "deferred": "crawl.deferred", "next_page": "crawl.nextPage",
              "next_offset": "crawl.nextOffset", "retry_ids": "itemRetryTaskIds"}
    extracts = ",\n".join("'%s',JSON_EXTRACT(%s,'$.%s')" % (key, payload, path) for key, path in fields.items())
    return f"""
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT JSON_OBJECT('kind','connection',
 'non_root',LOWER(SUBSTRING_INDEX(CURRENT_USER(),'@',1))<>'root',
 'read_only',@@session.transaction_read_only,
 'utc_offset_seconds',TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()),
 'observed_at',DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'));
SELECT JSON_OBJECT('kind','identity','collation',COLLATION_NAME)
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='movie_source_identity' AND COLUMN_NAME='external_id';
SELECT JSON_OBJECT('kind','metadata','id',id,'source',keyword,'status',status,
 'created_at',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s'),
 'finished_at',DATE_FORMAT(finished_at,'%Y-%m-%d %H:%i:%s'),{extracts})
 FROM (SELECT id,keyword,status,created_at,finished_at,payload,
 ROW_NUMBER() OVER (PARTITION BY keyword ORDER BY id DESC) AS position_rank
 FROM resource_hub_task WHERE task_type='METADATA_SYNC' AND source='GYING'
 AND keyword IN ({selected}) AND created_at>='{boundary}'
 AND JSON_UNQUOTE(JSON_EXTRACT({payload},'$.crawl.mode'))='RANGE') recent
 WHERE position_rank<=3 ORDER BY id;
SELECT JSON_OBJECT('kind','retry','id',id,'status',status,'attempts',attempts,'max_attempts',max_attempts)
 FROM resource_hub_task WHERE task_type='METADATA_ITEM_RETRY' AND source='GYING'
 ORDER BY id DESC LIMIT {MAX_RETRY_EVIDENCE};
SELECT JSON_OBJECT('kind','retry_summary','status',status,'count',COUNT(*),
 'exhausted',SUM(status='FAILED' AND attempts>=max_attempts))
 FROM resource_hub_task WHERE task_type='METADATA_ITEM_RETRY' AND source='GYING' GROUP BY status;
SELECT JSON_OBJECT('kind','archive_summary','provider',source,'status',status,'count',COUNT(*),
 'new_count',SUM(created_at>='{boundary}'),
 'historical_exhausted',SUM(created_at<'{boundary}' AND status='FAILED' AND attempts>=max_attempts))
 FROM resource_hub_task WHERE task_type='P2P_ARCHIVE' AND source IN ('QUARK','XUNLEI') GROUP BY source,status;
SELECT JSON_OBJECT('kind','resource_summary','provider',provider,'new_count',COUNT(*),
 'active_approved_normal',SUM(deleted_at IS NULL AND status='ACTIVE' AND audit_status=1 AND link_status='NORMAL'))
 FROM resource_link WHERE source='GYING_P2P_ARCHIVE' AND created_at>='{boundary}'
 AND provider IN ('QUARK','XUNLEI') GROUP BY provider;
ROLLBACK;
""".strip()


def read_snapshot(mysql, defaults_file, database, host, port, sql):
    if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", database) or not 1 <= port <= 65535:
        raise ValueError("Invalid database connection parameters")
    defaults = Path(defaults_file).resolve(strict=True)
    if not defaults.is_file():
        raise ValueError("A protected defaults file is required")
    command = [str(mysql), "--defaults-file=" + str(defaults), "--protocol=TCP", "--host=" + host,
               "--port=" + str(port), "--database=" + database, "--connect-timeout=5", "--skip-reconnect", "--skip-force",
               "--init-command=SET SESSION TRANSACTION READ ONLY", "--local-infile=0", "--batch", "--raw", "--skip-column-names",
               "--default-character-set=utf8mb4"]
    env = os.environ.copy()
    env.pop("MYSQL_PWD", None)  # Do not let an inherited password override the explicit defaults file.
    result = subprocess.run(command, input=sql, env=env, capture_output=True, text=True,
                            encoding="utf-8", errors="strict", timeout=45)
    if result.returncode:
        raise ValueError("Read-only database query failed")  # Never echo client output/errors.
    records = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if not records or any(not isinstance(row, dict) for row in records):
        raise ValueError("Incomplete read-only snapshot")
    return records


def cursor_evidence(row, retries):
    evidence = {"task_id": integer(row.get("id"), 1),
                "task_status": row.get("status") if row.get("status") in STATES else "UNKNOWN",
                "created_at": timestamp(row.get("created_at")), "finished_at": timestamp(row.get("finished_at"))}
    def result(status, reason):
        return dict(evidence, status=status, reason=reason)
    if evidence["task_id"] is None:
        return result("UNKNOWN", "invalid_task_identity")
    if evidence["task_status"] in ("PENDING", "RUNNING"):
        return result("UNKNOWN", "batch_not_finished")
    if evidence["task_status"] != "SUCCEEDED":
        return result("FAIL" if evidence["task_status"] == "FAILED" else "UNKNOWN", "batch_not_successful")
    values = {key: integer(row.get(key), 0, 10000) for key in
              ("page", "offset", "start_page", "end_page", "page_size", "attempted", "failed")}
    values["deferred"] = integer(row.get("deferred") if row.get("deferred") is not None else 0, 0, 20)
    if any(value is None for value in values.values()):
        return result("UNKNOWN", "incomplete_cursor_evidence")
    page, offset, start, end = (values[k] for k in ("page", "offset", "start_page", "end_page"))
    if not 1 <= start <= page <= end <= 500 or values["attempted"] > 20 or not 0 <= values["deferred"] <= values["failed"] <= values["attempted"]:
        return result("UNKNOWN", "invalid_cursor_bounds")
    if values["attempted"] > max(0, values["page_size"] - offset):
        return result("UNKNOWN", "attempted_work_exceeds_observed_page")
    evidence.update({"from": {"page": page, "offset": offset},
                     "attempted": values["attempted"], "failed": values["failed"], "deferred": values["deferred"]})
    if values["failed"] != values["deferred"]:
        return result("FAIL", "failed_items_not_durably_deferred")
    next_page, next_offset = integer(row.get("next_page"), 1, 500), integer(row.get("next_offset"), 0, 10000)
    if next_page is None or next_offset is None:
        return result("FAIL", "succeeded_without_next_cursor")
    expected_page, expected_offset = page, offset + values["attempted"]
    wrapped = expected_offset >= values["page_size"]
    if wrapped:
        expected_page = start if values["page_size"] == 0 or page >= end else page + 1
        expected_offset = 0
    evidence["to"] = {"page": next_page, "offset": next_offset}
    if (next_page, next_offset) != (expected_page, expected_offset):
        return result("FAIL", "next_cursor_does_not_match_completed_work")
    if (next_page, next_offset) == (page, offset) and not wrapped:
        return result("FAIL", "succeeded_without_progress")
    retry_ids = row.get("retry_ids")
    if retry_ids is None:
        retry_ids = []
    if not isinstance(retry_ids, list) or len(retry_ids) != values["deferred"] or any(integer(v, 1) is None for v in retry_ids):
        return result("UNKNOWN", "invalid_retry_checkpoint_references")
    if len(set(retry_ids)) != len(retry_ids):
        return result("UNKNOWN", "duplicate_retry_checkpoint_references")
    evidence["retry_task_ids"] = retry_ids
    if any(task_id not in retries for task_id in retry_ids):
        return result("UNKNOWN", "retry_checkpoint_not_observed")
    evidence["retry_states"] = [retries[task_id]["status"] for task_id in retry_ids]
    return result("PASS", "range_cycle_completed" if wrapped and next_page == start else "cursor_advanced")


def build_report(records, since, sources=SOURCES[:3]):
    checks = []
    def check(name, status, reason):
        checks.append({"check": name, "status": status, "reason": reason})
    connection = [r for r in records if r.get("kind") == "connection"]
    observed_at = None
    if len(connection) != 1:
        check("read_only_connection", "UNKNOWN", "connection_evidence_missing_or_duplicated")
    else:
        conn = connection[0]
        observed_at = timestamp(conn.get("observed_at"))
        safe = conn.get("non_root") == 1 and conn.get("read_only") == 1 and conn.get("utc_offset_seconds") == 28800
        check("read_only_connection", "PASS" if safe else "FAIL", "non_root_read_only_utc_plus_8" if safe else "connection_boundary_not_verified")
    identities = [r for r in records if r.get("kind") == "identity"]
    status = "UNKNOWN" if len(identities) != 1 else "PASS" if identities[0].get("collation") == "utf8mb4_bin" else "FAIL"
    check("source_identity_collation", status, "requires_utf8mb4_bin")
    retries, duplicate_retries = {}, set()
    for row in records:
        if row.get("kind") != "retry":
            continue
        task_id = integer(row.get("id"), 1)
        if task_id is None or row.get("status") not in STATES:
            continue
        if task_id in retries:
            duplicate_retries.add(task_id)
        retries[task_id] = {"status": row["status"], "attempts": integer(row.get("attempts")),
                            "max_attempts": integer(row.get("max_attempts"), 1)}
    for task_id in duplicate_retries:
        retries.pop(task_id, None)
    cursor_reports, isolated = [], []
    for source in sources:
        batches = [r for r in records if r.get("kind") == "metadata" and r.get("source") == source]
        batches.sort(key=lambda r: integer(r.get("id"), 1) or 0, reverse=True)
        evidence = cursor_evidence(batches[0], retries) if batches else {"status": "UNKNOWN", "reason": "no_batch_observed_in_window"}
        cursor_reports.append(dict(evidence, source=source))
        check("cursor:" + source, evidence["status"], evidence["reason"])
        for row in batches:
            if (integer(row.get("failed")) or 0) > 0:
                isolated.append(cursor_evidence(row, retries))
    isolation_status = "FAIL" if any(r["status"] == "FAIL" for r in isolated) else "PASS" if isolated and all(r["status"] == "PASS" for r in isolated) else "UNKNOWN"
    check("live_failure_isolation", isolation_status, "durable_retry_references_checked" if isolation_status == "PASS" else
          "no_live_failed_item_observed" if not isolated else "failure_isolation_incomplete")
    summaries = {"archive_summary": [], "resource_summary": [], "retry_summary": []}
    for row in records:
        kind = row.get("kind")
        if kind not in summaries:
            continue
        safe = {}
        if kind != "retry_summary":
            if row.get("provider") not in ("QUARK", "XUNLEI"):
                continue
            safe["provider"] = row["provider"]
        if kind != "resource_summary":
            if row.get("status") not in STATES:
                continue
            safe["status"] = row["status"]
        fields = {"archive_summary": ("count", "new_count", "historical_exhausted"),
                  "resource_summary": ("new_count", "active_approved_normal"),
                  "retry_summary": ("count", "exhausted")}[kind]
        safe.update({key: integer(row.get(key)) for key in fields})
        summaries[kind].append(safe)
    return {"observed_at": observed_at, "since": parse_since(since).isoformat(),
            "scope": "Read-only task/checkpoint evidence; no retries or provider writes. Resource rows do not prove remote share availability.",
            "checks": checks, "summary": {s: sum(c["status"] == s for c in checks) for s in ("PASS", "FAIL", "UNKNOWN")},
            "cursors": cursor_reports, **summaries,
            "retry_evidence_limit": MAX_RETRY_EVIDENCE,
            "limitations": ["Live failure isolation stays UNKNOWN until a failed item is naturally observed.",
                            "Historical failed archives are reported, never reset or replayed."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql", help="Existing MySQL 8 client executable")
    parser.add_argument("--defaults-file", required=True, help="Existing protected non-root client defaults file")
    parser.add_argument("--database", default="gying")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--since", required=True, help="ISO timestamp; unzoned values use Asia/Shanghai")
    parser.add_argument("--source", action="append", choices=SOURCES)
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    try:
        sources = tuple(dict.fromkeys(args.source or SOURCES[:3]))
        sql = snapshot_sql(args.since, sources)
        records = read_snapshot(args.mysql, args.defaults_file, args.database, args.host, args.port, sql)
        report = build_report(records, args.since, sources)
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        report = {"summary": {"PASS": 0, "FAIL": 0, "UNKNOWN": 1},
                  "error": "read_only_snapshot_unavailable; verify client/defaults/permissions/schema locally"}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        try:
            Path(args.output).write_text(text, encoding="utf-8")
        except OSError:
            print(json.dumps({"error": "report_output_unavailable"}))
            return 1
    print(text)
    return int(bool(report["summary"]["FAIL"] or report["summary"]["UNKNOWN"]))


if __name__ == "__main__":
    raise SystemExit(main())
