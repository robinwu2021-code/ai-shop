import { describe, expect, it } from 'vitest';
import { isActualPrintCompleted, isPdfDownloadable, printJobActions } from '../print-center';
import type { PrintJob } from '@/api/print';
describe('print evidence and recovery controls', () => {
 it('never treats simulation or PDF readiness as physical completion', () => { for (const status of ['PASSED','READY','ACCEPTED','PRINTING','UNKNOWN']) expect(isActualPrintCompleted(status)).toBe(false); expect(isPdfDownloadable('READY')).toBe(true); expect(isActualPrintCompleted('COMPLETED')).toBe(true); });
 it('never retries uncertain output', () => { const actions=printJobActions({status:'UNKNOWN'} as PrintJob,()=>true); expect(actions).not.toContain('RETRY'); expect(actions).toContain('REPRINT'); });
 it('honors both permissions and server permitted actions', () => { expect(printJobActions({status:'COMPLETED',allowedActions:['REPRINT']} as PrintJob,()=>true)).toEqual(['REPRINT']); expect(printJobActions({status:'COMPLETED'} as PrintJob,()=>false)).toEqual([]); });
});
