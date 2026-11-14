/** Simple FOOD stock receipts; this is separate from inventory-service on-hand and mirror settings. */
export interface RpScope {entityNo:string;storeNo:string;actorNo:string}
export interface RpConnection {enabled:boolean;generation:number;revision:number;sourceSystem:string|null;sourceOwner:string|null;postingWatermark:number}
export interface RpCommandState {status:'NOT_COMMITTED'|'COMPLETED'|'CANCELLED'|'UNKNOWN';original:RpView|null;connection:RpConnection|null}
export interface RpContext extends RpScope {connection:RpConnection}
export interface RpView {replenishmentNo:string;sourceSystem:string;sourceOwner:string;sourceDocument:string;sourceLine:number;sourceItem:string;sourceUnit:string;sourceQuantity:number;itemNo:string;baseUnit:string;factor:number;expectedQuantity:number;receivedQuantity:number;differenceQuantity:number;returnedQuantity:number;returnAcceptedQuantity:number;status:'PENDING'|'PARTIAL'|'RECEIVED'|'CLOSED'}
export interface RpPage {items:RpView[];nextCursor:number|null;throughId:number}
export interface RpReconciliation {original:RpView;receivedLedgerQuantity:number;manualQuantity:number;returnedLedgerQuantity:number;acceptedReturnQuantity:number;issues:string[]}
export interface RpMapping {itemNo:string;baseUnit:string;factor:string}
export interface RpRegister {documentNo:string;lineNo:number;mapping:RpMapping}
export interface RpChange {enabled:boolean;expectedVersion:number;reason:string}
export type RpAction='REPLENISH_REGISTER'|'REPLENISH_RECEIVE'|'REPLENISH_CLOSE'|'REPLENISH_RETURN'|'REPLENISH_RETURN_ACK'|'REPLENISH_MANUAL'|'REPLENISH_MANUAL_REGISTER'|'REPLENISH_CONFIG';
export type RpCommand=({kind:'register';body:RpRegister}|{kind:'manual-register';body:RpRegister&{movementNo:string}}|{kind:'configure';body:RpChange}|{kind:'receive';no:string;body:{cumulativeQuantity:string}}|{kind:'close';no:string;body:{reason:string}}|{kind:'return-dispatch';no:string;body:{cumulativeQuantity:string;reason:string}}|{kind:'return-ack';no:string;body:{documentNo:string;lineNo:number}}|{kind:'manual-receipt';no:string;body:{movementNo:string}})&{scope:RpScope;key:string;attempted?:boolean};
export interface ReplenishmentApi {
 mRpState(scope:RpScope,action:RpAction,key:string):Promise<RpCommandState>;
 mRpCancel(scope:RpScope,action:RpAction,key:string,reason:string):Promise<RpCommandState>;
 mRpContext():Promise<RpContext>;
 mRpList(scope:RpScope,q:{after?:number;through?:number;size?:number;attentionOnly?:boolean}):Promise<RpPage>;
 mRpOriginal(scope:RpScope,no:string):Promise<RpView>;
 mRpReconciliation(scope:RpScope,no:string):Promise<RpReconciliation>;
 mRpCommand(scope:RpScope,action:Exclude<RpAction,'REPLENISH_CONFIG'>,key:string):Promise<RpView|null>;
 mRpConfigurationCommand(scope:RpScope,key:string):Promise<RpConnection|null>;
 mRpConfigure(scope:RpScope,body:RpChange,key:string):Promise<RpConnection>;
 mRpRegister(scope:RpScope,body:RpRegister,key:string):Promise<RpView>;
 mRpManualRegister(scope:RpScope,body:RpRegister&{movementNo:string},key:string):Promise<RpView>;
 mRpReceive(scope:RpScope,no:string,body:{cumulativeQuantity:string},key:string):Promise<RpView>;
 mRpClose(scope:RpScope,no:string,body:{reason:string},key:string):Promise<RpView>;
 mRpReturnDispatch(scope:RpScope,no:string,body:{cumulativeQuantity:string;reason:string},key:string):Promise<RpView>;
 mRpReturnAck(scope:RpScope,no:string,body:{documentNo:string;lineNo:number},key:string):Promise<RpView>;
 mRpManualReceipt(scope:RpScope,no:string,body:{movementNo:string},key:string):Promise<RpView>;
}
