import {http} from '@shared/net/http-client';
import {buildPath,ENDPOINTS as E} from './endpoints';
import type {ManagementApi} from './management-types';
const headers=(key:string)=>({'Idempotency-Key':key});
export const managementHttpApi:ManagementApi={
 mManagementRecipients:(m,cursor)=>http.get(buildPath(E.mManagementRecipients.path,{m}),{cursor}),
 mManagementContext:()=>http.get(E.mManagementContext.path),
 mCreateManagementSpace:(body,key)=>http.post(E.mCreateManagementSpace.path,body,headers(key)),
 mManagementResources:(m,query)=>http.get(buildPath(E.mManagementResources.path,{m}),query),
 mManagementGrant:(m,body)=>http.post(buildPath(E.mManagementGrant.path,{m}),body),
 mRevokeManagementGrant:(m,g,expectedRevision,reason)=>http.del(buildPath(E.mRevokeManagementGrant.path,{m,g})+'?expectedRevision='+expectedRevision+'&reason='+encodeURIComponent(reason)),
 mManagementEntities:(m,cursor)=>http.get(buildPath(E.mManagementEntities.path,{m}),{cursor}),
 mManagementStores:(m,query)=>http.get(buildPath(E.mManagementStores.path,{m}),query),
 mPaymentMerchantCandidates:(e,managementNo,query)=>http.get(buildPath(E.mPaymentMerchantCandidates.path,{e}),{managementNo,...query}),
 mBrands:(managementNo,query)=>http.get(E.mBrands.path,{managementNo,...query}),
 mCreateBrand:(body,key)=>http.post(E.mCreateBrand.path,body,headers(key)),
 mBrand:b=>http.get(buildPath(E.mBrand.path,{b})),
 mEditBrand:(b,body,key)=>http.put(buildPath(E.mEditBrand.path,{b}),body,headers(key)),
 mBrandStatus:(b,body,key)=>http.post(buildPath(E.mBrandStatus.path,{b}),body,headers(key)),
 mBrandStores:(b,cursor)=>http.get(buildPath(E.mBrandStores.path,{b}),{cursor}),
 mBrandAudit:(b,cursor)=>http.get(buildPath(E.mBrandAudit.path,{b}),{cursor}),
 mStoreOperatingContext:(s,managementNo)=>http.get(buildPath(E.mStoreOperatingContext.path,{s}),{managementNo}),
 mCheckBrandChange:(s,body)=>http.post(buildPath(E.mCheckBrandChange.path,{s}),body),
 mSubmitBrandChange:(s,body,key)=>http.post(buildPath(E.mSubmitBrandChange.path,{s}),body,headers(key)),
 mBrandChange:c=>http.get(buildPath(E.mBrandChange.path,{c})),
 mDecideBrandChange:(c,body,key)=>http.post(buildPath(E.mDecideBrandChange.path,{c}),body,headers(key)),
};
