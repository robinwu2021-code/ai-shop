import { describe, expect, it } from "vitest";
import { formatMoney, parseAmount } from "./money";

describe("金额", () => {
  it("★★★ 小数位来自服务端；给不出就不显示（不兜底成 2 位）", () => {
    expect(formatMoney(1990, "CNY", 2, "zh-CN")).toContain("19.90");
    expect(formatMoney(150, "TWD", 0, "zh-TW")).toContain("150");
    expect(formatMoney(1990, "CNY", null, "zh-CN")).toBeNull();
  });

  it("★★ 阿拉伯语里数字仍用拉丁数字", () => {
    expect(formatMoney(1990, "AED", 2, "ar")).toMatch(/19\.90/);
  });

  it("★★★ 顾客输入：小数位超出、非数字、零与负数都拒；阿拉伯数字可输", () => {
    expect(parseAmount("12.5", 2)).toBe(1250);
    expect(parseAmount("12.345", 2)).toBeNull();
    expect(parseAmount("12.5", 0)).toBeNull();
    expect(parseAmount("150", 0)).toBe(150);
    expect(parseAmount("0", 2)).toBeNull();
    expect(parseAmount("-1", 2)).toBeNull();
    expect(parseAmount("abc", 2)).toBeNull();
    expect(parseAmount("١٢٫", 2)).toBeNull();
    expect(parseAmount("١٢", 2)).toBe(1200);
  });
});
