import { describe, expect, it } from "vitest";
import type { AfterSale, Order } from "@shared/types";
import { refundProgressKey, canWithdrawAfterSale, fulfillmentAllowed } from "@shared/strategies/order-phase1";
import { beginMockRefund, setMockRefundResult } from "@shared/mock/refund";
const sale = (execution = {}): AfterSale => ({ status: "REFUNDING", refundExecution: { status: "PROCESSING", resourceStatus: "PENDING", ...execution } } as AfterSale);
describe("基础退款真实进度", () => {
  it("受理、失败、未知和人工处理不冒充到账", () => {
    expect(refundProgressKey(sale())).toBe("afterSale.execution.processing");
    expect(refundProgressKey(sale({ status: "FAILED" }))).toBe("afterSale.execution.failed");
    expect(refundProgressKey(sale({ status: "UNKNOWN" }))).toBe("afterSale.execution.unknown");
    expect(refundProgressKey(sale({ errorCode: "REVERSE_UNCONFIRMED" }))).toBe("afterSale.execution.manual");
  });
  it("现金成功和库存优惠恢复分开展示", () => {
    expect(refundProgressKey(sale({ status: "SUCCEEDED" }))).toBe("afterSale.execution.resourcesPending");
    expect(refundProgressKey(sale({ status: "SUCCEEDED", resourceStatus: "FAILED" }))).toBe("afterSale.execution.resourcesFailed");
    expect(refundProgressKey(sale({ status: "SUCCEEDED", resourceStatus: "DONE" }))).toBe("afterSale.execution.done");
  });
  it("仅允许未进入退款或退货执行的申请撤销", () => {
    for (const status of ["APPLIED", "NEGOTIATING", "WAIT_RETURN", "REJECTED"]) expect(canWithdrawAfterSale({ status } as AfterSale)).toBe(true);
    for (const status of ["REFUNDING", "RETURNING", "REFUNDED"]) expect(canWithdrawAfterSale({ status } as AfterSale)).toBe(false);
  });
  it("模拟受理不自动到账；显式回执也保留资源进度", () => {
    const order = { status: "PAID", afterSale: { afterSaleNo: "AF1" } } as Order;
    beginMockRefund(order);
    expect(order.status).toBe("PAID");
    setMockRefundResult(order, "UNKNOWN");
    expect(refundProgressKey(order.afterSale!)).toBe("afterSale.execution.unknown");
    setMockRefundResult(order, "SUCCEEDED");
    expect(refundProgressKey(order.afterSale!)).toBe("afterSale.execution.resourcesPending");
  });
});
describe("付款、权限、售后履约守卫", () => {
  it("已付款有权限才允许，活动售后和支付异常均阻断", () => {
    const order = { status: "PAID" } as Order;
    expect(fulfillmentAllowed(order, true)).toBe(true);
    expect(fulfillmentAllowed(order, false)).toBe(false);
    expect(fulfillmentAllowed({ ...order, status: "WAIT_PAY" }, true)).toBe(false);
    expect(fulfillmentAllowed({ ...order, afterSale: { status: "APPLIED" } as AfterSale }, true)).toBe(false);
    expect(fulfillmentAllowed({ ...order, paymentResolution: { status: "MANUAL_REQUIRED" } as Order["paymentResolution"] }, true)).toBe(false);
  });
});
