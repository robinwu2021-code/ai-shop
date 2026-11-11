// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { createI18n } from "vue-i18n";
import { createPinia } from "pinia";
import type { MerchantPlan } from "@shared/types";
import { uniMock } from "./component-setup";
import zh from "../src/i18n/locale/zh-CN";

const hooks = vi.hoisted(() => ({ show: undefined as undefined | (() => void) }));
const calls = vi.hoisted(() => ({ plan: vi.fn(), trial: vi.fn(), confirm: vi.fn() }));
const merchant = reactive({ storeNo: "S1", profile: { merchantNo: "E1" }, can: () => true });
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => merchant }));
vi.mock("@/api", () => ({ api: { mMyPlan: calls.plan, mStartTrial: calls.trial } }));
vi.mock("@ai-shop/ui/prompt", async (importOriginal) => ({
  ...await importOriginal<typeof import("@ai-shop/ui/prompt")>(), confirm: calls.confirm,
}));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => void) => { hooks.show = cb; } }));
import Page from "../src/pages/plan/index.vue";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function plan(name: string, trialTier: string | null = null): MerchantPlan {
  return {
    planCode: "FREE", planName: name, status: "ACTIVE", startAt: null, expireAt: null,
    storeQuota: 1, storeUsed: 1, staffQuota: 0, staffUsed: 0, crossStoreStats: false,
    trialUsed: false, trialTier, trialDays: trialTier ? 14 : null,
    suspendedStores: [], tiers: [],
  };
}
function render() {
  const page = mount(Page, { global: {
    plugins: [createPinia(), createI18n({ legacy: false, locale: "zh-CN", messages: { "zh-CN": zh } })],
    stubs: { "app-overlay": true },
  } });
  hooks.show?.();
  return page;
}
function switchTo(storeNo: string, entityNo: string) {
  merchant.storeNo = storeNo;
  merchant.profile = { merchantNo: entityNo };
}
beforeEach(() => {
  switchTo("S1", "E1");
  calls.plan.mockReset().mockResolvedValue(plan("当前 A 套餐"));
  calls.trial.mockReset().mockResolvedValue(plan("当前 A 试用"));
  calls.confirm.mockReset().mockResolvedValue(true);
  uniMock.showToast.mockClear();
});

it("discards an old A response even after switching A to B and back to A", async () => {
  const old = deferred<MerchantPlan>();
  calls.plan.mockImplementationOnce(() => old.promise);
  const page = render(); await flushPromises();
  calls.plan.mockResolvedValue(plan("当前 B 付费套餐"));
  switchTo("S2", "E2"); await flushPromises();
  expect(page.get(".cur").text()).toContain("当前 B 付费套餐");
  calls.plan.mockResolvedValue(plan("当前 A 新套餐"));
  switchTo("S1", "E1"); await flushPromises();
  old.resolve(plan("旧 A 免费套餐")); await flushPromises();
  expect(page.get(".cur").text()).toContain("当前 A 新套餐");
  expect(page.text()).not.toContain("旧 A 免费套餐");
  page.unmount();
});

it("clears the old view on entity changes and keeps failed loads free of old plan data", async () => {
  const page = render(); await flushPromises();
  expect(page.get(".cur").text()).toContain("当前 A 套餐");
  const current = deferred<MerchantPlan>();
  calls.plan.mockImplementationOnce(() => current.promise);
  merchant.profile = { merchantNo: "E2" }; await flushPromises();
  expect(page.find(".cur").exists()).toBe(false);
  current.reject(new Error("503")); await flushPromises();
  expect(page.find(".fail").exists()).toBe(true);
  expect(page.text()).not.toContain("当前 A 套餐");
  page.unmount();
});

it("clears a previously loaded plan when a same-scope reload fails", async () => {
  const page = render(); await flushPromises();
  calls.plan.mockRejectedValueOnce(new Error("503"));
  hooks.show?.(); await flushPromises();
  expect(page.find(".fail").exists()).toBe(true);
  expect(page.find(".cur").exists()).toBe(false);
  page.unmount();
});

it("does not post a trial confirmed after the selected store changes", async () => {
  const confirmation = deferred<boolean>();
  calls.plan.mockResolvedValue(plan("可试用套餐", "PRO"));
  calls.confirm.mockImplementationOnce(() => confirmation.promise);
  const page = render(); await flushPromises();
  await page.get(".sh-btn.act").trigger("tap");
  calls.plan.mockResolvedValue(plan("当前 B 付费套餐"));
  switchTo("S2", "E2"); await flushPromises();
  confirmation.resolve(true); await flushPromises();
  expect(calls.trial).not.toHaveBeenCalled();
  expect(page.get(".cur").text()).toContain("当前 B 付费套餐");
  page.unmount();
});

it.each(["success", "failure"])("ignores obsolete trial %s after an entity switch", async (outcome) => {
  const trial = deferred<MerchantPlan>();
  calls.plan.mockResolvedValue(plan("可试用套餐", "PRO"));
  calls.trial.mockImplementationOnce(() => trial.promise);
  const page = render(); await flushPromises();
  await page.get(".sh-btn.act").trigger("tap"); await flushPromises();
  expect(calls.trial).toHaveBeenCalledTimes(1);
  calls.plan.mockResolvedValue(plan("当前 B 付费套餐"));
  switchTo("S2", "E2"); await flushPromises();
  if (outcome === "success") trial.resolve(plan("旧 A 试用结果"));
  else trial.reject(new Error("503"));
  await flushPromises();
  expect(page.get(".cur").text()).toContain("当前 B 付费套餐");
  expect(uniMock.showToast).not.toHaveBeenCalled();
  page.unmount();
});

it("keeps the normal trial flow and prevents repeated taps during confirmation", async () => {
  const confirmation = deferred<boolean>();
  calls.plan.mockResolvedValue(plan("可试用套餐", "PRO"));
  calls.confirm.mockImplementationOnce(() => confirmation.promise);
  const page = render(); await flushPromises();
  await page.get(".sh-btn.act").trigger("tap");
  await page.get(".sh-btn.act").trigger("tap");
  expect(calls.confirm).toHaveBeenCalledTimes(1);
  confirmation.resolve(true); await flushPromises();
  expect(calls.trial).toHaveBeenCalledTimes(1);
  expect(page.get(".cur").text()).toContain("当前 A 试用");
  expect(uniMock.showToast).toHaveBeenCalledWith({ title: zh.plan.trialStarted, icon: "none" });
  page.unmount();
});
