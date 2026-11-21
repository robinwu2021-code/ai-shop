#!/usr/bin/env python3
"""支付中心对接完整性：near-pay 支付运营接口 → 运营端接口封装 → 页面调用。

用法：python3 scripts/pay-center-wiring.py <near-pay 仓库根目录> [--tsv 输出文件]
  near-pay 侧读 backend/pay/pay-portal/.../api/ops/v2/*.java 的 @*Mapping；
  运营端读 ops-web/app/payment-center/_api/api.ts 的封装方法，再在 payment-center 页面里找调用。
  状态：UI = 页面能用；API_ONLY = 只封装、页面没用；MISSING = 运营端没接；
        NOT_OPEN = near-pay 未开放；EXCLUDED = 不在运营端提供；DEFERRED = 渠道专属暂缓。
"""
import collections, glob, json, os, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "ops-web/app/payment-center/_api/api.ts"
PAGES = ROOT / "ops-web/app/payment-center"
DYNAMIC = {"transfers.approve", "transfers.reject", "transfers.execute"}  # api.transfers[kind](...)
# 接口层内部调用（写命令超时后按幂等号取回执，TDD-支付中心运营页面 §2.15）：随所属写命令一起算「已接」
INTERNAL = {"merchants.creationByRequest", "merchants.assistedOperation", "onboardings.draftCommand", "claims.command"}
INTERNAL_PATHS = {("GET", "/merchant-creations/by-request/{}"), ("GET", "/merchants/{}/assisted-profile/operations/{}"), ("GET", "/onboardings/{}/draft/commands/{}"),
                  ("GET", "/channel-account-claims/{}/commands/{}")}
# near-pay 未开放（固定回 409 CAPABILITY_NOT_READY，da3ced2a4 核对）
NOT_OPEN = {("POST", "/merchants/{}/close"), ("GET", "/approval-tasks/{}/candidates"), ("POST", "/approval-flows"), ("PUT", "/approval-flows/{}"),
            ("POST", "/approval-flows/{}/copy"), ("POST", "/approval-flows/{}/publish"), ("POST", "/approval-flows/{}/disable"),
            ("POST", "/reconciliation-differences/{}/match"), ("POST", "/reconciliation-differences/{}/adjust-requests")}
# 功能开关在线上没开（near-pay shop.pay.channel-bill.enabled 未配置，2026-11-21 核对 166）：整个控制器不加载
NOT_OPEN_CONTROLLERS = {"OpsChannelBillController"}
# 用户确认不在运营端提供：near-pay 权限映射（权限由 ai-shop 决定）、关闭渠道账户（near-pay 不开放）、等价能力已有
EXCLUDED = {("GET", "/permission-mappings"), ("GET", "/permission-mappings/{}"), ("GET", "/permission-resources"), ("POST", "/permission-mappings"),
            ("POST", "/permission-mappings/{}/enable"), ("POST", "/permission-mappings/{}/revoke"), ("PUT", "/permission-mappings/{}"),
            ("GET", "/me/access"), ("POST", "/channel-accounts/{}/close"), ("POST", "/approval-tasks/{}/review"),
            # 已有等价能力：账户恢复走审批执行（approvals.execute）；认领附件随认领详情返回
            ("POST", "/channel-accounts/{}/resume"), ("GET", "/channel-account-claims/{}/files"),
            # 试运行资质统一走 /newebpay-trial|/linepay-trial（带准备信息、支持 LINE Pay）；通用准备接口等价
            ("POST", "/channel-accounts/{}/qualifications"),
            # 测试渠道「从服务器环境变量登记」读服务器沙箱密钥，属运维一次性动作，不放运营端（《支付中心-渠道专属能力运营需求》§1）
            ("POST", "/channel-accounts/test-channel-registrations")}
# 暂缓：易特付虚拟账户（线上 yitepay.va.enabled 未配置、接口不加载；缺列表接口；产品方案评审稿）——《支付中心-渠道专属能力运营需求》§4
DEFERRED_CONTROLLERS = {"OpsYitePayVaController"}


def nearpay_endpoints(np_root):
    d = os.path.join(np_root, "backend/pay/pay-portal/src/main/java/ai/neargo/shop/pay/api/ops/v2")
    out = set()
    for f in sorted(glob.glob(d + "/*.java")):
        s = open(f, encoding="utf-8").read()
        head = s.split("public class")[0] + "public class"
        m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?\{?([^)\n]*)\)(?=[\s\S]*?public (?:final )?class)', head)
        bases = [""]
        if m:
            bases = [b.strip() for b in re.findall(r'(OpsV2\.PREFIX\s*\+\s*"[^"]*"|"[^"]*"|OpsV2\.PREFIX)', m.group(1))]
        for mm in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?', s):
            paths = re.findall(r'"([^"]*)"', mm.group(2) or '""') or [""]
            for b in bases:
                bp = "".join(re.findall(r'"([^"]*)"', b.replace("OpsV2.PREFIX", '""')))
                for p in paths:
                    if p.startswith("multipart/") or p.startswith("application/"):
                        continue
                    path = re.sub(r"\{[^}]+\}", "{}", bp + p)
                    path = path[len("/pay-ops/v2"):] if path.startswith("/pay-ops/v2") else path
                    out.add((mm.group(1).upper(), path, os.path.basename(f)[:-5]))
    return sorted(out)


def wrappers():
    lines = API.read_text(encoding="utf-8").split("\n")
    methods, port = [], None
    for idx, l in enumerate(lines):
        am = re.match(r"^      ([a-zA-Z]+): async \(", l)
        if am and port and "c." not in l:  # 多行 async 方法：在后几行里找第一个请求
            for nxt in lines[idx + 1: idx + 5]:
                cm = re.search(r'c\.(get|page|blob|upload|command)(?:<.*?>)?\(\s*([`"])(.+?)\2(.*)$', nxt)
                if cm:
                    p = re.sub(r"\$\{[^}]*\}", "{}", cm.group(3)).split("?")[0]
                    mt = {"get": "GET", "page": "GET", "blob": "GET", "upload": "POST", "command": "POST"}[cm.group(1)]
                    r = re.search(r'method:\s*"(PUT|DELETE|PATCH)"', cm.group(4))
                    methods.append((port, am.group(1), r.group(1) if r else mt, p)); break
            continue
        m = re.match(r"^    ([a-zA-Z]+): \{\s*$", l)
        if m:
            port = m.group(1); continue
        top = re.match(r'^    ([a-zA-Z]+): crud<[^>]*>\("([^"]+)"\)', l)
        if top:
            port, b = top.group(1), top.group(2)
            methods += [(port, n, mt, pp) for n, mt, pp in
                        [("list", "GET", b), ("detail", "GET", b + "/{}"), ("create", "POST", b), ("update", "PUT", b + "/{}")]]
            continue
        sp = re.match(r'^      \.\.\.crud<[^>]*>\("([^"]+)"\)', l)
        if sp and port:
            b = sp.group(1)
            methods += [(port, n, mt, pp) for n, mt, pp in
                        [("list", "GET", b), ("detail", "GET", b + "/{}"), ("create", "POST", b), ("update", "PUT", b + "/{}")]]
            continue
        dc = re.match(r'^      ([a-zA-Z]+): \(no, body\) => draftCmd\(no, "([^"]*)", [^)]*?(?:, "(PUT)")?\),', l)
        if dc and port:  # 进件草稿写命令（带超时恢复的 draftCmd 包装）
            methods.append((port, dc.group(1), dc.group(3) or "POST", "/onboardings/{}/draft" + dc.group(2)))
            continue
        mm = re.match(r'^      ([a-zA-Z]+): .*?(c\.(get|page|blob|upload|command)|detailOf\(c,)\s*(?:<[^>(]*>)?\(?\s*([`"])(.+?)\4(.*)$', l)
        if mm and port:
            kind = mm.group(3) or "detail"
            p = re.sub(r"\$\{[^}]*\}", "{}", mm.group(5)).split("?")[0]
            mt = {"get": "GET", "page": "GET", "blob": "GET", "detail": "GET", "upload": "POST", "command": "POST"}[kind]
            r = re.search(r'method:\s*"(PUT|DELETE|PATCH)"', mm.group(6))
            methods.append((port, mm.group(1), r.group(1) if r else mt, p))
    src = "".join(p.read_text(encoding="utf-8") for p in PAGES.rglob("*.ts*")
                  if "_api" not in p.parts and not p.name.endswith(".test.ts"))
    return [(port, n, mt, p, bool(re.search(r"\." + port + r"\??\." + n + r"\b", src)) or f"{port}.{n}" in DYNAMIC)
            for port, n, mt, p in methods]


def match(fm, fp, m, p):
    a, b = fp.strip("/").split("/"), p.strip("/").split("/")
    seg = lambda x, y: x == y or x == "{}" or ("{}" in x and re.fullmatch(re.escape(x).replace(r"\{\}", "[^/]+"), y) is not None)
    return fm == m and len(a) == len(b) and all(seg(x, y) for x, y in zip(a, b))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    fe = wrappers(); rows = []
    for m, p, c in nearpay_endpoints(sys.argv[1]):
        hits = [(f"{po}.{n}", u) for po, n, fm, fp, u in fe if match(fm, fp, m, p)]
        st = "MISSING" if not hits else ("UI" if any(u for _, u in hits) else "API_ONLY")
        if st != "UI" and (m, p) in INTERNAL_PATHS: st = "UI"
        if st != "UI":
            st = ("NOT_OPEN" if (m, p) in NOT_OPEN or c in NOT_OPEN_CONTROLLERS else "EXCLUDED" if (m, p) in EXCLUDED
                  else "DEFERRED" if c in DEFERRED_CONTROLLERS else st)
        rows.append((c, m, p, st, ",".join(sorted({h for h, _ in hits}))))
    if "--tsv" in sys.argv:
        with open(sys.argv[sys.argv.index("--tsv") + 1], "w", encoding="utf-8") as f:
            f.writelines("\t".join(r) + "\n" for r in rows)
    n = collections.Counter(r[3] for r in rows)
    scope = len(rows) - n["NOT_OPEN"] - n["EXCLUDED"] - n["DEFERRED"]
    print(f"near-pay 接口 {len(rows)}：页面能用 {n['UI']}，只封装 {n['API_ONLY']}，未接 {n['MISSING']}；"
          f"near-pay 未开放 {n['NOT_OPEN']}，不在运营端提供 {n['EXCLUDED']}，暂缓（渠道专属）{n['DEFERRED']}")
    print(f"本期范围 {scope} 个，已接 {n['UI']}（{n['UI'] * 100 // max(scope, 1)}%）")


if __name__ == "__main__":
    main()
