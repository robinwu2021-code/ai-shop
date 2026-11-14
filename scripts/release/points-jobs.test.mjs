import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateFiles,validateUpdate} from './points-jobs.mjs';
const head='a'.repeat(40),base='b'.repeat(40);
test('only exact current commit can fast-forward the canonical branch',()=>{
 assert.equal(validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${base}`,head),base);
 for(const row of ['',`HEAD ${head} refs/heads/main ${base}`,`HEAD ${base} refs/heads/codex/ai-shop-codex ${base}`,`HEAD ${head} refs/heads/codex/ai-shop-codex ${'0'.repeat(40)}`])assert.throws(()=>validateUpdate(row,head));
});
test('scope includes points jobs and refuses unrelated or empty changes',()=>{
 validateFiles(['backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsScopedJobHandler.java']);
 for(const files of [[],['b-app/src/pages/points/index.vue'],['c-app/src/pages/points/index.vue'],['backend/shop-core/unrelated.java'],['ops-web/app/points/accounts/page.tsx']])assert.throws(()=>validateFiles(files));
});
