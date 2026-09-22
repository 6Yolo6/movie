"""Conservative offline secret scan. Outputs only file/line/rule, never matched values.
Not a replacement for Gitleaks/TruffleHog or provider-side credential rotation.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

SENSITIVE = r"(?:[A-Za-z0-9_]*(?:PASSWORD|SECRET|COOKIE|TOKEN|API_KEY|ACCESS_KEY|DB_PASS)[A-Za-z0-9_]*)"
ASSIGNMENT = re.compile(r"(?i)(?P<key>" + SENSITIVE + r")[\"']?\s*[:=]\s*([\"'])(?P<value>[^\r\n]*?)\2")
PLACEHOLDER = re.compile(r"(?i)^(?:|<[^>]+>|\$\{.*\}|(?:your|replace|change|example|test|dummy|fixture)[-_ ].*|true|false|auto|ENV)$")
ENV_DEFAULT = re.compile(r"\$\{(?P<key>" + SENSITIVE + r"):(?P<value>[^{}]+)\}", re.I)
HASH = re.compile(r"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}")


def findings(text, path):
    if path.endswith("package-lock.json") or "/public/locales/" in path.replace("\\", "/"): return []
    if any(x in path.replace("\\", "/").split("/") for x in ("test", "tests", "__tests__")) or path.endswith(".test.mjs"):
        return []
    result=[]
    for number,line in enumerate(text.splitlines(),1):
        if line.lstrip().startswith(("#", "//", "--")): continue
        if HASH.search(line) and path.endswith(".sql"):
            result.append({"path":path,"line":number,"rule":"seeded-password-hash"})
        for pattern in (ASSIGNMENT, ENV_DEFAULT):
            for match in pattern.finditer(line):
                if pattern is ASSIGNMENT and "getpass(" in line[:match.start()]: continue
                value=match.group("value").strip()
                key=re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", match.group("key")).lower()
                if key in ("number_token", "cookietime"): continue  # grammar token / cookie TTL, not credentials
                if not re.search(r"(?:password|secret|cookie(?:_str)?|token|api_key|access_key|db_pass)$", key): continue
                if pattern is ENV_DEFAULT and value.startswith("?"): continue
                if pattern is ENV_DEFAULT and value.startswith("-"): value=value[1:]
                if value.startswith("${"): continue
                if any(x in key for x in ("_url", "_path", "_enabled", "_per_", "_pattern", "_type", "_file")): continue
                if len(value)<8 or PLACEHOLDER.fullmatch(value): continue
                if key.endswith("_key") and value in ("gying_media", "admin"): continue
                result.append({"path":path,"line":number,"rule":"credential-literal"})
    return result


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--history",action="store_true"); args=parser.parse_args()
    root=Path(subprocess.check_output(["git","rev-parse","--show-toplevel"],text=True).strip())
    files=subprocess.check_output(["git","-C",str(root),"ls-files","--cached","--others","--exclude-standard","-z"]).decode().split("\0")
    current=[]
    for name in files:
        p=root/name
        if not p.is_file() or p.stat().st_size>2_000_000 or p.suffix in (".png",".jpg",".ico",".lock"): continue
        try: current.extend(findings(p.read_text(encoding="utf-8"),name))
        except UnicodeError: continue
    report={"scope":"working tree (tracked + non-ignored new files)","findings":current}
    if args.history:
        # Batch-read reachable blobs; avoid O(commits * files) scans and never print raw git output.
        objects=subprocess.check_output(["git","-C",str(root),"rev-list","--objects","--all"],text=True).splitlines()
        proc=subprocess.Popen(["git","-C",str(root),"cat-file","--batch"],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
        historical=[]; count=0
        for row in objects:
            oid,_,name=row.partition(" ")
            if not name or Path(name).suffix not in (".py",".yml",".yaml",".env",".example",".sql",".md",".json",".toml",".ps1",".java",".js",".ts",".tsx",".mjs"): continue
            proc.stdin.write((oid+"\n").encode()); proc.stdin.flush()
            header=proc.stdout.readline().decode().split()
            if len(header)!=3: continue
            data=proc.stdout.read(int(header[2])); proc.stdout.read(1)
            if header[1]!="blob": continue
            count+=1
            try: found=findings(data.decode("utf-8"),name)
            except UnicodeError: continue
            historical.extend(dict(item,blob=oid[:12]) for item in found)
        proc.stdin.close(); proc.wait()
        report["history_blobs_scanned"]=count; report["historical_findings"]=historical
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 1 if current or report.get("historical_findings") else 0


if __name__=="__main__":
    raise SystemExit(main())
