// @vitest-environment happy-dom
import { expect, it, vi } from "vitest";
import { reactive } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import "./component-setup";

const api = vi.hoisted(() => ({ mMallOfflineQuote: vi.fn(), mMallPlaceOrder: vi.fn() }));
const merchant = reactive({ storeNo: "", can: () => true });
let load: (query: Record<string, string>) => void;
vi.mock("@/api", () => ({ api }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => merchant }));
vi.mock("@dcloudio/uni-app", () => ({ onLoad: (callback: typeof load) => { load = callback; } }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key, te: () => true }) }));
import Checkout from "@/pages/biz-mall-checkout/index.vue";

it("等待门店恢复再按链接报价，切店后迟到的报价不能用于下单", async () => {
  let finish!: (result: { quoteNo: string }) => void;
  api.mMallOfflineQuote.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
  const page = mount(Checkout, { global: { stubs: { "sh-scaffold": { template: "<div><slot/></div>" } } } });
  load({ store: "A", sku: "V1", currency: "CNY", quantity: "2" });
  await flushPromises();
  expect(api.mMallOfflineQuote).not.toHaveBeenCalled();
  merchant.storeNo = "A";
  await flushPromises();
  expect(api.mMallOfflineQuote).toHaveBeenCalledExactlyOnceWith("A", {
    lines: [{ skuVersionNo: "V1", quantity: 2 }], currency: "CNY",
  });
  merchant.storeNo = "B";
  await flushPromises();
  finish({ quoteNo: "old" });
  await flushPromises();
  expect(page.find(".sh-btn").exists()).toBe(false);
  expect(api.mMallOfflineQuote).toHaveBeenCalledTimes(1);
  api.mMallOfflineQuote.mockResolvedValueOnce({ quoteNo: "fresh" });
  merchant.storeNo = "A";
  await flushPromises();
  const submit = page.get(".sh-btn");
  merchant.storeNo = "B";
  await submit.trigger("tap");
  expect(api.mMallPlaceOrder).not.toHaveBeenCalled();
  page.unmount();
});
