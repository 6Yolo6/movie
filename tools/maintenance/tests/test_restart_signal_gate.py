"""Isolated Linux PID1 signal gate tests; never send signals or stop containers."""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('signal_gate', Path(__file__).parents[1] / 'weekly_restart.py')
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class RestartSignalGate(unittest.TestCase):
    def setUp(self):
        self.cfg = {'docker': 'fixture-docker', 'recoveryExcluded': {}}
        self.original = {'id': 'a' * 64, 'image': 'sha256:fixture', 'running': True}

    def inspect(self, signal='', init=None, **changes):
        values = {'id': self.original['id'], 'image': self.original['image'], 'signal': signal, 'init': init}
        values.update(changes)
        return '|'.join(json.dumps(values[key]) for key in ('id', 'image', 'signal', 'init'))

    def status(self, process='node', executable='/usr/local/bin/node', caught=0, ignored=0, blocked=0):
        return '\n'.join([f'Name:\t{process}', f'SigCgt:\t{caught:016x}', f'SigIgn:\t{ignored:016x}',
                          f'SigBlk:\t{blocked:016x}', f'PID1Exe: {executable}'])

    def proof(self, metadata=None, status=None):
        with patch.object(w, 'docker', side_effect=[metadata or self.inspect(), status or self.status(caught=1 << 14)]) as docker:
            proof = w.container_signal_proof(self.cfg, 'fixture-service', self.original)
        self.assertEqual([c.args[1] for c in docker.call_args_list], ['inspect', 'exec'])
        self.assertEqual(docker.call_args_list[1].args[2:5], (self.original['id'], 'sh', '-c'))
        self.assertNotIn('kill', docker.call_args_list[1].args[-1])
        return proof

    def test_default_term_with_handler_is_read_only(self):
        self.assertEqual(self.proof()['signal'], 'SIGTERM')
        self.assertEqual(self.proof()['receiver'], 'handler')

    def test_nginx_uses_configured_quit_instead_of_term(self):
        proof = self.proof(self.inspect(signal='SIGQUIT'), self.status(process='nginx', executable='/usr/sbin/nginx', caught=1 << 2))
        self.assertEqual(proof['signal'], 'SIGQUIT')

    def test_numeric_and_short_signal_names_use_linux_numbers(self):
        for value in ('TERM', '15', 15, 'sigterm', None):
            with self.subTest(value=value):
                self.assertEqual(w.stop_signal(value), (15, 'SIGTERM'))

    def test_non_graceful_or_unknown_stop_signals_are_rejected(self):
        for value in ('SIGKILL', 'STOP', '9', '19', 'UNKNOWN', 'SIGRTMIN+1', True):
            with self.subTest(value=value), self.assertRaises(w.Blocked):
                w.stop_signal(value)

    def test_node_and_python_pid1_without_term_handler_are_rejected(self):
        for process, caught in (('node', 0x100000600), ('python', 2)):
            with self.subTest(process=process):
                with patch.object(w, 'docker', side_effect=[self.inspect(), self.status(process=process, caught=caught)]):
                    with self.assertRaisesRegex(w.Blocked, 'has no handler for SIGTERM'):
                        w.container_signal_proof(self.cfg, process, self.original)

    def test_ignored_or_blocked_signal_is_rejected_even_with_handler(self):
        for field, reason in (('ignored', 'ignores'), ('blocked', 'blocks')):
            with self.subTest(field=field):
                with patch.object(w, 'docker', side_effect=[self.inspect(), self.status(caught=1 << 14, **{field: 1 << 14})]):
                    with self.assertRaisesRegex(w.Blocked, reason + ' SIGTERM'):
                        w.container_signal_proof(self.cfg, 'fixture', self.original)

    def test_actual_tini_sigwait_is_allowed_without_host_init_flag(self):
        proof = self.proof(self.inspect(), self.status(process='tini', executable='/sbin/tini', blocked=1 << 14))
        self.assertEqual(proof['receiver'], 'init-forwarder')

    def test_actual_docker_init_is_allowed(self):
        proof = self.proof(self.inspect(init=True), self.status(process='docker-init', executable='/sbin/docker-init', blocked=1 << 14))
        self.assertEqual(proof['receiver'], 'init-forwarder')

    def test_unreadable_exe_uses_actual_pid1_owner_without_extra_capabilities(self):
        first = self.status(process='tini', executable='', blocked=1 << 14) + '\nUid: 0 0 0 0'
        second = self.status(process='tini', executable='/sbin/tini', blocked=1 << 14)
        with patch.object(w, 'docker', side_effect=[self.inspect(), first, second]) as docker:
            proof = w.container_signal_proof(self.cfg, 'fixture', self.original)
        self.assertEqual(proof['receiver'], 'init-forwarder')
        self.assertEqual(docker.call_args_list[2].args[2:5], ('--user', '0', self.original['id']))

    def test_unreadable_exe_without_pid1_owner_fails_conservatively(self):
        with patch.object(w, 'docker', side_effect=[self.inspect(), self.status(executable='')]):
            with self.assertRaisesRegex(w.Blocked, 'Cannot read'):
                w.container_signal_proof(self.cfg, 'fixture', self.original)

    def test_init_flag_cannot_override_observed_node_pid1(self):
        with patch.object(w, 'docker', side_effect=[self.inspect(init=True), self.status(caught=1 << 14)]):
            with self.assertRaisesRegex(w.Blocked, 'not the observed PID1'):
                w.container_signal_proof(self.cfg, 'fixture', self.original)

    def test_tini_that_ignores_configured_signal_is_rejected(self):
        with patch.object(w, 'docker', side_effect=[self.inspect(init=True), self.status(process='tini', executable='/sbin/tini', ignored=1 << 14)]):
            with self.assertRaisesRegex(w.Blocked, 'ignores SIGTERM'):
                w.container_signal_proof(self.cfg, 'fixture', self.original)

    def test_missing_or_invalid_fields_fail_conservatively(self):
        for metadata, status in ((self.inspect(init='true'), self.status()), ('not-json', self.status()),
                                 (self.inspect(), 'Name: node\nSigCgt: 0'),
                                 (self.inspect(), self.status().replace('SigCgt:\t0000000000000000', 'SigCgt: unreadable')),
                                 (self.inspect(), self.status(caught=1 << 64))):
            with self.subTest(metadata=metadata, status=status):
                with patch.object(w, 'docker', side_effect=[metadata, status]):
                    with self.assertRaisesRegex(w.Blocked, 'Cannot read'):
                        w.container_signal_proof(self.cfg, 'fixture', self.original)

    def test_changed_container_identity_is_rejected_before_exec(self):
        with patch.object(w, 'docker', return_value=self.inspect(id='replacement')) as docker:
            with self.assertRaisesRegex(w.Blocked, 'identity changed'):
                w.container_signal_proof(self.cfg, 'fixture', self.original)
        docker.assert_called_once()

    def test_unreadable_proc_or_failed_inspect_blocks_before_stop(self):
        for outcomes in ([w.Blocked('inspect unavailable')], [self.inspect(), w.Blocked('proc unavailable')]):
            with self.subTest(outcomes=outcomes), patch.object(w, 'docker', side_effect=outcomes):
                with self.assertRaises(w.Blocked):
                    w.validate_stop_signals(self.cfg, {'fixture': self.original})

    def test_stopped_services_and_reviewed_child_stop_diagnostic_are_skipped(self):
        self.cfg['recoveryExcluded'] = {'gying-xunlei-path-probe-20261006': self.original}
        current = {'stopped': dict(self.original, running=False), 'gying-xunlei-path-probe-20261006': self.original}
        with patch.object(w, 'container_signal_proof') as proof:
            self.assertEqual(w.validate_stop_signals(self.cfg, current), {})
        proof.assert_not_called()

    def test_failures_are_aggregated_for_all_running_services(self):
        current = {'publisher': self.original, 'quark': self.original}
        with patch.object(w, 'container_signal_proof', side_effect=w.Blocked('no handler')) as proof:
            with self.assertRaisesRegex(w.Blocked, 'publisher: no handler; quark: no handler'):
                w.validate_stop_signals(self.cfg, current)
        self.assertEqual(proof.call_count, 2)

    @unittest.skipUnless(os.name == 'nt', 'Windows elevation preflight contract')
    def test_execute_gate_fails_before_local_stop_journal_or_host_task_changes(self):
        with tempfile.TemporaryDirectory() as root:
            cfg = {'host': 'FIXTURE', 'stateRoot': root}
            for key in ('mysql', 'mysqlDefaults', 'backupConfig', 'backupScript', 'verifyBackupScript',
                        'localStop', 'localStart', 'age', 'ageIdentity', 'python', 'docker', 'boundedBackupScript'):
                cfg[key] = str(Path(root) / key)
                Path(cfg[key]).write_text('fixture')
            original = dict(self.original, paused=False, restarting=False)
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.dict(os.environ, {'COMPUTERNAME': 'FIXTURE'}))
                stack.enter_context(patch.object(w, 'validate_config'))
                stack.enter_context(patch.object(w, 'maintenance_window'))
                ps = stack.enter_context(patch.object(w, 'ps', return_value='China Standard Time'))
                stack.enter_context(patch.object(w.ctypes.windll.shell32, 'IsUserAnAdmin', return_value=True))
                stack.enter_context(patch.object(w, 'containers', return_value={'gying-movie-social-publisher-1': original}))
                stack.enter_context(patch.object(w, 'validate_diagnostics'))
                stack.enter_context(patch.object(w, 'docker', side_effect=[self.inspect(), self.status()]))
                local = stack.enter_context(patch.object(w, 'local_command'))
                host_tasks = stack.enter_context(patch.object(w, 'tasks'))
                with self.assertRaisesRegex(w.Blocked, 'before any service stop'):
                    w.run(cfg, Path(root) / 'config.json', True)
                local.assert_not_called()
                host_tasks.assert_not_called()
                ps.assert_called_once_with('(Get-TimeZone).Id')
                self.assertEqual(w.pending(cfg), [])
                attempt = w.read(next((Path(root) / 'attempt-log').glob('*.json')))
                self.assertEqual(attempt['stage'], 'preflight')
                self.assertIn('has no handler for SIGTERM', attempt['reason'])


if __name__ == '__main__':
    unittest.main()
