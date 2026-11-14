import { test } from 'node:test';
import assert from 'node:assert/strict';
import { packLocales, renderPackedLocales } from './pack-biz-locales.mjs';
async function loadGenerated(messages) {
  return (await import('data:text/javascript;base64,'+Buffer.from(renderPackedLocales(messages)).toString('base64'))).default;
}
test('preserves nested keys, ordering differences, interpolation, quotes and RTL text',async()=>{
 const messages={en:{points:{name:'Platform points',hint:'{n} points',quote:'"quoted"\ntext'}},ar:{points:{quote:'سطر\nثان',hint:'{n} نقطة',name:'نقاط المنصة'}}};
 assert.deepEqual(await loadGenerated(messages),messages);
});
test('rejects dictionary mismatches instead of silently dropping a language key',()=>{
 assert.throws(()=>packLocales({en:{a:'a'},ar:{b:'ب'}}),/keys differ/);
});
test('round-trips every actual business message in all three languages and saves source size',async()=>{
 const messages={};
 for(const locale of ['zh-CN','en','ar'])messages[locale]=(await import(new URL('../../b-app/src/i18n/locale/'+locale+'.ts',import.meta.url))).default;
 assert.deepEqual(await loadGenerated(messages),messages);
 const original=Buffer.byteLength(Object.values(messages).map(m=>JSON.stringify(m)).join(''));
 const packed=Buffer.byteLength(renderPackedLocales(messages));
 assert.ok(original-packed>30*1024,`Expected meaningful size reduction; saved ${original-packed} bytes`);
 console.log(`Business locale source savings: ${original-packed} bytes; every message preserved`);
});
