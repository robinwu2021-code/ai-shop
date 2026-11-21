#!/usr/bin/env python3
"""支付中心对接完整性：near-pay 支付运营接口 → 运营端接口封装 → 页面调用。

用法：python3 scripts/pay-center-wiring.py <near-pay 仓库根目录> [--tsv 输出文件]
  near-pay 侧读 backend/pay/pay-portal/.../api/ops/v2/*.java 的 @*Mapping；
  运营端读 ops-web/app/payment-center/_api/api.ts 的封装方法，再在 payment-center 页面里找调用。
  状态：UI = 页面能用；API_ONLY = 只封装、页面没用；MISSING = 运营端没接。
"""
import collections, glob, json, os, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = ROOT / "ops-web/app/payment-center/_api/api.ts"
PAGES = ROOT / "ops-web/app/payment-center"
DYNAMIC = {"transfers.approve", "transfers.reject", "transfers.execute"}  # api.transfers[kind](...)


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
    for l in lines:
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
    return fm == m and len(a) == len(b) and all(x == y or x == "{}" for x, y in zip(a, b))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    fe = wrappers(); rows = []
    for m, p, c in nearpay_endpoints(sys.argv[1]):
        hits = [(f"{po}.{n}", u) for po, n, fm, fp, u in fe if match(fm, fp, m, p)]
        st = "MISSING" if not hits else ("UI" if any(u for _, u in hits) else "API_ONLY")
        rows.append((c, m, p, st, ",".join(sorted({h for h, _ in hits}))))
    if "--tsv" in sys.argv:
        with open(sys.argv[sys.argv.index("--tsv") + 1], "w", encoding="utf-8") as f:
            f.writelines("\t".join(r) + "\n" for r in rows)
    n = collections.Counter(r[3] for r in rows)
    print(f"near-pay 接口 {len(rows)}：页面能用 {n['UI']}，只封装 {n['API_ONLY']}，未接 {n['MISSING']}")


if __name__ == "__main__":
    main()
