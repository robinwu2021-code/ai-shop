import { afterEach, describe, expect, it } from "vitest";
import { db } from "@shared/mock/db";
import type { Order } from "@shared/types";
import { orderMock } from "@/api/mocks/order";
const saved = db.orders;
afterEach(() => { db.orders = saved; });
function seed(type: "REFUND_ONLY" | "RETURN_REFUND", status = "APPLIED") {
  const order = structuredClone(saved[0]!) as Order;
  order.status = "PAID";
  order.afterSale = { afterSaleNo: "AS-PHASE1", orderNo: "PAY1", subOrderNo: order.orderNo,
    type, status: status as "APPLIED", reason: "退货", images: [], refundMinor: 1000,
    fulfillStage: "NOT_PREPARED", updatedAt: Date.now() };
  db.orders = [order];
  return order;
}
describe("售后 mock 不把受理当成到账", () => {
  it("仅退款同意后保持订单原状态，退款处理中", async () => {
    const order = seed("REFUND_ONLY");
    const result = await orderMock.mApproveAfterSale("AS-PHASE1", "同意");
    expect(result.status).toBe("REFUNDING");
    expect(result.refundExecution?.status).toBe("PROCESSING");
    expect(order.status).toBe("PAID");
  });
  it("退货审核后等待寄回", async () => {
    seed("RETURN_REFUND");
    expect((await orderMock.mApproveAfterSale("AS-PHASE1", "同意")).status).toBe("WAIT_RETURN");
  });
  it("确认收妥后仍需等待现金成功", async () => {
    const order = seed("RETURN_REFUND", "RETURNING");
    const result = await orderMock.mConfirmReturn("AS-PHASE1", {
      receiptNo: "RR-AS-PHASE1", lines: [{ itemId: 1, receivedQty: 1, quality: "UNSALEABLE" }],
    });
    expect(result.status).toBe("REFUNDING");
    expect(result.refundExecution?.succeededAt).toBeNull();
    expect(order.status).toBe("PAID");
  });
  it("积分退款请求被拒绝", async () => {
    seed("REFUND_ONLY");
    await expect(orderMock.mApproveAfterSale("AS-PHASE1", "同意", undefined, "POINTS")).rejects.toThrow();
  });
});
