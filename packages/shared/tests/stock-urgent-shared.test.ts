// 「有人在等」的那几项，两页必须同源。
//
// 起因是一次真实的分叉：工作台那张卡与库存页顶部本是同一块东西的前缀与全文，
// 而它们各写了一份 —— 加「在途」时只加了库存页，加「继续盘点」时只加了工作台，
// 于是**两边各缺对方一半，而两边都不报错**。看起来都对，只有并排看才发现。
//
// 这条守卫拦的是回潮：谁再在这两页里自己算一次 inTransitCount / openCountNo，
// 就等于又开了一份，而下一次加第三种紧急项时它一定跟不上。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const PAGES = ["home", "stock"] as const;
const HELPER = "urgentStockItems";

describe("紧急项两页同源", () => {
  it("有东西可查", () => {
    const src = readFileSync(join(ROOT, "b-app/src/shared/stock-urgent.ts"), "utf8");
    expect(src).toContain(`export function ${HELPER}`);
  });

  it("★★ 两页都用同一个 urgentStockItems，不各算一份", () => {
    for (const p of PAGES) {
      const src = readFileSync(join(ROOT, `b-app/src/pages/${p}/index.vue`), "utf8");
      expect(src, `${p} 没有用 ${HELPER}`).toContain(HELPER);
    }
  });

  it("★★ 两页里不许再自己读 inTransitCount / openCountNo", () => {
    const bad: string[] = [];
    for (const p of PAGES) {
      const src = readFileSync(join(ROOT, `b-app/src/pages/${p}/index.vue`), "utf8");
      const [script] = src.split("</script>");
      for (const [i, line] of script.split("\n").entries()) {
        const s = line.trim();
        if (s.startsWith("*") || s.startsWith("//")) continue;
        if (/\b(inTransitCount|openCountNo)\b/.test(s)) {
          bad.push(`b-app/src/pages/${p}/index.vue:${i + 1}  ${s}`);
        }
      }
    }
    expect(
      bad,
      "这两页在自己读进销存的紧急字段。\n" +
        `→ 改成用 ${HELPER}（b-app/src/shared/stock-urgent.ts）。\n` +
        "  各读一份的后果不是报错，是两边各缺对方一半 —— 而两边看起来都对：\n",
    ).toEqual([]);
  });
});
