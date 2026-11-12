#!/usr/bin/env python3
"""Near Pay Open API signing example. Python standard library; no network on --self-test."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def key_bytes(secret):
    for prefix in ('sk_test_', 'sk_live_'):
        if secret.startswith(prefix):
            secret = secret[len(prefix):]
            break
    return secret.encode('utf-8')


def canonical_query(raw_query):
    # RFC3986: '+' is a literal plus, not the form-encoded spelling of a space.
    pairs = urllib.parse.parse_qsl(raw_query.replace('+', '%2B'), keep_blank_values=True)
    enc = lambda value: urllib.parse.quote(value, safe='~-._')
    return '&'.join(enc(k) + '=' + enc(v) for k, v in sorted(pairs))


def p1(secret, method, path, raw_query, app_id, timestamp, nonce, body):
    text = '\n'.join((method.upper(), path, canonical_query(raw_query), app_id,
                      timestamp, nonce, hashlib.sha256(body).hexdigest()))
    return base64.b64encode(hmac.new(key_bytes(secret), text.encode('utf-8'), hashlib.sha256).digest()).decode('ascii')


def p2_valid(secret, headers, body, *, now_ms=None):
    headers = {k.lower(): v for k, v in headers.items()}
    try:
        timestamp = headers['x-timestamp']
        nonce = headers['x-nonce']
        given = base64.b64decode(headers['x-signature'], validate=True)
        now_ms = int(time.time() * 1000) if now_ms is None else now_ms
        if abs(now_ms - int(timestamp)) > 300_000 or not 8 <= len(nonce) <= 64:
            return False
        text = '\n'.join((timestamp, nonce, hashlib.sha256(body).hexdigest()))
        expected = hmac.new(key_bytes(secret), text.encode('utf-8'), hashlib.sha256).digest()
        return hmac.compare_digest(given, expected)
    except (KeyError, ValueError, TypeError):
        return False


class OpenApi:
    """Single-current-key example. Extend key lookup by X-Key-Serial before supporting rotation."""
    def __init__(self, base_url, app_id, app_secret, key_serial=None):
        origin = urllib.parse.urlsplit(base_url)
        if origin.scheme != 'https' or not origin.hostname or origin.path not in ('', '/') or origin.query or origin.fragment or origin.username:
            raise ValueError('base_url must be an HTTPS origin without a path or credentials')
        self.base_url = base_url.rstrip('/')
        self.app_id, self.app_secret, self.key_serial = app_id, app_secret, key_serial

    def call(self, method, path, payload=None, raw_query=''):
        if not path.startswith('/open/pay/v1/') or '?' in path or '#' in path:
            raise ValueError('pass the API path and query separately')
        # Serialize exactly once, then sign and send these same bytes.
        body = b'' if payload is None else json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        timestamp, nonce = str(int(time.time() * 1000)), secrets.token_hex(16)
        query = canonical_query(raw_query)
        headers = {
            'Content-Type': 'application/json; charset=UTF-8',
            'X-App-Id': self.app_id, 'X-Timestamp': timestamp, 'X-Nonce': nonce,
            'X-Signature': p1(self.app_secret, method, path, query, self.app_id, timestamp, nonce, body),
        }
        if self.key_serial:
            headers['X-Key-Serial'] = self.key_serial
        request = urllib.request.Request(self.base_url + path + ('?' + query if query else ''),
                                         data=None if method.upper() == 'GET' and payload is None else body,
                                         headers=headers, method=method.upper())
        # Do not automatically redirect a signed request to another URL.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, response_headers, newurl):
                return None
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, response_headers, raw_body = response.code, dict(response.headers), response.read()
        serial = next((v for k, v in response_headers.items() if k.lower() == 'x-key-serial'), None)
        if serial and serial != self.key_serial:
            raise RuntimeError('unknown signing serial; use a configured serial-to-secret key ring')
        if not p2_valid(self.app_secret, response_headers, raw_body):
            # Missing/invalid signatures must not be treated as financial results.
            raise RuntimeError('unverified response; check application/key configuration or query the original order')
        return status, json.loads(raw_body)


def self_test():
    vectors = json.loads(Path(__file__).with_name('test-vectors.json').read_text(encoding='utf-8'))
    req = vectors['p1']
    body = req['body'].encode('utf-8')
    assert hashlib.sha256(body).hexdigest() == req['bodySha256']
    assert p1(vectors['appSecret'], req['method'], req['path'], req['query'], vectors['appId'],
              req['timestamp'], req['nonce'], body) == req['signature']
    assert canonical_query('b=%2B&a=hello+world&a=hello%20world&empty=') == 'a=hello%20world&a=hello%2Bworld&b=%2B&empty='
    res = vectors['p2']
    assert p2_valid(vectors['appSecret'], res['headers'], res['body'].encode(), now_ms=int(res['headers']['X-Timestamp']))
    assert not p2_valid(vectors['appSecret'], res['headers'], b'{"changed":true}', now_ms=int(res['headers']['X-Timestamp']))
    assert not p2_valid(vectors['appSecret'], res['headers'], res['body'].encode(), now_ms=int(res['headers']['X-Timestamp']) + 300_001)
    assert not p2_valid('wrong-key', res['headers'], res['body'].encode(), now_ms=int(res['headers']['X-Timestamp']))
    print('PASS: P1/P2, canonical query, tampering, wrong key and expired response')


if __name__ == '__main__':
    if sys.argv[1:] == ['--self-test']:
        self_test()
    else:
        print('Import OpenApi for integration; run --self-test for offline vectors. No request was sent.')
