import {describe,it,expect,vi} from 'vitest';
const transport=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}));
vi.mock('@shared/net/http-client',()=>({http:transport}));
import {restoreRrCommand,resendRrCommand,rrCount,rrFactor,rrStorageKey} from '../src/shared/retail-replenishment-command';
import {retailReplenishmentHttp} from '../src/api/retail-replenishment-http';
import type {RetailReplenishmentApi,RrCommand,RrScope} from '../src/api/retail-replenishment';
const scope:RrScope={entityNo:'E',storeNo:'S',actorNo:'A'};
const original:RrCommand={kind:'receive',scope,key:'ORIGINAL',no:'SRP1',body:{cumulativeQuantity:18}};
describe('retail replenishment original requests',()=>{
 it('restores the exact original and isolates entity, store and actor',()=>{
  expect(restoreRrCommand(JSON.stringify(original),scope)).toEqual(original);
  for(const changed of [{...scope,entityNo:'X'},{...scope,storeNo:'X'},{...scope,actorNo:'X'}]){expect(restoreRrCommand(JSON.stringify(original),changed)).toBeNull();expect(rrStorageKey(changed)).not.toBe(rrStorageKey(scope));}
  expect(restoreRrCommand('{broken',scope)).toBeNull();
  expect(restoreRrCommand(JSON.stringify({...original,kind:'manual-register'}),scope)).toBeNull();
 });
 it('resends the same scope, key and body and never on a changed scope',async()=>{
  const api={mRrReceive:vi.fn().mockResolvedValue({original:{originalNo:'SRP1'}})} as unknown as RetailReplenishmentApi;
  await resendRrCommand(api,original,()=>scope);
  expect(api.mRrReceive).toHaveBeenCalledWith(scope,'SRP1',{cumulativeQuantity:18},'ORIGINAL');
  await expect(resendRrCommand(api,original,()=>({...scope,storeNo:'OTHER'}))).rejects.toThrow('retailReplenishment.scopeChanged');
  expect(api.mRrReceive).toHaveBeenCalledTimes(1);
 });
 it('keeps integer counts exact and rejects fractions, zero and overflow',()=>{
  expect(rrCount('20')).toBe(20);expect(rrCount('-3',true)).toBe(-3);expect(rrCount('2147483647')).toBe(2147483647);
  for(const bad of ['0','1.5','-1','2147483648','abc','']) expect(()=>rrCount(bad)).toThrow('retailReplenishment.invalidCount');
  expect(()=>rrCount('0',true)).toThrow();expect(()=>rrCount('-2147483648',true)).toThrow();
  expect(rrFactor('0.500000')).toBe('0.500000');expect(()=>rrFactor('0')).toThrow('retailReplenishment.invalidFactor');
 });
 it('uses the RETAIL paths with trusted scope headers, never the FOOD entry',async()=>{
  await retailReplenishmentHttp.mRrReceive(scope,'SRP1',{cumulativeQuantity:18},'K');
  expect(transport.post).toHaveBeenLastCalledWith('/biz/stock-control/retail-replenishment/SRP1/receive',{cumulativeQuantity:18},{'X-Store-No':'S','X-Stock-Entity-No':'E','Idempotency-Key':'K'});
  await retailReplenishmentHttp.mRrAdjust(scope,{itemNo:'SKU',baseUnit:'EA',delta:-2,reason:'loss'},'J');
  expect(transport.post).toHaveBeenLastCalledWith('/biz/stock-control/retail-replenishment/adjustments',{itemNo:'SKU',baseUnit:'EA',delta:-2,reason:'loss'},expect.objectContaining({'Idempotency-Key':'J'}));
  await retailReplenishmentHttp.mRrList(scope,{after:'SRP1',size:20});
  expect(transport.get).toHaveBeenLastCalledWith('/biz/stock-control/retail-replenishment',{after:'SRP1',size:20},{'X-Store-No':'S','X-Stock-Entity-No':'E'});
  for(const call of [...transport.post.mock.calls,...transport.get.mock.calls]) expect(String(call[0])).not.toContain('/biz/stock-control/replenishments');
 });
});
