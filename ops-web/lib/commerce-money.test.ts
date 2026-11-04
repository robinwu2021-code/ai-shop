import { describe, expect, it } from "vitest";
import { addMoney, formatMoney, isMinor, moneyEquals, parseMajor, toMajorInput } from "./commerce-money";

describe("商城金额：整数字符串，不走浮点", () => {
  it("TWD 69900 = NT$699.00（两位小数，千分位）", () => {
    expect(formatMoney({ currency: "TWD", minor: "69900" })).toBe("NT$699.00");
    expect(formatMoney({ currency: "TWD", minor: "123456789" })).toBe("NT$1,234,567.89");
    expect(formatMoney({ currency: "TWD", minor: "5" })).toBe("NT$0.05");
    expect(formatMoney({ currency: "CNY", minor: "0" })).toBe("¥0.00");
    expect(formatMoney({ currency: "JPY", minor: "1200" })).toBe("JPY 1,200");
  });

  it("超过 2^53 的金额照样精确 —— number 在这里会失真", () => {
    expect(formatMoney({ currency: "TWD", minor: "9007199254740993" })).toBe("NT$90,071,992,547,409.93");
  });

  it("非法 minor（小数、负数、前导零）不渲染成看似正常的数字", () => {
    expect(formatMoney({ currency: "TWD", minor: "1.5" })).toBe("TWD ?1.5");
    expect(isMinor("0")).toBe(true);
    expect(isMinor("007")).toBe(false);
    expect(isMinor("-1")).toBe(false);
    expect(isMinor("12345678901234567890")).toBe(false); // 超过 19 位
  });

  it("输入 699 / 699.5 / 699.00 解析成 minor 字符串；多一位小数直接拒绝而不是四舍五入", () => {
    expect(parseMajor("699", "TWD")).toBe("69900");
    expect(parseMajor("699.5", "TWD")).toBe("69950");
    expect(parseMajor("699.00", "TWD")).toBe("69900");
    expect(parseMajor("0.01", "TWD")).toBe("1");
    expect(parseMajor("0", "TWD")).toBe("0");
    expect(parseMajor("1,000", "TWD")).toBe("100000");
    expect(parseMajor("699.001", "TWD")).toBeNull();
    expect(parseMajor("-1", "TWD")).toBeNull();
    expect(parseMajor("abc", "TWD")).toBeNull();
    expect(parseMajor("", "TWD")).toBeNull();
    expect(parseMajor("1200", "JPY")).toBe("1200");
    expect(parseMajor("12.5", "JPY")).toBeNull();
  });

  it("回填到输入框是 parse 的逆运算", () => {
    expect(toMajorInput({ currency: "TWD", minor: "69950" })).toBe("699.50");
    expect(parseMajor(toMajorInput({ currency: "TWD", minor: "1" }), "TWD")).toBe("1");
  });

  it("相加用整数；币种不同拒绝相加", () => {
    expect(addMoney({ currency: "TWD", minor: "9007199254740993" }, { currency: "TWD", minor: "7" }))
      .toEqual({ currency: "TWD", minor: "9007199254741000" });
    expect(() => addMoney({ currency: "TWD", minor: "1" }, { currency: "CNY", minor: "1" })).toThrow();
    expect(moneyEquals({ currency: "TWD", minor: "100" }, { currency: "TWD", minor: "100" })).toBe(true);
    expect(moneyEquals({ currency: "TWD", minor: "100" }, { currency: "CNY", minor: "100" })).toBe(false);
  });
});
