import {http} from '@shared/net/http-client';
import {ENDPOINTS as E,buildPath} from './endpoints';
import type {RetailReplenishmentApi,RrScope} from './retail-replenishment';
const headers=(scope:RrScope,key?:string)=>({'X-Store-No':scope.storeNo,'X-Stock-Entity-No':scope.entityNo,...(key?{'Idempotency-Key':key}:{})});
export const retailReplenishmentHttp:RetailReplenishmentApi={
 mRrContext:()=>http.get(E.mRrContext.path),
 mRrList:(s,q)=>http.get(E.mRrList.path,q,headers(s)),
 mRrOriginal:(s,no)=>http.get(buildPath(E.mRrOriginal.path,{no}),undefined,headers(s)),
 mRrReconciliation:(s,no)=>http.get(buildPath(E.mRrReconciliation.path,{no}),undefined,headers(s)),
 mRrConfigure:(s,b,k)=>http.post(E.mRrConfigure.path,b,headers(s,k)),
 mRrRegister:(s,b,k)=>http.post(E.mRrRegister.path,b,headers(s,k)),
 mRrAdjust:(s,b,k)=>http.post(E.mRrAdjust.path,b,headers(s,k)),
 mRrReceive:(s,no,b,k)=>http.post(buildPath(E.mRrReceive.path,{no}),b,headers(s,k)),
 mRrClose:(s,no,b,k)=>http.post(buildPath(E.mRrClose.path,{no}),b,headers(s,k)),
 mRrReturnDispatch:(s,no,b,k)=>http.post(buildPath(E.mRrReturnDispatch.path,{no}),b,headers(s,k)),
 mRrReturnAck:(s,no,b,k)=>http.post(buildPath(E.mRrReturnAck.path,{no}),b,headers(s,k)),
 mRrManualReceipt:(s,no,b,k)=>http.post(buildPath(E.mRrManualReceipt.path,{no}),b,headers(s,k)),
};
