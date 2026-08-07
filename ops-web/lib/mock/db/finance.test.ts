// 结算与资金规则测试（P-12.1）。本域的价值在**跨域收口**，所以断言也要跨域：
// 读商家的报备状态、清售后的回退标记。
import { beforeEach, describe, expect, it } from "vitest";
import { financeMock } from "@/lib/api/mocks/finance";
import { afterSaleMock } from "@/lib/api/mocks/aftersale";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { feeRule, settlements } from "./finance";
import { afterSales } from "./aftersale";
import { merchants } from "./merchant";

const S0 = JSON.parse(JSON.stringify(settlements)) as typeof settlements;
const A0 = JSON.parse(JSON.stringify(afterSales)) as typeof afterSales;
const M0 = JSON.parse(JSON.stringify(merchants)) as typeof merchants;
const R0 = { ...feeRule, byTrafficSource: { ...feeRule.byTrafficSource } };
beforeEach(() => {
  settlements.length = 0; settlements.push(...(JSON.parse(JSON.stringify(S0)) as typeof settlements));
  afterSales.length = 0; afterSales.push(...(JSON.parse(JSON.stringify(A0)) as typeof afterSales));
  merchants.length = 0; merchants.push(...(JSON.parse(JSON.stringify(M0)) as typeof merchants));
  Object.assign(feeRule, JSON.parse(JSON.stringify(R0)));
});

describe("分账执行（P-12.1.3）", () => {
  it("未报备分账接收方的商家不能分账（跨域读商家档案，ADR-002）", async () => {
    // ST9004 属于 M901，其 settleAccountReady = false
    await expect(financeMock.executeSplit("ST9004")).rejects.toThrow(/尚未报备/);
  });

  it("商家补报备后即可分账", async () => {
    merchants.find((m) => m.merchantNo === "M901")!.settleAccountReady = true;
    const s = await financeMock.executeSplit("ST9004");
    expect(s.status).toBe("SPLIT");
  });

  it("对账不平的结算单拒绝分账（gross ≠ 佣金 + 服务费 + 实付）", async () => {
    const s = settlements.find((x) => x.settleNo === "ST9001")!;
    s.netAmount += 1; // 人为制造 1 分钱的差
    await expect(financeMock.executeSplit("ST9001")).rejects.toThrow(/对账不平/);
  });

  it("失败可重试，重试次数累加", async () => {
    const s = await financeMock.executeSplit("ST9003"); // 原 retryCount = 2
    expect(s.retryCount).toBe(3);
    expect(s.status).toBe("SPLIT");
  });

  it("重试到上限后转人工，不再无限重试", async () => {
    const s = settlements.find((x) => x.settleNo === "ST9003")!;
    s.retryCount = MAX_SPLIT_RETRY;
    await expect(financeMock.executeSplit("ST9003")).rejects.toThrow(/上限/);
  });

  it("已分账的单是终态", async () => {
    await expect(financeMock.executeSplit("ST9002")).rejects.toThrow(/不允许/);
  });
});

describe("超时兜底（P-12.1.4）", () => {
  it("冻结未达阈值不能解冻回平台", async () => {
    await expect(financeMock.freezeBackSettlement("ST9001")).rejects.toThrow(/未达兜底阈值/);
  });

  it("超过阈值可解冻回平台", async () => {
    // ST9005 冻结自 2026-07-01，已超过 15 天
    const s = await financeMock.freezeBackSettlement("ST9005");
    expect(s.status).toBe("FROZEN_BACK");
  });
});

describe("退款回退分账（P-12.1.5 / E4）—— 跨域收口", () => {
  it("队列由售后单的 refundSplitPending 派生，不另建实体", async () => {
    await afterSaleMock.decideAfterSale({
      asNo: "AS9001", liability: "MERCHANT",
      share: { platform: 0, merchant: 100, pickup: 0 },
      verdict: "坏果属实", amount: 2_290,
    });
    const list = await financeMock.listRefundSplitBacks();
    expect(list.some((a) => a.asNo === "AS9001")).toBe(true);
  });

  it("执行回退后清除标记，队列自然消掉（不清就会被反复执行）", async () => {
    await afterSaleMock.decideAfterSale({
      asNo: "AS9001", liability: "MERCHANT",
      share: { platform: 0, merchant: 100, pickup: 0 },
      verdict: "坏果属实", amount: 2_290,
    });
    const a = await financeMock.executeRefundSplitBack("AS9001");
    expect(a.refundSplitPending).toBe(false);
    expect(a.status).toBe("REFUNDED");
    const list = await financeMock.listRefundSplitBacks();
    expect(list.some((x) => x.asNo === "AS9001")).toBe(false);
  });

  it("没有待回退标记的单不能执行", async () => {
    await expect(financeMock.executeRefundSplitBack("AS9002")).rejects.toThrow(/没有待回退/);
  });
});

describe("费率配置（P-12.1.7 / 12.1.8）", () => {
  it("费率必须在 0–10000 万分比之间", async () => {
    await expect(
      financeMock.saveFeeRule({
        byTrafficSource: { MERCHANT_OWNED: 0, PLATFORM: 20_000, INVITE: 300, CHANNEL: 500 },
        pickupServiceFeeRate: 150, freezeDays: 15,
      }),
    ).rejects.toThrow(/0–10000/);
  });

  it(`超时兜底天数至少 ${SETTLE_FREEZE_MIN_DAYS} 天（太短会把还在重试的单提前收走）`, async () => {
    await expect(
      financeMock.saveFeeRule({
        byTrafficSource: { MERCHANT_OWNED: 0, PLATFORM: 500, INVITE: 300, CHANNEL: 500 },
        pickupServiceFeeRate: 150, freezeDays: 1,
      }),
    ).rejects.toThrow(new RegExp(String(SETTLE_FREEZE_MIN_DAYS)));
  });

  it("合法配置落库", async () => {
    await financeMock.saveFeeRule({
      byTrafficSource: { MERCHANT_OWNED: 0, PLATFORM: 400, INVITE: 200, CHANNEL: 400 },
      pickupServiceFeeRate: 120, freezeDays: 20,
    });
    const r = await financeMock.getFeeRule();
    expect(r.byTrafficSource.PLATFORM).toBe(400);
    expect(r.freezeDays).toBe(20);
    // R16 建议：自带客流零佣金
    expect(r.byTrafficSource.MERCHANT_OWNED).toBe(0);
  });
});
