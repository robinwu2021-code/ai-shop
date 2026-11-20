// 支付中心各页签的接口层：一个接口，两种实现（HTTP / mock），按 NEXT_PUBLIC_USE_MOCK 切换（与 lib/api 同一个开关）。
// 路径、查询参数、命令体与 near-pay /pay-ops/v2 一一对应（TDD-支付中心运营页面 §2.6，契约核对见 §6）。
import type { PayOpsClient, PayPage } from "./client";
import type {
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
}

const e = encodeURIComponent;

/** Detail 补齐：near-pay 的 Detail 总有 version/allowedActions/timeline；保险起见给默认值 */
async function detailOf<T>(c: PayOpsClient, path: string): Promise<Detail<T>> {
  const d = await c.get<Partial<Detail<T>> & { record: T }>(path);
  return { record: d.record, version: Number(d.version ?? 0), allowedActions: d.allowedActions ?? [], timeline: d.timeline ?? [] };
}

export function createHttpPayCenterApi(c: PayOpsClient): PayCenterApi {
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
