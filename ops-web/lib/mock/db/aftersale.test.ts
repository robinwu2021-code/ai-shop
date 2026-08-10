// 售后规则测试（P-6.1）。最关键的一条是**跨域校验**：退款不能超过订单实付。
import { beforeEach, describe, expect, it } from "vitest";
import { afterSaleMock } from "@/lib/api/mocks/aftersale";
import { LIABILITY_SHARE_TOTAL } from "@/lib/constants";
import { afterSales, fastRefundRule } from "./aftersale";
import { orders } from "./order";

const A0 = JSON.parse(JSON.stringify(afterSales)) as typeof afterSales;
const R0 = { ...fastRefundRule };
beforeEach(() => {
  afterSales.length = 0; afterSales.push(...(JSON.parse(JSON.stringify(A0)) as typeof afterSales));
  Object.assign(fastRefundRule, R0);
});

const share100 = { platform: 0, merchant: 100, pickup: 0 };

describe("平台介入裁决（P-6.1.3 / 6.1.4）", () => {
  it("退款金额不得超过订单实付（跨域查订单）", async () => {
    const order = orders.find((o) => o.orderNo === "SO2026080506")!;
    await expect(
      afterSaleMock.decideAfterSale({
        asNo: "AS9001", liability: "MERCHANT", share: share100,
        verdict: "坏果属实", amount: order.payAmount + 1,
      }),
    ).rejects.toThrow(/不得超过订单实付/);
  });

  it("赔付比例之和必须为 100", async () => {
    await expect(
      afterSaleMock.decideAfterSale({
        asNo: "AS9001", liability: "MERCHANT",
        share: { platform: 30, merchant: 30, pickup: 30 },
        verdict: "坏果属实", amount: 2_290,
      }),
    ).rejects.toThrow(new RegExp(String(LIABILITY_SHARE_TOTAL)));
  });

  it("裁决说明必填", async () => {
    await expect(
      afterSaleMock.decideAfterSale({ asNo: "AS9001", liability: "MERCHANT", share: share100, verdict: "  ", amount: 2_290 }),
    ).rejects.toThrow(/裁决说明/);
  });

  it("合法裁决落库：责任、比例、金额、状态，并打上分账待办标记", async () => {
    const a = await afterSaleMock.decideAfterSale({
      asNo: "AS9001", liability: "MERCHANT", share: share100,
      verdict: "坏果 2 个属实，按商品单价全额退", amount: 2_290,
    });
    expect(a.status).toBe("REFUNDING");
    expect(a.liability).toBe("MERCHANT");
    expect(a.share).toEqual(share100);
    // E4 未接：必须留标记而不是假装已完成
    expect(a.refundSplitPending).toBe(true);
  });

  it("已退款的单不能再裁决", async () => {
    await expect(
      afterSaleMock.decideAfterSale({ asNo: "AS9005", liability: "PLATFORM", share: { platform: 100, merchant: 0, pickup: 0 }, verdict: "重判", amount: 100 }),
    ).rejects.toThrow(/已结束/);
  });
});

describe("状态机", () => {
  it("驳回不是终点 —— 用户可把争议上升到平台", async () => {
    const a = await afterSaleMock.setAfterSaleStatus("AS9004", "ARBITRATING");
    expect(a.status).toBe("ARBITRATING");
  });

  /*
   * ⚠️ 这条用例原本断言「申请不能直接跳到已退款」，而后端**刻意允许**这一跳 ——
   * 极速退命中阈值时自动通过，商家只可见不可拒（AfterSaleVO.instant）。
   * 旧断言编码的是 ops-web 自己那套 mock 模型，从没被真实状态机检验过。
   *
   * 换成一条真正不允许的：终态不可复活。
   */
  it("已退款是终态，不能再改回处理中", async () => {
    await expect(afterSaleMock.setAfterSaleStatus("AS9005", "REFUNDING")).rejects.toThrow(/不允许/);
  });
});

describe("平台介入队列与极速退阈值", () => {
  it("intervene=1 只出平台介入的单", async () => {
    const page = await afterSaleMock.listAfterSales({ intervene: "1", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((a) => a.status === "ARBITRATING")).toBe(true);
  });

  it("金额上限必须大于 0", async () => {
    await expect(
      afterSaleMock.saveFastRefundRule({ enabled: true, maxAmount: 0, withinHours: 24, categories: [] }),
    ).rejects.toThrow(/大于 0/);
  });

  it("时限不能为 0（等于关掉极速退，但开关还显示已启用）", async () => {
    await expect(
      afterSaleMock.saveFastRefundRule({ enabled: true, maxAmount: 2000, withinHours: 0, categories: [] }),
    ).rejects.toThrow(/时限/);
  });

  it("合法配置落库", async () => {
    await afterSaleMock.saveFastRefundRule({ enabled: false, maxAmount: 5_000, withinHours: 48, categories: ["FRESH"] });
    const r = await afterSaleMock.getFastRefundRule();
    expect(r).toMatchObject({ enabled: false, maxAmount: 5_000, withinHours: 48 });
  });
});
