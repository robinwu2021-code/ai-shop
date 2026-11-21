"""ai-shop v2（网关版本）接口验证用的会话与请求。

票据在本机签（私钥在仓库外），换会话的接口 `/common/auth/automation` 不对公网开放，
所以经跳板机在 192.168.3.80 上调用；拿到的令牌只留在本进程内存里，不打印、不落盘。
之后的业务请求走公网入口，与真实的端走同一条路（nginx → 网关 → 入口）。

三端的钥各不相同：运营端、B 端、C 端各有一把私钥，对应 v2 上按端配的公钥
（`SHOP_AUTOMATION_PUBLIC_KEY_OPS` / `_B` / `_C`）。一把钥签不出别的端的票据。

环境变量：
  AUTOMATION_KEY_OPS  运营端私钥，默认 ~/work/env/ume/automation-ume-ed25519.pem
  AUTOMATION_KEY_B    B 端私钥，默认 ~/work/env/ume/automation-ume-b-ed25519.pem
  AUTOMATION_KEY_C    C 端私钥，默认 ~/work/env/ume/automation-ume-c-ed25519.pem
  V2_SSH              在 .80 上执行命令的包装脚本（接收一条远端命令，标准输入透传）
  V2_INTERNAL_BASE    默认 http://192.168.3.80:18180
  V2_PUBLIC_BASE      默认 https://nearshop.umeshop.ai
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import serialization

KEY_PATHS = {
    "OPS": os.environ.get("AUTOMATION_KEY_OPS", "~/work/env/ume/automation-ume-ed25519.pem"),
    "B": os.environ.get("AUTOMATION_KEY_B", "~/work/env/ume/automation-ume-b-ed25519.pem"),
    "C": os.environ.get("AUTOMATION_KEY_C", "~/work/env/ume/automation-ume-c-ed25519.pem"),
}
SSH = os.environ.get("V2_SSH", "")
INTERNAL = os.environ.get("V2_INTERNAL_BASE", "http://192.168.3.80:18180").rstrip("/")
PUBLIC = os.environ.get("V2_PUBLIC_BASE", "https://tw.neargo.ai").rstrip("/")  # 首选入口；nearshop.umeshop.ai 仍可用
# 三端的公网前缀：运营端页面与接口同在 /ops-shop-v2 下，B 端与 C 端在 /shop-api-v2 下
PREFIX = {"OPS": "/ops-shop-v2", "B": "/shop-api-v2", "C": "/shop-api-v2"}


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def public_key(realm: str) -> str:
    """这一端私钥对应的公钥（X.509 DER 的 base64），配到服务端用。公钥不是秘密。"""
    with open(os.path.expanduser(KEY_PATHS[realm]), "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode("ascii")


def _ticket(realm: str, sub: str, key_realm: str | None = None) -> str:
    """key_realm：用哪一端的钥签；默认就是票据自己的端。传别的端是为了验证「拿错钥会被拒」。"""
    with open(os.path.expanduser(KEY_PATHS[key_realm or realm]), "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    payload = json.dumps({"realm": realm, "sub": sub, "exp": int(time.time()) + 50,
                          "nonce": secrets.token_urlsafe(24)}, separators=(",", ":")).encode()
    body = _b64url(payload)
    return f"v1.{body}.{_b64url(key.sign(('v1.' + body).encode('ascii')))}"


def exchange(realm: str, sub: str, key_realm: str | None = None):
    """换会话，返回服务端的原始回答（令牌在 data.token 里）。"""
    if not SSH:
        raise SystemExit("要设置 V2_SSH：一个能在 192.168.3.80 上执行命令的脚本")
    request = json.dumps({"ticket": _ticket(realm, sub, key_realm)})
    remote = f"curl -s -m 20 -X POST -H 'Content-Type: application/json' --data-binary @- {INTERNAL}/common/auth/automation"
    done = subprocess.run([SSH, remote], input=request.encode(), capture_output=True, timeout=60)
    try:
        return json.loads(done.stdout.decode() or "null")
    except ValueError:
        return None


def login(realm: str, sub: str) -> str:
    """换一个会话令牌。失败抛异常，异常信息里不带票据。"""
    if not SSH:
        raise SystemExit("要设置 V2_SSH：一个能在 192.168.3.80 上执行命令的脚本")
    request = json.dumps({"ticket": _ticket(realm, sub)})
    remote = f"curl -s -m 20 -X POST -H 'Content-Type: application/json' --data-binary @- {INTERNAL}/common/auth/automation"
    done = subprocess.run([SSH, remote], input=request.encode(), capture_output=True, timeout=60)
    try:
        answer = json.loads(done.stdout.decode() or "null")
    except ValueError:
        raise SystemExit(f"换会话失败：{realm}:{sub} 返回的不是 JSON")
    token = ((answer or {}).get("data") or {}).get("token")
    if not token:
        raise SystemExit(f"换会话失败：{realm}:{sub} code={(answer or {}).get('code')}")
    return token


def call(realm: str, method: str, path: str, token: str | None = None, body=None, headers=None, timeout=30):
    """经公网入口调一次。返回（HTTP 状态码, 业务码或 None, 解出来的包体）。"""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(PUBLIC + PREFIX[realm] + path, data=data, method=method.upper())
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    except Exception as error:  # 超时、连接断开
        return 0, None, {"error": type(error).__name__}
    try:
        parsed = json.loads(raw or b"null")
    except ValueError:
        parsed = None
    code = parsed.get("code") if isinstance(parsed, dict) else None
    return status, code, parsed
