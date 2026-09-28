"""Keep audit findings precise without hiding known unsafe or unknown states."""
import importlib.util
import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def load(filename):
    spec = importlib.util.spec_from_file_location(filename, ROOT / "tools/security" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


security = load("check_security.py")
scanner = load("scan_secrets.py")


class PurposeConstantTest(unittest.TestCase):
    path = "backend/src/main/java/com/gying/movie/service/impl/EmailVerificationService.java"
    declaration = 'public static final String PURPOSE_RESET_PASSWORD = "reset-password";'

    def test_exact_reviewed_enum_only(self):
        self.assertEqual(scanner.findings(self.declaration, self.path), [])
        self.assertEqual(scanner.findings("    " + self.declaration, self.path.replace("/", chr(92))), [])

    def test_changed_literal_is_still_flagged_without_printing_value(self):
        text = self.declaration.replace("reset-password", "sensitive-material")
        findings = scanner.findings(text, self.path)
        self.assertEqual(len(findings), 1)
        self.assertNotIn("sensitive-material", json.dumps(findings))

    def test_same_literal_is_not_globally_allowlisted(self):
        self.assertEqual(len(scanner.findings(self.declaration, "backend/other.java")), 1)
        self.assertEqual(len(scanner.findings('DB_PASSWORD = "reset-password"', self.path)), 1)

    def test_extra_assignment_or_expression_is_not_hidden(self):
        for text in (self.declaration + ' DB_PASSWORD = "sensitive-material";',
                     self.declaration.replace(';', ' + "other-material";')):
            self.assertTrue(scanner.findings(text, self.path))


class RedisNetworkTest(unittest.TestCase):
    @staticmethod
    def row(name="project_cache-net", internal=True, cache=True):
        return {"Name": name, "Internal": internal,
                "Labels": {"com.docker.compose.network": "cache-net" if cache else "gying-net"}}

    def check(self, attached, rows, code=0):
        result = subprocess.CompletedProcess([], code, json.dumps(rows), "sensitive-daemon-diagnostic")
        return security.redis_network_check(attached, result)

    def test_only_internal_cache_network_passes(self):
        self.assertEqual(self.check({"project_cache-net": {}}, [self.row()])[0], "PASS")

    def test_shared_noninternal_network_fails(self):
        row = self.row("project_gying-net", False, False)
        self.assertEqual(self.check({row["Name"]: {}}, [row])[0], "FAIL")

    def test_extra_shared_network_fails_even_with_correct_cache_network(self):
        rows = [self.row(), self.row("project_gying-net", False, False)]
        self.assertEqual(self.check({r["Name"]: {} for r in rows}, rows)[0], "FAIL")

    def test_wrong_network_or_noninternal_cache_fails(self):
        for row in (self.row(internal=False), self.row(cache=False)):
            self.assertEqual(self.check({row["Name"]: {}}, [row])[0], "FAIL")

    def test_missing_evidence_is_unknown(self):
        for attached, rows in (({}, []), ({"missing": {}}, [self.row()]),
                               ({"project_cache-net": {}}, [{"Name": "project_cache-net"}]),
                               ({"project_cache-net": {}}, [self.row(), self.row()]),
                               ({"project_cache-net": {}}, {})):
            self.assertEqual(self.check(attached, rows)[0], "UNKNOWN")

    def test_command_failure_and_malformed_output_do_not_leak(self):
        for code, stdout in ((1, "sensitive-output"), (0, "not-json"), (0, '[null]')):
            result = subprocess.CompletedProcess([], code, stdout, "sensitive-diagnostic")
            self.assertEqual(security.redis_network_check({"cache": {}}, result), ("UNKNOWN", []))

    def test_known_unsafe_network_stays_failure_with_missing_peer(self):
        row = self.row("project_gying-net", False, False)
        self.assertEqual(self.check({row["Name"]: {}, "missing": {}}, [row])[0], "FAIL")

    def test_known_failure_survives_malformed_peer_in_either_order(self):
        unsafe = self.row("shared", False, False)
        for broken in (None, {}, {"Name": []}, {"Name": "broken", "Labels": ["invalid"], "Internal": True}):
            for rows in ([unsafe, broken], [broken, unsafe]):
                with self.subTest(rows=rows):
                    status, detail = self.check({"shared": {}, "broken": {}}, rows)
                    self.assertEqual(status, "FAIL")
                    self.assertNotIn("sensitive", json.dumps(detail))

    def test_malformed_peer_prevents_pass(self):
        for broken in (None, {}, {"Name": []}, {"Name": "broken", "Labels": ["invalid"], "Internal": True}):
            with self.subTest(broken=broken):
                self.assertEqual(self.check({"project_cache-net": {}, "broken": {}}, [self.row(), broken])[0], "UNKNOWN")

    def test_noninternal_flag_is_failure_even_without_usable_labels(self):
        for labels in (["invalid"], "invalid", 42):
            row = self.row(internal=False)
            row["Labels"] = labels
            self.assertEqual(self.check({row["Name"]: {}}, [row])[0], "FAIL")

    def test_missing_or_malformed_fields_are_not_passes(self):
        for field in ("Internal", "Labels"):
            row = self.row()
            del row[field]
            self.assertEqual(self.check({row["Name"]: {}}, [row])[0], "UNKNOWN")
        for value in (0, 1, "true", None):
            row = self.row(internal=value)
            self.assertEqual(self.check({row["Name"]: {}}, [row])[0], "UNKNOWN")

    def test_collect_inspects_actual_network_without_env_or_daemon_output(self):
        row = {"Name": "/gying-movie-redis-1", "HostConfig": {}, "Config": {},
               "NetworkSettings": {"Networks": {"project_gying-net": {}}}}
        commands = []
        def run(command, timeout=30):
            commands.append(command)
            if command[:2] == ["docker", "inspect"]:
                output = json.dumps([row])
            elif command[:2] == ["docker", "ps"]:
                output = "fixture-id"
            elif command[:3] == ["docker", "network", "inspect"]:
                output = json.dumps([self.row("project_gying-net", False, False)])
            elif command[:2] == ["docker", "top"]:
                output = "PID UID COMMAND\n1 999 redis-server"
            else:
                output = "NOAUTH Authentication required."
            return subprocess.CompletedProcess(command, 0, output, "")
        with patch.object(security, "run", side_effect=run):
            report = security.collect(ROOT)
        self.assertIn(["docker", "network", "inspect", "project_gying-net"], commands)
        result = next(c for c in report["checks"] if c["check"].endswith(":cache_network_isolation"))
        self.assertEqual(result["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
