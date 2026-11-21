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
  /** 最新一版资料（商户变更页审的就是它） */
  latestProfile?: ProfileSummary | null;
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
  /** 渠道最近一次结果码（AMR10014 蓝新商店已存在、KEY_RECEIPT_MISSING 回执缺失） */
  submitOutcome?: "UNKNOWN" | null; ezpayBindingStatus?: string | null; candidateStatus?: "PENDING" | "ACCEPTED" | null; subMerchantId?: string | null; profileRevisionRef?: string | null;
}
export interface OnboardingVersion { versionIndex: number; approvalNo?: string | null; resultCode?: string | null; channelApplyNo?: string | null; rejectReason?: string | null; reviewStatus: string; reviewReason: string | null; reviewedBy: string | null; status: string; submittedBy: string | null; submittedAt: number | null; materials: { fieldCode: string; fieldKind: string; display: string }[] }
export interface OnboardingDetail { onboarding: OnboardingRow; versions: OnboardingVersion[]; candidate?: OnboardingCandidate | null }

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
  status: "ENABLED" | "DISABLED" | "PENDING"; sortNo: number; enabledProductCount: number; endpoints: ChannelEndpoint[];
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

// ════ P2（2026-11-20 核对 near-pay 源码） ════

// ── 商户变更：列表复用 MerchantRow（profileStatus=SUBMITTED），审 latestProfile ──
export interface ProfileSummary { revisionNo: string; revision: number; purpose: string; reviewStatus: string }
export interface ProfileRevisionDetail extends ProfileRevision { market: string | null; profile: Record<string, string>; materials: { kind: string; ref: string; purpose: string }[] }

// ── 退款（v1）──
export const REFUND_STATUSES = ["REQUESTED", "PENDING_REVIEW", "PENDING_SECOND_REVIEW", "APPROVED", "DISPATCHED", "SUCCEEDED", "FAILED", "REJECTED", "OFFLINE_PENDING_PROOF"] as const;
export interface RefundListRow { payRefundNo: string; payOrderNo: string; amountMinor: number; currency: string; status: string; reason: string | null; requestedBy: string | null; sourceType: string | null; hitReasons: string | null; createdAt: number | null }

// ── 支付终端 ──
export const TERMINAL_CONFIG_STATUSES = ["DRAFT", "ENABLED", "DISABLED"] as const;
export const DEVICE_STATUSES = ["REGISTERED", "ACTIVE", "LOST", "RETIRED"] as const;
export const CONNECTION_MODES = ["STANDALONE", "ECR"] as const;
export interface TerminalRow {
  terminalNo: string; name: string; merchantNo: string; storeNo: string; payChannel: string; payMerchantNo: string | null; channelTerminalNo: string | null;
  serialNo: string; model: string | null; connectionMode: string; productCodes: string[]; receiptName: string | null; remark: string | null; dailyRefundLimitMinor: number;
  market: string | null; currency: string | null; configurationStatus: string; verificationStatus: string; configuredBy: string | null; verifiedBy: string | null;
  evidenceRef: string | null; deviceStatus: string; credentialRef: string | null; statusReason: string | null; version: number;
  configurationEligibility: { canEnable: boolean; reasons: string[] } | null;
  readiness: { ready: boolean; canReceivePayments: boolean; reasons: string[] } | null;
}

// ── 接入应用 ──
export const APP_STATUSES = ["DRAFT", "ACTIVE", "SUSPENDED", "REVOKED"] as const;
export interface AppRow {
  appNo: string; name: string; purpose: string | null; ownerKind: "MERCHANT" | "PLATFORM"; merchantNo: string | null; environment: "LIVE" | "SANDBOX";
  scopes: string[]; notifyUrl: string | null; callbackDomains: string[]; callbackStatus: string; ipWhitelist: string | null; rateLimitQps: number;
  dailyRefundLimitMinor: number; credentialStatus: string; grantCount: number; status: string; lastAccessAt: number | null; lastAccessOutcome: string | null; version: number;
}
export interface AppDetail { app: AppRow; registeredBy: string | null; callbackSetBy: string | null; callbackVerifiedBy: string | null; callbackEvidenceRef: string | null; statusReason: string | null; enableApprovalNo: string | null }
export interface AppGrant { appNo: string; storeNo: string; merchantNo: string; actions: string[]; status: string; grantedBy: string | null; grantedAt: number | null; version: number }
export interface CredentialIssued { keyId: number; versionIndex: number; serial: string; secret: string; fingerprint: string; status: string }

// ── 请求日志 / 渠道调用 / 回调 ──
export interface RequestLogRow { requestId: string; entry: string; appNo: string | null; method: string; path: string; httpStatus: number | null; businessCode: string | null; latencyMs: number | null; authResult: string; occurredAt: number }
export interface ChannelCallRow { callNo: string; attempt: number; payChannel: string; payMerchantNo: string | null; action: string; api: string | null; bizNo: string | null; requestId: string | null; resultCode: string | null; latencyMs: number | null; status: string; transport: string; occurredAt: number }
export interface ChannelCallbackRow { callbackNo: string; payChannel: string; notifyType: string; requestId: string | null; verifyResult: string; outcome: string; bizNo: string | null; channelTradeNo: string | null; occurredAt: number }
export interface RequestLogDetail { record: RequestLogRow; requestMasked: string | null; responseMasked: string | null; sourceType: string | null; operatorNo: string | null; idempotencyKey: string | null; calls: ChannelCallRow[]; callbacks: ChannelCallbackRow[] }
export interface ChannelCallDetail { callNo: string; attempts: { record: ChannelCallRow; httpMethod: string | null; urlPath: string | null; httpStatus: number | null; requestMasked: string | null; responseMasked: string | null; errorMessage: string | null; breakerState: string | null }[]; callbacks: ChannelCallbackRow[] }
export interface ChannelCallbackDetail { record: ChannelCallbackRow; headersMasked: string | null; bodyMasked: string | null; outcomeReason: string | null; ackBody: string | null }

// ── 监控告警 ──
export const ALERT_EVENT_STATUSES = ["OPEN", "CLAIMED", "RESOLVED", "CLOSED"] as const;
export const ALERT_RULE_STATUSES = ["DRAFT", "PUBLISHED", "RETIRED"] as const;
export const ALERT_SEVERITIES = ["P1", "P2", "P3"] as const;
export const ALERT_OPERATORS = ["GT", "GTE", "LT", "LTE"] as const;
export const ALERT_RESOLUTIONS = ["FIXED", "EXTERNAL", "FALSE_POSITIVE", "ACCEPTED_RISK", "DUPLICATE"] as const;
export const ALERT_METRICS = ["CHANNEL_SUCCESS_RATE", "CHANNEL_LATENCY", "CALLBACK_FAILURE", "NOTIFY_FAILURE", "BALANCE_ANOMALY", "CREDENTIAL_EXPIRY"] as const;
export interface AlertEventRow {
  eventNo: string; ruleNo: string; ruleRevisionNo: number; metric: string; severity: string; scopeType: string; scopeRef: string | null;
  observedValue: string; threshold: string; operator: string; triggerCount: number; firstTriggeredAt: number; firedAt: number;
  assigneeNo: string | null; resolution: string | null; resolutionNote: string | null; relatedRef: string | null; status: string; version: number;
}
export interface AlertRuleRow {
  ruleNo: string; revisionNo: number; name: string; metric: string; scopeType: string; scopeRef: string | null; operator: string; threshold: string; unit: string | null;
  windowSeconds: number; minSampleCount: number; consecutiveBreaches: number; severity: string; notifyTargets: string[]; status: string;
  enteredBy: string | null; publishedBy: string | null; publishedRevisionNo: number | null; version: number;
}

// ── 审批流程（只读）──
export interface ApprovalFlowRow { flowNo: string; revisionNo: number; name: string; bizType: string; market: string | null; nodes: { nodeNo: number; roleRef: string }[]; sensitive: boolean; leaseMinutes: number; timeoutHours: number; escalateAfterHours: number; status: string; version: number }

// ── 经营设置 ──
export interface OperatingSettings { market: string; currency: string; currencyScale: number; timezone: string; defaultChannelCode: string | null; channelOrder: string[]; version: number; updatedAt: number | null; updatedBy: string | null }

// ════ P3a（near-pay 分支 claude/ops-v2-p3a-426611，契约 docs/api/handbook/08-运营端P3a接口落地.md） ════

export const ATTEMPT_STATUSES = ["INIT", "PENDING", "SUCCESS", "FAILED", "CLOSED"] as const;
export const ATTEMPT_DIRECTIONS = ["PAY", "REFUND", "SUBSIDY", "SUBSIDY_REVERSE", "PAYOUT"] as const;
export interface AttemptListRow {
  paymentNo: string; direction: string; outTradeNo: string; tradeNo: string | null; payOrderNo: string | null;
  merchantNo: string | null; storeNo: string | null; storeName: string | null; payChannel: string | null; payMethod: string | null; payMerchantNo: string | null;
  amountMinor: number; currency: string; channelAmountMinor: number | null; channelCurrency: string | null; channelFeeMinor: number | null;
  status: string; errCode: string | null; errMsg: string | null; createdAt: number | null; succeededAt: number | null; closedAt: number | null; reconciledAt: number | null; version: number;
}

export interface LedgerEntryRow {
  entryNo: string; voucherNo: string; voucherType: string; lineNo: number; account: string; direction: "D" | "C"; amountMinor: number; currency: string;
  payChannel: string | null; market: string | null; fundsPath: string | null; payOrderNo: string | null; merchantNo: string | null; storeNo: string | null;
  bizDate: string; memo: string | null; reversalOfVoucherNo: string | null; createdAt: number;
}
export interface LedgerVoucher {
  voucherNo: string; voucherType: string; refType: string | null; sourceType: string | null; market: string | null; currency: string; totalMinor: number; bizDate: string;
  fundsPath: string | null; operatorNo: string | null; memo: string | null; reversalOfVoucherNo: string | null; reversedByVoucherNos: string[]; createdAt: number; lines: LedgerEntryRow[];
}

export const SETTLEMENT_STATUSES = ["DRAFT", "COLLECTED", "RECONCILING", "BLOCKED", "RECONCILED", "RELEASED"] as const;
export interface SettlementRow {
  settlementNo: string; merchantNo: string; payChannel: string | null; settleCycle: string | null; currency: string; periodFrom: number | null; periodTo: number | null;
  dueAt: number | null; releasedAt: number | null; status: string; billCount: number; grossMinor: number; netMinor: number; reconScope: "SELF_ONLY" | "BOTH" | string;
  blockedReason: string | null; decidedBy: string | null; decideRemark: string | null; createdAt: number; version: number;
}

export const WITHDRAWAL_STATUSES = ["PENDING", "APPROVED", "REJECTED", "CANCELLED", "PAID", "FAILED"] as const;
export interface WithdrawalRow {
  withdrawalNo: string; merchantNo: string; merchantName: string | null; amountMinor: number; availableBalanceMinor: number; bankAccountMasked: string | null;
  status: string; appliedAt: number; decidedAt: number | null; decidedBy: string | null; remark: string | null; version: number;
}

export const PAYOUT_STATUSES = ["PENDING", "EXPORTED", "PAID", "MATCHED", "FAILED"] as const;
export interface PayoutRow {
  payoutNo: string; batchNo: string; merchantNo: string; payMerchantNo: string | null; accountName: string | null; bankName: string | null; bankBranch: string | null;
  accountNoMasked: string | null; amountMinor: number; billCount: number; currency: string; status: string; channel: "MANUAL" | "BANK_API" | string;
  paymentRef: string | null; bankFlowNo: string | null; exportedAt: number | null; paidAt: number | null; paidBy: string | null; matchedAt: number | null; failReason: string | null; createdAt: number; version: number;
}

export const RECON_DIFF_STATUSES = ["PENDING", "RESOLVED", "IGNORED"] as const;
export const RECON_DIFF_TYPES = ["CHANNEL_ONLY", "PLATFORM_ONLY", "AMOUNT_DIFF", "REFUND_ABNORMAL", "REFUND_LATE_SUCCESS", "REFUND_CLOSED"] as const;
export interface ReconDiffRow {
  differenceNo: string; axis: string; billDate: string | null; payChannel: string | null; diffType: string; source: "SELF_CHECK" | "CHANNEL_BILL" | string;
  paymentNo: string | null; orderNo: string | null; channelTxnNo: string | null; channelAmountMinor: number | null; platformAmountMinor: number | null; diffMinor: number | null;
  status: string; resolution: string | null; note: string | null; recoveredOrderNo: string | null; createdAt: number; resolvedAt: number | null; resolvedBy: string | null; version: number;
}

export interface StoredValueAccountRow {
  accountNo: string; merchantNo: string; storeNo: string | null; issuerNo: string | null; customerNo: string | null; userNoMasked: string | null; currency: string;
  status: "ACTIVE" | "FROZEN" | string; principalMinor: number; bonusMinor: number; heldPrincipalMinor: number; heldBonusMinor: number; availableMinor: number;
  accountSeq: number; createdAt: number; updatedAt: number; version: number;
}
export interface StoredValueTransactionRow {
  transactionNo: string; accountNo: string; merchantNo: string; storeNo: string | null; issuerNo: string | null; currency: string; accountSeq: number;
  bizType: string; bizNo: string | null; principalDeltaMinor: number; bonusDeltaMinor: number; afterPrincipalMinor: number; afterBonusMinor: number; occurredAt: number; createdAt: number;
}

export const RATE_STATUSES = ["SCHEDULED", "ACTIVE", "SUPERSEDED", "DISABLED"] as const;
export interface ChannelCostRow {
  costNo: string; payChannel: string; payMethod: string; legalForm: string; market: string; rateBps: number; minFeeMinor: number; effectiveFrom: number;
  status: string; remark: string | null; createdAt: number; createdBy: string | null; version?: number;
}
export interface CommissionRuleRow {
  ruleNo: string; businessMode: string; trafficSource: string; rateBps: number; effectiveFrom: number; status: string; remark: string | null; createdAt: number | null; createdBy: string | null;
}

// ── P4 商户工作区（near-pay OpsMerchantAssistanceController / OpsTestMerchantController / 能力进度 / 渠道 / 主体资料）──
export interface MerchantCreation { merchantNo: string; operatingEntityId: string; name: string; market: string | null; status: string }
export interface TestMerchantContext { enabled: boolean; market: string }
export interface TestMerchantCreated { merchantNo: string; displayName: string; market: string; synthetic: boolean }
export const CAPABILITIES = ["FIAT", "STORED_VALUE", "POINTS", "CRYPTO"] as const;
export interface CapabilityItem { capability: string; stage: string; available: boolean; applicationNo: string | null; status: string | null; reviewStatus: string | null; note: string | null; entry: string | null }
export interface CapabilityProgress { merchantNo: string; items: CapabilityItem[] }
export interface MerchantChannelRow {
  payChannel: string; channelName: string | null; test: boolean; stage: "OPEN" | "APPLYING" | string; payMerchantNo: string | null; channelMerchantId: string | null;
  accountStatus: string | null; verificationStatus: string | null; boundStoreCount: number; products: { productCode: string; productName: string | null; status: string | null }[]; onboardingNo: string | null;
  onboardingStatus: string | null; reviewStatus: string | null; reason: string | null;
}
export interface MerchantChannels { merchantNo: string; rows: MerchantChannelRow[]; channels: { payChannel: string; channelName: string | null; onlineApplication: boolean }[] }
/** near-pay 实际返回 sourceSystem / capabilityType / outcome / actorRealm / actorSubject（§2.12 P6 #4）；eventType、summary 是早期设计字段，可能缺 */
export interface SourceEvent {
  eventNo?: string; eventType?: string | null; sourceSystem?: string | null; sourceEventId?: string | null; externalEntityId?: string | null;
  entityRevision?: number | null; capabilityType?: string | null; outcome?: string | null; actorRealm?: string | null; actorSubject?: string | null;
  summary?: string | null; occurredAt?: number | null; createdAt?: number | null; [k: string]: unknown;
}
/** 代填资料：6 个字段；敏感三项服务端只回脱敏值 */
export const ASSISTED_FIELDS = ["legalName", "registrationNo", "registeredAddress", "representativeName", "contactPhone", "industry"] as const;
export type AssistedField = (typeof ASSISTED_FIELDS)[number];
export interface AssistedProfileView { version: number; state: "EDITING" | "SUBMITTED" | string; profile: Record<string, string | null>; filledFields: string[]; revisionNo: string | null }
export interface MerchantConfirmResult { verifiedLinks: string[]; approvedRevisions: string[] }
export interface EntityMaterials { version: number; fields: Record<string, string | null>; documents: Record<string, string>; submittedRevision: number | null }
/** 证照识别（shop 端 /ops/onboarding-documents/recognize） */
export const DOCUMENT_TYPES = ["REGISTRATION", "OWNER_FRONT", "OWNER_BACK", "BANK_COVER"] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export interface RecognizeResult { recognized: boolean; documentType: string; fields: Record<string, string | null>; confidence: number | null }

// ── P4 进件工作区（OpsOnboardingController 草稿 / 附件 / 交接 / 人工结果 / 蓝新修改）──
export const DRAFT_STATES = ["EDITING", "CONFIRMED", "FROZEN"] as const;
export const LEGAL_FORMS = ["COMPANY", "INDIVIDUAL"] as const;
export const SETTLE_ACCOUNT_TYPES = ["CORPORATE_BANK", "PERSONAL_BANK_CARD", "MERCHANT_ID"] as const;
export const LICENSE_SLOTS = ["LICENSE_1", "LICENSE_2", "LICENSE_3", "LICENSE_4", "LICENSE_5", "LICENSE_6", "LICENSE_7", "LICENSE_8", "LICENSE_9", "LICENSE_10"] as const;
export interface SupplementHistory { materialVersion: number; source: string; fields: string[]; reason: string; createdBy: string; createdAt: number }
export interface MaterialDifference { fieldCode: string; change: "ADDED" | "REPLACED" | "RETAINED" | "REMOVED" }
/** 字段与 near-pay ops-web lib/onboarding/types.ts 的 OnboardingDraft 一致；`fields` 不含敏感值与附件引用，只在 filledFields 里标「已填」 */
export interface OnboardingDraft {
  schemaVersion?: string; frozenSchemaVersion?: string | null; representativeType?: string | null;
  channelMissing?: string[]; channelIssues?: { field: string; code: string }[];
  onboardingNo: string; expectedVersion: number; draftVersion: number; state: "EDITING" | "CONFIRMED" | "FROZEN";
  fields: Record<string, string>; filledFields: string[]; missing: string[]; editable: boolean; fileUploadAvailable: boolean;
  supplementFields: string[]; supplementReason: string | null; confirmedBy: string | null; confirmedAt: number | null; confirmationMode: string | null;
  supplementHistory: SupplementHistory[]; differences: MaterialDifference[];
}
export interface OnboardingReadiness {
  receiptStage: string; candidateStage: string | null; payMerchantNo: string | null; accountStatus: string | null; verificationStatus: string | null;
  credentialStatus: string | null; openProductCount: number; storeReadinessEvaluated: boolean; channelReady: boolean;
}
export interface OnboardingCandidate { channelMerchantId: string; subMerchantId: string | null; origin: "API" | "MANUAL"; evidenceRef: string | null; status: "PENDING" | "ACCEPTED" }
export interface OnboardingFile { slot: string; fileNo: string | null; contentType: string | null; byteLength: number | null; expiresAt: number | null; pinned: boolean; expired: boolean; legacy: boolean }
export const MANUAL_RESULTS = ["NEED_SIGN", "APPROVED", "REJECTED"] as const;
export const MODIFICATION_STATUSES = ["PENDING", "APPROVED", "SENDING", "ACCEPTED", "REJECTED", "UNKNOWN", "CANCELLED"] as const;
export interface NewebpayModification { changeNo: string; status: string; fieldCodes: string[]; maskedChanges: Record<string, string>; approvalNo: string | null; resultCode: string | null; version: number }
export interface NewebpayModificationsView { legalForm: string | null; fields: { code: string; label: string; maxLength: number; options: Record<string, string> | null }[]; requests: NewebpayModification[] }

// ── P5 渠道账户与门店绑定（near-pay OpsChannelAccountDtos，TDD §2.11）──
export const ACCOUNT_STATUSES = ["UNVERIFIED", "ACTIVE", "REFUND_ONLY", "SUSPENDED", "CLOSED"] as const;
export const VERIFICATION_STATUSES = ["PENDING", "VERIFIED", "REJECTED"] as const;
export const FUNDS_PATHS = ["DIRECT", "AGGREGATED"] as const;
export type AccountStatus = (typeof ACCOUNT_STATUSES)[number];
export type VerificationStatus = (typeof VERIFICATION_STATUSES)[number];
export type FundsPath = (typeof FUNDS_PATHS)[number];
export type AccountMode = "MERCHANT_OWN" | "SERVICE_PROVIDER" | "PLATFORM_SUB";
export interface ChannelAccountRow {
  payMerchantNo: string; ownerType: "MERCHANT" | "PLATFORM"; merchantNo: string | null; merchantName: string | null; payChannel: string; market: string; currency: string;
  channelMerchantId: string; subMerchantId: string | null; accountMode: AccountMode; fundsPath: FundsPath; parentPayMerchantNo: string | null;
  source: "ONBOARDING" | "MANUAL" | "LEGACY"; onboardingNo: string | null; evidenceRef: string | null; verificationStatus: VerificationStatus; status: AccountStatus;
  credentialKeyId: number | null; boundStoreCount: number; updatedAt: number; version: number;
}
export interface AccountProduct { productCode: string; productName: string; status: "OPEN" | "CLOSED"; source: "CHANNEL_RECEIPT" | "OPS_CONFIRMED"; evidenceRef: string | null; openedBy: string | null; openedAt: number | null }
export interface AccountStatusLog { fromStatus: string; toStatus: string; reason: string; approvalNo: string | null; operatorNo: string; changedAt: number }
export interface ChannelAccountDetail {
  account: ChannelAccountRow; verifiedBy: string | null; verifiedAt: number | null; verifyApprovalNo: string | null; verifyReason: string | null; registeredBy: string | null;
  products: AccountProduct[]; credentialVersion: number | null; settleCycle: string | null; poolAccountRef: string | null; maxSplitRate: number | null; statusLogs: AccountStatusLog[];
}
export interface ChannelAccountRegister {
  ownerType: "MERCHANT"; merchantNo: string; payChannel: string; channelMerchantId: string; subMerchantId?: string | null;
  accountMode: AccountMode; fundsPath: FundsPath; evidenceRef: string; reason: string;
}
/** 密钥只随请求发出、不回显：蓝新 hashKey+hashIv；LINE Pay / 街口 / MockPay secret；PayBy secret（私钥 PEM）+ publicMaterial */
export interface ChannelKeyEntry { secret?: string; hashKey?: string; hashIv?: string; publicMaterial?: string; reason: string }
export interface StoreBinding {
  bindingNo: string; storeNo: string; merchantNo: string | null; payChannel: string; payMerchantNo: string; status: "ACTIVE" | "INACTIVE"; reason: string; approvalNo: string | null;
  boundBy: string; effectiveFrom: number; effectiveTo: number | null; endedReason: string | null; pendingTargetPayMerchantNo: string | null; pendingReason: string | null; version: number;
}
export interface BindingImpact {
  bindingNo: string; currentPayMerchantNo: string; targetPayMerchantNo: string; targetBindable: boolean; blockedReason: string | null;
  productsKept: string[]; productsLost: string[]; productsGained: string[]; inflightPayments: "NONE" | "SOME" | "UNKNOWN";
}
export const CLAIM_STATES = ["DRAFT", "PENDING_VERIFY", "LINKED", "REJECTED"] as const;
export interface ClaimFile { fileNo: string; contentType: string; byteLength: number; expiresAt: number; pinned: boolean; expired: boolean }
export interface ChannelAccountClaim {
  claimNo: string; requestNo: string; merchantNo: string; payChannel: string; channelMerchantMasked: string; state: (typeof CLAIM_STATES)[number];
  version: number; files: ClaimFile[]; reason: string | null; payMerchantNo: string | null; accountVerificationStatus: string | null; accountStatus: string | null;
  channelMerchantId?: string; subMerchantId?: string | null;
}
export interface ClaimResolve { expectedVersion: number; result: "LINKED" | "REJECTED"; payMerchantNo?: string; expectedAccountVersion?: number; reason: string }
/** 转人工（near-pay EscalatedRow：订单 + 跟进记录） */
export interface FollowupRow { id: number; operation: string; payChannel: string; status: string; recoveryPath: string | null; attempts: number; nextAt: number | null; lastResult: string | null; resolvedAt: number | null; createdAt: number | null; payOrderNo: string }
export interface EscalatedRow { order: OrderRow; followup: FollowupRow }
export interface AdapterRow { adapterCode: string; channelType: string; operations: string[]; accountModes: string[]; channelMethods: string[]; tradeModes: string[] }
export interface PaymentMethodRow { code: string; name: string; category: string }

// ════ P6（2026-11-21，TDD-支付中心运营页面 §2.12） ════

// ── 渠道账户转移（换主体）：near-pay OpsChannelAccountTransferController.TransferDto ──
export const TRANSFER_STATUSES = ["PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED", "EXECUTED"] as const;
export interface ChannelAccountTransfer {
  transferNo: string; requestNo: string; payMerchantNo: string; fromMerchantNo: string; toMerchantNo: string; evidence: string;
  expectedVersion: number; status: string; approvalNo: string | null; approvalStatus: string | null; approvalVersion: number | null;
  requestedBy: string | null; executedBy: string | null; executedAt: number | null;
  beforeSnapshot: Record<string, unknown> | null; afterSnapshot: Record<string, unknown> | null; createdAt: number; version: number;
}
export interface TransferCreate { payMerchantNo: string; fromMerchantNo: string; toMerchantNo: string; evidence: string; expectedVersion: number }

// ── 渠道资质版本：near-pay QualificationService.Version（validUntil 是 Instant，可能序列化成 ISO 串或毫秒数） ──
export const QUALIFICATION_STATUSES = ["DRAFT", "ACTIVE", "REVOKED"] as const;
export interface QualificationVersion {
  ref: string; revision: number; channel: string; purpose: string; coordinates: Record<string, unknown> | null; coordinateKey: string | null;
  payloadJson: string; payloadSha256: string; status: string; maker: string | null; approvalNo: string | null; validUntil: string | number | null; version: number;
}
export interface QualificationImport { evidenceNo: string; revision: number; channel: string; purpose: string; payloadJson: string; validUntil: string; reason: string }

