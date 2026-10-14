/**
 * 中文子集的覆盖守卫。
 *
 * 子集是按页面真实文案算的 —— **改一句文案就可能多出一个没收进去的字**。
 * 正常路径上 `prebuild` 会重跑子集，这条断言是给不正常的路径准备的：
 * 有人直接 `next build`、有人只跑测试、CI 缓存了产物。
 *
 * 失败时的动作只有一个：`npm run fonts`。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { collectChars } from "../scripts/subset-fonts.mjs";

const SITE = join(import.meta.dirname, "..");

type Coverage = { count: number; chars: string };

function coverage(): Coverage {
  return JSON.parse(readFileSync(join(SITE, "fonts/coverage.json"), "utf8")) as Coverage;
}

describe("中文子集覆盖", () => {
  it("源码里的每个中文字都在子集里", () => {
    const have = new Set(coverage().chars);
    const missing = collectChars().filter((c: string) => !have.has(c));
    expect(
      missing.join(""),
      `子集缺 ${missing.length} 个字：${missing.join("")}\n跑 \`npm run fonts\` 重新生成`,
    ).toBe("");
  });

  it("两档字重的文件都在", () => {
    for (const w of [400, 600]) {
      const p = join(SITE, `public/fonts/hx-sc-${w}.woff2`);
      expect(() => readFileSync(p), `缺 ${p} —— 跑 \`npm run fonts\``).not.toThrow();
    }
  });

  /** @font-face 与产物文件名对不上时，页面静默回退系统字体，肉眼很难发现 */
  it("globals.css 引的就是产出的那两个文件", () => {
    const css = readFileSync(join(SITE, "app/globals.css"), "utf8");
    for (const w of [400, 600]) {
      expect(css, `globals.css 没引用 hx-sc-${w}.woff2`).toContain(`/fonts/hx-sc-${w}.woff2`);
    }
  });
});
