#!/usr/bin/env python3
"""后端有 `/biz` `/mp` 端点，**但 b-app / c-app 没有出口** —— 做了没人用得上。

**这是 `scripts/check-app-contract.py` 的反方向，两个都要跑。**

    · check-app-contract        端上调了 → 后端有没有（画了没后端）
    · 本脚本                    后端做了 → 端上调没调（做了没入口）

运营端两个方向都有闸（check-ops-contract / check-ops-orphan），
**而 b/c 端此前只有一个方向**。缺的偏偏是更难发现的那个：
「画了没后端」在切真后端时立刻 404；「做了没入口」不报任何错 ——
它表现成「这个功能我们做过啊」，而人点不到。
`check-ops-orphan` 立闸当天扫出 18 条，其中 13 条是当时唯一能把钱付出去的链路。

### 口径

- **抽取器直接复用 check-app-contract**，不另写一套。
  两道闸量的是同一批端点，各写一把尺就会出现「这边算孤儿、那边算存在」。
  它的 `norm()` 已经认 `:orderNo` / `{no}` / `${no}` 三种写法 ——
  ⚠️ 少认冒号那一种的后果是**所有带参端点全被报成孤儿**（实测 5 → 58）。
- **端上的出口两处都算**：`endpoints.ts` 的登记，加上全量源码里的字面量路径。
  只扫登记表会把「页面里直接拼路径」的调用误报成孤儿。**漏报，不误报。**
- 命中按 `(方法, 归一路径)`。同一路径上后端有 GET+POST 而端上只调 GET，
  那个 POST 就是真孤儿 —— 合并成只看路径会把它藏起来。

### 用法

    python3 scripts/check-app-backend-orphan.py           # 列出来
    python3 scripts/check-app-backend-orphan.py --check   # 超过基线就非零退出
"""
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "known-app-backend-orphan.txt"

# 复用同族那道闸的抽取器（文件名带连字符，只能按路径加载）
_spec = importlib.util.spec_from_file_location(
    "check_app_contract", ROOT / "scripts/check-app-contract.py")
_cac = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_cac)
norm, backend, contract = _cac.norm, _cac.backend, _cac.contract

APPS = {"b-app": "/biz", "c-app": "/mp"}

# **按设计就没有端上出口的端点** —— 服务器到服务器调用。
# 把它们记进「只准变短」的名单是错的：那种行永远变不短，而一份含着
# 变不短的行的名单，会训练人把「超过基线」当噪声。排除，不是欠账。
BY_DESIGN_NO_EXIT = {
    # 通道支付回调（ChannelPayCallbackController）——微信服务器打进来的。
    # GET 是微信的服务器配置校验，POST 是真回调。
    ("GET", "/mp/wx/callback"),
    ("POST", "/mp/wx/callback"),
}


def app_exits(app: str) -> set[tuple[str, str]]:
    """端上所有出口：endpoints.ts 的登记 + 全量源码里的字面量路径。"""
    out = {(m, norm(p)) for _, m, p in contract(app)}
    # 源码里直接写的路径没有方法名，登记成通配 —— 宁可漏报
    for f in (ROOT / app / "src").rglob("*"):
        if f.suffix not in (".ts", ".vue", ".js") or not f.is_file():
            continue
        src = f.read_text(encoding="utf-8", errors="replace")
        for p in re.findall(r'["`](/(?:biz|mp)/[^"`\n\s]*)', src):
            out.add(("*", norm(p)))
    return out


def orphans() -> dict[str, list[tuple[str, str]]]:
    eps = backend()
    # 扫描面断言：任一边扫空都会让这道闸恒绿，而恒绿的闸门等于没有闸门
    assert len(eps) > 200, f"后端端点只扫到 {len(eps)} 个 —— 抽取器坏了？"
    res = {}
    for app, prefix in APPS.items():
        exits = app_exits(app)
        assert len(exits) > 50, f"{app} 的出口只扫到 {len(exits)} 条 —— 抽取器坏了？"
        paths = {p for _, p in exits}
        mine = [(m, p) for m, p in eps if p.startswith(prefix + "/") or p == prefix]
        assert mine, f"后端一个 {prefix} 端点都没有 —— 前缀写错了？"
        res[app] = sorted((m, p) for m, p in mine
                          if (m, p) not in BY_DESIGN_NO_EXIT
                          and (m, p) not in exits and ("*", p) not in exits
                          and p not in paths)
    return res


def main() -> int:
    check = "--check" in sys.argv
    known = set()
    if BASELINE.exists():
        known = {l.strip() for l in BASELINE.read_text(encoding="utf-8").splitlines()
                 if l.strip() and not l.startswith("#")}
    rows, fresh, stale = [], [], set(known)
    for app, eps in orphans().items():
        for m, p in eps:
            key = f"{app} {m} {p}"
            rows.append(key)
            stale.discard(key)
            if key not in known:
                fresh.append(key)
    total = len(rows)
    print(f"后端做了没入口：{total} 条（已知欠账 {len(known)}）")
    for r in rows:
        print(f"  {'NEW ' if r in fresh else '    '}{r}")
    # 双向：修好了不从名单删，那个端点就永远免检 —— 与新增同样要报
    if stale:
        print(f"\n名单里这 {len(stale)} 条已经接上了，把它们从 {BASELINE.name} 删掉：")
        for s in sorted(stale):
            print(f"  {s}")
    if check and (fresh or stale):
        print("\n✗ 与基线不符（新增或已修好未删）")
        return 1
    if check:
        print("✓ 未超过基线")
    return 0


if __name__ == "__main__":
    sys.exit(main())
