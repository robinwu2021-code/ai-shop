import type { PrintJob, PrintOperation, Scene, Simulation } from '@/api/print';
export type JobAction='REPRINT'|'EXTRA'|'RETRY'|'CANCEL'|'QUERY'|'CONFIRM';
/** The server's allowedActions is authoritative (e.g. INVOICE_PROOF never lists EXTRA); local checks only narrow it. */
export function printJobActions(job:PrintJob,can:(permission:string)=>boolean):JobAction[]{const actions:JobAction[]=[];const allowed=new Set(job.allowedActions??[]);const add=(action:JobAction,condition:boolean)=>{if(condition&&allowed.has(action))actions.push(action);};add('REPRINT',['COMPLETED','FAILED','UNKNOWN'].includes(job.status)&&can('biz:print:reprint'));add('EXTRA',job.status==='COMPLETED'&&can('biz:print:extra'));add('RETRY',job.status==='RETRY_WAIT'&&can('biz:print:resolve'));add('CANCEL',['PENDING','DISPATCHING','ACCEPTED','PRINTING','RETRY_WAIT','UNKNOWN','STALE'].includes(job.status)&&can('biz:print:submit'));add('QUERY',['DISPATCHING','ACCEPTED','PRINTING','UNKNOWN'].includes(job.status)&&can('biz:print:resolve'));add('CONFIRM',['DISPATCHING','ACCEPTED','PRINTING','UNKNOWN'].includes(job.status)&&can('biz:print:resolve'));return actions;}
export function isActualPrintCompleted(status:string):boolean{return status==='COMPLETED';}
export function isPdfDownloadable(status:string):boolean{return status==='READY';}
export function simulationOutcomeLabel(status:string):string{return status==='PASSED'?'printCenter.simulationPassed':status==='FAILED'?'printCenter.simulationFailed':'printCenter.status.'+status;}

export interface ReceiptPreviewData { mandatoryNotice?: string; rows: { text?: string; right?: string; type?: string }[] }
/** Text preview retains both cells; production geometry remains the PDF renderer's responsibility. */
export function receiptPreviewLines(data:ReceiptPreviewData,warnings:readonly string[]=[]):string[]{
 const rows=data.rows.map(row=>row.type==='rule'?'────────────────': [row.text,row.right].filter(Boolean).join('  '));
 const notice=data.mandatoryNotice;
 return [...(notice&&rows[0]!==notice?[notice]:[]),...warnings,...rows];
}

/** Simulation evidence is diagnostic output, never physical-print confirmation. */
export function simulationEvidence(value:Pick<Simulation,'expectedResults'|'actualResults'|'eventJournal'>):{expected:string;actual:string;journal:string}{
 const format=(f:{canonicalJson:string})=>JSON.stringify(JSON.parse(f.canonicalJson),null,2);
 return {expected:format(value.expectedResults),actual:format(value.actualResults),journal:format(value.eventJournal)};
}

type Translate=(key:string)=>unknown;
type HasTranslation=((key:string)=>boolean)|undefined;
/** Scene names are localized by code; the catalog title (Traditional Chinese print heading) is only a fallback for new codes. */
export function sceneTitle(scene:string,scenes:readonly Pick<Scene,'scene'|'title'>[],t:Translate,te:HasTranslation):string{
 if(te?.('printCenter.scene.'+scene))return String(t('printCenter.scene.'+scene));
 return scenes.find(s=>s.scene===scene)?.title||scene;
}
/** Print failures carry a `print.xxx` key (PrintOperation.error, or the error payload when the transport keeps it). */
export function printFailureKey(value:unknown):string|null{
 const key=(value as {error?:{key?:unknown}|null;data?:{key?:unknown}|null}|null)?.error?.key??(value as {data?:{key?:unknown}|null}|null)?.data?.key;
 return typeof key==='string'&&key.startsWith('print.')?key:null;
}
export function printFailureText(key:string|null,t:Translate,te:HasTranslation):string|null{
 if(!key)return null;
 const name=key.slice('print.'.length);
 return te?.('printCenter.failure.'+name)?String(t('printCenter.failure.'+name)):null;
}
/** Text for a failed print command: mapped failure key first, otherwise the server's localized message. */
export function printErrorText(value:unknown,t:Translate,te:HasTranslation,fallback:string):string{
 return printFailureText(printFailureKey(value),t,te)??(value instanceof Error&&value.message?value.message:fallback);
}
export function operationFailureText(op:Pick<PrintOperation,'status'|'error'>,t:Translate,te:HasTranslation):string|null{
 if(op.status!=='FAILED')return null;
 return printFailureText(printFailureKey(op),t,te)??String(t('printCenter.failed'));
}
