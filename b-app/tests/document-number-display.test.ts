import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const page = (path: string) => readFileSync(resolve(import.meta.dirname, '../src', path), 'utf8');
describe('human document number projections', () => {
  it('shows nullable short numbers in stored value and points without changing lookup identities', () => {
    const detail = page('pages/stored-value-record/index.vue');
    expect(detail).toContain('record.documentNo');
    expect(detail).not.toContain('{{record.businessNo}}');
    const legacy = page('pages/stored-value-recharge/LegacyRecharge.vue');
    expect(legacy).toContain('progress.documentNo');
    expect(legacy).not.toContain('{{progress.rechargeNo}}');
    expect(legacy).toContain('api.mSvRecharge(no)');
    expect(page('pages/points-records/index.vue')).toContain('entry.voucherDocumentNo');
  });
  it('exports the settlement document number while retaining external voucher evidence', () => {
    const statement = page('pages/statement/index.vue');
    expect(statement).toContain('l.documentNo ?? ""');
    expect(statement).not.toContain('l.settleNo, l.orderNo,');
    expect(statement).toContain('l.voucherNo ?? ""');
    expect(page('pages/coupons/index.vue')).toContain('couponNo=${c.couponNo}');
  });
});
