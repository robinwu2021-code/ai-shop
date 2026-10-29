import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {startFaultProxy} from '../lib/beauty-bm03-proxy.mjs';

test('drops a committed response, preserves request bytes and resumes the same command', async () => {
 let effects=0; const seen=[];
 const owner=http.createServer(async(req,res)=>{let body='';for await(const b of req)body+=b;
  seen.push(body);effects++;res.setHeader('content-type','application/json');res.end('{"status":"SUCCEEDED"}');});
 await new Promise(r=>owner.listen(0,'127.0.0.1',r));
 const proxy=await startFaultProxy('http://127.0.0.1:'+owner.address().port);
 try {
  proxy.dropOnce(e=>e.path==='/execute');
  const body='{"commandNo":"original"}';
  await assert.rejects(fetch(proxy.url+'/execute',{method:'POST',body}));
  assert.equal(effects,1);assert.equal(proxy.events[0].dropped,true);
  assert.equal(proxy.events[0].response.status,'SUCCEEDED');
  assert.equal((await fetch(proxy.url+'/execute',{method:'POST',body})).status,200);
  assert.deepEqual(seen,[body,body]);assert.equal(proxy.events[1].dropped,false);
 } finally {await proxy.close();await new Promise(r=>owner.close(r));}
});
