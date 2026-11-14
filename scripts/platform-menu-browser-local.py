#!/usr/bin/env python3
"""Loopback-only, read-only static gateway for disposable platform browser acceptance.

Auth/business requests go to actual test-classpath Java applications; this server never
creates sessions, changes roles, fabricates domain responses or accesses production.
"""
import argparse
import http.client
import json
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--shop-output', type=Path, required=True)
    parser.add_argument('--pay-output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=34112)
    args = parser.parse_args()
    roots = {'/ops-shop': args.shop_output.resolve(), '/oms': args.pay_output.resolve()}
    class Gateway(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def do_GET(self): self.route()
        def do_POST(self): self.route()
        def do_PUT(self): self.route()
        def do_DELETE(self): self.route()
        def route(self):
            path = urlsplit(self.path).path
            target = None
            if path.startswith('/oms-api/auth/'):
                target = (34111, self.path.replace('/oms-api/auth/', '/ops/auth/', 1))
            elif path.startswith('/oms-api/pay-ops/'):
                target = (34113, self.path.replace('/oms-api', '', 1))
            elif path.startswith('/pay-ops/'):
                target = (34113, self.path)
            elif path.startswith('/ops/'):
                target = (34111, self.path)
            if target:
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                headers = {key: value for key, value in self.headers.items() if key.lower() not in ('host', 'connection', 'content-length')}
                connection = http.client.HTTPConnection('127.0.0.1', target[0], timeout=30)
                try:
                    connection.request(self.command, target[1], body=body, headers=headers)
                    response = connection.getresponse(); data = response.read()
                    self.send_response(response.status)
                    for key, value in response.getheaders():
                        if key.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                            self.send_header(key, value)
                    self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
                except (OSError, http.client.HTTPException):
                    self.send_error(503, 'Local acceptance application unavailable')
                finally: connection.close()
                return
            if self.command != 'GET': self.send_error(405); return
            for prefix, root in roots.items():
                if path == prefix or path.startswith(prefix + '/'):
                    relative = unquote(path[len(prefix):]).lstrip('/')
                    file = (root / relative).resolve()
                    if not file.is_relative_to(root): self.send_error(403); return
                    if file.is_dir(): file /= 'index.html'
                    if not file.is_file(): self.send_error(404); return
                    data = file.read_bytes()
                    self.send_response(200); self.send_header('Content-Type', mimetypes.guess_type(file.name)[0] or 'application/octet-stream')
                    self.send_header('Cache-Control', 'no-store'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data); return
            self.send_response(302); self.send_header('Location', '/ops-shop/'); self.send_header('Content-Length', '0'); self.end_headers()
        def log_message(self, fmt, *values):
            # Never log Authorization, passwords, tokens, request bodies or query parameters.
            print(self.command, urlsplit(self.path).path, flush=True)
    print(json.dumps({'loopback': True, 'port': args.port}), flush=True)
    class Server(ThreadingHTTPServer):
        request_queue_size = 128
        daemon_threads = True
    Server(('127.0.0.1', args.port), Gateway).serve_forever()

if __name__ == '__main__': main()
