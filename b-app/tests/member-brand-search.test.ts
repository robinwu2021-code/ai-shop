// @vitest-environment happy-dom
import {it,expect,vi} from 'vitest';
const calls=vi.hoisted(()=>({scoped:vi.fn(),legacy:vi.fn()}));
vi.mock('@/api',()=>({api:{mScopeSelectCustomer:calls.scoped,mSvSelectCustomer:calls.legacy}}));
import {selectStoredValueMember} from '@/api/member-scopes';
it('uses scoped member identification for brand search without changing the executing store',async()=>{
 calls.scoped.mockResolvedValue({selection:{customerRef:'cr1_scoped',displayName:'顾客',account:null},sourceStoreNo:'S2',sourceStoreName:'乙店'});
 const r=await selectStoredValueMember('S1',{type:'MEMBER',memberNo:'MSM1',memberScope:'OPERATING'});
 expect(calls.scoped).toHaveBeenCalledWith('S1',{source:{type:'MEMBER',memberNo:'MSM1'}});expect(calls.legacy).not.toHaveBeenCalled();expect(r.account).toBeNull();expect(r.sourceStoreName).toBe('乙店');
});
it('retains legacy account selection and never guesses a brand account',async()=>{
 calls.legacy.mockResolvedValue({customerRef:'cr1_old',account:{accountRef:'A1'}});
 const r=await selectStoredValueMember('S1',{type:'ACCOUNT',accountRef:'A1'});expect(calls.legacy).toHaveBeenCalledWith({storeNo:'S1',source:{type:'ACCOUNT',accountRef:'A1'}});expect(r.account?.accountRef).toBe('A1');
});
