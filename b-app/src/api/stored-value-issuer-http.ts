import {http} from '@shared/net/http-client';
import {buildPath,ENDPOINTS as E} from './endpoints';
import type {IssuerApi} from './stored-value-issuers';
const headers=(key:string)=>({'Idempotency-Key':key});
export const issuerHttpApi:IssuerApi={
 mSvStoreIssuerConfiguration:(no,managementNo)=>http.get(buildPath(E.mSvStoreIssuerConfiguration.path,{no}),{managementNo}),
 mSvConfigureStoreIssuer:(no,body,key)=>http.post(buildPath(E.mSvConfigureStoreIssuer.path,{no}),body,headers(key)),
 mSvIssuer:(type,no,managementNo)=>http.get(buildPath(type==='BRAND'?E.mSvIssuer.path:E.mSvStoreIssuerConfiguration.path,{no}),{managementNo}),
 mSvConfigureIssuer:(type,no,body,key)=>http.post(buildPath(type==='BRAND'?E.mSvConfigureIssuer.path:E.mSvConfigureStoreIssuer.path,{no}),body,headers(key)),
 mSvIssuerImpact:(no,body)=>http.post(buildPath(E.mSvIssuerImpact.path,{no}),body),
 mSvIssuerVersion:(no,body,key)=>http.post(buildPath(E.mSvIssuerVersion.path,{no}),body,headers(key)),
 mSvIssuerEntities:(managementNo,scopeType,scopeNo)=>http.get(E.mSvIssuerEntities.path,{managementNo,scopeType,scopeNo}),
 mSvWalletRecords:(storeNo,customerRef,q)=>http.get(buildPath(E.mSvWalletRecords.path,{storeNo,customerRef}),q),
 mSvConsumptionCancel:(orderNo,storeNo)=>http.post(buildPath(E.mSvConsumptionCancel.path,{orderNo}),{storeNo}),
 mSvConsumptionResolve:(body,key)=>http.post(E.mSvConsumptionResolve.path,body,headers(key)),
 mSvConsumptionPreview:(body,key)=>http.post(E.mSvConsumptionPreview.path,body,headers(key)),
 mSvConsumptionOrder:(orderNo,storeNo)=>http.get(buildPath(E.mSvConsumptionOrder.path,{orderNo}),{storeNo}),
 mSvConsume:body=>http.post(E.mSvConsume.path,body),
 mSvWallets:(storeNo,customerRef)=>http.get(buildPath(E.mSvWallets.path,{storeNo,customerRef})),
 mSvBrandCapability:()=>http.get(E.mSvBrandCapability.path),
 mSvBrandActivate:(brandNo,body,key)=>http.post(buildPath(E.mSvBrandActivate.path,{brandNo}),body,headers(key)),
 mSvStoreIssuers:storeNo=>http.get(buildPath(E.mSvStoreIssuers.path,{storeNo})),
 mSvRechargeOptions:({storeNo,...query})=>http.get(buildPath(E.mSvRechargeOptions.path,{storeNo}),{...query,scopeType:query.scopeType||'STORE'}),
 mSvIssuerRoutes:(no,managementNo)=>http.get(buildPath(E.mSvIssuerRoutes.path,{no}),{managementNo}),
 mSvPrepareIssuer:(no,managementNo)=>http.post(buildPath(E.mSvPrepareIssuer.path,{no}),{managementNo}),
 mSvRefreshIssuer:(no,managementNo)=>http.post(buildPath(E.mSvRefreshIssuer.path,{no}),{managementNo}),
 mSvIssuerRecharge:(rechargeNo,storeNo)=>http.get(buildPath(E.mSvIssuerRecharge.path,{rechargeNo,storeNo})),
 mSvIssuerCancel:(rechargeNo,storeNo,body,key)=>http.post(buildPath(E.mSvIssuerCancel.path,{rechargeNo,storeNo}),body,headers(key)),
};
