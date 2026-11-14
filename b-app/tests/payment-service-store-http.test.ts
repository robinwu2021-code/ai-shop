// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createI18n } from "vue-i18n";
import { STORAGE } from "@shared/utils/constants";
import { uniMock } from "./component-setup";
import zh from "../src/i18n/locale/zh-CN";

const hooks = vi.hoisted(() => ({ show: undefined as undefined | (() => void) }));
// Keep the real API implementation, HTTP header reader and merchant actions together.
vi.mock("@/api", async () => ({ api: (await import("../src/api/http")).httpApi }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: () => {}, onShow: (cb: () => void) => { hooks.show = cb; },
}));
import { useMerchantStore } from "../src/stores/merchant";
import Page from "../src/pages/payment/index.vue";

type WireRequest = {
  url: string;
  header: Record<string, string>;
  success: (response: { statusCode: number; data: { code: number; data: unknown } }) => void;
};
const storage = new Map<string, unknown>();
const sent: WireRequest[] = [];
const pathOf = (request: WireRequest) => new URL(request.url, "http://local").pathname;
beforeEach(() => {
  storage.clear(); sent.length = 0;
  storage.set(STORAGE.token, "owner-token");
  storage.set(STORAGE.storeNo, "S1");
  vi.stubGlobal("uni", {
    ...uniMock,
    getStorageSync: (key: string) => storage.get(key) ?? "",
    setStorageSync: (key: string, value: unknown) => { storage.set(key, value); },
    removeStorageSync: (key: string) => { storage.delete(key); },
    request: (request: WireRequest) => {
      sent.push(request);
      const storeNo = request.header["X-Store-No"];
      let data: unknown = [];
      if (pathOf(request) === "/biz/context") data = {
        owner: true, currentStoreNo: storeNo, merchantNo: "E1", perms: ["*"], staffRoles: ["OWNER"],
      };
      if (pathOf(request) === "/biz/merchant/pay-service") data = {
        status: "PENDING", merchantRef: `PAY-${storeNo}`, linkStatus: null, profileStatus: "PENDING", reviewReason: null,
      };
      request.success({ statusCode: 200, data: { code: 0, data } });
    },
  });
});

function render(perms: string[]) {
  const pinia = createPinia(); setActivePinia(pinia);
  const merchant = useMerchantStore();
  merchant.token = "owner-token"; merchant.storeNo = "S1"; merchant.perms = perms;
  const page = mount(Page, { global: {
    plugins: [pinia, createI18n({ legacy: false, locale: "zh-CN", messages: { "zh-CN": zh } })],
    stubs: { "app-overlay": true },
  } });
  hooks.show?.();
  return { page, merchant };
}

it("loads fresh owner status during cold start without relying on the empty local permission cache", async () => {
  const { page, merchant } = render([]); await flushPromises();
  expect(sent.filter((request) => pathOf(request) === "/biz/merchant/pay-service").map((request) => request.header["X-Store-No"]))
    .toEqual(["S1"]);
  // The real scaffold continues denying display until the normal scope load grants finance.
  expect(page.find(".sh-denied").exists()).toBe(true);
  await merchant.loadScope(); await flushPromises();
  expect(page.get(".pay-service").text()).toContain("PAY-S1");
  page.unmount();
});

it("waits for switchStore storage writes and cancels obsolete scheduled fetches", async () => {
  const { page, merchant } = render(["*"]); await flushPromises();
  sent.length = 0;
  await merchant.switchStore("S2"); await flushPromises();
  expect(sent.filter((request) => pathOf(request) === "/biz/context").every((request) => request.header["X-Store-No"] === "S2"))
    .toBe(true);
  expect(page.get(".pay-service").text()).toContain("PAY-S2");
  sent.length = 0;
  const back = merchant.switchStore("S1");
  const away = merchant.switchStore("S2");
  await Promise.all([back, away]); await flushPromises();
  expect(sent.filter((request) => pathOf(request) === "/biz/merchant/pay-service").map((request) => request.header["X-Store-No"]))
    .toEqual(["S2"]);
  expect(page.get(".pay-service").text()).toContain("PAY-S2");
  page.unmount();
});
