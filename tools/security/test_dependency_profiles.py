"""Opt-in disposable Docker startup probes; never mounts production data or uses its credentials.

This is NOT production deployment or provider integration verification. Images
are pinned to the IDs of existing containers; no image downloads are allowed.
"""
import argparse
import datetime
import json
import os
import re
import secrets
import subprocess
import time
import uuid
from pathlib import Path

try:
    from tools.security.check_security import no_new_privileges_check, process_user_check
except ModuleNotFoundError:
    from check_security import no_new_privileges_check, process_user_check

CONTAINERS = {"pansou": "gying-movie-pansou-1", "quark": "gying-movie-quark-auto-save-1",
              "minio": "minio-server", "redis": "gying-movie-redis-1"}
PREFIX = "gying-security-fixture-"
OWNER_LABEL = "com.gying.security-fixture"
HELPER_CONTAINER = "gying-movie-gying-source-1"
USERS = {"pansou": "10001:10001", "quark": "10001:10001", "minio": "10001:10001", "redis": "999:1000"}
TEMP_PATHS = {"pansou": "/app/cache", "quark": "/app/config", "minio": "/data", "redis": "/data"}
HEALTH = {"pansou": (8888, "api/health"), "quark": (5005, "login"), "minio": (9000, "minio/health/live")}


def run(docker, args, env=None, timeout=30):
    return subprocess.run([docker, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout, env=env)


def inspect(docker, name):
    result = run(docker, ["inspect", name])
    if result.returncode:
        return None
    rows = json.loads(result.stdout)
    return rows[0] if isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict) else None


def image_id(docker, name):
    row = inspect(docker, name)
    image = row.get("Image") if row else None
    if not isinstance(image, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
        raise ValueError("Required local image unavailable")
    return image


def synthetic_environment(service):
    if service == "pansou":
        return {"PORT": "8888", "CACHE_ENABLED": "true", "CACHE_PATH": "/app/cache", "ENABLED_PLUGINS": ""}
    if service == "quark":
        return {"QUARK_COOKIE": ""}
    if service == "minio":
        return {"HOME": "/tmp", "MINIO_ROOT_USER": "fixture-operator", "MINIO_ROOT_PASSWORD": secrets.token_hex(24)}
    if service == "redis":
        return {"REDIS_PASSWORD": secrets.token_hex(24)}
    raise ValueError("Unsupported service")


def fixture_args(service, image, owner, root):
    if service not in CONTAINERS or not isinstance(owner, str) or not re.fullmatch(r"[a-f0-9]{32}", owner):
        raise ValueError("Invalid fixture scope")
    if not isinstance(image, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
        raise ValueError("An existing image ID is required")
    name = PREFIX + owner + "-" + service
    uid, gid = USERS[service].split(":")
    args = ["run", "-d", "--pull=never", "--name", name, "--label", OWNER_LABEL + "=" + owner,
            "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
            "--pids-limit=128", "--memory=512m", "--user", USERS[service],
            "--tmpfs", "/tmp:rw,nosuid,nodev,mode=1777,size=64m",
            "--tmpfs", TEMP_PATHS[service] + ":rw,nosuid,nodev,uid=" + uid + ",gid=" + gid + ",mode=700,size=64m"]
    # Environment values stay in memory, not argv, output, or fixture files.
    for key in synthetic_environment(service):
        args.extend(["--env", key])
    if service == "redis":
        source = str((Path(root) / "deploy/redis-entrypoint.sh").resolve())
        if "," in source or not Path(source).is_file():
            raise ValueError("Reviewed Redis entrypoint unavailable")
        args.extend(["--entrypoint", "sh", "--mount", "type=bind,source=" + source + ",target=/fixture/redis-entrypoint.sh,readonly"])
    args.append(image)
    if service == "minio":
        args.extend(["server", "/data", "--console-address", ":9001"])
    if service == "redis":
        args.append("/fixture/redis-entrypoint.sh")
    return name, args


def isolated(row, service, root):
    host = row.get("HostConfig")
    if not isinstance(host, dict) or host.get("ReadonlyRootfs") is not True or host.get("CapDrop") != ["ALL"]:
        return False
    if host.get("NetworkMode") != "none" or host.get("Privileged") is not False or "PortBindings" not in host or host["PortBindings"] not in (None, {}):
        return False
    mounts = row.get("Mounts")
    if not isinstance(mounts, list):
        return False
    source = os.path.normcase(str((Path(root) / "deploy/redis-entrypoint.sh").resolve()))
    for mount in mounts:
        if not isinstance(mount, dict):
            return False
        if mount.get("Type") == "tmpfs" and mount.get("Destination") in ("/tmp", TEMP_PATHS[service]):
            continue
        if service == "redis" and mount.get("Type") == "bind" and mount.get("Destination") == "/fixture/redis-entrypoint.sh" and mount.get("RW") is False and os.path.normcase(mount.get("Source", "")) == source:
            continue
        return False  # No named/anonymous volumes or other host bind mounts.
    return True


def remove_owned(docker, name, owner):
    # Never remove by a broad name match. Verify this invocation's exact owner label and use its ID.
    if not re.fullmatch(r"[a-f0-9]{32}", owner) or not name.startswith(PREFIX + owner + "-"):
        return False
    row = inspect(docker, name)
    if row is None:
        # An unavailable daemon is not evidence that a fixture was removed.
        listing = run(docker, ["ps", "-a", "--format", "{{.Names}}"])
        return listing.returncode == 0 and name not in listing.stdout.splitlines()
    config = row.get("Config")
    labels = config.get("Labels") if isinstance(config, dict) else None
    container_id = row.get("Id")
    if not isinstance(labels, dict) or not isinstance(container_id, str):
        return False
    if row.get("Name") != "/" + name or labels.get(OWNER_LABEL) != owner or not re.fullmatch(r"[a-f0-9]{64}", container_id):
        return False
    removed = run(docker, ["rm", "-f", "-v", container_id])
    if removed.returncode:
        return False
    listing = run(docker, ["ps", "-a", "--format", "{{.Names}}"])
    return listing.returncode == 0 and name not in listing.stdout.splitlines()


def http_probe(docker, name, owner, helper, service):
    port, path = HEALTH[service]
    code = """import urllib.request,urllib.error
try:
 response=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(URL,timeout=2);print(response.status)
except urllib.error.HTTPError as error: print(error.code)
except Exception: print('unavailable')
""".replace("URL", repr("http://127.0.0.1:" + str(port) + "/" + path))
    helper_name = PREFIX + owner + "-" + service + "-http"
    try:
        result = run(docker, ["run", "--rm", "--pull=never", "--name", helper_name,
                             "--label", OWNER_LABEL + "=" + owner, "--network", "container:" + name,
                             "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                             "--pids-limit=32", "--memory=128m", "--user", "10001:10001",
                             "--entrypoint", "python", helper, "-B", "-c", code])
        value = result.stdout.strip()
        return int(value) if result.returncode == 0 and value.isdigit() else None
    finally:
        if not remove_owned(docker, helper_name, owner):
            raise ValueError("HTTP fixture cleanup failed")


def probe(docker, service, image, helper, root):
    owner = uuid.uuid4().hex
    name, args = fixture_args(service, image, owner, root)
    env = os.environ.copy()
    env.update(synthetic_environment(service))
    result = {"service": service, "fixture_name": name, "image_id": image, "requested_user": USERS[service], "passed": False}
    try:
        started = run(docker, args, env=env)
        if started.returncode:
            raise ValueError("Fixture startup failed")
        time.sleep(3)
        row = inspect(docker, name)
        result["isolated"] = bool(row and isolated(row, service, root))
        if not result["isolated"]:
            raise ValueError("Fixture isolation not verified")
        result["startup"] = row.get("State", {}).get("Status")
        result["nnp"] = no_new_privileges_check(row["HostConfig"].get("SecurityOpt"))
        result["non_root_processes"] = process_user_check(run(docker, ["top", name, "-eo", "pid,uid,comm"]))[0]
        if result["startup"] == "running" and service in HEALTH:
            for _ in range(4):
                result["http_status"] = http_probe(docker, name, owner, helper, service)
                if result["http_status"] == 200:
                    break
                time.sleep(2)
            functional = result["http_status"] == 200
        elif result["startup"] == "running" and service == "redis":
            anonymous = run(docker, ["exec", name, "redis-cli", "PING"])
            result["anonymous_denied"] = anonymous.returncode == 0 and "NOAUTH" in anonymous.stdout
            env["REDISCLI_AUTH"] = env["REDIS_PASSWORD"]
            commands = [("authenticated_ping", ["PING"], "PONG"), ("lua", ["EVAL", "return 1", "0"], "1"),
                        ("dangerous_config_denied", ["CONFIG", "GET", "save"], "NOPERM")]
            for label, command, expected in commands:
                auth = run(docker, ["exec", "-e", "REDISCLI_AUTH", name, "redis-cli", "--raw", *command], env=env)
                result[label] = auth.returncode == 0 and (auth.stdout.strip() == expected if expected != "NOPERM" else auth.stdout.strip().startswith("NOPERM"))
            functional = result["anonymous_denied"] and all(result[label] for label, _, _ in commands)
        else:
            functional = False
        result["passed"] = functional and result["nnp"] == "PASS" and result["non_root_processes"] == "PASS"
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        result["error_class"] = type(error).__name__
    finally:
        try:
            result["fixture_removed"] = remove_owned(docker, name, owner)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            result["fixture_removed"] = False
        result["passed"] = result["passed"] and result["fixture_removed"]
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--service", action="append", choices=tuple(CONTAINERS))
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[2]
    results = []
    try:
        helper = image_id(args.docker, HELPER_CONTAINER)
        for service in dict.fromkeys(args.service or CONTAINERS):
            image = image_id(args.docker, CONTAINERS[service])
            results.append(probe(args.docker, service, image, helper, root))
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        results.append({"passed": False, "error_class": type(error).__name__})
    report = {"at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "Disposable non-root/NNP startup fixtures, no network/production data/real credentials. Not production rollout, volume migration, remote provider verification or security-gate closure.",
              "results": results, "passed": bool(results) and all(r["passed"] for r in results)}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
    print(text)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
