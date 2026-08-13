// 覆盖范围：分账结算（P-12.1）。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";

export const financeHttp: FinanceApi = {
  pointsOverview: (market) => client.get("/ops/points/overview", { market: market ?? "CN" }),
  listSettlements: (q) => client.get("/ops/settlements", q),
  listSplitRecords: (q) => client.get("/ops/split-records", q),
  listRefundSplitBacks: () => client.get("/ops/refund-split-backs"),
  executeRefundSplitBack: (asNo) => client.post(`/ops/refund-split-backs/${asNo}/execute`),
  listFeeRules: () => client.get("/ops/settle/fee-rules"),
  effectiveFeeRates: (at) => client.get("/ops/settle/fee-rules/effective", at ? { at } : undefined),
  addFeeRule: (v) => client.post("/ops/settle/fee-rules", v),
  listWithdrawals: (q) => client.get("/ops/finance/withdrawals", q),
  decideWithdrawal: (v) => client.post(`/ops/finance/withdrawals/${v.withdrawNo}/decide`, v),
  listInvoiceRequests: (q) => client.get("/ops/finance/invoices", q),
  issueInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/issue`, v),
  rejectInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/reject`, v),
  getTaxRule: () => client.get("/ops/finance/tax-rule"),
  saveTaxRule: (v) => client.put("/ops/finance/tax-rule", v),
};
