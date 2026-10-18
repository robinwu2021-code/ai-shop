// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import "./component-setup";
const m = vi.hoisted(() => ({ ready: false, profileReady: false, profile: vi.fn(), scope: vi.fn(), can: vi.fn(), orders: vi.fn(), pending: vi.fn(), show: undefined as undefined | (() => unknown) }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ get canOperate() { return m.profileReady; }, ensureScope: m.scope, ensureProfile: m.profile, can: m.can, ensureStores: vi.fn().mockResolvedValue(undefined), multiStore: false, storeNo: "ST1" }) }));
vi.mock("@/api", () => ({ api: { mOrderWorklist: m.orders, mAfterSaleWorklist: m.pending } }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => unknown) => { m.show = cb; }, onReachBottom: vi.fn() }));
import Page from "@/pages/orders/index.vue";
beforeEach(() => {
  vi.clearAllMocks(); m.ready = false; m.profileReady = false;
  m.profile.mockImplementation(async () => { m.profileReady = true; });
  m.scope.mockImplementation(async () => { m.ready = true; });
  m.can.mockImplementation(() => m.ready);
  m.orders.mockResolvedValue({ records: [], total: 0 });
  m.pending.mockResolvedValue({ records: [], total: 0 });
});
async function render() {
  const w = mount(Page, { global: { mocks: { $t: (k: string) => k }, stubs: { "sh-scaffold": { template: "<div><slot/></div>" } } } });
  await m.show?.();
  for (let i = 0; i < 20; i++) await Promise.resolve();
  return w;
}
it("首次进入先加载门店权限，再取订单和有权处理的售后待办", async () => {
  const w = await render();
  expect(m.orders).toHaveBeenCalledOnce();
  expect(m.pending).toHaveBeenCalledOnce();
  w.unmount();
});
it("无售后权限仍可查看订单且不查询售后待办", async () => {
  m.ready = true; m.profileReady = true; m.can.mockImplementation((p: string) => p !== "biz:aftersale");
  const w = await render();
  expect(m.orders).toHaveBeenCalledOnce();
  expect(m.pending).not.toHaveBeenCalled();
  w.unmount();
});
it("门店权限先到而商家资料仍在加载时，不提前退出或漏查订单", async () => {
  let finishProfile!: () => void;
  m.profile.mockImplementation(() => new Promise<void>((resolve) => {
    finishProfile = () => { m.profileReady = true; resolve(); };
  }));
  const w = mount(Page, { global: { mocks: { $t: (k: string) => k }, stubs: { "sh-scaffold": { template: "<div><slot/></div>" } } } });
  const showing = m.show?.();
  for (let i = 0; i < 10; i++) await Promise.resolve();
  expect(m.ready).toBe(true);
  expect(m.orders).not.toHaveBeenCalled();
  expect(m.profile).toHaveBeenCalledOnce();
  finishProfile();
  await showing;
  for (let i = 0; i < 20; i++) await Promise.resolve();
  expect(m.orders).toHaveBeenCalledOnce();
  w.unmount();
});
