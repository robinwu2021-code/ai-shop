/**
 * 运营端色调的可读性：`--*-text` 压在 `--*-subtle` 上必须达 WCAG AA（4.5:1）。
 *
 * **为什么单独立一条，而不是并进 design-tokens 那组**：那一组守的是 b-app/c-app
 * 的皮肤（`.sh-root.skin-*.mode-*` 里的 `--sh-*`）；运营端是另一套体系
 *（Tailwind/shadcn 的 `--green-solid/-subtle/-text` 三层），两边的变量名与结构都不同。
 *
 * **这条检查此前只有一个入口：`/dev/ui` 的「全量对比度」按钮。**
 * 也就是说只有人打开那一页并点一下才会跑 —— 2026-08-29 查下来，从来没跑过。
 * 而它跑不跑得起来还取决于浏览器：那个扫描靠 `requestAnimationFrame` 换肤等两帧，
 * 在不合成帧的环境里（无头、隐藏面板）**一帧都不跳，扫描停在 0/10**，
 * 而按钮显示的是「进行中」—— 一个不会失败、也不会完成的检查。
 *
 * 改成静态判据的依据是当场量出来的：这四对色调**不随皮肤变**
 *（`data-theme` 切 mono/promo/blue，`--success-ink` 一直是 #05663a），
 * 只随明暗变。既然如此，CSS 文本里就读得到，不需要浏览器。
 *
 * 首次实测（浏览器里量的，与本判据同口径）：
 *   浅色 success 6.38 / warning 7.07 / destructive 7.62 / info 8.07
 *   深色 success 10.31 / warning 10.62 / destructive 8.81 / info 9.01
 * 全部达标 —— 这条闸门立起来是**守住现状**，不是修问题。
 *
 * ⚠️ `--primary-ink` 不在这里：它是 `color-mix(in oklch, …)` 派生的，
 * CSS 文本里读不到值，而且**没有配对的 `--primary-tint`** —— 它压在什么底上
 * 要看调用点。那一条仍然只能在浏览器里量。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const CSS = readFileSync(join(import.meta.dirname, "../../../ops-web/app/globals.css"), "utf8");

/** 四个色调各自的「文字 / 浅底」原色对 */
const PAIRS = [
  { tone: "success", text: "--green-text", subtle: "--green-subtle" },
  { tone: "warning", text: "--amber-text", subtle: "--amber-subtle" },
  { tone: "destructive", text: "--red-text", subtle: "--red-subtle" },
  { tone: "info", text: "--blue-text", subtle: "--blue-subtle" },
] as const;

function luminance(hex: string): number {
  const h = hex.replace("#", "");
  const ch = [0, 2, 4].map((i) => {
    const c = parseInt(h.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0]! + 0.7152 * ch[1]! + 0.0722 * ch[2]!;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

/**
 * 取某个模式下变量的字面值。
 * 浅色在 `:root`，深色在 `.dark` —— **深色块里没有的沿用浅色**，与 CSS 的层叠一致。
 */
function varOf(mode: "light" | "dark", name: string): string {
  const blocks = mode === "dark" ? [/\n\.dark\s*\{([\s\S]*?)\n\}/] : [];
  blocks.push(/\n:root\s*\{([\s\S]*?)\n\}/);
  for (const re of blocks) {
    const b = CSS.match(re);
    if (!b) continue;
    const m = b[1]!.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})\\s*;`));
    if (m) return m[1]!;
  }
  throw new Error(`${mode} 模式下取不到 ${name}`);
}

describe("运营端色调对比度：可读性是硬约束", () => {
  it("★★★ 每个色调的文字压在自己的浅底上达 WCAG AA（4.5:1）", () => {
    const bad: string[] = [];
    for (const mode of ["light", "dark"] as const) {
      for (const p of PAIRS) {
        const text = varOf(mode, p.text);
        const subtle = varOf(mode, p.subtle);
        const r = contrast(text, subtle);
        if (r < 4.5) bad.push(`${mode} ${p.tone}: ${text} on ${subtle} = ${r.toFixed(2)}`);
      }
    }
    expect(bad, `这些色调读不清：\n  ${bad.join("\n  ")}`).toEqual([]);
  });

  it("★★ 四个色调的三层原色都在（少一层不会报错，只会让那个 tone 退回继承色）", () => {
    const missing: string[] = [];
    for (const mode of ["light", "dark"] as const) {
      for (const p of PAIRS) {
        for (const n of [p.text, p.subtle]) {
          try {
            varOf(mode, n);
          } catch {
            missing.push(`${mode} ${n}`);
          }
        }
      }
    }
    expect(missing, `缺这些变量：${missing.join(", ")}`).toEqual([]);
  });
});
