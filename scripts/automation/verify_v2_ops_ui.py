#!/usr/bin/env python3
"""v2 运营端页面按角色验证：菜单展示 → 逐页打开 → 越权页面。

用法（生产 v2，请你本人在终端运行；脚本用自动化票据为每个测试用户换会话，以他的身份打开页面）：
  V2_SSH=<在 .80 上执行命令的脚本> python scripts/automation/verify_v2_ops_ui.py [报告输出路径]

本机自测（本机后端 + 本机前端 + 种子账号，不碰生产）：
  OPS_UI_LOCAL=1 OPS_UI_BASE=http://localhost:3100 OPS_UI_API=http://localhost:8082 python scripts/automation/verify_v2_ops_ui.py

每个用户做三件事：
  1. 菜单：各页侧栏里出现过的页面链接，不应包含后端 /ops/menu 没给他的页面（侧栏只列页面级入口，同页的标签不在侧栏里）；
  2. 逐页：他菜单里的每一页都打开，记下失败的请求（4xx/5xx）、页面上的报错字样、是否被踢回登录页；
  3. 越权：再挑最多 10 个他菜单里没有、超管有的页面直接打开。
  网关判得对不对，按「接口资源」里生效的规则逐个接口判：该拒却返回成功的是漏判；该放却被拒的是误拒。
  页面调了他没有权限的接口而被拒（网关判得对），单列为「页面依赖了他没有的权限」：那是页面与角色配置对不上，不是网关的错。

只读：只打开页面，不点任何按钮、不提交表单。需要 playwright（pip install playwright && python -m playwright install chromium）。
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

from verify_v2_gateway import effective, satisfied

LOCAL = os.environ.get("OPS_UI_LOCAL") == "1"
BASE = os.environ.get("OPS_UI_BASE", "https://tw.neargo.ai/ops-shop-v2").rstrip("/")
API = os.environ.get("OPS_UI_API", "http://localhost:8082").rstrip("/")
WAIT = float(os.environ.get("OPS_UI_WAIT", "3.5"))

# 生产：三个浏览器测试用户（docs/handoff/ai-shop-v2-166-2026-11-19/v2test-ui-users.sql）；本机：DevSeeder 的种子账号
USERS = [("ST-V2UI-CS", "客服"), ("ST-V2UI-FINANCE", "财务"), ("ST-V2UI-GOODS", "商品运营")]
LOCAL_USERS = [("support", "客服"), ("finance", "财务"), ("goods", "商品运营")]
ADMIN = "ST-V2TEST-ADMIN"
ERROR_WORDS = ["加载失败", "网络异常", "出错了", "Application error", "系统开小差", "请求参数有误", "打不开："]
DENIED_WORDS = ["无权限", "没有权限", "没有操作权限", "无权访问"]
OK_CODES = {0, 10404}
IGNORED = ("/ops/stream",)


def local_call(method: str, path: str, token: str | None = None, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(API + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read() or b"null")


def session(subject: str) -> tuple[str, dict, list[dict]]:
    """换会话；返回（令牌, 当前用户, 菜单功能点）。"""
    if LOCAL:
        token = local_call("POST", "/ops/auth/login", None, {"username": subject, "password": subject + "123"})["data"]["token"]
        me = local_call("GET", "/ops/auth/me", token)["data"]
        menu = local_call("GET", "/ops/menu", token)["data"]
    else:
        from v2_session import call, login
        token = login("OPS", subject)
        me = call("OPS", "GET", "/ops/auth/me", token)[2]["data"]
        menu = call("OPS", "GET", "/ops/menu", token)[2]["data"]
    return token, me, menu


def index(token: str) -> list[tuple[re.Pattern, str, str | None, int]]:
    """运营端接口资源：（地址正则, 生效类别, 生效权限式, 模式长度）。"""
    rows, page = [], 1
    while True:
        path = f"/ops/api-resources?endCode=ops&page={page}&size=200"
        if LOCAL:
            body = local_call("GET", path, token)
        else:
            from v2_session import call
            body = call("OPS", "GET", path, token)[2]
        data = body["data"]
        rows += data["records"]
        if len(rows) >= data["total"] or not data["records"]:
            break
        page += 1
    out = []
    for row in rows:
        if row["method"] not in ("GET", "*") or row["status"] != "ENABLED":
            continue
        access, rule = effective(row)
        regex = re.compile("^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(row["pattern"])) + "$")
        out.append((regex, access, rule, len(row["pattern"])))
    out.sort(key=lambda item: -item[3])
    return out


def rule_for(resources, path: str):
    for regex, access, rule, _ in resources:
        if regex.match(path):
            return access, rule
    return None, None


def page_url(href: str) -> str:
    path, _, query = href.partition("?")
    return BASE + path.rstrip("/") + "/" + ("?" + query if query else "")


def points(menu: list[dict]) -> list[dict]:
    seen, out = set(), []
    for function in menu:
        for point in function["points"]:
            if point.get("href") and point["href"] not in seen and point.get("uiReady", True) \
                    and point.get("backendStatus") != "NOT_IMPLEMENTED":
                seen.add(point["href"])
                out.append({"module": function["name"], "name": point["name"], "href": point["href"]})
    return out


def normalize(href: str) -> str:
    path, _, query = href.partition("?")
    path = path.replace(BASE, "").replace(re.sub(r"^https?://[^/]+", "", BASE), "", 1) if path.startswith(("http", "/")) else path
    return (path.rstrip("/") or "/") + ("?" + query if query else "")


def visit(page, href: str, log: list, links: set) -> dict:
    """打开一页，收集这一页的接口响应、页面文字，以及侧栏里出现的页面链接。"""
    log.clear()
    try:
        page.goto(page_url(href), wait_until="domcontentloaded", timeout=60000)
    except Exception as error:  # noqa: BLE001 —— 一页打不开只记下来，不中断整次验证
        return {"href": href, "here": href, "text": f"打不开：{type(error).__name__}", "responses": list(log)}
    time.sleep(WAIT)
    try:
        text = page.locator("main").first.inner_text(timeout=2000)
    except Exception:  # noqa: BLE001 —— 没有 main 就读整页
        text = page.inner_text("body")
    for link in page.eval_on_selector_all("aside a[href]", "els => els.map(e => e.getAttribute('href'))"):
        links.add(normalize(link))
    here = page.url.replace(BASE, "")
    return {"href": href, "here": here, "text": text, "responses": list(log)}


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else None
    report: list[str] = []

    def say(line: str = "") -> None:
        print(line, flush=True)
        report.append(line)

    users = LOCAL_USERS if LOCAL else USERS
    admin_subject = "admin" if LOCAL else ADMIN
    admin_token, _, admin_menu = session(admin_subject)
    admin_points = points(admin_menu)
    resources = index(admin_token)
    say(f"# v2 运营端页面按角色验证 {time.strftime('%Y-%m-%d %H:%M:%S')}")
    say(f"入口 {BASE}；超管可见 {len(admin_points)} 个页面")
    problems: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        for subject, label in users:
            token, me, menu = session(subject)
            mine = points(menu)
            state = {"state": {"username": me.get("username", subject), "role": "ANALYST", "tenantNo": "MAIN",
                               "merchantNo": "", "communityNo": "", "pickupNo": "", "token": token,
                               "perms": me.get("perms", [])}, "version": 0}
            context = browser.new_context(viewport={"width": 1400, "height": 900}, locale="zh-CN")
            context.add_init_script(f"try {{ localStorage.setItem('shop-ops-auth', {json.dumps(json.dumps(state))}); }} catch (e) {{}}")
            page = context.new_page()
            log: list = []

            def on_response(response, log=log):
                url = response.url
                if "/ops/" not in url or any(x in url for x in IGNORED):
                    return
                code = None
                try:
                    if "json" in (response.headers.get("content-type") or ""):
                        body = response.json()
                        code = body.get("code") if isinstance(body, dict) else None
                except Exception:  # noqa: BLE001 —— 读不了包体就只记状态码
                    pass
                path = url.split("/ops/", 1)[1].split("?")[0]
                log.append((response.status, code, "/ops/" + path, response.request.method))

            page.on("response", on_response)

            say(f"\n## {label}（{subject}）：持有 {len(me.get('perms', []))} 个权限码，菜单 {len(mine)} 个页面")
            page.goto(BASE + "/", wait_until="domcontentloaded", timeout=90000)
            time.sleep(WAIT)
            if "/login" in page.url:
                problems.append(f"{label}：带着会话打开首页被送回登录页")
                say("- 首页：被送回登录页，后面不测了")
                context.close()
                continue
            perms = set(me.get("perms", []))
            links: set = set()
            gateway_wrong: list[str] = []
            needs_more: set = set()

            def judge_calls(result, where):
                for status, code, path, method in result["responses"]:
                    if method != "GET":
                        continue
                    access, rule = rule_for(resources, path)
                    should_deny = access == "POINT" and bool(rule) and not satisfied(rule, perms)
                    if status < 400 and code == 0 and should_deny:
                        gateway_wrong.append(f"漏判：{where} 调 {path} 拿到了数据（规则 {rule}）")
                    elif code == 10403 and not should_deny and access == "POINT":
                        gateway_wrong.append(f"误拒：{where} 调 {path} 被拒（规则 {rule}，他持有）")
                    elif code == 10403 and should_deny:
                        needs_more.add(f"{where} 调 {path} 要 {rule}")

            # 2. 逐页
            bad_pages = 0
            for point in mine:
                result = visit(page, point["href"], log, links)
                where = f"{point['module']} / {point['name']}"
                judge_calls(result, where)
                issues = []
                if "/login" in result["here"]:
                    issues.append("被送回登录页")
                issues += sorted({f"{status} {path}" for status, code, path, _ in result["responses"] if status >= 400})
                issues += sorted({f"code={code} {path}" for status, code, path, _ in result["responses"]
                                  if status < 400 and code is not None and code not in OK_CODES and code != 10403})
                # 发票页的说明文字里写着「那不是加载失败」，先去掉再找报错字样
                text = result["text"].replace("那不是加载失败", "")
                issues += [w for w in ERROR_WORDS if w in text]
                if issues:
                    bad_pages += 1
                    problems.append(f"{label}：{where}（{point['href']}）→ {'；'.join(issues)}")
            say(f"- 逐页打开：{len(mine)} 页，加载出错的 {bad_pages} 页")

            # 1. 菜单：侧栏里出现过的页面链接 vs 后端给他的页面
            expected = {normalize(p["href"]) for p in mine}
            all_pages = {normalize(p["href"]) for p in admin_points}
            shown = links & all_pages
            extra = sorted(shown - expected)
            # 侧栏只列每个模块里页面级的入口，同一页面下的标签不在侧栏里，所以「侧栏少了」不算问题；要紧的是没露出不该看的
            say(f"- 菜单：后端给 {len(expected)} 个页面；侧栏里出现的页面入口 {len(shown)} 个，"
                + (f"露出了他不该看到的 {extra[:8]}" if extra else "没有露出他不该看到的页面"))
            if extra:
                problems.append(f"{label}：侧栏露出了他菜单里没有的页面 {extra[:8]}")

            # 3. 越权
            own = {p["href"] for p in mine}
            others = [p for p in admin_points if p["href"] not in own][:10]
            blocked = 0
            for point in others:
                result = visit(page, point["href"], log, set())
                judge_calls(result, f"越权页 {point['module']} / {point['name']}")
                left = not result["here"].startswith(point["href"].split("?")[0].rstrip("/"))
                if left or any(w in result["text"] for w in DENIED_WORDS):
                    blocked += 1
            say(f"- 越权：直接打开 {len(others)} 个不在他菜单里的页面，页面层面拦住（跳走或提示无权限）{blocked} 个；接口由网关逐个判，见下")
            say(f"- 网关判权：漏判 {sum(1 for x in gateway_wrong if x.startswith('漏判'))} 处，误拒 {sum(1 for x in gateway_wrong if x.startswith('误拒'))} 处")
            problems += [f"{label}：{x}" for x in gateway_wrong]
            if needs_more:
                say(f"- 页面依赖了他没有的权限（网关拒得对，页面会缺一块）{len(needs_more)} 处：")
                for line in sorted(needs_more)[:15]:
                    say(f"  - {line}")
            context.close()
        browser.close()

    say(f"\n## 结论：{'全部通过' if not problems else f'{len(problems)} 处要看'}")
    for line in problems:
        say(f"- {line}")
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
