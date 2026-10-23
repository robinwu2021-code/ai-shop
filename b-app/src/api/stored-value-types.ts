import type { CurrencyCode } from '@shared/types';
export interface SvPage<T> {
  /** 当前页业务记录列表 */
  records:T[];
  /** 下一页游标，无后续记录时为空 */
  nextCursor:string|null;
  /** 是否还有后续分页 */
  hasMore:boolean }
export interface SvContext {
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 货币最小单位的小数位数 */
  currencyScale:number;
  /** 业务日期所采用的时区 */
  timeZone:string }
export interface SvPlan {
  /** 充值方案编号 */
  planNo:string;
  /** 不可变充值方案版本编号 */
  versionNo:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 门店编号 */
  storeNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 实付充值金额，货币最小单位 */
  paidAmountMinor:number;
  /** 赠送金额，货币最小单位 */
  bonusAmountMinor:number;
  /** 实付加赠送的总金额，货币最小单位 */
  totalAmountMinor:number;
  /** 服务端业务状态编码 */
  status:'ACTIVE'|'INACTIVE';
  /** 创建时间 */
  createdAt:string;
  /** 最近更新时间 */
  updatedAt:string|null }
export interface SvPlanCreate {
  /** 门店编号 */
  storeNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 实付充值金额，货币最小单位 */
  paidAmountMinor:number;
  /** 赠送金额，货币最小单位 */
  bonusAmountMinor:number }
export interface SvAccount {
  /** 服务端签发的账户引用，非用户编号 */
  accountRef:string;
  /** 服务端脱敏的顾客名称 */
  maskedName:string;
  /** 服务端脱敏的手机号 */
  maskedPhone:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 账面余额，货币最小单位 */
  balanceMinor:number;
  /** 冻结金额，货币最小单位 */
  heldMinor:number;
  /** 可用余额，货币最小单位 */
  availableMinor:number;
  /** 最近业务发生时间 */
  lastOccurredAt:string|null;
  /** 最近一次充值时间 */
  lastRechargeAt?:string|null }
export interface SvRecord {
  /** 面向用户的展示单号，不能代替内部业务编号 */
  documentNo?:string|null;
  /** 原凭证展示单号 */
  voucherDocumentNo?:string|null;
  /** 储值流水内部编号 */
  recordNo:string;
  /** 服务端脱敏的顾客名称 */
  maskedName:string;
  /** 服务端脱敏的手机号 */
  maskedPhone:string;
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'RECHARGE'|'REDEMPTION';
  /** 面向用户的业务标题 */
  title:string;
  /** 门店编号 */
  storeNo:string;
  /** 门店显示名称 */
  storeName:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 带收支方向符号的金额，货币最小单位 */
  signedAmountMinor:number;
  /** 此笔流水入账后余额，货币最小单位 */
  balanceAfterMinor:number;
  /** 业务实际发生时间 */
  occurredAt:string;
  /** 来源业务编号 */
  businessNo:string;
  /** 充值方案编号 */
  planNo:string|null;
  /** 不可变充值方案版本编号 */
  versionNo:string|null;
  /** 实付充值金额，货币最小单位 */
  paidAmountMinor:number|null;
  /** 赠送金额，货币最小单位 */
  bonusAmountMinor:number|null }
export interface SvRecordsQuery {
  /** 门店编号 */
  storeNo:string;
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'ALL'|'RECHARGE'|'REDEMPTION';
  /** 筛选开始日期 */
  startDate?:string;
  /** 筛选截止日期 */
  endDate?:string;
  /** 服务端签发的账户引用，非用户编号 */
  accountRef?:string;
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?:string;
  /** 请求的每页记录数量 */
  size?:number }
export interface SvProgress {
  /** 充值业务编号 */
  rechargeNo:string;
  /** 面向用户的展示单号，不能代替内部业务编号 */
  documentNo?:string|null;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 服务端业务状态编码 */
  status:string;
  /** 门店编号 */
  storeNo:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 实付充值金额，货币最小单位 */
  paidAmountMinor:number|null;
  /** 确认入账总金额，货币最小单位 */
  creditedTotalMinor:number|null;
  /** 充值入账时间 */
  creditedAt:string|null;
  /** 线下收款确认时间 */
  receivedAt:string|null }
export interface SvRechargeCreate {
  /** 门店编号 */
  storeNo:string;
  /** 当前数据域签发的顾客选择引用 */
  customerRef:string;
  /** 充值方案编号 */
  planNo:string;
  /** 不可变充值方案版本编号 */
  versionNo:string }
export interface SvReceipt {
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number;
  /** 实际收款金额，货币最小单位 */
  actualAmountMinor:number;
  /** 实际收款方式编码 */
  collectionMethod:'CASH'|'TRANSFER';
  /** 原收款凭据编号 */
  receiptNo:string }
export interface SvReceive {
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number;
  /** 原收款凭据编号 */
  receiptNo:string;
  /** 线下收款核实备注 */
  confirmationNote:string }
export interface SvCustomer {
  /** 当前数据域签发的顾客选择引用 */
  customerRef:string;
  /** 服务端脱敏的顾客名称 */
  maskedName:string;
  /** 服务端引用失效时间 */
  expiresAt:string }

export type SvSelectionSource={
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'MEMBER';
  /** 当前门店会员编号 */
  memberNo:string;memberScope?:'OPERATING'}|{
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'ACCOUNT';
  /** 服务端签发的账户引用，非用户编号 */
  accountRef:string};
export interface SvCandidate {
  /** 当前门店会员编号 */
  memberNo:string;
  /** 脱敏显示名称 */
  displayName:string;
  /** 服务端脱敏手机号 */
  phoneMasked:string;
  /** 会员当前状态编码 */
  memberStatus:string;
  /** 当前条件是否允许充值 */
  canRecharge:boolean;
  /** 当前业务不可用的原因编码 */
  unavailableReason:string|null;
  /** 当前范围的储值账户摘要 */
  account:{
  /** 服务端签发的账户引用，非用户编号 */
  accountRef:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:CurrencyCode;
  /** 可用余额，货币最小单位 */
  availableMinor:number}|null}
export interface SvSelectedCustomer {
  /** 当前数据域签发的顾客选择引用 */
  customerRef:string;
  /** 服务端引用失效时间 */
  expiresAt:string;
  /** 顾客选择引用是否仍待范围确认 */
  pendingScope?:string;
  /** 来源门店编号 */
  sourceStoreNo?:string|null;
  /** 来源门店显示名称 */
  sourceStoreName?:string|null;
  /** 当前门店会员编号 */
  memberNo?:string|null;
  /** 脱敏显示名称 */
  displayName:string;
  /** 服务端脱敏手机号 */
  phoneMasked:string;
  /** 当前范围的储值账户摘要 */
  account:SvCandidate['account']}

export interface ScopeMemberCandidate extends Omit<SvCandidate,'memberNo'> {
  /** 当前门店会员编号 */
  memberNo:string|null;
  /** 来源门店编号 */
  sourceStoreNo:string|null;
  /** 来源门店显示名称 */
  sourceStoreName:string|null;
  /** 最近一次充值时间 */
  lastRechargeAt?:string|null}
export interface ScopeCandidatePage {
  /** 当前页业务记录 */
  items:ScopeMemberCandidate[];
  /** 下一页游标，无后续记录时为空 */
  nextCursor:string|null;
  /** 候选身份冲突数量 */
  identityConflictCount:number}
export type ScopeSelectionSource={
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'MEMBER';
  /** 当前门店会员编号 */
  memberNo:string}|{
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'ACCOUNT';
  /** 服务端签发的账户引用，非用户编号 */
  accountRef:string}|{
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:'MEMBER_CODE';code:string};
export interface ScopeSelectedCustomer {
  /** 服务端顾客选择引用及有效期 */
  selection:SvSelectedCustomer;
  /** 来源门店编号 */
  sourceStoreNo:string|null;
  /** 来源门店显示名称 */
  sourceStoreName:string|null}
export interface ScopeMemberSummary {
  /** 当前管理范围的会员编号 */
  scopeMemberNo:string;
  /** 脱敏显示名称 */
  displayName:string;
  /** 服务端脱敏手机号 */
  phoneMasked:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 来源门店编号 */
  sourceStoreNo:string;
  /** 来源门店显示名称 */
  sourceStoreName:string;
  /** 操作备注 */
  remark:string|null}
