"""Exercise the bootstrap command against disposable owning-service API fixtures."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


class BootstrapTest(unittest.TestCase):
    def test_export_and_reapply_preserve_watermark_and_revoke_hr(self):
        applied = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, code, body):
                self.send_response(code)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_GET(self):
                if self.headers.get('Authorization') != 'Bearer synthetic-test-token':
                    return self.reply(401, {})
                if self.path.startswith('/api/users?'):
                    users = [{'userId': 11, 'departmentId': 5, 'isBanned': False, 'roles': ['HR_MANAGER']},
                             {'userId': 12, 'departmentId': None, 'isBanned': True, 'roles': ['EMPLOYEE']}]
                    return self.reply(200, {'users': users, 'hasNext': False})
                if self.path == '/api/employees/11':
                    return self.reply(200, {'status': 'ACTIVE', 'reportingManagerUserId': 12})
                return self.reply(404, {})

            def do_PUT(self):
                applied.append((self.path, json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                self.send_response(204)
                self.end_headers()

        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            command = [sys.executable, '-B', str(Path(__file__).with_name('bootstrap.py')),
                       '--gateway', f'http://127.0.0.1:{server.server_port}']
            env = {**os.environ, 'CLAIM_BOOTSTRAP_TOKEN': 'synthetic-test-token'}
            try:
                exported = subprocess.run(command, env=env, text=True, capture_output=True, check=True)
                self.assertEqual(applied, [])
                snapshot = json.loads(exported.stdout)
                self.assertEqual(snapshot['employees'][0]['reportingManagerUserId'], 12)
                self.assertTrue(snapshot['employees'][0]['active'])
                self.assertFalse(snapshot['employees'][1]['active'])
                self.assertEqual(snapshot['reviewers'], [{'userId': 11, 'role': 'HR_MANAGER'}, {'userId': 12, 'role': None}])
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'scope.json'
                    path.write_text(exported.stdout)
                    for _ in range(2):
                        subprocess.run(command + ['--input', str(path), '--apply'], env=env, capture_output=True, check=True)
                self.assertEqual(len(applied), 8)
                self.assertEqual(applied[:4], applied[4:])
                self.assertTrue(all(body['asOf'] == snapshot['asOf'] for _, body in applied))
            finally:
                server.shutdown()
                thread.join()


if __name__ == '__main__':
    unittest.main()
