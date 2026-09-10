import { describe, expect, it } from "vitest";
import { planPrefill } from "@/utils/cert-prefill";
import type { CertRecognition } from "@shared/types";

/**
 * 证照识别结果 → 表单预填。
 *
 * <p>五条规则各有一次真实代价，都不是类型能挡住的：认不出要让他手填而不是
 * 报错、不能覆盖他打过的字、「长期」不是日期、UNKNOWN 不算类型不符。
 */
const cert = (over: Partial<CertRecognition> = {}): CertRecognition => ({
  recognized: true,
  docType: "BUSINESS_LICENSE",
  side: null,
  name: "示例商贸有限公司",
  code: "91330100MA2XXXXX1A",
  legalForm: "有限责任公司",
  person: "张三",
  address: "杭州",
  issuedAt: "2020-03-15",
  validTo: "2030-03-14",
  confidence: 0.93,
  ...over,
});
const blank = { qualNumber: "", expireAt: "" };

describe("证照识别的预填规则", () => {
  it("认出来了就把空着的编号与有效期填上", () => {
    const p = planPrefill(cert(), blank, "BUSINESS_LICENSE");
    expect(p.qualNumber).toBe("91330100MA2XXXXX1A");
    expect(p.expireAt).toBe("2030-03-14");
    expect(p.typeMismatch).toBe(false);
  });

  it("没认出来什么都不动 —— 那是「让他手填」，不是错误", () => {
    const p = planPrefill(cert({ recognized: false, code: "别用我" }), blank, "BUSINESS_LICENSE");
    expect(p).toEqual({ qualNumber: null, expireAt: null, typeMismatch: false });
  });

  it("不覆盖他已经打过的字", () => {
    /*
     * 这一条是整组里最要紧的。覆盖的表现是「我明明填对了，传完图它自己变了」，
     * 而他多半不会发现 —— 提交之后才是错的，且看不出是谁改的。
     */
    const p = planPrefill(cert(), { qualNumber: "我自己抄的号", expireAt: "2029-01-01" },
      "BUSINESS_LICENSE");
    expect(p.qualNumber).toBeNull();
    expect(p.expireAt).toBeNull();
  });

  it("「长期」不写进有效期 —— 那一栏空着就表示长期有效", () => {
    /*
     * 照抄字面量的话，Date.parse("长期") 是 NaN，落库是个坏时间戳，
     * 而过期扫描会把它当成已过期 —— 一张长期有效的证被标成过期。
     */
    expect(planPrefill(cert({ validTo: "长期" }), blank, "BUSINESS_LICENSE").expireAt).toBeNull();
  });

  it("认不出格式的日期也不填", () => {
    for (const bad of ["2030年3月", "2030-3-4", "", "长期有效"]) {
      expect(planPrefill(cert({ validTo: bad }), blank, "BUSINESS_LICENSE").expireAt).toBeNull();
    }
  });

  it("认出来是别的证就提醒", () => {
    expect(planPrefill(cert({ docType: "ID_CARD" }), blank, "BUSINESS_LICENSE").typeMismatch)
      .toBe(true);
  });

  it("UNKNOWN 不算不符 —— 否则每张模型没把握的证都弹一次，提醒就成了噪音", () => {
    expect(planPrefill(cert({ docType: "UNKNOWN" }), blank, "BUSINESS_LICENSE").typeMismatch)
      .toBe(false);
  });

  it("类型不符时该填的仍然算出来 —— 拦不拦是页面的事，不是这里的事", () => {
    // 反过来写（不符就整个不填）会让「提醒 + 他确认没错」之后什么也没填上
    const p = planPrefill(cert({ docType: "ID_CARD" }), blank, "BUSINESS_LICENSE");
    expect(p.qualNumber).toBe("91330100MA2XXXXX1A");
  });
});
