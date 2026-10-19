import type { AfterSale, Order } from "../types";

/** 资金到账与库存/优惠恢复是两件事，保留未知和人工处理出口。 */
export function refundProgressKey(sale: AfterSale): string {
  const e = sale.refundExecution;
  if (!e) return `afterSale.statusHint.${sale.status}`;
  if (e.errorCode === "REVERSE_UNCONFIRMED" || e.status === "MANUAL_REQUIRED") return "afterSale.execution.manual";
  if (e.status === "SUCCEEDED") {
    if (e.resourceStatus === "DONE") return "afterSale.execution.done";
    if (["FAILED", "ERROR"].includes(e.resourceStatus) || (e.resourceErrorCode && e.resourceErrorCode !== "STOCK_RESTORE_PENDING")) return "afterSale.execution.resourcesFailed";
    return "afterSale.execution.resourcesPending";
  }
  if (e.status === "FAILED") return "afterSale.execution.failed";
  if (e.status === "UNKNOWN") return "afterSale.execution.unknown";
  return "afterSale.execution.processing";
}

export function canWithdrawAfterSale(sale: AfterSale): boolean {
  return ["APPLIED", "NEGOTIATING", "WAIT_RETURN", "REJECTED"].includes(sale.status);
}

/** 仅作页面动作守卫；接口仍校验付款、权限与当前售后。 */
export function fulfillmentAllowed(order: Order | null, permitted: boolean): boolean {
  return !!order && permitted && order.status === "PAID" && !order.paymentResolution
    && (!order.afterSale || ["CLOSED", "REJECTED"].includes(order.afterSale.status));
}
