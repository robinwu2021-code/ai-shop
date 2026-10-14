"""密钥票据（ADR-027）的公共部分：私钥位置、签票据、换会话。

私钥在仓库外：默认 ~/work/env/ai-shop/automation-ed25519.pem（0600），可用环境变量
AUTOMATION_KEY 指到别处。**票据与换出来的会话只在本进程里用，不打印** —— 它们都是凭据。
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import time
import urllib.request

from cryptography.hazmat.primitives import serialization

KEY_PATH = os.path.expanduser(os.environ.get("AUTOMATION_KEY", "~/work/env/ai-shop/automation-ed25519.pem"))
BASE_URL = os.environ.get("AUTOMATION_BASE_URL", "https://www.hxmall.top").rstrip("/")
# 服务器只收 60 秒以内的；留 10 秒给网络与两边的时钟差
TTL_SECONDS = 50


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def load_key():
    if not os.path.exists(KEY_PATH):
        raise SystemExit(f"找不到私钥 {KEY_PATH} —— 先跑 scripts/automation/keygen.py")
    mode = os.stat(KEY_PATH).st_mode & 0o777
    if mode & 0o077:
        raise SystemExit(f"私钥权限是 {oct(mode)}，别人可读 —— chmod 600 {KEY_PATH}")
    with open(KEY_PATH, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def sign_ticket(realm: str, sub: str) -> str:
    if realm not in ("B", "OPS"):
        raise SystemExit("realm 只能是 B 或 OPS")
    payload = json.dumps({"realm": realm, "sub": sub, "exp": int(time.time()) + TTL_SECONDS,
                          "nonce": secrets.token_urlsafe(24)}, separators=(",", ":")).encode()
    p = _b64url(payload)
    sig = load_key().sign(("v1." + p).encode("ascii"))
    return f"v1.{p}.{_b64url(sig)}"


def request(method: str, path: str, token: str | None = None, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    # B 端接口按「当前门店」取数，与 App 一样用 X-Store-No 指定（不给 = 默认门店）
    if os.environ.get("AUTOMATION_STORE_NO"):
        req.add_header("X-Store-No", os.environ["AUTOMATION_STORE_NO"])
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300].decode("utf8", "replace")}


def session(realm: str, sub: str) -> str:
    status, resp = request("POST", "/common/auth/automation", body={"ticket": sign_ticket(realm, sub)})
    if status == 404:
        raise SystemExit("线上没开密钥票据登录（/common/auth/automation 404）—— 看 SHOP_AUTOMATION_ENABLED")
    token = ((resp or {}).get("data") or {}).get("token")
    if not token:
        raise SystemExit(f"换会话被拒：HTTP {status} code={(resp or {}).get('code')} msg={(resp or {}).get('msg')}"
                         "（原因码在服务器日志 [automation-login] 那一行）")
    return token


def upload(path: str, token: str, biz_type: str = "GOODS"):
    """传一张图到 /biz/upload/image（multipart），返回 (status, 响应体)。biz_type：GOODS 公开 / QUAL 私有。"""
    import mimetypes
    boundary = "----automation" + secrets.token_hex(8)
    name = os.path.basename(path)
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    with open(path, "rb") as f:
        data = f.read()
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            f"Content-Type: {ctype}\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{BASE_URL}/biz/upload/image?bizType={biz_type}", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    if os.environ.get("AUTOMATION_STORE_NO"):
        req.add_header("X-Store-No", os.environ["AUTOMATION_STORE_NO"])
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, {"raw": e.read()[:300].decode("utf8", "replace")}
