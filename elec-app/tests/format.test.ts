// 金额是**百万分之一元**（0402 电阻 ¥0.0015，按分存做不出来）。
// 输入框里的元 → E6、E6 → 显示，任何一边错一位，报出去的就是十倍的价。
import { describe, expect, it } from "vitest";
import { colLetter, e6Of, priceOf, subtotalOf, yuanOf } from "../src/shared/format";

describe("e6Of：输入框里的元 → 百万分之一元", () => {
  it("整数、两位、六位小数都按位换算，不走浮点", () => {
    expect(e6Of("6")).toBe(6_000_000);
    expect(e6Of("6.85")).toBe(6_850_000);
    expect(e6Of("0.0015")).toBe(1_500);
    // 浮点直接乘会错的两个（node 实测）：8.2 × 1e6 = 8199999.999999999、1.005 × 1e6 = 1004999.9999999999。
    // 截断存库就少了一个百万分之一元，拿它比价、算合计都会差 —— 必须按字符串拼
    expect(e6Of("8.2")).toBe(8_200_000);
    expect(e6Of("1.005")).toBe(1_005_000);
    expect(e6Of("0.000001")).toBe(1);
    expect(e6Of(" 9.8 ")).toBe(9_800_000);
  });

  it("认不出返回 null，**不是 0**（0 元是一个真实的价）", () => {
    expect(e6Of("")).toBeNull();
    expect(e6Of("abc")).toBeNull();
    expect(e6Of("6.1234567")).toBeNull(); // 超过六位小数：多半是粘错了，不替他截断
    expect(e6Of("-1")).toBeNull();
    expect(e6Of("1,000")).toBeNull();
    expect(e6Of("0")).toBe(0);
  });
});

describe("yuanOf / priceOf：E6 → 显示", () => {
  it("至少两位小数、最多六位，末尾的 0 去掉", () => {
    expect(yuanOf(6_850_000)).toBe("6.85");
    expect(yuanOf(6_000_000)).toBe("6.00");
    expect(yuanOf(1_500)).toBe("0.0015");
    expect(yuanOf(6_804_000)).toBe("6.804");
    expect(yuanOf(12_345_600_000)).toBe("12,345.60");
  });

  it("币种符号跟着币种走，空值写横杠而不是 ¥0", () => {
    expect(priceOf(6_300_000)).toBe("¥6.30");
    expect(priceOf(1_200_000, "USD")).toBe("$1.20");
    expect(priceOf(3_400_000, "HKD")).toBe("HK$3.40");
    expect(priceOf(null)).toBe("—");
  });

  it("e6Of 与 yuanOf 互逆（去掉千分位之后）", () => {
    for (const s of ["0.0015", "6.85", "123.456789"]) {
      expect(e6Of(yuanOf(e6Of(s)!).replace(/,/g, ""))).toBe(e6Of(s));
    }
  });
});

describe("subtotalOf：单价 × 数量 → 元", () => {
  it("两位小数、四舍五入到分", () => {
    expect(subtotalOf(6_720_000, 2_000)).toBe("13,440.00");
    expect(subtotalOf(1_500, 3)).toBe("0.00"); // 0.0045 元 → 分以下舍掉
    expect(subtotalOf(1_500, 4)).toBe("0.01"); // 0.006 元 → 进一分
  });
});

describe("colLetter：列序号 → Excel 列字母", () => {
  it("与 Excel 左上角那一排一致", () => {
    expect(colLetter(0)).toBe("A");
    expect(colLetter(25)).toBe("Z");
    expect(colLetter(26)).toBe("AA");
    expect(colLetter(27)).toBe("AB");
  });
});
