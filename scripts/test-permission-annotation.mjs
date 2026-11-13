import test from 'node:test';
import assert from 'node:assert/strict';
import { permissionAlternatives, scanEndpoints, buildMatrix } from './gen-perm-endpoint-matrix.mjs';

test('unified admission preserves all three independent permission alternatives', () => {
  const endpoint=scanEndpoints().find(e=>e.path==='/ops/merchants/admissions');
  assert.deepEqual(permissionAlternatives(endpoint.perm),['MERCHANT_APPLY_AUDIT','MERCHANT_APPLY_ONBEHALF','MERCHANT_SELFOP_CREATE']);
});

test('multiline conditional permission retains both action branches', () => {
  const endpoint = scanEndpoints().find(e => e.path === '/ops/points/statement-change');
  assert.deepEqual(permissionAlternatives(endpoint.perm), ['FINANCE_PAYOUT_EXECUTE', 'FINANCE_SETTLE_EXECUTE']);
  const { matrix } = buildMatrix();
  assert.ok(matrix.FINANCE.includes('POST /ops/points/statement-change'));
  for (const role of ['BD', 'CUSTOMER_SERVICE']) {
    if (matrix[role]) assert.ok(!matrix[role].includes('POST /ops/points/statement-change'));
  }
});

test('single permission and unguarded remain distinct', () => {
  assert.deepEqual(permissionAlternatives('FINANCE_SETTLE_READ'), ['FINANCE_SETTLE_READ']);
  assert.deepEqual(permissionAlternatives(null), []);
  const endpoints = scanEndpoints();
  assert.equal(endpoints.find(e => e.path === '/ops/captcha').perm, null);
});
