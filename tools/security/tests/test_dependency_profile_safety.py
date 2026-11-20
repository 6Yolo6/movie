"""Fixture commands cannot touch production mounts, credentials, networks or containers."""
import importlib.util
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("dependency_profiles", ROOT / "tools/security/test_dependency_profiles.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
OWNER = "a" * 32
IMAGE = "sha256:" + "b" * 64
ID = "c" * 64


class DependencyProfilesTest(unittest.TestCase):
    def test_all_profiles_are_non_root_offline_unpublished_and_memory_only(self):
        for service in tool.CONTAINERS:
            name, args = tool.fixture_args(service, IMAGE, OWNER, ROOT)
            self.assertTrue(name.startswith(tool.PREFIX + OWNER + "-"))
            for option in ("--network=none", "--pull=never", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true"):
                self.assertIn(option, args)
            self.assertNotIn("-p", args)
            self.assertNotIn("--publish", args)
            self.assertNotIn("-v", args)
            self.assertNotEqual(args[args.index("--user") + 1].split(":")[0], "0")
            for i, arg in enumerate(args):
                if arg == "--env":
                    self.assertNotIn("=", args[i + 1])
                if arg == "--mount":
                    self.assertEqual(service, "redis")
                    self.assertTrue(args[i + 1].endswith("/fixture/redis-entrypoint.sh,readonly"))

    def test_credentials_are_synthetic_and_quark_has_no_login_session(self):
        with patch.dict(os.environ, {"QUARK_COOKIE": "production-marker", "REDIS_PASSWORD": "production-marker", "MINIO_ROOT_PASSWORD": "production-marker"}):
            for service in tool.CONTAINERS:
                self.assertNotIn("production-marker", tool.synthetic_environment(service).values())
            self.assertEqual(tool.synthetic_environment("quark")["QUARK_COOKIE"], "")

    def test_mutable_tags_invalid_service_and_wrong_owner_are_rejected(self):
        for service, image, owner in (("pansou", "latest", OWNER), ("backend", IMAGE, OWNER), ("redis", IMAGE, "unsafe")):
            with self.assertRaises(ValueError):
                tool.fixture_args(service, image, owner, ROOT)

    def test_production_network_port_or_volume_is_never_isolated(self):
        row = {"HostConfig": {"NetworkMode": "none", "Privileged": False, "PortBindings": {}, "ReadonlyRootfs": True, "CapDrop": ["ALL"]}, "Mounts": []}
        self.assertTrue(tool.isolated(row, "quark", ROOT))
        self.assertFalse(tool.isolated({"HostConfig": None}, "quark", ROOT))
        for field, value in (("NetworkMode", "gying-movie_gying-net"), ("Privileged", True), ("PortBindings", {"5005/tcp": []}), ("ReadonlyRootfs", False), ("CapDrop", [])):
            bad = {**row, "HostConfig": dict(row["HostConfig"], **{field: value})}
            self.assertFalse(tool.isolated(bad, "quark", ROOT))
        self.assertFalse(tool.isolated({**row, "Mounts": [{"Type": "volume", "Destination": "/app/config"}]}, "quark", ROOT))
        self.assertFalse(tool.isolated({**row, "Mounts": [{"Type": "bind", "Source": "production", "Destination": "/app/config"}]}, "quark", ROOT))

    def test_cleanup_refuses_production_or_foreign_invocation_names(self):
        with patch.object(tool, "run") as run, patch.object(tool, "inspect") as inspect:
            self.assertFalse(tool.remove_owned("docker", "gying-movie-pansou-1", OWNER))
            self.assertFalse(tool.remove_owned("docker", tool.PREFIX + "d" * 32 + "-pansou", OWNER))
            run.assert_not_called()
            inspect.assert_not_called()

    def test_cleanup_requires_matching_label_and_uses_only_verified_id(self):
        name = tool.PREFIX + OWNER + "-pansou"
        row = {"Name": "/" + name, "Id": ID, "Config": {"Labels": {tool.OWNER_LABEL: OWNER}}}
        good = subprocess.CompletedProcess([], 0, "", "")
        with patch.object(tool, "inspect", return_value=row), patch.object(tool, "run", return_value=good) as run:
            self.assertTrue(tool.remove_owned("docker", name, OWNER))
            self.assertEqual(run.call_args_list[0].args, ("docker", ["rm", "-f", "-v", ID]))
        row["Config"]["Labels"][tool.OWNER_LABEL] = "other"
        with patch.object(tool, "inspect", return_value=row), patch.object(tool, "run") as run:
            self.assertFalse(tool.remove_owned("docker", name, OWNER))
            run.assert_not_called()

    def test_unavailable_daemon_is_not_successful_cleanup(self):
        name = tool.PREFIX + OWNER + "-pansou"
        unavailable = subprocess.CompletedProcess([], 1, "", "sensitive-daemon-diagnostic")
        with patch.object(tool, "inspect", return_value=None), patch.object(tool, "run", return_value=unavailable):
            self.assertFalse(tool.remove_owned("docker", name, OWNER))

    def test_missing_fixture_requires_successful_inventory_confirmation(self):
        name = tool.PREFIX + OWNER + "-pansou"
        for stdout, expected in (("", True), (name + "\n", False)):
            with patch.object(tool, "inspect", return_value=None), patch.object(tool, "run", return_value=subprocess.CompletedProcess([], 0, stdout, "")):
                self.assertEqual(tool.remove_owned("docker", name, OWNER), expected)


if __name__ == "__main__":
    unittest.main()
