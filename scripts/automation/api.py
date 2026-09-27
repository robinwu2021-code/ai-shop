#!/usr/bin/env python3
"""用密钥票据换会话，再调接口（ADR-027）。给自动化用：运营审核、店主建店建商品、清理测试数据。

  python3 scripts/automation/api.py OPS ST-XXX GET  /ops/auth/me
  python3 scripts/automation/api.py B   U2026… GET  /biz/merchant/profile
  python3 scripts/automation/api.py B   U2026… POST /biz/store/create '{"name":"…","address":"…"}'
  python3 scripts/automation/api.py B   U2026… POST /biz/goods @goods.json     # @文件 = 从文件读 body

环境变量：AUTOMATION_BASE_URL（默认 https://www.hxmall.top）、AUTOMATION_KEY（私钥路径）。
输出只有接口的响应体；**会话令牌不打印**。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from _ticket import request, session  # noqa: E402

if len(sys.argv) < 5:
    print(__doc__, file=sys.stderr)
    sys.exit(2)
realm, sub, method, path = sys.argv[1:5]
body = None
if len(sys.argv) > 5:
    arg = sys.argv[5]
    body = json.load(open(arg[1:], encoding="utf8")) if arg.startswith("@") else json.loads(arg)

status, resp = request(method.upper(), path, session(realm, sub), body)
print(json.dumps(resp, ensure_ascii=False, indent=2))
code = (resp or {}).get("code") if isinstance(resp, dict) else None
sys.exit(0 if status < 400 and code in (0, None) else 1)
