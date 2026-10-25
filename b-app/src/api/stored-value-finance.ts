import type {CurrencyCode} from '@shared/types';
import type {SvPage} from './stored-value-types';
export interface SvFinanceQuery {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Restricts bills to issuers of this scope type; omit for both store and brand issuers. */
scopeType?:'STORE'|'BRAND';
/** Restricts bills to this stored-value issuer; it must be in the caller's financial directory. */
issuerNo?:string;
/** ISO currency code filter; omit for all currencies. */
currency?:string;
/** Bill status filter: DRAFT, RECONCILED, DISPUTED, PART_PAID, WAIT_RECEIPT, SETTLED or INTERNAL_RECONCILED. */
status?:string;
/** Inclusive ISO-8601 instant; returns bills whose period overlaps [periodFrom, periodTo). Must be sent together with periodTo. */
periodFrom?:string;
/** Exclusive ISO-8601 instant ending the period-overlap filter. Must be sent together with periodFrom. */
periodTo?:string;
/** Opaque continuation cursor from the preceding page; omit for the first page. */
cursor?:string;
/** Maximum records per page; server default 20, at most 100. */
size?:number}
export interface SvReportQuery {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** ISO currency code; must equal the issuer's configured currency or the request is rejected. */
currency:CurrencyCode;
/** Inclusive first business date (yyyy-MM-dd), starting at midnight in the issuer's business time zone. */
startDate:string;
/** Exclusive end business date (yyyy-MM-dd): the window ends at midnight of this day in the issuer's business time zone; at most 367 days after startDate. */
endDate:string;
/** Optional activity filter restricting totals to recharges of this activity; ignored by the store-issuer summary. */
activityNo?:string;
/** Opaque continuation cursor from the preceding page; omit for the first page. Paged reports only. */
cursor?:string;
/** Maximum records per page; server default 20, at most 100. Paged reports only. */
size?:number}
export interface SvFinanceResource {
/** Stored-value issuer identifier. */
issuerNo:string;
/** Whether the issuer belongs to a single store (STORE) or a brand (BRAND). */
scopeType:'STORE'|'BRAND';
/** Store or brand identifier owning the issuer, according to scopeType. */
scopeNo:string;
/** Display name of the issuer resource. */
name:string;
/** ISO currency code of the issuer's current version. */
currency:CurrencyCode;
/** IANA time zone (issuer market zone) used to turn report and settlement dates into instants. */
businessTimeZone:string}
export interface SvFinanceCapability {
/** Finance contract protocol version reported by the payment service (currently 2). */
protocol:number;
/** Whether stored-value financial reports can be served. */
reportsReady:boolean;
/** Whether settlement bills can be read and processed. */
settlementReady:boolean;
/** Whether new cross-entity settlement (bill generation and payment registration) is ready. */
crossEntityReady:boolean;
/** Whether settlement payments may carry evidence attachments; currently always false. */
receiptAttachmentsSupported:boolean;
/** Machine-readable reasons cross-entity settlement is not ready; empty when ready. */
blockers:string[]}
export interface SvSummary {
/** Stored-value issuer whose ledger is summarized. */
issuerNo:string;
/** Issuer scope type: STORE or BRAND. */
scopeType:string;
/** Display name of the issuer. */
name:string;
/** ISO currency code of all monetary amounts; amounts are in currency minor units. */
currency:CurrencyCode;
/** Report window as instants, derived from startDate/endDate in the issuer's business time zone. */
period:{
/** Inclusive window start instant (ISO-8601). */
from:string;
/** Exclusive window end instant (ISO-8601). */
to:string};
/** Server time at which the figures were computed. */
asOf:string;
/** Highest ledger-entry id included in the figures; identifies the data snapshot. */
sourceWatermark:number;
/** Principal plus bonus balance before the window start, in minor units. */
openingMinor:number;
/** Paid principal credited by recharges within the window, in minor units. */
creditedPaidMinor:number;
/** Bonus credited by recharges within the window, in minor units. */
creditedBonusMinor:number;
/** Principal plus bonus consumed within the window, in minor units. */
consumedMinor:number;
/** Signed net change from entries that are neither recharge nor consumption (e.g. reversals) within the window, in minor units. */
reversedMinor:number;
/** Balance at the window end, in minor units; equals opening + creditedPaid + creditedBonus + reversed − consumed. */
closingMinor:number;
/** Principal plus bonus currently held (frozen) on credited lots, in minor units; current state, not bounded by the window. */
heldMinor:number;
/** Distinct recharge vouchers within the window. */
rechargeCount:number;
/** Distinct consumption vouchers within the window. */
usageCount:number;
/** Distinct customers with any ledger activity within the window. */
customerCount:number;
/** Inter-entity clearing payable of clearing entries occurring within the window, in minor units. */
payableMinor:number}
export interface SvStoreAttribution {
/** Store where the recharge or consumption occurred; rows are ordered by it. */
storeNo:string;
/** Display name of the store. */
storeName:string;
/** Paid principal credited at this store within the window, in minor units. */
creditedPaidMinor:number;
/** Bonus credited at this store within the window, in minor units. */
creditedBonusMinor:number;
/** Principal plus bonus consumed at this store within the window, in minor units. */
consumedMinor:number;
/** Clearing payable of entries redeemed at this store within the window, in minor units. */
payableMinor:number;
/** Distinct recharge vouchers at this store within the window. */
rechargeCount:number;
/** Distinct consumption vouchers at this store within the window. */
usageCount:number;
/** Distinct customers with ledger activity at this store within the window. */
customerCount:number;
/** Server time at which the row was computed. */
asOf:string;
/** Highest ledger-entry id included in this row. */
sourceWatermark:number}
export interface SvActivityAttribution {
/** Activity whose recharges produced the lots; null for plan-sourced recharges. */
activityNo:string;
/** Issuer release the lots belong to; rows are grouped and ordered by it. */
releaseNo:string;
/** Recharge source: ACTIVITY or PLAN. */
sourceKind:string;
/** Display name of the release. */
name:string;
/** Paid principal credited within the window, in minor units. */
creditedPaidMinor:number;
/** Bonus credited within the window, in minor units. */
creditedBonusMinor:number;
/** Principal consumed within the window, in minor units. */
consumedPrincipalMinor:number;
/** Bonus consumed within the window, in minor units. */
consumedBonusMinor:number;
/** Current remaining principal plus bonus on this release's lots, in minor units; not bounded by the window. */
remainingMinor:number;
/** Distinct recharge vouchers within the window. */
rechargeCount:number;
/** Distinct consumption vouchers within the window. */
usageCount:number;
/** Distinct customers with ledger activity within the window. */
customerCount:number;
/** Server time at which the row was computed. */
asOf:string;
/** Highest ledger-entry id included in this row. */
sourceWatermark:number}
export interface SvSettlementBill {
/** Settlement bill identifier. */
billNo:string;
/** Stored-value issuer whose clearing entries the bill settles. */
issuerNo:string;
/** Operating entity that owes payableMinor. */
payerEntityNo:string;
/** Operating entity that receives payableMinor; equals payerEntityNo for internal reconciliation bills. */
payeeEntityNo:string;
/** ISO currency code of all monetary amounts on the bill. */
currency:CurrencyCode;
/** Inclusive posting-period start instant (ISO-8601). */
periodFrom:string;
/** Exclusive posting-period end instant; unclaimed clearing entries posted before it are included. */
periodTo:string;
/** CROSS_ENTITY when payer and payee differ; INTERNAL_RECON when they are the same entity (reconciliation only, no payment). */
billKind:string;
/** Bill status: DRAFT, RECONCILED, DISPUTED, PART_PAID, WAIT_RECEIPT, SETTLED or INTERNAL_RECONCILED. */
status:string;
/** Amount the payer owes the payee, in minor units; never negative (payer and payee are swapped at generation instead). */
payableMinor:number;
/** Total of non-voided offline payments registered by the payer, in minor units. */
registeredPaymentMinor:number;
/** Total of payments whose receipt the payee has confirmed, in minor units. */
confirmedReceiptMinor:number;
/** Principal used across the source clearing entries, in minor units. */
principalMinor:number;
/** Bonus used across the source clearing entries, in minor units. */
bonusMinor:number;
/** Number of clearing entries claimed by the bill, including adjustments. */
sourceCount:number;
/** Highest clearing-entry id considered when the bill was generated. */
sourceWatermark:number;
/** Actor who confirmed reconciliation for the payer; null until confirmed or after an adjustment resets it. */
payerReconciledBy:string|null;
/** Actor who confirmed reconciliation for the payee; null until confirmed or after an adjustment resets it. */
payeeReconciledBy:string|null;
/** Open dispute identifier; null when no dispute is open. */
disputeNo:string|null;
/** Current bill revision; send it as expectedRevision on bill commands. */
revision:number;
/** Time the bill was generated. */
createdAt:string;
/** Time the bill last changed. */
updatedAt:string}
export interface SvSettlementItem {
/** Bill item identifier. */
itemNo:string;
/** Clearing entry claimed by this item. */
clearingNo:string;
/** Payable contributed to the bill in the bill's payer→payee direction, in minor units; negated when the bill direction was reversed. */
amountMinor:number;
/** Principal used by the clearing entry, in minor units; 0 for adjustments. */
principalMinor:number;
/** Bonus used by the clearing entry, in minor units; 0 for adjustments. */
bonusMinor:number;
/** Store where the consumed value was recharged. */
rechargeStoreNo:string;
/** Store where the value was consumed. */
redemptionStoreNo:string;
/** Business time of the underlying clearing event. */
occurredAt:string;
/** Time the clearing entry was posted. */
postedAt:string;
/** Whether the entry was posted before the bill's period start and carried forward from an earlier period. */
carriedForward:boolean;
/** Clearing entry type: USE, REVERSAL or ADJUSTMENT. */
entryType:string;
/** Original clearing entry corrected by an ADJUSTMENT entry; null for other entry types. */
originalClearingNo:string|null}
export interface SvSettlementEvent {
/** Settlement event identifier. */
eventNo:string;
/** Event type: GENERATED, RECON_CONFIRM, DISPUTE, DISPUTE_RESOLVE, ADJUST, PAYMENT, RECEIPT or PAYMENT_VOID. */
type:string;
/** Bill revision after the event was applied. */
billRevision:number;
/** Actor who performed the action. */
actorNo:string;
/** Reason supplied with the command; null when none was given. */
reason:string|null;
/** Time the event was recorded. */
occurredAt:string}
export interface SvSettlementPayment {
/** Settlement payment identifier. */
paymentNo:string;
/** Settlement bill the payment pays. */
billNo:string;
/** ISO currency code of amountMinor. */
currency:CurrencyCode;
/** Offline payment method: TRANSFER, CASH or OTHER. */
method:string;
/** Registered payment amount, in minor units. */
amountMinor:number;
/** Time the payer states the offline payment was made. */
paidAt:string;
/** Payment status: REGISTERED, RECEIPT_CONFIRMED or VOIDED. */
status:string;
/** Payer-side actor who registered the payment. */
registeredBy:string;
/** Payee-side actor who confirmed receipt; null until confirmed. */
receivedBy:string|null;
/** Receipt time stated by the payee; null until confirmed. */
receivedAt:string|null;
/** Current payment revision; send it as expectedRevision for receipt confirmation or void. */
revision:number}
export interface SvBillActions {
/** Settlement permission codes the caller holds on the bill's payer entity. */
payerActions:string[];
/** Settlement permission codes the caller holds on the bill's payee entity. */
payeeActions:string[]}
export interface SvGenerationRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Issuer to generate bills for; it must be in the caller's financial directory. */
issuerNo:string;
/** Inclusive first posting date (yyyy-MM-dd) in the issuer's business time zone. */
periodFrom:string;
/** Exclusive end posting date (yyyy-MM-dd) in the issuer's business time zone; the period must already have ended. */
periodTo:string;
/** ISO currency code; must equal the issuer's current currency. */
currency:CurrencyCode}
export interface SvDecisionRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Bill revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** Party side being confirmed: PAYER or PAYEE; required for reconciliation, not used by disputes. */
side:'PAYER'|'PAYEE';
/** Business reason, at most 255 characters; optional for reconciliation, required for disputes. */
reason?:string}
export interface SvResolutionRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Bill revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** Dispute currently open on the bill. */
disputeNo:string;
/** Recorded resolution decision: ACCEPT or REJECT; either way the bill returns to its pre-dispute state. */
decision:'ACCEPT'|'REJECT';
/** Business reason for the resolution, at most 255 characters. */
reason:string}
export interface SvAdjustmentRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Cross-entity USE clearing entry on this bill whose payable is corrected. */
clearingNo:string;
/** Bill revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** Signed, non-zero change to the entry's payable in minor units; the effective payable must stay between 0 and the principal used. */
payableDeltaMinor:number;
/** Business reason for the adjustment, at most 255 characters. */
reason:string}
export interface SvPaymentRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Bill revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** Positive payment amount in minor units; at most payableMinor − registeredPaymentMinor. */
amountMinor:number;
/** ISO-8601 instant the offline payment was made; must not be in the future. */
paidAt:string;
/** Offline payment method. */
method:'TRANSFER'|'CASH'|'OTHER';
/** Offline payment reference (e.g. transfer serial), at most 160 characters; normalized, stored encrypted and unique per payer while active. */
receiptReference:string;
/** Reserved evidence attachment references; must be empty because attachments are not supported. */
evidenceRefs:[]}
export interface SvReceiptRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Payment revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** ISO-8601 instant the payee received the funds; not before paidAt and not in the future. */
receivedAt:string}
export interface SvVoidRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Payment revision the client last read; stale revisions are rejected. */
expectedRevision:number;
/** Business reason for voiding the registration, at most 255 characters. */
reason:string}
export interface SvGenerated {
/** Bills for the issuer and period visible to the caller, including ones generated earlier. */
bills:SvSettlementBill[];
/** Highest clearing-entry id considered by the generation. */
sourceWatermark:number}
export interface SvPaymentResolution {
/** Whether a completed payment registration exists for the given commandKey. */
found:boolean;
/** The payment created by that registration; null when not found. */
payment:SvSettlementPayment|null}
export interface SvFinancialBrandChange {
/** Financial brand change identifier. */
changeNo:string;
/** Change state: PREPARING, PAY_PREPARED or COMPLETED. */
state:string;
/** Whether the store's brand binding change has been staged in the merchant core and awaits the payment-service commit. */
staged:boolean}
export interface SvFinancialBrandRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Destination brand; null removes the store from its current brand. */
toBrandNo:string|null;
/** Store revision the client last read; stale revisions are rejected. */
expectedStoreRevision:number;
/** Business reason for the change, at most 255 characters. */
reason:string}
export interface SvFinancialPermissionResult {
/** Whether the settlement permissions are now granted; true on success. */
prepared:boolean;
/** Operating entity the permissions were granted on. */
entityNo:string;
/** Settlement permission codes now held by the caller on the entity. */
permissions:string[]}
export interface SvFinancialBrandOriginal {
/** Change originally created with the given commandKey; null when no such change exists. */
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
