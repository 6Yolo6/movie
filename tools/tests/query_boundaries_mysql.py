#!/usr/bin/env python3
"""Opt-in offline MySQL/mapping tests; no production mounts, ports, credentials or network.

Default is a read-only plan. Pass --run to create uniquely labelled, disposable
fixtures. --full-suite runs all backend tests, then a separate skip-tests compile.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

LABEL = 'com.gying.fixture'
RUN_LABEL = 'com.gying.fixture.run'
KIND = 'query-boundaries-mysql'
MYSQL_HOSTNAME = 'gying-query-fixture'
IMAGES = {'mysql': 'mysql:8.0', 'maven': 'maven:3.9-eclipse-temurin-17'}
REQUIRED_TESTS = {'QueryBoundaryMySqlTest': 7, 'MonitoringServiceMySqlTest': 4}
INSPECT = ('{"id":{{json .Id}},"labels":{{json .Config.Labels}},'
           '"network":{{json .HostConfig.NetworkMode}},"ports":{{json .HostConfig.PortBindings}},'
           '"mounts":{{json .Mounts}},"tmpfs":{{json .HostConfig.Tmpfs}},'
           '"user":{{json .Config.User}},"privileged":{{json .HostConfig.Privileged}},'
           '"readonly":{{json .HostConfig.ReadonlyRootfs}},"security":{{json .HostConfig.SecurityOpt}},'
           '"status":{{json .State.Status}},"exit":{{json .State.ExitCode}}}')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def eligible_source(relative):
    p = Path(relative)
    if p.is_absolute() or '..' in p.parts or not p.parts or p.parts[0] != 'backend':
        return False
    if p.as_posix() != 'backend/pom.xml' and p.parts[:2] != ('backend', 'src'):
        return False
    return not any(part.lower() in {'.git', 'target', 'node_modules', 'dist', 'bin', 'obj', '__pycache__'}
                   or part.lower().startswith('.env') or part.lower().endswith(('.pem', '.key', '.p12', '.pfx'))
                   for part in p.parts)


def own_container(facts, identifier, run_id):
    require(bool(re.fullmatch('[0-9a-f]{64}', identifier)), 'A full container ID is required')
    require(facts.get('id') == identifier, 'Container identity mismatch')
    labels = facts.get('labels') or {}
    require(labels.get(LABEL) == KIND and labels.get(RUN_LABEL) == run_id,
            'Container ownership label mismatch; refusing mutation or cleanup')


class FixtureReportError(RuntimeError):
    def __init__(self, message, report):
        super().__init__(message)
        self.report = report


def summary(reports):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    classes = {}
    for file in sorted(reports.glob('TEST-*.xml')):
        suite = ET.parse(file).getroot()
        values = {key: int(suite.get(key, 0)) for key in totals}
        classes[suite.get('name', '').split('.')[-1]] = values
        for key in totals:
            totals[key] += values[key]
    totals['passed'] = totals['tests'] - totals['failures'] - totals['errors'] - totals['skipped']
    report = {'totals': totals, 'classes': classes, 'required_sql_verified': False}
    if not classes:
        raise FixtureReportError('No Surefire reports were generated', report)
    for name, minimum in REQUIRED_TESTS.items():
        if name not in classes or classes[name]['tests'] < minimum or classes[name]['skipped'] != 0:
            raise FixtureReportError('Required real-MySQL test missing, incomplete or skipped: ' + name, report)
    report['required_sql_verified'] = all(classes[name]['failures'] == 0 and classes[name]['errors'] == 0
                                          for name in REQUIRED_TESTS)
    return report


def maven_script(full_suite):
    options = ['mvn', '-o', '-B', '-ntp', '-s', '/fixture-settings.xml', '-gs', '/fixture-settings.xml',
               '-DargLine=-Duser.home=/tmp -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8']
    selection = [] if full_suite else ['-Dtest=' + ','.join(sorted(REQUIRED_TESTS))]
    commands = [shlex.join([*options, *selection, 'test'])]
    if full_suite:
        commands.append(shlex.join([*options, '-q', '-DskipTests', 'compile']))
        commands.append("printf '%s\\n' passed > /workspace/target/query-boundaries-compile.ok")
    return '\n'.join(commands)


class Docker:
    def __init__(self, binary):
        self.binary = binary

    def call(self, args, *, check=True, input_text=None, timeout=60):
        result = subprocess.run([self.binary, *args], input=input_text, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, encoding='utf-8', timeout=timeout)
        if check:
            require(result.returncode == 0, 'Docker ' + args[0] + ' failed; exit=' + str(result.returncode))
        return result

    def inspect(self, identifier):
        return json.loads(self.call(['inspect', '--format', INSPECT, identifier]).stdout)

    def create(self, args):
        identifier = self.call(['create', *args]).stdout.strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', identifier)), 'Docker returned an invalid container ID')
        return identifier

    def cleanup(self, identifier, run_id):
        facts = self.inspect(identifier)
        own_container(facts, identifier, run_id)
        if facts['status'] == 'running':
            self.call(['stop', '--time', '5', identifier], timeout=30)
        self.call(['rm', identifier])
        remaining = self.call(['ps', '-aq', '--no-trunc', '--filter', 'id=' + identifier]).stdout.strip()
        require(not remaining, 'A fixture container remains after cleanup')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', help='Create and remove this run\'s disposable fixtures')
    parser.add_argument('--full-suite', action='store_true', help='Run all backend tests and a separate skip-tests compile')
    parser.add_argument('--docker', default=shutil.which('docker'))
    parser.add_argument('--maven-repository', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    repo = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip()).resolve()
    require(args.docker is not None, 'An existing Docker CLI is required')
    maven_repo = args.maven_repository.resolve(strict=True)
    require(maven_repo.is_dir() and (maven_repo / 'org/apache/maven').is_dir(), 'Existing Maven artifact cache is required')
    docker = Docker(args.docker)
    images = {}
    for key, tag in IMAGES.items():
        images[key] = docker.call(['image', 'inspect', tag, '--format', '{{.Id}}']).stdout.strip()
        require(bool(re.fullmatch('sha256:[0-9a-f]{64}', images[key])), 'A cached immutable image is required')
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '--', 'backend'],
                                    cwd=repo, text=True, encoding='utf-8').splitlines()
    files = sorted(set(path for path in paths if eligible_source(path)))
    for relative in files:
        src = repo / relative
        require(src.is_file() and src.resolve().is_relative_to(repo), 'Unsafe source path: ' + relative)
    require(any(path.endswith('/QueryBoundaryMySqlTest.java') for path in files), 'Missing SQL regression test')
    print(json.dumps({'mode': 'run' if args.run else 'plan-only', 'network': 'none; runner shares only fixture loopback',
                      'host_ports': [], 'production_mounts': [], 'source_files': len(files),
                      'full_suite': args.full_suite, 'cached_images': images}, indent=2), flush=True)
    if not args.run:
        return 0

    run_id = uuid.uuid4().hex[:12]
    stage = repo / 'tmp' / (KIND + '-' + run_id)
    require(not stage.exists(), 'Refusing to overwrite a prior fixture')
    stage.mkdir(parents=True)
    require(subprocess.run(['git', 'check-ignore', '--quiet', str(stage)], cwd=repo).returncode == 0,
            'Fixture output directory must be ignored by Git')
    manifest = []
    for relative in files:
        dst = stage / relative
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / relative, dst)
        manifest.append({'path': relative, 'sha256': hashlib.sha256(dst.read_bytes()).hexdigest()})
    (stage / 'sources.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    settings = stage / 'maven-settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><localRepository>/m2</localRepository><offline>true</offline></settings>', encoding='utf-8')
    database = 'query_boundary_fixture_' + run_id
    monitoring = 'monitoring_fixture_' + str(int(run_id, 16))
    common = ['--label', LABEL + '=' + KIND, '--label', RUN_LABEL + '=' + run_id,
              '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pull', 'never']
    created = []
    result = {'run_id': run_id, 'started_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'images': images,
              'stage': str(stage), 'production_changed': False, 'full_suite': args.full_suite, 'compile_check_requested': args.full_suite, 'cleanup': []}
    exit_code = 1
    try:
        mysql = docker.create([*common, '--name', 'gying-query-mysql-' + run_id, '--hostname', MYSQL_HOSTNAME,
            '--network', 'none', '--user', '999:999', '--memory', '768m', '--cpus', '1', '--pids-limit', '256',
            '--tmpfs', '/var/lib/mysql:rw,noexec,nosuid,size=512m,uid=999,gid=999',
            '--tmpfs', '/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999',
            '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m,mode=1777',
            '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_INITDB_SKIP_TZINFO=1',
            '--env', 'MYSQL_DATABASE=' + database, '--env', 'TZ=Asia/Shanghai', images['mysql'],
            '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--innodb-lock-wait-timeout=5'])
        created.append(mysql)
        facts = docker.inspect(mysql)
        own_container(facts, mysql, run_id)
        require(facts['network'] == 'none' and not facts['ports'] and not facts['privileged'], 'MySQL isolation failed')
        require(not any(m['Type'] in ('bind', 'volume') for m in facts['mounts']), 'MySQL must not mount persistent data')
        require(facts['readonly'] and facts['user'] == '999:999', 'MySQL filesystem/user gate failed')
        result['mysql_isolation'] = facts
        docker.call(['start', mysql])
        ready = False
        for _ in range(100):
            ping = docker.call(['exec', mysql, 'mysqladmin', '--protocol=tcp', '--host=127.0.0.1', '--user=root', 'ping'], check=False, timeout=8)
            if ping.returncode == 0:
                ready = True
                break
            time.sleep(1)
        require(ready, 'Isolated MySQL did not become ready')
        initialization = ('CREATE TABLE fixture_marker(run_id CHAR(12) PRIMARY KEY);'
                          "INSERT INTO fixture_marker VALUES ('" + run_id + "');CREATE DATABASE " + monitoring + ';')
        docker.call(['exec', '-i', mysql, 'mysql', '--protocol=tcp', '--host=127.0.0.1', '--user=root', database], input_text=initialization)
        runner = docker.create([*common, '--name', 'gying-query-maven-' + run_id,
            '--network', 'container:' + mysql, '--user', '10001:10001', '--memory', '2g', '--cpus', '2', '--pids-limit', '512',
            '--tmpfs', '/tmp:rw,nosuid,size=1g,mode=1777', '--workdir', '/workspace',
            '--mount', 'type=bind,source=' + str(stage / 'backend') + ',target=/workspace',
            '--mount', 'type=bind,source=' + str(maven_repo) + ',target=/m2,readonly',
            '--mount', 'type=bind,source=' + str(settings) + ',target=/fixture-settings.xml,readonly',
            '--env', 'HOME=/tmp', '--env', 'MAVEN_CONFIG=/tmp/maven', '--env', 'TZ=Asia/Shanghai',
            '--env', 'MAVEN_OPTS=-Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai',
            '--env', 'QUERY_BOUNDARY_MYSQL_FIXTURE=network-none-tmpfs', '--env', 'QUERY_BOUNDARY_FIXTURE_ID=' + run_id,
            '--env', 'QUERY_BOUNDARY_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/' + database,
            '--env', 'MONITORING_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/' + monitoring,
            '--env', 'MONITORING_TEST_MYSQL_PASSWORD=', '--entrypoint', 'sh', images['maven'],
            '-ec', maven_script(args.full_suite)])
        created.append(runner)
        facts = docker.inspect(runner)
        own_container(facts, runner, run_id)
        require(facts['network'] == 'container:' + mysql and not facts['ports'] and not facts['privileged'], 'Maven isolation failed')
        mounts = {m['Destination']: m for m in facts['mounts']}
        require(set(mounts) == {'/workspace', '/m2', '/fixture-settings.xml'}, 'Unexpected Maven mounts')
        require(mounts['/workspace']['RW'] and not mounts['/m2']['RW'] and not mounts['/fixture-settings.xml']['RW'], 'Maven mount permissions failed')
        require(facts['readonly'] and facts['user'] == '10001:10001', 'Maven filesystem/user gate failed')
        result['maven_isolation'] = facts
        print('Running real mapper/MySQL tests. Log: ' + str(stage / 'maven.log'), flush=True)
        with (stage / 'maven.log').open('w', encoding='utf-8') as log:
            subprocess.run([docker.binary, 'start', '--attach', runner], stdout=log, stderr=subprocess.STDOUT, timeout=900)
        facts = docker.inspect(runner)
        require(facts['status'] == 'exited', 'Maven container did not exit')
        result['maven_exit'] = facts['exit']
        result['test_summary'] = summary(stage / 'backend/target/surefire-reports')
        require(facts['exit'] == 0 and result['test_summary']['totals']['failures'] == 0
                and result['test_summary']['totals']['errors'] == 0, 'MySQL regression failed; see fixture reports')
        if args.full_suite:
            marker = stage / 'backend/target/query-boundaries-compile.ok'
            result['compile_verified'] = marker.is_file() and marker.read_text(encoding='utf-8') == 'passed\n'
            require(result['compile_verified'], 'The separate skip-tests compile did not complete')
        exit_code = 0
    except Exception as error:
        if isinstance(error, FixtureReportError):
            result['test_summary'] = error.report
        result['error'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('Fixture failed: ' + result['error'], file=sys.stderr, flush=True)
    finally:
        for identifier in reversed(created):
            try:
                docker.cleanup(identifier, run_id)
                result['cleanup'].append({'id': identifier, 'removed': True})
            except Exception as error:
                exit_code = 1
                result['cleanup'].append({'id': identifier, 'removed': False, 'error': type(error).__name__})
        result['exit'] = exit_code
        result['completed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (stage / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'result': str(stage / 'result.json'), 'exit': exit_code,
                          'tests': result.get('test_summary', {}).get('totals'), 'cleanup': result['cleanup']}, indent=2), flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
