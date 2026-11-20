"""Attempt records and one-off bounds; never stop production or schedule a reboot."""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('restart',Path(__file__).parents[1]/'weekly_restart.py')
w=importlib.util.module_from_spec(spec);spec.loader.exec_module(w)

class RestartAttempts(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.cfg={'stateRoot':str(self.root),'host':'FIXTURE'}
        self.config=self.root/'config.json';self.config.write_text('{}')
        self.permit=self.root/'one-off'/'fixture.json';self.permit.parent.mkdir()
        self.data={'version':1,'id':'fixture','host':'FIXTURE','configSha256':hashlib.sha256(self.config.read_bytes()).hexdigest(),'runtimeSha256':hashlib.sha256(Path(w.__file__).read_bytes()).hexdigest(),'startAt':'2026-10-25T18:00:00+08:00','latestStartAt':'2026-10-25T18:10:00+08:00','finishBefore':'2026-10-25T19:00:00+08:00'}
        w.save(self.permit,self.data)
    def test_preflight_failure_is_logged_without_pending_recovery(self):
        with patch.object(w,'assert_window'),patch.object(w,'preflight',side_effect=w.Blocked('Active business jobs')):
            with self.assertRaises(w.Blocked):w.run(self.cfg,self.config,True)
        records=list((self.root/'attempt-log').glob('*.json'));self.assertEqual(len(records),1)
        record=w.read(records[0]);self.assertEqual(record['stage'],'preflight');self.assertEqual(record['reason'],'Active business jobs');self.assertEqual(w.pending(self.cfg),[])
    def test_off_window_does_not_preflight_or_create_recovery_journal(self):
        with patch.object(w,'assert_window',side_effect=w.Blocked('Outside window')),patch.object(w,'preflight') as preflight:
            with self.assertRaises(w.Blocked):w.run(self.cfg,self.config,True)
            preflight.assert_not_called()
        self.assertEqual(w.pending(self.cfg),[])
    def test_configuration_and_runtime_changes_revoke_permit(self):
        self.config.write_text('{"changed":true}')
        with self.assertRaises(w.Blocked):w.oneoff_permit(self.cfg,self.config,self.permit)
        self.config.write_text('{}');self.data['runtimeSha256']='0'*64;w.save(self.permit,self.data)
        with self.assertRaises(w.Blocked):w.oneoff_permit(self.cfg,self.config,self.permit)
    def test_arbitrary_external_permit_is_rejected(self):
        other=self.root/'external.json';w.save(other,self.data)
        with self.assertRaises(w.Blocked):w.oneoff_permit(self.cfg,self.config,other)
    def test_unbounded_or_wrong_timezone_windows_are_rejected(self):
        for key,value in [('latestStartAt','2026-10-25T18:11:00+08:00'),('finishBefore','2026-10-25T19:01:00+08:00'),('startAt','2026-10-25T18:00:00')]:
            w.save(self.permit,{**self.data,key:value})
            with self.assertRaises(w.Blocked):w.oneoff_permit(self.cfg,self.config,self.permit)
    def test_a_failed_oneoff_is_consumed_and_cannot_reboot_on_retry(self):
        with patch.object(w,'maintenance_window'),patch.object(w,'preflight',side_effect=w.Blocked('busy')) as preflight:
            with self.assertRaises(w.Blocked):w.run(self.cfg,self.config,True,self.permit)
            self.assertTrue(w.read(self.permit)['usedAt'])
            with self.assertRaises(w.Blocked):w.run(self.cfg,self.config,True,self.permit)
            self.assertEqual(preflight.call_count,1)
        self.assertEqual(w.pending(self.cfg),[])
    def test_weekly_window_is_still_the_default(self):
        with patch.object(w,'assert_window') as check:
            w.maintenance_window(self.cfg,self.config,None,final=True);check.assert_called_once_with(True)

if __name__=='__main__':unittest.main()
