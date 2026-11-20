"""Safety-focused orchestration tests: no production signals or Windows reboot."""
import contextlib
import datetime as dt
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch, Mock

SPEC = importlib.util.spec_from_file_location("weekly", pathlib.Path(__file__).parents[1] / "weekly_restart.py")
w = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(w)


class MaintenanceSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "attempt" / "state.json"
        self.path.parent.mkdir()
        self.cfg = {"stateRoot": self.temp.name, "tasks": sorted(w.TASK_NAMES), "ignoredStaleRows": w.STALE_ROWS,
                    "firstRun": "2026-10-25T09:30:00+08:00", "recoveryExcluded": {},
                    "mysqlService": "MySQL", "docker": "docker", "healthUrls": [], "python": "python"}
        self.original = {"id": "a" * 64, "image": "sha256:original", "running": True, "health": "healthy",
                         "exitCode": 0}
        self.state = {"phase": "stopping", "config": "fixture.json", "stopped": [],
                      "baseline": {"boot": "old-boot", "containers": {"gying-movie-backend-1": self.original},
                                   "tasks": [], "localServices": [], "nativeServices": []}}

    def store(self):
        w.save(self.path, self.state)

    def pending(self):
        self.state["pendingStop"] = {"name": "gying-movie-backend-1", "requestId": "request-a", "receipt": "receipt.json"}
        self.store()

    def receipt(self, **overrides):
        data = {"phase": "completed", "requestId": "request-a", "containerId": self.original["id"], "boot": "old-boot"}
        data.update(overrides)
        w.save(self.path.parent / "receipt.json", data)

    def test_first_future_sunday_only(self):
        for date, allowed in [("2026-10-18T09:30:00+08:00", False), ("2026-10-25T09:29:59+08:00", False),
                              ("2026-10-25T09:30:00+08:00", True), ("2026-10-25T09:40:00+08:00", False),
                              ("2026-10-26T09:30:00+08:00", False)]:
            self.assertEqual(w.within_window(dt.datetime.fromisoformat(date)), allowed)

    def test_final_window_has_bounded_cutoff(self):
        self.assertTrue(w.within_window(dt.datetime.fromisoformat("2026-10-25T10:29:59+08:00"), True))
        self.assertFalse(w.within_window(dt.datetime.fromisoformat("2026-10-25T10:30:00+08:00"), True))

    def test_exact_stale_exception_cannot_hide_updated_work(self):
        old = dict(w.STALE_ROWS[0])
        new = dict(old, updated_at="2026-10-25 09:30:00")
        other = dict(old, id="3427")
        self.assertEqual(w.active([old, new, other], w.STALE_ROWS), [new, other])

    def test_config_cannot_expand_stale_allowlist(self):
        self.cfg["ignoredStaleRows"] = [*w.STALE_ROWS, dict(w.STALE_ROWS[0], id="other")]
        with self.assertRaises(w.Blocked):
            w.validate_config(self.cfg)

    def test_config_cannot_manage_arbitrary_host_task(self):
        self.cfg["tasks"].append("Unrelated task")
        with self.assertRaises(w.Blocked):
            w.validate_config(self.cfg)

    def test_config_cannot_exclude_business_container(self):
        self.cfg["recoveryExcluded"] = {"gying-movie-backend-1": self.original}
        with self.assertRaises(w.Blocked):
            w.validate_config(self.cfg)

    def local_config(self, config):
        root = pathlib.Path(self.temp.name) / "local-ai"
        (root / "scripts").mkdir(parents=True, exist_ok=True)
        (root / ".local").mkdir(exist_ok=True)
        self.cfg["localStart"] = str(root / "scripts" / "start-local.ps1")
        path = root / ".local" / "environment.json"
        w.save(path, config)
        return path

    def test_local_listener_detection_defaults_to_original_ollama_port(self):
        self.local_config({})
        with patch.object(w, "ps", return_value="[11434,5000,5002,3000]"):
            self.assertEqual(w.local_running(self.cfg), ["ollama", "backend", "router", "frontend"])

    def test_local_listener_detection_uses_shared_ollama_port(self):
        self.local_config({"ollamaPort": 21434})
        with patch.object(w, "ps", return_value="[21434,5000,5002,3000]"):
            self.assertEqual(w.local_running(self.cfg), ["ollama", "backend", "router", "frontend"])
        with patch.object(w, "ps", return_value="21434"):
            self.assertEqual(w.local_running(self.cfg), ["ollama"])

    def test_local_listener_detection_does_not_mistake_old_port_for_ollama(self):
        self.local_config({"ollamaPort": 21434})
        with patch.object(w, "ps", return_value="[11434,5000,5002,3000]"):
            self.assertEqual(w.local_running(self.cfg), ["backend", "router", "frontend"])

    def test_local_listener_detection_rejects_invalid_private_configuration(self):
        for value in [True, None, "21434", 21434.0, 1023, 65536, 3000, 5000, 5002]:
            with self.subTest(port=value):
                self.local_config({"ollamaPort": value})
                with patch.object(w, "ps") as ps, self.assertRaises(w.Blocked):
                    w.local_running(self.cfg)
                ps.assert_not_called()
        for value in [[], None, "private-invalid-value"]:
            with self.subTest(config=value):
                self.local_config(value)
                with patch.object(w, "ps") as ps, self.assertRaises(w.Blocked):
                    w.local_running(self.cfg)
                ps.assert_not_called()
        path = self.local_config({})
        path.write_text("private-invalid-json", encoding="utf-8")
        with patch.object(w, "ps") as ps, self.assertRaises(w.Blocked) as error:
            w.local_running(self.cfg)
        self.assertNotIn("private-invalid-json", str(error.exception))
        ps.assert_not_called()
        path.unlink()
        with patch.object(w, "ps") as ps, self.assertRaises(w.Blocked):
            w.local_running(self.cfg)
        ps.assert_not_called()

    def test_stopped_inspect_without_stop_receipt_does_not_allow_recovery(self):
        self.pending()
        with patch.object(w, "boot", return_value="old-boot"), patch.object(w, "ps") as ps:
            with self.assertRaises(w.Blocked):
                w.recover(self.cfg, self.path)
            ps.assert_not_called()

    def test_unrelated_stop_receipt_cannot_authorize_recovery(self):
        self.pending()
        for override in [{"requestId": "request-b"}, {"containerId": "different"}, {"phase": "failed"}, {"boot": "different"}]:
            self.receipt(**override)
            self.assertFalse(w.stop_settled(self.state, self.path))

    def test_completed_exact_rpc_receipt_allows_recovery(self):
        self.pending()
        self.receipt()
        self.assertTrue(w.stop_settled(self.state, self.path))

    def test_changed_container_refuses_start(self):
        self.store()
        with patch.object(w, "boot", return_value="old-boot"), patch.object(w, "ps"), patch.object(w, "cmd"), \
             patch.object(w, "containers", return_value={"gying-movie-backend-1": dict(self.original, id="replacement")}), \
             patch.object(w, "docker") as docker:
            with self.assertRaises(w.Blocked):
                w.recover(self.cfg, self.path)
            docker.assert_not_called()

    def test_only_baseline_local_services_restart(self):
        self.state["baseline"]["containers"] = {}
        self.state["baseline"]["localServices"] = ["ollama", "router"]
        self.store()
        with patch.object(w, "boot", return_value="old-boot"), patch.object(w, "ps"), patch.object(w, "cmd"), \
             patch.object(w, "wait_health", return_value=True), patch.object(w, "local_command") as local:
            w.recover(self.cfg, self.path)
        self.assertEqual([call.args for call in local.call_args_list],
                         [(self.cfg, "Start", "-Service", "ollama"), (self.cfg, "Start", "-Service", "router")])

    def test_dormant_native_service_mode_restored_without_start(self):
        self.state["baseline"]["nativeServices"] = [{"name": "Redis", "state": "Stopped", "startMode": "Auto"},
                                                    {"name": "MySQL", "state": "Running", "startMode": "Auto"}]
        with patch.object(w, "ps") as ps:
            w.pause_dormant_native(self.state)
            w.restore_native_modes(self.state)
        self.assertEqual([c.args[0] for c in ps.call_args_list],
                         ["Set-Service -Name 'Redis' -StartupType Manual", "Set-Service -Name 'Redis' -StartupType Automatic"])

    def test_rpc_worker_uses_infinite_graceful_timeout_and_writes_receipt(self):
        self.pending()
        with patch.object(w, "docker") as docker:
            w.stop_worker(self.cfg, self.path)
        docker.assert_called_once_with(self.cfg, "stop", "--timeout", "-1", self.original["id"], timeout=None)
        self.assertTrue(w.stop_settled(self.state, self.path))

    def test_rpc_failure_never_creates_completed_receipt(self):
        self.pending()
        with patch.object(w, "docker", side_effect=w.Blocked("fixture failure")):
            w.stop_worker(self.cfg, self.path)
        self.assertFalse(w.stop_settled(self.state, self.path))

    def test_controller_dispatches_worker_then_waits_for_its_matching_receipt(self):
        self.store()
        stopped = dict(self.original, running=False)

        def dispatch(*args, **kwargs):
            self.assertEqual(w.read(self.path)["stopped"], [])
            with patch.object(w, "docker") as docker:
                w.stop_worker(self.cfg, self.path)
            docker.assert_called_once_with(self.cfg, "stop", "--timeout", "-1", self.original["id"], timeout=None)

        with patch.object(w, "containers", side_effect=[{"gying-movie-backend-1": self.original},
                                                        {"gying-movie-backend-1": stopped}]), \
             patch.object(w.subprocess, "Popen", side_effect=dispatch) as spawn:
            w.stop(self.cfg, "gying-movie-backend-1", self.original, self.path)
        spawn.assert_called_once()
        self.assertIn("--stop-worker", spawn.call_args.args[0])
        result = w.read(self.path)
        self.assertEqual(result["stopped"], ["gying-movie-backend-1"])
        self.assertNotIn("pendingStop", result)

    def test_off_window_execute_does_not_prepare_or_stop(self):
        with patch.object(w, "assert_window", side_effect=w.Blocked("outside")), patch.object(w, "preflight") as preflight:
            with self.assertRaises(w.Blocked):
                w.run(self.cfg, "fixture.json", True)
            preflight.assert_not_called()

    def test_initial_busy_jobs_wait_for_natural_idle_before_preparation(self):
        baseline = {"at": "fixture"}
        with patch.object(w, "maintenance_window"), \
             patch.object(w, "preflight", side_effect=[w.BusyJobs("busy"), w.BusyJobs("busy"), baseline]) as preflight, \
             patch.object(w.time, "sleep") as sleep:
            self.assertEqual(w.idle_preflight(self.cfg, "fixture.json", None), baseline)
        self.assertEqual(preflight.call_count, 3)
        self.assertEqual([c.args for c in sleep.call_args_list], [(5,), (5,)])
        self.assertFalse(self.path.exists())

    def test_busy_preflight_wait_has_five_minute_deadline(self):
        with patch.object(w, "maintenance_window"), patch.object(w, "preflight", side_effect=w.BusyJobs("busy")), \
             patch.object(w.time, "monotonic", side_effect=[0, 301]), patch.object(w.time, "sleep") as sleep:
            with self.assertRaisesRegex(w.Blocked, "five-minute"):
                w.idle_preflight(self.cfg, "fixture.json", None)
        sleep.assert_not_called()
        self.assertFalse(self.path.exists())

    def test_busy_wait_cannot_cross_the_start_window(self):
        with patch.object(w, "maintenance_window", side_effect=[None, w.Blocked("outside")]), \
             patch.object(w, "preflight", side_effect=w.BusyJobs("busy")) as preflight, \
             patch.object(w.time, "sleep"):
            with self.assertRaisesRegex(w.Blocked, "outside"):
                w.idle_preflight(self.cfg, "fixture.json", None)
        preflight.assert_called_once()
        self.assertFalse(self.path.exists())

    def test_unrelated_preflight_failure_is_not_retried(self):
        with patch.object(w, "maintenance_window"), patch.object(w, "preflight", side_effect=w.Blocked("AC disconnected")), \
             patch.object(w.time, "sleep") as sleep:
            with self.assertRaisesRegex(w.Blocked, "AC disconnected"):
                w.idle_preflight(self.cfg, "fixture.json", None)
        sleep.assert_not_called()

    def test_failed_recovery_retains_retryable_journal(self):
        self.store()
        with patch.object(w, "recover", side_effect=w.Blocked("dependency unavailable")):
            self.assertFalse(w.attempt_recovery(self.cfg, self.path))
        self.assertEqual(w.read(self.path)["phase"], "recovery_failed")
        self.assertEqual(w.pending(self.cfg), [self.path])

    def test_journal_outside_state_root_rejected(self):
        self.cfg["stateRoot"] = str(pathlib.Path(self.temp.name) / "private")
        pathlib.Path(self.cfg["stateRoot"]).mkdir()
        self.store()
        with self.assertRaises(w.Blocked):
            w.state_path(self.cfg, self.path)

    def test_restore_does_not_recreate_containers(self):
        self.state["baseline"]["containers"] = {"gying-xunlei-path-probe-20261006": self.original}
        self.cfg["recoveryExcluded"] = {"gying-xunlei-path-probe-20261006": self.original}
        self.store()
        with patch.object(w, "boot", return_value="old-boot"), patch.object(w, "ps"), patch.object(w, "cmd"), \
             patch.object(w, "wait_health", return_value=True), patch.object(w, "docker") as docker:
            w.recover(self.cfg, self.path)
        docker.assert_not_called()

    def test_backup_authentication_rejects_wrong_private_key(self):
        backup = pathlib.Path(self.temp.name) / "backup.json"
        recipient = pathlib.Path(self.temp.name) / "recipient.txt"
        recipient.write_text("age1fixture")
        w.save(backup, {"age_recipient_file": str(recipient)})
        self.cfg.update(backupConfig=str(backup), age="age", ageIdentity="key.txt")
        with patch.object(w.subprocess, "run", side_effect=[Mock(returncode=0, stdout=b"cipher"), Mock(returncode=1, stdout=b"")]):
            with self.assertRaises(w.Blocked):
                w.authenticate_backup_identity(self.cfg)

    @unittest.skipUnless(__import__("os").name == "nt", "Windows byte-lock contract")
    def test_global_lock_blocks_competing_controller(self):
        with w.lock(self.cfg):
            with self.assertRaises(w.Blocked):
                with w.lock(self.cfg):
                    self.fail("second lock acquired")


if __name__ == "__main__":
    unittest.main()
