// Run after build:mp-weixin. Execute the generated WeChat render function;
// Vue's H5 renderer does not eagerly evaluate sibling v-else-if conditions.
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const assert = require('node:assert/strict');
const root = resolve(process.argv[2] || 'dist/build/mp-weixin');
const source = readFileSync(resolve(root, 'pages/stored-value-records/index.js'), 'utf8');
let page;
let show;
const vendor = {
  defineComponent: value => value, _export_sfc: value => value,
  useI18n: () => ({ t: key => key }), ref: value => ({ value }),
  computed: fn => ({ get value() { return fn(); } }),
  unref: value => value && typeof value === 'object' && 'value' in value ? value.value : value,
  canNativeShare: () => true,
  onLoad: fn => fn({ storeNo: 'STORE_TEST' }), onShow: fn => { show = fn; },
  onReachBottom() {}, onUnload() {},
  e: (...args) => Object.assign({}, ...args), p: value => value, t: String,
  o: value => value, f: (items, fn) => items.map(fn), gei: () => '',
  ROUTES: { storedValue: '/pages/stored-value/index' },
};
const deps = {
  '../../common/vendor.js': vendor,
  '../../api/index.js': { api: { storedValueNotificationCapability: async () => ({ channel: 'WXSUB', available: false, templateId: null }) } },
  '../../shared/stored-value-pager.js': { createStoredValuePager: () => ({
    state: { records: [], loading: false, failed: false, loaded: false, errorCode: 0 },
    reset: async () => {}, more() {}, invalidate() {},
  }) },
  '../../shared/stored-value-subscription.js': {},
  '../../shared/stored-value-time.js': {},
  '../../shared/stored-value-session.js': { readStoredValueWithSession: read => read() },
};
runInNewContext(source, { exports: {}, require: name => {
  assert.ok(name in deps, `Unexpected dependency: ${name}`); return deps[name];
}, wx: { createPage: value => { page = value; } } });
const render = page.setup({});
const context = { $t: key => key };
assert.doesNotThrow(() => render(context, {}), 'Initial WeChat render must tolerate an unresolved notification capability');
show();
Promise.resolve().then(() => Promise.resolve()).then(() => {
  assert.doesNotThrow(() => render(context, {}), 'Resolved unavailable capability must render without errors');
  console.log('PASS: generated WeChat render before and after notification capability resolves (2 checks)');
}).catch(error => { console.error(error); process.exitCode = 1; });
