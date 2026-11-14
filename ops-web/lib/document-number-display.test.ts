import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const page = (path: string) => readFileSync(resolve(import.meta.dirname, '../app', path), 'utf8');
it('uses short marketing identities in drawers but sends the original coupon key', () => {
  const marketing = page('marketing/page.tsx');
  expect(marketing).toContain('desc={budgetEdit.documentNo');
  expect(marketing).toContain('couponNo: budgetEdit.couponNo');
  expect(marketing).toContain('rowKey={(c) => c.couponNo}');
  expect(marketing).toContain('cell: (x) => x.documentNo');
});
it('shows short voucher numbers while detail requests keep the internal voucher key', () => {
  const transactions = page('points/transactions/page.tsx');
  expect(transactions).toContain('r.voucher.documentNo');
  expect(transactions).toContain('api.pointsTransaction(typeCode, selected!.voucher.voucherNo)');
  expect(transactions).not.toContain('{r.voucher.voucherNo}</p>');
  const finance = page('finance/settle-batch-tab.tsx');
  expect(finance).toContain('cell: (b) => b.documentNo');
  expect(finance).toContain('release.mutate(b.batchNo)');
});

it('exports and displays mapped settlement collections while keeping source IDs behind audit details', () => {
  const invoices = page('finance/purchase-invoice-tab.tsx');
  expect(invoices).toContain('r.settleDocumentNos.join');
  expect(invoices).toContain('<details');
  expect(invoices).toContain('value={r.settleNos.join');
  const payables = page('finance/payables-tab.tsx');
  expect(payables).toContain('value: (r) => (r.settleDocumentNos ?? []).join');
  expect(payables).not.toContain('value: (r) => r.settleNos.join');
  expect(page('marketing/page.tsx')).toContain('cell: (x) => x.documentNo ?? "—"');
});
