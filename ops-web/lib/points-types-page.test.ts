// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mock = vi.hoisted(() => ({ types: vi.fn(), fallback: vi.fn(), terms: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  pointsTypes: mock.types, pointsFallback: mock.fallback, pointsTerms: mock.terms,
} }));
vi.mock("@/components/ui/data-table", async () => {
  const { createElement: h } = await import("react");
  return { DataTable: ({ rows, columns }: { rows?: Array<Record<string, unknown>>; columns: Array<{ cell: (row: never) => unknown }> }) =>
    h("div", { "data-testid": "points-type-list" }, ...(rows ?? []).map((row, index) =>
      h("div", { key: index }, ...columns.map((column, col) => h("span", { key: col }, column.cell(row as never)))))) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  return { Drawer: () => null, DrawerSection: ({ children }: { children: unknown }) => h("section", {}, children as never) };
});

import { PointsTypesTab } from "@/app/points/types-tab";

let root: Root;
let host: HTMLDivElement;
let query: QueryClient;

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.types.mockResolvedValue([]);
  mock.fallback.mockResolvedValue(null);
  mock.terms.mockResolvedValue(null);
  host = document.createElement("div");
  document.body.append(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  root = createRoot(host);
});

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  query?.clear();
  host?.remove();
});

it("keeps the settings page focused on its own list and rule details", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: false }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });

  expect(host.querySelector('[data-testid="points-type-list"]')).not.toBeNull();
  expect(host.textContent).toContain("积分设置");
  expect(host.textContent).not.toContain("门店协议查询");
  expect(host.textContent).not.toContain("订单积分按门店接线");
  expect(host.textContent).not.toContain("原订单积分核验");
  expect(host.textContent).not.toContain("积分退款待处理");
});
