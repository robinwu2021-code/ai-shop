// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { can } from "./permissions";
const state = vi.hoisted(() => ({ tab: "market", perms: ["system:param:read"] as string[], list: vi.fn(), save: vi.fn(), types: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  listMarkets: async () => [{ code: "TW", name: "台湾", currency: "TWD", timezone: "Asia/Taipei", rate: 4.4, enabled: true }],
  listIndustries: async () => [{ industry: "RETAIL", name: "零售", sort: 1, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false }],
  listStoreIndustries: state.list, saveStoreIndustry: state.save, mallBusinessTypes: state.types,
} }));
vi.mock("@/lib/use-can", () => ({ useCan: () => (code: string) => can(state.perms, code) }));
vi.mock("@/lib/use-page-tab", () => ({ usePageTab: () => [state.tab, vi.fn()], useNavTabs: () => [] }));
vi.mock("@/lib/i18n", () => ({ useT: () => (k: string) => k, useI18n: () => ({ locale: "zh", t: (k: string) => k }) }));
vi.mock("@/components/ui/tab-header", () => ({ TabHeader: () => null }));
vi.mock("@/components/ui/drawer", () => ({ Drawer: ({ open, children }: { open: boolean; children: unknown }) => open ? h("aside", {}, children as never) : null, FieldGrid: ({ children }: { children: unknown }) => h("div", {}, children as never), DrawerSection: ({ children }: { children: unknown }) => h("div", {}, children as never), Field: ({ children }: { children: unknown }) => h("div", {}, children as never) }));
import SystemPage from "@/app/system/page";
let host: HTMLDivElement, root: Root, query: QueryClient;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  state.tab = "market"; state.perms = ["system:param:read"];
  state.list.mockResolvedValue([{ industryCode: "RETAIL", subIndustryCode: "", businessTypeCode: "RETAIL", nameZh: "零售", nameTw: "零售", nameEn: "Retail", sort: 1, enabled: false, version: 7 }]);
  state.save.mockImplementation(async (v) => ({ ...v, version: 8 }));
  state.types.mockResolvedValue([{ draft: { typeCode: "RETAIL", name: "零售" }, version: 1, catalogVersion: 1 }]);
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});
afterEach(async () => { await act(async () => root.unmount()); query.clear(); host.remove(); });
async function mount() {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(SystemPage))));
  await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
}
it("market readers cannot edit rates or enable a market", async () => {
  await mount();
  expect(host.querySelector("input")?.disabled).toBe(true);
  expect((host.querySelector('[role="switch"]') as HTMLButtonElement).disabled).toBe(true);
});
it("market writers can maintain rates", async () => {
  state.perms = ["system:param:read", "system:param:update"];
  await mount(); expect(host.querySelector("input")?.disabled).toBe(false);
});
it("parameter writers do not acquire payment industry authority", async () => {
  state.tab = "industry"; state.perms = ["system:param:read", "system:param:update"];
  await mount(); await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent === "配置")!.click());
  expect(document.body.textContent).not.toContain("关闭微信小微");
});
it("catalog readers see disabled industries without edit controls", async () => {
  state.tab = "storeIndustry"; state.perms = ["commerce:catalog:read"];
  await mount();
  expect(host.textContent).toContain("停用");
  expect([...host.querySelectorAll("button")].some((b) => b.textContent === "新增行业")).toBe(false);
});
it("catalog editors keep the original key and version when reopening a disabled row", async () => {
  state.tab = "storeIndustry"; state.perms = ["commerce:catalog:read", "commerce:catalog:edit"];
  await mount(); await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent === "编辑")!.click());
  expect((host.querySelector("#si-industry") as HTMLInputElement).value).toBe("RETAIL");
  expect((host.querySelector("#si-industry") as HTMLInputElement).disabled).toBe(true);
  expect((host.querySelector("#si-reason") as HTMLInputElement).value).toBe("");
  expect((host.querySelector("#si-enabled") as HTMLButtonElement).getAttribute("aria-checked")).toBe("false");
  await act(async () => {
    const reason = host.querySelector("#si-reason") as HTMLInputElement;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(reason, "恢复配置");
    reason.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent === "保存")!.click());
  expect(state.save).toHaveBeenCalledWith(expect.objectContaining({ industryCode: "RETAIL", subIndustryCode: "", expectedVersion: 7, reason: "恢复配置" }));
});
