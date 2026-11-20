import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request


@unittest.skipIf(os.name == 'nt', 'Production POSIX signal behavior is verified in Python 3.12 container')
class GyingGracefulShutdownTest(unittest.TestCase):
    def test_sigterm_finishes_an_active_request_before_exiting(self):
        child = r'''
import sys, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler
from crawler.gying_crawler import BoundedThreadingHTTPServer, serve_source
root = Path(sys.argv[1])
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        (root/'active').touch()
        while not (root/'release').exists(): time.sleep(0.02)
        self.send_response(200); self.end_headers(); self.wfile.write(b'completed')
    def log_message(self, *args): pass
server = BoundedThreadingHTTPServer(('127.0.0.1', 0), Handler)
(root/'port').write_text(str(server.server_port))
serve_source(server)
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = subprocess.Popen([sys.executable, '-c', child, directory], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            reply = []
            def wait_for(path):
                end = time.monotonic() + 10
                while not path.exists() and time.monotonic() < end:
                    time.sleep(0.02)
                self.assertTrue(path.exists())
            def request():
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                        'http://127.0.0.1:' + (root/'port').read_text(), timeout=10) as response:
                    reply.append(response.read())
            try:
                wait_for(root/'port')
                thread = threading.Thread(target=request)
                thread.start()
                wait_for(root/'active')
                process.send_signal(signal.SIGTERM)
                time.sleep(0.7)
                self.assertIsNone(process.poll(), 'Active handler must finish before process exits')
                (root/'release').touch()
                thread.join(timeout=10)
                self.assertEqual(process.wait(timeout=10), 0)
                self.assertEqual(reply, [b'completed'])
            finally:
                (root/'release').touch()
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM)
                    process.wait(timeout=10)
