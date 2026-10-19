import { describe, expect, it } from "vitest";
import { baseAmount } from "@shared/strategies/pricing/types";

const items = [{ price: 100, qty: 1 }] as Parameters<typeof baseAmount>[0];
const context = { fulfillment: "STORE_PICKUP", currency: "CNY", usePoints: 20 } as Parameters<typeof baseAmount>[1];

describe("积分金额只采用服务端报价", () => {
  it("未拿到报价时本地估价保持全额现金", () => {
    const amount = baseAmount(items, context, 0);
    expect(amount.pointsUsed).toBe(0);
    expect(amount.pointsDeductMinor).toBe(0);
    expect(amount.payableMinor).toBe(100);
  });

  it("20 积分按服务端 2:1 报价抵 10 个最小货币单位", () => {
    const amount = baseAmount(items, { ...context, pointsQuote: {
      pointsUsed: 20, deductMinor: 10, ratePoints: 2, rateMinor: 1, version: 3,
    } }, 0);
    expect(amount.pointsUsed).toBe(20);
    expect(amount.pointsDeductMinor).toBe(10);
    expect(amount.payableMinor).toBe(90);
  });
});
