"""Temporarily pause the existing Resource Hub worker and restore after maintenance."""
import argparse, ctypes, datetime as dt, hashlib, json, os, re, runpy, subprocess, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET

KEY = "resource.hub.worker.enabled"


def restore_allowed(task, operation, pending, now):
    if pending or task["state"] not in {"Ready", "Disabled"}:
        return False
    start = dt.datetime.fromisoformat(operation["startAt"])
    latest = dt.datetime.fromisoformat(operation["latestStartAt"])
    last = dt.datetime.fromisoformat(task["lastRun"])
    return task.get("enabled") is False or last >= start or now > latest




def validate_restart_registration(registration, permit_result):
    try:
        permit, start, latest, finish = permit_result
        if (registration.get("mode") != "registered" or "usedAt" in permit
                or registration["task"] != "GYing One-off Graceful Restart " + start.strftime("%Y%m%d-%H%M")
                or any(registration[key] != permit[key] for key in ("startAt", "latestStartAt", "finishBefore"))
                or start <= dt.datetime.now(dt.timezone.utc)):
            raise ValueError()
    except (KeyError, TypeError, ValueError, AttributeError):
        raise RuntimeError("Registration must match an unused verified future restart permit") from None
    return permit


def guard_arguments(helper, config_path, operation):
    return ('-X utf8 "' + str(helper) + '" --config "' + str(config_path) +
            '" --watch --operation "' + str(operation) + '"')


def validate_guard_task(proof, config, helper, config_path, operation, guard_name):
    """Validate actual exported task XML before allowing the worker CAS write."""
    namespace = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
    def only(parent, name):
        matches = parent.findall("t:" + name, namespace)
        if len(matches) != 1:
            raise ValueError("Missing or duplicate task element")
        return matches[0]
    def value(parent, name):
        return only(parent, name).text
    try:
        user = config["host"] + "\\ASUS"
        sid = proof["expectedSid"]
        if (proof["name"] != guard_name or proof["path"] != "\\"
                or not re.fullmatch(r"S-1-[0-9]+(?:-[0-9]+)+", sid)):
            raise ValueError("Task identity mismatch")
        root = ET.fromstring(proof["xml"])
        if root.tag != "{" + namespace["t"] + "}Task":
            raise ValueError("Invalid task XML")
        if value(only(root, "RegistrationInfo"), "URI") != "\\" + guard_name:
            raise ValueError("Task URI mismatch")
        principals = only(root, "Principals")
        principal = only(principals, "Principal")
        if len(principals) != 1:
            raise ValueError("Unexpected principal")
        owner = value(principal, "UserId")
        if (owner.casefold() not in {user.casefold(), sid.casefold()}
                or value(principal, "LogonType") != "InteractiveToken"
                or value(principal, "RunLevel") != "HighestAvailable"):
            raise ValueError("Principal mismatch")
        actions = only(root, "Actions")
        action = only(actions, "Exec")
        if len(actions) != 1 or actions.get("Context") != principal.get("id"):
            raise ValueError("Unexpected task action")
        if (value(action, "Command") != config["python"]
                or value(action, "Arguments") != guard_arguments(helper, config_path, operation)
                or value(action, "WorkingDirectory") != str(Path(helper).parent)
                or {child.tag for child in action} !=
                   {"{" + namespace["t"] + "}" + name for name in ("Command", "Arguments", "WorkingDirectory")}):
            raise ValueError("Action binding mismatch")
        triggers = only(root, "Triggers")
        trigger = only(triggers, "LogonTrigger")
        if (len(triggers) != 1 or value(trigger, "UserId").casefold() not in {user.casefold(), sid.casefold()}
                or value(trigger, "Delay") != "PT2M"
                or trigger.find("t:Enabled", namespace) is not None and value(trigger, "Enabled") != "true"
                or any(child.tag.rsplit("}", 1)[-1] not in {"UserId", "Delay", "Enabled"} for child in trigger)):
            raise ValueError("Logon trigger mismatch")
        settings = only(root, "Settings")
        expected = {"Enabled": "true", "MultipleInstancesPolicy": "IgnoreNew", "ExecutionTimeLimit": "PT0S",
                    "StartWhenAvailable": "true", "AllowHardTerminate": "false",
                    "DisallowStartIfOnBatteries": "false", "StopIfGoingOnBatteries": "false", "WakeToRun": "false"}
        # Export-ScheduledTask omits these two native default-valued settings.
        defaults = {"Enabled": "true", "WakeToRun": "false"}
        for key, expected_value in expected.items():
            nodes = settings.findall("t:" + key, namespace)
            actual = defaults[key] if not nodes and key in defaults else value(settings, key)
            if actual != expected_value:
                raise ValueError("Recovery settings mismatch")
        retry = only(settings, "RestartOnFailure")
        if value(retry, "Interval") != "PT2M" or value(retry, "Count") != "10" or len(retry) != 2:
            raise ValueError("Retry settings mismatch")
        for name in ("RunOnlyIfIdle", "RunOnlyIfNetworkAvailable"):
            if settings.find("t:" + name, namespace) is not None and value(settings, name) != "false":
                raise ValueError("Recovery has an unreviewed prerequisite")
    except (KeyError, TypeError, ValueError, AttributeError, ET.ParseError):
        raise RuntimeError("Enabled exact bound ASUS login recovery task required before pause") from None


def restore_sql(original, expected, current, restored_expected=None):
    if restored_expected is not None and current == restored_expected:
        return None
    if current == original:
        return None
    if current != expected:
        raise RuntimeError("Worker config changed concurrently; refuse automatic overwrite")
    if original["value"] not in {"true", "false", "0", "1"}:
        raise RuntimeError("Unexpected worker switch value")
    stamp = expected["updated"]
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}", stamp):
        raise RuntimeError("Invalid worker timestamp")
    target = "NOW()" if restored_expected is None else "'" + restored_expected["updated"] + "'"
    return ("UPDATE sys_config SET config_value='" + original["value"] + "',updated_at=" + target + " "
            "WHERE id=" + str(int(original["id"])) + " AND config_key='" + KEY + "' "
            "AND BINARY config_value='false' AND updated_at='" + stamp + "';SELECT ROW_COUNT();")


BACKEND = "gying-movie-backend-1"


class DatabaseFailure(RuntimeError):
    """Safe message: never include command output or credential-bearing arguments."""


def granted_worker_permissions(grants):
    required = {"SELECT": {"id", "config_key", "config_value", "updated_at"},
                "UPDATE": {"config_value", "updated_at"}}
    allowed = {key: set() for key in required}
    lines = grants.splitlines()
    if any(line.upper().startswith("REVOKE ") for line in lines):
        return False
    for line in lines:
        match = re.match(r"^GRANT (.+?) ON (.+?) TO ", line, re.IGNORECASE)
        if not match:
            continue
        privileges, scope = match.groups()
        scope = scope.replace("`", "").replace(" ", "").lower()
        if scope not in {"*.*", "gying.*", "gying.sys_config"}:
            continue
        if privileges.upper() == "ALL PRIVILEGES":
            for name in required:
                allowed[name].update(required[name])
            continue
        for item in re.findall(r"(?:[^(),]|\([^)]*\))+", privileges):
            field = re.fullmatch(r"\s*(SELECT|UPDATE)\s*(?:\(([^)]*)\))?\s*", item, re.IGNORECASE)
            if field is None:
                continue
            name, columns = field.groups()
            name = name.upper()
            if columns is None:
                allowed[name].update(required[name])
            else:
                allowed[name].update(c.strip().strip("`").lower() for c in columns.split(","))
    return all(columns <= allowed[name] for name, columns in required.items())


class ApplicationDatabase:
    def __init__(self, config, expected=None, require_running=False):
        # Keep this private projection in memory. Neither the environment nor the
        # inspect response may enter a journal, exception, command log or stdout.
        template = '{"id":{{json .Id}},"image":{{json .Image}},"env":{{json .Config.Env}},"running":{{json .State.Running}},"labels":{{json .Config.Labels}}}'
        try:
            result = subprocess.run([config["docker"], "inspect", "--format", template, BACKEND],
                                    capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            raise DatabaseFailure("Database stage=credential-source unavailable") from None
        if result.returncode:
            raise DatabaseFailure("Database stage=credential-source unavailable")
        try:
            projected = json.loads(result.stdout.decode("utf-8"))
            cid, image, running, labels = (projected[key] for key in ("id", "image", "running", "labels"))
            values = dict(item.split("=", 1) for item in projected["env"])
            user, password = values["DB_USER"], values["DB_PASSWORD"]
            port = int(values.get("DB_PORT", "3306"))
            if (not re.fullmatch(r"[A-Za-z0-9_.-]+", user) or user.lower() == "root"
                    or not password or "\x00" in password or not 1 <= port <= 65535
                    or values.get("DB_NAME", "gying") != "gying"
                    or values.get("DB_HOST", "host.docker.internal").lower() not in
                    {"localhost", "127.0.0.1", "host.docker.internal"}
                    or not re.fullmatch(r"[a-f0-9]{64}", cid)
                    or not re.fullmatch(r"sha256:[a-f0-9]{64}", image)
                    or labels.get("com.docker.compose.project") != "gying-movie"
                    or labels.get("com.docker.compose.service") != "backend"
                    or require_running and running is not True):
                raise ValueError()
        except (ValueError, KeyError, TypeError, AttributeError, UnicodeError):
            raise DatabaseFailure("Database stage=credential-source identity invalid") from None
        self.source = {"kind": "backend-container", "container": BACKEND, "id": cid, "image": image,
                       "user": user, "host": "127.0.0.1", "port": port, "database": "gying"}
        if expected is not None and self.source != expected:
            raise DatabaseFailure("Database stage=credential-source identity changed")
        self._mysql = config["mysql"]
        self._password = password

    def sql(self, statement, stage):
        args = [self._mysql, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                "--port=" + str(self.source["port"]), "--user=" + self.source["user"],
                "--connect-timeout=10", "--default-character-set=utf8mb4",
                "--batch", "--raw", "--skip-column-names", "gying", "-e", statement]
        environment = {**os.environ, "MYSQL_PWD": self._password}
        try:
            result = subprocess.run(args, capture_output=True, timeout=30, env=environment)
        except subprocess.TimeoutExpired:
            raise DatabaseFailure("Database stage=" + stage + " timeout") from None
        except OSError:
            raise DatabaseFailure("Database stage=" + stage + " unavailable") from None
        if result.returncode:
            match = re.search(rb"(?:^|\n)ERROR ([0-9]{4})\b", result.stderr)
            code = match.group(1).decode("ascii") if match else "unknown"
            raise DatabaseFailure("Database stage=" + stage + " mysql-error=" + code)
        return result.stdout.decode("utf-8", errors="replace").strip()

    def verify(self):
        identity = self.sql("SELECT JSON_OBJECT('account',CURRENT_USER(),'database',DATABASE())", "identity")
        try:
            actual = json.loads(identity)
            if actual["account"].split("@", 1)[0] != self.source["user"] or actual["database"] != "gying":
                raise ValueError()
        except (ValueError, KeyError, TypeError, AttributeError):
            raise DatabaseFailure("Database stage=identity mismatch") from None
        grants = self.sql("SHOW GRANTS FOR CURRENT_USER()", "permissions")
        if not granted_worker_permissions(grants):
            raise DatabaseFailure("Database stage=permissions sys_config SELECT/UPDATE required")
        # This SELECT also verifies current access to all fields used by the CAS.
        self.sql("SELECT id,config_key,config_value,updated_at FROM sys_config "
                 "WHERE config_key='" + KEY + "'", "permissions")


def verify_checkpoint(checkpoint, reference_time=None):
    if (checkpoint.get("status") != "complete"
            or checkpoint.get("verified") != "SHA-256 and authenticated age decryption to null"):
        raise RuntimeError("Verified database checkpoint required")
    try:
        observed = dt.datetime.fromisoformat(checkpoint["at"].replace("Z", "+00:00"))
        reference_time = reference_time or dt.datetime.now(dt.timezone.utc)
        age = reference_time - observed
        if observed.utcoffset() is None or age < -dt.timedelta(minutes=5) or age > dt.timedelta(hours=24):
            raise ValueError()
    except (KeyError, TypeError, ValueError, AttributeError):
        raise RuntimeError("Fresh timezone-aware database checkpoint required") from None
    files = checkpoint.get("files")
    if not isinstance(files, list) or not files or not any(isinstance(x, dict) and x.get("name") == "mysql.sql.age" for x in files):
        raise RuntimeError("Nonempty database checkpoint with mysql.sql.age required")
    try:
        directory = Path(checkpoint["directory"]).resolve(strict=True)
        names = set()
        for entry in files:
            name, digest = entry["name"], entry["sha256"]
            artifact = (directory / name).resolve(strict=True)
            if (Path(name).name != name or artifact.parent != directory or artifact.suffix != ".age"
                    or name in names or not artifact.is_file() or artifact.stat().st_size <= 0
                    or "size" in entry and (type(entry["size"]) is not int or entry["size"] != artifact.stat().st_size)
                    or not re.fullmatch(r"[a-fA-F0-9]{64}", digest)
                    or hashlib.sha256(artifact.read_bytes()).hexdigest() != digest.lower()):
                raise ValueError()
            names.add(name)
    except (OSError, ValueError, KeyError, TypeError):
        raise RuntimeError("Database checkpoint hash or path mismatch") from None


def main():
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--prepare", action="store_true")
    modes.add_argument("--pause", action="store_true")
    modes.add_argument("--watch", action="store_true")
    parser.add_argument("--registration")
    parser.add_argument("--operation")
    parser.add_argument("--checkpoint")
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    CONFIG = Path(args.config).resolve(strict=True)
    ROOT = CONFIG.parent / "resource-hub-quiesce"
    RUNTIME = CONFIG.parent / "runtime" / "weekly_restart.py"
    w = runpy.run_path(str(RUNTIME))
    c = w["read"](CONFIG)
    if os.environ.get("COMPUTERNAME", "").upper() != c["host"].upper():
        raise RuntimeError("Unexpected maintenance host")
    if not ctypes.windll.shell32.IsUserAnAdmin():
        raise RuntimeError("Administrator token required")
    database = None
    def sql(statement):
        return database.sql(statement, "prepare" if args.prepare else "pause" if args.pause else "restore")
    def row():
        return json.loads(sql("SELECT JSON_OBJECT('id',id,'value',config_value,'updated',"
            "DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) FROM sys_config WHERE config_key='" + KEY + "'"))
    def fresh_stamp(excluded):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            stamp = sql("SELECT DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s.%f')")
            if stamp not in excluded:
                return stamp
            time.sleep(0.1)
        raise RuntimeError("Cannot obtain a distinct config timestamp")
    def task(name):
        return json.loads(w["ps"]("$t=Get-ScheduledTask -TaskName " + w["q"](name) +
            ";$i=Get-ScheduledTaskInfo -TaskName " + w["q"](name) +
            ";[pscustomobject]@{state=[string]$t.State;enabled=$t.Settings.Enabled;lastRun=$i.LastRunTime.ToString('yyyy-MM-ddTHH:mm:sszzz')}|ConvertTo-Json -Compress"))
    if args.prepare:
        checkpoint = w["read"](args.checkpoint)
        verify_checkpoint(checkpoint)
        reg = w["read"](args.registration)
        if reg.get("mode") != "registered":
            raise RuntimeError("Verified restart task required")
        permit = validate_restart_registration(reg, w["oneoff_permit"](c, CONFIG, reg["permit"]))
        if ROOT.exists() and any(w["read"](p).get("phase") != "restored" for p in ROOT.glob("*.json")):
            raise RuntimeError("An earlier worker quiescence still needs completion")
        database = ApplicationDatabase(c, require_running=True)
        database.verify()
        original = row()
        if original["value"] not in {"true", "false", "0", "1"}:
            raise RuntimeError("Unexpected worker switch")
        ROOT.mkdir(parents=True, exist_ok=True)
        operation = ROOT / (uuid.uuid4().hex + ".json")
        state = {"phase": "prepared", "host": c["host"], "createdAt": w["now"](),
            "targetTask": reg["task"], "startAt": reg["startAt"], "latestStartAt": reg["latestStartAt"],
            "registration": {key: reg[key] for key in ("mode", "task", "startAt", "latestStartAt", "finishBefore", "permit")},
            "permitId": permit["id"],
            "guardTask": "GYing Resource Hub Maintenance Restore " + Path(operation).stem,
            "original": original, "checkpoint": checkpoint["directory"], "checkpointEvidence": checkpoint, "databaseSource": database.source,
            "helperSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        w["save"](operation, state)
        print(json.dumps({"operation": str(operation), "guardTask": state["guardTask"]}))
        return
    operation = Path(args.operation).resolve(strict=True)
    if operation.parent != ROOT.resolve() or operation.suffix != ".json":
        raise RuntimeError("Unexpected operation path")
    state = w["read"](operation)
    if state["host"] != c["host"]:
        raise RuntimeError("Operation host identity changed")
    # Completed operations from earlier helper versions remain harmless at login.
    if state["phase"] == "restored":
        return
    if state["helperSha256"] != hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
        raise RuntimeError("Operation helper identity changed")
    if "databaseSource" not in state:
        raise DatabaseFailure("Database stage=credential-source operation binding missing")
    if args.pause:
        verify_checkpoint(state.get("checkpointEvidence", {}))
        database = ApplicationDatabase(c, expected=state["databaseSource"], require_running=True)
        database.verify()
        with w["lock"](c):
            state = w["read"](operation)
            permit = validate_restart_registration(state["registration"],
                w["oneoff_permit"](c, CONFIG, state["registration"]["permit"]))
            if permit["id"] != state["permitId"]:
                raise RuntimeError("Worker operation restart permit identity changed")
            target = task(state["targetTask"])
            if target["state"] not in {"Ready", "Disabled"} or not target["enabled"] or dt.datetime.now(dt.timezone.utc) >= dt.datetime.fromisoformat(state["startAt"]):
                raise RuntimeError("Restart must still be enabled and future before worker pause")
            if state["phase"] != "prepared" or w["pending"](c):
                raise RuntimeError("Operation not prepared or maintenance still pending")
            proof = json.loads(w["ps"]("$name=" + w["q"](state["guardTask"]) +
                ";$t=Get-ScheduledTask -TaskName $name -TaskPath '\\'" +
                ";$owner=[Security.Principal.NTAccount]::new(" + w["q"](c["host"] + "\\ASUS") + ")" +
                ";[pscustomobject]@{name=$t.TaskName;path=$t.TaskPath;expectedSid=$owner.Translate([Security.Principal.SecurityIdentifier]).Value;xml=(Export-ScheduledTask -TaskName $name -TaskPath '\\')}|ConvertTo-Json -Compress"))
            validate_guard_task(proof, c, Path(__file__).resolve(), CONFIG, operation, state["guardTask"])
            if row() != state["original"]:
                raise RuntimeError("Worker config changed before pause")
            stamp = fresh_stamp({state["original"]["updated"]})
            expected = {"id": state["original"]["id"], "value": "false", "updated": stamp}
            state.update(phase="pausing", expected=expected)
            w["save"](operation, state)
            original = state["original"]
            update = ("UPDATE sys_config SET config_value='false',updated_at='" + stamp + "' WHERE id=" +
                str(int(original["id"])) + " AND config_key='" + KEY + "' AND BINARY config_value='" + original["value"] +
                "' AND updated_at='" + original["updated"] + "';SELECT ROW_COUNT();")
            if sql(update).strip() != "1" or row() != expected:
                raise RuntimeError("Worker pause compare-and-set failed")
            state["phase"] = "paused"
            w["save"](operation, state)
            print(json.dumps({"phase": "paused", "key": KEY, "originalValue": original["value"], "operation": str(operation)}))
            return
    deadline = time.monotonic() + 10800
    while time.monotonic() < deadline:
        state = w["read"](operation)
        if state["phase"] == "restored":
            return
        if restore_allowed(task(state["targetTask"]), state, len(w["pending"](c)), dt.datetime.now(dt.timezone.utc)):
            try:
                with w["lock"](c):
                    state = w["read"](operation)
                    if state["phase"] == "restored":
                        return
                    if not restore_allowed(task(state["targetTask"]), state, len(w["pending"](c)), dt.datetime.now(dt.timezone.utc)):
                        continue
                    database = ApplicationDatabase(c, expected=state["databaseSource"])
                    database.verify()
                    if state["phase"] == "prepared":
                        # The durable pausing phase always precedes the SQL write.
                        # A cancelled prepared operation therefore owns no DB change.
                        observed = row()
                        state.update(phase="restored", restoredAt=w["now"](),
                                     completion="cancelled-before-pause", workerChanged=False,
                                     observedValue=observed["value"], originalUnchanged=observed == state["original"])
                        w["save"](operation, state)
                        print(json.dumps({"phase": "restored", "completion": "cancelled-before-pause", "key": KEY}))
                        return
                    if "restoredExpected" not in state:
                        restore_stamp = fresh_stamp({state["original"]["updated"], state["expected"]["updated"]})
                        state.update(phase="restoring", restoredExpected={**state["original"], "updated": restore_stamp})
                        w["save"](operation, state)
                    statement = restore_sql(state["original"], state["expected"], row(), state["restoredExpected"])
                    if statement and sql(statement).strip() != "1":
                        raise RuntimeError("Worker restore compare-and-set failed")
                    restored = row()
                    if restored["value"] != state["original"]["value"]:
                        raise RuntimeError("Worker restoration readback failed")
                    state.update(phase="restored", restoredAt=w["now"](), restoredValue=restored["value"])
                    w["save"](operation, state)
                    print(json.dumps({"phase": "restored", "key": KEY, "value": restored["value"]}))
                    return
            except (w["Blocked"], DatabaseFailure):
                # MySQL/Desktop may still be coming up after login. Retry without
                # changing the saved identity, CAS version or original switch.
                pass
        time.sleep(10)
    raise RuntimeError("Worker remains paused; recovery needs attention")


if __name__ == "__main__":
    main()
