import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
const calls = vi.hoisted(() => ({ orders: vi.fn(), coupons: vi.fn(), groups: vi.fn(), favorites: vi.fn(), phone: vi.fn() }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => void) => cb(), onReachBottom: vi.fn(), onHide: vi.fn() }));
vi.mock("@/api", () => ({ api: { orderList: calls.orders, myCoupons: calls.coupons, myJoinedGroups: calls.groups,
  loginByWxPhone: calls.phone, favoriteGoods: calls.favorites, favoriteStores: async () => [], afterSaleList: async () => [], myStoreCoupons: async () => [] } }));
vi.mock("@/ports/phone-login", () => ({ nativePhoneAuthorization: true }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
import { useUserStore } from "@/stores/user";
import Orders from "@/pages/orders/index.vue";
import Favorites from "@/pages/favorites/index.vue";
import Groups from "@/pages/my-groups/index.vue";
import Coupons from "@/pages/coupons/index.vue";

describe("protected lists use page login panel", () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks();
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "guest", phone: "" } as never;
  });
  it.each([["orders", Orders], ["favorites", Favorites], ["groups", Groups], ["coupons", Coupons]])("%s waits for authorization", async (_name, Page) => {
    const wrapper = mount(Page, { global: { stubs: { "sh-scaffold": { template: "<div><slot /></div>" }, "biz-sku-row": { props: ["title"], template: "<span>{{title}}</span>" } }, mocks: { $t: (key: string) => key } } }); await flushPromises();
    expect(wrapper.find("button[open-type='getPhoneNumber']").exists()).toBe(true);
    for (const call of [calls.orders, calls.coupons, calls.groups, calls.favorites]) expect(call).not.toHaveBeenCalled();
    expect(wrapper.findComponent({ name: "phone-gate" }).exists()).toBe(false); wrapper.unmount();
  });  it.each([["orders", Orders, calls.orders], ["favorites", Favorites, calls.favorites],
    ["groups", Groups, calls.groups], ["coupons", Coupons, calls.coupons]])("%s loads its private data immediately after successful authorization", async (_name, Page, loader) => {
    calls.orders.mockResolvedValue({ records: [], total: 0 }); calls.coupons.mockResolvedValue([]);
    calls.groups.mockResolvedValue([]); calls.favorites.mockResolvedValue({ records: [], total: 0 });
    calls.phone.mockResolvedValue({ token: "formal", user: { userNo: "owner", phone: "13800138000" } });
    const wrapper = mount(Page, { global: { stubs: { "sh-scaffold": { template: "<div><slot /></div>" }, "biz-sku-row": { props: ["title"], template: "<span>{{title}}</span>" } }, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    await wrapper.get("button").trigger("getphonenumber", { detail: { code: "fresh-phone" } });
    await flushPromises(); expect(loader).toHaveBeenCalledOnce(); wrapper.unmount();
  });
  it.each([["orders", Orders, calls.orders], ["favorites", Favorites, calls.favorites],
    ["groups", Groups, calls.groups], ["coupons", Coupons, calls.coupons]])("%s loads when a known phone account is restored silently", async (_name, Page, loader) => {
    calls.orders.mockResolvedValue({ records: [], total: 0 }); calls.coupons.mockResolvedValue([]);
    calls.groups.mockResolvedValue([]); calls.favorites.mockResolvedValue({ records: [], total: 0 });
    const wrapper = mount(Page, { global: { stubs: { "sh-scaffold": { template: "<div><slot /></div>" }, "biz-sku-row": { props: ["title"], template: "<span>{{title}}</span>" } }, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    useUserStore().token = "restored"; useUserStore().user = { userNo: "owner", phone: "13800138000" } as never;
    await flushPromises(); expect(loader).toHaveBeenCalledOnce(); wrapper.unmount();
  });
  it("a late previous-account order response never appears in the new account", async () => {
    const user = useUserStore(); user.token = "old"; user.user = { userNo: "old", phone: "13800138000" } as never;
    let finish!: (value: unknown) => void;
    calls.orders.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    calls.orders.mockResolvedValue({ records: [], total: 0 });
    const wrapper = mount(Orders, { global: { stubs: { "sh-scaffold": { template: "<div><slot /></div>" }, "biz-sku-row": { props: ["title"], template: "<span>{{title}}</span>" } }, mocks: { $t: (key: string) => key } } });
    await flushPromises(); user.token = "owner"; user.user = { userNo: "owner", phone: "13800138000" } as never;
    await flushPromises();
    finish({ records: [{ orderNo: "OLD", status: "PAID", fulfillment: "STORE_PICKUP", items: [
      { goodsNo: "G1", skuNo: "S1", title: "private-old", cover: "🍚", spec: "5kg", price: 2980, qty: 1 }],
      amount: { payableMinor: 2980 }, createdAt: Date.now() }], total: 1 });
    await flushPromises(); expect(wrapper.text()).not.toContain("private-old"); wrapper.unmount();
  });

});
