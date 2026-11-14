import {http} from '@shared/net/http-client';
import {ENDPOINTS as E,buildPath} from './endpoints';
import type {ReplenishmentApi,RpScope} from './replenishment';
const headers=(scope:RpScope,key?:string)=>({'X-Store-No':scope.storeNo,'X-Stock-Entity-No':scope.entityNo,...(key?{'Idempotency-Key':key}:{})});
export const replenishmentHttp:ReplenishmentApi={
 mRpState:(s,action,key)=>http.get(buildPath(E.mRpState.path,{action}),{key},headers(s)),
 mRpCancel:(s,action,key,reason)=>http.post(buildPath(E.mRpCancel.path,{action}),{key,reason},headers(s)),
 mRpContext:()=>http.get(E.mRpContext.path),
 mRpList:(scope,q)=>http.get(E.mRpList.path,q,headers(scope)),
 mRpOriginal:(scope,no)=>http.get(buildPath(E.mRpOriginal.path,{no}),undefined,headers(scope)),
 mRpReconciliation:(scope,no)=>http.get(buildPath(E.mRpReconciliation.path,{no}),undefined,headers(scope)),
 mRpCommand:(scope,action,key)=>http.get(buildPath(E.mRpCommand.path,{action}),{key},headers(scope)),
 mRpConfigurationCommand:(scope,key)=>http.get(E.mRpConfigurationCommand.path,{key},headers(scope)),
 mRpConfigure:(s,b,k)=>http.post(E.mRpConfigure.path,b,headers(s,k)),
 mRpRegister:(s,b,k)=>http.post(E.mRpRegister.path,b,headers(s,k)),
 mRpManualRegister:(s,b,k)=>http.post(E.mRpManualRegister.path,b,headers(s,k)),
 mRpReceive:(s,no,b,k)=>http.post(buildPath(E.mRpReceive.path,{no}),b,headers(s,k)),
 mRpClose:(s,no,b,k)=>http.post(buildPath(E.mRpClose.path,{no}),b,headers(s,k)),
 mRpReturnDispatch:(s,no,b,k)=>http.post(buildPath(E.mRpReturnDispatch.path,{no}),b,headers(s,k)),
 mRpReturnAck:(s,no,b,k)=>http.post(buildPath(E.mRpReturnAck.path,{no}),b,headers(s,k)),
 mRpManualReceipt:(s,no,b,k)=>http.post(buildPath(E.mRpManualReceipt.path,{no}),b,headers(s,k)),
};
