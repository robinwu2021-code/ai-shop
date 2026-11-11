// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { createI18n } from "vue-i18n";
import { createPinia } from "pinia";
import "./component-setup";
import zh from "../src/i18n/locale/zh-CN";

const hooks = vi.hoisted(() => ({ show: undefined as undefined | (() => void), query: {} as Record<string, string> }));
const calls = vi.hoisted(() => ({ scope: vi.fn(), status: vi.fn(), channels: vi.fn() }));
const merchant = reactive({ storeNo: "S1", can: () => true });
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => merchant }));
vi.mock("@/api", () => ({ api: {
  mBizScope: calls.scope, mPayServiceStatus: calls.status,
  mPayChannels: calls.channels, mStoreList: async () => [], mPayments: async () => [],
} }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (query: Record<string, string>) => void) => cb(hooks.query),
  onShow: (cb: () => void) => { hooks.show = cb; },
}));
import Page from "../src/pages/payment/index.vue";

const scope = (owner = true, storeNo = "S1", merchantNo = "E1") => ({ owner, currentStoreNo: storeNo, merchantNo, perms: ["biz:finance"] });
const status = (value: string, reviewReason: string | null = null) => ({ status: value, merchantRef: "PM1", linkStatus: "LINKED", profileStatus: value, reviewReason });
function render() {
  const page = mount(Page, { global: {
    plugins: [createPinia(), createI18n({ legacy: false, locale: "zh-CN", messages: { "zh-CN": zh } })],
    stubs: { "app-overlay": true },
  } });
  hooks.show?.();
  return page;
}
beforeEach(() => {
  merchant.storeNo = "S1";
  hooks.query = {};
  calls.scope.mockReset().mockResolvedValue(scope());
  calls.status.mockReset().mockResolvedValue(status("APPROVED"));
  calls.channels.mockReset().mockResolvedValue([]);
});

it("shows actual profile approval separately from receiving money and retains upstream detail", async () => {
  const page = render(); await flushPromises();
  const card = page.get(".pay-service");
  expect(card.text()).toContain("资料审核通过");
  expect(card.text()).toContain("资料审核通过不等于当前门店已开通收款");
  expect(card.text()).toContain("PM1");
  expect(card.text()).toContain("LINKED");
  expect(card.find(".is-ok").exists()).toBe(false);
  page.unmount();
});

it.each(["NONE", "PENDING", "NEED_MORE", "REJECTED"])("renders the real %s review result and reason", async (value) => {
  calls.status.mockResolvedValue(status(value, value === "NEED_MORE" || value === "REJECTED" ? "补充登记文件" : null));
  const page = render(); await flushPromises();
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.status[value as keyof typeof zh.payment.service.status]);
  if (value === "NEED_MORE" || value === "REJECTED") expect(page.text()).toContain("补充登记文件");
  page.unmount();
});

it.each([
  { label: "non-owner finance member", context: scope(false), query: {} },
  { label: "different explicit entity", context: scope(), query: { entityNo: "E2" } },
  { label: "different selected store", context: scope(true, "S2"), query: {} },
  { label: "scope without finance", context: { ...scope(), perms: [] }, query: {} },
])("does not call the owner endpoint for $label", async ({ context, query }) => {
  hooks.query = query; calls.scope.mockResolvedValue(context);
  const page = render(); await flushPromises();
  expect(calls.status).not.toHaveBeenCalled();
  expect(page.find(".pay-service").exists()).toBe(false);
  page.unmount();
});

it("keeps a dependency failure distinct from NONE and retries the real request", async () => {
  calls.status.mockRejectedValueOnce(new Error("503"));
  const page = render(); await flushPromises();
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.failed);
  expect(page.get(".pay-service").text()).not.toContain(zh.payment.service.status.NONE);
  calls.status.mockResolvedValue(status("PENDING"));
  await page.get(".pay-service .sh-btn").trigger("tap"); await flushPromises();
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.status.PENDING);
  expect(calls.scope).toHaveBeenCalledTimes(2);
  page.unmount();
});

it("keeps the profile card visible when channel loading fails and retries channels independently", async () => {
  calls.channels.mockRejectedValueOnce(new Error("legacy channel unavailable"));
  const page = render(); await flushPromises();
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.status.APPROVED);
  expect(page.find(".ch").exists()).toBe(false);
  const failure = page.get(".empty");
  expect(failure.text()).toContain(zh.common.loadFailed);
  calls.channels.mockResolvedValue([{ payChannel: "WECHAT", channelName: "微信支付", canReceiveMoney: true, applyStatus: "ACTIVE" }]);
  await failure.get(".sh-btn").trigger("tap"); await flushPromises();
  expect(page.find(".empty").exists()).toBe(false);
  expect(page.get(".ch").text()).toContain("微信支付");
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.status.APPROVED);
  expect(calls.status).toHaveBeenCalledTimes(1);
  page.unmount();
});

it("clears results on switching store and discards an earlier request even after switching back", async () => {
  let finishOld!: (value: ReturnType<typeof status>) => void;
  calls.status.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve; }));
  const page = render(); await flushPromises();
  calls.scope.mockResolvedValue(scope(true, "S2", "E2"));
  merchant.storeNo = "S2"; await flushPromises();
  expect(page.get(".pay-service").text()).toContain(zh.payment.service.status.APPROVED);
  calls.scope.mockResolvedValue(scope());
  calls.status.mockResolvedValue(status("NEED_MORE", "当前主体原因"));
  merchant.storeNo = "S1"; await flushPromises();
  finishOld(status("REJECTED", "旧请求原因")); await flushPromises();
  expect(page.get(".pay-service").text()).toContain("当前主体原因");
  expect(page.text()).not.toContain("旧请求原因");
  page.unmount();
});
