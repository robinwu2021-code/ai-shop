import type { CurrencyCode } from '@shared/types';
export interface SvPage<T> {
  /** Business records returned by the current query. */
  records:T[];
  /** Opaque cursor for the next page; null when the result is exhausted. */
  nextCursor:string|null;
  /** Whether a further page is available using nextCursor. */
  hasMore:boolean }
export interface SvContext {
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Number of decimal places in the currency's major unit. */
  currencyScale:number;
  /** IANA time zone used for business-date filtering and activity scheduling. */
  timeZone:string }
export interface SvPlan {
  /** Recharge plan identifier; null for operations without a recharge plan. */
  planNo:string;
  /** Immutable version identifier frozen for the referenced plan or policy. */
  versionNo:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Recharge principal paid by the customer, in minor currency units. */
  paidAmountMinor:number;
  /** Gifted stored-value amount, in minor currency units. */
  bonusAmountMinor:number;
  /** Total recharge credit, equal to principal plus bonus, in minor currency units. */
  totalAmountMinor:number;
  /** Current server-owned lifecycle state of this resource. */
  status:'ACTIVE'|'INACTIVE';
  /** Resource creation time returned by the server. */
  createdAt:string;
  /** Time the resource was last updated; null when it has never changed. */
  updatedAt:string|null }
export interface SvPlanCreate {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Recharge principal paid by the customer, in minor currency units. */
  paidAmountMinor:number;
  /** Gifted stored-value amount, in minor currency units. */
  bonusAmountMinor:number }
export interface SvAccount {
  /** Opaque stored-value account reference used for authorized queries. */
  accountRef:string;
  /** Customer name masked for the current operator's access level. */
  maskedName:string;
  /** Customer phone number masked for the current operator's access level. */
  maskedPhone:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Account balance including held funds, in minor currency units. */
  balanceMinor:number;
  /** Stored-value amount reserved by unfinished operations, in minor currency units. */
  heldMinor:number;
  /** Stored-value amount currently spendable, excluding held funds, in minor units. */
  availableMinor:number;
  /** Time of the most recent account record; null when there are no records. */
  lastOccurredAt:string|null;
  /** Time of the most recent completed recharge; null when none exists. */
  lastRechargeAt?:string|null }
export interface SvRecord {
  /** Human-facing document number; null or absent for historical unmapped records. */
  documentNo?:string|null;
  /** Human-facing accounting voucher number; absent for historical unmapped vouchers. */
  voucherDocumentNo?:string|null;
  /** Internal stored-value record reference; use documentNo for human display. */
  recordNo:string;
  /** Customer name masked for the current operator's access level. */
  maskedName:string;
  /** Customer phone number masked for the current operator's access level. */
  maskedPhone:string;
  /** Record, target or calculation classification used to interpret this object. */
  type:'RECHARGE'|'REDEMPTION';
  /** Human-readable title of the stored-value business record. */
  title:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Store display name captured for the record or selection. */
  storeName:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Signed change in stored-value balance, in minor currency units. */
  signedAmountMinor:number;
  /** Account balance after this record, in minor currency units. */
  balanceAfterMinor:number;
  /** Time the server recorded the business operation. */
  occurredAt:string;
  /** Business operation identifier associated with this stored-value record. */
  businessNo:string;
  /** Recharge plan identifier; null for operations without a recharge plan. */
  planNo:string|null;
  /** Immutable version identifier frozen for the referenced plan or policy. */
  versionNo:string|null;
  /** Recharge principal paid by the customer, in minor currency units. */
  paidAmountMinor:number|null;
  /** Gifted stored-value amount, in minor currency units. */
  bonusAmountMinor:number|null }
export interface SvRecordsQuery {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Record, target or calculation classification used to interpret this object. */
  type:'ALL'|'RECHARGE'|'REDEMPTION';
  /** Inclusive first business date in YYYY-MM-DD format. */
  startDate?:string;
  /** Inclusive final business date in YYYY-MM-DD format. */
  endDate?:string;
  /** Opaque stored-value account reference used for authorized queries. */
  accountRef?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string;
  /** Maximum records per page requested or returned by the server. */
  size?:number }
export interface SvProgress {
  /** Recharge operation identifier used for status queries and recovery. */
  rechargeNo:string;
  /** Human-facing document number; null or absent for historical unmapped records. */
  documentNo?:string|null;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Recharge principal paid by the customer, in minor currency units. */
  paidAmountMinor:number|null;
  /** Total principal and bonus actually credited, in minor currency units. */
  creditedTotalMinor:number|null;
  /** Time the recharge was credited; null before posting. */
  creditedAt:string|null;
  /** Time offline collection was confirmed; null before confirmation. */
  receivedAt:string|null }
export interface SvRechargeCreate {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Server-issued selected-customer reference; expires and must be reselected when stale. */
  customerRef:string;
  /** Recharge plan identifier; null for operations without a recharge plan. */
  planNo:string;
  /** Immutable version identifier frozen for the referenced plan or policy. */
  versionNo:string }
export interface SvReceipt {
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number;
  /** Amount actually collected, in the currency's smallest unit. */
  actualAmountMinor:number;
  /** Offline collection method: cash or bank transfer. */
  collectionMethod:'CASH'|'TRANSFER';
  /** Operator-provided collection evidence reference used to confirm the same receipt. */
  receiptNo:string }
export interface SvReceive {
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number;
  /** Operator-provided collection evidence reference used to confirm the same receipt. */
  receiptNo:string;
  /** Operator's evidence note for the offline collection confirmation. */
  confirmationNote:string }
export interface SvCustomer {
  /** Server-issued selected-customer reference; expires and must be reselected when stale. */
  customerRef:string;
  /** Customer name masked for the current operator's access level. */
  maskedName:string;
  /** Expiry time of the server-issued selection or preview. */
  expiresAt:string }

export type SvSelectionSource={
  /** Record, target or calculation classification used to interpret this object. */
  type:'MEMBER';
  /** Member identifier; null when the candidate only has an existing account. */
  memberNo:string;
  /** Membership namespace used to resolve the selected member. */
  memberScope?:'OPERATING'}|{
  /** Record, target or calculation classification used to interpret this object. */
  type:'ACCOUNT';
  /** Opaque stored-value account reference used for authorized queries. */
  accountRef:string};
export interface SvCandidate {
  /** Member identifier; null when the candidate only has an existing account. */
  memberNo:string;
  /** Display name of the selected customer or grant recipient. */
  displayName:string;
  /** Masked customer phone number for display and identity confirmation. */
  phoneMasked:string;
  /** Current member lifecycle state used when checking recharge eligibility. */
  memberStatus:string;
  /** Whether the account and selected issuer currently permit recharging. */
  canRecharge:boolean;
  /** Server-provided explanation of why this account or issuer cannot be used. */
  unavailableReason:string|null;
  /** Selected stored-value account summary; null when no account exists. */
  account:{
  /** Opaque stored-value account reference used for authorized queries. */
  accountRef:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Stored-value amount currently spendable, excluding held funds, in minor units. */
  availableMinor:number}|null}
export interface SvSelectedCustomer {
  /** Server-issued selected-customer reference; expires and must be reselected when stale. */
  customerRef:string;
  /** Expiry time of the server-issued selection or preview. */
  expiresAt:string;
  /** Membership scope that still requires confirmation before recharge. */
  pendingScope?:string;
  /** Store identifier from which the customer relationship originated. */
  sourceStoreNo?:string|null;
  /** Display name of the store from which the customer relationship originated. */
  sourceStoreName?:string|null;
  /** Member identifier; null when the candidate only has an existing account. */
  memberNo?:string|null;
  /** Display name of the selected customer or grant recipient. */
  displayName:string;
  /** Masked customer phone number for display and identity confirmation. */
  phoneMasked:string;
  /** Selected stored-value account summary; null when no account exists. */
  account:SvCandidate['account']}

export interface ScopeMemberCandidate extends Omit<SvCandidate,'memberNo'> {
  /** Member identifier; null when the candidate only has an existing account. */
  memberNo:string|null;
  /** Store identifier from which the customer relationship originated. */
  sourceStoreNo:string|null;
  /** Display name of the store from which the customer relationship originated. */
  sourceStoreName:string|null;
  /** Time of the most recent completed recharge; null when none exists. */
  lastRechargeAt?:string|null}
export interface ScopeCandidatePage {
  /** Records returned for the current continuation-cursor page. */
  items:ScopeMemberCandidate[];
  /** Opaque cursor for the next page; null when the result is exhausted. */
  nextCursor:string|null;
  /** Number of candidate identities that require manual conflict resolution. */
  identityConflictCount:number}
export type ScopeSelectionSource={
  /** Record, target or calculation classification used to interpret this object. */
  type:'MEMBER';
  /** Member identifier; null when the candidate only has an existing account. */
  memberNo:string}|{
  /** Record, target or calculation classification used to interpret this object. */
  type:'ACCOUNT';
  /** Opaque stored-value account reference used for authorized queries. */
  accountRef:string}|{
  /** Record, target or calculation classification used to interpret this object. */
  type:'MEMBER_CODE';
  /** Business code supplied for the selected member or resource. */
  code:string};
export interface ScopeSelectedCustomer {
  /** Server-issued selected-customer data and its expiry. */
  selection:SvSelectedCustomer;
  /** Store identifier from which the customer relationship originated. */
  sourceStoreNo:string|null;
  /** Display name of the store from which the customer relationship originated. */
  sourceStoreName:string|null}
export interface ScopeMemberSummary {
  /** Membership identifier within the authorized operating scope. */
  scopeMemberNo:string;
  /** Display name of the selected customer or grant recipient. */
  displayName:string;
  /** Masked customer phone number for display and identity confirmation. */
  phoneMasked:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Store identifier from which the customer relationship originated. */
  sourceStoreNo:string;
  /** Display name of the store from which the customer relationship originated. */
  sourceStoreName:string;
  /** Operator-supplied audit note explaining the submitted operation. */
  remark:string|null}
