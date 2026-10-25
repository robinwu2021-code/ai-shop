import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

function check(source) {
  const root = mkdtempSync(join(tmpdir(), "i18n-permission-"));
  const put = (path, text) => { const file = join(root, path); mkdirSync(join(file, ".."), { recursive: true }); writeFileSync(file, text); };
  try {
    put("scripts/check-i18n-orphan.mjs", "");
    copyFileSync(new URL("../check-i18n-orphan.mjs", import.meta.url), join(root, "scripts/check-i18n-orphan.mjs"));
    put("scripts/lib/i18n-locale.mjs", "");
    copyFileSync(new URL("../lib/i18n-locale.mjs", import.meta.url), join(root, "scripts/lib/i18n-locale.mjs"));
    put("backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/brand/impl/ManagementScopeServiceImpl.java", `private static final Map<String,Set<String>> PERMISSIONS=Map.of(
      "STORE",Set.of("store.read","store.brand.change"),
      "ENTITY",Set.of("payment.merchant.read"));`);
    if (source.catalog !== undefined) put("backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/brand/impl/ManagementScopeServiceImpl.java", source.catalog);
    put("b-app/src/i18n/locale/zh-CN.ts", `export default {
 store: {
 title: "Store",
 },
 payment: {
 title: "Payment",
 },
};`);
    if (source.locale) put("b-app/src/i18n/locale/zh-CN.ts", source.locale);
    put("b-app/src/page.vue", `<script setup>const labels = ["store.title", "payment.title"];${source.script ?? ""}</script><template>${source.template ?? ""}</template>`);
    return spawnSync(process.execPath, [join(root, "scripts/check-i18n-orphan.mjs"), "--check"], { encoding: "utf8" });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test("authoritative server permission literals are not translation references", () => {
  const result = check({ script: `const permissions = ['store.read', 'payment.merchant.read'];`, template: `<view v-if="context.actions.includes('store.brand.change')" />` });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test("finite settlement action aliases are permissions while real translations stay checked", () => {
  const catalog = 'private static final Map<String,Set<String>> PERMISSIONS=Map.of("ENTITY",Set.of("sv.settlement.payment.register","sv.settlement.payment.void"));';
  const result = check({catalog, template: `<view v-if="can('payment.register','PAYER') || can('payment.void','PAYER')" />`});
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const translation = check({catalog, script: `t('payment.void');`});
  assert.equal(translation.status, 1);
  assert.match(translation.stdout, /缺 b-app payment.void/);
  const unknown = check({catalog, script: `const label = 'payment.typo';`});
  assert.equal(unknown.status, 1);
  assert.match(unknown.stdout, /缺 b-app payment.typo/);
});

for (const source of [
  { script: `t('store.read');` },
  { script: `t(ok ? 'store.read' : 'store.title');` },
  { template: `{{ $t('store.read') }}` },
  { template: `<sh-scaffold title-key="store.read" />` },
  { template: `<sh-scaffold :title-key="\'store.read\'" />` },
  { template: `<sh-kv :label-key="ok ? \'store.read\' : \'store.title\'" />` },
  { script: `const labelKey = "store.read";` },
  { script: `const cell = {labelKey: "store.read"};` },
]) test(`permission codes used as translation keys must still fail: ${JSON.stringify(source)}`, () => {
  const result = check(source);
  assert.equal(result.status, 1);
  assert.match(result.stdout, /缺 b-app store.read/);
});

test("unknown literal keys still fail the broad missing-key check", () => {
  const result = check({ script: `const label = 'store.typo';` });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /缺 b-app store.typo/);
});

test("unreadable authoritative permission catalog fails closed", () => {
  const result = check({ catalog: "// renamed map; scanner must be updated" });
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /permission catalog/);
});


test("quoted and minified locale properties remain checked", () => {
  const result = check({locale: 'export default {"store":{"title":"Store"},"payment":{"title":"Payment"}};', script: `t("store.typo");`});
  assert.equal(result.status, 1); assert.match(result.stdout, /缺 b-app store.typo/);
});
test("literal concatenation prefixes in actual translation calls retain their dynamic keys", () => {
  const result = check({locale: 'export default {"store":{"title":"Store","status":{"ACTIVE":"Active","DRAFT":"Draft"}},"payment":{"title":"Payment"}};', script: `t('store.status.' + state);`});
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
test("quoted placeholder entries still require their interpolation argument", () => {
  const result = check({locale: 'export default {"store":{"title":"Store","count":"{n} items"},"payment":{"title":"Payment"}};', script: `t("store.count");`});
  assert.equal(result.status, 1); assert.match(result.stdout, /占位符没传参.*store.count/);
});
