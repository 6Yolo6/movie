"""Guarded POSIX directory-mode changes; never traverses or rewrites file contents."""
import argparse
import json
import os
import stat


def directory_mode(path, *, expected_mode=None, target_mode=None, expected_uid=0,
                   expected_gid=0, expected_device=None, expected_inode=None):
    if os.name != 'posix':
        raise RuntimeError('POSIX directory permissions required')
    if not os.path.isabs(path) or os.path.normpath(path) in ('/', '//'):
        raise ValueError('An absolute non-root directory is required')
    if (expected_mode is None) != (target_mode is None):
        raise ValueError('Mutation requires both expected and target modes')
    for mode in (expected_mode, target_mode):
        if mode is not None and (not isinstance(mode, int) or not 0 <= mode <= 0o777):
            raise ValueError('Only ordinary permission bits are supported')
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISDIR(before.st_mode):
            raise ValueError('Not a directory')
        if (before.st_uid, before.st_gid) != (expected_uid, expected_gid):
            raise ValueError('Directory owner mismatch')
        if expected_device is not None and before.st_dev != expected_device:
            raise ValueError('Directory device mismatch')
        if expected_inode is not None and before.st_ino != expected_inode:
            raise ValueError('Directory inode mismatch')
        mode = stat.S_IMODE(before.st_mode)
        if target_mode is not None:
            if mode != expected_mode:
                raise ValueError('Directory mode changed since inspection')
            os.fchmod(fd, target_mode)
        after = os.fstat(fd)
        current = os.lstat(path)
        if (current.st_dev, current.st_ino) != (before.st_dev, before.st_ino):
            raise RuntimeError('Directory path identity changed')
        if (after.st_uid, after.st_gid) != (before.st_uid, before.st_gid):
            raise RuntimeError('Directory ownership changed')
        if stat.S_IMODE(after.st_mode) != (target_mode if target_mode is not None else mode):
            raise RuntimeError('Directory permission verification failed')
        return {'path': path, 'before_mode': format(mode, '04o'),
                'mode': format(stat.S_IMODE(after.st_mode), '04o'),
                'uid': after.st_uid, 'gid': after.st_gid,
                'device': after.st_dev, 'inode': after.st_ino,
                'changed': target_mode is not None and target_mode != mode}
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--path', required=True)
    parser.add_argument('--expected-mode', type=lambda v: int(v, 8))
    parser.add_argument('--target-mode', type=lambda v: int(v, 8))
    parser.add_argument('--expected-uid', type=int, default=0)
    parser.add_argument('--expected-gid', type=int, default=0)
    parser.add_argument('--expected-device', type=int)
    parser.add_argument('--expected-inode', type=int)
    args = parser.parse_args()
    try:
        result = directory_mode(args.path, expected_mode=args.expected_mode,
                                target_mode=args.target_mode, expected_uid=args.expected_uid,
                                expected_gid=args.expected_gid, expected_device=args.expected_device,
                                expected_inode=args.expected_inode)
        print(json.dumps(result))
    except Exception as error:
        # Do not echo exception arguments, directory contents or file data.
        print(json.dumps({'status': 'failed', 'error_class': type(error).__name__}))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
