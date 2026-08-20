/**
 * 品牌色在两处声明：`brand/tokens.json`（真源，由 build.py 生成）与
 * `site/app/globals.css` 的 `@theme`（Tailwind 需要它才能生成工具类）。
 *
 * 两处漂移的症状很隐蔽：品牌换了色，官网还是旧的，而没有任何东西会报错 ——
 * 页面照常渲染，只是颜色不对，要有人肉眼比对才发现。这组断言就是拦这个的。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../..");
const TOKENS = JSON.parse(readFileSync(join(ROOT, "brand/tokens.json"), "utf8")) as {
  brand: Record<string, { value: string; note: string }>;
};
const CSS = readFileSync(join(ROOT, "site/app/globals.css"), "utf8");

/** tokens.json 的键 → globals.css 的 @theme 变量名 */
const MAP: Record<string, string> = {
  red: "--color-brand",
  redDeep: "--color-brand-deep",
  redBright: "--color-brand-bright",
  ink: "--color-ink",
};

function themeValue(name: string): string | undefined {
  // 只认 @theme 块里的声明，避免把 @layer base 里的用法当成定义
  const block = CSS.match(/@theme\s*\{([\s\S]*?)\n\}/);
  const m = block?.[1]?.match(new RegExp(`${name}:\\s*([^;]+);`));
  return m?.[1]?.trim();
}

describe("品牌色：@theme 与 brand/tokens.json 同源", () => {
  it.each(Object.entries(MAP))("%s === %s", (key, cssVar) => {
    const truth = TOKENS.brand[key]?.value;
    expect(truth, `brand/tokens.json 里没有 ${key} —— 跑 python3 brand/build.py`).toBeTruthy();
    expect(
      themeValue(cssVar)?.toLowerCase(),
      `globals.css 的 ${cssVar} 与 tokens.json 的 ${key} 不一致 —— 真源是 tokens.json`,
    ).toBe(truth!.toLowerCase());
  });

  it("@theme 里不出现 tokens.json 之外的第二个红", () => {
    const block = CSS.match(/@theme\s*\{([\s\S]*?)\n\}/)?.[1] ?? "";
    const known = new Set(
      Object.values(TOKENS.brand).map((t) => t.value.toLowerCase()),
    );
    // 只看色值声明，且排除中性面（面板/线/浅红底不在 build.py 的产物里，是官网自有的）
    const OWN = new Set(["#63676e", "#f5f6f8", "#e5e7ea", "#d4d7dd", "#fdeceb"]);
    const reds = [...block.matchAll(/#[0-9a-f]{6}/gi)]
      .map((m) => m[0].toLowerCase())
      .filter((c) => !known.has(c) && !OWN.has(c));
    expect(reds, `@theme 里出现了未登记的色值：${reds.join(", ")}`).toEqual([]);
  });
});
