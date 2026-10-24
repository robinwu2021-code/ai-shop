import type {SvRechargeOffer,SvProgress,SvRechargeCreate,SvSelectedCustomer} from '@/api/stored-value-types';
export interface SvPending {body:SvRechargeCreate;key:string;customer:SvSelectedCustomer;plan:SvRechargeOffer;progress:SvProgress|null}
/** Local recovery is never a payment authorization. The server still validates every original command. */
export function storedValuePending(merchant:string,store:string,actor=''){
 const legacyKey=`sv.offline:${merchant}:${store}`,mapKey=actor?`sv.offline.v3:${merchant}:${store}:${actor}`:`sv.offline.v2:${merchant}:${store}`;
 function valid(p:SvPending){return p?.body?.storeNo===store&&!!p.key&&!!p.plan&&(p.body.scopeType==='BRAND'?'optionRef' in p.plan&&p.plan.optionRef===p.body.optionRef:'planNo' in p.plan&&p.plan.planNo===p.body.planNo)&&p.customer?.customerRef===p.body.customerRef;}
 function entries():Record<string,SvPending>{
  try{const raw=JSON.parse(String(uni.getStorageSync(mapKey)||'{}'));return Object.fromEntries(Object.entries(raw).filter(([,p])=>valid(p as SvPending))) as Record<string,SvPending>;}catch{return {};}
 }
 function legacy():SvPending|null{if(actor)return null;try{const p=JSON.parse(String(uni.getStorageSync(legacyKey)||'null'));return valid(p)?p:null;}catch{return null;}}
 function write(scope:string,p:SvPending){const all=entries();all[scope]=p;uni.setStorageSync(mapKey,JSON.stringify(all));}
 function remove(scope:string,p:SvPending){
  const all=entries();if(all[scope]?.key===p.key)delete all[scope];
  if(Object.keys(all).length)uni.setStorageSync(mapKey,JSON.stringify(all));else uni.removeStorageSync(mapKey);
  if(legacy()?.key===p.key)uni.removeStorageSync(legacyKey);
 }
 return {entries,legacy,write,remove};
}
