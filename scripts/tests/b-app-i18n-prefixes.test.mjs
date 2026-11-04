import test from 'node:test';
import assert from 'node:assert/strict';
import { concatenatedTranslationPrefixes as prefixes } from '../lib/i18n-dynamic-prefixes.mjs';
test('concatenated translation keys retain only their qualified namespace', () => {
 assert.deepEqual(prefixes(`t('printCenter.status.'+state); $t("stock.reason." + code); i18n.global.t('status.' + value)`), ['printCenter.status.', 'stock.reason.', 'status.']);
});
test('generic ID concatenation and unqualified prefixes cannot hide orphan translations', () => {
 assert.deepEqual(prefixes(`const id = 'c.' + code; t('c' + code); other('goods.' + code)`), []);
});
