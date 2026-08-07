// 皮肤对比度守卫：**主按钮的文字必须在自己的主色底上过 WCAG AA（4.5:1）**。
//
// 这条是实测出来的血泪：白字压在亮色主色上根本不够 ——
// 生鲜绿 2.66:1、促销橙(原 #ff6010) 3.03:1、时尚蓝(原 #2f6bff) 4.50:1。
// 默认皮肤是黑白灰(18.75:1)，所以**平时完全看不出来**，换个皮肤才暴露。
//
// 为什么放单测而不是只靠 `/dev/ui` 的「全量对比度」按钮：那个要人去点，
// 而调色是最容易顺手改的东西。这里直接读 `app/globals.css` 里的色值算，
// 改一个 hex 就会红。
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const CSS = readFileSync(join(new URL("..", import.meta.url).pathname, "app/globals.css"), "utf8");

/** WCAG 相对亮度。只支持 #rrggbb —— 皮肤块里就是这一种写法，遇到别的直接报错而不是猜。 */
function luminance(hex: string): number {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`色值不是 #rrggbb：${hex}`);
  const ch = [0, 2, 4]
    .map((i) => parseInt(m[1].slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

function contrast(a: string, b: string): number {
  const [la, lb] = [luminance(a), luminance(b)];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** 从 globals.css 里抓出所有 `[data-theme="x"] { --primary: …; --primary-foreground: …; }` 单行块。 */
function skinPairs(): { sel: string; primary: string; fg: string }[] {
  const out: { sel: string; primary: string; fg: string }[] = [];
  const re = /(\.dark)?\[data-theme="(\w+)"\]\s*\{([^}]*)\}/g;
  for (const m of CSS.matchAll(re)) {
    const body = m[3];
    const primary = /--primary:\s*([^;]+);/.exec(body)?.[1];
    const fg = /--primary-foreground:\s*([^;]+);/.exec(body)?.[1];
    if (!primary || !fg) continue; // business 那种整套块单独在下面校验
    out.push({ sel: `${m[1] ?? ""}[data-theme="${m[2]}"]`, primary, fg });
  }
  return out;
}

describe("皮肤对比度", () => {
  it("每套皮肤的主按钮文字都过 AA（4.5:1）—— 白字压亮色主色是过不了的", () => {
    const pairs = skinPairs();
    // 少于 5 套说明正则没抓全，别让"零违规"变成假阴性
    expect(pairs.length, "没抓到皮肤块，正则或 CSS 结构变了").toBeGreaterThanOrEqual(5);

    const bad = pairs
      .map((p) => ({ ...p, ratio: contrast(p.primary, p.fg) }))
      .filter((p) => p.ratio < 4.5);

    expect(
      bad.map((b) => `${b.sel} ${b.primary} + ${b.fg} = ${b.ratio.toFixed(2)}:1`),
      "把该套皮肤的 --primary-foreground 换成能过 4.5 的那一档（白字或墨字）",
    ).toEqual([]);
  });

  it("business 是整套配色，也要一起验", () => {
    const block = /\[data-theme="business"\]\s*\{([\s\S]*?)\}/.exec(CSS)?.[1] ?? "";
    const primary = /--primary:\s*([^;]+);/.exec(block)?.[1];
    const fg = /--primary-foreground:\s*([^;]+);/.exec(block)?.[1];
    expect(primary, "business 块里没有 --primary").toBeTruthy();
    expect(contrast(primary!, fg!)).toBeGreaterThanOrEqual(4.5);
  });

  it("与 C 端 packages/shared 的皮肤色值一致（mono 是明确记录在案的例外）", () => {
    // 注释里一直写着"同名同色，改一处要改两处"，但实测对不上：
    // promo 用成了 C 端的 dark 值、blue 差一档。这条把口头约定变成断言。
    const C_END: Record<string, string> = {
      fresh: "#00b578",
      promo: "#d24600",
      blue: "#2c69ff",
    };
    const got = Object.fromEntries(
      skinPairs()
        .filter((p) => !p.sel.startsWith(".dark"))
        .map((p) => [/\[data-theme="(\w+)"\]/.exec(p.sel)![1], p.primary.trim().toLowerCase()]),
    );
    for (const [skin, hex] of Object.entries(C_END)) {
      expect(got[skin], `${skin} 与 packages/shared 的 SKIN_HEX 对不上`).toBe(hex);
    }
  });
});
