"""Run locally on POSIX or in an isolated Python container, never with production mounts."""
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

if 'private_directory' not in sys.modules:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_directory import directory_mode

@unittest.skipUnless(os.name == 'posix', 'requires POSIX permission semantics')
class PrivateDirectoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'config'
        self.path.mkdir(mode=0o777)
        os.chmod(self.path, 0o777)
        self.owner = {'expected_uid': os.getuid(), 'expected_gid': os.getgid()}

    def tearDown(self):
        self.temp.cleanup()

    def test_inspect_does_not_change_mode(self):
        result = directory_mode(str(self.path), **self.owner)
        self.assertEqual(result['mode'], '0777')
        self.assertFalse(result['changed'])

    def test_harden_and_restore_preserve_content_and_identity(self):
        data = self.path / 'fixture.json'
        data.write_bytes(b'fixture-data')
        os.chmod(data, 0o644)
        before = directory_mode(str(self.path), **self.owner)
        private = directory_mode(str(self.path), expected_mode=0o777, target_mode=0o700,
                                 expected_device=before['device'], expected_inode=before['inode'], **self.owner)
        self.assertEqual(private['mode'], '0700')
        self.assertEqual(data.read_bytes(), b'fixture-data')
        self.assertEqual(stat.S_IMODE(data.stat().st_mode), 0o644)
        restored = directory_mode(str(self.path), expected_mode=0o700, target_mode=0o777, **self.owner)
        self.assertEqual(restored['mode'], '0777')
        self.assertEqual(before['inode'], restored['inode'])

    def test_symlink_is_rejected_without_mutation(self):
        link = Path(self.temp.name) / 'link'
        link.symlink_to(self.path, target_is_directory=True)
        with self.assertRaises(OSError):
            directory_mode(str(link), expected_mode=0o777, target_mode=0o700, **self.owner)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o777)

    def test_wrong_owner_is_rejected(self):
        with self.assertRaises(ValueError):
            directory_mode(str(self.path), expected_mode=0o777, target_mode=0o700,
                           expected_uid=os.getuid() + 1, expected_gid=os.getgid())

    def test_mode_drift_is_rejected(self):
        with self.assertRaises(ValueError):
            directory_mode(str(self.path), expected_mode=0o755, target_mode=0o700, **self.owner)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o777)

    def test_inode_drift_is_rejected(self):
        with self.assertRaises(ValueError):
            directory_mode(str(self.path), expected_mode=0o777, target_mode=0o700,
                           expected_inode=self.path.stat().st_ino + 1, **self.owner)

    def test_file_is_rejected(self):
        f = self.path / 'file'
        f.write_text('fixture')
        with self.assertRaises(OSError):
            directory_mode(str(f), **self.owner)

    def test_missing_expected_mode_and_invalid_targets_are_rejected(self):
        for options in ({'target_mode': 0o700}, {'expected_mode': 0o777, 'target_mode': 0o4777}):
            with self.assertRaises(ValueError):
                directory_mode(str(self.path), **options, **self.owner)

    def test_root_and_relative_paths_are_rejected(self):
        for path in ('/', '//', '//.', '//..', '///', '/tmp/..', '.', 'config'):
            with self.subTest(path=path), patch('private_directory.os.open',
                    side_effect=AssertionError('Rejected paths must not be opened')), self.assertRaises(ValueError):
                directory_mode(path, **self.owner)

if __name__ == '__main__':
    unittest.main()
