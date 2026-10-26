import { test } from 'node:test';
import assert from 'node:assert/strict';
import { translationCalls } from '../lib/b-app-i18n-refs.mjs';
test('tt wrappers are checked along with direct calls; comments are ignored', () => {
  const calls = translationCalls(`// tt('comment.key')\nt('common.save'); tt("missing.key"); $t('common.done');`);
  assert.deepEqual(calls.map(x => x.key), ['common.save', 'missing.key', 'common.done']);
  assert.equal(calls[1].line, 1);
});
test('dynamic prefixes and interpolated wrappers are not mistaken for complete keys', () => {
  const calls = translationCalls("t('status.' + state); tt(`kind.${kind}`)");
  assert.deepEqual(calls.map(x => x.key), [null, null]);
  assert.equal(calls[0].expression, "'status.' + state");
});
