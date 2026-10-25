"""Graceful weekly Windows maintenance. Default is read-only; never force-kill services."""
import argparse
import base64
import contextlib
import ctypes
import datetime as dt
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
import urllib.request

STOP = ["gying-movie-nginx-1", "gying-movie-frontend-1", "openclaw-openclaw-gateway-1",
        "gying-movie-backend-1", "gying-movie-social-publisher-1", "gying-movie-gying-source-1",
        "gying-movie-quark-auto-save-1", "gying-xunlei-path-probe-20261006",
        "gying-movie-pansou-1", "minio-server", "gying-movie-redis-1"]
START = ["gying-movie-redis-1", "minio-server", "gying-movie-pansou-1",
         "gying-movie-quark-auto-save-1", "gying-movie-gying-source-1",
         "gying-movie-social-publisher-1", "gying-movie-backend-1",
         "gying-movie-frontend-1", "gying-movie-nginx-1",
         "openclaw-openclaw-gateway-1", "gying-xunlei-path-probe-20261006"]
BUSY = {"resource_hub_task": ["RUNNING"],
        "quark_transfer_task": ["RUNNING", "TRANSFERRING", "SHARING", "PROCESSING"],
        "xunlei_transfer_task": ["RUNNING", "TRANSFERRING", "SHARING", "PROCESSING"],
        "social_post_log": ["PREPARING", "PUBLISHING"],
        "qq_transfer_cleanup_job": ["RUNNING", "PROCESSING"]}
LOCAL = {"ollama": 11434, "backend": 5000, "router": 5002, "frontend": 3000}
DONE = {"recovered", "cancelled"}
TASK_NAMES = {"GYing Cloudflare Tunnel", "GYing QQ Channel Auto Post", "GYing Xunlei Token Sync"}
STALE_ROWS = [
    {"table": "resource_hub_task", "id": "3426", "status": "RUNNING", "updated_at": "2026-09-28 19:56:43"},
    {"table": "xunlei_transfer_task", "id": "434", "status": "RUNNING", "updated_at": "2026-09-28 20:45:54"},
]
CST = dt.timezone(dt.timedelta(hours=8))

class Blocked(RuntimeError):
    pass

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def save(path, obj):
    path = Path(path)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temp.open("w", encoding="utf-8") as stream:
        json.dump(obj, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)

def cmd(args, timeout=60, check=True):
    try:
        result = subprocess.run(args, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Blocked("Command timeout: " + Path(args[0]).name)
    if check and result.returncode:
        raise Blocked("Command failed: " + Path(args[0]).name + " exit " + str(result.returncode))
    return result.stdout.decode("utf-8", errors="replace").strip()

def ps(script, timeout=60):
    script = "$ErrorActionPreference='Stop';[Console]::OutputEncoding=[Text.UTF8Encoding]::new();" + script
    encoded = base64.b64encode(script.encode("utf-16le")).decode()
    return cmd(["powershell.exe", "-NoProfile", "-EncodedCommand", encoded], timeout)

def q(value):
    return "'" + str(value).replace("'", "''") + "'"

def boot():
    return ps("(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')")

def healthy(url):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=5) as response:
            return response.status == 200
    except Exception:
        return False

def docker(c, *args, timeout=60):
    return cmd([c["docker"], *args], timeout)

def containers(c):
    ids = docker(c, "ps", "-aq").split()
    if not ids:
        raise Blocked("No existing containers")
    # Narrow projection: never collect environment variables or credentials.
    template = '{{json .Name}}|{{json .Id}}|{{json .Image}}|{{json .State}}|{{json .HostConfig.RestartPolicy.Name}}'
    rows = docker(c, "inspect", "--format", template, *ids).splitlines()
    result = {}
    for line in rows:
        name, cid, image, state, restart = [json.loads(part) for part in line.split("|", 4)]
        result[name.lstrip("/")] = {"id": cid, "image": image, "running": state["Running"],
            "paused": state["Paused"], "restarting": state["Restarting"],
            "exitCode": state["ExitCode"], "started": state["StartedAt"],
            "health": state.get("Health", {}).get("Status"), "restart": restart}
    return result

def tasks(c):
    raw = ps("@(Get-ScheduledTask -TaskName " + ",".join(q(n) for n in c["tasks"]) +
             " | ForEach-Object {[PSCustomObject]@{name=$_.TaskName;enabled=$_.Settings.Enabled;state=[string]$_.State}})|ConvertTo-Json -Compress")
    data = json.loads(raw)
    return data if isinstance(data, list) else [data]

def active(rows, ignored):
    allow = {(r["table"], str(r["id"]), r["status"], r["updated_at"]) for r in ignored}
    return [r for r in rows if (r["table"], str(r["id"]), r["status"], r["updated_at"]) not in allow]

def jobs(c):
    statements = []
    for table, states in BUSY.items():
        values = ",".join("'" + s + "'" for s in states)
        statements.append("SELECT '" + table + "',id,status,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') FROM " +
                          table + " WHERE status IN (" + values + ")")
    sql = "SET SESSION TRANSACTION READ ONLY;START TRANSACTION READ ONLY;" + " UNION ALL ".join(statements) + ";ROLLBACK;"
    raw = cmd([c["mysql"], "--defaults-extra-file=" + c["mysqlDefaults"], "--batch", "--raw",
               "--skip-column-names", "--default-character-set=utf8mb4", "gying", "-e", sql])
    return [dict(zip(["table", "id", "status", "updated_at"], line.split("\t"))) for line in raw.splitlines()]

def assert_idle(c):
    busy = active(jobs(c), c["ignoredStaleRows"])
    if busy:
        raise Blocked("Active business jobs; restart cancelled: " + json.dumps(busy))

def pending(c):
    return [p for p in sorted(Path(c["stateRoot"]).glob("*/state.json")) if read(p)["phase"] not in DONE]

@contextlib.contextmanager
def lock(c):
    """OS byte lock is released even if the controller crashes; never use PID-only locks."""
    import msvcrt
    root = Path(c["stateRoot"])
    root.mkdir(parents=True, exist_ok=True)
    with (root / "maintenance.lock").open("a+b") as handle:
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise Blocked("Another maintenance/recovery process owns the lock")
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)

def within_window(value, final=False):
    value = value.astimezone(CST)
    minutes = value.hour * 60 + value.minute
    return value.date() >= dt.date(2026, 10, 25) and value.weekday() == 6 and 570 <= minutes < (630 if final else 580)

def assert_window(final=False):
    if not within_window(dt.datetime.now(dt.timezone.utc), final):
        raise Blocked("Outside Sunday 09:30 maintenance window; no catch-up reboot")

def local_running():
    raw = ps("@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalPort -Unique)|ConvertTo-Json -Compress")
    ports = json.loads(raw or "[]")
    if isinstance(ports, int):
        ports = [ports]
    return [name for name, port in LOCAL.items() if port in ports]

def local_command(c, kind, *args):
    return cmd(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                c["local" + kind], *args], timeout=None if kind == "Stop" else 300)


def native_baseline():
    return json.loads(ps("@(Get-CimInstance Win32_Service | Where-Object {$_.Name -in @('MySQL','mysqlzt','Redis')} | ForEach-Object {[PSCustomObject]@{name=$_.Name;state=$_.State;startMode=$_.StartMode}})|ConvertTo-Json -Compress"))

def pause_dormant_native(state):
    # Auto services already stopped by the operator must not wake up on reboot.
    for item in state["baseline"].get("nativeServices", []):
        if item["state"] == "Stopped" and item["startMode"] == "Auto":
            ps("Set-Service -Name " + q(item["name"]) + " -StartupType Manual")

def restore_native_modes(state):
    for item in state["baseline"].get("nativeServices", []):
        if item["state"] == "Stopped" and item["startMode"] == "Auto":
            ps("Set-Service -Name " + q(item["name"]) + " -StartupType Automatic")

def authenticate_backup_identity(c):
    # A tiny random ciphertext verifies that the configured key can decrypt
    # this backup recipient before any service stops. Nothing is persisted.
    recipient_file = read(c["backupConfig"])["age_recipient_file"]
    recipient = Path(recipient_file).read_text(encoding="utf-8-sig").strip()
    probe = os.urandom(64)
    encrypted = subprocess.run([c["age"], "-r", recipient], input=probe,
                               capture_output=True, timeout=30)
    if encrypted.returncode:
        raise Blocked("Backup recipient verification failed")
    decrypted = subprocess.run([c["age"], "-d", "-i", c["ageIdentity"]],
                               input=encrypted.stdout, capture_output=True, timeout=30)
    if decrypted.returncode or decrypted.stdout != probe:
        raise Blocked("Backup recipient/identity mismatch")

def preflight(c, admin=False, require_idle=True):
    validate_config(c)
    if os.environ.get("COMPUTERNAME", "").upper() != c["host"].upper():
        raise Blocked("Maintenance configured for a different host")
    if ps("(Get-TimeZone).Id") != "China Standard Time":
        raise Blocked("Windows timezone changed")
    if admin and not ctypes.windll.shell32.IsUserAnAdmin():
        raise Blocked("Elevated token required for MySQL service control")
    for key in ["mysql", "mysqlDefaults", "backupConfig", "backupScript", "verifyBackupScript",
                "localStop", "localStart", "age", "ageIdentity", "python", "docker", "boundedBackupScript"]:
        if not Path(c[key]).is_file():
            raise Blocked("Missing file: " + key)
    current = containers(c)
    unknown = [n for n, d in current.items() if d["running"] and n not in STOP]
    if unknown:
        raise Blocked("Unreviewed running containers: " + ",".join(unknown))
    if any(d["paused"] or d["restarting"] for d in current.values()):
        raise Blocked("Container is paused or restarting")
    task_state = tasks(c)
    if len(task_state) != len(c["tasks"]):
        raise Blocked("Expected scheduled task missing")
    if any(t["state"] == "Running" and t["name"] != "GYing Cloudflare Tunnel" for t in task_state):
        raise Blocked("Host publish/token task is running")
    if require_idle:
        assert_idle(c)
    if ps("(Get-Service -Name " + q(c["mysqlService"]) + ").Status.ToString()") != "Running":
        raise Blocked("MySQL baseline is not Running")
    if not healthy("http://127.0.0.1/api/movies/list?page=1&size=1"):
        raise Blocked("Website baseline unhealthy")
    cmd([c["python"], "-X", "utf8", c["backupScript"], "--config", c["backupConfig"]], 90)
    local = json.loads(local_command(c, "Stop", "-CheckOnly", "-ForReboot").splitlines()[-1])
    if local.get("checkOnly") is not True:
        raise Blocked("Local AI preflight did not return its read-only proof")
    if set(local.get("runningServices", [])) != set(local_running()):
        raise Blocked("Local AI listener/managed-service mismatch")
    validate_diagnostics(c, current)
    authenticate_backup_identity(c)
    return {"at": now(), "boot": boot(), "containers": current, "tasks": task_state,
            "nativeServices": native_baseline(), "localServices": local.get("runningServices", []), "localInstances": local.get("instances", []), "staleRows": jobs(c)}

def same(left, right):
    return left["id"] == right["id"] and left["image"] == right["image"]


def state_path(c, value):
    path = Path(value).resolve(strict=True)
    root = Path(c["stateRoot"]).resolve(strict=True)
    if not path.is_relative_to(root) or path.name != "state.json":
        raise Blocked("Journal outside configured state root")
    return path

def validate_config(c):
    if set(c["tasks"]) != TASK_NAMES or len(c["tasks"]) != len(TASK_NAMES):
        raise Blocked("Only the reviewed host task names may be managed")
    if c["ignoredStaleRows"] != STALE_ROWS:
        raise Blocked("Stale row exceptions must match the two reviewed historical records exactly")
    if set(c.get("recoveryExcluded", {})) - {"gying-xunlei-path-probe-20261006"}:
        raise Blocked("Unreviewed recovery exclusion")
    if c.get("firstRun") != "2026-10-25T09:30:00+08:00":
        raise Blocked("Unexpected first maintenance time")

def validate_diagnostics(c, current):
    import hashlib
    name = "gying-xunlei-path-probe-20261006"
    item = current.get(name)
    if not item or not item["running"]:
        return
    excluded = c.get("recoveryExcluded", {}).get(name)
    if not excluded or not same(item, excluded) or item["restart"] != "no":
        raise Blocked("One-shot diagnostic requires its reviewed identity exclusion")
    command = docker(c, "inspect", "--format", "{{json .Config.Cmd}}", item["id"])
    if hashlib.sha256(command.encode()).hexdigest() != excluded["commandSha256"]:
        raise Blocked("Diagnostic command changed")
    docker(c, "exec", item["id"], "sh", "-c", DIAGNOSTIC_CHECK)

# The reviewed one-shot diagnostic has no application server. PID 1 is a shell
# waiting for exactly one 'tail -f /dev/null' child. Signal that child, keep files.
DIAGNOSTIC_CHECK = """set -eu
found=0
for entry in /proc/[0-9]*/stat; do
  line=$(cat "$entry" 2>/dev/null) || continue
  case "$line" in *" (tail) "*) ;; *) continue ;; esac
  rest=${line#*) }; set -- $rest
  [ "$2" = 1 ] || continue
  pid=${entry#/proc/}; pid=${pid%/stat}
  command=$(tr '\\000' ' ' < "/proc/$pid/cmdline")
  [ "$command" = 'tail -f /dev/null ' ] || exit 32
  found=$((found + 1)); target=$pid
done
[ "$found" = 1 ] || exit 33
"""
DIAGNOSTIC_STOP = DIAGNOSTIC_CHECK + 'kill -TERM "$target"\n'

def stop_worker(c, path):
    """Independent completion receipt outlives a crashed controller.
    No recovery in the same boot may start any container until this receipt proves
    Docker's blocking stop RPC finished, even if inspect already says stopped.
    """
    state = read(path)
    request = state["pendingStop"]
    receipt = path.parent / request["receipt"]
    original = state["baseline"]["containers"][request["name"]]
    result = {"requestId": request["requestId"], "containerId": original["id"],
              "boot": state["baseline"]["boot"], "phase": "dispatched"}
    save(receipt, result)
    try:
        if request["name"] in c.get("recoveryExcluded", {}):
            docker(c, "exec", original["id"], "sh", "-c", DIAGNOSTIC_STOP)
            deadline = time.monotonic() + c.get("stopSeconds", 180)
            while time.monotonic() < deadline:
                item = containers(c).get(request["name"])
                if not item or not same(item, original):
                    raise Blocked("Diagnostic identity changed")
                if not item["running"]:
                    break
                time.sleep(1)
            else:
                raise Blocked("Diagnostic graceful exit timed out")
        else:
            # Docker documents -1 as no SIGKILL deadline. Do not time out/kill
            # this client: a detached daemon RPC must finish before recovery.
            docker(c, "stop", "--timeout", "-1", original["id"], timeout=None)
        result.update(phase="completed", completedAt=now())
    except Exception as error:
        result.update(phase="failed", error=str(error) if isinstance(error, Blocked) else type(error).__name__)
    save(receipt, result)

def stop_settled(state, path):
    request = state.get("pendingStop")
    if not request:
        return True
    receipt = path.parent / request["receipt"]
    if not receipt.is_file():
        return False
    result = read(receipt)
    original = state["baseline"]["containers"][request["name"]]
    return (result.get("phase") == "completed" and
            result.get("requestId") == request["requestId"] and
            result.get("containerId") == original["id"] and
            result.get("boot") == state["baseline"]["boot"])

def stop(c, name, original, path):
    current = containers(c).get(name)
    if not current or not same(current, original):
        raise Blocked("Container identity changed: " + name)
    if not current["running"]:
        return
    request_id = uuid.uuid4().hex
    state = read(path)
    state["pendingStop"] = {"name": name, "requestId": request_id, "receipt": "stop-" + request_id + ".json"}
    save(path, state)
    subprocess.Popen([c["python"], "-X", "utf8", str(Path(__file__).resolve()),
                      "--config", state["config"], "--stop-worker", str(path)],
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), close_fds=True)
    deadline = time.monotonic() + c.get("stopSeconds", 180)
    while time.monotonic() < deadline:
        current = containers(c).get(name)
        if not current or not same(current, original):
            raise Blocked("Container identity changed during stop: " + name)
        state = read(path)
        if stop_settled(state, path) and not current["running"]:
            state.pop("pendingStop", None)
            state["stopped"].append(name)
            save(path, state)
            if current["exitCode"] in [137, 139]:
                raise Blocked("Unclean container exit: " + name)
            return
        time.sleep(2)
    raise Blocked("Graceful stop pending: " + name + "; no force kill or reboot; watchdog waits for completed receipt")

def wait_health(url, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if healthy(url):
            return True
        time.sleep(3)
    return False

def wait_container(c, name, original):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        current = containers(c).get(name)
        if not current or not same(current, original):
            raise Blocked("Recovery refuses changed container: " + name)
        if current["running"] and current["health"] in [None, "healthy"]:
            return
        time.sleep(3)
    raise Blocked("Container recovery health failed: " + name)

def verify_backup(c, directory):
    cmd([c["python"], "-X", "utf8", c["verifyBackupScript"], directory], timeout=None)
    root = Path(directory).resolve(strict=True)
    for entry in read(root / "manifest.json")["files"]:
        path = (root / entry["name"]).resolve(strict=True)
        if not path.is_relative_to(root) or path.suffix != ".age":
            raise Blocked("Unsafe backup artifact path")
        # Authenticate decryption without persisting or printing any plaintext.
        result = subprocess.run([c["age"], "-d", "-i", c["ageIdentity"], str(path)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise Blocked("Backup decryption verification failed")

def recover(c, path):
    """Caller must hold the global lock. Failed recovery retains its journal for retry."""
    state = read(path)
    if state["phase"] in DONE:
        return
    if boot() == state["baseline"]["boot"] and not stop_settled(state, path):
        raise Blocked("Unsettled Docker stop RPC; recovery waits for its completion receipt")
    state["phase"] = "recovering"
    save(path, state)
    ps("Start-Service -Name " + q(c["mysqlService"]) + ";(Get-Service -Name " + q(c["mysqlService"]) +
       ").WaitForStatus('Running',[TimeSpan]::FromSeconds(90))", 100)
    cmd([c["docker"], "desktop", "start", "--timeout", "180"], 200)
    for name in START:
        if name in c.get("recoveryExcluded", {}):
            continue
        original = state["baseline"]["containers"].get(name)
        if not original or not original["running"]:
            continue
        current = containers(c).get(name)
        if not current or not same(current, original):
            raise Blocked("Recovery refuses changed container: " + name)
        if not current["running"]:
            docker(c, "start", original["id"])
        wait_container(c, name, original)
        if name == "gying-movie-backend-1" and not wait_health("http://127.0.0.1:8880/api/movies/list?page=1&size=1", 180):
            raise Blocked("Backend recovery failed; host tasks remain paused")
    if state["baseline"]["containers"].get("gying-movie-nginx-1", {}).get("running"):
        docker(c, "exec", "gying-movie-nginx-1", "nginx", "-t")
        docker(c, "exec", "gying-movie-nginx-1", "nginx", "-s", "reload")
    for name in LOCAL:
        if name in state["baseline"]["localServices"]:
            local_command(c, "Start", "-Service", name)
    if not wait_health("http://127.0.0.1/api/movies/list?page=1&size=1", 180):
        raise Blocked("Local ingress failed; host tasks remain paused")
    restore_native_modes(state)
    for task in state["baseline"]["tasks"]:
        ps(("Enable" if task["enabled"] else "Disable") + "-ScheduledTask -TaskName " + q(task["name"]) + "|Out-Null")
    if any(t["name"] == "GYing Cloudflare Tunnel" and t["enabled"] and t["state"] == "Running" for t in state["baseline"]["tasks"]):
        ps("if((Get-ScheduledTask -TaskName 'GYing Cloudflare Tunnel').State -ne 'Running'){Start-ScheduledTask -TaskName 'GYing Cloudflare Tunnel'}")
    state["health"] = {url: wait_health(url) for url in c["healthUrls"]}
    state["recoveredAt"] = now()
    state["phase"] = "recovered" if all(state["health"].values()) else "recovery_failed"
    state.pop("pendingStop", None)
    save(path, state)
    if state["phase"] != "recovered":
        raise Blocked("Recovery health check failed")

def attempt_recovery(c, path):
    try:
        recover(c, path)
        return True
    except Exception as error:
        state = read(path)
        state["phase"] = "recovery_failed"
        state["recoveryError"] = str(error) if isinstance(error, Blocked) else type(error).__name__
        save(path, state)
        return False

def oneoff_permit(c, config, path):
    import hashlib
    root=(Path(c["stateRoot"])/"one-off").resolve()
    target=Path(path).resolve(strict=True)
    if target.parent!=root or target.suffix!=".json" or target.is_symlink():raise Blocked("One-off permit outside approved directory")
    permit=read(target)
    if set(permit)-{"version","id","host","configSha256","runtimeSha256","startAt","latestStartAt","finishBefore","createdAt","usedAt"}:raise Blocked("Unknown one-off permit field")
    if permit.get("version")!=1 or permit.get("host","").upper()!=c["host"].upper():raise Blocked("One-off host mismatch")
    if permit.get("configSha256")!=hashlib.sha256(Path(config).read_bytes()).hexdigest():raise Blocked("One-off configuration changed")
    if permit.get("runtimeSha256")!=hashlib.sha256(Path(__file__).read_bytes()).hexdigest():raise Blocked("One-off runtime changed")
    try:
        start,latest,finish=[dt.datetime.fromisoformat(permit[k]) for k in ["startAt","latestStartAt","finishBefore"]]
        if any(v.utcoffset()!=dt.timedelta(hours=8) for v in [start,latest,finish]):raise ValueError()
        if not dt.timedelta(0)<latest-start<=dt.timedelta(minutes=10) or not latest<finish<=start+dt.timedelta(minutes=60):raise ValueError()
    except (KeyError,TypeError,ValueError):raise Blocked("Invalid bounded one-off window")
    return permit,start,latest,finish

def maintenance_window(c, config, once, final=False):
    if once is None:return assert_window(final)
    permit,start,latest,finish=oneoff_permit(c,config,once)
    value=dt.datetime.now(dt.timezone.utc)
    if not start<=value<(finish if final else latest):raise Blocked("Outside explicit one-off window; no catch-up reboot")

def run(c, config, reboot, once=None):
    # Attempt logs are separate from state.json: no stopped services, no recovery obligation.
    attempt=Path(c["stateRoot"])/"attempt-log"/(uuid.uuid4().hex+".json")
    attempt.parent.mkdir(parents=True,exist_ok=True)
    if once and not reboot:raise Blocked("One-off permit requires execute")
    save(attempt,{"startedAt":now(),"mode":"one-off" if once else "weekly" if reboot else "rehearsal","stage":"window","status":"started"})
    try:
        _run(c,config,reboot,once,attempt)
        save(attempt,{**read(attempt),"finishedAt":now(),"status":"completed"})
    except Exception as error:
        save(attempt,{**read(attempt),"finishedAt":now(),"status":"failed","reason":str(error)[:4000] if isinstance(error,Blocked) else type(error).__name__})
        raise

def _run(c, config, reboot, once=None, attempt=None):
    if reboot:
        maintenance_window(c, config, once)
    if pending(c):
        raise Blocked("Previous maintenance needs recovery")
    if once:
        permit=read(once)
        if permit.get("usedAt"):raise Blocked("One-off maintenance permit already used")
        permit["usedAt"]=now();save(once,permit)
    if attempt:save(attempt,{**read(attempt),"stage":"preflight"})
    baseline = preflight(c, True)
    if reboot:
        maintenance_window(c, config, once)
    path = Path(c["stateRoot"]) / dt.datetime.now().strftime("%Y%m%d-%H%M%S") / "state.json"
    path.parent.mkdir(parents=True, exist_ok=False)
    state = {"phase": "prepared", "baseline": baseline, "stopped": [], "config": str(config)}
    save(path, state)
    if attempt:save(attempt,{**read(attempt),"stage":"maintenance","journal":str(path)})
    subprocess.Popen([c["python"], "-X", "utf8", str(Path(__file__).resolve()),
                      "--config", str(config), "--watch", str(path)],
                     creationflags=subprocess.CREATE_NO_WINDOW, close_fds=True)
    try:
        pause_dormant_native(state)
        for task in baseline["tasks"]:
            ps("Disable-ScheduledTask -TaskName " + q(task["name"]) + "|Out-Null")
        if any(t["state"] == "Running" and t["name"] != "GYing Cloudflare Tunnel" for t in tasks(c)):
            raise Blocked("Host job started during preflight")
        local_command(c, "Stop", "-Graceful", "-ForReboot")
        state = read(path)
        state["phase"] = "stopping"
        save(path, state)
        for name in STOP:
            original = baseline["containers"].get(name)
            if not original or not original["running"]:
                continue
            if name in ["gying-movie-backend-1", "gying-movie-social-publisher-1", "gying-movie-gying-source-1"]:
                assert_idle(c)
            stop(c, name, original, path)
        # Check again after writers exit. Never reboot with a newly stranded RUNNING row.
        assert_idle(c)
        state = read(path)
        state["phase"] = "backup"
        save(path, state)
        out = cmd([c["python"], "-X", "utf8", c["boundedBackupScript"], "--config", str(config),
                   "--folder", str(path.parent / "backup-job")], timeout=1050)
        backup = json.loads(out.splitlines()[-1])
        if backup.get("status") != "complete":
            raise Blocked("Backup incomplete")
        if not backup.get("verifiedArtifacts") or backup.get("verified") != "SHA-256 and authenticated age decryption to null":
            raise Blocked("Authenticated backup verification receipt missing")
        state["backup"] = backup
        state["backup"]["verified"] = "SHA-256 and authenticated age decryption to null"
        save(path, state)
        if reboot:
            maintenance_window(c, config, once, final=True)
        ps("Stop-Service -Name " + q(c["mysqlService"]) + ";(Get-Service -Name " + q(c["mysqlService"]) +
           ").WaitForStatus('Stopped',[TimeSpan]::FromSeconds(120))", 135)
        if any(d["running"] for d in containers(c).values()):
            raise Blocked("A container started during maintenance; no Docker Desktop shutdown")
        cmd([c["docker"], "desktop", "stop", "--timeout", "120"], 140)
        state["phase"] = "awaiting_reboot" if reboot else "rehearsal_complete"
        state["shutdownIssuedAt"] = now()
        save(path, state)
        if reboot:
            maintenance_window(c, config, once, final=True)
            # Microsoft: nonzero /t implies /f. Zero timeout preserves application vetoes.
            cmd(["shutdown.exe", "/r", "/t", "0", "/d", "p:0:0",
                 "/c", "Weekly graceful maintenance; login to restore GYING services"])
        else:
            recover(c, path)
    except Exception as error:
        state = read(path)
        state["phase"] = "aborted"
        state["error"] = str(error) if isinstance(error, Blocked) else type(error).__name__
        save(path, state)
        attempt_recovery(c, path)
        raise
    print(json.dumps({"attempt": str(path.parent), "phase": read(path)["phase"]}))

def watch(c, path):
    for _ in range(720):
        state = read(path)
        if state["phase"] in DONE or boot() != state["baseline"]["boot"]:
            return
        if state["phase"] == "awaiting_reboot":
            elapsed = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(state["shutdownIssuedAt"])).total_seconds()
            if elapsed < 180:
                time.sleep(10)
                continue
        try:
            with lock(c):
                # Main process released/crashed. Only one actor may restore services.
                if state.get("shutdownIssuedAt"):
                    cmd(["shutdown.exe", "/a"], check=False)
                if attempt_recovery(c, path):
                    return
        except Blocked:
            pass
        time.sleep(10)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--readiness", action="store_true")
    modes.add_argument("--execute", action="store_true")
    modes.add_argument("--rehearse", action="store_true")
    modes.add_argument("--recover", action="store_true")
    modes.add_argument("--watch")
    modes.add_argument("--stop-worker")
    parser.add_argument("--once-window",help="Reviewed one-off permit under stateRoot/one-off; requires --execute")
    args = parser.parse_args()
    if args.once_window and not args.execute:parser.error("--once-window requires --execute")
    config = Path(args.config).resolve(strict=True)
    c = read(config)
    validate_config(c)
    if os.environ.get("COMPUTERNAME", "").upper() != c["host"].upper():
        raise Blocked("Maintenance configured for a different host")
    if args.stop_worker:
        stop_worker(c, state_path(c, args.stop_worker))
    elif args.watch:
        watch(c, state_path(c, args.watch))
    elif args.recover:
        with lock(c):
            for path in pending(c):
                if not attempt_recovery(c, path):
                    raise Blocked("Recovery incomplete; inspect maintenance journal")
        print("Recovery check complete")
    elif args.execute or args.rehearse:
        with lock(c):
            run(c, config, args.execute, args.once_window)
    else:
        report = preflight(c, require_idle=not args.readiness)
        print(json.dumps({"mode": "read-only", "at": report["at"],
              "busyJobs": active(jobs(c), c["ignoredStaleRows"]), "activeContainers": sum(d["running"] for d in report["containers"].values()),
              "localServices": report["localServices"], "pendingAttempts": len(pending(c)),
              "schedule": "Sunday 09:30 Asia/Shanghai", "recovery": "after ASUS interactive logon"}))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Maintenance stopped: " + (str(error) if isinstance(error, Blocked) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
