import { test } from 'node:test';
import assert from 'node:assert/strict';
import { uiPolicyViolations } from '../lib/b-app-ui-policy.mjs';

test('missing translations, placeholder drift and absent referenced keys fail the check', () => {
  const errors = uiPolicyViolations({ parity: [{ lang: 'ar', missing: ['common.delete'], extra: [], placeholderMismatch: ['count'] }], missingStaticKeys: [{ file: 'page.vue', line: 1, key: 'absent.key' }] });
  assert.equal(errors.length, 3);
});
test('a documented log exception never exempts other UI text in the same file', () => {
  const log = { file: 'b-app/src/App.vue', kind: 'script-string', text: '日志' };
  const errors = uiPolicyViolations({ literalCandidates: [log, { ...log, text: '新用户提示' }] }, { literals: [log], layouts: [] });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /新用户提示/);
});
test('an allowed overlay does not exempt a new fixed toolbar or a changed shared input size', () => {
  const overlay = { rule: 'custom-fixed', file: 'b-app/src/pages/stock/index.vue', selector: '.catch', property: 'position', value: 'fixed' };
  const errors = uiPolicyViolations({ layoutCandidates: [overlay, { ...overlay, selector: '.new-bar' }, { rule: 'shared-class-override', file: 'b-app/src/pages/form.vue', selector: '.field__input', property: 'height', value: '76rpx' }] }, { literals: [], layouts: [overlay] });
  assert.equal(errors.length, 2);
});
test('undefined class names and unstyled native controls are reported', () => {
  assert.equal(uiPolicyViolations({ unresolvedClasses: [{ file: 'p.vue', className: 'sh-between' }], unstyledControls: [{ file: 'p.vue', tag: 'input', line: 1 }] }).length, 2);
});
test('valid pages and the shared library fixed infrastructure pass', () => {
  assert.deepEqual(uiPolicyViolations({ layoutCandidates: [{ file: 'packages/ui/src/components/sh-sheet.vue', rule: 'custom-fixed' }] }), []);
});
test('layout contract violations fail the same UI gate used by the app test command', () => {
 const errors = uiPolicyViolations({ layoutContractViolations: [{ file: 'b-app/src/pages/example/index.vue', line: 8, rule: 'unspaced-controls', detail: 'Missing group gap' }] });
 assert.equal(errors.length, 1);
 assert.match(errors[0], /example\/index.vue:8: unspaced-controls/);
});
