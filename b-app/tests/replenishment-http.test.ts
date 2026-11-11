import {it,expect,vi,beforeEach} from 'vitest';
const transport=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}));
vi.mock('@shared/net/http-client',()=>({http:transport}));
import {replenishmentHttp as api} from '../src/api/replenishment-http';
const scope={entityNo:'E',storeNo:'S',actorNo:'ACT'};
beforeEach(()=>vi.clearAllMocks());
it('uses formal paths and binds writes to the original trusted store/entity precondition',async()=>{
 await api.mRpReceive(scope,'R',{cumulativeQuantity:'0.123456'},'ORIGINAL');
 expect(transport.post).toHaveBeenCalledWith('/biz/stock-control/replenishments/R/receive',{cumulativeQuantity:'0.123456'},{'X-Store-No':'S','X-Stock-Entity-No':'E','Idempotency-Key':'ORIGINAL'});
 await api.mRpManualReceipt(scope,'R',{movementNo:'MOV'},'LINK');expect(transport.post).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/R/manual-receipt',{movementNo:'MOV'},expect.objectContaining({'Idempotency-Key':'LINK'}));
});
it('separates original control receipts, physical receipts and read-only reconciliation',async()=>{
 await api.mRpConfigurationCommand(scope,'OFF');expect(transport.get).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/connection/command',{key:'OFF'},expect.objectContaining({'X-Stock-Entity-No':'E'}));
 await api.mRpCommand(scope,'REPLENISH_RECEIVE','KEY');expect(transport.get).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/commands/REPLENISH_RECEIVE',{key:'KEY'},expect.anything());
 await api.mRpReconciliation(scope,'R');expect(transport.get).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/R/reconciliation',undefined,expect.anything());expect(transport.post).not.toHaveBeenCalled();
});

it('uses durable original-key state and cancellation without fabricating a new stock operation',async()=>{
 await api.mRpState(scope,'REPLENISH_RECEIVE','OLD');expect(transport.get).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/commands/REPLENISH_RECEIVE/state',{key:'OLD'},expect.anything());
 await api.mRpCancel(scope,'REPLENISH_RECEIVE','OLD','stop');expect(transport.post).toHaveBeenLastCalledWith('/biz/stock-control/replenishments/commands/REPLENISH_RECEIVE/cancel',{key:'OLD',reason:'stop'},expect.objectContaining({'X-Store-No':'S','X-Stock-Entity-No':'E'}));
});
