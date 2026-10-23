import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateFiles,validateUpdate} from './brand-p2.mjs';
const head='a'.repeat(40),base='b'.repeat(40);
test('limits the release to an existing canonical fast-forward target at HEAD',()=>{
 assert.equal(validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${base}`,head),base);
 for(const update of [`HEAD ${head} refs/heads/main ${base}`,`HEAD ${base} refs/heads/codex/ai-shop-codex ${base}`,`HEAD ${head} refs/heads/codex/ai-shop-codex ${'0'.repeat(40)}`,''])assert.throws(()=>validateUpdate(update,head));
});
test('refuses any unrelated file and an empty release',()=>{
 validateFiles(['b-app/src/api/member-scopes.ts']);
 assert.throws(()=>validateFiles(['backend/shop-core/unrelated.java']));
 assert.throws(()=>validateFiles([]));
});
