#!/usr/bin/env python3
"""A5 追溯矩阵：功能点 → 端点 → 页面 → 后端实现 → 测试覆盖，六列合拢。

手工维护追溯矩阵是没有意义的 —— 它一定会过期，而一份过期的追溯矩阵比没有更糟：
它会让人以为「查过了，都覆盖了」。所以这里全部从各自的真源现算：

  功能点/优先级  ← docs/technical/API清单.md 的矩阵 ID 列
  端点          ← docs/api/openapi.yaml（A1 契约）
  页面          ← c-app/src/api/endpoints.ts + pages/**（哪个页面调哪个契约方法）
  后端实现      ← backend/**/portal/**/*.java 的映射注解
  测试覆盖      ← backend/**/src/test/**/*.java 里出现的路径字面量

反向检查三条（真正的价值所在）：
  ① P0 端点没有页面消费 → 要么页面漏做，要么端点多做
  ② 已实现端点没有测试   → 覆盖率造假的重灾区
  ③ 端点没有矩阵 ID     → 需求里没有这件事，为什么要做

用法：python3 backend/scripts/gen-traceability.py
"""
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/technical/追溯矩阵.md"


def norm(p):
    return re.sub(r"[:{](\w+)}?", r"{\1}", p.rstrip("/") or "/")


# ---------------------------------------------------------------- 真源 1：契约（含矩阵 ID）

def load_contract():
    """从 API 清单直接取，因为矩阵 ID 与优先级在那里最完整。"""
    rows = {}
    for line in (ROOT / "docs/technical/API清单.md").read_text().splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        matrix_id = cells[0] if cells else ""
        priority = next((c for c in cells if c.startswith("P0") or c in ("P1", "P2", "仅 dev")), "")
        for meth, path in re.findall(r"`(GET/POST|GET|POST)\s+(/[A-Za-z0-9:_\-{}/.]+)`", line):
            marker = f"`{meth} {path}`"
            tail = line.split(marker, 1)[1] if marker in line else ""
            summary = re.split(r"[·|`]", tail)[0].strip().strip("* ") or ""
            for m in (["GET", "POST"] if meth == "GET/POST" else [meth]):
                rows.setdefault((m, norm(path)),
                                {"matrixId": matrix_id, "priority": priority, "summary": summary})
    return rows


# ---------------------------------------------------------------- 真源 2：前端页面

def load_pages():
    """契约方法名 → 路径；页面 → 契约方法名。合成 路径 → [页面]。"""
    ts = (ROOT / "c-app/src/api/endpoints.ts").read_text()
    method_to_path = {}
    for m in re.finditer(r"(\w+):\s*\{[^}]*?method:\s*\"(GET|POST)\"[^}]*?path:\s*\"([^\"]+)\"", ts, re.S):
        method_to_path[m.group(1)] = (m.group(2), norm(m.group(3)))

    path_to_pages = defaultdict(set)
    src_dirs = [ROOT / "c-app/src/pages", ROOT / "c-app/src/stores", ROOT / "c-app/src/components"]
    for d in src_dirs:
        for f in list(d.rglob("*.vue")) + list(d.rglob("*.ts")):
            # 页面名：pages/xxx/index.vue → xxx；stores/cart.ts → store:cart
            if "pages" in f.parts:
                name = f.parent.name
            elif "stores" in f.parts:
                name = "store:" + f.stem
            else:
                name = "comp:" + f.stem
            for call in set(re.findall(r"api\.(\w+)", f.read_text(errors="ignore"))):
                if call in method_to_path:
                    path_to_pages[method_to_path[call]].add(name)
    return path_to_pages


# ---------------------------------------------------------------- 真源 3/4：后端实现与测试

def load_backend():
    out = set()
    portal = ROOT / "backend/shop-app/src/main/java/ai/neargo/shop/portal"
    if not portal.exists():
        return out
    for f in portal.rglob("*.java"):
        text = f.read_text()
        base = ""
        m = re.search(r'@RequestMapping\("([^"]+)"\)', text)
        if m:
            base = m.group(1)
        for mm in re.finditer(r'@(Get|Post)Mapping\((?:value\s*=\s*)?"([^"]*)"\)', text):
            method = "GET" if mm.group(1) == "Get" else "POST"
            out.add((method, norm(base + mm.group(2))))
        for mm in re.finditer(r'@(Get|Post)Mapping\s*\n', text):
            if base:
                out.add(("GET" if mm.group(1) == "Get" else "POST", norm(base)))
    return out


def load_tests():
    """测试源码里的请求路径。

    两种写法都要认：
      1. 完整字面量  `get("/mp/goods/G0001")`
      2. **字符串拼接** `get("/mp/order/" + orderNo + "/pay")`
    只认第 1 种会把交易域几乎所有带路径参数的端点误报成「无测试」——
    而这个指标是要当 CI 红线用的，误报会让人直接把它关掉。
    """
    text = "\n".join(f.read_text(errors="ignore")
                     for d in ROOT.glob("backend/*/src/test") for f in d.rglob("*.java"))
    prefix = r"/(?:mp|biz|ops|common|callback)"
    literals = {m.group(1) for m in re.finditer(rf'"({prefix}/[A-Za-z0-9_\-/{{}}.]*)"', text)}

    # 拼接：`"/mp/order/" + expr + "/pay"` → `/mp/order/{}/pay`；末尾无后缀则补一段 `{}`
    for m in re.finditer(rf'"({prefix}/[A-Za-z0-9_\-/]*/)"\s*\+\s*[^"+]+?(?:\+\s*"(/[A-Za-z0-9_\-/]*)")?[),]',
                         text):
        literals.add(m.group(1) + "{}" + (m.group(2) or ""))
    return literals


def test_covered(path, literals):
    """端点被覆盖 = 存在一条测试字面量与它**逐段匹配**（`{param}` 段匹配任意值）。

    早期版本用「静态前缀出现即算覆盖」，会把 `GET /mp/community/{no}`
    误判为无覆盖（测试里写的是 `/mp/community/C0001`，前缀 `/mp/community` 并未单独出现），
    同时又会把 `/mp/cart` 误判为被 `/mp/cart/add` 覆盖 —— 两个方向都错。
    段级匹配才是对的：这个指标要当 CI 红线用，假阳性和假阴性都不能有。
    """
    want = path.strip("/").split("/")
    for lit in literals:
        got = lit.strip("/").split("/")
        if len(got) != len(want):
            continue
        if all(w.startswith("{") or g.startswith("{") or w == g for w, g in zip(want, got)):
            return True
    return False


# ---------------------------------------------------------------- 生成

def main():
    contract = load_contract()
    pages = load_pages()
    backend = load_backend()
    tests = load_tests()

    rows = []
    for (method, path), meta in sorted(contract.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        rows.append({
            "method": method, "path": path,
            "matrixId": meta["matrixId"], "priority": meta["priority"], "summary": meta["summary"],
            "pages": sorted(pages.get((method, path), [])),
            "impl": (method, path) in backend,
            "test": test_covered(path, tests) and (method, path) in backend,
        })

    # 反向检查
    p0 = [r for r in rows if r["priority"].startswith("P0")]
    no_page = [r for r in p0 if not r["pages"] and r["path"].startswith("/mp")]
    impl_no_test = [r for r in rows if r["impl"] and not r["test"]]
    no_matrix = [r for r in rows if not r["matrixId"] or r["matrixId"] in ("端点", "")]

    lines = [
        "# A5 · 追溯矩阵（自动生成，勿手改）",
        "",
        "> 由 `backend/scripts/gen-traceability.py` 生成 · 六列各自来自真源，不手工维护。",
        "> 手工维护的追溯矩阵一定会过期，而过期的追溯矩阵比没有更糟 —— 它让人以为「查过了，都覆盖了」。",
        "",
        f"**{len(rows)} 条端点** · P0 {len(p0)} 条 · 后端已实现 {sum(r['impl'] for r in rows)} 条 "
        f"· 有测试覆盖 {sum(r['test'] for r in rows)} 条 · 前端有页面消费 {sum(1 for r in rows if r['pages'])} 条",
        "",
        "---",
        "",
        "## 一、反向检查（**本文的价值所在**）",
        "",
        f"### ① P0 端点无页面消费（{len(no_page)} 条）",
        "",
        "要么页面漏做，要么端点多做。C 端端点没有页面调用，等于没人要。",
        "",
        "| 矩阵 ID | 端点 | 说明 |",
        "|---|---|---|",
    ]
    for r in no_page[:40]:
        lines.append(f"| {r['matrixId']} | `{r['method']} {r['path']}` | {r['summary']} |")
    if len(no_page) > 40:
        lines.append(f"| … | 其余 {len(no_page) - 40} 条见完整表 | |")

    lines += [
        "",
        f"### ② 已实现但无测试（{len(impl_no_test)} 条）",
        "",
        "**覆盖率造假的重灾区**：端点跑得通不等于行为正确。",
        "",
    ]
    if impl_no_test:
        lines += ["| 端点 | 说明 |", "|---|---|"]
        lines += [f"| `{r['method']} {r['path']}` | {r['summary']} |" for r in impl_no_test]
    else:
        lines.append("✅ 无 —— 已实现的端点全部有测试触达。")

    lines += [
        "",
        f"### ③ 端点无矩阵 ID（{len(no_matrix)} 条）",
        "",
        "需求矩阵里没有这件事。要么补需求，要么这个端点不该存在。",
        "",
    ]
    if no_matrix:
        lines += ["| 端点 | 说明 |", "|---|---|"]
        lines += [f"| `{r['method']} {r['path']}` | {r['summary']} |" for r in no_matrix[:30]]
    else:
        lines.append("✅ 无。")

    lines += [
        "",
        "---",
        "",
        "## 二、完整追溯表",
        "",
        "图例：实现 ✅=已实现 · 测试 ✅=有测试触达 · 页面=消费该端点的 c-app 页面/store",
        "",
        "| 矩阵 ID | P | 端点 | 说明 | 页面 | 实现 | 测试 |",
        "|---|:-:|---|---|---|:-:|:-:|",
    ]
    for r in rows:
        lines.append(
            f"| {r['matrixId']} | {r['priority']} | `{r['method']} {r['path']}` | {r['summary']} "
            f"| {' '.join(r['pages']) or '—'} | {'✅' if r['impl'] else '⬜'} | {'✅' if r['test'] else '⬜'} |")

    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}")
    print(f"  端点 {len(rows)} · P0 {len(p0)} · 实现 {sum(r['impl'] for r in rows)} "
          f"· 测试 {sum(r['test'] for r in rows)} · 有页面 {sum(1 for r in rows if r['pages'])}")
    print(f"  反查①P0无页面 {len(no_page)} · ②实现无测试 {len(impl_no_test)} · ③无矩阵ID {len(no_matrix)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
