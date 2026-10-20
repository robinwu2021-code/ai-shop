// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import './component-setup';
const m = vi.hoisted(() => ({ record: vi.fn(), entries: vi.fn(), load: undefined as undefined | ((q: Record<string,string>) => unknown) }));
vi.mock('@dcloudio/uni-app', () => ({ onLoad: (load: typeof m.load) => { m.load = load; } }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('@/api', () => ({ api: { mSvRecord: m.record, mStorePointEntries: m.entries } }));
vi.mock('@/stores/merchant', () => ({ useMerchantStore: () => ({ storeNo: 'S1', can: () => true, ensureStores: async () => {} }) }));
import Detail from '@/pages/stored-value-record/index.vue';
import Points from '@/pages/points-records/index.vue';
const options = {global:{mocks:{$t:(key:string)=>key},stubs:{'sh-scaffold':{template:'<div><slot/></div>'}}}};
const row = { recordNo:'READ-internal', businessNo:'PR-internal-digest', documentNo:'PR20261020000001', maskedName:'陈女士', storeName:'本店', occurredAt:'2026-10-20T02:00:00Z', currency:'CNY', balanceAfterMinor:6000, signedAmountMinor:6000, type:'RECHARGE', title:'充值' };
beforeEach(() => { vi.clearAllMocks(); m.record.mockResolvedValue(row); });
it('renders the mapped number and continues fetching by the original record identity', async () => {
  const view = mount(Detail,options); m.load?.({recordNo:'READ-internal'}); await flushPromises();
  expect(m.record).toHaveBeenCalledWith('READ-internal','S1');
  expect(view.text()).toContain('PR20261020000001');
  expect(view.text()).not.toContain('PR-internal-digest'); view.unmount();
});
it('hides the whole number row for an unmapped historical record', async () => {
  m.record.mockResolvedValue({...row, documentNo:null});
  const view=mount(Detail,options);m.load?.({recordNo:'READ-internal'});await flushPromises();
  expect(view.text()).not.toContain('storedValue.businessNo');
  expect(view.text()).not.toContain('PR-internal-digest');view.unmount();
});
it('renders associated short voucher numbers on points records without displaying voucher UUIDs', async () => {
  m.entries.mockResolvedValue({rows:[{id:1,voucherNo:'uuid-internal',voucherDocumentNo:'PTV20261020000001',bucket:'AVAILABLE',deltaPoints:1,afterPoints:2,at:1}],next:0,hasMore:false});
  const view=mount(Points,options);await m.load?.({typeCode:'TYPE'});await flushPromises();
  expect(view.text()).toContain('PTV20261020000001');expect(view.text()).not.toContain('uuid-internal');view.unmount();
});
