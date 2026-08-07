// 覆盖范围：分账结算（P-12.1）。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";

export const financeHttp: FinanceApi = {
  listSettlements: (q) => client.get("/ops/settlements", q),
  executeSplit: (no) => client.post(`/ops/settlements/${no}/split`),
  freezeBackSettlement: (no) => client.post(`/ops/settlements/${no}/freeze-back`),
  listSplitRecords: (q) => client.get("/ops/split-records", q),
  listRefundSplitBacks: () => client.get("/ops/refund-split-backs"),
  executeRefundSplitBack: (asNo) => client.post(`/ops/refund-split-backs/${asNo}/execute`),
  getFeeRule: () => client.get("/ops/fee-rule"),
  saveFeeRule: (v) => client.post("/ops/fee-rule", v),
  listWithdrawals: (q) => client.get("/ops/finance/withdrawals", q),
  decideWithdrawal: (v) => client.post(`/ops/finance/withdrawals/${v.withdrawNo}/decide`, v),
  listInvoiceRequests: (q) => client.get("/ops/finance/invoices", q),
  issueInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/issue`, v),
  rejectInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/reject`, v),
  getTaxRule: () => client.get("/ops/finance/tax-rule"),
  saveTaxRule: (v) => client.put("/ops/finance/tax-rule", v),
};
