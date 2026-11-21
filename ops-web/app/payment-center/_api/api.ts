// 支付中心各页签的接口层：一个接口，两种实现（HTTP / mock），按 NEXT_PUBLIC_USE_MOCK 切换（与 lib/api 同一个开关）。
// 路径、查询参数、命令体与 near-pay /pay-ops/v2 一一对应（TDD-支付中心运营页面 §2.6，契约核对见 §6）。
import { PayOpsError, requestNoFor, type PayOpsClient, type PayPage } from "./client";
import type {
  AttemptListRow, ChannelCostRow, CommissionRuleRow, LedgerEntryRow, LedgerVoucher, PayoutRow, ReconDiffRow, SettlementRow,
  StoredValueAccountRow, StoredValueTransactionRow, WithdrawalRow,
  AlertEventRow, AlertRuleRow, AppDetail, AppGrant, AppRow, ApprovalFlowRow, ChannelCallDetail, ChannelCallRow, ChannelCallbackDetail, ChannelCallbackRow,
  CredentialIssued, OperatingSettings, ProfileRevisionDetail, RefundListRow, RequestLogDetail, RequestLogRow, TerminalRow,
  ActionResult, ApprovalDetail, StoreMethodSave, StoreMethodView, StoreQrCode, StoreQrCreate, StoreSyncEvent, ChannelAccountTransfer, QualificationImport, QualificationVersion, TransferCreate, ApprovalRow, ApprovalTask, ApprovalView, ChannelRow, CollectionPolicyView, DataScopeRow, Detail, EntryRow,
  ExceptionRow, MerchantDetail, MerchantRow, OnboardingDetail, OnboardingRow, OrderDetail, OrderLogEntry, OrderRow, ProductRow, StoreDetail, StoreRow,
  AssistedProfileView, CapabilityProgress, EntityMaterials, MerchantChannels, MerchantConfirmResult, MerchantCreation, SourceEvent, TestMerchantContext, TestMerchantCreated,
  NewebpayModification, NewebpayModificationsView, OnboardingDraft, OnboardingFile, OnboardingReadiness, DocumentType, RecognizeResult,
  CredentialTypeRow, CredentialPolicy, CredentialPolicySave, CredentialRow, CredentialDetail, CredentialAccessLog, CredentialVersion, CredentialVersionEntry, CredentialBindingRow, AuditEventRow,
  ChannelAccountUpdate, QualificationPrepare, ClaimCommandReceipt, TerminalCredentialReference, TerminalRetirement, AlertMetric, ReversalRecheck,
  MerchantMemberRow, MemberCreate, ProfileRevisionRow, MerchantWorkspace, AssistedOperation, DraftCommandOutcome, RepairRow, RepairCreate,
  AdapterRow, BindingImpact, ChannelAccountClaim, ChannelAccountDetail, ChannelAccountRegister, ChannelAccountRow, ChannelKeyEntry, ClaimResolve, EscalatedRow, PaymentMethodRow, StoreBinding,
} from "./types";

export type Q = Record<string, string | number | boolean | undefined>;
/** 通用状态命令体（near-pay `Command`）；requestNo 由客户端按请求体生成 */
export interface Cmd { expectedVersion: number; reason: string }
/** 进件草稿命令：申请版本 + 草稿版本（near-pay OnboardingDraftCommand） */
export interface DraftCmd { expectedVersion: number; expectedDraftVersion: number }

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
    /** 转人工（near-pay 自动恢复用尽、待人工跟进的订单） */
    escalations(q: Q): Promise<PayPage<EscalatedRow>>;
  };
  merchants: {
    list(q: Q): Promise<PayPage<MerchantRow>>;
    detail(no: string): Promise<Detail<MerchantDetail>>;
    suspend(no: string, c: Cmd): Promise<ActionResult>;
    activate(no: string, c: Cmd): Promise<ActionResult>;
    /** P8：资料版本（near-pay 回数组，不分页）、工作台、两个写操作的回执（超时恢复用，页面不直调） */
    revisions(no: string, q?: { purpose?: string; reviewStatus?: string }): Promise<ProfileRevisionRow[]>;
    workspace(no: string): Promise<MerchantWorkspace>;
    creationByRequest(requestNo: string): Promise<MerchantCreation>;
    assistedOperation(no: string, requestNo: string): Promise<AssistedOperation>;
    // P4 商户工作区
    creationContext(): Promise<{ enabled: boolean }>;
    create(body: { operatingEntityId: string; name: string }): Promise<MerchantCreation>;
    testContext(): Promise<TestMerchantContext>;
    createTest(body: { batchNo: string }): Promise<TestMerchantCreated>;
    capability(no: string): Promise<CapabilityProgress>;
    channels(no: string): Promise<MerchantChannels>;
    policy(no: string): Promise<CollectionPolicyView>;
    savePolicy(no: string, body: { expectedVersion: number; mode: string; productCodes: string[]; reason: string }): Promise<ActionResult>;
    sourceEvents(no: string, q: Q): Promise<PayPage<SourceEvent>>;
    assistedDraft(no: string): Promise<AssistedProfileView>;
    saveAssisted(no: string, body: { expectedVersion: number; fields: Record<string, string | null> }): Promise<AssistedProfileView>;
    submitAssisted(no: string, body: { expectedVersion: number }): Promise<AssistedProfileView>;
    verifyLink(no: string, linkNo: string, body: { expectedVersion: number; result: "VERIFIED" | "MISMATCH"; reason: string }): Promise<ActionResult>;
    confirm(no: string, body: { reason?: string }): Promise<MerchantConfirmResult>;
    entityMaterials(no: string, revisionNo: string): Promise<EntityMaterials>;
    entityFile(no: string, revisionNo: string, fileNo: string): Promise<Blob>;
    entityRequestMore(no: string, revisionNo: string, body: { expectedVersion: number; reason: string; fields: string[]; documents: string[] }): Promise<unknown>;
  };
  stores: {
    list(q: Q): Promise<PayPage<StoreRow>>;
    detail(no: string): Promise<Detail<StoreDetail>>;
    policy(no: string): Promise<CollectionPolicyView>;
    savePolicy(no: string, body: { expectedVersion: number; mode: string; productCodes: string[]; reason: string }): Promise<ActionResult>;
    requestChange(no: string, body: { expectedVersion: number; targetMerchantNo: string; reason: string }): Promise<ActionResult>;
    cancelChange(no: string, c: Cmd): Promise<ActionResult>;
    executeChange(no: string, c: Cmd): Promise<ActionResult>;
    /** 收款码：near-pay 建码非幂等（先查后建），停码带视图里的版本。§2.13 */
    qrCodes(no: string): Promise<StoreQrCode[]>;
    createQrCode(no: string, body: StoreQrCreate): Promise<StoreQrCode>;
    disableQrCode(no: string, qrCode: string, c: Cmd): Promise<ActionResult>;
    /** 支付方式：保存时 near-pay 换算成门店收款策略。§2.13 */
    methods(no: string): Promise<StoreMethodView>;
    saveMethods(no: string, body: StoreMethodSave): Promise<StoreMethodView>;
    syncEvents(no: string, q: Q): Promise<PayPage<StoreSyncEvent>>;
  };
  onboardings: {
    list(q: Q): Promise<PayPage<OnboardingRow>>;
    detail(no: string): Promise<Detail<OnboardingDetail>>;
    create(body: { merchantNo: string; payChannel: string }): Promise<Detail<OnboardingDetail>>;
    submit(no: string, body: { expectedVersion: number; materialVersion: number; materials: OnboardingMaterials }): Promise<ActionResult>;
    review(no: string, body: { expectedVersion: number; decision: "APPROVE" | "REJECT" | "REQUEST_MORE"; reason: string; materialVersion: number }): Promise<ActionResult>;
    dispatch(no: string, body: { expectedVersion: number; channelApplyNo?: string | null; reason: string }): Promise<ActionResult>;
    refresh(no: string, c: Cmd): Promise<ActionResult>;
    cancel(no: string, c: Cmd): Promise<ActionResult>;
    // P4 进件工作区
    draft(no: string): Promise<OnboardingDraft>;
    saveDraft(no: string, body: DraftCmd & { fields: Record<string, string | null> }): Promise<OnboardingDraft>;
    confirmDraft(no: string, body: DraftCmd): Promise<OnboardingDraft>;
    submitDraft(no: string, body: DraftCmd): Promise<OnboardingDraft>;
    requestMoreFields(no: string, body: DraftCmd & { fields: string[]; reason: string }): Promise<OnboardingDraft>;
    readiness(no: string): Promise<OnboardingReadiness>;
    /** P8：草稿命令是否已生效（超时恢复用） */
    draftCommand(no: string, requestNo: string): Promise<DraftCommandOutcome>;
    retryHandoff(no: string, c: Cmd): Promise<{ onboardingNo: string; version: number; candidateStatus: string | null }>;
    /** 敏感字段封存：明文只走这一个口，换回 cipherRef 再写草稿（near-pay 不收幂等号） */
    seal(no: string, body: { expectedVersion: number; fieldCode: string; secret: string }): Promise<{ fieldCode: string; cipherRef: string }>;
    files(no: string): Promise<OnboardingFile[]>;
    upload(no: string, fields: { slot: string; expectedVersion: number; expectedDraftVersion: number }, file: File): Promise<{ draft: OnboardingDraft; attachment: OnboardingFile }>;
    file(no: string, fileNo: string): Promise<Blob>;
    manualResult(no: string, body: { expectedVersion: number; result: string; signUrl?: string | null; channelMerchantId?: string | null; subMerchantId?: string | null; evidenceRef?: string | null; reason: string }): Promise<ActionResult>;
    modifications(no: string): Promise<NewebpayModificationsView>;
    createModification(no: string, body: { expectedVersion: number; changes: Record<string, string> }): Promise<NewebpayModification>;
    dispatchModification(no: string, changeNo: string, c: Cmd): Promise<NewebpayModification>;
    cancelModification(no: string, changeNo: string, c: Cmd): Promise<NewebpayModification>;
  };
  documents: {
    recognize(file: File, documentType: DocumentType): Promise<RecognizeResult>;
  };
  approvals: {
    /** 来源页的「提交审批」：冻结当前版本（subjectRevision = 版本 + 1），批与驳在审核待办 */
    submit(body: { subjectType: string; subjectRef: string; subjectRevision: number; action: string; reason: string }): Promise<{ approvalNo: string }>;
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
    /** 标为待开通（运营维护的状态，不进收银台） */
    pending(code: string, c: Cmd): Promise<ActionResult>;
    adapters(): Promise<AdapterRow[]>;
  };
  products: {
    list(q: Q): Promise<PayPage<ProductRow>>;
    detail(code: string): Promise<Detail<ProductRow>>;
    create(body: ProductSave): Promise<Detail<ProductRow>>;
    update(code: string, body: ProductSave): Promise<ActionResult>;
    enable(code: string, c: Cmd): Promise<ActionResult>;
    disable(code: string, c: Cmd): Promise<ActionResult>;
    paymentMethods(): Promise<PaymentMethodRow[]>;
  };
  /** 渠道账户（商户「渠道账户」抽屉，TDD §2.11）；状态命令用 Cmd（版本 + 理由） */
  channelAccounts: {
    list(q: Q): Promise<PayPage<ChannelAccountRow>>;
    detail(no: string): Promise<Detail<ChannelAccountDetail>>;
    register(body: ChannelAccountRegister): Promise<Detail<ChannelAccountDetail>>;
    reject(no: string, c: Cmd): Promise<ActionResult>;
    activate(no: string, c: Cmd): Promise<ActionResult>;
    suspend(no: string, c: Cmd): Promise<ActionResult>;
    refundOnly(no: string, c: Cmd): Promise<ActionResult>;
    /** P9：只在 UNVERIFIED 可改坐标，改完核验退回 PENDING */
    update(no: string, body: ChannelAccountUpdate): Promise<ActionResult>;
    openProduct(no: string, productCode: string, body: { expectedVersion: number; evidenceRef: string; reason: string }): Promise<ActionResult>;
    closeProduct(no: string, productCode: string, c: Cmd): Promise<ActionResult>;
    bindCredential(no: string, body: { expectedVersion: number; keyId: number; reason: string }): Promise<ActionResult>;
    enterKey(no: string, body: ChannelKeyEntry): Promise<Detail<ChannelAccountDetail>>;
    bindings(no: string): Promise<PayPage<StoreBinding>>;
  };
  /** 渠道账户转移（换主体）：发起 → 审核通过 / 驳回（审批单版本）→ 执行（申请版本）。§2.12 */
  transfers: {
    list(q: Q): Promise<PayPage<ChannelAccountTransfer>>;
    detail(no: string): Promise<Detail<ChannelAccountTransfer>>;
    create(body: TransferCreate): Promise<Detail<ChannelAccountTransfer>>;
    approve(no: string, c: Cmd): Promise<ActionResult>;
    reject(no: string, c: Cmd): Promise<ActionResult>;
    execute(no: string, c: Cmd): Promise<ActionResult>;
  };
  /** 渠道资质版本：导入为草稿，激活走 CHANNEL_QUALIFICATION 审批，吊销直接生效。§2.12 */
  qualifications: {
    ofAccount(payMerchantNo: string): Promise<QualificationVersion[]>;
    /** P9：沙箱试运行资质准备（建 DRAFT，经审批生效） */
    prepare(payMerchantNo: string, body: QualificationPrepare): Promise<QualificationVersion>;
    detail(ref: string): Promise<QualificationVersion>;
    importVersion(body: QualificationImport): Promise<QualificationVersion>;
    revoke(ref: string, reason: string): Promise<string>;
  };
  /** 门店渠道绑定（门店「渠道绑定」抽屉）；换绑执行在审批通过后由审核待办触发，这里不直调 */
  storeBindings: {
    list(storeNo: string): Promise<PayPage<StoreBinding>>;
    bind(storeNo: string, body: { payChannel: string; payMerchantNo: string; reason: string }): Promise<Detail<StoreBinding>>;
    unbind(storeNo: string, bindingNo: string, c: Cmd): Promise<ActionResult>;
    impact(storeNo: string, bindingNo: string, targetPayMerchantNo: string): Promise<BindingImpact>;
    rebindRequest(storeNo: string, bindingNo: string, body: { expectedVersion: number; targetPayMerchantNo: string; reason: string }): Promise<ActionResult>;
    rebindWithdraw(storeNo: string, bindingNo: string, c: Cmd): Promise<ActionResult>;
    /** P8：换绑审批通过后执行（审批权限；执行人不能是登记换绑的人）；resourceNo 是新绑定号 */
    rebind(storeNo: string, bindingNo: string, c: Cmd): Promise<ActionResult>;
  };
  /** P10 凭证管理（near-pay OpsCredentialController）：只有 generate 的响应带一次性明文；启用要审批（启用人 ≠ 录入人） */
  credentials: {
    types(): Promise<CredentialTypeRow[]>;
    policies(): Promise<CredentialPolicy[]>;
    savePolicy(ownerType: string, body: CredentialPolicySave): Promise<CredentialPolicy>;
    list(q: Q): Promise<PayPage<CredentialRow>>;
    detail(keyId: number): Promise<CredentialDetail>;
    accessLogs(keyId: number): Promise<CredentialAccessLog[]>;
    register(body: { credentialType: string; ownerRef: string; reason: string }): Promise<CredentialRow>;
    addVersion(keyId: number, body: CredentialVersionEntry): Promise<CredentialVersion>;
    generate(keyId: number, reason: string): Promise<CredentialIssued>;
    verify(keyId: number, versionIndex: number, body: { evidenceRef: string | null; reason: string }): Promise<CredentialVersion>;
    enable(keyId: number, versionIndex: number, body: { approvalRef: string | null; reason: string }): Promise<CredentialVersion>;
    revoke(keyId: number, versionIndex: number, reason: string): Promise<CredentialVersion>;
    bindings(days?: number): Promise<CredentialBindingRow[]>;
  };
  /** P10 审计事件（near-pay OpsAuditController，只读） */
  audits: {
    list(q: Q): Promise<PayPage<AuditEventRow>>;
    detail(no: string): Promise<Detail<AuditEventRow>>;
  };
  /** P8 商户成员（near-pay OpsMemberController）；新增只在商户没有生效管理员时可用 */
  merchantMembers: {
    list(q: Q): Promise<PayPage<MerchantMemberRow>>;
    detail(no: string): Promise<Detail<MerchantMemberRow>>;
    create(body: MemberCreate): Promise<Detail<MerchantMemberRow>>;
    command(no: string, action: "suspend" | "resume" | "revoke", c: Cmd): Promise<ActionResult>;
  };
  /** P8 来源关联修复（near-pay OpsSourceAssociationRepairController）；建单自动提交审批，双人：申请人不能自审 */
  repairs: {
    list(q: Q): Promise<PayPage<RepairRow>>;
    detail(no: string): Promise<Detail<RepairRow>>;
    create(body: RepairCreate): Promise<Detail<RepairRow>>;
    /** approve / reject 的 expectedVersion 是审批版本（RepairRow.approvalVersion）；execute 的是修复单版本 */
    decide(no: string, action: "approve" | "reject" | "execute", c: Cmd): Promise<ActionResult>;
  };
  /** 商户号认领（实例未开放时 near-pay 回 404） */
  claims: {
    list(merchantNo: string): Promise<ChannelAccountClaim[]>;
    detail(no: string): Promise<ChannelAccountClaim>;
    resolve(no: string, body: ClaimResolve): Promise<ChannelAccountClaim>;
    /** P9：认领命令回执（超时恢复用） */
    command(no: string, requestNo: string): Promise<ClaimCommandReceipt>;
    file(no: string, fileNo: string): Promise<Blob>;
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
    /** 不分页，最多 200 条，新的在前；页面在前端分页（shop /ops/payments/refunds） */
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
    /** P9：凭证引用（需 pay:credential:view）与退役状态 */
    credentialReference(no: string): Promise<TerminalCredentialReference>;
    retirement(no: string): Promise<TerminalRetirement>;
    lost(no: string): Promise<unknown>;
    retire(no: string, c: Cmd): Promise<unknown>;
  };
  apps: {
    list(q: Q): Promise<PayPage<AppRow>>;
    detail(no: string): Promise<Detail<AppDetail>>;
    grants(no: string): Promise<PayPage<AppGrant>>;
    /** P9：撤销某个门店的授权；expectedVersion 是授权的版本 */
    revokeGrant(no: string, storeNo: string, c: Cmd): Promise<ActionResult>;
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
    /** P9：指标目录（单位、默认窗口、适用范围）与冲正回查（无请求体，回 VERIFIED / PENDING / DEFERRED） */
    metrics(): Promise<AlertMetric[]>;
    recheckReversal(no: string): Promise<ReversalRecheck>;
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
    transaction(no: string): Promise<Detail<StoredValueTransactionRow>>;
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

/**
 * 退款端口：near-pay 的退款只有 /pay-ops/v1，而 nginx 对公网只转 /pay-ops/v2；
 * 所以退款走 shop 后端已有的转发 /ops/payments/refunds（同一个运营会话、同一组码）。
 */
export interface RefundPort {
  list(q: { status?: string; limit: number }): Promise<RefundListRow[]>;
  review(payRefundNo: string, body: { approve: boolean; reason: string }): Promise<RefundListRow>;
}

/** 证照识别端口：shop 后端 `/ops/onboarding-documents/recognize`（不是 near-pay；结果只填表单，不落库） */
export interface DocumentPort {
  recognize(file: File, documentType: DocumentType): Promise<RecognizeResult>;
}

/** 走 shop 后端的那几个口 */
export interface ShopPorts { refunds: RefundPort; documents: DocumentPort }

const unset = () => Promise.reject(new Error("shop port not configured"));
const NO_PORTS: ShopPorts = { refunds: { list: unset, review: unset }, documents: { recognize: unset } };

/** 网络中断或 5xx：命令可能已经生效，按同一个幂等号查回执再决定（TDD-支付中心运营页面 §2.15 P8） */
function transient(err: unknown): boolean {
  return !(err instanceof PayOpsError) || err.status >= 500;
}
/** 回执明确说命令被拒（不是「查不到回执」）：要把回执里的原因交给页面 */
class ReceiptRejected extends PayOpsError {}
async function recover<T>(send: () => Promise<T>, receipt: () => Promise<T | null>): Promise<T> {
  try {
    return await send();
  } catch (err) {
    if (!transient(err)) throw err;
    const r = await receipt().catch((e: unknown) => { if (e instanceof ReceiptRejected) throw e; return null; });
    if (r == null) throw err;
    return r;
  }
}

export function createHttpPayCenterApi(c: PayOpsClient, ports: Partial<ShopPorts> = {}): PayCenterApi {
  const { refunds, documents } = { ...NO_PORTS, ...ports };
  const crud = <Row, Save extends object>(base: string) => ({
    list: (q: Q) => c.page<Row>(base, q),
    detail: (id: string) => detailOf<Row>(c, `${base}/${e(id)}`),
    create: (body: Save) => c.command<Detail<Row>>(base, body as Record<string, unknown> as never).then((d) => ({ ...d, allowedActions: d.allowedActions ?? [], timeline: d.timeline ?? [] })),
    update: (id: string, body: Save) => c.command<ActionResult>(`${base}/${e(id)}`, body as never, { method: "PUT" }),
  });
  /** 进件草稿写命令：超时后按幂等号查是否已生效，生效了就回最新草稿 */
  const draftCmd = (no: string, suffix: string, body: Record<string, unknown>, method?: "PUT") => {
    const path = `/onboardings/${e(no)}/draft${suffix}`;
    return recover<OnboardingDraft>(() => c.command(path, body, method ? { method } : {}),
      async () => ((await c.get<DraftCommandOutcome>(`/onboardings/${e(no)}/draft/commands/${e(requestNoFor(path, body))}`)).applied
        ? c.get<OnboardingDraft>(`/onboardings/${e(no)}/draft`) : null));
  };
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
      escalations: (q) => c.page("/payment-escalations", q),
    },
    merchants: {
      list: (q) => c.page("/merchants", q),
      detail: (no) => detailOf(c, `/merchants/${e(no)}`),
      suspend: (no, cmd) => c.command(`/merchants/${e(no)}/suspend`, { ...cmd }),
      activate: (no, cmd) => c.command(`/merchants/${e(no)}/activate`, { ...cmd }),
      creationContext: () => c.get("/merchant-creations/context"),
      create: (body) => recover(() => c.command("/merchant-creations", { ...body }),
        () => c.get(`/merchant-creations/by-request/${e(requestNoFor("/merchant-creations", { ...body }))}`)),
      testContext: () => c.get("/test-merchants/context"),
      createTest: (body) => c.command("/test-merchants", { ...body }),
      capability: (no) => c.get(`/merchants/${e(no)}/capability-progress`),
      channels: (no) => c.get(`/merchants/${e(no)}/channels`),
      policy: (no) => c.get(`/merchants/${e(no)}/collection-policy`),
      savePolicy: (no, body) => c.command(`/merchants/${e(no)}/collection-policy`, { ...body }, { method: "PUT" }),
      sourceEvents: (no, q) => c.page(`/merchants/${e(no)}/source-events`, q),
      assistedDraft: (no) => c.get(`/merchants/${e(no)}/assisted-profile/draft`),
      saveAssisted: (no, body) => recover(() => c.command(`/merchants/${e(no)}/assisted-profile/draft`, { ...body }, { method: "PUT" }),
        () => c.get<AssistedOperation>(`/merchants/${e(no)}/assisted-profile/operations/${e(requestNoFor(`/merchants/${e(no)}/assisted-profile/draft`, { ...body }))}`).then((o) => o.current)),
      submitAssisted: (no, body) => recover(() => c.command(`/merchants/${e(no)}/assisted-profile/submit`, { ...body, fields: {} }),
        () => c.get<AssistedOperation>(`/merchants/${e(no)}/assisted-profile/operations/${e(requestNoFor(`/merchants/${e(no)}/assisted-profile/submit`, { ...body, fields: {} }))}`).then((o) => o.current)),
      revisions: (no, q) => c.get(`/merchants/${e(no)}/profile-revisions`, { ...q }),
      workspace: (no) => c.get(`/merchants/${e(no)}/workspace`),
      creationByRequest: (rn) => c.get(`/merchant-creations/by-request/${e(rn)}`),
      assistedOperation: (no, rn) => c.get(`/merchants/${e(no)}/assisted-profile/operations/${e(rn)}`),
      verifyLink: (no, link, body) => c.command(`/merchants/${e(no)}/source-links/${e(link)}/verify`, { ...body }),
      confirm: (no, body) => c.command(`/merchants/${e(no)}/confirm`, { ...body }),
      entityMaterials: (no, rev) => c.get(`/merchants/${e(no)}/profile-revisions/${e(rev)}/entity-materials`),
      entityFile: (no, rev, file) => c.blob(`/merchants/${e(no)}/profile-revisions/${e(rev)}/entity-materials/files/${e(file)}`, "application/octet-stream"),
      entityRequestMore: (no, rev, body) => c.command(`/merchants/${e(no)}/profile-revisions/${e(rev)}/entity-materials/request-more`, { ...body }),
    },
    stores: {
      list: (q) => c.page("/stores", q),
      detail: (no) => detailOf(c, `/stores/${e(no)}`),
      policy: (no) => c.get(`/stores/${e(no)}/collection-policy`),
      savePolicy: (no, body) => c.command(`/stores/${e(no)}/collection-policy`, { ...body }, { method: "PUT" }),
      requestChange: (no, body) => c.command(`/stores/${e(no)}/association-change`, { ...body }),
      cancelChange: (no, cmd) => c.command(`/stores/${e(no)}/association-change/cancel`, { ...cmd }),
      executeChange: (no, cmd) => c.command(`/stores/${e(no)}/association-change/execute`, { ...cmd }),
      qrCodes: (no) => c.get(`/stores/${e(no)}/qr-codes`),
      // 建码非幂等：不带自动幂等号，避免同一请求体被当成重放而吞掉第二个码
      createQrCode: (no, body) => c.command(`/stores/${e(no)}/qr-codes`, { ...body }, { idempotent: false }),
      disableQrCode: (no, code, cmd) => c.command(`/stores/${e(no)}/qr-codes/${e(code)}/disable`, { ...cmd }),
      methods: (no) => c.get(`/stores/${e(no)}/method-preferences`),
      saveMethods: (no, body) => c.command(`/stores/${e(no)}/method-preferences`, { ...body }, { method: "PUT" }),
      syncEvents: (no, q) => c.page(`/stores/${e(no)}/sync-events`, q),
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
      draft: (no) => c.get(`/onboardings/${e(no)}/draft`),
      saveDraft: (no, body) => draftCmd(no, "", { ...body }, "PUT"),
      confirmDraft: (no, body) => draftCmd(no, "/confirm", { ...body, fields: {} }),
      submitDraft: (no, body) => draftCmd(no, "/submit", { ...body, fields: {} }),
      draftCommand: (no, rn) => c.get(`/onboardings/${e(no)}/draft/commands/${e(rn)}`),
      requestMoreFields: (no, body) => c.command(`/onboardings/${e(no)}/draft/request-more`, { ...body }),
      readiness: (no) => c.get(`/onboardings/${e(no)}/draft/readiness`),
      retryHandoff: (no, cmd) => c.command(`/onboardings/${e(no)}/draft/handoff/retry`, { ...cmd }),
      seal: (no, body) => c.command(`/onboardings/${e(no)}/newebpay-materials`, { ...body }, { idempotent: false }),
      files: (no) => c.get(`/onboardings/${e(no)}/files`),
      upload: (no, fields, file) => c.upload(`/onboardings/${e(no)}/files`, fields, file),
      file: (no, fileNo) => c.blob(`/onboardings/${e(no)}/files/${e(fileNo)}`),
      manualResult: (no, body) => c.command(`/onboardings/${e(no)}/manual-result`, { ...body }),
      modifications: (no) => c.get(`/onboardings/${e(no)}/newebpay-modifications`),
      createModification: (no, body) => c.command(`/onboardings/${e(no)}/newebpay-modifications`, { ...body }),
      dispatchModification: (no, ch, cmd) => c.command(`/onboardings/${e(no)}/newebpay-modifications/${e(ch)}/dispatch`, { ...cmd }),
      cancelModification: (no, ch, cmd) => c.command(`/onboardings/${e(no)}/newebpay-modifications/${e(ch)}/cancel`, { ...cmd }),
    },
    documents: { recognize: (file, type) => documents.recognize(file, type) },
    approvals: {
      submit: (body) => c.command("/approvals", { ...body }),
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
      pending: (code, cmd) => c.command(`/channels/${e(code)}/pending`, { ...cmd }),
      adapters: () => c.get("/adapters"),
    },
    products: {
      ...crud<ProductRow, ProductSave>("/products"),
      enable: (code, cmd) => c.command(`/products/${e(code)}/enable`, { ...cmd }),
      disable: (code, cmd) => c.command(`/products/${e(code)}/disable`, { ...cmd }),
      paymentMethods: () => c.get("/payment-methods"),
    },
    channelAccounts: {
      list: (q) => c.page("/channel-accounts", q),
      detail: (no) => detailOf(c, `/channel-accounts/${e(no)}`),
      register: (body) => c.command(`/channel-accounts`, { ...body }),
      reject: (no, cmd) => c.command(`/channel-accounts/${e(no)}/verify`, { ...cmd, result: "REJECTED" }),
      activate: (no, cmd) => c.command(`/channel-accounts/${e(no)}/activate`, { ...cmd }),
      suspend: (no, cmd) => c.command(`/channel-accounts/${e(no)}/suspend`, { ...cmd }),
      refundOnly: (no, cmd) => c.command(`/channel-accounts/${e(no)}/refund-only`, { ...cmd }),
      update: (no, body) => c.command(`/channel-accounts/${e(no)}`, { ...body }, { method: "PUT" }),
      openProduct: (no, code, body) => c.command(`/channel-accounts/${e(no)}/products/${e(code)}/open`, { ...body, source: "OPS_CONFIRMED" }),
      closeProduct: (no, code, cmd) => c.command(`/channel-accounts/${e(no)}/products/${e(code)}/close`, { ...cmd }),
      bindCredential: (no, body) => c.command(`/channel-accounts/${e(no)}/credential`, { ...body }, { method: "PUT" }),
      enterKey: (no, body) => c.command(`/channel-accounts/${e(no)}/migration-credential`, { ...body }),
      bindings: (no) => c.page(`/channel-accounts/${e(no)}/bindings`, { size: 100 }),
    },
    transfers: {
      list: (q) => c.page("/channel-account-transfers", q),
      detail: (no) => detailOf(c, `/channel-account-transfers/${e(no)}`),
      create: (body) => c.command("/channel-account-transfers", { ...body }),
      approve: (no, cmd) => c.command(`/channel-account-transfers/${e(no)}/approve`, { ...cmd }),
      reject: (no, cmd) => c.command(`/channel-account-transfers/${e(no)}/reject`, { ...cmd }),
      execute: (no, cmd) => c.command(`/channel-account-transfers/${e(no)}/execute`, { ...cmd }),
    },
    qualifications: {
      ofAccount: (no) => c.get(`/channel-accounts/${e(no)}/qualifications`),
      prepare: (no, body) => c.command(`/channel-accounts/${e(no)}/qualifications`, { ...body }),
      detail: (ref) => c.get(`/channel-qualifications/${e(ref)}`),
      importVersion: (body) => c.command("/channel-qualifications", { ...body }),
      revoke: (ref, reason) => c.command(`/channel-qualifications/${e(ref)}/revoke`, { reason }),
    },
    storeBindings: {
      list: (store) => c.page(`/stores/${e(store)}/bindings`, { size: 100 }),
      bind: (store, body) => c.command(`/stores/${e(store)}/bindings`, { ...body }),
      unbind: (store, b, cmd) => c.command(`/stores/${e(store)}/bindings/${e(b)}/unbind`, { ...cmd }),
      impact: (store, b, target) => c.get(`/stores/${e(store)}/bindings/${e(b)}/rebind-impact`, { targetPayMerchantNo: target }),
      rebindRequest: (store, b, body) => c.command(`/stores/${e(store)}/bindings/${e(b)}/rebind-request`, { ...body }),
      rebindWithdraw: (store, b, cmd) => c.command(`/stores/${e(store)}/bindings/${e(b)}/rebind-request/withdraw`, { ...cmd }),
      rebind: (store, b, cmd) => c.command(`/stores/${e(store)}/bindings/${e(b)}/rebind`, { ...cmd }),
    },
    credentials: {
      types: () => c.get("/credential-types"),
      policies: () => c.get("/credential-policies"),
      savePolicy: (owner, body) => c.command(`/credential-policies/${e(owner)}`, { ...body }, { method: "PUT" }),
      list: (q) => c.page("/credentials", q),
      detail: (id) => c.get(`/credentials/${id}`),
      accessLogs: (id) => c.get(`/credentials/${id}/access-logs`),
      register: (body) => c.command("/credentials", { ...body }),
      // 明文只写不读：不带自动幂等号，避免请求体指纹里出现密钥材料
      addVersion: (id, body) => c.command(`/credentials/${id}/versions`, { ...body }, { idempotent: false }),
      // 生成的明文只在这次响应里出现一次；不重放
      generate: (id, reason) => c.command(`/credentials/${id}/versions/generate`, { reason }, { idempotent: false }),
      verify: (id, v, body) => c.command(`/credentials/${id}/versions/${v}/verify`, { ...body }),
      enable: (id, v, body) => c.command(`/credentials/${id}/versions/${v}/enable`, { ...body }),
      revoke: (id, v, reason) => c.command(`/credentials/${id}/versions/${v}/revoke`, { reason }),
      bindings: (days) => c.get("/credential-bindings", days ? { days } : undefined),
    },
    audits: {
      list: (q) => c.page("/audit-events", q),
      detail: (no) => detailOf(c, `/audit-events/${e(no)}`),
    },
    merchantMembers: {
      list: (q) => c.page("/merchant-members", q),
      detail: (no) => detailOf(c, `/merchant-members/${e(no)}`),
      create: (body) => c.command("/merchant-members", { ...body }),
      command: (no, action, cmd) => c.command(`/merchant-members/${e(no)}/${action}`, { ...cmd }),
    },
    repairs: {
      list: (q) => c.page("/source-association-repair-requests", q),
      detail: (no) => detailOf(c, `/source-association-repair-requests/${e(no)}`),
      create: (body) => c.command("/source-association-repair-requests", { ...body }),
      decide: (no, action, cmd) => c.command(`/source-association-repair-requests/${e(no)}/${action}`, { ...cmd }),
    },
    claims: {
      list: (merchantNo) => c.get("/channel-account-claims", { merchantNo }),
      detail: (no) => c.get(`/channel-account-claims/${e(no)}`),
      resolve: (no, body) => recover(() => c.command(`/channel-account-claims/${e(no)}/resolve`, { ...body }), async () => {
        const r = await c.get<ClaimCommandReceipt>(`/channel-account-claims/${e(no)}/commands/${e(requestNoFor(`/channel-account-claims/${e(no)}/resolve`, { ...body }))}`);
        if (r.outcome === "REJECTED") throw new ReceiptRejected(r.failure?.message ?? "认领处理被拒绝", { status: r.failure?.code ?? 409, reasonCode: r.failure?.reasonCode });
        return r.result as ChannelAccountClaim;
      }),
      command: (no, rn) => c.get(`/channel-account-claims/${e(no)}/commands/${e(rn)}`),
      file: (no, fileNo) => c.blob(`/channel-account-claims/${e(no)}/files/${e(fileNo)}`),
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
      list: async (q) => (await refunds.list({ status: q.status, limit: q.limit ?? 200 })) ?? [],
      review: (no, body) => refunds.review(no, body),
    },
    terminals: {
      ...crud<TerminalRow, TerminalSave>("/terminals"),
      verify: (no, body) => c.command(`/terminals/${e(no)}/verify`, { ...body }),
      enable: (no, cmd) => c.command(`/terminals/${e(no)}/enable`, { ...cmd }),
      disable: (no, cmd) => c.command(`/terminals/${e(no)}/disable`, { ...cmd }),
      activationCode: (no) => c.command(`/terminals/${e(no)}/activation-codes`, undefined, { idempotent: false }),
      credentialReference: (no) => c.get(`/terminals/${e(no)}/credential-reference`),
      retirement: (no) => c.get(`/terminals/${e(no)}/retirement`),
      lost: (no) => c.command(`/terminals/${e(no)}/lost`, undefined, { idempotent: false }),
      retire: (no, cmd) => c.command(`/terminals/${e(no)}/retire`, { ...cmd }),
    },
    apps: {
      list: (q) => c.page("/apps", q),
      detail: (no) => detailOf(c, `/apps/${e(no)}`),
      grants: (no) => c.page(`/apps/${e(no)}/grants`, { page: 1, size: 100 }),
      revokeGrant: (no, store, cmd) => c.command(`/apps/${e(no)}/grants/${e(store)}/revoke`, { ...cmd }),
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
      metrics: () => c.get("/alert-metrics"),
      recheckReversal: (no) => c.command(`/alert-events/${e(no)}/recheck-reversal`, undefined, { idempotent: false }),
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
      transaction: (no) => detailOf(c, `/stored-value/transactions/${e(no)}`),
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
