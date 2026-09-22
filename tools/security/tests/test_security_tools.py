import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

ROOT=Path(__file__).resolve().parents[3]


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


class DummyMCP:
    def __init__(self,*args,**kwargs): pass
    def tool(self): return lambda func:func


class SecurityToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scan=load('tools/security/scan_secrets.py','secret_scan_test')
        cls.verify=load('tools/security/verify_backup.py','backup_verify_test')
        with patch.dict(sys.modules,{'mcp':types.ModuleType('mcp'),'mcp.server':types.ModuleType('mcp.server'),
            'mcp.server.fastmcp':types.SimpleNamespace(FastMCP=DummyMCP),'pymysql':MagicMock()}):
            cls.mysql=load('backend/mysql_gying_mcp.py','mysql_security_test')
            cls.docker=load('backend/docker_mcp.py','docker_security_test')
        with patch.dict(sys.modules,{'requests':MagicMock(),'pymysql':MagicMock(),'minio':types.SimpleNamespace(Minio=MagicMock())}):
            cls.source=load('crawler/gying_crawler.py','crawler_security_test')

    def test_secret_scanner_reports_positions_not_values(self):
        fixture='DB_PASS = "synthetic-sensitive-material"'
        found=self.scan.findings(fixture,'crawler/tool.py')
        self.assertEqual(len(found),1); self.assertNotIn('synthetic-sensitive-material',json.dumps(found))
        self.assertEqual(self.scan.findings('secret: ${JWT_SECRET:?Set JWT_SECRET}','application.yml'),[])

    def test_mcp_sql_rejects_mutation_and_file_read_primitives(self):
        for statement in ['UPDATE sys_user SET role=1','SELECT 1; DELETE FROM sys_user',"SELECT 1 INTO OUTFILE '/tmp/test'",'SELECT SLEEP(9)']:
            with self.assertRaises(ValueError): self.mysql.validate_read_sql(statement)
        self.assertEqual(self.mysql.validate_read_sql("SELECT '%'"),"SELECT '%'")

    def test_mcp_table_identifier_is_not_interpolated_unchecked(self):
        with patch.object(self.mysql,'read_query') as read:
            with self.assertRaises(ValueError): self.mysql.describe_table('x`; DROP TABLE sys_user; --')
            read.assert_not_called()

    def test_mcp_write_tools_fail_closed_by_default(self):
        with patch.dict(os.environ,{'GYING_MCP_ALLOW_WRITES':'0'}):
            with self.assertRaises(PermissionError): self.mysql.execute_sql('DELETE FROM test')
            with self.assertRaises(PermissionError): self.docker.docker_rm('fixture')
            with self.assertRaises(PermissionError): self.docker.compose_down(str(ROOT),True)

    def test_mysql_mcp_refuses_root(self):
        with patch.dict(self.mysql.DB_CONFIG,{'user':'root','password':'synthetic'}):
            with self.assertRaises(ValueError): self.mysql.get_conn()

    def test_docker_output_redaction_covers_json_and_headers(self):
        for text in ['Authorization: Bearer synthetic-secret', '{"access_token":"synthetic-secret"}', 'cookie=synthetic-secret']:
            self.assertNotIn('synthetic-secret',self.docker._redact(text))
        self.assertNotIn('synthetic-secret',str(self.docker._safe_command(['docker','run','-e','CUSTOM=synthetic-secret'])))

    def test_source_token_is_required(self):
        request=types.SimpleNamespace(headers={'X-Internal-Token':''})
        with patch.object(self.source,'API_TOKEN',''):
            self.assertFalse(self.source.GyingSourceApiHandler.authorized(request))
        with patch.object(self.source,'API_TOKEN','fixture'):
            request.headers['X-Internal-Token']='fixture'
            self.assertTrue(self.source.GyingSourceApiHandler.authorized(request))
            request.headers['X-Internal-Token']='wrong'
            self.assertFalse(self.source.GyingSourceApiHandler.authorized(request))

    def test_source_body_bound_and_chunk_parser(self):
        handler=self.source.GyingSourceApiHandler
        request=types.SimpleNamespace(headers={'Content-Length':str(1024*1024+1)},rfile=io.BytesIO())
        with self.assertRaises(ValueError): handler.read_json(request)
        request=types.SimpleNamespace(headers={'Transfer-Encoding':'chunked'},rfile=io.BytesIO(b'2\r\n{}\r\n0\r\n\r\n'))
        self.assertEqual(handler.read_json(request),{})
        request.rfile=io.BytesIO(b'100001\r\n')
        with self.assertRaises(ValueError): handler.read_json(request)

    def test_encrypted_backup_manifest_checks_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory); payload=b'fixture encrypted artifact'; (p/'data.age').write_bytes(payload)
            manifest={'status':'complete','files':[{'name':'data.age','size':len(payload),'sha256':hashlib.sha256(payload).hexdigest()}]}
            (p/'manifest.json').write_text(json.dumps(manifest))
            self.assertEqual(self.verify.verify(p),1)
            (p/'data.age').write_bytes(b'changed')
            with self.assertRaises(ValueError): self.verify.verify(p)

    def test_incomplete_backup_is_never_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory); (p/'manifest.json').write_text(json.dumps({'status':'incomplete','files':[]}))
            with self.assertRaises(ValueError): self.verify.verify(p)


if __name__=='__main__': unittest.main()
