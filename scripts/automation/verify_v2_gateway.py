#!/usr/bin/env python3
"""ai-shop v2（网关版本）登录后的接口验证：运营端、B 端、C 端。

用法：
  V2_SSH=<在 .80 上执行命令的脚本> python scripts/automation/verify_v2_gateway.py [报告输出路径]

做什么（只发 GET，不改数据）：
  运营端  管理员与受限角色各打一遍网关索引里所有不带路径变量的 GET；
          受限角色「该不该被拒」按索引里的权限式与他实际持有的权限码算出来，再与入口的真实响应逐条比。
  B 端    店主用户打一遍 B 端的 GET：没有一条因为「没登录」被拒。
  C 端    买家打一遍 C 端的 GET：匿名时 401 的，带令牌后都不再是 401。
  跨端    三种令牌互相打另外两端，全部被拒。

测试账号见 docs/handoff/ai-shop-v2-166-2026-11-19/v2test-accounts.sql；令牌只在内存里。
"""
from __future__ import annotations

import collections
import json
import re
import sys
import time

from v2_session import call, exchange, login

ADMIN, SUPPORT = "ST-V2TEST-ADMIN", "ST-V2TEST-SUPPORT"
BUYER, OWNER = "UUMV2TESTBUYER0000000001", "UUMV2TESTOWNER0000000001"
UNAUTH, EXPIRED, FORBIDDEN, BAD_REQUEST = 10401, 10402, 10403, 10400
# 不打的地址：长连接、导出下载（体积大或会落文件），以及访问一次就记一条高危审计的 GET（如下载付款清单）——
# 验证脚本不该在生产审计里留下「导出」记录（2026-11-19 发现 /ops/payables/payout-list 留过两条）
SKIP = re.compile(r"/(events|stream|sse|export|exports|download)(/|$)|^/ops/payables/payout-list$")
# 必须带查询参数才有意义的地址：不带时业务按「看不到范围」拒，会被误报成管理员被拒。
# 打印运营按门店看打印机，三个参数都要；取测试门店（租户 MAIN）
REQUIRED_QUERY = {
    "/ops/print/health": "tenantNo=MAIN&entityNo=M261119TS0PN0XBK9&storeNo=ST261119BE70D8B9C9",
    "/ops/print/incidents": "tenantNo=MAIN&entityNo=M261119TS0PN0XBK9&storeNo=ST261119BE70D8B9C9",
}


def with_query(pattern: str) -> str:
    query = REQUIRED_QUERY.get(pattern)
    return pattern if query is None else f"{pattern}?{query}"


def resources(admin: str, end: str) -> list[dict]:
    rows, page = [], 1
    while True:
        _, code, body = call("OPS", "GET", f"/ops/api-resources?endCode={end}&page={page}&size=200", admin)
        if code != 0:
            raise SystemExit(f"读不到接口资源（{end}）：code={code}")
        data = body["data"]
        rows += data["records"]
        if len(rows) >= data["total"] or not data["records"]:
            return rows
        page += 1


def effective(row: dict) -> tuple[str, str | None]:
    """这一行网关实际按什么判：库里调整过的用调整后的，没调整过的用入口注解抄进来的。"""
    if row.get("adjustedAccess"):
        return row["adjustedAccess"], row.get("adjustedRule")
    return row["access"], row.get("ruleText")


def probes(rows: list[dict]) -> list[dict]:
    return sorted((r for r in rows if r["method"] == "GET" and "{" not in r["pattern"] and "*" not in r["pattern"]
                   and not SKIP.search(r["pattern"]) and r["status"] == "ENABLED" and not r["stale"]),
                  key=lambda r: r["pattern"])


def holds(perms: set[str], code: str) -> bool:
    if "*" in perms or code in perms:
        return True
    return any(p.endswith(":*") and code.startswith(p[:-1]) for p in perms)


def satisfied(rule: str, perms: set[str]) -> bool:
    """按「接口资源」页显示的权限式算：单个码，或带括号的「且 / 或」。"""
    tokens = re.findall(r"\(|\)|且|或|[^\s()且或]+", rule)
    position = 0

    def term() -> bool:
        nonlocal position
        token = tokens[position]
        position += 1
        if token != "(":
            return holds(perms, token)
        values, joiner = [term()], None
        while tokens[position] != ")":
            joiner = tokens[position]
            position += 1
            values.append(term())
        position += 1
        return all(values) if joiner == "且" else any(values)

    return term()


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else None
    report: list[str] = []
    problems: list[str] = []

    def say(line: str = "") -> None:
        print(line, flush=True)
        report.append(line)

    admin, support = login("OPS", ADMIN), login("OPS", SUPPORT)
    buyer, owner = login("C", BUYER), login("B", OWNER)
    say(f"# v2 接口验证 {time.strftime('%Y-%m-%d %H:%M:%S')}")
    say("四个测试账号都换到了会话：运营管理员、运营客服、买家、店主用户。")

    # ---- 运营端 ----
    _, _, me = call("OPS", "GET", "/ops/auth/me", support)
    perms = set(me["data"]["perms"])
    ops_rows = probes(resources(admin, "ops") + resources(admin, "pay-ops"))
    say(f"\n## 运营端：{len(ops_rows)} 个 GET；受限角色持有 {len(perms)} 个权限码")
    tally = collections.Counter()
    expect_denied = actual_denied = 0
    for row in ops_rows:
        path = row["pattern"][len("/ops"):] if row["pattern"].startswith("/ops/") else None
        # 支付运营面在 /pay-ops 下，公网入口只放 /ops-shop-v2/ops/**，不在这次的范围里
        if path is None:
            tally["支付运营面（公网入口不放行，未打）"] += 1
            continue
        status_a, code_a, _ = call("OPS", "GET", with_query("/ops" + path), admin)
        status_s, code_s, _ = call("OPS", "GET", with_query("/ops" + path), support)
        if status_a == 401 or code_a in (UNAUTH, EXPIRED, FORBIDDEN):
            problems.append(f"运营端 管理员被拒 {row['pattern']} http={status_a} code={code_a}")
        if status_a >= 500 or status_a == 0:
            tally[f"管理员 http {status_a}"] += 1
            problems.append(f"运营端 管理员 {status_a} {row['pattern']} code={code_a}")
        access, rule = effective(row)
        should_deny = access == "POINT" and bool(rule) and not satisfied(rule, perms)
        denied = code_s == FORBIDDEN
        # 缺必填参数时入口先报参数错（10400），还没走到方法上的判权：这一条在入口这边看不出该不该拒，单独记数。
        # 网关提前判权排在读参数之前，开到拦截档后这类请求会直接得到「没有权限」
        undecided = code_s == BAD_REQUEST and should_deny
        expect_denied += should_deny
        actual_denied += denied
        if undecided:
            tally["受限角色：该拒，但入口先报了参数错（未走到判权）"] += 1
            continue
        if access == "POINT" and should_deny != denied:
            problems.append(f"运营端 判权不一致 {row['pattern']} 权限式={rule} "
                            f"网关算出{'该拒' if should_deny else '该放'} 入口实际{'拒了' if denied else '放了'}(code={code_s})")
        if status_s == 401 or code_s in (UNAUTH, EXPIRED):
            problems.append(f"运营端 受限角色被当成没登录 {row['pattern']} http={status_s} code={code_s}")
        tally[f"受限角色：{'被拒' if denied else '放行'}（{access}）"] += 1
    for key in sorted(tally):
        say(f"- {key}：{tally[key]}")
    say(f"- 按权限式算出该拒 {expect_denied} 条，入口实际拒了 {actual_denied} 条")

    # ---- C 端 ----
    mp_rows = probes(resources(admin, "mp"))
    say(f"\n## C 端：{len(mp_rows)} 个 GET")
    anon_401 = with_token_401 = 0
    codes = collections.Counter()
    for row in mp_rows:
        status_0, _, _ = call("C", "GET", row["pattern"])
        status_1, code_1, _ = call("C", "GET", row["pattern"], buyer)
        anon_401 += status_0 == 401
        codes[f"http {status_1}"] += 1
        if status_1 == 401:
            with_token_401 += 1
            problems.append(f"C 端 带令牌仍 401 {row['pattern']} code={code_1}")
        if status_1 >= 500 or status_1 == 0:
            problems.append(f"C 端 {status_1} {row['pattern']} code={code_1}")
    say(f"- 匿名时 401 的 {anon_401} 条；带买家令牌后仍 401 的 {with_token_401} 条")
    say("- 带令牌的响应：" + "、".join(f"{k} {v}" for k, v in sorted(codes.items())))

    # ---- B 端 ----
    biz_rows = probes(resources(admin, "biz"))
    say(f"\n## B 端：{len(biz_rows)} 个 GET（店主用户，还没有门店）")
    codes = collections.Counter()
    for row in biz_rows:
        status, code, _ = call("B", "GET", row["pattern"], owner)
        codes[f"http {status} code {code}"] += 1
        if status == 401 or code in (UNAUTH, EXPIRED):
            problems.append(f"B 端 带令牌被当成没登录 {row['pattern']} http={status} code={code}")
        if status >= 500 or status == 0:
            problems.append(f"B 端 {status} {row['pattern']} code={code}")
    for key, count in codes.most_common(12):
        say(f"- {key}：{count}")

    # ---- 钥按端分 ----
    say("\n## 钥按端分：拿一端的钥签另一端的票据")
    wrong = [("运营端的钥", "OPS", "B", OWNER), ("运营端的钥", "OPS", "C", BUYER),
             ("B 端的钥", "B", "OPS", ADMIN), ("B 端的钥", "B", "C", BUYER),
             ("C 端的钥", "C", "OPS", ADMIN), ("C 端的钥", "C", "B", OWNER)]
    for name, key_realm, realm, subject in wrong:
        answer = exchange(realm, subject, key_realm) or {}
        token = (answer.get("data") or {}).get("token")
        say(f"- {name} 签 {realm} 端的票据：{'拿到了令牌' if token else '被拒'}（code {answer.get('code')}）")
        if token:
            problems.append(f"钥按端分 {name} 签出了 {realm} 端的会话")

    # ---- 跨端 ----
    say("\n## 跨端：拿一端的令牌打另外两端")
    crossing = [("买家令牌", buyer, "OPS", "/ops/auth/me"), ("买家令牌", buyer, "B", "/biz/goods"),
                ("店主令牌", owner, "OPS", "/ops/auth/me"), ("店主令牌", owner, "C", "/mp/order/list"),
                ("运营令牌", admin, "B", "/biz/goods"), ("运营令牌", admin, "C", "/mp/order/list")]
    for name, token, realm, path in crossing:
        status, code, _ = call(realm, "GET", path, token)
        rejected = status == 401 or code in (UNAUTH, EXPIRED)
        say(f"- {name} → {path}：http {status} code {code} {'被拒' if rejected else '放行了'}")
        if not rejected:
            problems.append(f"跨端 {name} 打 {path} 没有被拒 http={status} code={code}")

    say(f"\n## 结论：{'没有发现问题' if not problems else f'{len(problems)} 处要看'}")
    for line in problems:
        say(f"- {line}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
