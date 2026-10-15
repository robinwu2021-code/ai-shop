import type { Order } from "../types";

/** 本地 mock 仅记录受理；到账事实必须由独立的模拟回执提供。 */
export function beginMockRefund(order: Order): void {
  const sale = order.afterSale;
  if (!sale) throw new Error("售后单不存在");
  sale.status = "REFUNDING";
  sale.refundExecution ??= {
    executionNo: `MOCK-RF-${sale.afterSaleNo}`, paymentNo: null,
    status: "PROCESSING", phase: "CASH", errorCode: null,
    resourceStatus: "PENDING", resourceErrorCode: null, succeededAt: null,
  };
}

/** 开发样例/测试显式注入模拟回执；审核接口不会自动调用它。 */
export function setMockRefundResult(order: Order, status: "SUCCEEDED" | "FAILED" | "UNKNOWN",
  resourcesDone = false): void {
  const execution = order.afterSale?.refundExecution;
  if (!execution) throw new Error("退款尚未受理");
  execution.status = status;
  execution.errorCode = status === "SUCCEEDED" ? null : `MOCK_REFUND_${status}`;
  if (status === "SUCCEEDED") {
    execution.succeededAt ??= Date.now();
    execution.resourceStatus = resourcesDone ? "DONE" : "PENDING";
    order.afterSale!.status = "REFUNDED";
    order.status = "REFUNDED";
  }
}
