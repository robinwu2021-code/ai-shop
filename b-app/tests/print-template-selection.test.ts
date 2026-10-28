// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import './component-setup';
import en from '../src/i18n/locale/en';
import Check from '../../packages/ui/src/components/sh-check.vue';
const lifecycle = vi.hoisted(() => ({ show: undefined as undefined | (() => Promise<void>) }));
vi.mock('@/api', () => ({ USE_MOCK: false }));
vi.mock('@/stores/merchant', () => ({ useMerchantStore: () => ({ storeNo: 'S1', can: () => true, ensureScope: async () => {} }) }));
vi.mock('@dcloudio/uni-app', () => ({ onShow: (cb: () => Promise<void>) => { lifecycle.show = cb; }, onPullDownRefresh: () => {} }));
// Real decoding/commands remain; only replace the HTTP-facing query boundary.
vi.mock('@/api/print', async importOriginal => {
 const real = await importOriginal<typeof import('@/api/print')>();
 const frozen = (data: unknown) => ({ canonicalJson: JSON.stringify(data), sha256: 'fixture' });
 return { ...real, callPrint: async (id: string) => {
  if (id === 'C01') return [{ scene: 'ORDER_PAID', title: 'Order paid', sourceAvailable: true, variants: ['STANDARD_80'], sourceTypes: ['ORDER'], triggers: ['ORDER_PAYMENT_CONFIRMED'] }];
  if (id === 'P02') return { records: [{ printerNo: 'P1', storeNo: 'S1', name: 'Counter printer', connectionType: 'CLOUD', adapterCode: 'FEIE', paperWidthMm: 80, status: 'ACTIVE', healthStatus: 'ONLINE', revision: 1 }], total: 1, page: 1, size: 100 };
  if (id === 'C02') return [];
  if (id === 'C12' || id === 'C05') return { records: [], total: 0, page: 1, size: 100 };
  if (id === 'C20') return { baseTemplate: frozen({ blocks: [{ type: 'keyValue', binding: 'orderNo', label: 'Order number' }, { type: 'keyValue', binding: 'note', label: 'Note' }], layout: {} }) };
  if (id === 'C19') return { catalog: frozen({ mandatoryPrintPaths: ['orderNo'] }) };
  throw new Error('Unexpected query: ' + id);
 } };
});
import Page from '../src/pages/print-templates/index.vue';
it('shared check controls reflect a single row tap and mandatory receipt fields remain selected', async () => {
 const page = mount(Page, { global: { plugins: [createI18n({ legacy: false, locale: 'en', messages: { en } })], stubs: { 'sh-scaffold': { template: '<main><slot/></main>' } } } });
 await lifecycle.show?.(); await flushPromises();
 expect(page.findAllComponents(Check)).toHaveLength(3);
 const row = (label: string) => page.findAll('.sh-cell').find(w => w.text().includes(label))!;
 await row('Note').findComponent(Check).trigger('tap');
 expect(row('Note').findComponent(Check).props('modelValue')).toBe(false);
 await row('Note').trigger('tap');
 expect(row('Note').findComponent(Check).props('modelValue')).toBe(true);
 await row('Order number').trigger('tap');
 expect(row('Order number').findComponent(Check).props('modelValue')).toBe(true);
 expect(row('Order number').findComponent(Check).props('disabled')).toBe(true);
 await row('Counter printer').findComponent(Check).trigger('tap');
 expect(row('Counter printer').findComponent(Check).props('modelValue')).toBe(true);
 await row('Counter printer').trigger('tap');
 expect(row('Counter printer').findComponent(Check).props('modelValue')).toBe(false);
 page.unmount();
});
