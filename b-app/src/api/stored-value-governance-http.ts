import {http} from '@shared/net/http-client';
import {ENDPOINTS as E,buildPath} from './endpoints';
import type {StoredValueGovernanceApi} from './stored-value-governance';
const h=(key:string)=>({'Idempotency-Key':key});
const path=(endpoint:{path:string},params:Record<string,string>={})=>buildPath(endpoint.path,params);
export const storedValueGovernanceHttp:StoredValueGovernanceApi={
 mSvActivityDraftEdit:(store,no,body,key)=>http.put(path(E.mSvActivityDraftEdit,{store,no}),body,h(key)),
 mSvPreparePolicyPermissions:managementNo=>http.post(E.mSvPreparePolicyPermissions.path,{managementNo}),
 mSvActivityContext:(store,q)=>http.get(path(E.mSvActivityContext,{store}),q),
 mSvActivityPresets:(store,q)=>http.get(path(E.mSvActivityPresets,{store}),q),
 mSvActivitySchedule:(store,q)=>http.get(path(E.mSvActivitySchedule,{store}),q),
 mSvGovernanceCapability:()=>http.get(E.mSvGovernanceCapability.path),
 mSvPolicyTargets:managementNo=>http.get(E.mSvPolicyTargets.path,{managementNo}),
 mSvPolicies:q=>http.get(E.mSvPolicies.path,q),
 mSvPolicyCreate:(body,key)=>http.post(E.mSvPolicyCreate.path,body,h(key)),
 mSvPolicyEdit:(no,body,key)=>http.put(path(E.mSvPolicyEdit,{no}),body,h(key)),
 mSvEffectivePolicy:(store,q)=>http.get(path(E.mSvEffectivePolicy,{store}),q),
 mSvActivities:(store,q)=>http.get(path(E.mSvActivities,{store}),q),
 mSvActivity:(store,no,q)=>http.get(path(E.mSvActivity,{store,no}),q),
 mSvActivitySave:(store,no,body,key)=>no?http.put(path(E.mSvActivityDraftEdit,{store,no}),body,h(key)):http.post(path(E.mSvActivitySave,{store}),body,h(key)),
 mSvActivityCheck:(store,no,body)=>http.post(path(E.mSvActivityCheck,{store,no}),body),
 mSvActivitySubmit:(store,no,body,key)=>http.post(path(E.mSvActivitySubmit,{store,no}),body,h(key)),
 mSvActivityStop:(store,no,body,key)=>http.post(path(E.mSvActivityStop,{store,no}),body,h(key)),
 mSvActivityCopy:(store,no,body,key)=>http.post(path(E.mSvActivityCopy,{store,no}),body,h(key)),
 mSvApprovals:q=>http.get(E.mSvApprovals.path,q),
 mSvApproval:(no,managementNo)=>http.get(path(E.mSvApproval,{no}),{managementNo}),
 mSvApprovalDecide:(no,body,key)=>http.post(path(E.mSvApprovalDecide,{no}),body,h(key)),
};
