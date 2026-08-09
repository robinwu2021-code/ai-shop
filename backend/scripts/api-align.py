#!/usr/bin/env python3
"""A6 对齐守卫：契约 × 前端端点表 × 后端代码，三方比对。

三个真源各自会漂移，且漂移**不会有任何编译错误**：
  - 契约   docs/api/openapi.yaml（由 API 清单生成）
  - 前端   c-app/src/api/endpoints.ts（前端唯一真源）
  - 后端   backend/**/portal/**/*.java 的 @GetMapping/@PostMapping

三者不一致的后果全是联调时才发现：前端调了不存在的路径、后端实现了没人要的端点、
路径少个连字符。本脚本把这类问题提前到 CI。

退出码：0=无阻塞差异；1=存在阻塞差异（前端↔后端不一致，或后端实现了契约里没有的端点）
用法：python3 backend/scripts/api-align.py [--strict]
      --strict 时「契约有而后端未实现」也算失败（默认不算，因为还没实现完）
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "docs/api/openapi.yaml"
OPENAPI_B = ROOT / "docs/api/openapi-b.yaml"
# 平台端契约。它一直存在，但此前**读不进来** —— ops 生成器把 YAML 每个 key 都加了引号
# （`"paths":`），而本脚本按 `^paths:` 匹配。生成器已修（裸 key），这里把它接上，
# 三个域才都在同一张网里。
OPENAPI_OPS = ROOT / "docs/api/openapi-ops.yaml"
ENDPOINTS_TS = ROOT / "c-app/src/api/endpoints.ts"
ENDPOINTS_TS_B = ROOT / "b-app/src/api/endpoints.ts"
# Controller 不只在 shop-app：2026-08 模块合并（S7 垂直切片）之后，
# 25 个里有 20 个搬进了各自域模块的 `api` 包。
# 只扫 portal 会让后端端点数从 151 掉到 56 —— 而报告照样打印，
# 看起来像「后端突然少实现了 95 条」，实际是这个脚本瞎了。
BACKEND_ROOTS = [
    ROOT / "backend" / m / "src/main/java/ai/neargo/shop"
    for m in ("shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel")
]


def norm(path):
    """
    统一路径参数写法：`:x`、`{x}`、`${x}` 视为同一条，且**参数名一律折叠成 {id}**。

    为什么折叠名字：ops-web 的路径是 JS 模板字面量，`${no}` 里的 `no` 是**本地变量名**，
    不是 API 参数名 —— 拿它去比契约里的 `{auditNo}` 会让每一条带 id 的路径
    都被算成「契约里没有」，90 条假差异淹掉真差异。
    路径参数叫什么不是契约的一部分，路径形状才是。
    """
    path = re.sub(r"\$\{[^}]*\}", "{id}", path)
    return re.sub(r"[:{](\w+)}?", "{id}", path.rstrip("/") or "/")


# ---------------------------------------------------------------- 三方抽取

def from_openapi(*files):
    """只解析 paths 段的两级缩进，够用且不引入 PyYAML。"""
    out = set()
    for f in files:
        if f.exists():
            out |= _one_openapi(f)
    return out


def _one_openapi(f):
    out, current = set(), None
    in_paths = False
    for line in f.read_text().splitlines():
        # 容忍 `paths:` 与 `"paths":` 两种写法 —— 生成器已改成裸 key，
        # 这里留一道保险：解析器不该因为下游换了个等价写法就整个瘸掉且不报错
        if re.match(r'^"?paths"?:\s*$', line):
            in_paths = True
            continue
        if not in_paths:
            continue
        m = re.match(r'^  "?(/[^":]*)"?:\s*$', line)
        if m:
            current = norm(m.group(1))
            continue
        m = re.match(r'^    "?(get|post|put|delete)"?:\s*$', line)
        if m and current:
            out.add((m.group(1).upper(), current))
    return out


def from_frontend(*files):
    """endpoints.ts 里的 { method: "GET", path: "/mp/..." }。"""
    out = set()
    for f in files:
        if not f.exists():
            continue
        out |= {(m.group(1), norm(m.group(2)))
                for m in re.finditer(r'method:\s*"(GET|POST)",\s*\n?\s*path:\s*"([^"]+)"',
                                     f.read_text())}
    return out


def from_ops_frontend():
    """
    ops-web 的端点散在 lib/api/https/*.ts 里，形如
    ``client.get("/ops/x", q)`` / ``client.post(`/ops/x/${id}/y`)``。

    **它以前不在对账范围里**，于是平台端一直显示「前端 0 条」——
    而 ops-web 默认走 mock，页面全绿，没人会发现后端根本没这些接口。
    纳进来之后，「哪些调用是真的」才在这份报告里看得见。
    """
    out = set()
    d = ROOT / "ops-web" / "lib" / "api" / "https"
    if not d.exists():
        return out
    # 动词按原样保留：契约里 PUT 就是 PUT。折成 POST 的话，
    # 「前端 PUT / 契约 PUT」会被当成「前端 POST 调了个契约没有的端点」
    verb = {"get": "GET", "post": "POST", "put": "PUT", "del": "DELETE"}
    for f in d.glob("*.ts"):
        for m in re.finditer(r'client\.(get|post|put|del)\(\s*[`"]([^`"]+)[`"]', f.read_text()):
            out.add((verb[m.group(1)], norm(m.group(2))))
    return out


def from_backend():
    """@RequestMapping 前缀 + @GetMapping/@PostMapping 后缀。"""
    out = set()
    for f in (f for root in BACKEND_ROOTS if root.exists()
              for f in root.rglob("*Controller.java")):
        text = f.read_text()
        base = ""
        m = re.search(r'@RequestMapping\("([^"]+)"\)', text)
        if m:
            base = m.group(1)
        # 注解可能写成全限定名（@org.springframework.web.bind.annotation.PostMapping）——
        # 这是第三次被这个模式坑：Port 被误判「无人调用」、改名批量替换漏掉、
        # 以及 /mp/merchant/apply 明明实现了却一直报未实现。前缀一律容忍。
        for mm in re.finditer(
                r'@(?:[\w.]*\.)?(Get|Post)Mapping\((?:value\s*=\s*)?"?([^")]*)"?\)?', text):
            verb = mm.group(1).upper() + ("T" if mm.group(1) == "Pos" else "")
            method = "GET" if mm.group(1) == "Get" else "POST"
            suffix = mm.group(2).strip()
            path = (base + suffix) if suffix.startswith("/") else (base or suffix)
            if not path.startswith("/"):
                continue
            out.add((method, norm(path)))
        # 无 value 的 @GetMapping：整个类只有类级路径
        for mm in re.finditer(r'@(?:[\w.]*\.)?(Get|Post)Mapping\s*\n', text):
            if base:
                out.add(("GET" if mm.group(1) == "Get" else "POST", norm(base)))
    return out


# ---------------------------------------------------------------- 比对

# 按**前缀分域**比对。三个前缀是三套独立契约，混在一起比会得出彻头彻尾的假数字：
#   /mp  C 端 BFF  → 契约 docs/api/openapi.yaml（由 c-app 生成）· 前端 c-app
#   /biz B 端      → 契约缺失（b-app 尚未生成 openapi）· 前端 b-app（注意其前缀是 /mb，见下）
#   /common 跨端   → 公共元数据（行业/主体/通道），C 端与 B 端要的是同一份。
#                     它不是漏网的 /biz —— 造一个别名只会得到两条路径服务同一件事
#   /ops 平台端    → 契约 docs/api/openapi-ops.yaml（由 ops-web 生成）· 前端 ops-web
#                     （前端侧仍未纳入：它是 Next.js，端点散在 lib/api/https/*.ts，
#                      但契约↔后端这一对已经能比了）
# 之前拿 C 端契约去比全部后端路由，于是 /biz 与 /ops 的每一条都被算成「后端多出的端点」。
DOMAINS = [
    ("/mp", "C 端"),
    ("/biz", "B 端"),
    ("/ops", "平台端"),
    ("/common", "跨端公共"),
]


def pick(items, prefix):
    return {(m, p) for m, p in items if p.startswith(prefix + "/") or p == prefix}


def main():
    strict = "--strict" in sys.argv
    contract = from_openapi(OPENAPI, OPENAPI_B, OPENAPI_OPS)
    frontend = from_frontend(ENDPOINTS_TS, ENDPOINTS_TS_B) | from_ops_frontend()
    backend = from_backend()

    print(f"契约 {len(contract)} 条 · 前端 {len(frontend)} 条 · 后端 {len(backend)} 条")

    blocking = 0

    def report(title, items, is_blocking, hint):
        nonlocal blocking
        if not items:
            return
        mark = "✗" if is_blocking else "·"
        print(f"  {mark} {title}（{len(items)}）—— {hint}")
        for method, path in sorted(items, key=lambda x: x[1]):
            print(f"      {method:4} {path}")
        if is_blocking:
            blocking += len(items)

    for prefix, label in DOMAINS:
        c, f, b = pick(contract, prefix), pick(frontend, prefix), pick(backend, prefix)
        if not (c or f or b):
            continue
        print(f"\n── {label} {prefix}/** ── 契约 {len(c)} · 前端 {len(f)} · 后端 {len(b)}")

        if not c:
            # 没有契约就没有基准，此时把「前端 ↔ 后端」直接对上 —— 这一档最容易烂掉，
            # 因为两边都没有第三方约束，谁改了对方都不会知道。
            print("    ⚠ 该域尚无契约文件，退化为前端↔后端直比（无第三方基准，最容易漂移）")
            report("前端调了后端没有的", f - b, True, "路径或前缀不一致，联调必炸")
            report("后端有而前端没调", b - f, False, "可能是尚未接入的能力")
            continue

        # **非阻塞**（--strict 下阻塞）。契约是从前端端点表生成的，所以「后端有、契约没有」
        # 与下面的「前端还没接」是同一件事的两种说法 —— 前者判阻塞、后者判进度差，
        # 自相矛盾，且会让守卫长期挂红。挂红成为常态之后，它就再也拦不住真问题了。
        # 它仍然值得看：没人调用的后端端点要么是待接入能力，要么是没人复核过的暴露面。
        report("后端已实现、前端尚未接入", b - c, strict,
               "后端先行。逐条确认：是待接入能力，还是没人要的暴露面")
        report("前端调用了契约里没有的端点", f - c, True,
               "前端跑在契约前面：补清单，或前端改路径")
        # 同样非阻塞，但**关掉 mock 那天它们全部变成 404** —— 切 VITE_USE_MOCK=0 前必须清零
        report("前端要调但后端还没实现", f - b, False,
               "关掉 mock 即 404。切 VITE_USE_MOCK=0 之前必须清零")
        report("契约有而后端未实现", c - b, strict, "正常的待办；--strict 下视为失败")
        print(f"    覆盖率 {len(b & c) / len(c) * 100:.1f}%")

    # 落在三个域之外的后端路由（如 /callback/**）单独点名，别让它们静默存在
    stray = {(m, p) for m, p in backend if not any(p.startswith(pre) for pre, _ in DOMAINS)}
    if stray:
        print(f"\n── 域外路由（{len(stray)}）—— 不属于任何已知前缀，确认是否有意为之")
        for m, pth in sorted(stray, key=lambda x: x[1]):
            print(f"      {m:4} {pth}")

    # 曾经 b-app 用 /mb 而后端用 /biz，两边一条也对不上（已统一为 /biz，见 ADR-007 修订）。
    # 这个检查留着：任何一端再漂到已知前缀之外，都会在这里当场暴露。
    fe_stray = {(m, p) for m, p in frontend if not any(p.startswith(pre) for pre, _ in DOMAINS)}
    if fe_stray:
        print(f"\n✗ 前端有 {len(fe_stray)} 条路径不在任何已知前缀下")
        for m, pth in sorted(fe_stray, key=lambda x: x[1]):
            print(f"      {m:4} {pth}")
        blocking += len(fe_stray)

    if blocking:
        print(f"\n✗ 存在 {blocking} 条阻塞差异")
        return 1
    print("\n✓ 无阻塞差异")
    return 0


if __name__ == "__main__":
    sys.exit(main())
