import type { AfterSale } from "./types";
export function canRecoverRefund(sale: AfterSale): boolean {
  const e = sale.refundExecution;
  return !!e && !(e.status === "SUCCEEDED" && e.resourceStatus === "DONE");
}
export function refundProgress(sale: AfterSale) {
  const e = sale.refundExecution;
  if (!e) return "refundUnstarted";
  if (e.errorCode === "REVERSE_UNCONFIRMED" || e.status === "MANUAL_REQUIRED") return "refundManual";
  if (e.status === "SUCCEEDED") {
    if (e.resourceStatus === "DONE") return "refundDone";
    return e.resourceErrorCode && e.resourceErrorCode !== "STOCK_RESTORE_PENDING" ? "refundResourcesFailed" : "refundResourcesPending";
  }
  if (e.status === "FAILED") return "refundFailed";
  if (e.status === "UNKNOWN") return "refundUnknown";
  return "refundProcessing";
}
