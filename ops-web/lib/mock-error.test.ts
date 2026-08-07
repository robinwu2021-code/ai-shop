// mock 层报错的守卫。
//
// **背景**：mock 的规则报错原先一律是中文。界面切到 EN 后，页面文案是英文、
// 错误提示还是中文 —— 而错误提示恰恰是用户最需要看懂的那句话。
// 全站 250 多处改成 `fail(zh, en)` 之后，这三条断言负责让它回不去。
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;
const MOCKS = join(ROOT, "lib/api/mocks");
const CJK = /[一-龥]/;

/** 去掉注释，只留代码 —— 注释里当然可以写中文，本仓的注释就是中文的。 */
function codeOf(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((l) => l.replace(/\/\/.*$/, ""))
    .join("\n");
}

const files = readdirSync(MOCKS).filter((f) => f.endsWith(".ts") && !f.endsWith(".test.ts"));

describe("mock 层报错", () => {
  it("有 mock 文件可扫（否则下面两条是空转）", () => {
    expect(files.length).toBeGreaterThan(10);
  });

  it.each(files)("%s：不许再用 throw new Error —— 一律走 fail(zh, en) / notFound()", (f) => {
    const code = codeOf(readFileSync(join(MOCKS, f), "utf8"));
    const hits = code.split("\n")
      .map((l, i) => ({ l: l.trim(), i: i + 1 }))
      .filter((x) => /throw new Error\(/.test(x.l));
    expect(hits.map((x) => `${x.i}: ${x.l.slice(0, 72)}`), `改用 fail("中文", "English")：`).toEqual([]);
  });

  it.each(files)("%s：fail() 的第二个参数里不许有汉字（漏译最常见的样子）", (f) => {
    const src = readFileSync(join(MOCKS, f), "utf8");
    const offenders: string[] = [];
    // fail( 之后的两个参数：模板串或普通串。第二个参数里出现汉字 = 复制粘贴漏译
    for (const m of src.matchAll(/\bfail\(\s*([\s\S]*?)\n?\s*\)[;,]/g)) {
      const args = m[1];
      // 按顶层逗号切两段：参数内部有 ${...}（可能含逗号），所以要跳过模板插值
      let depth = 0, cut = -1;
      for (let i = 0; i < args.length; i++) {
        const ch = args[i];
        if (ch === "{" || ch === "(" || ch === "[") depth++;
        else if (ch === "}" || ch === ")" || ch === "]") depth--;
        else if (ch === "," && depth === 0) { cut = i; break; }
      }
      if (cut < 0) continue;
      const en = args.slice(cut + 1);
      // 模板插值里的表达式（如 ${sku.title.zh}）不算文案，先剔掉
      const enText = en.replace(/\$\{[^}]*\}/g, "");
      if (CJK.test(enText)) offenders.push(en.trim().slice(0, 72));
    }
    expect(offenders, `这些 fail() 的英文还带汉字：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("assertTransition 的调用都带了英文实体名（否则状态机报错还是半中文）", () => {
    const offenders: string[] = [];
    for (const f of files) {
      const code = codeOf(readFileSync(join(MOCKS, f), "utf8"));
      for (const m of code.matchAll(/assertTransition\(([^;]*?)\)/g)) {
        // 期望 5 个参数：table, from, to, zhEntity, enEntity
        const commas = m[1].split(",").length;
        if (commas < 5) offenders.push(`${f}: ${m[0].slice(0, 72)}`);
      }
    }
    expect(offenders, `补上英文实体名：\n${offenders.join("\n")}`).toEqual([]);
  });
});
