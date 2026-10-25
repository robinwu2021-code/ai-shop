import type {CurrencyCode} from '@shared/types';
import type {SvPage} from './stored-value-types';
export interface SvFinanceQuery {managementNo:string;scopeType?:'STORE'|'BRAND';issuerNo?:string;currency?:string;status?:string;periodFrom?:string;periodTo?:string;cursor?:string;size?:number}
export interface SvReportQuery {managementNo:string;currency:CurrencyCode;startDate:string;endDate:string;activityNo?:string;cursor?:string;size?:number}
export interface SvFinanceResource {issuerNo:string;scopeType:'STORE'|'BRAND';scopeNo:string;name:string;currency:CurrencyCode;businessTimeZone:string;
/** Current server-verified generate permission for this issuer; absent values fail closed. */
canGenerate:boolean}
export interface SvFinanceCapability {protocol:number;reportsReady:boolean;settlementReady:boolean;crossEntityReady:boolean;receiptAttachmentsSupported:boolean;blockers:string[]}
export interface SvSummary {issuerNo:string;scopeType:string;name:string;currency:CurrencyCode;period:{from:string;to:string};asOf:string;sourceWatermark:number;openingMinor:number;creditedPaidMinor:number;creditedBonusMinor:number;consumedMinor:number;reversedMinor:number;closingMinor:number;heldMinor:number;rechargeCount:number;usageCount:number;customerCount:number;payableMinor:number}
export interface SvStoreAttribution {storeNo:string;storeName:string;creditedPaidMinor:number;creditedBonusMinor:number;consumedMinor:number;payableMinor:number;rechargeCount:number;usageCount:number;customerCount:number;asOf:string;sourceWatermark:number}
export interface SvActivityAttribution {activityNo:string;releaseNo:string;sourceKind:string;name:string;creditedPaidMinor:number;creditedBonusMinor:number;consumedPrincipalMinor:number;consumedBonusMinor:number;remainingMinor:number;rechargeCount:number;usageCount:number;customerCount:number;asOf:string;sourceWatermark:number}
export interface SvSettlementBill {billNo:string;issuerNo:string;payerEntityNo:string;payeeEntityNo:string;currency:CurrencyCode;periodFrom:string;periodTo:string;billKind:string;status:string;payableMinor:number;registeredPaymentMinor:number;confirmedReceiptMinor:number;principalMinor:number;bonusMinor:number;sourceCount:number;sourceWatermark:number;payerReconciledBy:string|null;payeeReconciledBy:string|null;disputeNo:string|null;revision:number;createdAt:string;updatedAt:string}
export interface SvSettlementItem {itemNo:string;clearingNo:string;amountMinor:number;principalMinor:number;bonusMinor:number;rechargeStoreNo:string;redemptionStoreNo:string;occurredAt:string;postedAt:string;carriedForward:boolean;entryType:string;originalClearingNo:string|null}
export interface SvSettlementEvent {eventNo:string;type:string;billRevision:number;actorNo:string;reason:string|null;occurredAt:string}
export interface SvSettlementPayment {paymentNo:string;billNo:string;currency:CurrencyCode;method:string;amountMinor:number;paidAt:string;status:string;registeredBy:string;receivedBy:string|null;receivedAt:string|null;revision:number}
export interface SvBillActions {payerActions:string[];payeeActions:string[]}
export interface SvGenerationRequest {managementNo:string;issuerNo:string;periodFrom:string;periodTo:string;currency:CurrencyCode}
export interface SvDecisionRequest {managementNo:string;expectedRevision:number;side:'PAYER'|'PAYEE';reason?:string}
export interface SvResolutionRequest {managementNo:string;expectedRevision:number;disputeNo:string;decision:'ACCEPT'|'REJECT';reason:string}
export interface SvAdjustmentRequest {managementNo:string;clearingNo:string;expectedRevision:number;payableDeltaMinor:number;reason:string}
export interface SvPaymentRequest {managementNo:string;expectedRevision:number;amountMinor:number;paidAt:string;method:'TRANSFER'|'CASH'|'OTHER';receiptReference:string;evidenceRefs:[]}
export interface SvReceiptRequest {managementNo:string;expectedRevision:number;receivedAt:string}
export interface SvVoidRequest {managementNo:string;expectedRevision:number;reason:string}
export interface SvGenerated {bills:SvSettlementBill[];sourceWatermark:number}
export interface SvPaymentResolution {found:boolean;payment:SvSettlementPayment|null}
export interface SvFinancialBrandChange {changeNo:string;state:string;staged:boolean}
export interface SvFinancialBrandRequest {managementNo:string;toBrandNo:string|null;expectedStoreRevision:number;reason:string}
export interface SvFinancialPermissionResult {prepared:boolean;entityNo:string;permissions:string[]}
export interface SvFinancialBrandOriginal {original:SvFinancialBrandChange|null}
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
