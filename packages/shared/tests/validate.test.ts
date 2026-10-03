// 输入校验的判据。**这一份的价值全在反例** ——
// 正例谁写都对，而这套判据此前放行的恰恰是那几个反例。
import { describe, expect, it } from "vitest";
import {
  digitsOnly, isEmail, isMoney, isNonNegativeInt, isOtp, isPhone, isPositiveInt, notBlank,
} from "../src/utils/validate";

describe("手机号", () => {
  it("★★★ 只查长度会放行 00000000000 —— 那正是收编前 7 处各写一份的判据", () => {
    expect(isPhone("00000000000")).toBe(false);   // 11 位，但不是手机号
    expect(isPhone("12345678901")).toBe(false);   // 12 号段不存在
    expect(isPhone("13800138000")).toBe(true);
    expect(isPhone("19912345678")).toBe(true);
  });
  it("长度与空白", () => {
    expect(isPhone("1380013800")).toBe(false);    // 10 位
    expect(isPhone("138001380000")).toBe(false);  // 12 位
    expect(isPhone(" 13800138000 ")).toBe(true);  // 首尾空白不算错
    expect(isPhone("")).toBe(false);
    expect(isPhone(undefined)).toBe(false);
  });
});

describe("非空", () => {
  it("★ 全是空格不算填了 —— `!!x` 会把它当成填了", () => {
    expect(notBlank("   ")).toBe(false);
    expect(notBlank("")).toBe(false);
    expect(notBlank(null)).toBe(false);
    expect(notBlank(" a ")).toBe(true);
  });
});

describe("整数", () => {
  it("★ 小数不算正整数 —— parseInt 会把 1.5 悄悄变成 1", () => {
    expect(isPositiveInt("1.5")).toBe(false);
    expect(isPositiveInt("1e3")).toBe(false);
    expect(isPositiveInt("0")).toBe(false);
    expect(isPositiveInt("-1")).toBe(false);
    expect(isPositiveInt("012")).toBe(false);     // 前导零
    expect(isPositiveInt("12")).toBe(true);
  });
  it("库存可以是 0", () => {
    expect(isNonNegativeInt("0")).toBe(true);
    expect(isNonNegativeInt("-1")).toBe(false);
  });
});

describe("金额", () => {
  it("★ 最多两位小数，不接受负数与前导零", () => {
    expect(isMoney("0")).toBe(true);
    expect(isMoney("0.5")).toBe(true);
    expect(isMoney("12.34")).toBe(true);
    expect(isMoney("12.345")).toBe(false);
    expect(isMoney("-1")).toBe(false);
    expect(isMoney("01")).toBe(false);
    expect(isMoney(".5")).toBe(false);
  });
});

describe("验证码与邮箱", () => {
  it("6 位数字", () => {
    expect(isOtp("123456")).toBe(true);
    expect(isOtp("12345")).toBe(false);
    expect(isOtp("12345a")).toBe(false);
  });
  it("邮箱**故意宽松**：目的是挡手滑，收不收得到只有服务端知道", () => {
    expect(isEmail("a@b.cn")).toBe(true);
    expect(isEmail("a@b")).toBe(false);
    expect(isEmail("a b@c.cn")).toBe(false);
  });
});

describe("只留数字", () => {
  it("★ 粘贴进来的手机号常带空格或 - —— 在输入时清掉，而不是提交时报错", () => {
    expect(digitsOnly("138 0013 8000")).toBe("13800138000");
    expect(digitsOnly("138-0013-8000")).toBe("13800138000");
    expect(digitsOnly("+86 13800138000")).toBe("8613800138000");
  });
});
