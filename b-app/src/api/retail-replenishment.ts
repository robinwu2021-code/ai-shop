/** RETAIL physical replenishment into the original PLATFORM pool; separate from FOOD receipts and online sale quotas. */
export interface RrScope {entityNo:string;storeNo:string;actorNo:string}
export interface RrConnection {enabled:boolean;generation:number;revision:number;sourceSystem:string|null;sourceOwner:string|null;postingWatermark:number}
export interface RrContext extends RrScope {connection:RrConnection}
export interface RrTarget {entityNo:string;storeNo:string|null;skuNo:string;market:string|null;baseUnit:string;quantityScope:'ENTITY'|'STORE';poolRef:string;identity:string}
export interface RrSource {sourceSystem:string;sourceOwner:string;documentNo:string;lineNo:number;itemNo:string;baseUnit:string;quantity:number;targetStoreNo:string;postingSequence:number}
export interface RrMapping {itemNo:string;baseUnit:string;factor:string}
export interface RrOriginal {originalNo:string;targetOwner:'PLATFORM';source:RrSource;mapping:{itemNo:string;baseUnit:string;factor:number|string};target:RrTarget;expectedQuantity:number;receivedQuantity:number;status:'PENDING'|'PARTIAL'|'RECEIVED'|'CLOSED'}
export interface RrView {original:RrOriginal;differenceQuantity:number;returnedQuantity:number;returnAcceptedQuantity:number;closeReason:string|null}
export interface RrPage {items:RrView[];nextCursor:string|null}
export interface RrReconciliation {original:RrView;receivedLedgerQuantity:number;manualQuantity:number;returnedLedgerQuantity:number;acceptedReturnQuantity:number;issues:string[]}
export interface RrAdjustment {movementNo:string;target:RrTarget;delta:number;onHandBefore:number;onHandAfter:number;reservedQuantity:number}
export interface RrChange {enabled:boolean;expectedVersion:number;reason:string}
export interface RrRegister {documentNo:string;lineNo:number;mapping:RrMapping}
export interface RrAdjust {itemNo:string;baseUnit:string;delta:number;reason:string}
export type RrCommand=({kind:'configure';body:RrChange}|{kind:'register';body:RrRegister}|{kind:'adjust';body:RrAdjust}|{kind:'receive';no:string;body:{cumulativeQuantity:number}}|{kind:'close';no:string;body:{reason:string}}|{kind:'return-dispatch';no:string;body:{cumulativeQuantity:number;reason:string}}|{kind:'return-ack';no:string;body:{documentNo:string;lineNo:number}}|{kind:'manual-receipt';no:string;body:{movementNo:string}})&{scope:RrScope;key:string;attempted?:boolean};
export interface RetailReplenishmentApi {
 mRrContext():Promise<RrContext>;
 mRrList(scope:RrScope,q:{after?:string;size?:number;attentionOnly?:boolean}):Promise<RrPage>;
 mRrOriginal(scope:RrScope,no:string):Promise<RrView>;
 mRrReconciliation(scope:RrScope,no:string):Promise<RrReconciliation>;
 mRrConfigure(scope:RrScope,body:RrChange,key:string):Promise<RrConnection>;
 mRrRegister(scope:RrScope,body:RrRegister,key:string):Promise<RrView>;
 mRrAdjust(scope:RrScope,body:RrAdjust,key:string):Promise<RrAdjustment>;
 mRrReceive(scope:RrScope,no:string,body:{cumulativeQuantity:number},key:string):Promise<RrView>;
 mRrClose(scope:RrScope,no:string,body:{reason:string},key:string):Promise<RrView>;
 mRrReturnDispatch(scope:RrScope,no:string,body:{cumulativeQuantity:number;reason:string},key:string):Promise<RrView>;
 mRrReturnAck(scope:RrScope,no:string,body:{documentNo:string;lineNo:number},key:string):Promise<RrView>;
 mRrManualReceipt(scope:RrScope,no:string,body:{movementNo:string},key:string):Promise<RrView>;
}
