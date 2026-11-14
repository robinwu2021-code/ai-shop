import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateFiles,validateUpdate} from './brand-p4.mjs';
const head='a'.repeat(40),base='b'.repeat(40);
test('release targets exact HEAD on the existing canonical branch',()=>{
 assert.equal(validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${base}`,head),base);
 for(const row of ['',`HEAD ${head} refs/heads/main ${base}`,`HEAD ${base} refs/heads/codex/ai-shop-codex ${base}`,`HEAD ${head} refs/heads/codex/ai-shop-codex ${'0'.repeat(40)}`])assert.throws(()=>validateUpdate(row,head));
});
test('release permits only the explicit activity governance file list',()=>{
 validateFiles(['backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/storedvalue/issuer/StoredValueIssuerLedgerBridge.java']);
 assert.throws(()=>validateFiles([]));assert.throws(()=>validateFiles(['backend/shop-core/unrelated.java']));
});
