import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root=new URL('../src/main/resources/',import.meta.url);
const script='V16__beauty_task_workflow.sql';
const bytes=readFileSync(new URL('db/industry/beauty/'+script,root));
let crc=0xffffffff;
for(const byte of Buffer.from(bytes.toString('utf8').split(/\r?\n/).join(''))) {
 crc^=byte;
 for(let bit=0;bit<8;bit++) crc=(crc>>>1)^((crc&1)?0xedb88320:0);
}
const descriptor={version:16,script,sha256:createHash('sha256').update(bytes).digest('hex'),checksum:(crc^0xffffffff)|0};
const target=new URL('META-INF/shop/beauty-task-workflow-schema.json',root);
const output=JSON.stringify(descriptor,null,2)+'\n';
if(process.argv.includes('--check')) assert.equal(readFileSync(target,'utf8'),output);
else writeFileSync(target,output);
console.log('Beauty workflow V16 descriptor matches migration');
