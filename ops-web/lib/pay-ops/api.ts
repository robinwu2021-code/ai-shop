// 支付中心各页签的接口层：一个接口，两种实现（HTTP / mock），按 NEXT_PUBLIC_USE_MOCK 切换（与 lib/api 同一个开关）。
// 路径、查询参数、命令体与 near-pay /pay-ops/v2 一一对应（TDD-支付中心运营页面 §2.6，契约核对见 §6）。
import type { PayOpsClient, PayPage } from "./client";
import type {
  AttemptListRow, ChannelCostRow, CommissionRuleRow, LedgerEntryRow, LedgerVoucher, PayoutRow, ReconDiffRow, SettlementRow,
  StoredValueAccountRow, StoredValueTransactionRow, WithdrawalRow,
  AlertEventRow, AlertRuleRow, AppDetail, AppGrant, AppRow, ApprovalFlowRow, ChannelCallDetail, ChannelCallRow, ChannelCallbackDetail, ChannelCallbackRow,
  CredentialIssued, OperatingSettings, ProfileRevisionDetail, RefundListRow, RequestLogDetail, RequestLogRow, TerminalRow,
  ActionResult, ApprovalDetail, ApprovalRow, ApprovalTask, ApprovalView, ChannelRow, CollectionPolicyView, DataScopeRow, Detail, EntryRow,
  ExceptionRow, MerchantDetail, MerchantRow, OnboardingDetail, OnboardingRow, OrderDetail, OrderLogEntry, OrderRow, ProductRow, StoreDetail, StoreRow,
} from "./types";

export type Q = Record<string, string | number | boolean | undefined>;
/** 通用状态命令体（near-pay `Command`）；requestNo 由客户端按请求体生成 */
export interface Cmd { expectedVersion: number; reason: string }

export interface ChannelSave {
  expectedVersion: number; channelCode: string; name: string; channelType: string; adapterCode: string;
  platformCredentialKeyId?: number | null; sortNo?: number | null; market: string | null; testChannel?: boolean;
  endpoints: { environment: string; baseUrl: string; callbackStyle: string | null }[]; reason: string;
}
export type ProductSave = Omit<ProductRow, "status" | "version" | "updatedAt" | "updatedBy"> & { expectedVersion: number; reason: string };
export interface EntrySave {
  expectedVersion: number; reason: string; name: string; merchantNo: string; storeNo: string; accessMethod: string; productCodes: string[];
  appNo?: string | null; allowedDomain?: string | null; sessionTimeoutSeconds?: number | null; clientAppIdentifier?: string | null; terminalNo?: string | null;
}
export interface ScopeSave {
  expectedVersion: number; scopeType: string; subjectNo: string; marketSet: string[]; entityNos: string[]; storeNos: string[];
  fundsAccess: string; expiresAt: number; reason: string;
}
export interface OnboardingMaterials { legalForm: string; contactName: string; contactPhone: string; licenses: string[]; settleAccountType: string; settleAccount: string }


export interface TerminalSave {
  expectedVersion: number; reason: string; name: string; merchantNo: string; storeNo: string; payChannel: string; payMerchantNo: string;
  channelTerminalNo: string; serialNo: string; model: string; connectionMode: string; productCodes: string[]; receiptName: string; remark: string; dailyRefundLimitMinor: number;
}
export interface AppCreate { name: string; purpose: string; ownerKind: string; merchantNo: string | null; environment?: string; scopes: string[]; notifyUrl: string; callbackDomains: string[]; ipWhitelist: string; rateLimitQps: number; dailyRefundLimitMinor: number; reason: string }
export interface AppUpdate { expectedVersion: number; name: string; purpose: string; ipWhitelist: string; rateLimitQps: number; dailyRefundLimitMinor: number; reason: string }
export interface AlertRuleSave { expectedVersion: number; name: string; metric: string; scopeType: string; scopeRef: string | null; operator: string; threshold: string; windowSeconds: number; minSampleCount: number; consecutiveBreaches: number; severity: string; notifyTargets: string[]; reason: string }

export interface ChannelCostSave { reason: string; payChannel: string; payMethod?: string; legalForm?: string; market?: string; rateBps: number; minFeeMinor?: number; effectiveFrom: number }

export interface PayCenterApi {
  source: "http" | "mock";
  orders: {
    list(q: Q): Promise<PayPage<OrderRow>>;
    detail(no: string): Promise<Detail<OrderDetail>>;
    logs(no: string): Promise<{ payOrderNo: string; entries: OrderLogEntry[] }>;
    recover(no: string, reason?: string): Promise<unknown>;
    manualSettle(no: string, body: { channelTradeNo?: string; amountMinor: number; paidAt: number; reason: string }): Promise<unknown>;
    dismiss(no: string, reason: string): Promise<unknown>;
  };
  exceptions: {
    list(q: Q): Promise<PayPage<ExceptionRow>>;
    refund(id: number, reason: string): Promise<unknown>;
    ignore(id: number, reason: string): Promise<unknown>;
  };
  merchants: {
    list(q: Q): Promise<PayPage<MerchantRow>>;
    detail(no: string): Promise<Detail<MerchantDetail>>;
    suspend(no: string, c: Cmd): Promise<ActionResult>;
    activate(no: string, c: Cmd): Promise<ActionResult>;
  };
  stores: {
    list(q: Q): Promise<PayPage<StoreRow>>;
    detail(no: string): Promise<Detail<StoreDetail>>;
    policy(no: string): Promise<CollectionPolicyView>;
    savePolicy(no: string, body: { expectedVersion: number; mode: string; productCodes: string[]; reason: string }): Promise<ActionResult>;
    requestChange(no: string, body: { expectedVersion: number; targetMerchantNo: string; reason: string }): Promise<ActionResult>;
    cancelChange(no: string, c: Cmd): Promise<ActionResult>;
    executeChange(no: string, c: Cmd): Promise<ActionResult>;
  };
  onboardings: {
    list(q: Q): Promise<PayPage<OnboardingRow>>;
    detail(no: string): Promise<Detail<OnboardingDetail>>;
    create(body: { merchantNo: string; payChannel: string }): Promise<Detail<OnboardingDetail>>;
    submit(no: string, body: { expectedVersion: number; materialVersion: number; materials: OnboardingMaterials }): Promise<ActionResult>;
    review(no: string, body: { expectedVersion: number; decision: "APPROVE" | "REJECT" | "REQUEST_MORE"; reason: string; materialVersion: number }): Promise<ActionResult>;
    dispatch(no: string, body: { expectedVersion: number; channelApplyNo: string; reason: string }): Promise<ActionResult>;
    refresh(no: string, c: Cmd): Promise<ActionResult>;
    cancel(no: string, c: Cmd): Promise<ActionResult>;
  };
  approvals: {
    list(q: Q & { view?: ApprovalView }): Promise<PayPage<ApprovalRow>>;
    detail(no: string): Promise<ApprovalDetail>;
    review(no: string, body: { expectedVersion: number; decision: "APPROVE" | "REJECT" | "REQUEST_MORE"; reason: string; materialVersion: number }): Promise<unknown>;
    withdraw(no: string, c: Cmd): Promise<unknown>;
    execute(no: string, c: Cmd): Promise<unknown>;
    claim(taskNo: string, c: Cmd): Promise<ApprovalTask>;
    release(taskNo: string, c: Cmd): Promise<ApprovalTask>;
    assign(taskNo: string, body: Cmd & { assigneeNo: string }): Promise<ApprovalTask>;
  };
  channels: {
    list(q: Q): Promise<PayPage<ChannelRow>>;
    detail(code: string): Promise<Detail<ChannelRow>>;
    create(body: ChannelSave): Promise<Detail<ChannelRow>>;
    update(code: string, body: ChannelSave): Promise<ActionResult>;
    enable(code: string, c: Cmd): Promise<ActionResult>;
    disable(code: string, c: Cmd): Promise<ActionResult>;
    diagnose(code: string, c: Cmd): Promise<{ jobNo: string; status: string; errorCode: string | null }>;
  };
  products: {
    list(q: Q): Promise<PayPage<ProductRow>>;
    detail(code: string): Promise<Detail<ProductRow>>;
    create(body: ProductSave): Promise<Detail<ProductRow>>;
    update(code: string, body: ProductSave): Promise<ActionResult>;
    enable(code: string, c: Cmd): Promise<ActionResult>;
    disable(code: string, c: Cmd): Promise<ActionResult>;
  };
  entries: {
    list(q: Q): Promise<PayPage<EntryRow>>;
    detail(no: string): Promise<Detail<EntryRow>>;
    create(body: EntrySave): Promise<Detail<EntryRow>>;
    update(no: string, body: EntrySave): Promise<ActionResult>;
    enable(no: string, c: Cmd): Promise<ActionResult>;
    disable(no: string, c: Cmd): Promise<ActionResult>;
  };
  dataScopes: {
    list(q: Q): Promise<PayPage<DataScopeRow>>;
    detail(no: string): Promise<Detail<DataScopeRow>>;
    create(body: ScopeSave): Promise<Detail<DataScopeRow>>;
    update(no: string, body: ScopeSave): Promise<ActionResult>;
    enable(no: string, c: Cmd): Promise<ActionResult>;
    revoke(no: string, c: Cmd): Promise<ActionResult>;
  };

  merchantChanges: {
    list(q: Q): Promise<PayPage<MerchantRow>>;
    revision(merchantNo: string, revisionNo: string): Promise<Detail<ProfileRevisionDetail>>;
    decide(merchantNo: string, revisionNo: string, decision: "approve" | "reject" | "request-more", c: Cmd): Promise<ActionResult>;
  };
  refunds: {
    /** v1：不分页，最多 200 条，新的在前；页面在前端分页 */
    list(q: { status?: string; limit?: number }): Promise<RefundListRow[]>;
    review(payRefundNo: string, body: { approve: boolean; reason: string }): Promise<RefundListRow>;
  };
  terminals: {
    list(q: Q): Promise<PayPage<TerminalRow>>;
    detail(no: string): Promise<Detail<TerminalRow>>;
    create(body: TerminalSave): Promise<Detail<TerminalRow>>;
    update(no: string, body: TerminalSave): Promise<ActionResult>;
    verify(no: string, body: Cmd & { evidenceRef: string }): Promise<ActionResult>;
    enable(no: string, c: Cmd): Promise<ActionResult>;
    disable(no: string, c: Cmd): Promise<ActionResult>;
    activationCode(no: string): Promise<{ activationCode: string }>;
    lost(no: string): Promise<unknown>;
    retire(no: string, c: Cmd): Promise<unknown>;
  };
  apps: {
    list(q: Q): Promise<PayPage<AppRow>>;
    detail(no: string): Promise<Detail<AppDetail>>;
    grants(no: string): Promise<PayPage<AppGrant>>;
    create(body: AppCreate): Promise<Detail<AppDetail>>;
    update(no: string, body: AppUpdate): Promise<ActionResult>;
    scopes(no: string, body: Cmd & { scopes: string[] }): Promise<ActionResult>;
    callback(no: string, body: Cmd & { notifyUrl: string; callbackDomains: string[] }): Promise<ActionResult>;
    verifyCallback(no: string, body: Cmd & { evidenceRef: string }): Promise<ActionResult>;
    command(no: string, action: "enable" | "resume" | "suspend" | "revoke", c: Cmd): Promise<ActionResult>;
    issueCredential(no: string, body: { credentialType: "APP_SECRET" | "APP_DATA_KEY"; reason: string }): Promise<CredentialIssued>;
  };
  traces: {
    requestLogs(q: Q): Promise<PayPage<RequestLogRow>>;
    requestLog(id: string): Promise<RequestLogDetail>;
    calls(q: Q): Promise<PayPage<ChannelCallRow>>;
    call(no: string): Promise<ChannelCallDetail>;
    callbacks(q: Q): Promise<PayPage<ChannelCallbackRow>>;
    callback(no: string): Promise<ChannelCallbackDetail>;
  };
  alerts: {
    events(q: Q): Promise<PayPage<AlertEventRow>>;
    event(no: string): Promise<Detail<AlertEventRow>>;
    claim(no: string, c: Cmd): Promise<ActionResult>;
    assign(no: string, body: Cmd & { assigneeNo: string }): Promise<ActionResult>;
    resolve(no: string, body: Cmd & { resolution: string; relatedRef: string | null }): Promise<ActionResult>;
    close(no: string, c: Cmd): Promise<ActionResult>;
    rules(q: Q): Promise<PayPage<AlertRuleRow>>;
    rule(no: string): Promise<Detail<AlertRuleRow> & { revisions: AlertRuleRow[] }>;
    createRule(body: AlertRuleSave): Promise<unknown>;
    updateRule(no: string, body: AlertRuleSave): Promise<ActionResult>;
    enableRule(no: string, c: Cmd): Promise<ActionResult>;
    disableRule(no: string, c: Cmd): Promise<ActionResult>;
  };
  approvalFlows: {
    list(q: Q): Promise<PayPage<ApprovalFlowRow>>;
    detail(no: string): Promise<{ record: ApprovalFlowRow; revisions: ApprovalFlowRow[] }>;
  };
  settings: {
    get(): Promise<OperatingSettings>;
    save(body: { expectedVersion: number; defaultChannelCode: string; channelOrder: string[]; reason: string }): Promise<OperatingSettings>;
  };

  // ── P3a ──
  attempts: {
    list(q: Q): Promise<PayPage<AttemptListRow>>;
    detail(outTradeNo: string): Promise<Detail<AttemptListRow>>;
    refresh(outTradeNo: string, c: Cmd): Promise<{ outTradeNo: string; payOrderNo: string | null; orderStatus: string; accepted: boolean }>;
  };
  ledger: {
    list(q: Q): Promise<PayPage<LedgerEntryRow>>;
    detail(entryNo: string): Promise<Detail<{ entry: LedgerEntryRow | null; voucher: LedgerVoucher }>>;
  };
  settlements: {
    list(q: Q): Promise<PayPage<SettlementRow>>;
    detail(no: string): Promise<Detail<{ settlement: SettlementRow; payouts: PayoutRow[] }>>;
  };
  withdrawals: {
    list(q: Q): Promise<PayPage<WithdrawalRow>>;
    detail(no: string): Promise<Detail<WithdrawalRow>>;
    review(no: string, body: Cmd & { decision: "APPROVE" | "REJECT" }): Promise<ActionResult>;
    cancel(no: string, c: Cmd): Promise<ActionResult>;
  };
  payouts: {
    list(q: Q): Promise<PayPage<PayoutRow>>;
    detail(no: string): Promise<Detail<{ payout: PayoutRow; settleNos: string[]; settleDocumentNos: string[] }>>;
    receipt(no: string, body: Cmd & { result: "PAID" | "FAILED"; paymentRef: string | null }): Promise<ActionResult>;
  };
  reconDiffs: {
    list(q: Q): Promise<PayPage<ReconDiffRow>>;
    coverage(): Promise<{ channelBillConnected: boolean; note: string | null }>;
    detail(no: string): Promise<Detail<{ difference: ReconDiffRow; claimedBy: string | null; claimedAt: number | null }>>;
    claim(no: string, c: Cmd): Promise<ActionResult>;
    close(no: string, body: Cmd & { outcome: "RESOLVED" | "IGNORED" }): Promise<ActionResult>;
  };
  storedValue: {
    accounts(q: Q): Promise<PayPage<StoredValueAccountRow>>;
    account(no: string): Promise<Detail<{ account: StoredValueAccountRow; recentTransactions: StoredValueTransactionRow[] }>>;
    transactions(q: Q): Promise<PayPage<StoredValueTransactionRow>>;
  };
  channelCosts: {
    list(q: Q): Promise<PayPage<ChannelCostRow>>;
    detail(no: string): Promise<Detail<ChannelCostRow>>;
    versions(no: string): Promise<ChannelCostRow[]>;
    create(body: ChannelCostSave): Promise<Detail<ChannelCostRow>>;
    revise(no: string, body: ChannelCostSave & { expectedVersion: number }): Promise<Detail<ChannelCostRow>>;
  };
  commissionRules: {
    list(q: Q): Promise<PayPage<CommissionRuleRow>>;
    detail(no: string): Promise<Detail<CommissionRuleRow>>;
    create(body: { reason: string; businessMode: string; trafficSource: string; rateBps: number; effectiveFrom: number }): Promise<Detail<CommissionRuleRow>>;
  };
}

const e = encodeURIComponent;

/** Detail 补齐：near-pay 的 Detail 总有 version/allowedActions/timeline；保险起见给默认值 */
async function detailOf<T>(c: PayOpsClient, path: string): Promise<Detail<T>> {
  const d = await c.get<Partial<Detail<T>> & { record: T }>(path);
  return { record: d.record, version: Number(d.version ?? 0), allowedActions: d.allowedActions ?? [], timeline: d.timeline ?? [] };
}

/** v1 客户端：near-pay 退款只有 /pay-ops/v1；信封 {code,msg,data} 由同一个解包逻辑处理 */
export function createHttpPayCenterApi(c: PayOpsClient, v1: PayOpsClient = c): PayCenterApi {
  const crud = <Row, Save extends object>(base: string) => ({
    list: (q: Q) => c.page<Row>(base, q),
    detail: (id: string) => detailOf<Row>(c, `${base}/${e(id)}`),
    create: (body: Save) => c.command<Detail<Row>>(base, body as Record<string, unknown> as never).then((d) => ({ ...d, allowedActions: d.allowedActions ?? [], timeline: d.timeline ?? [] })),
    update: (id: string, body: Save) => c.command<ActionResult>(`${base}/${e(id)}`, body as never, { method: "PUT" }),
  });
  return {
    source: "http",
    orders: {
      list: (q) => c.page("/orders", q),
      detail: (no) => detailOf(c, `/orders/${e(no)}`),
      logs: (no) => c.get(`/orders/${e(no)}/logs`),
      recover: (no, reason) => c.command(`/orders/${e(no)}/recover`, reason ? { reason } : {}, { idempotent: false }),
      manualSettle: (no, body) => c.command(`/orders/${e(no)}/manual-settle`, body, { idempotent: false }),
      dismiss: (no, reason) => c.command(`/orders/${e(no)}/dismiss`, { reason }, { idempotent: false }),
    },
    exceptions: {
      list: (q) => c.page("/payment-exceptions", q),
      refund: (id, reason) => c.command(`/payment-exceptions/${id}/refund`, { reason }, { idempotent: false }),
      ignore: (id, reason) => c.command(`/payment-exceptions/${id}/ignore`, { reason }, { idempotent: false }),
    },
    merchants: {
      list: (q) => c.page("/merchants", q),
      detail: (no) => detailOf(c, `/merchants/${e(no)}`),
      suspend: (no, cmd) => c.command(`/merchants/${e(no)}/suspend`, { ...cmd }),
      activate: (no, cmd) => c.command(`/merchants/${e(no)}/activate`, { ...cmd }),
    },
    stores: {
      list: (q) => c.page("/stores", q),
      detail: (no) => detailOf(c, `/stores/${e(no)}`),
      policy: (no) => c.get(`/stores/${e(no)}/collection-policy`),
      savePolicy: (no, body) => c.command(`/stores/${e(no)}/collection-policy`, { ...body }, { method: "PUT" }),
      requestChange: (no, body) => c.command(`/stores/${e(no)}/association-change`, { ...body }),
      cancelChange: (no, cmd) => c.command(`/stores/${e(no)}/association-change/cancel`, { ...cmd }),
      executeChange: (no, cmd) => c.command(`/stores/${e(no)}/association-change/execute`, { ...cmd }),
    },
    onboardings: {
      list: (q) => c.page("/onboardings", q),
      detail: (no) => detailOf(c, `/onboardings/${e(no)}`),
      create: (body) => c.command(`/onboardings`, { ...body }),
      submit: (no, body) => c.command(`/onboardings/${e(no)}/submit`, { ...body }),
      review: (no, body) => c.command(`/onboardings/${e(no)}/review`, { ...body }),
      dispatch: (no, body) => c.command(`/onboardings/${e(no)}/dispatch`, { ...body }),
      refresh: (no, cmd) => c.command(`/onboardings/${e(no)}/refresh`, { ...cmd }),
      cancel: (no, cmd) => c.command(`/onboardings/${e(no)}/cancel`, { ...cmd }),
    },
    approvals: {
      list: (q) => c.page("/approvals", q),
      // 审批详情**不是** Detail 包装（near-pay 直接返回 ApprovalDetailDto）
      detail: (no) => c.get(`/approvals/${e(no)}`),
      review: (no, body) => c.command(`/approvals/${e(no)}/review`, { ...body }),
      withdraw: (no, cmd) => c.command(`/approvals/${e(no)}/withdraw`, { ...cmd }),
      execute: (no, cmd) => c.command(`/approvals/${e(no)}/execute`, { ...cmd }),
      claim: (t, cmd) => c.command(`/approval-tasks/${e(t)}/claim`, { ...cmd }),
      release: (t, cmd) => c.command(`/approval-tasks/${e(t)}/release`, { ...cmd }),
      assign: (t, body) => c.command(`/approval-tasks/${e(t)}/assign`, { ...body }),
    },
    channels: {
      ...crud<ChannelRow, ChannelSave>("/channels"),
      enable: (code, cmd) => c.command(`/channels/${e(code)}/enable`, { ...cmd }),
      disable: (code, cmd) => c.command(`/channels/${e(code)}/disable`, { ...cmd }),
      diagnose: (code, cmd) => c.command(`/channels/${e(code)}/diagnose`, { ...cmd }),
    },
    products: {
      ...crud<ProductRow, ProductSave>("/products"),
      enable: (code, cmd) => c.command(`/products/${e(code)}/enable`, { ...cmd }),
      disable: (code, cmd) => c.command(`/products/${e(code)}/disable`, { ...cmd }),
    },
    entries: {
      ...crud<EntryRow, EntrySave>("/collection-entries"),
      enable: (no, cmd) => c.command(`/collection-entries/${e(no)}/enable`, { ...cmd }),
      disable: (no, cmd) => c.command(`/collection-entries/${e(no)}/disable`, { ...cmd }),
    },
    merchantChanges: {
      list: (q) => c.page("/merchants", { ...q, profileStatus: "SUBMITTED" }),
      revision: (m, r) => detailOf(c, `/merchants/${e(m)}/profile-revisions/${e(r)}`),
      decide: (m, r, d, cmd) => c.command(`/merchants/${e(m)}/profile-revisions/${e(r)}/${d}`, { ...cmd }),
    },
    refunds: {
      list: async (q) => (await v1.get<RefundListRow[] | null>("/refunds", { status: q.status, limit: q.limit ?? 200 })) ?? [],
      review: (no, body) => v1.command(`/refunds/${e(no)}/review`, { ...body }, { idempotent: false }),
    },
    terminals: {
      ...crud<TerminalRow, TerminalSave>("/terminals"),
      verify: (no, body) => c.command(`/terminals/${e(no)}/verify`, { ...body }),
      enable: (no, cmd) => c.command(`/terminals/${e(no)}/enable`, { ...cmd }),
      disable: (no, cmd) => c.command(`/terminals/${e(no)}/disable`, { ...cmd }),
      activationCode: (no) => c.command(`/terminals/${e(no)}/activation-codes`, undefined, { idempotent: false }),
      lost: (no) => c.command(`/terminals/${e(no)}/lost`, undefined, { idempotent: false }),
      retire: (no, cmd) => c.command(`/terminals/${e(no)}/retire`, { ...cmd }),
    },
    apps: {
      list: (q) => c.page("/apps", q),
      detail: (no) => detailOf(c, `/apps/${e(no)}`),
      grants: (no) => c.page(`/apps/${e(no)}/grants`, { page: 1, size: 100 }),
      create: (body) => c.command("/apps", { ...body }),
      update: (no, body) => c.command(`/apps/${e(no)}`, { ...body }, { method: "PUT" }),
      scopes: (no, body) => c.command(`/apps/${e(no)}/scopes`, { ...body }, { method: "PUT" }),
      callback: (no, body) => c.command(`/apps/${e(no)}/callback`, { ...body }, { method: "PUT" }),
      verifyCallback: (no, body) => c.command(`/apps/${e(no)}/callback/verify`, { ...body }),
      command: (no, action, cmd) => c.command(`/apps/${e(no)}/${action}`, { ...cmd }),
      // 签发没有幂等号与版本（near-pay OpsCredentialController）；明文只在这次响应里出现
      issueCredential: (no, body) => c.command(`/apps/${e(no)}/credentials`, { ...body }, { idempotent: false }),
    },
    traces: {
      requestLogs: (q) => c.page("/request-logs", q),
      requestLog: (id) => c.get(`/request-logs/${e(id)}`),
      calls: (q) => c.page("/channel-calls", q),
      call: (no) => c.get(`/channel-calls/${e(no)}`),
      callbacks: (q) => c.page("/channel-callbacks", q),
      callback: (no) => c.get(`/channel-callbacks/${e(no)}`),
    },
    alerts: {
      events: (q) => c.page("/alert-events", q),
      event: (no) => detailOf(c, `/alert-events/${e(no)}`),
      claim: (no, cmd) => c.command(`/alert-events/${e(no)}/claim`, { ...cmd }),
      assign: (no, body) => c.command(`/alert-events/${e(no)}/assign`, { ...body }),
      resolve: (no, body) => c.command(`/alert-events/${e(no)}/resolve`, { ...body }),
      close: (no, cmd) => c.command(`/alert-events/${e(no)}/close`, { ...cmd }),
      rules: (q) => c.page("/alert-rules", q),
      rule: async (no) => {
        const d = await c.get<Detail<AlertRuleRow> & { revisions?: AlertRuleRow[] }>(`/alert-rules/${e(no)}`);
        return { ...d, version: Number(d.version ?? 0), allowedActions: d.allowedActions ?? [], timeline: d.timeline ?? [], revisions: d.revisions ?? [] };
      },
      createRule: (body) => c.command("/alert-rules", { ...body }),
      updateRule: (no, body) => c.command(`/alert-rules/${e(no)}`, { ...body }, { method: "PUT" }),
      enableRule: (no, cmd) => c.command(`/alert-rules/${e(no)}/enable`, { ...cmd }),
      disableRule: (no, cmd) => c.command(`/alert-rules/${e(no)}/disable`, { ...cmd }),
    },
    approvalFlows: {
      list: (q) => c.page("/approval-flows", q),
      detail: async (no) => { const d = await c.get<{ record: ApprovalFlowRow; revisions?: ApprovalFlowRow[] }>(`/approval-flows/${e(no)}`); return { record: d.record, revisions: d.revisions ?? [] }; },
    },
    settings: {
      get: () => c.get("/settings"),
      save: (body) => c.command("/settings", { ...body }, { method: "PUT" }),
    },
    attempts: {
      list: (q) => c.page("/attempts", q),
      detail: (no) => detailOf(c, `/attempts/${e(no)}`),
      refresh: (no, cmd) => c.command(`/attempts/${e(no)}/refresh`, { ...cmd }),
    },
    ledger: {
      list: (q) => c.page("/ledger-entries", q),
      detail: (no) => detailOf(c, `/ledger-entries/${e(no)}`),
    },
    settlements: {
      list: (q) => c.page("/settlements", q),
      detail: (no) => detailOf(c, `/settlements/${e(no)}`),
    },
    withdrawals: {
      list: (q) => c.page("/withdrawals", q),
      detail: (no) => detailOf(c, `/withdrawals/${e(no)}`),
      review: (no, body) => c.command(`/withdrawals/${e(no)}/review`, { ...body }),
      cancel: (no, cmd) => c.command(`/withdrawals/${e(no)}/cancel`, { ...cmd }),
    },
    payouts: {
      list: (q) => c.page("/payouts", q),
      detail: (no) => detailOf(c, `/payouts/${e(no)}`),
      receipt: (no, body) => c.command(`/payouts/${e(no)}/receipt`, { ...body }),
    },
    reconDiffs: {
      list: (q) => c.page("/reconciliation-differences", q),
      coverage: () => c.get("/reconciliation-differences/coverage"),
      detail: (no) => detailOf(c, `/reconciliation-differences/${e(no)}`),
      claim: (no, cmd) => c.command(`/reconciliation-differences/${e(no)}/claim`, { ...cmd }),
      close: (no, body) => c.command(`/reconciliation-differences/${e(no)}/close`, { ...body }),
    },
    storedValue: {
      accounts: (q) => c.page("/stored-value/accounts", q),
      account: (no) => detailOf(c, `/stored-value/accounts/${e(no)}`),
      transactions: (q) => c.page("/stored-value/transactions", q),
    },
    channelCosts: {
      list: (q) => c.page("/channel-costs", q),
      detail: (no) => detailOf(c, `/channel-costs/${e(no)}`),
      versions: async (no) => (await c.get<ChannelCostRow[] | null>(`/channel-costs/${e(no)}/versions`)) ?? [],
      // 新建与调价都要 requestNo（客户端按请求体生成）；调价是追加新版本，不改原行
      create: (body) => c.command("/channel-costs", { ...body }),
      revise: (no, body) => c.command(`/channel-costs/${e(no)}`, { ...body }, { method: "PUT" }),
    },
    commissionRules: {
      list: (q) => c.page("/commission-rules", q),
      detail: (no) => detailOf(c, `/commission-rules/${e(no)}`),
      create: (body) => c.command("/commission-rules", { ...body }),
    },
    dataScopes: {
      ...crud<DataScopeRow, ScopeSave>("/data-scopes"),
      enable: (no, cmd) => c.command(`/data-scopes/${e(no)}/enable`, { ...cmd }),
      revoke: (no, cmd) => c.command(`/data-scopes/${e(no)}/revoke`, { ...cmd }),
    },
  };
}

export function isPayMock(value = process.env.NEXT_PUBLIC_USE_MOCK): boolean {
  return value !== "0";
}
