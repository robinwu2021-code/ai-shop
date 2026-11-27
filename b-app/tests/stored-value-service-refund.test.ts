// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import './component-setup';

const mock = vi.hoisted(() => ({
  load: undefined as undefined | ((options:{ recordNo:string }) => void),
  record: vi.fn(), refund: vi.fn(), confirm: vi.fn(), can: vi.fn(),
}));
vi.mock('@dcloudio/uni-app', () => ({ onLoad:(callback:typeof mock.load) => { mock.load = callback; } }));
vi.mock('@/api', () => ({ api:{ mSvRecord:mock.record, mSvConsumptionRefund:mock.refund } }));
vi.mock('@ai-shop/ui/prompt', () => ({ confirm:mock.confirm }));
vi.mock('@/stores/merchant', () => ({
  useMerchantStore:() => ({ storeNo:'STORE', ensureStores:async () => {}, can:mock.can }),
}));
vi.mock('vue-i18n', () => ({ useI18n:() => ({ t:(key:string) => key }) }));
import Detail from '@/pages/stored-value-record/index.vue';

const original = {
  recordNo:'REC', serviceOrderNo:'ORDER', type:'REDEMPTION', signedAmountMinor:-1500,
  currency:'CNY', occurredAt:'2026-10-20T02:00:00Z', balanceAfterMinor:0, storeNo:'STORE',
};
async function open() {
  const wrapper = mount(Detail, { global:{ stubs:{ 'sh-scaffold':{template:'<div><slot/></div>'} } } });
  mock.load!({recordNo:'REC'});
  await flushPromises();
  return wrapper;
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.can.mockReturnValue(true);
  mock.record.mockResolvedValue(original);
  mock.confirm.mockResolvedValue(true);
  mock.refund.mockResolvedValue({refundNo:'SVR-1',status:'REFUNDED',amountMinor:1500});
});
it('refunds the original service order after confirmation and displays its receipt status', async () => {
  const wrapper = await open();
  mock.confirm.mockResolvedValueOnce(false);
  await wrapper.get('button').trigger('tap');
  await flushPromises();
  expect(mock.refund).not.toHaveBeenCalled();
  await wrapper.get('button').trigger('tap');
  await flushPromises();
  expect(mock.refund).toHaveBeenCalledExactlyOnceWith('ORDER','STORE');
  expect(wrapper.text()).toContain('storedValue.consumeState.REFUNDED');
  expect(wrapper.find('button').exists()).toBe(false);
  wrapper.unmount();
});
it('keeps the refund action scoped to the merchant refund permission', async () => {
  mock.can.mockImplementation((code:string) => code !== 'mch:pay:refund');
  const wrapper = await open();
  expect(wrapper.find('button').exists()).toBe(false);
  wrapper.unmount();
});
