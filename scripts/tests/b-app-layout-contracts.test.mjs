import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inspectLayout } from '../lib/b-app-layout-contracts.mjs';
const check = (template, css = '', contracts = {}) => inspectLayout({ source: `<template>${template}</template><style>${css}</style>`, file: 'page.vue', contracts });
const rules = r => r.violations.map(v => v.rule);
test('editable key-value rows are rejected even inside a conditional template', () => {
 assert.ok(rules(check('<template v-if="open"><sh-kv label="Name"><input class="field__input" /></sh-kv></template>')).includes('editable-kv'));
});
test('unknown component props fail; framework attributes and events remain valid', () => {
 const contracts = { 'sh-uploader': ['list','width','height'] };
 assert.ok(rules(check('<sh-uploader :w="160" :list="images" />', '', contracts)).includes('unknown-component-prop'));
 assert.deepEqual(rules(check('<sh-uploader :width="160" :list="images" :class="classes" @add="pick" />', '', contracts)), []);
});
test('full inputs without separation reproduce joined input backgrounds', () => {
 assert.ok(rules(check('<view><input class="field__input" /><input class="field__input" /></view>')).includes('unspaced-controls'));
});
test('two independently visible actions require a group gap', () => {
 assert.ok(rules(check('<view><button v-if="canEdit" class="sh-btn" /><button v-if="canPublish" class="sh-btn" /></view>')).includes('unspaced-controls'));
});
test('a compound row and explicitly separated local controls remain valid', () => {
 assert.deepEqual(rules(check('<view class="sh-row"><input class="field__input" /><button class="sh-btn" /></view>', '.sh-row { display:flex;gap:16rpx }')), []);
 assert.deepEqual(rules(check('<view><input class="field__input" /><input class="field__input separated" /></view>', '.separated { margin-top:20rpx }')), []);
});
test('mutually exclusive branches do not need spacing between invisible alternatives', () => {
 assert.deepEqual(rules(check('<view><button v-if="ready" class="sh-btn" /><button v-else class="sh-btn" /></view>')), []);
});
test('stack owns gaps and rejects child margins instead of adding them twice', () => {
 const css = '.sh-stack { display:flex;flex-direction:column;gap:20rpx }.offset {margin-top:16rpx}';
 assert.ok(rules(check('<view class="sh-stack"><view class="offset">content</view></view>', css)).includes('mixed-spacing-owners'));
 assert.deepEqual(rules(check('<view class="sh-stack"><input class="field__input" /><button class="sh-btn" /></view>', css)), []);
});
test('comments cannot supply a layout rule; nested template action groups are inspected', () => {
 const r = check('<view><template v-if="open"><button class="sh-btn" /><button class="sh-btn" /></template></view>', '/* .buttons { gap:16rpx } */');
 assert.ok(rules(r).includes('unspaced-controls'));
});
test('card title without explicit separation is a review candidate, not an inferred visual failure', () => {
 const r = check('<view class="sh-card"><text class="txt-title">Title</text><view>Content</view></view>');
 assert.ok(r.candidates.some(c => c.rule === 'card-heading-spacing'));
 assert.deepEqual(r.violations, []);
});
test('transparent templates inherit row layout and dialog action slots use the component-owned gap', () => {
 assert.deepEqual(rules(check('<view class="sh-row"><template v-if="open"><button class="sh-btn" /><button class="sh-btn" /></template></view>', '.sh-row { display:flex;gap:16rpx }')), []);
 assert.deepEqual(rules(check('<sh-dialog><template #actions><view class="sh-btn sh-dialog__act"/><view class="sh-btn sh-dialog__act"/></template></sh-dialog>')), []);
});
test('a read-only date picker in a key-value row is not a full input field', () => {
 assert.deepEqual(rules(check('<sh-kv label="Date"><picker mode="date"><view>2026-10-26</view></picker></sh-kv>')), []);
});
test('stack field reset wins over later app field margins by specificity', () => {
 assert.deepEqual(rules(check('<view class="sh-stack"><view class="field"><input/></view></view>', '.sh-stack {gap:20rpx}.sh-stack > .field {margin-top:0}.field {margin-top:20rpx}')), []);
});
test('the shipped shared stack and action classes provide real spacing declarations', async () => {
 const { readFileSync } = await import('node:fs');
 const css = readFileSync(new URL('../../packages/ui/src/styles/base.css', import.meta.url), 'utf8');
 assert.deepEqual(rules(check('<view class="sh-stack"><input class="field__input"/><button class="sh-btn"/></view>', css)), []);
 assert.deepEqual(rules(check('<view class="sh-actions"><button class="sh-btn"/><button class="sh-btn"/></view>', css)), []);
});
test('logical block margins provide vertical separation without requiring physical duplicates', () => {
 const r = check('<view class="sh-card"><text class="txt-strong">Title</text><text class="meta">Description</text></view>', '.meta {margin-block:16rpx}');
 assert.deepEqual(r.candidates, []);
});
test('gap without flex/grid is not a working control layout', () => {
 assert.ok(rules(check('<view class="buttons"><button class="sh-btn"/><button class="sh-btn"/></view>', '.buttons {gap:16rpx}')).includes('unspaced-controls'));
});
test('transparent branches and negative child margins cannot defeat stack spacing', () => {
 const css = '.sh-stack {display:flex;flex-direction:column;gap:20rpx}.offset {margin-top:-8rpx}';
 assert.ok(rules(check('<view class="sh-stack"><template v-if="open"><view class="offset">content</view></template></view>', css)).includes('mixed-spacing-owners'));
});
test('rendered boundaries across optional templates and repeated controls require spacing', () => {
 assert.ok(rules(check('<view><input/><template v-if="open"><input/></template></view>')).includes('unspaced-controls'));
 assert.ok(rules(check('<view><input v-for="item in items" :key="item.id"/></view>')).includes('unspaced-controls'));
 assert.ok(rules(check('<view><input/><template v-if="open"><text>label</text></template><input/></view>')).includes('unspaced-controls'));
 assert.deepEqual(rules(check('<view><template v-if="open"><input/></template><template v-else><input/></template></view>')), []);
 assert.deepEqual(rules(check('<view class="sh-stack"><input v-for="item in items" :key="item.id"/></view>', '.sh-stack {display:flex;flex-direction:column;gap:20rpx}')), []);
});
test('the declared flex axis determines which gap separates controls', () => {
 const controls = '<view class="layout"><input/><input/></view>';
 assert.ok(rules(check(controls, '.layout {display:flex;flex-direction:column;column-gap:20rpx}')).includes('unspaced-controls'));
 assert.ok(rules(check(controls, '.layout {display:flex;row-gap:20rpx}')).includes('unspaced-controls'));
 assert.ok(rules(check(controls, '.layout {display:flex;flex-direction:column;gap:0 16rpx}')).includes('unspaced-controls'));
 assert.deepEqual(rules(check(controls, '.layout {display:flex;gap:0 16rpx}')), []);
 assert.deepEqual(rules(check(controls, '.layout {display:flex;flex-direction:column;row-gap:20rpx}')), []);
});
test('inline shorthands use the same spacing and ownership rules as stylesheets', () => {
 assert.deepEqual(rules(check('<view><input/><input style="margin:20rpx 0"/></view>')), []);
 assert.deepEqual(rules(check('<view><input/><view style="padding-block:20rpx"><input/></view></view>')), []);
 assert.ok(rules(check('<view class="sh-stack"><view style="margin:20rpx 0">content</view></view>', '.sh-stack {display:flex;flex-direction:column;gap:20rpx}')).includes('mixed-spacing-owners'));
});
