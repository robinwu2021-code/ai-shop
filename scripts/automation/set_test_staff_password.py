#!/usr/bin/env python3
"""给【网关验证】测试运营账号设同一个登录密码（v2 浏览器页面测试用）。

用法（在你自己的终端里运行；密码只在这里输入，不回显，只把 bcrypt 哈希写进库）：
  V2_DB_SSH=<在跳板机上对指定主机执行命令的脚本> python scripts/automation/set_test_staff_password.py ST-V2UI-CS ST-V2UI-FINANCE ST-V2UI-GOODS

只允许改编号以 ST-V2UI- 或 ST-V2TEST- 开头的测试账号；顺带把「必须改密码」清掉，免得第一次登录就被拦去改密码。
需要 Python 的 bcrypt 包（应用用 Spring 的 BCryptPasswordEncoder，认 $2a$/$2b$/$2y$）。
"""
from __future__ import annotations

import getpass
import os
import re
import subprocess
import sys

import bcrypt

ALLOWED = re.compile(r"^ST-V2(UI|TEST)-[A-Z0-9-]{1,40}$")
DB_HOST = "192.168.3.61"
MYSQL = ("sudo /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock exec -i mysql9 "
         "sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -N ai_shop_ume'")


def main() -> int:
    staff = sys.argv[1:]
    if not staff or any(not ALLOWED.match(s) for s in staff):
        print("只接受 ST-V2UI-… / ST-V2TEST-… 测试账号编号", file=sys.stderr)
        return 2
    runner = os.environ.get("V2_DB_SSH")
    if not runner:
        print("缺 V2_DB_SSH（在跳板机上对指定主机执行命令的脚本）", file=sys.stderr)
        return 2
    password = getpass.getpass("新密码（至少 8 位，不回显）：")
    if len(password) < 8 or password != getpass.getpass("再输一遍："):
        print("两次不一致或少于 8 位，没有改", file=sys.stderr)
        return 1
    hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(12)).decode()
    del password
    numbers = ",".join(f"'{s}'" for s in staff)
    sql = (f"UPDATE sys_ops_staff SET password='{hashed}', must_change_password=0, updated_by='gateway-v2-verify', "
           f"updated_at=NOW() WHERE staff_no IN ({numbers});\n"
           f"SELECT staff_no, username, status, LEFT(password, 4) FROM sys_ops_staff WHERE staff_no IN ({numbers});\n")
    result = subprocess.run([runner, DB_HOST, MYSQL], input=sql, text=True, capture_output=True)
    lines = [line for line in result.stdout.splitlines() if line.startswith("ST-")]
    for line in lines:
        print("已设：", line)
    if result.returncode != 0 or len(lines) != len(staff):
        print("没有全部设上：", result.stderr.strip()[-300:], file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
