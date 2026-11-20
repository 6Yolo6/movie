"""Fail-closed inventory, effective security options, and the historical 11 gates."""
import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("gate_security", ROOT / "tools/security/check_security.py")
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)
MARKER = "SENSITIVE_DAEMON_DIAGNOSTIC"


def container(name="gying-movie-pansou-1", options=None):
    return {"Name": "/" + name, "HostConfig": {"PortBindings": {}, "Privileged": False,
            "SecurityOpt": ["no-new-privileges:true"] if options is None else options},
            "Config": {"Env": []}, "Mounts": []}


def collected(rows, inspect_code=0, raw=None, listing="fixture-id"):
    def run(command, timeout=30):
        if command[:2] == ["docker", "ps"]:
            output, code = listing, 0
        elif command[:2] == ["docker", "inspect"]:
            output, code = json.dumps(rows) if raw is None else raw, inspect_code
        elif command[:2] == ["docker", "top"]:
            output, code = "PID UID COMMAND\n1 10001 fixture", 0
        else:
            output, code = "", 0
        return subprocess.CompletedProcess(command, code, output, MARKER)
    with patch.object(security, "run", side_effect=run):
        return security.collect(ROOT)


class ReadinessTest(unittest.TestCase):
    def test_explicit_false_never_passes_in_real_collection(self):
        report = collected([container(options=["no-new-privileges:false"])])
        result = next(c for c in report["checks"] if c["check"].endswith(":no_new_privileges"))
        self.assertEqual(result["status"], "FAIL")

    def test_valid_docker_security_option_spellings(self):
        for option in ("no-new-privileges", "no-new-privileges:true", "no-new-privileges=true"):
            self.assertEqual(security.no_new_privileges_check([option]), "PASS")

    def test_disabled_missing_conflicting_or_malformed_options(self):
        for options in ([], None, ["no-new-privileges:false"], ["no-new-privileges=false"],
                        ["no-new-privileges:true", "no-new-privileges:false"],
                        ["custom-no-new-privileges:true"]):
            self.assertEqual(security.no_new_privileges_check(options), "FAIL")
        for options in ("no-new-privileges:true", [None], ["no-new-privileges:invalid"],
                        ["no-new-privileges:true", None]):
            self.assertEqual(security.no_new_privileges_check(options), "UNKNOWN")

    def test_inspect_failure_empty_output_or_no_containers_stays_unknown(self):
        cases = [(1, None, "fixture-id"), (0, "[]", "fixture-id"),
                 (0, "{}", "fixture-id"), (0, "not-json", "fixture-id"), (0, None, "")]
        for code, raw, listing in cases:
            with self.subTest(code=code, raw=raw, listing=listing):
                report = collected([], inspect_code=code, raw=raw, listing=listing)
                self.assertTrue(any(c["status"] == "UNKNOWN" for c in report["checks"]))
                self.assertNotIn(MARKER, json.dumps(report))

    def test_partial_inventory_and_missing_fields_do_not_pass(self):
        report = collected([{"Name": "/gying-movie-pansou-1", "HostConfig": {}, "Config": {}}])
        results = {c["check"]: c["status"] for c in report["checks"]}
        self.assertEqual(results["expected_container_inventory"], "UNKNOWN")
        for suffix in ("published_ports", "privileged", "docker_socket", "no_new_privileges"):
            self.assertEqual(results["gying-movie-pansou-1:" + suffix], "UNKNOWN")

    def test_known_unsafe_row_survives_malformed_inventory_peer(self):
        for rows in ([None, container(options=[])], [container(options=[]), {"Name": []}]):
            report = collected(rows)
            self.assertTrue(any(c["status"] == "FAIL" for c in report["checks"]))
            self.assertTrue(any(c["status"] == "UNKNOWN" for c in report["checks"]))

    def test_socket_source_is_checked_when_destination_was_renamed(self):
        row = container()
        row["Mounts"] = [{"Source": "/var/run/docker.sock", "Destination": "/innocent-name"}]
        report = collected([row])
        result = next(c for c in report["checks"] if c["check"].endswith(":docker_socket"))
        self.assertEqual(result["status"], "FAIL")

    def test_effective_security_options_are_not_echoed(self):
        row = container(options=["no-new-privileges:true", "seccomp=" + MARKER])
        self.assertNotIn(MARKER, json.dumps(collected([row])))

    def test_run_suppresses_timeout_and_missing_executable_diagnostics(self):
        for error in (FileNotFoundError(MARKER), subprocess.TimeoutExpired([MARKER], 1, output=MARKER)):
            with patch.object(security.subprocess, "run", side_effect=error):
                result = security.run(["docker", "ps"])
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(MARKER, result.stdout + result.stderr)

    def test_other_named_containers_on_production_network_are_also_audited(self):
        row = container(name="leftover-diagnostic", options=[])
        row["NetworkSettings"] = {"Networks": {"gying-movie_gying-net": {}}}
        report = collected([row])
        result = next(c for c in report["checks"] if c["check"] == "leftover-diagnostic:no_new_privileges")
        self.assertEqual(result["status"], "FAIL")
        inventory = next(c for c in report["checks"] if c["check"] == "expected_container_inventory")
        self.assertEqual(inventory["detail"]["additional_network_peers"], ["leftover-diagnostic"])

    def test_unrelated_containers_do_not_satisfy_production_inventory(self):
        report = collected([container(name="unrelated-app")])
        result = next(c for c in report["checks"] if c["check"] == "expected_container_inventory")
        self.assertEqual(result["status"], "UNKNOWN")


class GateLedgerTest(unittest.TestCase):
    def checks(self, status="FAIL"):
        return [{"check": target + ":" + check, "status": status, "detail": MARKER}
                for _, target, check in security.HARDENING_GATES]

    def test_exactly_eleven_stable_independently_evaluated_gates(self):
        report = security.build_gate_ledger(self.checks())
        self.assertEqual(report["summary"], {"PASS": 0, "FAIL": 11, "UNKNOWN": 0})
        self.assertEqual(len({g["id"] for g in report["items"]}), 11)
        self.assertNotIn(MARKER, json.dumps(report))

    def test_missing_gate_is_unknown_not_silently_removed(self):
        report = security.build_gate_ledger(self.checks()[1:])
        self.assertEqual(report["summary"], {"PASS": 0, "FAIL": 10, "UNKNOWN": 1})

    def test_duplicate_pass_is_unknown_and_known_failure_wins(self):
        checks = self.checks("PASS")
        report = security.build_gate_ledger(checks + [checks[0]])
        self.assertEqual(report["items"][0]["status"], "UNKNOWN")
        checks[0] = dict(checks[0], status="FAIL")
        report = security.build_gate_ledger(checks + [dict(checks[0], status="UNKNOWN")])
        self.assertEqual(report["items"][0]["status"], "FAIL")

    def test_unknown_or_invalid_status_is_not_a_pass(self):
        checks = self.checks("PASS")
        checks[0] = dict(checks[0], status="unexpected")
        self.assertEqual(security.build_gate_ledger(checks)["items"][0]["status"], "UNKNOWN")


if __name__ == "__main__":
    unittest.main()
