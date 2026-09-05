/**
 * `env(safe-area-inset-*)` **必须有一条不含它的兜底声明**。
 *
 * <p>来历（2026-09-05 真机）：小程序里购物车的结算条不贴底了，跟在商品卡后面
 * 浮在半空，而它上面的占位块一起塌成 0。iPhone 模拟器上一切正常。
 *
 * <p>根因是 `env()` / `constant()` 在部分 Android 内核里不认，
 * 而它们被写进了 `calc()`：**一个不认的函数会让整条声明失效**，
 * 于是 `bottom` 根本没被设置，`position: fixed` 的元素就停在静态位置。
 * 决定性的一条证据是 `.ab` 那句 `bottom: calc(28rpx + env(...))` 里**没有任何变量** ——
 * 它要是有效，条至少会贴在最底下压住菜单，而不是停在卡片下面。
 *
 * <p>修法是 CSS 的老办法：先写一条不含 `env()` 的，再写带 `env()` 的覆盖它。
 * 认得的设备用后者，不认的退回前者 —— 最多少掉安全区那一段，不会把整条赔进去。
 *
 * <p><b>内联样式里一律不许出现</b>：`:style` 是一次性求值的字符串，
 * 没有「后一条覆盖前一条」，失效就是彻底失效（`sh-actionbar` 的占位块高度
 * 当时就是这么塌的）。要安全区就单独挂一个属性，让它自己降级。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const DIRS = ["packages/ui/src", "c-app/src", "b-app/src"];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue") || p.endsWith(".css")) out.push(p);
  }
  return out;
}

const FILES = DIRS.flatMap((d) => walk(join(ROOT, d)));
const SAFE_AREA = /env\(safe-area-inset-[a-z]+\)/;
/** 一条 CSS 声明：`prop: value;`（简写与长写都算） */
const DECL = /^\s*([a-z-]+)\s*:\s*(.+);\s*$/;

describe("安全区兜底", () => {
  it("有文件可扫（否则下面全是空转）", () => {
    expect(FILES.length).toBeGreaterThan(50);
  });

  it("★★★ 每条带 env(safe-area) 的声明，前面都要有一条不带它的同名声明", () => {
    const bad: string[] = [];
    for (const f of FILES) {
      const lines = readFileSync(f, "utf8").split("\n");
      for (const [i, line] of lines.entries()) {
        if (!SAFE_AREA.test(line)) continue;
        const m = DECL.exec(line);
        // 不是一条声明（注释、文档里提到它）——不管
        if (!m) continue;
        const prop = m[1]!;
        // 往上找同一条规则里的同名声明，跳过注释行
        let ok = false;
        for (let k = i - 1; k >= 0 && k >= i - 4; k -= 1) {
          const prev = lines[k]!;
          if (prev.includes("{") || prev.includes("}")) break;
          const pm = DECL.exec(prev);
          if (!pm) continue;
          if (pm[1] === prop && !/env\(|constant\(/.test(prev)) {
            ok = true;
            break;
          }
        }
        if (!ok) bad.push(`${relative(ROOT, f)}:${i + 1}  ${line.trim()}`);
      }
    }
    expect(
      bad,
      "这些声明只有 env() 那一份 —— 不认它的内核上整条失效，"
        + "贴底的条会停在静态位置、占位块会塌成 0：\n  "
        + bad.join("\n  "),
    ).toEqual([]);
  });

  it("★★★ 内联样式（<script> 里）不许出现 env(safe-area) —— 它没法降级", () => {
    const bad: string[] = [];
    for (const f of FILES.filter((p) => p.endsWith(".vue"))) {
      const src = readFileSync(f, "utf8");
      const s = src.indexOf("<script");
      const e = src.indexOf("</script>");
      if (s < 0 || e < 0) continue;
      const script = src.slice(s, e);
      for (const [i, line] of script.split("\n").entries()) {
        // 注释里提它是可以的 —— 这条守卫自己的说明就在注释里
        const code = line.replace(/\/\/.*$/, "").replace(/^\s*\*.*$/, "");
        if (SAFE_AREA.test(code)) {
          bad.push(`${relative(ROOT, f)} 的 script 第 ${i + 1} 行：${line.trim()}`);
        }
      }
    }
    expect(
      bad,
      "内联样式是一次性求值的字符串，env() 失效就是整条失效（没有覆盖可言）。"
        + "把安全区拆成单独一个属性放进 CSS：\n  " + bad.join("\n  "),
    ).toEqual([]);
  });
});
