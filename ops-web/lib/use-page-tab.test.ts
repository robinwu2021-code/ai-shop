// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { platformCatalog } from "./platform-nav";
import { BACKEND_ROLE_PERMS } from "./permissions";
import { useNavTabs, usePageTab } from "./use-page-tab";

const state = vi.hoisted(() => ({ path: "/system", keys: ["appearance", "market", "flags", "storage", "industry", "storeIndustry", "authCode", "scope", "testPhone", "appVersion"], query: "", perms: ["*"], hrefSet: new Set<string>() }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(state.query) }));
vi.mock("@/lib/auth", () => ({ useAuth: (select: (s: typeof state) => unknown) => select(state) }));
vi.mock("@/lib/i18n", () => ({ useI18n: () => ({ tNav: (label: string) => label }) }));
vi.mock("@/lib/stores/server-menu", () => ({ useCatalogNavTree: () => platformCatalog(), useServerMenu: (select: (s: typeof state) => unknown) => select(state) }));

let root: Root;
let host: HTMLDivElement;
function Harness() {
  const tabs = useNavTabs(state.path, state.keys);
  const [selected] = usePageTab(tabs);
  return createElement("output", null, JSON.stringify({ tabs, selected }));
}
async function render() { await act(async () => root.render(createElement(Harness))); }
const result = () => JSON.parse(host.textContent!) as { tabs: { key: string; label: string }[]; selected: string };
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  Object.assign(state, { path: "/system", keys: ["appearance", "market", "flags", "storage", "industry", "storeIndustry", "authCode", "scope", "testPhone", "appVersion"], query: "", perms: ["*"], hrefSet: new Set<string>() });
  host = document.createElement("div"); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });

it("resolves reordered default system tab labels for TECH_OPS and keeps its bare URL", async () => {
  state.perms = BACKEND_ROLE_PERMS.TECH_OPS;
  await render();
  expect(result().tabs.find(t => t.key === "appearance")?.label).toBe("外观文案");
  expect(result().selected).toBe("appearance");
});
it("does not relabel a filtered explicit tab as the hidden default tab", async () => {
  state.perms = ["product:category:read"];
  await render();
  expect(result()).toMatchObject({ tabs: [{ key: "authCode", label: "经营授权" }], selected: "authCode" });
});
it("keeps the inventory bare URL on health after moving its cross-module link", async () => {
  state.path = "/inventory"; state.keys = ["health", "ledger", "recon", "link-health", "credentials"];
  await render();
  expect(result().tabs.find(t => t.key === "health")?.label).toBe("库存概览");
  expect(result().selected).toBe("health");
});
it("restores the real default when navigating back from an explicit system tab", async () => {
  state.query = "tab=market"; await render(); expect(result().selected).toBe("market");
  state.query = ""; await render(); expect(result().selected).toBe("appearance");
});
it("keeps explicit query selection and labels when the server grants only that tab", async () => {
  state.query = "tab=authCode"; state.perms = ["product:category:read"]; state.hrefSet = new Set(["/system?tab=authCode"]);
  await render(); expect(result()).toMatchObject({ selected: "authCode", tabs: [{ key: "authCode", label: "经营授权" }] });
});
it("keeps the mall catalog default and labels after placing entity plans before store plans", async () => {
  state.path = "/mall"; state.keys = ["catalog", "categories", "products", "orders", "claims", "collection", "assisted", "sources"];
  await render();
  expect(result().selected).toBe("catalog");
  expect(result().tabs.find(t => t.key === "catalog")?.label).toBe("门店套餐");
  state.query = "tab=orders"; await render(); expect(result().selected).toBe("orders");
});
it.each([false, true])("preserves the restricted merchant bare-URL default with server grants=%s", async server => {
  state.path = "/merchants";
  state.keys = ["audit", "list", "stores", "categories", "qualifications", "verify", "credit", "admission", "onboarding", "self-operated", "on-behalf", "mode-risk", "ban", "plans", "plan-defs", "chain"];
  state.perms = ["merchant:merchant:read"];
  if (server) state.hrefSet = new Set(["/merchants?tab=list", "/merchants?tab=stores", "/merchants?tab=plans", "/merchants?tab=plan-defs"]);
  await render(); expect(result().selected).toBe("list");
  expect(result().tabs.find(t => t.key === "list")?.label).toBe("商家档案");
  state.query = "tab=plans"; await render(); expect(result().selected).toBe("plans");
  state.query = ""; await render(); expect(result().selected).toBe("list");
});

it("resolves canonical customer aliases without altering the legacy source tabs", async () => {
  Object.assign(state, { path: "/customers", keys: ["overview", "users", "members", "stores", "chains"], query: "tab=stores", perms: ["merchant:merchant:read"], hrefSet: new Set(["/customers?tab=stores"]) });
  await render();
  expect(result()).toMatchObject({ selected: "stores", tabs: [{ key: "stores", label: "门店列表" }] });
});
it("resolves canonical payment aliases from exact server menu grants", async () => {
  Object.assign(state, { path: "/orders", keys: ["all", "exception", "payments", "payment-exceptions"], query: "tab=payments", perms: ["pay:order:view"], hrefSet: new Set(["/orders?tab=payments"]) });
  await render();
  expect(result()).toMatchObject({ selected: "payments", tabs: [{ key: "payments", label: "支付订单" }] });
});

it('shows only mall-owned merchant tabs while visiting an entity plan', async () => {
  Object.assign(state, { path: '/merchants', keys: ['audit', 'list', 'stores', 'plans', 'plan-defs', 'payment', 'payment-onboarding'], query: 'tab=plans', perms: ['*'] });
  await render();
  expect(result().tabs.map(t => t.key)).toEqual(['plans', 'plan-defs']);
  expect(result().selected).toBe('plans');
});
it('shows only payment merchant tabs while preserving the complete legacy profile context', async () => {
  Object.assign(state, { path: '/merchants', keys: ['audit', 'list', 'plans', 'plan-defs', 'payment', 'payment-onboarding', 'payment-channels'], query: 'tab=payment', perms: ['*'] });
  await render();
  expect(result().tabs.map(t => t.key)).toEqual(['payment', 'payment-onboarding']);
  state.query = 'tab=list'; await render();
  expect(result().tabs.map(t => t.key)).toContain('audit');
  expect(result().tabs.map(t => t.key)).toContain('list');
});
it('keeps operating and payment order tabs together and honors exact server grants', async () => {
  Object.assign(state, { path: '/orders', keys: ['all', 'exception', 'proxy', 'payments', 'payment-exceptions'], query: '', perms: ['*'] });
  await render();
  expect(result().tabs.map(t => t.key)).toEqual(['all', 'exception', 'payments', 'payment-exceptions']);
  state.query = 'tab=payments'; state.hrefSet = new Set(['/orders?tab=payments']); await render();
  expect(result().tabs.map(t => t.key)).toEqual(['payments']);
});
