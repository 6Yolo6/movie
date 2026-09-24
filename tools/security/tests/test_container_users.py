"""Regression coverage for Docker Desktop's required PID column."""
import importlib.util
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("container_security", ROOT / "tools/security/check_security.py")
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)


class ContainerUsersTest(unittest.TestCase):
    def check(self, output, returncode=0):
        return security.process_user_check(subprocess.CompletedProcess([], returncode, output, ""))

    def test_non_root_numeric_uid(self):
        self.assertEqual(self.check("PID UID COMMAND\n5549 10001 java\n5550 999 redis-server\n"), ("PASS", []))

    def test_root_is_detected_in_uid_not_pid_column(self):
        self.assertEqual(self.check("PID UID COMMAND\n1 10001 java\n20 0 sh\n"), ("FAIL", ["sh"]))

    def test_daemon_failure_is_unknown_without_error_output(self):
        self.assertEqual(self.check("daemon diagnostic", 1), ("UNKNOWN", []))

    def test_empty_or_malformed_data_is_not_a_pass(self):
        for output in ["", "PID UID COMMAND\n", "USER COMMAND\ngying java", "PID UID COMMAND\n1 unknown java", "PID UID COMMAND\n1 10001 java\nbad row"]:
            with self.subTest(output=output):
                self.assertEqual(self.check(output), ("UNKNOWN", []))

    def test_known_root_remains_failure_if_other_rows_are_malformed(self):
        self.assertEqual(self.check("PID UID COMMAND\n1 0 sh\ninvalid"), ("FAIL", ["sh"]))

    def test_collect_requests_pid_and_numeric_uid_without_arguments(self):
        import json
        row = {"Name": "/gying-movie-fixture-1", "HostConfig": {}, "Config": {}}
        def run(command, timeout=30):
            if command[:2] == ["docker", "inspect"]:
                output = json.dumps([row])
            elif command[:2] == ["docker", "ps"]:
                output = "fixture-id"
            elif command[:2] == ["docker", "top"]:
                self.assertEqual(command, ["docker", "top", "gying-movie-fixture-1", "-eo", "pid,uid,comm"])
                output = "PID UID COMMAND\n123 10001 java"
            else:
                output = ""
            return subprocess.CompletedProcess(command, 0, output, "")
        with patch.object(security, "run", side_effect=run):
            result = security.collect(ROOT)
        check = next(c for c in result["checks"] if c["check"].endswith(":root_processes"))
        self.assertEqual(check["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
