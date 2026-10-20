// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mock = vi.hoisted(() => ({ types: vi.fn(), create: vi.fn(), state: vi.fn(), fallback: vi.fn(), terms: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  pointsTypes: mock.types, createPointsType: mock.create, transitionPointsType: mock.state,
  pointsFallback: mock.fallback, pointsTerms: mock.terms,
} }));
vi.mock("@/components/ui/data-table", async () => {
  const { createElement: h } = await import("react");
  return { DataTable: ({ rows, columns, rowKey, rowProps, empty, emptyAction }: {
    rows?: Array<Record<string, unknown>>; columns: Array<{ cell: (row: never) => unknown }>;
    rowKey: (row: never) => string; rowProps?: (row: never) => Record<string, unknown>;
    empty?: string; emptyAction?: unknown;
  }) => h("div", { "data-testid": "points-type-list" }, ...(rows?.length
    ? rows.map((row) => h("button", { ...rowProps?.(row as never), key: rowKey(row as never), type: "button" },
      ...columns.map((column, index) => h("span", { key: index }, column.cell(row as never)))))
    : [h("div", { key: "empty" }, empty, emptyAction as never)])) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  return { Drawer: ({ open, title, children }: { open: boolean; title: string; children: unknown }) =>
    open ? h("section", { role: "dialog", "aria-label": title }, h("h2", {}, title), children as never) : null,
    DrawerSection: ({ title, children }: { title: string; children: unknown }) => h("section", {}, h("h3", {}, title), children as never),
  };
});

import { PointsConfigurationTab } from "@/app/points/legacy-configuration-tab";

const type = { typeCode: "HXPOINTS_CN", market: "CN", currency: "CNY", scale: 2,
  rate: { pointsUnit: 100, minorUnit: 100 }, deductRatioBp: 2000, idleDays: 365, status: "ACTIVE" };
let root: Root;
let host: HTMLDivElement;
let query: QueryClient;

async function flush() { await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); }); }
async function render() {
  host = document.createElement("div"); document.body.append(host);
  query = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 15000 } } });
  root = createRoot(host);
  await act(async () => { root.render(h(QueryClientProvider, { client: query }, h(PointsConfigurationTab, { canEdit: true }))); });
  await flush();
}

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  mock.types.mockResolvedValue([type]);
  mock.fallback.mockResolvedValue({ typeCode: type.typeCode, revision: 1, mode: "RATIO", value: 500 });
  mock.terms.mockResolvedValue({ typeCode: type.typeCode, version: "1", text: "测试协议", hash: "hash" });
});
afterEach(async () => {
  if (root) await act(async () => root.unmount());
  query?.clear(); host?.remove();
});

it("lists points types and opens the selected type details in a drawer", async () => {
  await render();
  expect(host.querySelector('[data-testid="points-type-list"]')?.textContent).toContain("HXPOINTS_CN");
  expect(host.querySelector('[role="dialog"]')).toBeNull();

  const row = Array.from(host.querySelectorAll("button")).find((button) => button.textContent?.includes("HXPOINTS_CN"));
  expect(row).toBeDefined();
  await act(async () => row!.click());
  await flush();

  expect(host.querySelector('[role="dialog"]')?.getAttribute("aria-label")).toContain("HXPOINTS_CN");
  expect(host.querySelector('[role="dialog"]')?.textContent).toContain("平台兜底规则");
});

it("opens the create form in a separate drawer instead of inline on the list", async () => {
  await render();
  const add = Array.from(host.querySelectorAll("button")).find((button) => button.textContent?.includes("新建积分类型"));
  expect(add).toBeDefined();
  await act(async () => add!.click());

  const dialog = host.querySelector('[role="dialog"]');
  expect(dialog?.textContent).toContain("新建积分类型（草稿）");
  expect(host.querySelector('[data-testid="points-type-list"]')?.textContent).toContain("HXPOINTS_CN");
  expect(mock.create).not.toHaveBeenCalled();
});
