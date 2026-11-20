"""Read a metadata-only catalog snapshot using an existing source container. No credentials exported."""
import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
import evaluate_agent as base
import comparable_evaluation as comparable

CODE = r'''import os,json,pymysql
connection=pymysql.connect(host=os.environ.get("GYING_DB_HOST","host.docker.internal"),port=int(os.environ.get("GYING_DB_PORT","3306")),user=os.environ["GYING_DB_USER"],password=os.environ["GYING_DB_PASSWORD"],database=os.environ.get("GYING_DB_NAME","gying"),connect_timeout=5,read_timeout=30,cursorclass=pymysql.cursors.DictCursor)
try:
 with connection.cursor() as cursor:
  cursor.execute("SET SESSION MAX_EXECUTION_TIME=15000")
  cursor.execute("SET TRANSACTION READ ONLY")
  connection.begin()
  cursor.execute("SELECT COUNT(*) total FROM movie_metadata WHERE status='ACTIVE' AND deleted_at IS NULL")
  total=cursor.fetchone()["total"]
  cursor.execute("SELECT m.id,m.title_cn,m.title_en,m.year,m.category,m.series_name,m.genres,m.directors,m.actors,m.summary,m.aliases,m.regions,m.languages,m.runtime,m.release_dates,m.douban_score,m.imdb_score,m.tmdb_vote_average,m.tmdb_popularity,m.popularity,GREATEST(COALESCE(m.douban_score,0),COALESCE(m.imdb_score,0),COALESCE(m.tmdb_vote_average,0)) rating,COALESCE(m.tmdb_popularity,0)+COALESCE(m.popularity,0) heat,(SELECT COUNT(*) FROM resource_link r WHERE r.movie_id=m.id AND r.status='ACTIVE' AND r.audit_status=1 AND r.deleted_at IS NULL AND r.link_status='NORMAL') resources FROM movie_metadata m WHERE m.status='ACTIVE' AND m.deleted_at IS NULL ORDER BY m.id LIMIT 50000")
  rows=cursor.fetchall()
  connection.rollback()
 print(json.dumps({"schemaVersion":1,"activeTotal":total,"indexLimit":50000,"truncated":total>50000,"movies":rows},ensure_ascii=False,default=str))
finally:
 connection.close()
'''


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run",action="store_true")
    parser.add_argument("--container",default="gying-movie-gying-source-1")
    parser.add_argument("--snapshot",type=Path)
    parser.add_argument("--manifest",type=Path,default=base.ROOT / "docs/evaluation/human-holdout-v1/catalog-snapshot.json")
    args=parser.parse_args(argv)
    try:
        if not args.run:
            print(json.dumps({"mode":"plan","databaseWrites":0,"tables":["movie_metadata","resource_link counts"],"userData":False,"networkRequests":0}));return 0
        if args.manifest.exists() or not args.snapshot or args.snapshot.exists() or args.snapshot.resolve().is_relative_to(base.ROOT):
            comparable.fail("New external snapshot and new manifest paths are required")
        if args.container != "gying-movie-gying-source-1": comparable.fail("This adapter requires the known existing source container")
        # Docker is used only for an existing Python/PyMySQL runtime and its already-scoped environment.
        result=subprocess.run(["docker","exec","-i",args.container,"python","-"],input=CODE,text=True,encoding="utf-8",capture_output=True,timeout=60)
        if result.returncode: comparable.fail("Read-only catalog snapshot failed; provider output omitted")
        data=json.loads(result.stdout)
        if data.get("schemaVersion")!=1 or not isinstance(data.get("movies"),list): comparable.fail("Invalid snapshot response")
        ids=[m["id"] for m in data["movies"]]
        if len(ids)!=len(set(ids)): comparable.fail("Duplicate snapshot IDs")
        comparable.write_new(args.snapshot,data)
        manifest={"schemaVersion":1,"capturedAt":datetime.now(timezone.utc).isoformat(),"canonicalSha256":base.digest(data),
                  "fileSha256":hashlib.sha256(args.snapshot.read_bytes()).hexdigest(),"activeTotal":data["activeTotal"],
                  "snapshotRows":len(ids),"truncated":data["truncated"],"privateSnapshotPath":str(args.snapshot),
                  "transaction":"read-only consistent catalog read; rolled back","userDataIncluded":False,"resourceUrlsIncluded":False,
                  "scope":"Reusable offline metadata corpus; online website/agent runs still use live data, not this frozen corpus"}
        comparable.write_new(args.manifest,manifest)
        print(json.dumps(manifest,ensure_ascii=False,indent=2));return 0
    except (base.EvaluationError,OSError,ValueError,KeyError,subprocess.SubprocessError) as exc:
        print(str(exc) if isinstance(exc,base.EvaluationError) else "Snapshot failed: "+type(exc).__name__);return 2


if __name__=="__main__":raise SystemExit(main())
