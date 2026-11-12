import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { createPortal } from '../app.mjs';

const content = path.join(import.meta.dirname, '../content/支付');
test('published payment Markdown is well formed with resolvable relative links and valid JSON examples', async () => {
  for (const file of ['接入概览.md', 'README.md', '外部API接入指南.md']) {
    const text = await readFile(path.join(content, file), 'utf8');
    assert.equal((text.match(/^```/gm) || []).length % 2, 0, `${file}: unclosed code fence`);
    assert.equal((text.replace(/```[\s\S]*?```/g, '').match(/^# /gm) || []).length, 1, `${file}: one page heading`);
    for (const [, body] of text.matchAll(/```json\n([\s\S]*?)\n```/g)) JSON.parse(body);
    const prose = text.replace(/```[\s\S]*?```/g, '');
    for (const [, href] of prose.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
      if (/^(https?:|#)/.test(href)) continue;
      assert.ok((await stat(path.resolve(content, href.split('#')[0]))).isFile(), `${file}: broken link ${href}`);
    }
  }
});

test('payment guide renders with metadata and all public samples download intact', { timeout: 15000 }, async (t) => {
  const server = createPortal().listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const guide = await fetch(base + encodeURI('/支付/外部API接入指南'));
  assert.equal(guide.status, 200);
  const html = await guide.text();
  assert.match(html, /<title>Near Pay 外部 API 接入指南/);
  assert.match(html, /name="description" content="从应用与密钥申请/);
  assert.match(html, /10\. 生产接入验收清单/);
  assert.match(html, /<table>/);
  assert.match(html, /language-python/);
  assert.doesNotMatch(html, /<summary>Samples<\/summary>/);
  for (const filename of ['open_api.py', 'crypto.mjs', 'test-vectors.json']) {
    const response = await fetch(base + encodeURI('/支付/samples/' + filename));
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-disposition'), /attachment/);
    assert.equal(response.headers.get('cache-control'), 'no-cache');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), await readFile(path.join(content, 'samples', filename)));
  }
  assert.equal((await fetch(base + encodeURI('/支付/samples/other.json'))).status, 404);
});
