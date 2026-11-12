import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPortal } from '../app.mjs';

test('rate limiting separates clients forwarded by the configured trusted proxy', async t => {
  const server = createPortal({ trustProxy:['loopback','192.168.3.201/32'] }).listen(0,'127.0.0.1');
  await new Promise(resolve => server.once('listening',resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const url = `http://127.0.0.1:${server.address().port}/`;
  const remaining = async ip => {
    const response = await fetch(url,{headers:{'X-Forwarded-For':`${ip}, 192.168.3.201`}});
    await response.text();
    assert.equal(response.status,200);
    return Number(response.headers.get('ratelimit-remaining'));
  };
  assert.equal(await remaining('203.0.113.1'),199);
  assert.equal(await remaining('203.0.113.1'),198);
  assert.equal(await remaining('203.0.113.2'),199);
});
