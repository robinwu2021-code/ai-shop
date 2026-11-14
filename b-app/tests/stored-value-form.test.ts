import { expect,it } from 'vitest';
import { fixedMinor, dateFilter, createRechargeProgress } from '@/shared/stored-value-form';
it('uses exact minor units without rounding and rejects overflow',()=>{
 expect(fixedMinor('50.10',2)).toBe(5010);
 expect(()=>fixedMinor('50.001',2)).toThrow();
 expect(()=>fixedMinor('-1',2)).toThrow();
 expect(()=>fixedMinor('99999999999999999',2)).toThrow();
 expect(fixedMinor('100',0)).toBe(100);
});
it('keeps calendar dates for server-owned timezone conversion and rejects reversed or invalid dates',()=>{
 expect(dateFilter('2026-10-20','2026-10-20')).toEqual({startDate:'2026-10-20',endDate:'2026-10-20'});
 expect(()=>dateFilter('2026-10-21','2026-10-20')).toThrow();
 expect(()=>dateFilter('2026-02-30','')).toThrow();
});
it('retries original workflow stages without creating a second recharge',async()=>{
 let creates=0;const service={create:async()=>{creates++;return {rechargeNo:'R1',status:'WAIT_COLLECTION',revision:0};},submit:async()=>({rechargeNo:'R1',status:'SUBMITTED',revision:1}),receive:async()=>{throw Error('network');},query:async()=>({rechargeNo:'R1',status:'CREDITED',revision:3})};
 const flow=createRechargeProgress(service);
 await expect(flow.advance()).rejects.toThrow('network');
 expect(flow.current?.rechargeNo).toBe('R1');
 await flow.refresh();expect(flow.current?.status).toBe('CREDITED');expect(creates).toBe(1);
});

it('stops before the next mutation when the operator leaves the captured store',async()=>{
 let active=true;let submits=0;let receives=0;
 const flow=createRechargeProgress({
  create:async()=>{active=false;return {rechargeNo:'A1',status:'WAIT_COLLECTION',revision:0};},
  submit:async r=>{submits++;return {...r,status:'SUBMITTED'};},
  receive:async r=>{receives++;return {...r,status:'RECEIVED'};},query:async()=>{throw Error('unused');}
 },()=>{},null,()=>active);
 await expect(flow.advance()).rejects.toThrow('inactive');
 expect(submits).toBe(0);expect(receives).toBe(0);expect(flow.current?.rechargeNo).toBe('A1');
});
it('stops confirmation after an in-flight submit loses the store context',async()=>{
 let active=true;let receives=0;
 const flow=createRechargeProgress({create:async()=>({rechargeNo:'A2',status:'WAIT_COLLECTION',revision:0}),
 submit:async r=>{active=false;return {...r,status:'SUBMITTED'};},receive:async r=>{receives++;return r;},query:async()=>{throw Error('unused');}
 },()=>{},null,()=>active);
 await expect(flow.advance()).rejects.toThrow('inactive');expect(receives).toBe(0);
});
