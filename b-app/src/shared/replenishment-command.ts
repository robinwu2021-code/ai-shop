import type {ReplenishmentApi,RpAction,RpCommand,RpScope} from '@/api/replenishment';
const actions:Record<RpCommand['kind'],RpAction>={register:'REPLENISH_REGISTER','manual-register':'REPLENISH_MANUAL_REGISTER',configure:'REPLENISH_CONFIG',receive:'REPLENISH_RECEIVE',close:'REPLENISH_CLOSE','return-dispatch':'REPLENISH_RETURN','return-ack':'REPLENISH_RETURN_ACK','manual-receipt':'REPLENISH_MANUAL'};
export const rpAction=(op:RpCommand)=>actions[op.kind];
export const sameRpScope=(a:RpScope|null,b:RpScope|null)=>!!a&&!!b&&a.entityNo===b.entityNo&&a.storeNo===b.storeNo&&a.actorNo===b.actorNo;
export const rpStorageKey=(scope:RpScope)=>['stock-replenishment-v1',scope.entityNo,scope.storeNo,scope.actorNo].map(encodeURIComponent).join(':');
/** Restore the exact stored body, including decimal strings and original key; never recompute a target. */
export function restoreRpCommand(raw:unknown,scope:RpScope):RpCommand|null {
 if(!raw)return null;
 try{const op=JSON.parse(String(raw)) as RpCommand;return op&&sameRpScope(op.scope,scope)&&Object.hasOwn(actions,op.kind)&&typeof op.key==='string'&&!!op.key&&op.body&&typeof op.body==='object'?op:null;}catch{return null;}
}
export async function resumeRpCommand(api:ReplenishmentApi,op:RpCommand,current:()=>RpScope|null,beforeWrite?:(op:RpCommand)=>void){
 const guard=()=>{if(!sameRpScope(op.scope,current()))throw Error('replenishment.scopeChanged')};
 guard();const action=actions[op.kind];
 const state=await api.mRpState(op.scope,action,op.key);
 guard();if(state.status==='CANCELLED')return {status:'CANCELLED' as const};
 if(state.status==='COMPLETED'){const result=state.original??state.connection;if(!result)throw Error('replenishment.invalidReceipt');return result;}
 if(state.status!=='NOT_COMMITTED')throw Error('replenishment.unknown');
 beforeWrite?.(op);
 let result;
 switch(op.kind){
  case 'configure':result=await api.mRpConfigure(op.scope,op.body,op.key);break;
  case 'register':result=await api.mRpRegister(op.scope,op.body,op.key);break;
  case 'manual-register':result=await api.mRpManualRegister(op.scope,op.body,op.key);break;
  case 'receive':result=await api.mRpReceive(op.scope,op.no,op.body,op.key);break;
  case 'close':result=await api.mRpClose(op.scope,op.no,op.body,op.key);break;
  case 'return-dispatch':result=await api.mRpReturnDispatch(op.scope,op.no,op.body,op.key);break;
  case 'return-ack':result=await api.mRpReturnAck(op.scope,op.no,op.body,op.key);break;
  case 'manual-receipt':result=await api.mRpManualReceipt(op.scope,op.no,op.body,op.key);break;
 }
 guard();return result;
}
/** Keep entry values textual until the server binds BigDecimal; JS Number would round large/fractional stock. */
export function rpQuantity(value:string){const text=value.trim();if(!/^(?:0|[1-9]\d{0,17})(?:\.\d{1,6})?$/.test(text)||!/[1-9]/.test(text))throw Error('replenishment.invalidQuantity');return text;}
