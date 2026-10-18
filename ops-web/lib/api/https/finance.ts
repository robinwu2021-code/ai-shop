// 覆盖范围：分账结算（P-12.1）。
import { client } from "../http-client";
import type { FinanceApi } from "../contracts/finance";
import type {
  PurchaseInvoice,
  BuyerInvoiceRequest, Settlement,
  Page, PayoutAccount, PayoutList, SettleStatRow,
  BankFlowImportResult,
} from "@/lib/types";

export const financeHttp: FinanceApi = {
  orderPointsIntegration: (q) => client.get("/ops/points/order-integration", q),
  changeOrderPointsIntegration: (v) => client.post("/ops/points/order-integration", v),
  inspectOrderPoints: (subOrderNo) => client.get("/ops/points/order-integration/inspection", { subOrderNo }),
  manualOrderPointsRefunds: (storeNo, after, limit) => client.get("/ops/points/order-integration/refunds/manual", { storeNo, after, limit }),
  closeOrderPointsRefund: (afterSaleNo, reason) => client.post(`/ops/points/order-integration/refunds/${encodeURIComponent(afterSaleNo)}/close`, { reason }),
  retryOrderPointsRefund: (afterSaleNo, reason) => client.post(`/ops/points/order-integration/refunds/${encodeURIComponent(afterSaleNo)}/retry`, { reason }),
  pointsOverview: (market, typeCode) => client.get("/ops/points/overview", { market: market ?? "CN", typeCode }),
  pointsReceivables: (q) => client.get("/ops/points/receivables", q),
  pointsTypes: () => client.get("/ops/points/types"),
  createPointsType: (v) => client.post("/ops/points/types", v),
  transitionPointsType: (v) => client.post("/ops/points/type-state", v),
  pointsFallback: (typeCode) => client.get("/ops/points/fallback-rule", { typeCode }),
  savePointsFallback: (v) => client.post("/ops/points/fallback-rule", v),
  pointsSwitches: (q) => client.get("/ops/points/switches", q),
  changePointsSwitch: (v) => client.post("/ops/points/switches", v),
  pointsTerms: (typeCode) => client.get("/ops/points/terms", { typeCode }),
  publishPointsTerms: (v) => client.post("/ops/points/terms", v),
  pointsAgreements: (q) => client.get("/ops/points/agreements", q),
  pointsRequests: (q) => client.get("/ops/points/requests", q),
  submitPointsRequest: (v) => client.post("/ops/points/requests", v),
  reviewPointsRequest: (v) => client.post("/ops/points/request-review", v),
  pointsPlans: (q) => client.get("/ops/points/plans", q),
  savePointsPlan: (v) => client.post("/ops/points/plans", v),
  generatePointsPeriods: (v) => client.post("/ops/points/plan-generate", v),
  declarePointsReceipt: (v) => client.post("/ops/points/plan-receipt", v),
  cancelPointsPeriod: (v) => client.post("/ops/points/plan-cancel", v),
  executePointsWithdrawal: (v) => client.post("/ops/points/withdrawal-execute", v),
  pointsStatements: (q) => client.get("/ops/points/statements", q),
  draftPointsStatements: (v) => client.post("/ops/points/statement-draft", v),
  changePointsStatement: (v) => client.post("/ops/points/statement-change", v),
  pointsRefund: (q) => client.get("/ops/points/refund", q),
  revisePointsRefund: (v) => client.post("/ops/points/refund-revise", v),
  preparePointsReceivable: (v) => client.post("/ops/points/receivable-recovery", v),
  pointsFunds: (q) => client.get("/ops/points/funds", q),
  recoverPointsFunds: (v) => client.post("/ops/points/funds-recovery", v),
  pointsEvents: (q) => client.get("/ops/points/events", q),
  retryPointsEvent: (v) => client.post("/ops/points/event-retry", v),
  pointsAccount: (q) => client.get("/ops/points/account", q),
  freezePointsAccount: (v) => client.post("/ops/points/account-freeze", v),
  pointsVouchers: (q) => client.get("/ops/points/vouchers", q),
  pointsEntries: (q) => client.get("/ops/points/entries", q),
  pointsFlows: (q) => client.get("/ops/points/flows", q),
  pointsPosition: (q) => client.get("/ops/points/position", q),
  pointsStats: (q) => client.get("/ops/points/stats", q),
  rebuildPointsStats: (v) => client.post("/ops/points/stats-rebuild", v),
  pointsReconciliation: (q) => client.get("/ops/points/reconciliation", q),
  reconcilePoints: (v) => client.post("/ops/points/reconcile", v),
  // ── 自营应付账款（后端十个端点早已实现，此前运营端零入口）
  listPayables: (q) => client.get<Settlement[]>("/ops/payables", q),
  confirmPayable: (settleNo) => client.post<Settlement>(`/ops/payables/${settleNo}/confirm`),
  payPayable: (settleNo, paymentRef) =>
    client.post<Settlement>(`/ops/payables/${settleNo}/paid`, { paymentRef }),
  markNoInvoice: (settleNo, reason) =>
    client.post<Settlement>(`/ops/payables/${settleNo}/no-invoice`, { reason }),

  // ── 供应商收款账户（V358）
  listPayoutAccounts: (q) => client.get<Page<PayoutAccount>>("/ops/payout-accounts", q),
  auditPayoutAccount: (accountNo, pass, remark) =>
    client.post<PayoutAccount>(`/ops/payout-accounts/${accountNo}/audit`, { pass, remark }),

  // ── 经营统计（结算口径）
  listSettleStats: (q) => client.get<SettleStatRow[]>("/ops/settle-stats", q),

  // ── 付款清单（带明文账号，后端每次调用写 critical 审计）
  payoutList: (entityNo) =>
    client.get<PayoutList>("/ops/payables/payout-list", entityNo ? { entityNo } : undefined),

  // ── 银行流水导入（V363）。文本贴上来，解析在服务端
  importBankFlows: (fileName, csv) =>
    client.post<BankFlowImportResult>("/ops/payables/bank-flows/import", { fileName, csv }),

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
  listInvoiceRequests: (q) => client.get("/ops/finance/invoices", q),
  issueInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/issue`, v),
  rejectInvoice: (v) => client.post(`/ops/finance/invoices/${v.invoiceNo}/reject`, v),
  getInvoiceTitle: () => client.get("/ops/finance/invoice-title"),
  saveInvoiceTitle: (v) => client.post("/ops/finance/invoice-title", v),
  getTaxRule: () => client.get("/ops/finance/tax-rule"),
  saveTaxRule: (v) => client.put("/ops/finance/tax-rule", v),
  listPayChannels: () => client.get("/ops/settle/pay-channels"),
  updatePayChannel: (channel, v) => client.put(`/ops/settle/pay-channels/${channel}`, v),
  addPayChannelRate: (channel, v) => client.post(`/ops/settle/pay-channels/${channel}/rates`, v),
  listSettleBatches: (q) => client.get("/ops/settle-batches", q),
  approveSettleBatch: (batchNo, remark) =>
    client.post(`/ops/settle-batches/${batchNo}/approve`, { remark }),
  holdSettleBatch: (batchNo, remark) =>
    client.post(`/ops/settle-batches/${batchNo}/hold`, { remark }),
  releaseSettleBatch: (batchNo) =>
    client.post(`/ops/settle-batches/${batchNo}/release`),
  // ── 放款记录（V391）
  listPayouts: (q) => client.get("/ops/payouts", q),
  payPayout: (payoutNo, paymentRef) =>
    client.post(`/ops/payouts/${payoutNo}/paid`, { paymentRef }),
  failPayout: (payoutNo, reason) =>
    client.post(`/ops/payouts/${payoutNo}/fail`, { reason }),
  merchantDebt: (entityNo) => client.get(`/ops/debts/${entityNo}`),
  offsetDebtByDeposit: (entityNo, amountMinor, reason, requestNo) =>
    client.post(`/ops/debts/${entityNo}/deposit-offset`, { amountMinor, reason, requestNo }),
};
