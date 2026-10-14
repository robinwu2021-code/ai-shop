// 结算与资金规则测试（P-12.1）。本域的价值在**跨域收口**，所以断言也要跨域：
// 读商家的报备状态、清售后的回退标记。
//
// ⚠️ 「退款回退分账」队列读写的 `refundSplitPending`/`share` 是 finance 域私有字段——
// 售后裁决（`afterSaleMock.decideAfterSale`）真实后端没有这两个字段，不会去设它们。
// 这里直接摆好 fixture 状态来测 finance 侧的队列逻辑，不借售后裁决当"种数据"的旁路。
import { beforeEach, describe, expect, it } from "vitest";
import { financeMock } from "@/lib/api/mocks/finance";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { feeRules, settlements } from "./finance";
import { afterSales } from "./aftersale";
import { merchants } from "./merchant";

const S0 = JSON.parse(JSON.stringify(settlements)) as typeof settlements;
const A0 = JSON.parse(JSON.stringify(afterSales)) as typeof afterSales;
const M0 = JSON.parse(JSON.stringify(merchants)) as typeof merchants;
const R0 = JSON.parse(JSON.stringify(feeRules)) as typeof feeRules;
beforeEach(() => {
  settlements.length = 0; settlements.push(...(JSON.parse(JSON.stringify(S0)) as typeof settlements));
  afterSales.length = 0; afterSales.push(...(JSON.parse(JSON.stringify(A0)) as typeof afterSales));
  merchants.length = 0; merchants.push(...(JSON.parse(JSON.stringify(M0)) as typeof merchants));
  // 费率是**只增**的，用例之间会互相污染 —— 每个用例前整表还原
  feeRules.length = 0; feeRules.push(...(JSON.parse(JSON.stringify(R0)) as typeof feeRules));
});

describe("退款回退分账（P-12.1.5 / E4）—— 跨域收口", () => {
  it("队列由售后单的 refundSplitPending 派生，不另建实体", async () => {
    const a = afterSales.find((x) => x.afterSaleNo === "AS9001")!;
    a.liability = "MERCHANT";
    a.share = { platform: 0, merchant: 100, pickup: 0 };
    a.refundSplitPending = true;
    const list = await financeMock.listRefundSplitBacks();
    expect(list.some((x) => x.afterSaleNo === "AS9001")).toBe(true);
  });

  it("执行回退后清除标记，队列自然消掉（不清就会被反复执行）", async () => {
    const a0 = afterSales.find((x) => x.afterSaleNo === "AS9001")!;
    a0.liability = "MERCHANT";
    a0.share = { platform: 0, merchant: 100, pickup: 0 };
    a0.refundSplitPending = true;

    const a = await financeMock.executeRefundSplitBack("AS9001");
    expect(a.refundSplitPending).toBe(false);
    expect(a.status).toBe("REFUNDED");
    const list = await financeMock.listRefundSplitBacks();
    expect(list.some((x) => x.afterSaleNo === "AS9001")).toBe(false);
  });

  it("没有待回退标记的单不能执行", async () => {
    await expect(financeMock.executeRefundSplitBack("AS9002")).rejects.toThrow(/没有待回退/);
  });
});

/*
 * 分账执行与超时兜底的用例删掉了：它们测的是 executeSplit / freezeBackSettlement，
 * 而**后端有意不提供这两个端点** —— 分账的下发与回退有它们自己的触发路径
 * （结算生成、售后退款），在运营台放一个「立即分账」按钮等于给人一个
 * 绕过状态机的口子，而这条链路动的是真钱。
 *
 * 留着这些用例会让人以为那两个动作还在，只是暂时没接。
 */
describe("费率版本（只增不改）", () => {
  it("费率必须在 0–10000 万分比之间", async () => {
    await expect(
      financeMock.addFeeRule({ businessMode: "THIRD_PARTY", trafficSource: "PLATFORM", rateBp: 20_000 }),
    ).rejects.toThrow(/0–10000/);
  });

  it("★ 时点回查：调价之后，问「调价之前」仍拿到旧费率", async () => {
    const before = Date.now();
    await financeMock.addFeeRule({
      businessMode: "THIRD_PARTY", trafficSource: "PLATFORM", rateBp: 800,
      effectiveFrom: before + 1000,
    });

    // 这正是「原地改一个数」做不到、才要做成版本的理由
    expect((await financeMock.effectiveFeeRates(before))["THIRD_PARTY|PLATFORM"]).toBe(500);
    expect((await financeMock.effectiveFeeRates(before + 2000))["THIRD_PARTY|PLATFORM"]).toBe(800);
  });

  it("★ 预约生效：填未来时刻，当下不受影响", async () => {
    const now = Date.now();
    await financeMock.addFeeRule({
      businessMode: "SELF_OPERATED", trafficSource: "MERCHANT_OWNED", rateBp: 300,
      effectiveFrom: now + 86_400_000,
    });

    expect((await financeMock.effectiveFeeRates(now))["SELF_OPERATED|MERCHANT_OWNED"]).toBe(0);
  });

  it("R16 建议：自带客流零佣金", async () => {
    expect((await financeMock.effectiveFeeRates(1))["THIRD_PARTY|MERCHANT_OWNED"]).toBe(0);
  });
});
