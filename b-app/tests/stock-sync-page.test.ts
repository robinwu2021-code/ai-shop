// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import "./component-setup";
import zh from "../src/i18n/locale/zh-CN";
import en from "../src/i18n/locale/en";
import ar from "../src/i18n/locale/ar";
const m = vi.hoisted(() => ({ records: vi.fn(), show: undefined as (() => Promise<void>) | undefined }));
vi.mock("@/api", () => ({ api: { mStockSyncRecords: m.records } }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ storeNo: "S", can: () => true, ensureStores: async () => {} }) }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => Promise<void>) => { m.show = cb; } }));
import Page from "../src/pages/stock-sync-records/index.vue";
beforeEach(() => vi.clearAllMocks());
async function render(records: unknown[]) {
  m.records.mockResolvedValue(records);
  const i18n = createI18n({ legacy: false, locale: "en", fallbackLocale: "zh-CN", messages: { "zh-CN": zh, en, ar } });
  const page = mount(Page, { global: { plugins: [i18n], stubs: { "sh-scaffold": { template: "<main><slot/></main>" } } } });
  await m.show?.(); await flushPromises();
  return { page, i18n };
}
const record = (status: string, result: unknown) => ({ operationNo: "OP1", skuNo: "SKU1", status, result, reason: "", updatedAt: "2026-10-26" });
it("zero stock is a valid completed result and live language changes translate its status and quantity", async () => {
  const { page, i18n } = await render([record("COMPLETED", { applied: true, before: 5, after: 0 })]);
  expect(page.text()).toContain("Completed");
  expect(page.text()).toContain("Online stock: 5 → 0");
  i18n.global.locale.value = "ar"; await flushPromises();
  expect(page.text()).toContain("مكتمل");
  expect(page.text()).not.toMatch(/线上|已完成/);
  page.unmount();
});
it("pending, skipped and unknown outcomes do not claim completion or turn missing quantities into zero", async () => {
  const { page } = await render([record("WAITING", null), record("FUTURE_STATUS", { skipped: "FUTURE_REASON" })]);
  expect(page.text()).toContain("Waiting to sync");
  expect(page.text()).toContain("No result yet");
  expect(page.text()).toContain("Needs review");
  expect(page.text()).toContain("Not applied");
  expect(page.text()).not.toMatch(/FUTURE_STATUS|FUTURE_REASON|→ 0|Completed/);
  page.unmount();
});
it("known blocking reasons stay actionable and an idempotent replay is described as previously processed", async () => {
  const { page } = await render([
    record("BLOCKED", { skipped: "NO_SKU" }),
    record("BLOCKED", { skipped: "ENTITY_MULTI_STORE" }),
    record("COMPLETED", { skipped: "DONE_BEFORE" }),
  ]);
  expect(page.text()).toContain("SKU not found");
  expect(page.text()).toContain("Set separate store stock first");
  expect(page.text()).toContain("Already processed; no duplicate adjustment");
  expect(page.text()).not.toMatch(/NO_SKU|ENTITY_MULTI_STORE|DONE_BEFORE/);
  page.unmount();
});
