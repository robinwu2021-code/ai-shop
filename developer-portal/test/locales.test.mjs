import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createPortal } from '../app.mjs';
import { pagePairs } from '../locales.mjs';

const decode = html => html.replaceAll('&#x2F;', '/');
test('Chinese and English pages expose matching navigation and language SEO', { timeout: 15000 }, async t => {
  const server = createPortal({ siteUrl:'https://docs.example.com' }).listen(0,'127.0.0.1');
  await new Promise(resolve => server.once('listening',resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  for (const [zh,en] of pagePairs) {
    const response = await fetch(base + encodeURI(en));
    assert.equal(response.status,200,en);
    const html = decode(await response.text());
    assert.match(html, /<html lang="en">/);
    assert.ok(html.includes(`href="${zh}"`),`Chinese counterpart for ${en}`);
    assert.ok(html.includes(`hreflang="en" href="https://docs.example.com${en}"`));
    assert.ok(html.includes(`hreflang="zh-CN" href="https://docs.example.com${zh}"`));
    assert.ok(html.includes(`rel="canonical" href="https://docs.example.com${en}"`));
    assert.match(html, /Search docs/);
    assert.doesNotMatch(html, /开发者中心|输入关键词/);
    const prose = html.match(/<article class="prose">([\s\S]*?)<\/article>/)?.[1];
    for (const [,target] of (prose || '').matchAll(/href="([^"#]+)"/g)) {
      const linked = await fetch(new URL(target,base + encodeURI(en)));
      assert.equal(linked.status,200,`${en} -> ${target}`);
    }
  }
  const zh = decode(await (await fetch(base + encodeURI('/支付/外部API接入指南'))).text());
  assert.match(zh, /href="\/en\/payments\/external-api-guide"/);
  const search = decode(await (await fetch(base + '/en/?search=refund')).text());
  assert.match(search,/Search results/);
  assert.match(search,/href="\/en\/payments\/external-api-guide"/);
  const sitemap = await (await fetch(base + '/en/sitemap.xml')).text();
  assert.match(sitemap, /https:\/\/docs.example.com\/en\/payments\/external-api-guide/);
  assert.match(sitemap, /<loc>https:\/\/docs.example.com\/en\/<\/loc>/);
  assert.doesNotMatch(sitemap, /\/en\/index<\/loc>/);
  assert.equal((await fetch(base + '/en/missing')).status,404);
  const unknown = await (await fetch(base + '/en/missing')).text();
  assert.match(unknown,/Document not found/);
});

test('English payment translation preserves every JSON example and main chapter', async () => {
  const zh = await readFile(new URL('../content/支付/外部API接入指南.md',import.meta.url),'utf8');
  const en = await readFile(new URL('../content-en/payments/external-api-guide.md',import.meta.url),'utf8');
  const json = text => [...text.matchAll(/```json\n([\s\S]*?)\n```/g)].map(match => JSON.parse(match[1]));
  assert.deepEqual(json(en),json(zh));
  assert.equal((en.match(/^## /gm) || []).length,10);
  assert.equal((en.match(/^```/gm) || []).length,40);
  assert.doesNotMatch(en,/@@CODE\d+@@/);
});
