import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { db, findGoodsSeed } from "@shared/mock/db";
import { tradeMock } from "@/api/mocks/trade";
import type { Order } from "@shared/types";

const original = [...db.orders];
function child(no: string, minor: number): Order {
  const seed = structuredClone(original[0]!);
  return { ...seed, orderNo: no, payOrderNo: "PG-TEST", payGroupNo: "PG-TEST",
    status: "WAIT_PAY", fulfillment: "EXPRESS", groupNo: undefined,
    amount: { ...seed.amount, payableMinor: minor, paidMinor: 0, pointsUsed: 0 },
  } as Order;
}
beforeEach(() => { db.orders = [child("S1", 1000), child("S2", 2000)]; });
afterEach(() => { db.orders = [...original]; });

describe("mock与真实支付主单视角一致", () => {
  it("主单详情汇总金额，支付参数与结果使用同一合计", async () => {
    const view = await tradeMock.orderDetail("PG-TEST");
    expect(view.amount.payableMinor).toBe(3000);
    expect(view.subOrders).toHaveLength(2);
    const init = await tradeMock.payOrder("PG-TEST");
    expect(init.payParams.amount).toBe("3000");
    const result = await tradeMock.payResult("PG-TEST");
    expect(result.orderNo).toBe("PG-TEST");
    expect(result.amount.paidMinor).toBe(3000);
    expect(result.status).toBe("PAID");
  });

  it("主单号取消会取消整组且返回主单视角", async () => {
    const cancelled = await tradeMock.cancelOrder("PG-TEST");
    expect(cancelled.orderNo).toBe("PG-TEST");
    expect(cancelled.status).toBe("CANCELLED");
    expect(db.orders.map((o) => o.status)).toEqual(["CANCELLED", "CANCELLED"]);
    const item = db.orders[0]!.items[0]!;
    const sku = findGoodsSeed(item.goodsNo).skus.find((s) => s.skuNo === item.skuNo)!;
    const restored = sku.stock;
    await tradeMock.cancelOrder("PG-TEST");
    expect(sku.stock).toBe(restored);
  });
});
