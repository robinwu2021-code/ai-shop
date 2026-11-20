// near-pay /pay-ops/v2 的 DTO（取自 near-pay 后端 Ops*Dtos 与 OpsOrderConsoleService，2026-11-20 核对）。
// 时间一律 UTC 毫秒；金额一律最小货币单位（*Minor）。只列页面用得到的字段。

export interface ActionResult { resourceNo: string; status: string; version: number; approvalNo: string | null; jobNo: string | null }
export interface TimelineItem { auditNo: string; action: string; operatorNo: string; result: string; reason: string | null; occurredAt: number }
export interface Detail<T> { record: T; version: number; allowedActions: string[]; timeline: TimelineItem[] }

// ── 1 支付订单 ──
export const ORDER_STATUSES = ["CREATED", "AWAITING_PAYMENT", "AUTHORIZED", "AWAITING_CONFIRM", "SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED", "CLOSED", "FAILED"] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];
export interface OrderRow {
  payOrderNo: string; outOrderNo: string | null; bizType: string | null;
  storeNo: string | null; storeName: string | null; merchantNo: string | null; merchantName: string | null;
  currency: string; amountMinor: number; paidMinor: number; refundedMinor: number;
  status: OrderStatus; payChannel: string | null; payMethod: string | null; sourceType: string | null;
  createdAt: number | null; succeededAt: number | null; closedAt: number | null; expireAt: number | null;
  followupStatus: string | null; payerNote: string | null;
}
export interface AttemptRow { paymentNo: string; direction: string; outTradeNo: string | null; tradeNo: string | null; payChannel: string | null; amountMinor: number; currency: string; status: string; at: number | null }
export interface RefundRow { payRefundNo: string; payOrderNo: string; amountMinor: number; currency: string; status: string; reason: string | null; createdAt: number | null }
export interface OrderDetail { order: OrderRow; attempts: AttemptRow[]; refunds: RefundRow[] }
export interface OrderLogEntry { kind: string; traceId: string | null; at: number | null; title: string; status: string | null; elapsedMs: number | null; note: string | null }

// ── 2 异常交易 ──
export const EXCEPTION_KINDS = ["LATE_PAYMENT", "AMOUNT_MISMATCH", "DUPLICATE_PAYMENT", "CURRENCY_MISMATCH"] as const;
export const EXCEPTION_STATUSES = ["OPEN", "AUTO_REFUNDING", "RESOLVED", "IGNORED"] as const;
export type ExceptionStatus = (typeof EXCEPTION_STATUSES)[number];
export interface ExceptionRow { id: number; kind: string; payOrderNo: string; amountMinor: number; currency: string; status: ExceptionStatus; resolution: string | null; note: string | null; handledBy: string | null; createdAt: number | null }

// ── 3 商户 ──
export const MERCHANT_STATUSES = ["ACTIVE", "SUSPENDED", "CLOSED"] as const;
export const PROFILE_STATUSES = ["NONE", "SUBMITTED", "APPROVED", "REJECTED", "NEED_MORE", "SUPERSEDED"] as const;
export const VERIFY_STATUSES = ["PENDING", "VERIFIED", "MISMATCH"] as const;
export interface MerchantRow {
  merchantNo: string; displayName: string; sourceSystem: string | null; entityNo: string | null; entityName: string | null;
  market: string | null; profileStatus: string; verifyStatus: string; duplicateSuspect: boolean;
  payMerchantNos: string[] | null; storeCount: number; status: (typeof MERCHANT_STATUSES)[number]; statusReason: string | null;
  createdAt: number; version: number;
}
export interface SourceLink { linkNo: string; sourceSystem: string; sourceScope: string | null; externalEntityId: string; entityName: string | null; verifyStatus: string; verifiedBy: string | null; verifiedAt: number | null; version: number }
export interface ProfileRevision { revisionNo: string; revision: number; purpose: string; reviewStatus: string; submittedBy: string | null; submittedAt: number; reviewedBy: string | null; reviewedAt: number | null; reviewReason: string | null; version: number }
export interface MerchantDetail { merchant: MerchantRow; sourceLinks: SourceLink[]; profiles: ProfileRevision[] }

// ── 4 门店 ──
export const STORE_SOURCE_STATUSES = ["ACTIVE", "READONLY", "DISABLED", "DELETED", "PENDING_SYNC"] as const;
export const STORE_MODES = ["INHERIT", "OVERRIDE"] as const;
export interface StoreRow {
  storeNo: string; storeName: string; sourceSystem: string | null; externalStoreId: string | null; entityNo: string | null;
  merchantNo: string | null; merchantName: string | null; market: string | null; sourceStatus: string; configMode: string;
  payMerchantNos: string[] | null; syncIssue: string | null; syncedAt: number; version: number;
}
export interface StoreAssociation { linkNo: string; merchantNo: string; merchantName: string | null; origin: string; validFrom: number; validTo: number | null; endedReason: string | null }
export interface StorePendingChange { targetMerchantNo: string; reason: string; requestedBy: string; requestedAt: number }
export interface StoreDetail { store: StoreRow; associations: StoreAssociation[]; pendingChange: StorePendingChange | null }
export interface CollectionPolicyView { ownerType: string; ownerRef: string; mode: string; productCodes: string[]; version: number; merchantMode: string | null; merchantProductCodes: string[] }

// ── 5 进件 ──
export const ONBOARDING_STATUSES = ["DRAFT", "SUBMITTED", "CHANNEL_REVIEW", "NEED_SIGN", "APPROVED", "REJECTED", "CANCELLED", "SUPERSEDED"] as const;
export const REVIEW_STATUSES = ["NONE", "PENDING", "APPROVED", "REJECTED", "NEED_MORE"] as const;
export interface OnboardingRow {
  onboardingNo: string; merchantNo: string; merchantName: string | null; payChannel: string; submitMode: string;
  materialVersion: number; reviewStatus: string; status: string; channelApplyNo: string | null; signUrl: string | null;
  rejectReason: string | null; payMerchantNo: string | null; updatedAt: number; version: number;
}
export interface OnboardingVersion { versionIndex: number; reviewStatus: string; reviewReason: string | null; reviewedBy: string | null; status: string; submittedBy: string | null; submittedAt: number | null; materials: { fieldCode: string; fieldKind: string; display: string }[] }
export interface OnboardingDetail { onboarding: OnboardingRow; versions: OnboardingVersion[] }

// ── 6 审批 ──
export const APPROVAL_STATUSES = ["PENDING_FIRST", "PENDING_SECOND", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED"] as const;
export const APPROVAL_VIEWS = ["CANDIDATE", "CLAIMED", "MINE", "ALL"] as const;
export type ApprovalView = (typeof APPROVAL_VIEWS)[number];
export interface ApprovalRow {
  approvalNo: string; bizType: string; action: string; resourceNo: string | null; subjectTitle: string | null; applicantNo: string;
  nodeNo: number | null; assigneeNo: string | null; materialVersion: number; status: string; executionStatus: string;
  expiresAt: number | null; escalated: boolean; createdAt: number; version: number;
}
export interface ApprovalTask { taskNo: string; approvalNo: string; nodeNo: number; roleRef: string | null; status: string; assigneeNo: string | null; decision: string | null; decisionReason: string | null; decidedAt: number | null; version: number }
export interface ApprovalRecord { approvalNo: string; subjectType: string; action: string; subjectRef: string; materialVersion: number; status: string; version: number; expiresAt: number | null; activeTask: ApprovalTask | null; execution: { status: string; executedAt: number | null; executedBy: string | null } | null; applicantNo: string; rejectReason: string | null; createdAt: number }
export interface ApprovalDetail { record: ApprovalRecord; subjectTitle: string | null; snapshotJson: string | null; sourcePage: string | null; tasks: ApprovalTask[]; decisions: { nodeNo: number; reviewerNo: string; decision: string; reason: string | null; decidedAt: number }[]; timeline: TimelineItem[]; allowedActions: string[] }

// ── 7 渠道 ──
export interface ChannelEndpoint { environment: "SANDBOX" | "PRODUCTION"; baseUrl: string; callbackStyle: "PATH" | "QUERY" | null; enabled?: boolean | null }
export interface ChannelRow {
  channelCode: string; name: string; channelType: "FIAT" | "CRYPTO"; adapterCode: string; platformCredentialKeyId: number | null;
  status: "ENABLED" | "DISABLED"; sortNo: number; enabledProductCount: number; endpoints: ChannelEndpoint[];
  version: number; updatedAt: number | null; updatedBy: string | null; market: string | null; testChannel: boolean;
}

// ── 8 产品 ──
export const PAYMENT_METHODS = ["CARD", "TWQR", "JKO_WALLET", "PAYBY", "LINE_PAY", "GOOGLE_PAY", "PI_WALLET", "APPLE_PAY", "WECHAT_PAY", "ALIPAY", "TEST_PAY", "FREE"] as const;
export const ACCESS_METHODS = ["H5", "API", "APP", "POS"] as const;
export const ACCOUNT_MODES = ["MERCHANT_OWN", "SERVICE_PROVIDER", "PLATFORM_SUB"] as const;
export const TRADE_MODES = ["ONE_TIME", "INSTALLMENT", "BONUS", "PRESENTED_CODE", "MERCHANT_CODE", "REDIRECT", "IN_APP"] as const;
export const REFUND_MODES = ["NONE", "FULL_ONLY", "PARTIAL"] as const;
export interface ProductAccess { accessMethod: string; channelMethod: string; enabled?: boolean | null }
export interface CurrencyLimit { currency: string; amountScale: number; minMinor: number; maxMinor: number; enabled?: boolean | null }
export interface RefundRule { refundMode: string; maxPartialRefunds?: number | null; refundWindowDays?: number | null; refundIntervalSeconds?: number | null }
export interface ProductRow {
  productCode: string; channelCode: string; displayName: string; tradeMode: string; paymentMethods: string[];
  accessMethods: ProductAccess[]; accountModes: string[]; currencyLimits: CurrencyLimit[]; refundRule: RefundRule;
  twoPhase: boolean; status: "ENABLED" | "DISABLED"; sortNo: number; version: number; updatedAt: number | null; updatedBy: string | null; protocolProductCode: string | null;
}

// ── 9 收款接入 ──
export interface EntryRow {
  entryNo: string; name: string; merchantNo: string; storeNo: string; accessMethod: string; productCodes: string[];
  appNo: string | null; allowedDomain: string | null; sessionTimeoutSeconds: number | null; clientAppIdentifier: string | null; terminalNo: string | null;
  status: "DRAFT" | "ACTIVE" | "DISABLED"; statusReason: string | null; version: number;
  readiness: { ready: boolean; reasons: string[] } | null;
}

// ── 10 数据范围 ──
export interface DataScopeRow {
  scopeNo: string; scopeType: "USER" | "ROLE"; subjectNo: string; marketSet: string[]; entityNos: string[]; storeNos: string[];
  fundsAccess: "NONE" | "MASKED_READ"; expiresAt: number; status: "DRAFT" | "ACTIVE" | "REVOKED" | "EXPIRED";
  reason: string | null; enteredBy: string | null; enabledBy: string | null; revokedAt: number | null;
}
