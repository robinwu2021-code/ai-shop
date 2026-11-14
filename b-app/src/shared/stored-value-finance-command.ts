import type {SvDecisionRequest,SvResolutionRequest,SvAdjustmentRequest,SvPaymentRequest,SvReceiptRequest,SvVoidRequest} from '@/api/stored-value-finance';
export type FinanceCommand=({kind:'reconcile'|'dispute';body:SvDecisionRequest}|{kind:'resolve';body:SvResolutionRequest}|{kind:'adjust';body:SvAdjustmentRequest}|{kind:'register';body:SvPaymentRequest}|{kind:'receipt';body:SvReceiptRequest}|{kind:'void';body:SvVoidRequest}) & {resourceNo:string;billNo:string;key:string;actorKey:string};
/** Cache the original operation identity, never the private bank receipt reference. */
export function encodeFinanceCommand(command:FinanceCommand):string {return JSON.stringify(command.kind==='register'?{...command,body:{...command.body,receiptReference:''}}:command)}
export function restoreFinanceCommand(raw:unknown,actor:string,management:string,bill:string):FinanceCommand|null {
 if(!raw)return null;try{const c=JSON.parse(String(raw)) as FinanceCommand;return c&&['reconcile','dispute','resolve','adjust','register','receipt','void'].includes(c.kind)&&c.actorKey===actor&&c.billNo===bill&&c.body?.managementNo===management&&typeof c.key==='string'&&!!c.key&&typeof c.resourceNo==='string'&&Number.isSafeInteger(c.body.expectedRevision)&&c.body.expectedRevision>=0?c:null}catch{return null}
}
export function financeStorageKey(actor:string,management:string,bill:string){return ['sv-finance-pending',actor,management,bill].map(encodeURIComponent).join(':')}
