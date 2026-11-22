import { describe, expect, it } from 'vitest';
import { isActualPrintCompleted, isPdfDownloadable, operationFailureText, printErrorText, printJobActions, receiptPreviewLines, sceneTitle, simulationEvidence } from '../src/shared/print-center';
import type { PrintJob } from '@/api/print';
describe('print evidence and recovery controls', () => {
 it('never treats simulation or PDF readiness as physical completion', () => { for (const status of ['PASSED','READY','ACCEPTED','PRINTING','UNKNOWN']) expect(isActualPrintCompleted(status)).toBe(false); expect(isPdfDownloadable('READY')).toBe(true); expect(isActualPrintCompleted('COMPLETED')).toBe(true); });
 it('never retries uncertain output', () => { const actions=printJobActions({status:'UNKNOWN',allowedActions:['REPRINT','RETRY','QUERY','CONFIRM','CANCEL']} as PrintJob,()=>true); expect(actions).not.toContain('RETRY'); expect(actions).toContain('REPRINT'); });
 it('offers only what the server lists: no EXTRA for an invoice proof, nothing without a list', () => { expect(printJobActions({scene:'INVOICE_PROOF',status:'COMPLETED',allowedActions:['REPRINT']} as PrintJob,()=>true)).toEqual(['REPRINT']); expect(printJobActions({status:'COMPLETED'} as PrintJob,()=>true)).toEqual([]); });
 it('honors both permissions and server permitted actions', () => { expect(printJobActions({status:'COMPLETED',allowedActions:['REPRINT']} as PrintJob,()=>true)).toEqual(['REPRINT']); expect(printJobActions({status:'COMPLETED'} as PrintJob,()=>false)).toEqual([]); });
});

describe('compact receipt text preview',()=>{
 it('preserves right-side amounts, warning and separators',()=>{expect(receiptPreviewLines({mandatoryNotice:'支付小票',rows:[{text:'实付',right:'88.00'},{type:'rule'}]},['预览，非真实出票'])).toEqual(['支付小票','预览，非真实出票','实付  88.00','────────────────']);});
 it('does not duplicate a legacy title',()=>{expect(receiptPreviewLines({mandatoryNotice:'支付小票',rows:[{text:'支付小票'}]})).toEqual(['支付小票']);});
});


describe('simulation detail evidence',()=>{
 it('keeps expected, actual and journal evidence separately readable',()=>{
  const frozen=(value:unknown)=>({canonicalJson:JSON.stringify(value),sha256:'fixture'});
  expect(simulationEvidence({expectedResults:frozen({accepted:true}),actualResults:frozen({accepted:false}),eventJournal:frozen([{event:'TIMEOUT_UNKNOWN'}])})).toEqual({expected:'{\n  "accepted": true\n}',actual:'{\n  "accepted": false\n}',journal:'[\n  {\n    "event": "TIMEOUT_UNKNOWN"\n  }\n]'});
 });
});

describe('scene names and print failure keys',()=>{
 const dict:Record<string,string>={'printCenter.scene.INVOICE_PROOF':'电子发票证明联','printCenter.failure.extraCopiesNotAllowed':'不能加印','printCenter.failed':'操作失败'};
 const t=(k:string)=>dict[k]??k, te=(k:string)=>k in dict;
 it('localizes known scenes and falls back to the catalog title for new ones',()=>{
  expect(sceneTitle('INVOICE_PROOF',[{scene:'INVOICE_PROOF',title:'電子發票證明聯'}],t,te)).toBe('电子发票证明联');
  expect(sceneTitle('NEW_SCENE',[{scene:'NEW_SCENE',title:'新單據'}],t,te)).toBe('新單據');
  expect(sceneTitle('NEW_SCENE',[],t,undefined)).toBe('NEW_SCENE');
 });
 it('maps print failure keys and keeps the server message otherwise',()=>{
  expect(operationFailureText({status:'FAILED',error:{key:'print.extraCopiesNotAllowed'}},t,te)).toBe('不能加印');
  expect(operationFailureText({status:'FAILED',error:{key:'print.somethingNew'}},t,te)).toBe('操作失败');
  expect(operationFailureText({status:'SUCCEEDED',error:null},t,te)).toBeNull();
  expect(printErrorText(Object.assign(new Error('generic'),{data:{key:'print.extraCopiesNotAllowed'}}),t,te,'x')).toBe('不能加印');
  expect(printErrorText(new Error('generic'),t,te,'x')).toBe('generic');
 });
});
