import type {RetailReplenishmentApi,RrCommand,RrScope} from '@/api/retail-replenishment';
const kinds=['configure','register','adjust','receive','close','return-dispatch','return-ack','manual-receipt'];
export const sameRrScope=(a:RrScope|null,b:RrScope|null)=>!!a&&!!b&&a.entityNo===b.entityNo&&a.storeNo===b.storeNo&&a.actorNo===b.actorNo;
export const rrStorageKey=(scope:RrScope)=>['retail-replenishment-v1',scope.entityNo,scope.storeNo,scope.actorNo].map(encodeURIComponent).join(':');
export function restoreRrCommand(raw:unknown,scope:RrScope):RrCommand|null {
 try{const op=JSON.parse(String(raw)) as RrCommand;return op&&sameRrScope(op.scope,scope)&&kinds.includes(op.kind)&&typeof op.key==='string'&&!!op.key&&op.body&&typeof op.body==='object'?op:null;}catch{return null;}
}
/**
 * Retail commands commit atomically with their permanent receipt, so an unknown result is recovered by sending the
 * exact original scope, key and body again: the server replays the receipt or performs the never-committed command.
 */
export async function resendRrCommand(api:RetailReplenishmentApi,op:RrCommand,current:()=>RrScope|null){
 const guard=()=>{if(!sameRrScope(op.scope,current()))throw Error('retailReplenishment.scopeChanged')};
 guard();let result;
 switch(op.kind){
  case 'configure':result=await api.mRrConfigure(op.scope,op.body,op.key);break;
  case 'register':result=await api.mRrRegister(op.scope,op.body,op.key);break;
  case 'adjust':result=await api.mRrAdjust(op.scope,op.body,op.key);break;
  case 'receive':result=await api.mRrReceive(op.scope,op.no,op.body,op.key);break;
  case 'close':result=await api.mRrClose(op.scope,op.no,op.body,op.key);break;
  case 'return-dispatch':result=await api.mRrReturnDispatch(op.scope,op.no,op.body,op.key);break;
  case 'return-ack':result=await api.mRrReturnAck(op.scope,op.no,op.body,op.key);break;
  case 'manual-receipt':result=await api.mRrManualReceipt(op.scope,op.no,op.body,op.key);break;
 }
 guard();return result;
}
/** The PLATFORM ledger is integer; never round or truncate an entered count. */
export function rrCount(value:string,signed=false){
 const text=value.trim();if(!(signed?/^-?[1-9]\d{0,9}$/:/^[1-9]\d{0,9}$/).test(text))throw Error('retailReplenishment.invalidCount');
 const n=Number(text);if(Math.abs(n)>2147483647)throw Error('retailReplenishment.invalidCount');return n;
}
/** Source-to-local factor stays textual until the server binds an exact BigDecimal. */
export function rrFactor(value:string){const text=value.trim();if(!/^(?:0|[1-9]\d{0,17})(?:\.\d{1,6})?$/.test(text)||!/[1-9]/.test(text))throw Error('retailReplenishment.invalidFactor');return text;}
