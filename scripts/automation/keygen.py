#!/usr/bin/env python3
"""生成密钥票据用的 Ed25519 密钥对（ADR-027）。

  python3 scripts/automation/keygen.py          # 私钥写到 ~/work/env/ai-shop/automation-ed25519.pem（0600）
  python3 scripts/automation/keygen.py --force  # 换一对（旧私钥立刻作废：线上公钥也要换）

只打印**公钥**（X.509 DER 的 base64），把它配到线上的 SHOP_AUTOMATION_PUBLIC_KEY。
私钥不打印、不进仓库。
"""
import base64
import os
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, os.path.dirname(__file__))
from _ticket import KEY_PATH  # noqa: E402

if os.path.exists(KEY_PATH) and "--force" not in sys.argv:
    key = serialization.load_pem_private_key(open(KEY_PATH, "rb").read(), password=None)
    print(f"私钥已存在：{KEY_PATH}（要换一对加 --force）", file=sys.stderr)
else:
    key = Ed25519PrivateKey.generate()
    os.makedirs(os.path.dirname(KEY_PATH), mode=0o700, exist_ok=True)
    fd = os.open(KEY_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    print(f"已生成私钥：{KEY_PATH}（0600）", file=sys.stderr)

pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
print(base64.b64encode(pub).decode("ascii"))
