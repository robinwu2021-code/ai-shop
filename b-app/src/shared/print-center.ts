import type { PrintJob, Simulation } from '@/api/print';
export type JobAction='REPRINT'|'EXTRA'|'RETRY'|'CANCEL'|'QUERY'|'CONFIRM';
export function printJobActions(job:PrintJob,can:(permission:string)=>boolean):JobAction[]{const actions:JobAction[]=[];const allowed=job.allowedActions?new Set(job.allowedActions):null;const add=(action:JobAction,condition:boolean)=>{if(condition&&(!allowed||allowed.has(action)))actions.push(action);};add('REPRINT',['COMPLETED','FAILED','UNKNOWN'].includes(job.status)&&can('biz:print:reprint'));add('EXTRA',job.status==='COMPLETED'&&can('biz:print:extra'));add('RETRY',job.status==='RETRY_WAIT'&&can('biz:print:resolve'));add('CANCEL',['PENDING','DISPATCHING','ACCEPTED','PRINTING','RETRY_WAIT','UNKNOWN','STALE'].includes(job.status)&&can('biz:print:submit'));add('QUERY',['DISPATCHING','ACCEPTED','PRINTING','UNKNOWN'].includes(job.status)&&can('biz:print:resolve'));add('CONFIRM',['DISPATCHING','ACCEPTED','PRINTING','UNKNOWN'].includes(job.status)&&can('biz:print:resolve'));return actions;}
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
