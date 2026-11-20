"""Read-only production baseline. JSON output contains states and paths, never credential values.
Run after deployment; FAIL means an unsafe observed state, UNKNOWN is not a pass.
"""
import argparse
import datetime
import json
import ipaddress
import re
import subprocess
import urllib.error
import urllib.request
from pathlib import Path


def run(command, timeout=30):
    try:
        return subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        # Exceptions may include command arguments, daemon output, or credentials.
        return subprocess.CompletedProcess(command, 125, "", "")


def process_user_check(result):
    """Require numeric PID/UID evidence; never print arguments or daemon errors."""
    if result.returncode != 0:
        return "UNKNOWN", []
    lines = result.stdout.splitlines()
    if not lines or lines[0].split() != ["PID", "UID", "COMMAND"]:
        return "UNKNOWN", []
    processes = [line.split(maxsplit=2) for line in lines[1:] if line.strip()]
    valid = [row for row in processes
             if len(row) == 3 and row[0].isdigit() and row[1].isdigit()]
    roots = [row[2] for row in valid if int(row[1]) == 0]
    if roots:
        return "FAIL", roots
    if not valid or len(valid) != len(processes):
        return "UNKNOWN", []
    return "PASS", []


def redis_network_check(attached, result):
    """Require internal cache-net evidence; malformed peers must not hide a known failure."""
    if not isinstance(attached, dict) or not attached or result.returncode != 0:
        return "UNKNOWN", []
    try:
        rows = json.loads(result.stdout)
    except (ValueError, TypeError):
        return "UNKNOWN", []
    if not isinstance(rows, list):
        return "UNKNOWN", []

    networks = {}
    unknown = False
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("Name"), str) or not row["Name"]:
            unknown = True
            continue
        networks.setdefault(row["Name"], []).append(row)

    detail = []
    unsafe = False
    for name in attached:
        matches = networks.get(name, [])
        if len(matches) != 1:
            unknown = True
        for row in matches:
            internal = row.get("Internal")
            if not isinstance(internal, bool):
                internal = None
                unknown = True
            labels = row.get("Labels")
            if "Labels" in row and labels is None:
                labels = {}  # Docker explicitly reports an unlabeled network.
            if isinstance(labels, dict):
                cache_network = labels.get("com.docker.compose.network") == "cache-net"
            else:
                cache_network = None
                unknown = True
            detail.append({"network": name, "internal": internal, "cache_network": cache_network})
            unsafe |= internal is False or cache_network is False
    if unsafe:
        return "FAIL", detail
    return ("UNKNOWN" if unknown else "PASS"), detail


# Stable IDs refer to the original eleven findings, not all deployment prerequisites.
HARDENING_GATES = (
    ("G01", "gying-movie-quark-auto-save-1", "published_ports"),
    ("G02", "minio-server", "published_ports"),
    ("G03", "openclaw-openclaw-gateway-1", "no_new_privileges"),
    ("G04", "gying-movie-redis-1", "no_new_privileges"),
    ("G05", "gying-movie-quark-auto-save-1", "no_new_privileges"),
    ("G06", "gying-movie-pansou-1", "no_new_privileges"),
    ("G07", "minio-server", "no_new_privileges"),
    ("G08", "gying-movie-quark-auto-save-1", "root_processes"),
    ("G09", "gying-movie-pansou-1", "root_processes"),
    ("G10", "minio-server", "root_processes"),
    ("G11", "gying-movie-redis-1", "cache_network_isolation"),
)
EXPECTED_CONTAINERS = frozenset(
    ["gying-movie-" + service + "-1" for service in
     ("backend", "frontend", "nginx", "gying-source", "social-publisher", "redis", "pansou", "quark-auto-save")]
    + ["minio-server", "openclaw-openclaw-gateway-1"]
)

EXPECTED_NETWORKS = frozenset(("gying-movie_gying-net", "gying-movie_cache-net"))

def build_gate_ledger(checks):
    items = []
    for gate_id, target, name in HARDENING_GATES:
        key = target + ":" + name
        statuses = [c.get("status") for c in checks if isinstance(c, dict) and c.get("check") == key]
        # A malformed/duplicate positive must not hide missing evidence or a known failure.
        status = "FAIL" if "FAIL" in statuses else statuses[0] if len(statuses) == 1 and statuses[0] == "PASS" else "UNKNOWN"
        items.append({"id": gate_id, "target": target, "check": name, "status": status})
    return {"scope": "Historical eleven container/network gates only; not deployment approval",
            "summary": {s: sum(c["status"] == s for c in items) for s in ("PASS", "FAIL", "UNKNOWN")},
            "items": items}


def no_new_privileges_check(options):
    if options is None:
        return "FAIL"  # Docker explicitly reports no security options.
    if not isinstance(options, list):
        return "UNKNOWN"
    enabled = False
    unknown = False
    for option in options:
        if not isinstance(option, str):
            unknown = True
        elif option in ("no-new-privileges:false", "no-new-privileges=false"):
            return "FAIL"
        elif option in ("no-new-privileges", "no-new-privileges:true", "no-new-privileges=true"):
            enabled = True
        elif option.startswith(("no-new-privileges:", "no-new-privileges=")):
            unknown = True
    return "UNKNOWN" if unknown else "PASS" if enabled else "FAIL"


def published_ports_check(host):
    if "PortBindings" not in host:
        return "UNKNOWN", []
    bindings = host["PortBindings"]
    if bindings is None:
        return "PASS", []
    if not isinstance(bindings, dict):
        return "UNKNOWN", []
    ports, unknown, unsafe = [], False, False
    for key, values in bindings.items():
        if not isinstance(key, str) or not re.fullmatch(r"[0-9]{1,5}/(?:tcp|udp|sctp)", key):
            unknown = True
            continue
        if values is None:
            continue
        if not isinstance(values, list):
            unknown = True
            continue
        for item in values:
            if not isinstance(item, dict):
                unknown = True
                continue
            address = item.get("HostIp")
            port = item.get("HostPort")
            if address == "":
                address = "0.0.0.0"
            try:
                ipaddress.ip_address(address)
            except (ValueError, TypeError):
                unknown = True
                continue
            # Keep the existing deployment contract: these two explicit loopback addresses.
            unsafe |= address not in ("127.0.0.1", "::1")
            if not isinstance(port, str) or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
                unknown = True
                continue
            ports.append({"container_port": key, "host_ip": address, "host_port": port})
    return ("FAIL" if unsafe else "UNKNOWN" if unknown else "PASS"), ports


def docker_socket_check(mounts):
    if not isinstance(mounts, list):
        return "UNKNOWN", None
    unknown = False
    for mount in mounts:
        if not isinstance(mount, dict):
            unknown = True
            continue
        for field in ("Source", "Destination"):
            path = mount.get(field)
            if not isinstance(path, str):
                unknown = True
                continue
            normalized = path.lower().replace(chr(92), "/")
            if "docker.sock" in normalized or "docker_engine" in normalized:
                return "FAIL", True
    return ("UNKNOWN", None) if unknown else ("PASS", False)


def collect(repo, probe=False):
    checks = []
    def add(name, status, detail):
        checks.append({"check": name, "status": status, "detail": detail})

    compose = run(["docker", "compose", "-p", "gying-movie", "-f", str(repo / "docker-compose.prod.yml"), "config", "--quiet"])
    add("production_compose_contract", "PASS" if compose.returncode == 0 else "UNKNOWN" if compose.returncode == 125 else "FAIL",
        "Validated without printing resolved environment" if compose.returncode == 0 else
        "Compose validation unavailable or contract not satisfied; inspect key presence privately")
    listing = run(["docker", "ps", "-aq"])
    rows = []
    inventory_unknown = listing.returncode != 0
    if listing.returncode:
        add("docker", "UNKNOWN", "Docker inventory unavailable")
    elif listing.stdout.strip():
        inspected = run(["docker", "inspect", *listing.stdout.split()])
        try:
            rows = json.loads(inspected.stdout) if inspected.returncode == 0 else None
        except (ValueError, TypeError):
            rows = None
        if not isinstance(rows, list):
            rows = []
            inventory_unknown = True
        if len(rows) != len(listing.stdout.split()):
            inventory_unknown = True
    else:
        inventory_unknown = True

    observed = set()
    network_peers = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("Name"), str):
            inventory_unknown = True
            continue
        name = row["Name"].lstrip("/")
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", name):
            inventory_unknown = True
            continue
        settings = row.get("NetworkSettings")
        attached = settings.get("Networks") if isinstance(settings, dict) else None
        on_production_network = isinstance(attached, dict) and bool(EXPECTED_NETWORKS.intersection(attached))
        named_target = name.startswith("gying-movie-") or name in EXPECTED_CONTAINERS
        if not (named_target or on_production_network):
            continue
        if not named_target:
            network_peers.append(name)
        if name in observed:
            inventory_unknown = True
        observed.add(name)
        host, config = row.get("HostConfig"), row.get("Config")
        if not isinstance(host, dict) or not isinstance(config, dict):
            add(name + ":container_metadata", "UNKNOWN", "Container metadata incomplete")
            continue
        status, detail = published_ports_check(host)
        add(name + ":published_ports", status, detail)
        status, detail = docker_socket_check(row.get("Mounts"))
        add(name + ":docker_socket", status, detail)
        privileged = host.get("Privileged")
        add(name + ":privileged", "FAIL" if privileged is True else "PASS" if privileged is False else "UNKNOWN",
            privileged if isinstance(privileged, bool) else None)
        status = no_new_privileges_check(host["SecurityOpt"]) if "SecurityOpt" in host else "UNKNOWN"
        add(name + ":no_new_privileges", status, {"enabled": True if status == "PASS" else False if status == "FAIL" else None})
        # Config.User is not PID 1 evidence (Redis drops root in its entrypoint).
        top = run(["docker", "top", name, "-eo", "pid,uid,comm"])
        status, detail = process_user_check(top)
        add(name + ":root_processes", status, detail)
        raw_env = config.get("Env")
        env_valid = isinstance(raw_env, list) and all(isinstance(v, str) and "=" in v for v in raw_env)
        env = dict(item.split("=", 1) for item in raw_env) if env_valid else {}
        if any(part in name for part in ("backend", "gying-source", "social-publisher")):
            user = env.get("GYING_DB_USER", env.get("DB_USER", ""))
            add(name + ":non_root_db_user", "UNKNOWN" if not env_valid else "PASS" if user and user.lower() != "root" else "FAIL",
                {"configured": bool(user), "is_root": user.lower() == "root"})
        token_key = "GYING_SOURCE_API_TOKEN" if "gying-source" in name else "SOCIAL_PUBLISHER_TOKEN" if "social-publisher" in name else None
        if token_key:
            meets_minimum = len(env.get(token_key, "").encode()) >= 32
            add(name + ":internal_token", "UNKNOWN" if not env_valid else "PASS" if meets_minimum else "FAIL",
                {"key": token_key, "meets_minimum": meets_minimum})
        if "redis" in name:
            settings = row.get("NetworkSettings")
            attached = settings.get("Networks") if isinstance(settings, dict) else None
            if isinstance(attached, dict) and attached:
                networks = run(["docker", "network", "inspect", *attached])
                status, detail = redis_network_check(attached, networks)
            else:
                status, detail = "UNKNOWN", []
            add(name + ":cache_network_isolation", status, detail)
            ping = run(["docker", "exec", name, "redis-cli", "PING"])
            denied = ping.stdout.strip() in ("NOAUTH Authentication required.", "(error) NOAUTH Authentication required.")
            status = "UNKNOWN" if ping.returncode else "PASS" if denied else "FAIL"
            add(name + ":redis_anonymous_auth", status, "Unauthenticated PING denied" if denied else "Expected NOAUTH not observed")

    missing = sorted(EXPECTED_CONTAINERS - observed)
    add("expected_container_inventory", "UNKNOWN" if missing or inventory_unknown else "PASS",
        {"expected": len(EXPECTED_CONTAINERS), "observed_expected": len(EXPECTED_CONTAINERS & observed),
         "missing": missing, "complete_inspection": not inventory_unknown,
         "additional_network_peers": sorted(network_peers)})
    if probe:
        for path, expected in [("/", 200), ("/api/movies/list?page=1&size=1", 200), ("/api/admin/users", 401),
                               ("/api/internal/resource-hub/health", 404), ("/api/qq-bot/health", 404), ("/media/private.txt", 404)]:
            try:
                with urllib.request.urlopen("http://127.0.0.1" + path, timeout=8) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            except (OSError, ValueError):
                status = None
            add("origin:" + path, "UNKNOWN" if status is None else "PASS" if status == expected else "FAIL",
                {"expected": expected, "observed": status})
    return {"collected_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "scope": "Read-only local origin, expected production containers and attached network peers; Cloudflare policies, external reachability, DB grants, MinIO policy and restore drills require separate verification",
            "checks": checks, "gate_ledger": build_gate_ledger(checks)}


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
