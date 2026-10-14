#!/usr/bin/env python3
"""c-app / b-app 点名要调的端点，后端必须真的有。

**这是 `scripts/check-ops-contract.mjs` 的另外两端。** 那道闸盯运营端
（它默认走 mock，所以「调了一个后端没有的接口」在界面上完全看不出来），
而 c-app 与 b-app 此前没有任何东西做这件事 —— 它们的失败方式不同但一样安静：

  · 两端的 `http.ts` 统一 catch，页面拿到空数组，**屏是空的但不报错**
  · b-app 的 `mFulfillmentImpact` 干脆写着 `.catch(() => [])` ——
    「关掉这条履约渠道会影响多少单」恒为空，且看起来完全正常

基线 `app-known-missing-endpoints.txt` 与运营端那份同一个道理：
要求全部补齐才让过，会让它从第一天起恒红，而恒红的闸门等于没有闸门。
**补上一条就删一行。**

用法：
    python3 scripts/check-app-contract.py            # 列全部
    python3 scripts/check-app-contract.py --check    # 基线之外的新缺口 → 非零退出

### 口径

- 只认**字面量**路径（`endpoints.ts` 里就是字面量）。漏报，不误报。
- 端点侧扫 `@XxxMapping`，**类级 `@RequestMapping` 必须拼上** ——
  方法级写的是 `/address`，单看它会得出「`/mp/user/address` 不存在」这种正好反了的结论。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "app-known-missing-endpoints.txt"


def norm(p: str) -> str:
    return re.sub(r"\{[^}]*\}|\$\{[^}]*\}|:[A-Za-z_]\w*", "*", p.split("?")[0]).rstrip("/")


def backend() -> set[tuple[str, str]]:
    out = set()
    for f in (ROOT / "backend").rglob("*.java"):
        if "/test/" in str(f) or "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8", errors="ignore")
        if "Mapping(" not in src:
            continue
        cls = re.search(r'@RequestMapping\(\s*"([^"]+)"\s*\)', src)
        base = cls.group(1) if cls else ""
        for method, path in re.findall(
                r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"', src):
            full = path if (base and path.startswith(base)) or not base else base.rstrip("/") + path
            out.add((method.upper(), norm(full)))
    return out


def contract(app: str) -> list[tuple[str, str, str]]:
    src = (ROOT / app / "src/api/endpoints.ts").read_text(encoding="utf-8")
    return [(n, m.upper(), p) for n, m, p in
            re.findall(r'(\w+):\s*\{\s*method:\s*"(\w+)",\s*path:\s*"([^"]+)"', src)]


def baseline() -> set[str]:
    if not BASELINE.exists():
        return set()
    return {l.strip() for l in BASELINE.read_text(encoding="utf-8").splitlines()
            if l.strip() and not l.startswith("#")}


def main() -> int:
    have = backend()
    known = baseline()
    total = 0
    gaps: list[tuple[str, str, str, str]] = []
    for app in ("c-app", "b-app"):
        ent = contract(app)
        total += len(ent)
        for name, method, path in ent:
            if (method, norm(path)) not in have:
                gaps.append((app, name, method, norm(path)))

    new = [g for g in gaps if f"{g[2]} {g[3]}" not in known]
    stale = known - {f"{g[2]} {g[3]}" for g in gaps}

    print(f"c-app + b-app 契约端点 {total} 条 · 后端没有 {len(gaps)} 条 "
          f"（基线内 {len(gaps) - len(new)}，基线外 {len(new)}）")
    print(f"**对得上的 {total - len(gaps)} 条本身就是对照量** —— "
          f"扫描器要是整体失效，会是全部报缺。")
    for app, name, method, path in gaps:
        tag = "新增" if f"{method} {path}" not in known else "已登记"
        print(f"    [{tag}] {app:<6} {name:<24} {method:<5} {path}")

    if "--check" in sys.argv:
        if stale:
            print("\n✗ 基线里这些已经补上了，删掉对应行 —— "
                  "**修好的行留在欠账清单里，那个端点就永远免检**：", file=sys.stderr)
            for s in sorted(stale):
                print(f"    {s}", file=sys.stderr)
            return 1
        if new:
            print("\n✗ 新增了「前端在调、后端没有」的端点。"
                  "页面会拿到空数据且不报错：", file=sys.stderr)
            for app, name, method, path in new:
                print(f"    {app} {name}  {method} {path}", file=sys.stderr)
            return 1
        print("✓ 没有基线之外的契约缺口")
    return 0


if __name__ == "__main__":
    sys.exit(main())
