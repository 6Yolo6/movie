"""Offline QQ channel behavior checks using Windows PowerShell 5.1.

No service, production .env, credentials or network is used. Actual SELECT SQL is
executed by SQLite after adapting MySQL UTF-8 hex literals, LEFT and NOW.
This checks selection behavior, but does not certify MySQL collation, query plans,
transaction behavior, or the installed native mysql driver's character encoding.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools'
POWERSHELL = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
ARTIFACTS = ROOT.parent / 'qq-channel-test-runs'
ARTIFACTS.mkdir(parents=True, exist_ok=True)


def connect(path):
    db = sqlite3.connect(path)
    db.create_function('QQ_LEFT', 2, lambda value, size: None if value is None else str(value)[:size])
    db.create_function('NOW', 0, lambda: '2026-10-15 12:00:00')
    return db


def sqlite_sql(sql):
    sql = re.sub(r'CONVERT\(0x([0-9A-Fa-f]+)\s+USING\s+utf8mb4\)', lambda m: "CAST(X'" + m[1] + "' AS TEXT)", sql, flags=re.I)
    sql = re.sub(r'_utf8mb4\s+0x([0-9A-Fa-f]+)', lambda m: "CAST(X'" + m[1] + "' AS TEXT)", sql, flags=re.I)
    sql = re.sub(r'\bLEFT\s*\(', 'QQ_LEFT(', sql, flags=re.I)
    sql = re.sub(r'ON\s+DUPLICATE\s+KEY\s+UPDATE', 'ON CONFLICT DO UPDATE SET', sql, flags=re.I)
    return sql


def fake_mysql(argv):
    fixture = Path(os.environ['QQ_FIXTURE_ROOT']).resolve()
    sql = argv[argv.index('-e') + 1]
    with (fixture / 'mysql-queries.jsonl').open('a', encoding='utf-8') as out:
        out.write(json.dumps({'argv': argv, 'sql': sql}, ensure_ascii=False) + '\n')
    db = connect(fixture / 'fixture.sqlite')
    try:
        if (fixture / 'empty-on-aggregation.marker').exists() and 'WITH eligible_resources AS' in sql:
            # Emulate eligibility changing after candidate selection, in fixture DB only.
            db.execute("UPDATE resource_link SET audit_status=0")
        result = db.execute(sqlite_sql(sql))
        if result.description:
            for row in result.fetchall():
                print('\t'.join('NULL' if value is None else str(value) for value in row))
        db.commit()
    finally:
        db.close()


def fake_cli(argv):
    fixture = Path(os.environ['QQ_FIXTURE_ROOT']).resolve()
    if argv[:2] != ['feed', 'publish-feed']:
        raise RuntimeError('Unexpected fixture CLI command')
    content_path = Path(argv[argv.index('--content-file') + 1]).resolve()
    if not content_path.is_relative_to(fixture):
        raise RuntimeError('Content file escaped fixture temporary directory')
    raw = content_path.read_bytes()
    content = raw.decode('utf-8')
    with (fixture / 'payloads.jsonl').open('a', encoding='utf-8') as out:
        out.write(json.dumps({'argv': argv, 'content': content, 'utf8_bom': raw.startswith(b'\xef\xbb\xbf')}, ensure_ascii=False) + '\n')
    if (fixture / 'cli-failure.marker').exists():
        raise SystemExit(17)
    print('{"success":true,"fixture":true}')


def fake_mode():
    if len(sys.argv) >= 3 and sys.argv[1] in ('--fake-mysql-args', '--fake-cli-args'):
        argv = json.loads(Path(sys.argv[2]).read_text(encoding='utf-8-sig'))
        (fake_mysql if sys.argv[1] == '--fake-mysql-args' else fake_cli)(argv)
        return True
    return False


SCHEMA = """
CREATE TABLE sys_config(config_key TEXT PRIMARY KEY, config_value TEXT);
CREATE TABLE movie_metadata(id TEXT PRIMARY KEY, title_cn TEXT, title_en TEXT, summary TEXT,
 tmdb_type TEXT, category TEXT, poster_url TEXT, year INTEGER, popularity INTEGER,
 tmdb_popularity NUMERIC, status TEXT DEFAULT 'ACTIVE', deleted_at TEXT);
CREATE TABLE resource_link(id INTEGER PRIMARY KEY, movie_id TEXT, provider TEXT, url TEXT, code TEXT,
 status TEXT, audit_status INTEGER, type TEXT, deleted_at TEXT, link_status TEXT,
 source TEXT, updated_at TEXT, created_at TEXT);
CREATE TABLE qq_channel_post_log(id INTEGER PRIMARY KEY AUTOINCREMENT, resource_link_id INTEGER,
 movie_id TEXT, title TEXT, link_url TEXT, channel_type TEXT, channel_id TEXT,
 status TEXT, posted_at TEXT, created_at TEXT, error_message TEXT,
 UNIQUE(resource_link_id,channel_id));
"""


def resource(resource_id=1, provider='QUARK', **kw):
    row = dict(id=resource_id, movie_id='film-a', provider=provider,
        url=f'https://fixture.invalid/{provider.lower()}/{resource_id}', code='',
        status='ACTIVE', audit_status=1, type='DISK', deleted_at=None,
        link_status='NORMAL', source='RESOURCE_HUB', updated_at='2026-10-15 09:00:00',
        created_at='2026-10-14 09:00:00')
    row.update(kw)
    return row


def insert_rows(db, table, rows):
    for row in rows:
        keys = list(row)
        db.execute(f'INSERT INTO {table} ({",".join(keys)}) VALUES ({",".join("?" for _ in keys)})', [row[key] for key in keys])
    db.commit()


def fixture_env(path):
    path = path.resolve()
    for child in ('tmp', 'profile', 'appdata', 'localappdata', 'bin'):
        (path / child).mkdir(exist_ok=True)
    windows = os.environ.get('SystemRoot', r'C:\Windows')
    env = {'SystemRoot': windows, 'WINDIR': windows, 'COMSPEC': windows + r'\System32\cmd.exe',
        'PATH': os.pathsep.join((str(path / 'bin'), str(POWERSHELL.parent), windows + r'\System32', str(Path(sys.executable).parent))),
        'PATHEXT': '.COM;.EXE;.BAT;.CMD;.PS1', 'TEMP': str(path / 'tmp'), 'TMP': str(path / 'tmp'),
        'USERPROFILE': str(path / 'profile'), 'APPDATA': str(path / 'appdata'),
        'LOCALAPPDATA': str(path / 'localappdata'), 'PYTHONIOENCODING': 'utf-8',
        'QQ_FIXTURE_ROOT': str(path), 'QQ_FIXTURE_PYTHON': sys.executable,
        'QQ_FIXTURE_DRIVER': str(Path(__file__).resolve())}
    return env


def ps_run(args, path, timeout=45):
    result = subprocess.run([str(POWERSHELL), '-NoLogo', '-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass', *map(str, args)], cwd=path, env=fixture_env(path),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    (path / 'stdout.txt').write_bytes(result.stdout)
    (path / 'stderr.txt').write_bytes(result.stderr)
    if result.returncode:
        raise AssertionError(f'PowerShell failed ({result.returncode}) in {path}:\n' + result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace'))
    return result


class QqChannelBehaviorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not POWERSHELL.is_file():
            raise unittest.SkipTest('Windows PowerShell 5.1 is required')
        cls.run_root = Path(tempfile.mkdtemp(prefix='offline-', dir=ARTIFACTS))
        cls.helper = TOOLS / 'qq-channel-resources.ps1'
        if not cls.helper.exists():
            raise AssertionError(f'Candidate helper missing: {cls.helper}')
        cls.sql = cls.call_helper({'Action': 'sql', 'MovieId': 'film-a'})
        print(f'QQ fixture artifacts: {cls.run_root}', flush=True)

    @classmethod
    def new_case(cls):
        return Path(tempfile.mkdtemp(prefix='case-', dir=cls.run_root))

    @classmethod
    def call_helper(cls, job, allow_error=False):
        case = cls.new_case()
        (case / 'input.json').write_text(json.dumps(job, ensure_ascii=False), encoding='utf-8')
        ps_run(['-File', TOOLS / 'tests/qq-channel-test-harness.ps1', '-HelperPath', cls.helper,
            '-InputPath', case / 'input.json', '-OutputPath', case / 'result.json'], case)
        result = json.loads((case / 'result.json').read_text(encoding='utf-8-sig'))
        if allow_error:
            return result
        if not result['Ok']:
            raise AssertionError(result['Error'])
        return result['Value']

    def query_resources(self, rows, sql=None):
        db = connect(':memory:')
        db.executescript(SCHEMA)
        insert_rows(db, 'resource_link', rows)
        insert_rows(db, 'movie_metadata', [dict(id=movie_id, status='ACTIVE') for movie_id in {row['movie_id'] for row in rows}])
        result = db.execute(sqlite_sql(sql or self.sql)).fetchall()
        db.close()
        return result

    def parse(self, rows, allow_error=False):
        return self.call_helper({'Action': 'parse', 'Rows': rows}, allow_error)

    def parsed_resources(self):
        return [dict(ResourceId=n, Provider=p, Url=f'https://fixture.invalid/{p.lower()}', Code=c)
            for n, p, c in ((1, 'QUARK', 'q123'), (2, 'XUNLEI', ''), (3, 'BAIDU', 'b456'))]

    def content(self, **kw):
        job = dict(Action='content', Title='测试影片', Link='https://fixture.invalid/legacy',
            Intro='中文简介', Year='2026', MediaType='电影', Template='', Resources=self.parsed_resources())
        job.update(kw)
        return self.call_helper(job)

    def assert_three_links(self, text):
        for provider in ('quark', 'xunlei', 'baidu'):
            self.assertEqual(text.count(f'https://fixture.invalid/{provider}'), 1, text)
        self.assertIn('夸克', text)
        self.assertIn('迅雷', text)
        self.assertIn('百度', text)

    def test_sql_returns_three_providers_including_non_hub_resource(self):
        result = self.query_resources([resource(1), resource(2, 'XUNLEI'), resource(3, 'BAIDU', source='USER')])
        self.assertEqual({row[0] for row in result}, {1, 2, 3})

    def test_sql_latest_uses_updated_at_not_created_at(self):
        result = self.query_resources([resource(1, updated_at='2026-10-15 11:00:00', created_at='2026-01-01'),
            resource(2, updated_at='2026-10-15 10:00:00', created_at='2026-10-15')])
        self.assertEqual([row[0] for row in result], [1])

    def test_sql_latest_tie_uses_highest_id(self):
        self.assertEqual([row[0] for row in self.query_resources([resource(1), resource(2)])], [2])

    def test_sql_provider_grouping_ignores_case_and_outer_spaces(self):
        self.assertEqual(len(self.query_resources([resource(1, 'QUARK'), resource(2, ' quark ')])), 1)

    def test_sql_excludes_invalid_unreviewed_deleted_and_non_disk(self):
        variants = [dict(link_status='INVALID'), dict(link_status='SUSPECT'), dict(audit_status=0),
            dict(audit_status=2), dict(status='DELETED'), dict(deleted_at='2026-10-15'),
            dict(type='MAGNET'), dict(type='TORRENT'), dict(url=''), dict(url=None)]
        for patch in variants:
            with self.subTest(patch=patch):
                self.assertEqual(self.query_resources([resource(1, **patch)]), [])

    def test_sql_null_link_status_is_not_confirmed_usable(self):
        self.assertEqual(self.query_resources([resource(1, link_status=None)]), [])

    def test_sql_never_mixes_other_movies(self):
        result = self.query_resources([resource(1), resource(2, 'BAIDU', movie_id='film-b')])
        self.assertEqual([row[0] for row in result], [1])

    def test_sql_movie_id_handles_chinese_newline_quote_without_injection(self):
        movie_id = "电影\n' OR 1=1 --"
        sql = self.call_helper(dict(Action='sql', MovieId=movie_id))
        result = self.query_resources([resource(1, movie_id=movie_id), resource(2, movie_id='other')], sql)
        self.assertEqual([row[0] for row in result], [1])

    def test_parser_preserves_utf8_chinese_newline_and_extraction_code(self):
        cells = ['1', '夸克', 'https://fixture.invalid/中文', '中\n文码']
        row = '\t'.join([cells[0], *(s.encode('utf-8').hex() for s in cells[1:])])
        parsed = self.parse([row])
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]['Provider'], cells[1])
        self.assertEqual(parsed[0]['Url'], cells[2])
        self.assertEqual(parsed[0]['Code'], cells[3])

    def test_parser_malformed_hex_and_short_rows_never_become_publishable(self):
        for row in ('1\t51\tZZ\t', '1\t51\tA\t', '1\t51', 'invalid\t51\t68747470733A2F2F78\t'):
            with self.subTest(row=row):
                result = self.parse([row], allow_error=True)
                self.assertTrue(not result['Ok'] or not result['Value'], result)

    def test_content_default_contains_all_disks_and_codes(self):
        text = self.content()
        self.assert_three_links(text)
        self.assertIn('q123', text)
        self.assertIn('b456', text)
        self.assertIn('测试影片', text)

    def test_content_legacy_link_placeholder_expands_multidrive_block(self):
        text = self.content(Template='自定义 {{title}}\\n资源 {{link}}\\n{{intro}}')
        self.assert_three_links(text)
        self.assertNotIn('{{link}}', text)
        self.assertIn('自定义 测试影片', text)

    def test_content_template_without_link_appends_all_disks(self):
        self.assert_three_links(self.content(Template='{{title}} {{year}}'))

    def test_content_legacy_single_link_stays_usable(self):
        text = self.content(Resources=[], Template='{{title}}\\n{{link}}')
        self.assertEqual(text.count('https://fixture.invalid/legacy'), 1)
        self.assertNotIn('{{link}}', text)

    def test_content_normalizes_literal_and_real_newlines(self):
        text = self.content(Template='标题 {{title}}\\r\\n年份 {{year}}\r\n{{link}}\\n简介 {{intro}}')
        self.assertNotIn('\\n', text)
        self.assertNotIn('\r', text)
        self.assertIn('\n\n', text)
        self.assert_three_links(text)

    def fixture(self, resources=None, pending=None, posted=None, title='测试电影', template='{{title}}\\n{{link}}\\n{{intro}}'):
        case = self.new_case()
        (case / 'tools').mkdir()
        for name in ('publish-latest-resource-to-qq-channel.ps1', 'publish-qq-channel-feed.ps1', 'qq-channel-resources.ps1'):
            text = (TOOLS / name).read_text(encoding='utf-8-sig')
            # Only the process mutex name differs; no application behavior is replaced.
            text = text.replace('Global\\GYingQqChannelAutoPost', 'Local\\GYingQqChannelFixture' + case.name)
            (case / 'tools' / name).write_text(text, encoding='utf-8-sig')
        env = fixture_env(case)
        for name, mode in (('mysql.ps1', '--fake-mysql-args'), ('tencent-channel-cli.ps1', '--fake-cli-args')):
            stub = "$ErrorActionPreference='Stop'\n$argFile=Join-Path $env:QQ_FIXTURE_ROOT ([guid]::NewGuid().ToString('N')+'.json')\n[IO.File]::WriteAllText($argFile, (ConvertTo-Json -InputObject @($args) -Depth 10), [Text.UTF8Encoding]::new($false))\n& $env:QQ_FIXTURE_PYTHON -X utf8 $env:QQ_FIXTURE_DRIVER '" + mode + "' $argFile\n$global:LASTEXITCODE=$LASTEXITCODE\n"
            (case / 'bin' / name).write_text(stub, encoding='utf-8-sig')
        (case / '.env').write_text('DB_HOST=fixture.invalid\nDB_USER=fixture_only\nDB_PASSWORD=synthetic-test-only\nDB_NAME=qq_fixture\nQQ_CHANNEL_GUILD_ID=fixture-guild\nQQ_CHANNEL_MOVIE_ID=fixture-movie\nQQ_CHANNEL_TV_ID=fixture-tv\n', encoding='utf-8')
        db = connect(case / 'fixture.sqlite')
        db.executescript(SCHEMA)
        config = {'qq.channel.auto_post.enabled': 'true', 'qq.channel.auto_post.daily_time': '00:00',
            'qq.channel.auto_post.post_total': '5', 'qq.channel.auto_post.post_interval_seconds': '0',
            'qq.channel.auto_post.candidate_limit': '20', 'qq.channel.auto_post.template': template}
        insert_rows(db, 'sys_config', [dict(config_key=k, config_value=v) for k, v in config.items()])
        insert_rows(db, 'movie_metadata', [dict(id='film-a', title_cn=title, title_en='', summary='简介第一行\n第二行',
            tmdb_type='movie', category='mv', poster_url='', year=2026, popularity=50, tmdb_popularity=0)])
        insert_rows(db, 'resource_link', resources or [resource(1), resource(2, 'XUNLEI'), resource(3, 'BAIDU')])
        insert_rows(db, 'qq_channel_post_log', (pending or []) + (posted or []))
        db.close()
        return case

    def run_main(self, case, post_id=0):
        runner = "param([string]$Target,[string]$State,[string]$RunLog,[long]$PostLogId=0)\n$ErrorActionPreference='Stop'\nfunction Invoke-WebRequest { throw 'Network forbidden in fixture' }\nfunction Invoke-RestMethod { throw 'Network forbidden in fixture' }\n& $Target -StateFile $State -RunLogFile $RunLog -PostLogId $PostLogId\n"
        (case / 'run.ps1').write_text(runner, encoding='utf-8-sig')
        ps_run(['-File', case / 'run.ps1', '-Target', case / 'tools/publish-latest-resource-to-qq-channel.ps1',
            '-State', case / 'state/posted.txt', '-RunLog', case / 'run.log', '-PostLogId', post_id], case)
        payloads = case / 'payloads.jsonl'
        return [json.loads(line) for line in payloads.read_text(encoding='utf-8').splitlines()] if payloads.exists() else []

    def test_auto_script_publishes_one_post_with_three_disks(self):
        case = self.fixture()
        payloads = self.run_main(case)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        for provider in ('quark/1', 'xunlei/2', 'baidu/3'):
            self.assertIn(f'https://fixture.invalid/{provider}', payloads[0]['content'])
        self.assertFalse(payloads[0]['utf8_bom'])

    def test_auto_and_pending_same_movie_publish_once(self):
        pending = [dict(id=100, resource_link_id=1, movie_id='film-a', title='队列旧标题', link_url='https://fixture.invalid/stale',
            channel_type='movie', status='PENDING', created_at='2026-10-15 08:00:00')]
        case = self.fixture(pending=pending)
        payloads = self.run_main(case)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        self.assertNotIn('https://fixture.invalid/stale', payloads[0]['content'])
        self.assertIn('https://fixture.invalid/baidu/3', payloads[0]['content'])

    def test_bridge_post_log_id_uses_same_all_disk_aggregation(self):
        pending = [dict(id=101, resource_link_id=1, movie_id='film-a', title='队列标题', link_url='https://fixture.invalid/obsolete',
            channel_type='movie', status='PENDING', created_at='2026-10-15 08:00:00')]
        case = self.fixture(pending=pending)
        payloads = self.run_main(case, post_id=101)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        self.assertIn('https://fixture.invalid/quark/1', payloads[0]['content'])
        self.assertIn('https://fixture.invalid/xunlei/2', payloads[0]['content'])
        self.assertIn('https://fixture.invalid/baidu/3', payloads[0]['content'])
        self.assertNotIn('https://fixture.invalid/obsolete', payloads[0]['content'])

    def test_script_latest_and_invalid_selection_reaches_actual_payload(self):
        rows = [resource(1), resource(2, updated_at='2026-10-15 12:00:00', code='abc1'),
            resource(3, 'XUNLEI'), resource(4, 'BAIDU'), resource(5, 'QUARK', link_status='INVALID', updated_at='2026-10-15 13:00:00')]
        case = self.fixture(resources=rows)
        payloads = self.run_main(case)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        text = payloads[0]['content']
        self.assertIn('https://fixture.invalid/quark/2', text)
        self.assertNotIn('https://fixture.invalid/quark/1', text)
        self.assertNotIn('https://fixture.invalid/quark/5', text)
        self.assertIn('abc1', text)

    def test_script_excludes_previously_posted_movie(self):
        posted = [dict(resource_link_id=1, movie_id='film-a', channel_type='movie', channel_id='fixture-movie',
            status='POSTED', created_at='2026-10-14 08:00:00')]
        case = self.fixture(posted=posted)
        self.assertEqual(self.run_main(case), [])

    def test_script_chinese_quotes_newlines_survive_sql_cli_and_log(self):
        title = "中文电影 '导演版'\n下一行"
        case = self.fixture(title=title)
        payloads = self.run_main(case)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        self.assertIn("中文电影 '导演版'", payloads[0]['content'])
        self.assertIn('下一行', payloads[0]['content'])
        self.assertIn('简介第一行', payloads[0]['content'])
        self.assertIn('第二行', payloads[0]['content'])
        db = connect(case / 'fixture.sqlite')
        values = db.execute("SELECT title FROM qq_channel_post_log WHERE status='POSTED'").fetchall()
        db.close()
        self.assertTrue(values)
        self.assertTrue(all(value[0] == title for value in values))


    def test_script_empty_eligible_set_after_selection_never_calls_cli(self):
        case = self.fixture()
        (case / 'empty-on-aggregation.marker').touch()
        self.assertEqual(self.run_main(case), [])
        self.assertEqual((case / 'state/posted.txt').read_text(encoding='utf-8-sig'), '')
        db = connect(case / 'fixture.sqlite')
        self.assertEqual(db.execute("SELECT COUNT(*) FROM qq_channel_post_log WHERE status='POSTED'").fetchone()[0], 0)
        db.close()

    def test_failed_cli_does_not_retry_same_movie_via_pending_or_auto(self):
        pending = [dict(id=200, resource_link_id=1, movie_id='film-a', title='待发', link_url='https://fixture.invalid/stale',
            channel_type='movie', status='PENDING', created_at='2026-10-15 08:00:00')]
        case = self.fixture(pending=pending)
        (case / 'cli-failure.marker').touch()
        payloads = self.run_main(case)
        self.assertEqual(len(payloads), 1, (case / 'run.log').read_text(encoding='utf-8-sig'))
        self.assertEqual((case / 'state/posted.txt').read_text(encoding='utf-8-sig'), '')
        db = connect(case / 'fixture.sqlite')
        self.assertGreater(db.execute("SELECT COUNT(*) FROM qq_channel_post_log WHERE status='FAILED'").fetchone()[0], 0)
        self.assertEqual(db.execute("SELECT COUNT(*) FROM qq_channel_post_log WHERE status='POSTED'").fetchone()[0], 0)
        db.close()

    def test_real_feed_script_accepts_legacy_single_link_without_resources(self):
        case = self.fixture()
        ps_run(['-File', case / 'tools/publish-qq-channel-feed.ps1', '-Title', '旧调用', '-Link',
            'https://fixture.invalid/legacy', '-Intro', '简介', '-GuildId', 'fixture-guild', '-ChannelId', 'fixture-movie'], case)
        payloads = [json.loads(line) for line in (case / 'payloads.jsonl').read_text(encoding='utf-8').splitlines()]
        self.assertEqual(len(payloads), 1)
        self.assertEqual(payloads[0]['content'].count('https://fixture.invalid/legacy'), 1)
        self.assertIn('查看资源', payloads[0]['content'])

    def test_resource_code_uses_url_fallback_and_explicit_code_takes_priority(self):
        resources = [dict(Provider='XUNLEI', Url='https://fixture.invalid/share?pwd=a%2Bb', Code='')]
        result = self.call_helper(dict(Action='links', Resources=resources, Link=''))
        self.assertIn('提取码：a+b', result)
        resources[0]['Code'] = 'explicit7'
        result = self.call_helper(dict(Action='links', Resources=resources, Link=''))
        self.assertIn('提取码：explicit7', result)
        self.assertNotIn('提取码：a+b', result)

    def test_invalid_url_or_utf8_data_is_rejected_before_rendering(self):
        for url in ('javascript:alert(1)', 'https://fixture.invalid/a\n[injected](https://evil.invalid)'):
            with self.subTest(url=url):
                result = self.call_helper(dict(Action='links', Resources=[dict(Provider='QUARK', Url=url, Code='')], Link=''), allow_error=True)
                self.assertFalse(result['Ok'])
        result = self.parse(['1\tFF\t68747470733A2F2F666978747572652E696E76616C6964\t'], allow_error=True)
        self.assertFalse(result['Ok'])

    def test_sql_excludes_inactive_or_deleted_movie(self):
        for status, deleted in (('INACTIVE', None), ('ACTIVE', '2026-10-15')):
            with self.subTest(status=status, deleted=deleted):
                db = connect(':memory:')
                db.executescript(SCHEMA)
                insert_rows(db, 'resource_link', [resource(1)])
                insert_rows(db, 'movie_metadata', [dict(id='film-a', status=status, deleted_at=deleted)])
                self.assertEqual(db.execute(sqlite_sql(self.sql)).fetchall(), [])
                db.close()


if __name__ == '__main__':
    if not fake_mode():
        unittest.main(verbosity=2)
