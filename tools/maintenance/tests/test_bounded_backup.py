"""Containment tests run only ephemeral child processes, never production services."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("bounded", Path(__file__).parents[1] / "bounded_backup.py")
b = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(b)


class BackupContainment(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Windows job containment")
    def test_deadline_terminates_only_own_worker_and_descendant(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / "fixture"
            with self.assertRaisesRegex(RuntimeError, "budget"):
                b.supervise("unused-fixture-config", folder, timeout_seconds=2, fixture=True)
            self.assertEqual(b.read(folder / "supervisor.json")["status"], "timed_out")
            pid = b.read(folder / "fixture-child.json")["pid"]
            import ctypes
            from ctypes import wintypes
            api = ctypes.WinDLL("kernel32", use_last_error=True)
            api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            api.OpenProcess.restype = wintypes.HANDLE
            handle = api.OpenProcess(0x1000, False, pid)
            if handle:
                api.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
                api.CloseHandle.argtypes = [wintypes.HANDLE]
                code = wintypes.DWORD()
                try:
                    self.assertTrue(api.GetExitCodeProcess(handle, ctypes.byref(code)))
                    self.assertNotEqual(code.value, 259)
                finally:
                    api.CloseHandle(handle)

    def test_cleanup_refuses_existing_production_container_even_with_matching_label(self):
        state = {"token": "attempt", "helpers": ["gying-backup-attempt-0"]}
        row = '|'.join(json.dumps(x) for x in ["/gying-movie-backend-1",
                    {b.LABEL: "attempt"}, True, "none", [{"RW": False}]])
        with patch.object(b.subprocess, "run", side_effect=[Mock(stdout=b"abc"), Mock(stdout=row.encode())]) as run:
            with self.assertRaisesRegex(RuntimeError, "refused"):
                b.cleanup_helpers({"docker": "docker"}, state)
        self.assertEqual(len(run.call_args_list), 2)

    def test_cleanup_refuses_writable_helper_mount(self):
        state = {"token": "attempt", "helpers": ["gying-backup-attempt-0"]}
        row = '|'.join(json.dumps(x) for x in ["/gying-backup-attempt-0",
                    {b.LABEL: "attempt"}, True, "none", [{"RW": True}]])
        with patch.object(b.subprocess, "run", side_effect=[Mock(stdout=b"abc"), Mock(stdout=row.encode())]) as run:
            with self.assertRaisesRegex(RuntimeError, "refused"):
                b.cleanup_helpers({"docker": "docker"}, state)
        self.assertEqual(len(run.call_args_list), 2)

    def test_cleanup_requires_nonce_and_exact_name(self):
        state = {"token": "attempt", "helpers": ["gying-backup-attempt-0"]}
        row = '|'.join(json.dumps(x) for x in ["/gying-backup-attempt-0",
                    {b.LABEL: "other"}, True, "none", [{"RW": False}]])
        with patch.object(b.subprocess, "run", side_effect=[Mock(stdout=b"abc"), Mock(stdout=row.encode())]) as run:
            with self.assertRaisesRegex(RuntimeError, "refused"):
                b.cleanup_helpers({"docker": "docker"}, state)
        self.assertEqual(len(run.call_args_list), 2)


if __name__ == "__main__":
    unittest.main()
