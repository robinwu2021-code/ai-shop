import type {CurrencyCode} from '@shared/types';
import type {SvPage} from './stored-value-types';
export interface SvFinanceQuery {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Whether the stored-value issuer belongs to a store or a brand. */
  scopeType?:'STORE'|'BRAND';
  /** Stored-value issuer identifier whose ledger or settlement data is requested. */
  issuerNo?:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency?:string;
  /** Server-owned lifecycle state used to filter settlement bills. */
  status?:string;
  /** Inclusive start of the settlement period as an ISO instant. */
  periodFrom?:string;
  /** Exclusive end of the settlement period as an ISO instant. */
  periodTo?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string;
  /** Maximum records requested per page; the financial reporting API accepts 1–100. */
  size?:number}
export interface SvReportQuery {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode;
  /** Inclusive first business date in YYYY-MM-DD format, interpreted in the resource’s business time zone. */
  startDate:string;
  /** Exclusive end business date in YYYY-MM-DD format, interpreted in the resource’s business time zone. */
  endDate:string;
  /** Optional stored-value activity identifier restricting the report to that activity. */
  activityNo?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string;
  /** Maximum records requested per page; the financial reporting API accepts 1–100. */
  size?:number}
export interface SvFinanceResource {
  /** Stored-value issuer identifier whose ledger or settlement data is requested. */
  issuerNo:string;
  /** Whether the stored-value issuer belongs to a store or a brand. */
  scopeType:'STORE'|'BRAND';
  /** Store or brand identifier selected by scopeType. */
  scopeNo:string;
  /** Display name of the stored-value resource represented by this row. */
  name:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode;
  /** IANA business time zone used to interpret report date boundaries. */
  businessTimeZone:string;
/** Current server-verified generate permission for this issuer; absent values fail closed. */
canGenerate:boolean}
export interface SvFinanceCapability {
  /** Server capability protocol version for the stored-value finance integration. */
  protocol:number;
  /** Whether the server can provide stored-value financial reports. */
  reportsReady:boolean;
  /** Whether settlement generation and settlement commands are available. */
  settlementReady:boolean;
  /** Whether settlement between different operating entities is available. */
  crossEntityReady:boolean;
  /** Whether this deployment accepts receipt evidence attachments. */
  receiptAttachmentsSupported:boolean;
  /** Server capability blocker codes explaining unavailable financial features. */
  blockers:string[]}
export interface SvSummary {
  /** Stored-value issuer identifier whose ledger or settlement data is requested. */
  issuerNo:string;
  /** Reported stored-value resource scope, such as STORE or BRAND. */
  scopeType:string;
  /** Display name of the stored-value resource represented by this row. */
  name:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode;
  /** Report interval with an inclusive from instant and an exclusive to instant. */
  period:{from:string;to:string};
  /** ISO instant at which this report snapshot was computed. */
  asOf:string;
  /** Source-ledger high-water mark included in the report or generated bills. */
  sourceWatermark:number;
  /** Stored-value balance at the start of the report period, in minor currency units. */
  openingMinor:number;
  /** Recharge principal credited during the period, in minor currency units. */
  creditedPaidMinor:number;
  /** Recharge bonus credited during the period, in minor currency units. */
  creditedBonusMinor:number;
  /** Stored-value amount consumed during the period, in minor currency units. */
  consumedMinor:number;
  /** Previously consumed amount restored by reversals during the period, in minor currency units. */
  reversedMinor:number;
  /** Stored-value balance at period end: opening plus credits and reversals minus consumption, in minor units. */
  closingMinor:number;
  /** Stored-value amount reserved by unfinished operations, in minor currency units. */
  heldMinor:number;
  /** Number of recharge operations included in this report row. */
  rechargeCount:number;
  /** Number of stored-value consumption operations included in this report row. */
  usageCount:number;
  /** Number of customers represented in this report row. */
  customerCount:number;
  /** Net settlement amount payable for the reported scope or bill, in minor currency units. */
  payableMinor:number}
export interface SvStoreAttribution {
  /** Store identifier used to attribute this report row. */
  storeNo:string;
  /** Display name of the store represented by this attribution row. */
  storeName:string;
  /** Recharge principal credited during the period, in minor currency units. */
  creditedPaidMinor:number;
  /** Recharge bonus credited during the period, in minor currency units. */
  creditedBonusMinor:number;
  /** Stored-value amount consumed during the period, in minor currency units. */
  consumedMinor:number;
  /** Net settlement amount payable for the reported scope or bill, in minor currency units. */
  payableMinor:number;
  /** Number of recharge operations included in this report row. */
  rechargeCount:number;
  /** Number of stored-value consumption operations included in this report row. */
  usageCount:number;
  /** Number of customers represented in this report row. */
  customerCount:number;
  /** ISO instant at which this report snapshot was computed. */
  asOf:string;
  /** Source-ledger high-water mark included in the report or generated bills. */
  sourceWatermark:number}
export interface SvActivityAttribution {
  /** Optional stored-value activity identifier restricting the report to that activity. */
  activityNo:string;
  /** Immutable published activity release to which these ledger amounts are attributed. */
  releaseNo:string;
  /** Stored-value activity source classification used to interpret activityNo and releaseNo. */
  sourceKind:string;
  /** Display name of the stored-value resource represented by this row. */
  name:string;
  /** Recharge principal credited during the period, in minor currency units. */
  creditedPaidMinor:number;
  /** Recharge bonus credited during the period, in minor currency units. */
  creditedBonusMinor:number;
  /** Recharge principal consumed from this activity’s credits, in minor currency units. */
  consumedPrincipalMinor:number;
  /** Recharge bonus consumed from this activity’s credits, in minor currency units. */
  consumedBonusMinor:number;
  /** Unused stored-value balance attributed to this activity, in minor currency units. */
  remainingMinor:number;
  /** Number of recharge operations included in this report row. */
  rechargeCount:number;
  /** Number of stored-value consumption operations included in this report row. */
  usageCount:number;
  /** Number of customers represented in this report row. */
  customerCount:number;
  /** ISO instant at which this report snapshot was computed. */
  asOf:string;
  /** Source-ledger high-water mark included in the report or generated bills. */
  sourceWatermark:number}
export interface SvSettlementBill {
  /** Settlement bill identifier used for authorized detail queries and commands. */
  billNo:string;
  /** Stored-value issuer identifier whose ledger or settlement data is requested. */
  issuerNo:string;
  /** Operating entity responsible for paying this settlement bill. */
  payerEntityNo:string;
  /** Operating entity entitled to receive this settlement bill’s payment. */
  payeeEntityNo:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode;
  /** Inclusive start of the settlement period as an ISO instant. */
  periodFrom:string;
  /** Exclusive end of the settlement period as an ISO instant. */
  periodTo:string;
  /** Settlement classification, including CROSS_ENTITY payments and INTERNAL_RECON reconciliation. */
  billKind:string;
  /** Settlement bill lifecycle state, such as DRAFT, DISPUTED, RECONCILED or a payment/receipt state. */
  status:string;
  /** Net settlement amount payable for the reported scope or bill, in minor currency units. */
  payableMinor:number;
  /** Total amount of registered bill payments, in minor currency units. */
  registeredPaymentMinor:number;
  /** Total amount whose receipt the payee has confirmed, in minor currency units. */
  confirmedReceiptMinor:number;
  /** Principal component attributed to this settlement bill or clearing item, in minor currency units. */
  principalMinor:number;
  /** Bonus component attributed to this settlement bill or clearing item, in minor currency units. */
  bonusMinor:number;
  /** Number of source clearing entries included in this bill. */
  sourceCount:number;
  /** Source-ledger high-water mark included in the report or generated bills. */
  sourceWatermark:number;
  /** Actor who reconciled the payer side; null before payer reconciliation. */
  payerReconciledBy:string|null;
  /** Actor who reconciled the payee side; null before payee reconciliation. */
  payeeReconciledBy:string|null;
  /** Settlement dispute identifier; null when the bill has no dispute. */
  disputeNo:string|null;
  /** Current server revision used for optimistic concurrency on the returned resource. */
  revision:number;
  /** ISO instant when the settlement bill was created. */
  createdAt:string;
  /** ISO instant when the settlement bill was last updated. */
  updatedAt:string}
export interface SvSettlementItem {
  /** Settlement line identifier within the bill detail. */
  itemNo:string;
  /** Source stored-value clearing entry represented by or adjusted through this line. */
  clearingNo:string;
  /** Monetary amount in the settlement bill’s minor currency units. */
  amountMinor:number;
  /** Principal component attributed to this settlement bill or clearing item, in minor currency units. */
  principalMinor:number;
  /** Bonus component attributed to this settlement bill or clearing item, in minor currency units. */
  bonusMinor:number;
  /** Store where the stored-value credit was recharged. */
  rechargeStoreNo:string;
  /** Store where the credited stored value was redeemed. */
  redemptionStoreNo:string;
  /** ISO instant when the source business operation occurred. */
  occurredAt:string;
  /** ISO instant when the source clearing entry was posted to the ledger. */
  postedAt:string;
  /** Whether the source entry was carried into this bill from an earlier settlement period. */
  carriedForward:boolean;
  /** Source clearing classification, such as USE or a reversal. */
  entryType:string;
  /** Original clearing entry referenced by a reversal; null for an original entry. */
  originalClearingNo:string|null}
export interface SvSettlementEvent {
  /** Immutable settlement audit event identifier. */
  eventNo:string;
  /** Settlement audit event type used to interpret the recorded action. */
  type:string;
  /** Settlement bill revision to which this audit event applies. */
  billRevision:number;
  /** Actor identifier recorded for the settlement event. */
  actorNo:string;
  /** Business reason recorded for this settlement operation; limited to 255 characters when supplied. */
  reason:string|null;
  /** ISO instant when this settlement audit event occurred. */
  occurredAt:string}
export interface SvSettlementPayment {
  /** Registered settlement payment identifier used for receipt confirmation and voiding. */
  paymentNo:string;
  /** Settlement bill identifier used for authorized detail queries and commands. */
  billNo:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode;
  /** Declared settlement payment method: bank transfer, cash or other. */
  method:string;
  /** Registered settlement payment amount in the bill currency’s minor units. */
  amountMinor:number;
  /** ISO instant when the payer made the registered payment. */
  paidAt:string;
  /** Registered payment lifecycle state, distinguishing registered, received and voided payments. */
  status:string;
  /** Actor who registered this settlement payment. */
  registeredBy:string;
  /** Actor who confirmed receipt; null until the payee confirms. */
  receivedBy:string|null;
  /** ISO instant of receipt confirmation; null until receipt is confirmed. */
  receivedAt:string|null;
  /** Current server revision used for optimistic concurrency on the returned resource. */
  revision:number}
export interface SvBillActions {
  /** Settlement permission codes currently available to this actor on the payer side. */
  payerActions:string[];
  /** Settlement permission codes currently available to this actor on the payee side. */
  payeeActions:string[]}
export interface SvGenerationRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Stored-value issuer identifier whose ledger or settlement data is requested. */
  issuerNo:string;
  /** Inclusive first settlement business date in YYYY-MM-DD format, interpreted in the issuer’s business time zone. */
  periodFrom:string;
  /** Exclusive end settlement business date in YYYY-MM-DD format, interpreted in the issuer’s business time zone. */
  periodTo:string;
  /** ISO currency code; monetary fields use this currency’s minor units. */
  currency:CurrencyCode}
export interface SvDecisionRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** Bill party performing reconciliation or raising a dispute: PAYER or PAYEE. */
  side:'PAYER'|'PAYEE';
  /** Business reason for the decision; required for a dispute and optional for reconciliation, at most 255 characters. */
  reason?:string}
export interface SvResolutionRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** Settlement dispute identifier; null when the bill has no dispute. */
  disputeNo:string;
  /** Dispute resolution decision: ACCEPT or REJECT. */
  decision:'ACCEPT'|'REJECT';
  /** Business reason recorded for this settlement operation; limited to 255 characters when supplied. */
  reason:string}
export interface SvAdjustmentRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Source stored-value clearing entry represented by or adjusted through this line. */
  clearingNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** Nonzero signed change to the clearing entry’s payable amount, in minor currency units. */
  payableDeltaMinor:number;
  /** Business reason recorded for this settlement operation; limited to 255 characters when supplied. */
  reason:string}
export interface SvPaymentRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** Positive payment amount to register, in the bill currency’s minor units. */
  amountMinor:number;
  /** ISO instant when the payer made the registered payment. */
  paidAt:string;
  /** Declared settlement payment method: bank transfer, cash or other. */
  method:'TRANSFER'|'CASH'|'OTHER';
  /** Payer’s receipt or transfer reference; required and limited to 160 characters. */
  receiptReference:string;
  /** Receipt attachment references; this client currently sends an empty list. */
  evidenceRefs:[]}
export interface SvReceiptRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** ISO instant at which the payee confirms receipt of the payment. */
  receivedAt:string}
export interface SvVoidRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Previously read revision of the target bill or payment; stale commands are rejected. */
  expectedRevision:number;
  /** Business reason recorded for this settlement operation; limited to 255 characters when supplied. */
  reason:string}
export interface SvGenerated {
  /** Settlement bills created or returned idempotently for the generation request. */
  bills:SvSettlementBill[];
  /** Source-ledger high-water mark included in the report or generated bills. */
  sourceWatermark:number}
export interface SvPaymentResolution {
  /** Whether the server found the original payment command for the supplied idempotency key. */
  found:boolean;
  /** Original registered payment when found; null when the command has no recorded result. */
  payment:SvSettlementPayment|null}
export interface SvFinancialBrandChange {
  /** Financial brand-binding change identifier used to query or resume the change. */
  changeNo:string;
  /** Server-owned lifecycle state of the financial brand-binding change. */
  state:string;
  /** Whether the financial brand-binding change is staged pending completion. */
  staged:boolean}
export interface SvFinancialBrandRequest {
  /** Management space authorizing this financial query or command; the server verifies access. */
  managementNo:string;
  /** Destination brand identifier; null requests removal of the store’s brand binding. */
  toBrandNo:string|null;
  /** Previously read store revision used to reject a stale financial brand-binding change. */
  expectedStoreRevision:number;
  /** Business reason recorded for this settlement operation; limited to 255 characters when supplied. */
  reason:string}
export interface SvFinancialPermissionResult {
  /** Whether the server successfully prepared the requested financial permission setup. */
  prepared:boolean;
  /** Operating entity whose financial permissions were prepared. */
  entityNo:string;
  /** Financial permission codes returned by the server for the prepared entity. */
  permissions:string[]}
export interface SvFinancialBrandOriginal {
  /** Original financial brand-binding command result; null when the command is not recorded. */
  original:SvFinancialBrandChange|null}
export interface StoredValueFinanceApi {
 mSvFinancialBrandOriginal(store:string,managementNo:string,commandKey:string):Promise<SvFinancialBrandOriginal>;
 mSvPrepareFinancePermissions(entity:string,body:{managementNo:string}):Promise<SvFinancialPermissionResult>;
 mSvFinancialBrandStart(store:string,body:SvFinancialBrandRequest,key:string):Promise<SvFinancialBrandChange>;
 mSvFinancialBrandChange(no:string,managementNo:string):Promise<SvFinancialBrandChange>;
 mSvFinancialBrandResume(no:string,body:{managementNo:string}):Promise<SvFinancialBrandChange>;
 mSvFinanceCapability():Promise<SvFinanceCapability>;
 mSvFinanceResources(managementNo:string,scopeType?:'STORE'|'BRAND'):Promise<SvFinanceResource[]>;
 mSvBrandSummary(no:string,q:SvReportQuery):Promise<SvSummary>;
 mSvStoreSummary(no:string,q:SvReportQuery):Promise<SvSummary>;
 mSvBrandStoreBreakdown(no:string,q:SvReportQuery):Promise<SvPage<SvStoreAttribution>>;
 mSvLocalIssuerSummary(q:SvReportQuery):Promise<SvPage<SvSummary>>;
 mSvActivitySummary(no:string,q:SvReportQuery & {issuerNo:string}):Promise<SvPage<SvActivityAttribution>>;
 mSvSettlementBills(q:SvFinanceQuery):Promise<SvPage<SvSettlementBill>>;
 mSvSettlementBill(no:string,managementNo:string):Promise<SvSettlementBill>;
 mSvSettlementActions(no:string,managementNo:string):Promise<SvBillActions>;
 mSvSettlementItems(no:string,q:{managementNo:string;cursor?:string;size?:number}):Promise<SvPage<SvSettlementItem>>;
 mSvSettlementEvents(no:string,q:{managementNo:string;cursor?:string;size?:number}):Promise<SvPage<SvSettlementEvent>>;
 mSvSettlementPayments(no:string,q:{managementNo:string;cursor?:string;size?:number}):Promise<SvPage<SvSettlementPayment>>;
 mSvSettlementGenerate(body:SvGenerationRequest,key:string):Promise<SvGenerated>;
 mSvSettlementReconcile(no:string,body:SvDecisionRequest,key:string):Promise<SvSettlementBill>;
 mSvSettlementDispute(no:string,body:SvDecisionRequest,key:string):Promise<SvSettlementBill>;
 mSvSettlementResolveDispute(no:string,body:SvResolutionRequest,key:string):Promise<SvSettlementBill>;
 mSvSettlementAdjust(no:string,body:SvAdjustmentRequest,key:string):Promise<SvSettlementBill>;
 mSvSettlementRegister(no:string,body:SvPaymentRequest,key:string):Promise<SvSettlementPayment>;
 mSvSettlementPaymentResolution(no:string,managementNo:string,commandKey:string):Promise<SvPaymentResolution>;
 mSvSettlementReceipt(no:string,body:SvReceiptRequest,key:string):Promise<SvSettlementPayment>;
 mSvSettlementVoid(no:string,body:SvVoidRequest,key:string):Promise<SvSettlementPayment>;
}
