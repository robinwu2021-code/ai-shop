#!/usr/bin/env python3
"""三端前端**真的会去调**的端点，后端必须存在。

为什么要这道闸：三端各有一份契约（`c-app|b-app/src/api/endpoints.ts`、
`ops-web/lib/api/https/*.ts`），它们是页面与后端之间唯一的接线。
接错了不会有任何东西报错 ——

  · c-app / b-app：`http.ts` 里统一 catch，页面拿到空数组，**屏是空的但没有报错**；
    b-app 的 `mFulfillmentImpact` 甚至写着 `.catch(() => [])`，
    「关掉这条履约渠道会影响多少单」在线上恒为空，且看起来完全正常
  · ops-web：默认 `NEXT_PUBLIC_USE_MOCK=1`，本地开发永远是通的；
    只有 `build:prod`（部署走的那条）才 `USE_MOCK=0`。
    **于是「本地好好的」与「线上是空的」可以同时成立。**

所以这道闸不测行为，只回答一件很窄、但没人回答过的事：
**前端点名要调的这个路径，后端到底有没有。**

用法：
    python3 scripts/check-contract-endpoints.py            # 列全部
    python3 scripts/check-contract-endpoints.py --check    # 有缺口就非零退出

### 已知的两处口径

- 只认**字面量**路径。用变量拼出来的路径这里看不见 —— 漏报，不误报。
- 注释里的路径不算调用（`iam.ts` 与 `merchant.ts` 各有一条写在注释里的
  历史路径，它们不是缺口）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def norm(p: str) -> str:
    """`{no}` / `:id` / `${no}` 一律归一，只比结构。"""
    return re.sub(r"\{[^}]*\}|\$\{[^}]*\}|:[A-Za-z_]\w*", "{}", p.split("?")[0]).rstrip("/")


def backend_endpoints() -> set[tuple[str, str]]:
    """(METHOD, 路径)。类级 @RequestMapping 必须拼上 —— 方法级写的是 `/address`，
    单看它会得出「/mp/user/address 不存在」这种正好反了的结论。"""
    out = set()
    for f in (ROOT / "backend").rglob("*.java"):
        if "/test/" in str(f) or "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8", errors="ignore")
        cls = re.search(r'@RequestMapping\(\s*"([^"]+)"\s*\)', src)
        base = cls.group(1) if cls else ""
        for method, path in re.findall(
                r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"', src):
            full = path if (base and path.startswith(base)) or not base else base.rstrip("/") + path
            out.add((method.upper(), norm(full)))
    return out


def uni_contract(app: str) -> list[tuple[str, str, str]]:
    """c-app / b-app：一张表，method 与 path 写在一起。"""
    src = (ROOT / app / "src/api/endpoints.ts").read_text(encoding="utf-8")
    return [(n, m.upper(), p) for n, m, p in
            re.findall(r'(\w+):\s*\{\s*method:\s*"(\w+)",\s*path:\s*"([^"]+)"', src)]


def ops_contract() -> list[tuple[str, str, str]]:
    """ops-web：`client.get("/ops/…")` 散在按域切片的 https/*.ts 里。"""
    out = []
    for f in sorted((ROOT / "ops-web/lib/api/https").glob("*.ts")):
        if f.name.endswith(".test.ts"):
            continue
        for line in f.read_text(encoding="utf-8").split("\n"):
            stripped = line.lstrip()
            if stripped.startswith(("//", "*", "/*")):
                continue          # 注释里的路径不是调用
            m = re.search(r'client\.(get|post|put|patch|delete)\(\s*[`"](/ops/[^`"]*)[`"]', line)
            if m:
                out.append((f.stem, m.group(1).upper(), m.group(2)))
    return out


def main() -> int:
    backend = backend_endpoints()
    total = missing = 0
    for app, entries in (("c-app", uni_contract("c-app")),
                         ("b-app", uni_contract("b-app")),
                         ("ops-web", ops_contract())):
        gaps = [(w, m, p) for w, m, p in entries if (m, norm(p)) not in backend]
        total += len(entries)
        missing += len(gaps)
        print(f"{app:<8} 契约 {len(entries):>3} 条 · 后端不存在 {len(gaps)} 条")
        for where, method, path in gaps:
            print(f"    {where:<22} {method:<6} {path}")
    print(f"\n合计 {total} 条契约端点，其中 {missing} 条后端没有。")
    if "--check" in sys.argv and missing:
        print("\n前端会去调这些路径，而后端没有 —— 线上那几屏是空的，且不会报错。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
