"""Read-only production baseline. JSON output contains states and paths, never credential values.
Run after deployment; FAIL means an unsafe observed state, UNKNOWN is not a pass.
"""
import argparse
import datetime
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path


def run(command, timeout=30):
    return subprocess.run(command,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout)


def collect(repo, probe=False):
    checks=[]
    def add(name,status,detail): checks.append({"check":name,"status":status,"detail":detail})
    compose=run(["docker","compose","-f",str(repo/"docker-compose.prod.yml"),"config","--quiet"])
    add("production_compose_contract","PASS" if compose.returncode==0 else "FAIL",
        "Validated without printing resolved environment" if compose.returncode==0 else "Local environment/configuration does not satisfy hardened Compose; inspect key presence privately")
    listing=run(["docker","ps","-aq"])
    if listing.returncode: add("docker","UNKNOWN","Docker daemon unavailable")
    elif listing.stdout.strip():
        inspected=run(["docker","inspect",*listing.stdout.split()])
        rows=json.loads(inspected.stdout) if inspected.returncode==0 else []
        for row in rows:
            name=row["Name"].lstrip("/")
            if not any(part in name for part in ("gying-movie", "minio-server", "openclaw-openclaw")): continue
            host=row["HostConfig"]; config=row["Config"]
            ports=[{"container_port":key, "host_ip":item.get("HostIp") or "0.0.0.0", "host_port":item.get("HostPort")}
                   for key,values in (host.get("PortBindings") or {}).items() for item in (values or [])]
            unsafe=[x for x in ports if x["host_ip"] not in ("127.0.0.1","::1")]
            add(name+":published_ports","FAIL" if unsafe else "PASS",ports)
            mounts=[m["Destination"] for m in row.get("Mounts",[])]
            socket=any("docker.sock" in m or "docker_engine" in m for m in mounts)
            add(name+":docker_socket","FAIL" if socket else "PASS",socket)
            add(name+":privileged","FAIL" if host.get("Privileged") else "PASS",bool(host.get("Privileged")))
            add(name+":no_new_privileges","PASS" if any("no-new-privileges" in x for x in host.get("SecurityOpt") or []) else "FAIL",host.get("SecurityOpt") or [])
            # Image Config.User alone is not evidence of PID 1 UID (Redis drops root in its entrypoint).
            top=run(["docker","top",name,"-eo","user,comm"])
            processes=[line.split() for line in top.stdout.splitlines()[1:] if line.strip()] if top.returncode==0 else []
            root_processes=[x[-1] for x in processes if x[0] in ("root","0")]
            add(name+":root_processes","FAIL" if root_processes else ("PASS" if processes else "UNKNOWN"),root_processes)
            env=dict(item.split("=",1) for item in config.get("Env",[]) if "=" in item)
            if "backend" in name or "gying-source" in name or "social-publisher" in name:
                user=env.get("GYING_DB_USER",env.get("DB_USER",""))
                add(name+":non_root_db_user","PASS" if user and user.lower()!="root" else "FAIL",{"configured":bool(user),"is_root":user.lower()=="root"})
            token_key="GYING_SOURCE_API_TOKEN" if "gying-source" in name else "SOCIAL_PUBLISHER_TOKEN" if "social-publisher" in name else None
            if token_key: add(name+":internal_token","PASS" if len(env.get(token_key,"").encode())>=32 else "FAIL",{"key":token_key,"meets_minimum":len(env.get(token_key,"").encode())>=32})
            if "redis" in name:
                ping=run(["docker","exec",name,"redis-cli","PING"])
                add(name+":redis_anonymous_auth","PASS" if "NOAUTH" in ping.stdout else "FAIL","Unauthenticated PING denied" if "NOAUTH" in ping.stdout else "Expected NOAUTH not observed")
    if probe:
        for path,expected in [("/",200),("/api/movies/list?page=1&size=1",200),("/api/admin/users",401),
                              ("/api/internal/resource-hub/health",404),("/api/qq-bot/health",404),("/media/private.txt",404)]:
            try:
                with urllib.request.urlopen("http://127.0.0.1"+path,timeout=8) as response: status=response.status
            except urllib.error.HTTPError as error: status=error.code
            except Exception: status=None
            add("origin:"+path,"PASS" if status==expected else "FAIL",{"expected":expected,"observed":status})
    return {"collected_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"scope":"read-only local origin and Docker; Cloudflare account policies, external reachability, DB grants and restore drills require separate verification", "checks":checks}


def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--repo",default=str(Path(__file__).resolve().parents[2])); parser.add_argument("--probe",action="store_true")
    parser.add_argument("--output"); args=parser.parse_args()
    report=collect(Path(args.repo).resolve(),args.probe)
    report["summary"]={status:sum(c["status"]==status for c in report["checks"]) for status in ("PASS","FAIL","UNKNOWN")}
    text=json.dumps(report,ensure_ascii=False,indent=2)
    if args.output: Path(args.output).write_text(text,encoding="utf-8")
    print(text)
    return 1 if report["summary"]["FAIL"] or report["summary"]["UNKNOWN"] else 0


if __name__=="__main__":
    raise SystemExit(main())
