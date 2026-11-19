#!/usr/bin/env python3
"""ai-shop v2：运营端逐角色验证网关判权。

用法：
  V2_SSH=<在 .80 上执行命令的脚本> python scripts/automation/verify_v2_ops_roles.py [报告输出路径]

每个运营角色一个【网关验证】测试账号（ST-V2TEST-<角色>，见 docs/handoff/ai-shop-v2-166-2026-11-19/v2test-ops-roles.sql），
用自动化票据登录后，把运营端索引里所有不带路径变量的 GET 都打一遍：

  按「接口资源」里的权限式与这个角色实际持有的权限码，算出每条该拒还是该放，与真实响应逐条比。
  被拒时再分是谁拒的：网关（提前判权，回 10403 Permission denied）还是入口之后的业务代码。

只发 GET，不改数据；每个角色 4 个并发。
"""
from __future__ import annotations

import collections
import concurrent.futures
import sys
import time

from v2_session import call, login
from verify_v2_gateway import probes, resources, satisfied, with_query

ADMIN = "ST-V2TEST-ADMIN"
ROLES = ["SUPPORT", "BD", "GOODS_OPS", "CAMPAIGN_OPS", "COMMUNITY_OPS", "AUDITOR", "FINANCE", "RISK", "ANALYST",
         "TECH_OPS", "ELEC_ADMIN", "STORE_APPLY_REVIEW", "NP_IT_CONFIG", "NP_IT_REVIEW"]
FORBIDDEN = 10403
GATEWAY_MESSAGE = "Permission denied"


def account(role: str) -> str:
    return "ST-V2TEST-" + role.replace("_", "-")


def judge(token: str, perms: set[str], row: dict) -> tuple[str, str]:
    """返回（类别, 说明）。类别：一致 / 不一致 / 不按码判 / 其它。"""
    status, code, body = call("OPS", "GET", with_query(row["pattern"]), token)
    message = body.get("msg") if isinstance(body, dict) else None
    denied = code == FORBIDDEN or status == 403
    by_gateway = denied and message == GATEWAY_MESSAGE
    if row["access"] != "POINT" or not row.get("ruleText"):
        if denied:
            return "不按码判却被拒", f"{row['pattern']} http={status} code={code} msg={message}"
        return "不按码判", ""
    should_deny = not satisfied(row["ruleText"], perms)
    if should_deny and by_gateway:
        return "该拒且被网关拒", ""
    if should_deny and denied:
        return "该拒、被业务拒（网关放过了）", f"{row['pattern']} 权限式={row['ruleText']} msg={message}"
    if should_deny:
        return "该拒却放行", f"{row['pattern']} 权限式={row['ruleText']} http={status} code={code}"
    if by_gateway:
        return "该放却被网关拒", f"{row['pattern']} 权限式={row['ruleText']}"
    if denied:
        return "该放、网关放过、业务拒", f"{row['pattern']} 权限式={row['ruleText']} msg={message}"
    if status >= 500 or status == 0:
        return f"该放、http {status}", f"{row['pattern']} code={code}"
    return "该放且放行", ""


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else None
    report: list[str] = []

    def say(line: str = "") -> None:
        print(line, flush=True)
        report.append(line)

    admin = login("OPS", ADMIN)
    rows = [r for r in probes(resources(admin, "ops")) if r["pattern"].startswith("/ops/")]
    say(f"# v2 运营端逐角色验证 {time.strftime('%Y-%m-%d %H:%M:%S')}")
    say(f"运营端 {len(rows)} 个不带路径变量的 GET；{len(ROLES)} 个角色各一个测试账号")
    say("\n| 角色 | 持有码数 | 该拒且被网关拒 | 该放且放行 | 不按码判 | 不一致 |")
    say("|---|---:|---:|---:|---:|---:|")
    problems: list[str] = []
    notes: list[str] = []
    for role in ROLES:
        token = login("OPS", account(role))
        _, _, me = call("OPS", "GET", "/ops/auth/me", token)
        perms = set(me["data"]["perms"])
        tally = collections.Counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for kind, detail in pool.map(lambda row: judge(token, perms, row), rows):
                tally[kind] += 1
                if kind in ("该拒却放行", "该放却被网关拒", "不按码判却被拒"):
                    problems.append(f"{role} {kind}：{detail}")
                elif detail:
                    notes.append(f"{role} {kind}：{detail}")
        bad = tally["该拒却放行"] + tally["该放却被网关拒"] + tally["不按码判却被拒"]
        say(f"| {role} | {len(perms)} | {tally['该拒且被网关拒']} | {tally['该放且放行']} | {tally['不按码判']} | {bad} |")
        others = {k: v for k, v in tally.items() if k not in ("该拒且被网关拒", "该放且放行", "不按码判")}
        if others:
            say(f"|  ↳ 其它 | | {others} | | | |")
    say(f"\n## 结论：{'每个角色的网关判权都与权限式一致' if not problems else f'{len(problems)} 处不一致'}")
    for line in problems:
        say(f"- {line}")
    if notes:
        say("\n## 网关放行之后由业务代码拒、或其它情况（不算网关判错，逐条列出）")
        for line in notes:
            say(f"- {line}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
