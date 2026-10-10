/**
 * 评价页：**点了没反应永远是缺陷**。
 *
 * 此前 `submit()` 里 `if (!canSubmit) return;` 静默吞掉，用户敲了几个字
 * 点提交没动静，只能怀疑功能坏了。2026-09-28 用户报"评价页面无法提交"
 * 定位就是这个：内容 <5 字时按钮 disabled + submit 静默 return。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const raw = readFileSync(resolve(__dirname, "../src/pages/review-write/index.vue"), "utf-8");
const code = raw.replace(/<!--[\s\S]*?-->/g, "").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");

describe("评价页点了要有反应", () => {
  it("★★★ 内容不足 5 字时 toast 提示（不是静默 return）", () => {
    // 断言必须落在 submit 函数体里 —— 别处引用 tooShort 不算数
    const at = code.indexOf("async function submit()");
    const body = code.slice(at, at + 900);
    expect(body, "submit 里少了字数不足的 toast → 又变回静默 return")
      .toContain('review.tooShort');
    expect(body, "submit 里少了订单没取到的 toast")
      .toContain('review.orderMissing');
    expect(body, "submit 里必须要有 showToast 调用（不能只保留字符串常量）")
      .toContain('uni.showToast');
  });

  it("★★★ 页面上直接告诉用户「还差几个字」—— 不要等到点按钮才发现", () => {
    // 字数计数下面有一行 <5 时才显示的红字提示
    expect(raw).toMatch(/content\.trim\(\)\.length < 5[^\n]*(\n[^\n]*){0,3}review\.tooShort/);
  });

  it("★★★ submitting=true 期间直接返（防连击），别再显示 toast 干扰", () => {
    const at = code.indexOf("async function submit()");
    const body = code.slice(at, at + 500);
    // 顺序：先判 submitting → 再判其它
    expect(body.indexOf("submitting.value")).toBeLessThan(body.indexOf("orderMissing"));
  });
});
