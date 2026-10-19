import type { AfterSale } from "./types";
export function canRecoverRefund(sale: AfterSale): boolean {
  const e = sale.refundExecution;
  return !!e && (e.closureVersion === 2 && e.closureStatus === "PENDING"
    || !(e.status === "SUCCEEDED" && ["DONE", "MANAGED_SEPARATELY"].includes(e.resourceStatus)));
}
export function refundProgress(sale: AfterSale) {
  const e = sale.refundExecution;
  if (!e) return "refundUnstarted";
  if (e.errorCode === "REVERSE_UNCONFIRMED" || e.status === "MANUAL_REQUIRED") return "refundManual";
  if (e.status === "FAILED") return "refundFailed";
  if (e.status === "UNKNOWN") return "refundUnknown";
  if (e.closureVersion === 2 && ["CONFIRMED", "NOT_REQUIRED"].includes(e.cashStatus ?? "")) {
    if (e.closureStatus === "DONE" && e.benefitsStatus === "SETTLED") return "refundBenefitsDone";
    if (e.closureStatus === "PENDING") return e.cashStatus === "NOT_REQUIRED" ? "refundZeroBenefitsPending" : "refundBenefitsPending";
  }
  if (e.status === "SUCCEEDED") {
    if (e.resourceStatus === "DONE") return "refundDone";
    return e.resourceErrorCode && e.resourceErrorCode !== "STOCK_RESTORE_PENDING" ? "refundResourcesFailed" : "refundResourcesPending";
  }
  return "refundProcessing";
}
