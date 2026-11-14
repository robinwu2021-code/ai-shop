import { beforeEach,expect,it,vi } from 'vitest';
const {get,post,put}=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),put:vi.fn()}));
vi.mock('@shared/net/http-client',()=>({http:{get,post,put},clientTag:()=> 'test'}));
import { httpApi } from '@/api/http';
beforeEach(()=>vi.clearAllMocks());
it('binds reads to the selected store and keeps type/date filters',async()=>{
 await httpApi.mSvRecords({storeNo:'S1',type:'REDEMPTION',startDate:'2026-10-20',endDate:'2026-10-20'});
 expect(get).toHaveBeenCalledWith('/biz/stores/S1/stored-value/issuer-records',{storeNo:'S1',type:'REDEMPTION',startDate:'2026-10-20',endDate:'2026-10-20'});
 await httpApi.mSvAccount('a.b/c','S1');expect(get).toHaveBeenLastCalledWith('/biz/stored-value/accounts/a.b%2Fc',{storeNo:'S1'});
});
it('sends immutable plan creation and status commands with original keys and revisions',async()=>{
 const body={storeNo:'S1',name:'充50送10',currency:'CNY' as const,paidAmountMinor:5000,bonusAmountMinor:1000};
 await httpApi.mSvCreatePlan(body,'K1');expect(post).toHaveBeenCalledWith('/biz/stored-value/plans',body,{'Idempotency-Key':'K1'});
 await httpApi.mSvPlanStatus('P1',{storeNo:'S1',status:'INACTIVE',expectedRevision:3},'K2');
 expect(put).toHaveBeenCalledWith('/biz/stored-value/plans/P1/status',{storeNo:'S1',status:'INACTIVE',expectedRevision:3},{'Idempotency-Key':'K2'});
});
it('creates a recharge without activity fields and submits only the receipt reference',async()=>{
 const body={storeNo:'S1',customerRef:'C1',planNo:'P1',versionNo:'V1'};
 await httpApi.mSvCreateRecharge(body,'K1');expect(post).toHaveBeenCalledWith('/biz/stored-value/plan-recharges',body,{'Idempotency-Key':'K1'});
 await httpApi.mSvReceive('R1',{expectedRevision:2,receiptNo:'OFFLINE-1',confirmationNote:''},'K2');
 expect(post).toHaveBeenLastCalledWith('/biz/stored-value/plan-recharges/R1/receive',{expectedRevision:2,receiptNo:'OFFLINE-1',confirmationNote:''},{'Idempotency-Key':'K2'});
});
