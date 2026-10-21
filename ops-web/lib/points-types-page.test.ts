// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mock = vi.hoisted(() => ({ types: vi.fn(), fallback: vi.fn(), terms: vi.fn(), create: vi.fn(), transition: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: {
  pointsTypes: mock.types, pointsFallback: mock.fallback, pointsTerms: mock.terms, createPointsType: mock.create, transitionPointsType: mock.transition,
} }));
vi.mock("@/components/ui/data-table", async () => {
  const { createElement: h } = await import("react");
  return { DataTable: ({ rows, columns, rowProps }: { rowProps?: (row: never) => Record<string, unknown>; rows?: Array<Record<string, unknown>>; columns: Array<{ cell: (row: never) => unknown }> }) =>
    h("div", { "data-testid": "points-type-list" }, ...(rows ?? []).map((row, index) =>
      h("div", { key: index, ...rowProps?.(row as never) }, ...columns.map((column, col) => h("span", { key: col }, column.cell(row as never)))))) };
});
vi.mock("@/components/ui/drawer", async () => {
  const { createElement: h } = await import("react");
  return { Drawer: ({ open, title, desc, children, footer }: { open: boolean; title: string; desc?: string; children: unknown; footer?: unknown }) =>
    open ? h("aside", {}, h("h2", {}, title), desc, children as never, footer as never) : null,
    DrawerSection: ({ children }: { children: unknown }) => h("section", {}, children as never) };
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
  mock.create.mockResolvedValue({ requestId: "REQ1", action: "CREATE", typeCode: "CODE1", at: 1 });
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

it("creates a named points type with the selected currency and conversion rate", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  const create = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes("新建积分类型"));
  expect(create).toBeDefined();
  await act(async () => create?.click());

  expect(host.textContent).toContain("新增积分类型");
  expect(host.textContent).not.toContain("草稿");
  expect(host.textContent).not.toContain("1 积分 = 1 分");
  expect(host.querySelector('[name="currency"]')).not.toBeNull();
  expect(host.querySelector('[name="unit"]')).not.toBeNull();
  expect(host.querySelector('[name="name"]')).not.toBeNull();
  expect(host.querySelector('[name="code"]')).not.toBeNull();
  expect(host.querySelector('[name="remark"]')).not.toBeNull();
  expect(host.querySelector('[name="market"]')).toBeNull();
  expect(host.querySelector('[name="days"]')).toBeNull();
  expect(host.querySelector('[name="points"]')).not.toBeNull();
  expect(host.querySelector('[name="minor"]')).toBeNull();

  (host.querySelector('[name="name"]') as HTMLInputElement).value = "会员积分";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "CODE1";
  (host.querySelector('[name="remark"]') as HTMLInputElement).value = "首期积分";
  await act(async () => host.querySelector("form#points-type-create")?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.create).toHaveBeenCalledWith(expect.objectContaining({
    command: { typeCode: "CODE1", name: "会员积分", currency: "CNY", rate: { pointsUnit: 1, minorUnit: 1 } },
    reason: "首期积分",
  }));
});

it("shows a clear message when the backend rejects a duplicate code or name", async () => {
  mock.create.mockRejectedValue(new Error("积分代码或名称已存在，请修改后重试。"));
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  const create = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes("新建积分类型"));
  await act(async () => create?.click());
  (host.querySelector('[name="name"]') as HTMLInputElement).value = "积分名称";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "DUPLICATE";
  (host.querySelector('[name="remark"]') as HTMLInputElement).value = "备注";
  await act(async () => host.querySelector("form#points-type-create")?.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  expect(await host.textContent).toContain("积分代码或名称已存在，请修改后重试。");
});

it("sends non-default currency and rate through the page without dropping the settings", async () => {
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => [...host.querySelectorAll("button")].find((b) => b.textContent?.includes("新建积分类型"))!.click());
  const currency = host.querySelector('[name="currency"]') as HTMLSelectElement;
  await act(async () => { currency.value = "KWD"; currency.dispatchEvent(new Event("change", { bubbles: true })); });
  const unit = host.querySelector('[name="unit"]') as HTMLSelectElement;
  await act(async () => { unit.value = "dinar"; unit.dispatchEvent(new Event("change", { bubbles: true })); });
  (host.querySelector('[name="name"]') as HTMLInputElement).value = "第纳尔积分";
  (host.querySelector('[name="code"]') as HTMLInputElement).value = "KWD_POINTS";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.create).toHaveBeenCalledWith(expect.objectContaining({
    command: { typeCode: "KWD_POINTS", name: "第纳尔积分", currency: "KWD", rate: { pointsUnit: 1000, minorUnit: 1000 } },
    reason: "创建积分类型",
  }));
});

it("shows the default and offers pause without affecting redemption", async () => {
  mock.types.mockResolvedValue([{ typeCode: "HX_TOKEN", name: "平台积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", earningPaused: false, orderDefault: true }]);
  mock.transition.mockResolvedValue({ requestId: "PAUSE1" });
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.textContent).toContain("订单默认");
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  const actions = host.querySelector('[name="action"]') as HTMLSelectElement;
  expect([...actions.options].map((o) => o.value)).toContain("PAUSE_EARNING");
  expect([...actions.options].map((o) => o.value)).not.toContain("SET_DEFAULT");
  actions.value = "PAUSE_EARNING";
  (host.querySelector('[name="reason"]') as HTMLInputElement).value = "暂停赠送";
  await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
  expect(mock.transition).toHaveBeenCalledWith(expect.objectContaining({ typeCode: "HX_TOKEN", action: "PAUSE_EARNING", reason: "暂停赠送" }));
  expect(host.textContent).toContain("已有积分仍可抵扣");
});

it("offers resume and explicit default selection for a paused independent type", async () => {
  mock.types.mockResolvedValue([{ typeCode: "OTHER", name: "其他积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE", earningPaused: true, orderDefault: false }]);
  await act(async () => root.render(h(QueryClientProvider, { client: query }, h(PointsTypesTab, { canEdit: true }))));
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
  expect(host.textContent).toContain("赠送已暂停");
  await act(async () => (host.querySelector('[role="button"]') as HTMLElement).click());
  const actions = host.querySelector('[name="action"]') as HTMLSelectElement;
  expect([...actions.options].map((o) => o.value)).toContain("RESUME_EARNING");
  expect([...actions.options].map((o) => o.value)).toContain("SET_DEFAULT");
});
