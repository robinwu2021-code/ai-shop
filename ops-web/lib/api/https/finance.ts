// 覆盖范围：分账结算（P-12.1）。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";
import type {
  PurchaseInvoice,
  BuyerInvoiceRequest, Settlement, ClientPointsPolicy } from "@/lib/types";

export const financeHttp: FinanceApi = {
  pointsOverview: (market) => client.get("/ops/points/overview", { market: market ?? "CN" }),
  pointsClientPolicy: () => client.get<ClientPointsPolicy>("/ops/points/client-policy"),
  savePointsClientPolicy: (v) => client.post<ClientPointsPolicy>("/ops/points/client-policy", v),
  // ── 自营应付账款（后端十个端点早已实现，此前运营端零入口）
  listPayables: (q) => client.get<Settlement[]>("/ops/payables", q),
  confirmPayable: (settleNo) => client.post<Settlement>(`/ops/payables/${settleNo}/confirm`),
  payPayable: (settleNo, paymentRef) =>
    client.post<Settlement>(`/ops/payables/${settleNo}/paid`, { paymentRef }),
  markNoInvoice: (settleNo, reason) =>
    client.post<Settlement>(`/ops/payables/${settleNo}/no-invoice`, { reason }),

  // ── 进项票
  listPurchaseInvoices: (q) => client.get<PurchaseInvoice[]>("/ops/purchase-invoices", q),
  verifyPurchaseInvoice: (invoiceNo) =>
    client.post<PurchaseInvoice>(`/ops/purchase-invoices/${invoiceNo}/verify`),
  rejectPurchaseInvoice: (invoiceNo, reason) =>
    client.post<PurchaseInvoice>(`/ops/purchase-invoices/${invoiceNo}/reject`, { reason }),

  // ── 买家开票申请
  listBuyerInvoiceRequests: (q) => client.get<BuyerInvoiceRequest[]>("/ops/invoice-requests", q),
  markBuyerInvoiceIssued: (requestNo, invoiceNo) =>
    client.post<BuyerInvoiceRequest>(`/ops/invoice-requests/${requestNo}/issued`, { invoiceNo }),
  rejectBuyerInvoiceRequest: (requestNo, reason) =>
    client.post<BuyerInvoiceRequest>(`/ops/invoice-requests/${requestNo}/reject`, { reason }),

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
  getInvoiceTitle: () => client.get("/ops/finance/invoice-title"),
  saveInvoiceTitle: (v) => client.post("/ops/finance/invoice-title", v),
  getTaxRule: () => client.get("/ops/finance/tax-rule"),
  saveTaxRule: (v) => client.put("/ops/finance/tax-rule", v),
};
