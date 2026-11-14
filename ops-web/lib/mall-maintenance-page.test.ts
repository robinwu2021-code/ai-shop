// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { MallCatalogOverview } from "./types";
const s = vi.hoisted(() => ({ catalog: vi.fn(), publish: vi.fn(), profile: vi.fn(), configure: vi.fn(), subscription: vi.fn(), quote: vi.fn(), place: vi.fn(), confirm: vi.fn(), dismiss: vi.fn(), realConfirm: false, query: "" }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(s.query) }));
vi.mock("@/lib/api", () => ({ api: { mallCatalog: s.catalog, mallBusinessTypes: async () => [], mallPlanDefinitions: async () => [], mallBusinessEnds: async () => [], mallDeploymentOptions: async () => [], publishMallIndustry: s.publish, offlineProfile: s.profile, configureOfflineProfile: s.configure, mallSubscription: s.subscription, offlineAssistedQuote: s.quote, mallOrders: async () => [], offlineAssistedPlace: s.place } }));
vi.mock("@/lib/notify", () => ({ notify: { success: vi.fn(), error: vi.fn() } }));
vi.mock("@/components/ui/confirm-dialog", async (original) => {
  const actual = await original<typeof import("@/components/ui/confirm-dialog")>();
  return { useConfirm: () => { const real = actual.useConfirm(); return s.realConfirm ? real : { confirm: s.confirm, dialog: null, dismiss: s.dismiss }; } };
});
vi.mock("@/lib/i18n", () => ({ useT: () => (k: string) => k, useI18n: () => ({ locale: "zh", t: (k: string) => k }) }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: () => null, FieldGrid: ({ children }: { children: unknown }) => h("div", {}, children as never), DrawerSection: ({ children }: { children: unknown }) => h("div", {}, children as never), Field: ({ label, children }: { label: string; children: unknown }) => h("div", {}, label, children as never) }));
import { CatalogTab } from "@/app/mall/catalog-tab";
import { CollectionTab } from "@/app/mall/collection-tab";
import { AssistedTab } from "@/app/mall/assisted-tab";
import { MALL_COPY } from "@/app/mall/copy";
const c = MALL_COPY.zh;
let host: HTMLDivElement, root: Root, query: QueryClient, catalog: MallCatalogOverview;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks(); s.realConfirm = false; s.query = "";
  catalog = { businessTypes: [{ draft: { typeCode: "RETAIL", name: "Retail" }, catalogVersion: 7 }], planDefinitions: ["FREE", "PRO", "CHAIN"].map((tier) => ({ planNo: tier, tier, typeCode: "RETAIL", currentVersionNo: tier + "-7", version: 3 })) as never,
    planVersions: ["FREE", "PRO", "CHAIN"].map((tier) => ({ status: "PUBLISHED", version: 2, draft: { planNo: tier, tier, typeCode: "RETAIL", planVersionNo: tier + "-7", displayName: tier + " current" } })) as never,
    skuDefinitions: [{ skuNo: "HW", title: "Hardware", saleStatus: "ON_SALE", currentVersionNo: "HW-1" }, { skuNo: "OTHER", title: "Wrong business", saleStatus: "ON_SALE", currentVersionNo: "OTHER-1" }],
    skuVersions: [{ status: "PUBLISHED", draft: { skuVersionNo: "HW-1", price: { currency: "TWD", minor: "100" }, allowedBusinessTypes: ["RETAIL"] } }, { status: "PUBLISHED", draft: { skuVersionNo: "OTHER-1", price: { currency: "TWD", minor: "100" }, allowedBusinessTypes: ["FNB"] } }] };
  s.catalog.mockImplementation(async () => structuredClone(catalog)); s.publish.mockResolvedValue({ catalogVersion: 8 });
  s.subscription.mockResolvedValue({ businessTypeCode: "RETAIL", currency: "TWD", currentPlanVersionNo: "PRO-7", serverNow: 1000 });
  s.profile.mockResolvedValue({ profileNo: "p", profileVersion: 4, platformMerchantNo: "platform", currency: "TWD", receivingAccountKey: "key", paymentWindowVersion: "w1", paymentWindowMs: 86400000, instructions: { bankCode: "B", bankAccount: "123", accountName: "Name" }, enabled: false });
  s.configure.mockResolvedValue({ profileVersion: 5 });
  s.quote.mockResolvedValue({ quoteNo: "Q", total: { currency: "TWD", minor: "100" }, payable: { currency: "TWD", minor: "100" }, components: [{ componentNo: "C", kind: "HARDWARE", quantity: 1 }] }); s.place.mockRejectedValue(new Error("NETWORK_ERROR"));
  host = document.createElement("div"); document.body.append(host); root = createRoot(host); query = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
async function settle() { await act(async () => { await new Promise((r) => setTimeout(r, 30)); }); }
async function mount(child: ReturnType<typeof h>) { await act(async () => root.render(h(QueryClientProvider, { client: query }, child))); await settle(); }
async function input(id: string, value: string) { await act(async () => { const e = host.querySelector(id) as HTMLInputElement; Object.getOwnPropertyDescriptor(e.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, "value")!.set!.call(e, value); e.dispatchEvent(new Event("input", { bubbles: true })); }); }
async function select(id: string, value: string) { await act(async () => { const e = host.querySelector(id) as HTMLSelectElement; e.value = value; e.dispatchEvent(new Event("change", { bubbles: true })); }); }
function button(text: string) { return [...host.querySelectorAll("button")].find((b) => b.textContent === text)!; }
async function click(text: string) { await act(async () => button(text).click()); await settle(); }
it("publication reads the server version and regenerates its operation after success", async () => {
  await mount(h(CatalogTab, { c, canEdit: false, canPublish: true })); expect(host.querySelector('[data-testid="catalog-version"]')?.textContent).toBe("7"); expect(host.querySelector("#pub-catalog")).toBeNull();
  await click(c.btnPublishIndustry); await act(async () => s.confirm.mock.calls.at(-1)![0].action("publish")); await settle();
  const request = s.publish.mock.calls[0]![1]; expect(request.expectedCatalogVersion).toBe(7);
  await click(c.btnPublishIndustry); await act(async () => s.confirm.mock.calls.at(-1)![0].action("republish")); expect(s.publish.mock.calls[1]![1].operationNo).not.toBe(request.operationNo);
});
it("missing catalog version disables publication instead of guessing zero", async () => {
  delete catalog.businessTypes![0]!.catalogVersion; await mount(h(CatalogTab, { c, canEdit: false, canPublish: true })); expect(button(c.btnPublishIndustry).disabled).toBe(true);
});
it("a publication conflict reloads the server version without retrying", async () => {
  await mount(h(CatalogTab, { c, canEdit: false, canPublish: true })); s.publish.mockRejectedValueOnce(new Error("VERSION_CONFLICT")); await click(c.btnPublishIndustry); catalog.businessTypes![0]!.catalogVersion = 9;
  await act(async () => { await expect(s.confirm.mock.calls.at(-1)![0].action("publish")).rejects.toThrow("VERSION_CONFLICT"); }); await settle();
  expect(host.querySelector('[data-testid="catalog-version"]')?.textContent).toBe("9"); expect(s.publish).toHaveBeenCalledTimes(1);
});
it("a disabled collection profile retains its version and can be re-enabled", async () => {
  await mount(h(CollectionTab, { c, canWrite: true })); expect(host.textContent).toContain(c.profileDisabled); expect((host.querySelector("#pf-no") as HTMLInputElement).disabled).toBe(true);
  const checkbox = host.querySelector('[role="checkbox"]') as HTMLButtonElement; expect(checkbox.getAttribute("aria-checked")).toBe("false"); await act(async () => checkbox.click()); await input("#pf-reason", "resume"); await click(c.btnSaveNewVersion);
  expect(s.configure).toHaveBeenCalledWith(expect.objectContaining({ profileNo: "p", expectedVersion: 4, enabled: true, reason: "resume" }));
});
async function targetStore(canReadSubscription = true) { await mount(h(AssistedTab, { c, canReadSubscription })); await input("#assist-store", "S1"); await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); await settle(); }
it("assisted purchase shows current plan and filters other business types", async () => {
  await targetStore(); expect(host.textContent).toContain("PRO current"); expect(host.querySelector('#as-sku option[value="OTHER-1"]')).toBeNull(); expect((host.querySelector("#as-cur") as HTMLSelectElement).disabled).toBe(true);
});
it("hardware requires an address and retries the same frozen intent after a network failure", async () => {
  await targetStore(); await select("#as-sku", "HW-1"); await click(c.btnQuote); expect(button(c.btnPlace).disabled).toBe(true);
  for (const [key, value] of Object.entries({ recipient: "Recipient", phone: "123", countryCode: "TW", addressLine: "Taipei" })) await input("#as-" + key, value);
  await click(c.btnPlace); expect((host.querySelector("#as-recipient") as HTMLInputElement).disabled).toBe(true); expect(host.textContent).toContain("NETWORK_ERROR"); await click(c.reviewLookup); await click(c.reviewRetry);
  expect(s.place).toHaveBeenCalledTimes(2); expect(s.place.mock.calls[0]).toEqual(s.place.mock.calls[1]); expect(s.place.mock.calls[0]).toEqual(["S1", expect.objectContaining({ quoteNo: "Q", shippingAddress: expect.objectContaining({ recipient: "Recipient", phone: "123", countryCode: "TW", addressLine: "Taipei" }) })]);
});
it("subscription readers are optional and no permission is borrowed", async () => {
  await targetStore(false); expect(host.textContent).toContain(c.assistedContextUnavailable); expect(s.subscription).not.toHaveBeenCalled();
});

it("a real conflict confirmation closes and the next explicit confirmation uses fresh versions", async () => {
  s.realConfirm = true;
  await mount(h(CatalogTab, { c, canEdit: false, canPublish: true }));
  s.publish.mockRejectedValueOnce(new Error("VERSION_CONFLICT"));
  await click(c.btnPublishIndustry);
  async function confirmInDialog() {
    await act(async () => {
      const e = document.querySelector('[role="dialog"] input') as HTMLInputElement;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(e, "publish");
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => [...document.querySelectorAll('[role="dialog"] button')].find((b) => b.textContent === "confirm.ok")!.dispatchEvent(new MouseEvent("click", { bubbles: true })));
    await settle();
  }
  catalog.businessTypes![0]!.catalogVersion = 9;
  await confirmInDialog();
  expect(document.querySelector('[role="dialog"]')).toBeNull();
  expect(host.querySelector('[data-testid="catalog-version"]')?.textContent).toBe("9");
  await click(c.btnPublishIndustry); await confirmInDialog();
  expect(s.publish).toHaveBeenCalledTimes(2);
  expect(s.publish.mock.calls[0]![1].expectedCatalogVersion).toBe(7);
  expect(s.publish.mock.calls[1]![1].expectedCatalogVersion).toBe(9);
});

it("prefills an assisted purchase from the actual customer store deep link without creating an order", async()=>{
 s.query="tab=assisted&store=S%2F1";await mount(h(AssistedTab,{c,canReadSubscription:true}));await settle();
 expect((host.querySelector("#assist-store") as HTMLInputElement).value).toBe("S/1");expect(s.subscription).toHaveBeenCalledWith("S/1");expect(s.place).not.toHaveBeenCalled();expect(s.quote).not.toHaveBeenCalled();
});
