#!/usr/bin/env python3
"""ai-shop v2：B 端权限是否在网关层生效、是否正确（重点看支付入口）。

用法：
  V2_SSH=<在 .80 上执行命令的脚本> python scripts/automation/verify_v2_biz_perms.py [报告输出路径]

对测试门店里的两个人——店主（全部权限）与店员（预置的 CLERK 角色）——各打一遍网关索引里
所有不带路径变量的 B 端 GET：

  店员  按索引里的权限式与 CLERK 实际持有的权限码算出「该不该被拒」，与真实响应逐条比。
        被拒应当是 70006（角色不够），由网关在进入入口之前给出。
  店主  网关一定放行（他有全部权限）。放行之后仍被拒的，就是「权限不在网关判」的地方，逐条列出。

只发 GET，不改数据。测试账号与门店见 docs/handoff/ai-shop-v2-166-2026-11-19/。
"""
from __future__ import annotations

import collections
import re
import sys
import time

from v2_session import call, login
from verify_v2_gateway import probes, resources, satisfied

ADMIN = "ST-V2TEST-ADMIN"
OWNER, CLERK = "UUMV2TESTOWNER0000000001", "UUMV2TESTCLERK0000000001"
STORE = "ST261119BE70D8B9C9"
ROLE_DENIED, FORBIDDEN, PAY_FORBIDDEN, BAD_REQUEST = 70006, 10403, 70082, 10400
# 支付相关的地址：支付、退款、结算、储值、收银、流水、对账、权益、积分
PAY = re.compile(r"/(pay|payment|payments|refund|refunds|settle|stored-value|cash|cashier|statement|ledger|"
                 r"recon|entitlement|entitlements|points|finance|wallet|collection|collections)(/|$)")


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else None
    report, problems = [], []

    def say(line: str = "") -> None:
        print(line, flush=True)
        report.append(line)

    admin, owner, clerk = login("OPS", ADMIN), login("B", OWNER), login("B", CLERK)
    header = {"X-Store-No": STORE}
    _, _, roles = call("B", "GET", "/biz/roles", owner, None, header)
    clerk_perms = set(next(r["perms"] for r in roles["data"] if r["roleCode"] == "CLERK"))
    rows = probes(resources(admin, "biz"))
    say(f"# v2 B 端权限验证 {time.strftime('%Y-%m-%d %H:%M:%S')}")
    say(f"{len(rows)} 个 GET；店员（CLERK）持有 {len(clerk_perms)} 个权限码；门店 {STORE}")

    tally = collections.Counter()
    beyond = []      # 店主被网关放行、之后仍被拒
    pay_rows = collections.Counter()
    for row in rows:
        pattern, access, rule = row["pattern"], row["access"], row.get("ruleText")
        is_pay = bool(PAY.search(pattern))
        status_o, code_o, body_o = call("B", "GET", pattern, owner, None, header)
        status_c, code_c, _ = call("B", "GET", pattern, clerk, None, header)

        should_deny = access == "POINT" and bool(rule) and not satisfied(rule, clerk_perms)
        denied = code_c == ROLE_DENIED and status_c == 200
        kind = "支付" if is_pay else "其它"
        # HTTP 403 是排在网关判权之前的过滤器拒的（门店的方案与功能准入、打印模块的准入）：
        # 请求没有走到网关判权这一步，这一条看不出角色判得对不对，单独记数
        if status_c == 403:
            tally[f"{kind}：在网关判权之前就被准入挡住（HTTP 403）"] += 1
        elif access == "POINT":
            if should_deny and denied:
                tally[f"{kind}：店员该拒且被拒"] += 1
            elif not should_deny and not denied:
                tally[f"{kind}：店员该放且放行"] += 1
            else:
                tally[f"{kind}：不一致"] += 1
                problems.append(f"店员判权不一致 {pattern} 权限式={rule} 算出{'该拒' if should_deny else '该放'} "
                                f"实际 http={status_c} code={code_c}")
        else:
            tally[f"{kind}：网关不按权限码判（{access}）"] += 1
        if is_pay:
            pay_rows[access] += 1

        if status_o == 401 or code_o in (10401, 10402):
            problems.append(f"店主被当成没登录 {pattern} http={status_o} code={code_o}")
        elif code_o in (ROLE_DENIED, FORBIDDEN, PAY_FORBIDDEN) or status_o == 403:
            message = (body_o or {}).get("msg") if isinstance(body_o, dict) else None
            beyond.append((pattern, access, status_o, code_o, message, is_pay))
        elif status_o >= 500 or status_o == 0:
            tally[f"店主：http {status_o}"] += 1

    say("\n## 店员：网关的判定与真实响应")
    for key in sorted(tally):
        say(f"- {key}：{tally[key]}")
    say("\n## 支付入口在网关上的类别")
    for access, count in pay_rows.most_common():
        say(f"- {access}：{count}")
    say(f"\n## 店主（全部权限）仍被拒：{len(beyond)} 条 —— 这些拒绝都不是网关按角色判出来的")

    def layer(status, code):
        if status == 403 and code == ROLE_DENIED:
            return "门店方案与功能准入（过滤器，排在网关判权之前）"
        if status == 403:
            return "模块自己的准入（HTTP 403）"
        if code == PAY_FORBIDDEN:
            return "支付域的调用方矩阵与门店范围（业务代码里）"
        return "业务代码里的范围检查"

    groups = collections.defaultdict(list)
    for item in beyond:
        groups[layer(item[2], item[3])].append(item)
    for name, items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        pay_count = sum(1 for item in items if item[5])
        say(f"\n### {name}：{len(items)} 条（其中支付相关 {pay_count} 条）")
        for pattern, access, status, code, message, is_pay in sorted(items, key=lambda x: (not x[5], x[0])):
            say(f"- {'[支付] ' if is_pay else ''}{pattern}（网关类别 {access}）→ http {status} code {code}")
    say(f"\n## 结论：{'店员的判权全部一致' if not problems else f'{len(problems)} 处不一致'}")
    for line in problems:
        say(f"- {line}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
