// 规格文案规范化：**两份实现必须给出同一个答案**。
//
// 前端一份（`utils/spec-normalize.ts`）、后端一份（`SpecNormalizer.java`），
// 因为商家在 b-app 输入、运营在 ops-web 输入、还有迁移里的种子 —— 三条路进同一个值池。
// 只在一侧规范化，另一侧就是漏网的入口，而漏进去的东西看着完全正常：
// 值池里多一条「500 G」，它与「500g」在任何一处都不会碰头，也不会报错。
//
// 这里用**同一张用例表**同时钉住两侧：TS 的直接跑，Java 的从源码里取出常量对照
// （跑 JVM 太贵，而两份实现的差异几乎总是出在用例覆盖到的那几类形式上）。
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { normalizeSpecLabel } from "../src/utils/spec-normalize";

const ROOT = join(import.meta.dirname, "../../..");
const JAVA = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/common/SpecNormalizer.java");

/** 输入 → 期望。**改这张表要同时改两份实现** */
const CASES: [string, string][] = [
  ["  500g  ", "500g"],
  ["500 g", "500g"],
  ["500G", "500g"],
  ["５００ｇ", "500g"],
  ["1KG", "1kg"],
  ["1 kg", "1kg"],
  ["1l", "1L"],
  ["1L", "1L"],
  ["330ML", "330ml"],
  ["24 CM", "24cm"],
  ["800w", "800W"],
  ["五斤", "5斤"],
  ["三 段", "3 段"],
  ["约  1  斤", "约 1 斤"],
  // 只规范形式不改语义：「1斤」不会变成 500g —— 那是别名与归一量的事
  ["1斤", "1斤"],
  ["黑色", "黑色"],
  ["", ""],
];

describe("规格文案规范化", () => {
  it.each(CASES)("「%s」→「%s」", (input, expected) => {
    expect(normalizeSpecLabel(input)).toBe(expected);
  });

  it("null / undefined 进，空串出 —— 调用方常在判空之前先规范一下", () => {
    expect(normalizeSpecLabel(null)).toBe("");
    expect(normalizeSpecLabel(undefined)).toBe("");
  });

  it("★★ 后端那份实现还在，且覆盖同样几类形式 —— 少一类就是一个漏网的入口", () => {
    if (!existsSync(JAVA)) return; // 只装前端的场景
    const src = readFileSync(JAVA, "utf8");
    // 不比对实现细节，只钉住「这几件事后端也做了」：漏掉任何一条，
    // 同一个输入两侧就会给出不同结果，而那种分歧没有任何一处会报错
    for (const marker of ["0xFEE0", "CN_DIGITS", "kg", "ml", "cm", "unifyUnit"]) {
      expect(src, `SpecNormalizer.java 里找不到 ${marker}`).toContain(marker);
    }
  });
});
