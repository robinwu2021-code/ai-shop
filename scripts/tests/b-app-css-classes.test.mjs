import { test } from 'node:test';
import assert from 'node:assert/strict';
import { declaredCssClasses } from '../lib/b-app-css-classes.mjs';
test('obsolete classes mentioned in comments do not satisfy the undefined-class guard', () => {
 const classes = declaredCssClasses('/* Replaced .field__hint with .sh-hint */ .sh-hint { color: var(--sh-sub) }');
 assert.equal(classes.has('sh-hint'), true);
 assert.equal(classes.has('field__hint'), false);
});
test('classes inside media rules count, declaration string content does not', () => {
 const classes = declaredCssClasses('@media (min-width: 600px) { .sh-card .is-on:hover { content: ".not-a-selector" } }');
 assert.deepEqual([...classes].sort(), ['is-on', 'sh-card']);
});
