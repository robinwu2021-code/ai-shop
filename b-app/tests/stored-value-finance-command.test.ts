import {describe,it,expect} from 'vitest';
import {encodeFinanceCommand,restoreFinanceCommand,financeStorageKey} from '../src/shared/stored-value-finance-command';
import type {FinanceCommand} from '../src/shared/stored-value-finance-command';
const command:FinanceCommand={kind:'register',resourceNo:'B1',billNo:'B1',key:'ORIGINAL',actorKey:'MCH:A',body:{managementNo:'M1',expectedRevision:3,amountMinor:100,paidAt:'2026-10-01T00:00:00Z',method:'TRANSFER',receiptReference:'PRIVATE-BANK-REFERENCE',evidenceRefs:[]}};
describe('financial operation recovery',()=>{
 it('persists the exact key and revision without private receipt reference',()=>{const raw=encodeFinanceCommand(command);expect(raw).not.toContain('PRIVATE-BANK-REFERENCE');expect(restoreFinanceCommand(raw,'MCH:A','M1','B1')).toMatchObject({key:'ORIGINAL',body:{expectedRevision:3,amountMinor:100,receiptReference:''}})});
 it('does not restore another operator, management space or bill',()=>{const raw=encodeFinanceCommand(command);expect(restoreFinanceCommand(raw,'MCH:B','M1','B1')).toBeNull();expect(restoreFinanceCommand(raw,'MCH:A','M2','B1')).toBeNull();expect(restoreFinanceCommand(raw,'MCH:A','M1','B2')).toBeNull()});
 it('isolates storage identities and rejects malformed commands',()=>{expect(financeStorageKey('MCH:A','M1','B1')).not.toBe(financeStorageKey('MCH:B','M1','B1'));expect(restoreFinanceCommand('{','MCH:A','M1','B1')).toBeNull()});
});
