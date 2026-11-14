import {describe,it,expect,vi} from 'vitest';
import {restoreRpCommand,resumeRpCommand,rpQuantity,rpStorageKey} from '../src/shared/replenishment-command';
import type {RpCommand,RpScope,ReplenishmentApi} from '../src/api/replenishment';
const scope:RpScope={entityNo:'E',storeNo:'S',actorNo:'A'};
const original:RpCommand={kind:'receive',scope,key:'ORIGINAL',no:'R',body:{cumulativeQuantity:'0.123456'}};
function api(){return {mRpState:vi.fn(),mRpCommand:vi.fn(),mRpConfigurationCommand:vi.fn(),mRpReceive:vi.fn(),mRpConfigure:vi.fn()} as unknown as ReplenishmentApi;}
describe('replenishment original requests',()=>{
 it('restores exact decimal text and isolates entity, store and actual actor',()=>{
  expect(restoreRpCommand(JSON.stringify(original),scope)).toEqual(original);
  for(const changed of [{...scope,entityNo:'OTHER'},{...scope,storeNo:'OTHER'},{...scope,actorNo:'OTHER'}]){expect(restoreRpCommand(JSON.stringify(original),changed)).toBeNull();expect(rpStorageKey(scope)).not.toBe(rpStorageKey(changed));}
  expect(restoreRpCommand('{broken',scope)).toBeNull();
 });
 it('looks up the committed original before resending and returns its old snapshot',async()=>{
  const a=api();vi.mocked(a.mRpState).mockResolvedValue({status:'COMPLETED',original:{replenishmentNo:'R'},connection:null} as never);
  expect(await resumeRpCommand(a,original,()=>scope)).toEqual({replenishmentNo:'R'});expect(a.mRpReceive).not.toHaveBeenCalled();
 });
 it('retries exact body and key only after explicit absence and persists before writing',async()=>{
  const a=api(),before=vi.fn();vi.mocked(a.mRpState).mockResolvedValue({status:'NOT_COMMITTED',original:null,connection:null});vi.mocked(a.mRpReceive).mockResolvedValue({replenishmentNo:'R'} as never);
  await resumeRpCommand(a,original,()=>scope,before);expect(before).toHaveBeenCalledWith(original);expect(a.mRpReceive).toHaveBeenCalledWith(scope,'R',{cumulativeQuantity:'0.123456'},'ORIGINAL');
  expect(before.mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(a.mRpReceive).mock.invocationCallOrder[0]!);
 });
 it('never converts failed lookup into permission to resend',async()=>{
  const a=api();vi.mocked(a.mRpState).mockRejectedValue(Error('timeout'));
  await expect(resumeRpCommand(a,original,()=>scope)).rejects.toThrow('timeout');expect(a.mRpReceive).not.toHaveBeenCalled();
 });
 it('does not send after a scope switch while the lookup was in flight',async()=>{
  const a=api();let current=scope;vi.mocked(a.mRpState).mockImplementation(async()=>{current={...scope,storeNo:'NEW'};return {status:'NOT_COMMITTED',original:null,connection:null};});
  await expect(resumeRpCommand(a,original,()=>current)).rejects.toThrow('scopeChanged');expect(a.mRpReceive).not.toHaveBeenCalled();
 });
 it('recovers connection receipts through the correct typed endpoint',async()=>{
  const a=api();vi.mocked(a.mRpState).mockResolvedValue({status:'COMPLETED',original:null,connection:{enabled:false}} as never);
  await resumeRpCommand(a,{scope,key:'OFF',kind:'configure',body:{enabled:false,expectedVersion:3,reason:'stop'}},()=>scope);
  expect(a.mRpState).toHaveBeenCalledWith(scope,'REPLENISH_CONFIG','OFF');expect(a.mRpConfigure).not.toHaveBeenCalled();
 });
 it('does not round decimal or large quantity input through JavaScript numbers',()=>{
  expect(rpQuantity('999999999999999999.123456')).toBe('999999999999999999.123456');expect(rpQuantity('0.000001')).toBe('0.000001');
  for(const invalid of ['0','0.000000','-1','1.0000001','NaN','1e3','1000000000000000000'])expect(()=>rpQuantity(invalid)).toThrow('invalidQuantity');
 });
 it('treats a server sealed request as terminal without sending it again',async()=>{
  const a=api();vi.mocked(a.mRpState).mockResolvedValue({status:'CANCELLED',original:null,connection:null});
  expect(await resumeRpCommand(a,original,()=>scope)).toEqual({status:'CANCELLED'});expect(a.mRpReceive).not.toHaveBeenCalled();
 });

});
