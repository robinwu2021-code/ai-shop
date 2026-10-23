import { describe, expect, it } from 'vitest';
import { isActualPrintCompleted, isPdfDownloadable, printJobActions, receiptPreviewLines } from '../src/shared/print-center';
import type { PrintJob } from '@/api/print';
describe('print evidence and recovery controls', () => {
 it('never treats simulation or PDF readiness as physical completion', () => { for (const status of ['PASSED','READY','ACCEPTED','PRINTING','UNKNOWN']) expect(isActualPrintCompleted(status)).toBe(false); expect(isPdfDownloadable('READY')).toBe(true); expect(isActualPrintCompleted('COMPLETED')).toBe(true); });
 it('never retries uncertain output', () => { const actions=printJobActions({status:'UNKNOWN'} as PrintJob,()=>true); expect(actions).not.toContain('RETRY'); expect(actions).toContain('REPRINT'); });
 it('honors both permissions and server permitted actions', () => { expect(printJobActions({status:'COMPLETED',allowedActions:['REPRINT']} as PrintJob,()=>true)).toEqual(['REPRINT']); expect(printJobActions({status:'COMPLETED'} as PrintJob,()=>false)).toEqual([]); });
});

describe('compact receipt text preview',()=>{
 it('preserves right-side amounts, warning and separators',()=>{expect(receiptPreviewLines({mandatoryNotice:'支付小票',rows:[{text:'实付',right:'88.00'},{type:'rule'}]},['预览，非真实出票'])).toEqual(['支付小票','预览，非真实出票','实付  88.00','────────────────']);});
 it('does not duplicate a legacy title',()=>{expect(receiptPreviewLines({mandatoryNotice:'支付小票',rows:[{text:'支付小票'}]})).toEqual(['支付小票']);});
});
