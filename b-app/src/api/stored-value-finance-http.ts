import {http} from '@shared/net/http-client';
import {ENDPOINTS as E,buildPath} from './endpoints';
import type {StoredValueFinanceApi} from './stored-value-finance';
const h=(key:string)=>({'Idempotency-Key':key});
const path=(endpoint:{path:string},params:Record<string,string>)=>buildPath(endpoint.path,params);
export const storedValueFinanceHttp:StoredValueFinanceApi={
 mSvFinancialBrandOriginal:(store,managementNo,commandKey)=>http.get(path(E.mSvFinancialBrandOriginal,{store}),{managementNo,commandKey}),
 mSvPrepareFinancePermissions:(entity,body)=>http.post(path(E.mSvPrepareFinancePermissions,{entity}),body),
 mSvFinancialBrandStart:(store,body,key)=>http.post(path(E.mSvFinancialBrandStart,{store}),body,h(key)),
 mSvFinancialBrandChange:(no,managementNo)=>http.get(path(E.mSvFinancialBrandChange,{no}),{managementNo}),
 mSvFinancialBrandResume:(no,body)=>http.post(path(E.mSvFinancialBrandResume,{no}),body),
 mSvFinanceCapability:()=>http.get(E.mSvFinanceCapability.path),
 mSvFinanceResources:(managementNo,scopeType)=>http.get(E.mSvFinanceResources.path,{managementNo,scopeType}),
 mSvBrandSummary:(no,q)=>http.get(path(E.mSvBrandSummary,{no}),q),
 mSvStoreSummary:(no,q)=>http.get(path(E.mSvStoreSummary,{no}),q),
 mSvBrandStoreBreakdown:(no,q)=>http.get(path(E.mSvBrandStoreBreakdown,{no}),q),
 mSvLocalIssuerSummary:(q)=>http.get(E.mSvLocalIssuerSummary.path,q),
 mSvActivitySummary:(no,q)=>http.get(path(E.mSvActivitySummary,{no}),q),
 mSvSettlementBills:(q)=>http.get(E.mSvSettlementBills.path,q),
 mSvSettlementBill:(no,managementNo)=>http.get(path(E.mSvSettlementBill,{no}),{managementNo}),
 mSvSettlementActions:(no,managementNo)=>http.get(path(E.mSvSettlementActions,{no}),{managementNo}),
 mSvSettlementItems:(no,q)=>http.get(path(E.mSvSettlementItems,{no}),q),
 mSvSettlementEvents:(no,q)=>http.get(path(E.mSvSettlementEvents,{no}),q),
 mSvSettlementPayments:(no,q)=>http.get(path(E.mSvSettlementPayments,{no}),q),
 mSvSettlementGenerate:(body,key)=>http.post(E.mSvSettlementGenerate.path,body,h(key)),
 mSvSettlementReconcile:(no,body,key)=>http.post(path(E.mSvSettlementReconcile,{no}),body,h(key)),
 mSvSettlementDispute:(no,body,key)=>http.post(path(E.mSvSettlementDispute,{no}),body,h(key)),
 mSvSettlementResolveDispute:(no,body,key)=>http.post(path(E.mSvSettlementResolveDispute,{no}),body,h(key)),
 mSvSettlementAdjust:(no,body,key)=>http.post(path(E.mSvSettlementAdjust,{no}),body,h(key)),
 mSvSettlementRegister:(no,body,key)=>http.post(path(E.mSvSettlementRegister,{no}),body,h(key)),
 mSvSettlementPaymentResolution:(no,managementNo,commandKey)=>http.get(path(E.mSvSettlementPaymentResolution,{no}),{managementNo,commandKey}),
 mSvSettlementReceipt:(no,body,key)=>http.post(path(E.mSvSettlementReceipt,{no}),body,h(key)),
 mSvSettlementVoid:(no,body,key)=>http.post(path(E.mSvSettlementVoid,{no}),body,h(key)),
};
