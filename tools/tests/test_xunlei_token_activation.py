"""Isolated regression for the exact shell command used by the PowerShell sync task.

Uses the already-built backend image without network, secrets, or production volumes.
Run: python tools/tests/test_xunlei_token_activation.py
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOCKER = shutil.which("docker")
IMAGE = os.environ.get("XUNLEI_ACTIVATION_TEST_IMAGE", "gying-movie-backend")
SCRIPT = (ROOT / "tools/sync-xunlei-edge-token.ps1").read_text(encoding="utf-8-sig")
MATCH = re.search(r"\$stateJson \| & \$DockerPath exec -i \$container sh -c '((?:''|[^'])*)'", SCRIPT)
if not MATCH:
    raise RuntimeError("Expected unprivileged stdin activation command not found")
COMMAND = MATCH.group(1).replace("''", "'").replace("/app/data/", "/tmp/xunlei-activation-test/")
DATA = "/tmp/xunlei-activation-test"
PAYLOAD = json.dumps({"access_token": "synthetic-test-only", "label": "\u8fc5\u96f7\u6d4b\u8bd5"}, ensure_ascii=False)


@unittest.skipUnless(DOCKER, "Docker CLI required")
class ActivationTests(unittest.TestCase):
    def run_shell(self, body):
        result = subprocess.run(
            [DOCKER, "run", "--rm", "-i", "--network", "none", "--cap-drop", "ALL",
             "--security-opt", "no-new-privileges:true", "--user", "10001:10001",
             "--entrypoint", "sh", IMAGE, "-c", body],
            input=PAYLOAD.encode("utf-8"), capture_output=True, timeout=60,
        )
        # docker run needs interactive stdin, without a pseudo-TTY.
        return result

    def run_activation(self, setup="", command=COMMAND):
        body = (
            f"set -eu; mkdir -m 700 {DATA}; " + setup +
            f"sh -c {shell_quote(command)}; " +
            f"stat -c '%u:%g %a' {DATA}/xunlei-auth.json; " +
            f"cat {DATA}/xunlei-auth.json; " +
            f"test -z \"$(find {DATA} -name '.xunlei-auth.*' -print)\""
        )
        result = self.run_shell(body)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        permission, content = result.stdout.decode("utf-8").split("\n", 1)
        self.assertEqual(permission, "10001:10001 600")
        self.assertEqual(json.loads(content), json.loads(PAYLOAD))

    def test_first_write_unicode_owner_and_mode(self):
        self.run_activation()

    def test_existing_state_is_atomically_replaced(self):
        self.run_activation(f"printf old > {DATA}/xunlei-auth.json; chmod 600 {DATA}/xunlei-auth.json; ")

    def test_failed_rename_preserves_original_and_removes_temporary(self):
        broken = COMMAND.replace(f"mv -f -- \"$tmp\" {DATA}/xunlei-auth.json", "false")
        self.assertNotEqual(broken, COMMAND)
        body = (
            f"set -eu; mkdir -m 700 {DATA}; printf old > {DATA}/xunlei-auth.json; "
            f"if sh -c {shell_quote(broken)}; then exit 91; fi; "
            f"test \"$(cat {DATA}/xunlei-auth.json)\" = old; "
            f"test -z \"$(find {DATA} -name '.xunlei-auth.*' -print)\"; echo rollback-preserved"
        )
        result = self.run_shell(body)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        self.assertEqual(result.stdout.strip(), b"rollback-preserved")


def shell_quote(value):
    return "'" + value.replace("'", "'\"'\"'") + "'"


if __name__ == "__main__":
    unittest.main(verbosity=2)
