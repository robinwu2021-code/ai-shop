// 评价治理规则测试（P-13.1）。
import { beforeEach, describe, expect, it } from "vitest";
import { reviewMock } from "@/lib/api/mocks/review";
import { SCORE_WEIGHT_TOTAL } from "@/lib/constants";
import { reviewAppeals, reviews, scoreConfig } from "./review";

const R0 = JSON.parse(JSON.stringify(reviews)) as typeof reviews;
const A0 = JSON.parse(JSON.stringify(reviewAppeals)) as typeof reviewAppeals;
const S0 = { ...scoreConfig };
beforeEach(() => {
  reviews.length = 0; reviews.push(...(JSON.parse(JSON.stringify(R0)) as typeof reviews));
  reviewAppeals.length = 0; reviewAppeals.push(...(JSON.parse(JSON.stringify(A0)) as typeof reviewAppeals));
  Object.assign(scoreConfig, S0);
});

describe("评价审核", () => {
  it("下架必须带原因（用户与商家都会看到）", async () => {
    await expect(reviewMock.decideReview("RV9001", false)).rejects.toThrow(/原因/);
  });

  it("通过后清空历史原因", async () => {
    const r = await reviewMock.decideReview("RV9001", true);
    expect(r.status).toBe("PASSED");
    expect(r.reason).toBeUndefined();
  });

  it("已处理的评价不能重复裁决", async () => {
    await expect(reviewMock.decideReview("RV9005", true)).rejects.toThrow(/已处理/);
  });

  it("刷评信号筛选只出命中的（信号是线索，不自动处置）", async () => {
    const page = await reviewMock.listReviews({ risky: "1", status: "", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((r) => r.riskFlags.length > 0)).toBe(true);
    // 命中信号的评价**仍然是待审**，没有被自动下架
    expect(page.records.some((r) => r.status === "PENDING")).toBe(true);
  });
});

describe("申诉裁决（P-13.1.3）", () => {
  it("裁决说明必填 —— 「已读不处理」不是一种结果", async () => {
    await expect(reviewMock.decideAppeal("AP9001", true, "  ")).rejects.toThrow(/裁决说明/);
  });

  it("申诉成立会真的把原评价下架（不能只改申诉单）", async () => {
    await reviewMock.decideAppeal("AP9001", true, "商家已全额退款并补发，差评描述与事实不符");
    const page = await reviewMock.listReviews({ status: "REJECTED", size: 100 });
    const r = page.records.find((x) => x.reviewNo === "RV9002");
    expect(r?.status).toBe("REJECTED");
    expect(r?.reason).toContain("申诉成立");
  });

  it("驳回申诉不动原评价", async () => {
    await reviewMock.decideAppeal("AP9001", false, "证据不足以说明评价失实");
    const page = await reviewMock.listReviews({ status: "PENDING", size: 100 });
    expect(page.records.some((x) => x.reviewNo === "RV9002")).toBe(true);
  });

  it("已裁决的申诉不能重复处理", async () => {
    await expect(reviewMock.decideAppeal("AP9002", true, "再判一次")).rejects.toThrow(/已裁决/);
  });
});

describe("评分算法参数（P-13.1.4）", () => {
  it("三维权重之和必须为 100", async () => {
    await expect(
      reviewMock.saveScoreConfig({ weightProduct: 50, weightFulfill: 30, weightService: 30, newMerchantProtectDays: 30, decayHalfLifeDays: 90 }),
    ).rejects.toThrow(new RegExp(String(SCORE_WEIGHT_TOTAL)));
  });

  it("保护期不能为负、半衰期至少 1 天", async () => {
    await expect(
      reviewMock.saveScoreConfig({ weightProduct: 50, weightFulfill: 30, weightService: 20, newMerchantProtectDays: -1, decayHalfLifeDays: 90 }),
    ).rejects.toThrow(/保护期/);
    await expect(
      reviewMock.saveScoreConfig({ weightProduct: 50, weightFulfill: 30, weightService: 20, newMerchantProtectDays: 0, decayHalfLifeDays: 0 }),
    ).rejects.toThrow(/半衰期/);
  });

  it("合法参数落库并留痕", async () => {
    await reviewMock.saveScoreConfig({ weightProduct: 40, weightFulfill: 40, weightService: 20, newMerchantProtectDays: 14, decayHalfLifeDays: 60 });
    const c = await reviewMock.getScoreConfig();
    expect(c.weightFulfill).toBe(40);
    expect(c.updatedBy).toBeTruthy();
  });
});
