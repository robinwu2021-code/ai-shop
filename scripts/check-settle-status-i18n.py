#!/usr/bin/env python3
"""结算单状态文案对账 —— 后端枚举全集 ↔ 三端词条。

B 端结算单页写的是 ``$t(`settle.status${b.status}`)``，**动态键**。
i18n 闸门扫的是字面量键，这一片它一个字都看不见：缺词条不报错、不回退，
直接把键名原样渲染到界面上。2026-09-29 线上实测，虹选合并证照转自营后，
``PENDING_RECON`` / ``CONFIRMED`` / ``PAID`` 三个状态全露成
``settle.statusPENDING_RECON`` 这样的字符串，而所有闸门都是绿的。

所以这里换个方向量：不从键出发找文案，而从 **StlBill.STATUS_ALL** 出发，
要求每个状态在每种语言里都有一条 ``settle.status<VALUE>``。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENTITY = ROOT / "backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlBill.java"
LOCALES = {
    "zh-CN": ROOT / "b-app/src/i18n/locale/zh-CN.ts",
    "en": ROOT / "b-app/src/i18n/locale/en.ts",
    "ar": ROOT / "b-app/src/i18n/locale/ar.ts",
}


def backend_statuses() -> set[str]:
    src = ENTITY.read_text(encoding="utf-8")
    block = re.search(r"STATUS_ALL\s*=\s*java\.util\.Set\.of\((.*?)\);", src, re.S)
    if not block:
        sys.exit(f"✗ {ENTITY.relative_to(ROOT)} 里找不到 STATUS_ALL —— 守卫量不到东西，等于没有守卫")
    names = [n.strip() for n in block.group(1).replace("\n", " ").split(",") if n.strip()]
    consts = dict(re.findall(r'public static final String (\w+) = "([^"]+)";', src))
    missing = [n for n in names if n not in consts]
    if missing:
        sys.exit(f"✗ STATUS_ALL 里的 {', '.join(missing)} 不是这个类的字符串常量")
    return {consts[n] for n in names}


def locale_keys(path: Path) -> set[str]:
    """只取 settle 段里的 status* 键 —— 别的段也有同名键（goods、pickup…）。"""
    src = path.read_text(encoding="utf-8")
    seg = re.search(r"\n  settle: \{(.*?)\n  \},", src, re.S)
    if not seg:
        sys.exit(f"✗ {path.relative_to(ROOT)} 里找不到 settle 段")
    return set(re.findall(r"\bstatus([A-Z][A-Z_]*)\s*:", seg.group(1)))


def main() -> int:
    want = backend_statuses()
    bad = False
    for lang, path in LOCALES.items():
        have = locale_keys(path)
        for miss in sorted(want - have):
            print(f"✗ {lang}: 缺 settle.status{miss} —— 界面会把这个键名原样显示给店主")
            bad = True
        for extra in sorted(have - want):
            print(f"✗ {lang}: settle.status{extra} 在后端 STATUS_ALL 里没有对应状态")
            bad = True
    if bad:
        print(f"\n  后端全集（StlBill.STATUS_ALL，{len(want)} 个）：{', '.join(sorted(want))}")
        return 1
    print(f"✓ 结算单状态文案：{len(want)} 个状态 × {len(LOCALES)} 种语言，齐")
    return 0


if __name__ == "__main__":
    sys.exit(main())
