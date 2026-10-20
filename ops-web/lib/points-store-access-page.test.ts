// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mock = vi.hoisted(() => ({ stores: vi.fn(), types: vi.fn(), agreements: vi.fn(), integration: vi.fn(), obligations: vi.fn(), change: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  listStores: mock.stores, pointsTypes: mock.types, pointsAgreements: mock.agreements, orderPointsIntegration: mock.integration,
  orderPointsObligations: mock.obligations, changeOrderPointsIntegration: mock.change,
} }));
vi.mock("@/components/ui/data-table", async () => {
  const { createElement: h } = await import("react");
  return { DataTable: ({ rows, columns }: { rows?: Array<Record<string, unknown>>; columns: Array<{ cell: (row: never) => unknown }> }) =>
    h("div", { "data-testid": "points-store-access-list" }, ...(rows ?? []).map((row, index) =>
      h("div", { key: index }, ...columns.map((column, col) => h("span", { key: col }, column.cell(row as never)))))) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  return { Drawer: () => null, DrawerSection: ({ children }: { children: unknown }) => h("section", {}, children as never) };
});

import { PointsStoreAccessPage } from "@/app/points/store-access-tab";
let root: Root; let host: HTMLDivElement; let query: QueryClient;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.stores.mockResolvedValue({ records: [{ storeNo: "S1", name: "示例门店", merchantNo: "M1", merchantName: "商户一", status: "ACTIVE" }], total: 1 });
  mock.types.mockResolvedValue([{ typeCode: "PT1", name: "商城积分" }]);
  mock.agreements.mockResolvedValue({ rows: [], next: 0, hasMore: false });
  mock.integration.mockResolvedValue({ mode: "DETACHED", earnEnabled: false, redeemEnabled: false, version: 1 });
  mock.obligations.mockResolvedValue({});
  host = document.createElement("div"); document.body.append(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false } } }); root = createRoot(host);
});
afterEach(async () => { if (root) await act(async () => root.unmount()); query?.clear(); host?.remove(); });

it("shows store agreement access in a focused list with store selection", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsStoreAccessPage, { canEdit: false }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.querySelector('[data-testid="points-store-access-list"]')).not.toBeNull();
  expect(host.textContent).toContain("门店协议与接入");
  expect(host.textContent).not.toContain("积分退款待处理");
  expect(mock.stores).toHaveBeenCalled();
  expect(mock.agreements).not.toHaveBeenCalled();
});

it("loads agreements and access state only after a store is selected", async () => {
  mock.agreements.mockResolvedValue({rows:[{agreement:{storeNo:"S1",typeCode:"PT1",state:"ACTIVE",settleMode:"PER_ORDER",termsVersion:"1"}, integration:{}}],next:0,hasMore:false});
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsStoreAccessPage, { canEdit: false }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  const storeSelect = [...host.querySelectorAll("select")].at(-1)!;
  const typeSelect = [...host.querySelectorAll("select")][0];
  await act(async () => {
    typeSelect.value = "PT1";
    typeSelect.dispatchEvent(new Event("change", { bubbles: true }));
    storeSelect.value = "S1";
    storeSelect.dispatchEvent(new Event("change", { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  expect(mock.agreements).toHaveBeenCalledWith({ storeNo: "S1", typeCode: "PT1", after: 0, limit: 100 });
  expect(mock.integration).toHaveBeenCalledWith({ storeNo: "S1", scene: "ONLINE" });
  expect(host.textContent).toContain("示例门店");
  const list=host.querySelector('[data-testid="points-store-access-list"]')!;
  expect(list.textContent).toContain("商城积分");
  expect(list.querySelectorAll("span")[0].textContent).toBe("商城积分");
  expect(list.querySelectorAll("span")[1].textContent).toBe("PT1");
});

it("shows and retries a points-name load failure", async () => {
  mock.types.mockRejectedValueOnce(new Error("POINTS_NAME_MISSING"));
  await act(async () => root.render(h(QueryClientProvider, {client:query},h(PointsStoreAccessPage,{canEdit:false}))));
  await act(async () => {await new Promise(resolve=>setTimeout(resolve,20));});
  expect(host.textContent).toContain("POINTS_NAME_MISSING");
  const retry=[...host.querySelectorAll("button")].find(b=>b.textContent==="重试")!;
  await act(async () => {retry.click(); await new Promise(resolve=>setTimeout(resolve,20));});
  expect(mock.types).toHaveBeenCalledTimes(2);
  expect(host.textContent).toContain("商城积分");
});
