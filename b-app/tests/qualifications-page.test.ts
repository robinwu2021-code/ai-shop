// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import './component-setup';
import zh from '../src/i18n/locale/zh-CN';
import Uploader from '../../packages/ui/src/components/sh-uploader.vue';
const hooks = vi.hoisted(() => ({ show: undefined as undefined | (() => void) }));
vi.mock('@/api', () => ({ api: { mQualifications: async () => ({ items: [], grantedCodes: [], catalog: [] }) } }));
vi.mock('@/stores/merchant', () => ({ useMerchantStore: () => ({ can: () => true, categoryGateEnforced: false }) }));
vi.mock('@dcloudio/uni-app', () => ({ onLoad: () => {}, onShow: (cb: () => void) => { hooks.show = cb; } }));
import Page from '../src/pages/qualifications/index.vue';
it('opening a certificate form renders a landscape document upload target and cancel dismisses the form', async () => {
 const page = mount(Page, { global: { plugins: [createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })], stubs: { 'sh-scaffold': { template: '<main><slot/></main>' } } } });
 hooks.show?.(); await flushPromises();
 await page.findAll('.adds text')[0]!.trigger('tap');
 expect(page.findAll('input')).toHaveLength(3);
 // Certificates use the 160×112 document shape, not the 104×104 goods-photo fallback.
 expect(page.getComponent(Uploader).props('width')).toBe(160);
 expect(page.getComponent(Uploader).props('height')).toBe(112);
 await page.get('.acts .sh-btn--muted').trigger('tap');
 expect(page.find('input').exists()).toBe(false);
 page.unmount();
});
