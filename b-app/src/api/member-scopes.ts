import {api} from '@/api';
import type {SvSelectionSource,SvSelectedCustomer} from './stored-value-types';
/** Route explicitly scoped members to MB02; legacy wallets retain the original store-bound API. */
export async function selectStoredValueMember(store:string,source:SvSelectionSource):Promise<SvSelectedCustomer>{
 if(source.type==='MEMBER'&&source.memberScope==='OPERATING'){
  const result=await api.mScopeSelectCustomer(store,{source:{type:'MEMBER',memberNo:source.memberNo}});
  return {...result.selection,sourceStoreNo:result.sourceStoreNo,sourceStoreName:result.sourceStoreName};
 }
 return api.mSvSelectCustomer({storeNo:store,source});
}
